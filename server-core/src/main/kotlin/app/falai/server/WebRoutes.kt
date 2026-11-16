package app.falai.server

import app.falai.protocol.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Nome do cookie que guarda a chave do navegador (o JavaScript da página não consegue ler). */
const val WEB_COOKIE = "falai_web"

/** Quanto tempo o QR Code da página vale. */
const val WEB_LINK_VALID_MS = 5 * 60_000L

/**
 * A chave do aparelho: no app, vem no cabeçalho Authorization; no navegador, no cookie.
 * Com cookie, só aceita pedidos da própria página (o navegador manda "Origin" igual ao endereço).
 */
fun tokenFrom(call: ApplicationCall, checkOrigin: Boolean = false): String? {
    call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        ?.takeIf { it.isNotEmpty() }?.let { return it }
    val cookie = call.request.cookies[WEB_COOKIE]?.takeIf { it.isNotEmpty() } ?: return null
    if (checkOrigin) {
        val origin = call.request.headers[HttpHeaders.Origin] ?: return null
        val host = call.request.headers[HttpHeaders.Host] ?: return null
        val originHost = origin.substringAfter("://").substringBefore('/')
        if (!originHost.equals(host, ignoreCase = true)) return null
    }
    return cookie
}

/** Pedidos de "Falaí no computador" esperando o celular ler o QR Code. Ficam só na memória. */
class WebLinks(private val clock: () -> Long = System::currentTimeMillis) {
    class Link(
        val id: String,
        val secret: String,
        val code: String,
        val browser: String,
        val createdAt: Long,
    ) {
        @Volatile var token: String? = null
        @Volatile var approvedBy: String? = null
    }

    private val random = SecureRandom()
    private val byId = ConcurrentHashMap<String, Link>()

    private fun randomString(n: Int, alphabet: String) =
        (1..n).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

    private fun cleanup() {
        val cutoff = clock() - WEB_LINK_VALID_MS
        byId.values.removeIf { it.createdAt < cutoff }
    }

    fun create(browser: String): Link {
        cleanup()
        // Muitos pedidos abertos ao mesmo tempo = alguém tentando adivinhar: recusa.
        if (byId.size >= 200) throw FalaiException("rate_limited", "Muitos pedidos. Tente de novo em alguns minutos.")
        val link = Link(
            id = randomString(24, "abcdefghijklmnopqrstuvwxyz0123456789"),
            secret = randomString(32, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"),
            code = randomString(8, WebLinkCodes.ALPHABET),
            browser = browser,
            createdAt = clock(),
        )
        byId[link.id] = link
        return link
    }

    fun get(id: String, secret: String): Link? {
        cleanup()
        val l = byId[id] ?: return null
        return l.takeIf { java.security.MessageDigest.isEqual(it.secret.toByteArray(), secret.toByteArray()) }
    }

    fun byCode(code: String): Link? {
        cleanup()
        return byId.values.firstOrNull { it.code == code && it.token == null }
    }

    fun remove(id: String) {
        byId.remove(id)
    }
}

@Serializable
data class WebLinkResponse(val id: String, val secret: String, val code: String, val qrSvg: String, val validMs: Long)

@Serializable
data class WebLinkStatus(val status: String)

@Serializable
data class WebMe(val userId: String, val name: String, val deviceId: String)

/** "Chrome no Windows", "Firefox no Mac"... a partir do User-Agent, para o nome do aparelho. */
fun browserName(userAgent: String?): String {
    val ua = userAgent ?: return "Navegador"
    val b = when {
        "Edg/" in ua -> "Edge"
        "OPR/" in ua || "Opera" in ua -> "Opera"
        "Firefox/" in ua -> "Firefox"
        "Chrome/" in ua -> "Chrome"
        "Safari/" in ua -> "Safari"
        else -> "Navegador"
    }
    val os = when {
        "Windows" in ua -> "Windows"
        "Android" in ua -> "Android"
        "iPhone" in ua || "iPad" in ua -> "iPhone"
        "Mac OS X" in ua || "Macintosh" in ua -> "Mac"
        "Linux" in ua -> "Linux"
        else -> null
    }
    return if (os != null) "$b no $os" else b
}

/** QR Code em SVG (preto no branco, com margem), para a página mostrar sem biblioteca nenhuma. */
fun qrSvg(text: String): String {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
    )
    val n = matrix.width
    val quiet = 4
    val size = n + quiet * 2
    val path = StringBuilder()
    for (y in 0 until n) {
        var x = 0
        while (x < n) {
            if (matrix.get(x, y)) {
                val start = x
                while (x < n && matrix.get(x, y)) x++
                path.append("M${start + quiet} ${y + quiet}h${x - start}v1h-${x - start}z")
            } else x++
        }
    }
    return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $size $size" shape-rendering="crispEdges">""" +
        """<rect width="$size" height="$size" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
}

private fun isHttps(call: ApplicationCall): Boolean =
    call.request.headers["X-Forwarded-Proto"].equals("https", ignoreCase = true) ||
        call.request.headers["CF-Visitor"]?.contains("https") == true

private suspend fun ApplicationCall.json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(text, ContentType.Application.Json, status)

private suspend fun ApplicationCall.error(e: FalaiException, status: HttpStatusCode) =
    json(FalaiJson.encodeToString(ErrorResponse.serializer(), ErrorResponse(e.code, e.message ?: "")), status)

fun Route.webRoutes(store: Store, links: WebLinks, limiter: FailureLimiter, clientKey: (ApplicationCall) -> String, log: (String) -> Unit) {
    get("/web") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; " +
                "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self' ws: wss:; " +
                "frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        )
        call.respondText(WEB_PAGE, ContentType.Text.Html.withParameter("charset", "utf-8"))
    }

    // Quem sou eu? (o navegador já está ligado ou precisa mostrar o QR Code)
    get("/api/web/me") {
        val device = tokenFrom(call)?.let(store::authenticate)
        if (device == null) {
            call.error(FalaiException("unauthorized", "Navegador não ligado."), HttpStatusCode.Unauthorized)
        } else {
            call.json(FalaiJson.encodeToString(WebMe.serializer(), WebMe(device.user.id, device.user.name, device.deviceId)))
        }
    }

    // A página pede um QR Code novo.
    post("/api/web/link") {
        val key = clientKey(call)
        if (limiter.isBlocked(key)) {
            call.error(FalaiException("rate_limited", "Muitas tentativas. Espere alguns minutos."), HttpStatusCode.TooManyRequests)
            return@post
        }
        limiter.recordFailure(key) // conta pedidos: no máximo alguns por janela, por endereço
        val link = try {
            links.create(browserName(call.request.headers[HttpHeaders.UserAgent]))
        } catch (e: FalaiException) {
            call.error(e, HttpStatusCode.TooManyRequests)
            return@post
        }
        call.json(
            FalaiJson.encodeToString(
                WebLinkResponse.serializer(),
                WebLinkResponse(link.id, link.secret, link.code, qrSvg(WebLinkCodes.qrText(link.code)), WEB_LINK_VALID_MS),
            )
        )
    }

    // A página pergunta se o celular já aprovou. Quando aprovou, entrega a chave num cookie.
    get("/api/web/link/{id}") {
        val link = links.get(call.parameters["id"].orEmpty(), call.request.queryParameters["s"].orEmpty())
        val token = link?.token
        val status = when {
            link == null -> "expired"
            token == null -> "waiting"
            else -> {
                links.remove(link.id)
                val secure = if (isHttps(call)) "; Secure" else ""
                call.response.header(
                    HttpHeaders.SetCookie,
                    "$WEB_COOKIE=$token; Path=/; Max-Age=${365 * 24 * 3600}; HttpOnly; SameSite=Strict$secure",
                )
                "approved"
            }
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.json(FalaiJson.encodeToString(WebLinkStatus.serializer(), WebLinkStatus(status)))
    }

    // "Sair" no navegador: desliga este aparelho e apaga o cookie.
    post("/api/web/logout") {
        val device = tokenFrom(call, checkOrigin = true)?.let(store::authenticate)
        if (device != null) {
            store.revokeDevice(device.deviceId)
            log("Navegador desligado: ${device.user.name} (${device.deviceId.take(8)})")
        }
        call.response.header(HttpHeaders.SetCookie, "$WEB_COOKIE=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict")
        call.json("""{"ok":true}""")
    }
}
