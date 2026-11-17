package app.falai.server

import app.falai.protocol.*
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Convite por link e download do app.
 *
 * - O servidor aprende o próprio endereço público (ex.: falai.falaifamilia.net) quando um pedido
 *   chega pela Cloudflare. Com isso, cada convite vira um link para mandar pelo WhatsApp.
 * - A página do convite tem um botão que abre o app já preenchido e, para quem ainda não tem
 *   o app, um botão para baixar. O arquivo do app é enviado ao servidor pelo próprio app do
 *   administrador (ver FalaiClient.publishAppIfNewer).
 */
class InviteSupport(private val store: Store, private val mediaDir: File, private val log: (String) -> Unit) {

    @Volatile private var host: String? = store.getMeta("public_host")

    /** Ex.: "https://falai.falaifamilia.net", ou null enquanto ninguém acessou pela internet. */
    fun publicBase(): String? = host?.let { "https://$it" }

    /** Chamado em todo pedido: se veio pela Cloudflare, guarda o endereço público. */
    fun noteRequest(call: ApplicationCall) {
        val cf = call.request.headers["CF-Connecting-IP"] ?: return
        if (cf.isBlank()) return
        val remote = call.request.origin.remoteHost
        if (remote != "127.0.0.1" && remote != "localhost" && remote != "::1" && remote != "0:0:0:0:0:0:0:1") return
        val h = call.request.headers[HttpHeaders.Host]?.substringBefore(':')?.lowercase()?.trim() ?: return
        if (!HOST.matches(h) || h == "localhost" || h.startsWith("127.") || !h.contains('.')) return
        if (h != host) {
            host = h
            store.setMeta("public_host", h)
            log("Endereço público: $h")
        }
    }

    /**
     * O administrador informa o endereço (aceita "falai.x.net", "https://falai.x.net/" etc.).
     * Devolve a base (https://...) ou null se o endereço não parecer válido.
     */
    fun setAddress(input: String): String? {
        val h = input.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':').trim()
        if (!HOST.matches(h) || h == "localhost" || !h.contains('.') || h.all { it.isDigit() || it == '.' }) return null
        if (h != host) {
            host = h
            store.setMeta("public_host", h)
            log("Endereço público definido: $h")
        }
        return publicBase()
    }

    fun linkFor(code: String): String? = publicBase()?.let { InviteLinks.page(it, code) }

    // ---------- Arquivo do app ----------

    val apkFile get() = File(mediaDir, "falai-app.apk")

    /** Avisa os aparelhos conectados quando chega uma versão nova do app. */
    var onApkPublished: ((AppApkInfo) -> Unit)? = null

    fun apkInfo(): AppApkInfo? {
        val v = store.getMeta("apk_version_code")?.toLongOrNull() ?: return null
        if (!apkFile.exists()) return null
        return AppApkInfo(v, store.getMeta("apk_version_name") ?: "", apkFile.length())
    }

    companion object {
        private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
        const val MAX_APK_BYTES = 300L * 1024 * 1024
    }
}

private fun sizeLabel(bytes: Long): String =
    if (bytes >= 1024 * 1024) "%.0f MB".format(bytes / 1048576.0) else "${maxOf(1, bytes / 1024)} KB"

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/**
 * "Passo a passo" dos avisos do Android ao instalar um app fora da loja, com desenhos simples de
 * cada tela e o botão certo destacado. Os avisos assustam, mas são normais para apps de família.
 */
internal fun installGuideHtml(): String {
    fun screen(title: String, text: String, buttons: List<Pair<String, Boolean>>, extra: String = "") =
        """<div class="phone"><div class="dlg"><b>$title</b><p>$text</p>$extra<div class="bts">""" +
            buttons.joinToString("") { (t, hi) -> """<span class="${if (hi) "hi" else ""}">$t</span>""" } +
            "</div></div></div>"
    fun step(n: Int, title: String, body: String, mock: String) =
        """<li><div class="gn">$n</div><div class="gt"><b>$title</b><p>$body</p>$mock</div></li>"""
    return """
<details class="guide" open>
<summary>🛡️ O Android vai mostrar alguns avisos. É normal! Veja onde tocar</summary>
<p class="small">O Falaí foi feito pela família e não está na Play Store, por isso o Android desconfia. Ele só fala com o nosso servidor. Os nomes dos botões mudam um pouco de um celular para outro.</p>
<ol>
${step(1, "Baixar o arquivo", "O navegador pode avisar que o arquivo \"pode ser perigoso\". Toque em <b>Baixar mesmo assim</b>.",
    screen("Quer baixar o falai.apk mesmo assim?", "Este tipo de arquivo pode ser perigoso.", listOf("Cancelar" to false, "Baixar mesmo assim" to true)))}
${step(2, "Abrir o arquivo", "Quando terminar de baixar, toque em <b>Abrir</b> (aparece embaixo da tela ou nas notificações).",
    screen("falai.apk", "Download concluído.", listOf("Abrir" to true)))}
${step(3, "Permitir desta fonte (só na primeira vez)", "Se aparecer que o celular \"não tem permissão para instalar apps desta fonte\", toque em <b>Configurações</b>, ligue a chave <b>Permitir desta fonte</b> e volte (seta ◁).",
    screen("Instalar apps desconhecidos", "Por segurança, seu telefone não tem permissão para instalar apps desta fonte.", listOf("Cancelar" to false, "Configurações" to true),
        """<div class="sw"><span>Permitir desta fonte</span><i></i></div>"""))}
${step(4, "Instalar", "Toque em <b>Instalar</b>.",
    screen("Falaí", "Quer instalar este app?", listOf("Cancelar" to false, "Instalar" to true)))}
${step(5, "Aviso do Play Protect (o que mais confunde)", "Se aparecer \"App não seguro\" ou \"desenvolvedor desconhecido\", <b>não toque em OK</b>. Toque em <b>Mais detalhes</b> (ou na setinha ⌄) e depois em <b>Instalar mesmo assim</b>.",
    screen("Bloqueado pelo Play Protect", "O desenvolvedor deste app não é conhecido. <u class=\"hi2\">Mais detalhes ⌄</u>", listOf("OK" to false, "Instalar mesmo assim" to true)))}
${step(6, "Pronto!", "Se perguntar se quer \"enviar o app para verificação\", tanto faz. Depois toque em <b>Abrir</b>. Das próximas vezes o Falaí se atualiza pelo botão <b>Atualizar</b>, sem esses avisos de download.",
    screen("Falaí", "App instalado.", listOf("Concluído" to false, "Abrir" to true)))}
</ol>
</details>"""
}

internal const val INSTALL_GUIDE_CSS = """
 .guide{background:#fff;border-radius:14px;padding:14px 16px;margin:14px 0;box-shadow:0 1px 3px rgba(0,0,0,.08)}
 .guide summary{font-weight:700;cursor:pointer;color:#136F7A;font-size:16px;line-height:1.35}
 .guide ol{list-style:none;padding:0;margin:10px 0 0}
 .guide li{display:flex;gap:12px;margin:16px 0}
 .gn{flex:none;width:28px;height:28px;border-radius:50%;background:#136F7A;color:#fff;display:flex;align-items:center;justify-content:center;font-weight:700}
 .gt{flex:1;min-width:0}.gt p{margin:4px 0 8px;font-size:15px;line-height:1.4}
 .phone{background:#3a4446;border-radius:16px;padding:14px 10px;max-width:290px}
 .dlg{background:#fff;border-radius:14px;padding:14px;font-size:14px}
 .dlg p{margin:6px 0 10px;color:#555;font-size:13px}
 .bts{display:flex;justify-content:flex-end;gap:6px;flex-wrap:wrap}
 .bts span{padding:7px 10px;border-radius:16px;color:#1a73e8;font-weight:600;font-size:13px}
 .bts span.hi,.hi2{outline:3px solid #E8875A;outline-offset:1px;border-radius:16px;animation:pisca 1.4s infinite}
 .hi2{text-decoration:none;color:#1a73e8;font-weight:600;padding:0 4px}
 .sw{display:flex;align-items:center;justify-content:space-between;background:#f1f3f4;border-radius:10px;padding:8px 10px;margin:0 0 10px;font-size:13px;outline:3px solid #E8875A;animation:pisca 1.4s infinite}
 .sw i{width:34px;height:18px;border-radius:9px;background:#136F7A;position:relative}
 .sw i:after{content:"";position:absolute;right:2px;top:2px;width:14px;height:14px;border-radius:50%;background:#fff}
 @keyframes pisca{50%{outline-color:rgba(232,135,90,.25)}}
"""

/** Página para baixar o Falaí sem convite (quem já tem conta, ou para reinstalar). */
internal fun appPage(apk: AppApkInfo?): String = """<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#136F7A">
<link rel="icon" type="image/svg+xml" href="/web/icon.svg">
<title>Baixar o Falaí</title>
<style>
 body{margin:0;font-family:system-ui,-apple-system,Roboto,sans-serif;background:#ECF3F3;color:#1F2A2C}
 main{max-width:480px;margin:0 auto;padding:28px 18px 40px}
 .brand{display:flex;align-items:center;gap:10px;margin:0 0 22px;font-weight:800;font-size:22px;color:#136F7A}
 .brand img{width:44px;height:44px}
 h1{font-size:24px;line-height:1.25;margin:0 0 8px;color:#136F7A}
 .btn{display:block;box-sizing:border-box;width:100%;margin:12px 0 6px;padding:14px;border-radius:12px;background:#136F7A;color:#fff;text-align:center;text-decoration:none;font-weight:600;font-size:17px}
 .small{display:block;font-size:13px;color:#6E625C}
 $INSTALL_GUIDE_CSS
</style></head>
<body><main><div class="brand"><img src="/web/icon.svg" alt="">Falaí</div>
<h1>Baixar o Falaí</h1>
${if (apk != null) """<a class="btn" href="/app/falai.apk">Baixar o Falaí ${esc(apk.versionName)} (${sizeLabel(apk.size)})</a>
<p class="small">Se você já usou o Falaí neste celular, ele entra sozinho na sua conta. Se não, peça um convite.</p>
${installGuideHtml()}""" else "<p>O app ainda não está disponível aqui. Peça para o administrador abrir o Falaí no celular dele.</p>"}
</main></body></html>"""

/** Página simples, em português, que funciona em qualquer celular. */
internal fun invitePage(
    preview: Store.InvitePreview,
    code: String,
    base: String,
    apk: AppApkInfo?,
): String {
    val appUri = InviteLinks.appUri(base, code, forExistingUser = preview.forUserName != null, name = preview.suggestedName)
    val pageUrl = InviteLinks.page(base, code)
    // "intent:" abre o app no Chrome; se o app não estiver instalado, volta para esta página, na parte de baixar.
    val intentUri = "intent://convite?" + appUri.substringAfter('?') +
        "#Intent;scheme=falai;package=app.falai;S.browser_fallback_url=" +
        java.net.URLEncoder.encode("$pageUrl#baixar", "UTF-8") + ";end"
    val who = preview.inviterName?.let { "<b>${esc(it)}</b> convidou ${preview.suggestedName?.let { n -> "você, ${esc(n)}," } ?: "você"}" } ?: "Você foi convidado"
    val body = when (preview.status) {
        "ok" -> """
            <h1>$who para o Falaí 💬</h1>
            <p class="sub">O mensageiro da família.</p>
            ${if (preview.forUserName != null) "<p>Este convite liga <b>mais um aparelho</b> de ${esc(preview.forUserName)}.</p>" else ""}
            ${if (apk != null) """<div class="step" id="baixar"><div>
              <b>Ainda não tem o Falaí?</b>
              <a class="btn" href="/convite/${esc(code)}/falai.apk" onclick="guardarConvite()">Baixar e instalar o Falaí (${sizeLabel(apk.size)})</a>
              <p class="small">Toque em <b>Instalar</b> e depois em <b>Abrir</b>. O Falaí já vem com o seu convite: ${if (preview.suggestedName != null) "é só confirmar o seu nome" else "é só escrever o seu nome"}.</p>
            </div></div>
            ${installGuideHtml()}"""
            else """<div class="step" id="baixar"><div><b>Ainda não tem o Falaí?</b><br>Peça o arquivo do app para quem te convidou, instale e volte aqui.</div></div>"""}
            <div class="step"><div>
              <b>Já tem o Falaí?</b>
              <a class="btn ${if (apk != null) "outline" else ""}" href="${esc(intentUri)}">Abrir o convite no Falaí</a>
              <a class="small" href="${esc(appUri)}">Não abriu? Toque aqui</a>
            </div></div>
            <p class="code">Código: <b>${esc(code.chunked(5).joinToString(" "))}</b><br>Vale por 7 dias e é só seu: depois de usado, não serve para mais ninguém.</p>
        """
        "used" -> "<h1>Este convite já foi usado</h1><p>Peça um convite novo para quem te convidou.</p>"
        "expired" -> "<h1>Este convite venceu</h1><p>O convite vale 7 dias. Peça um novo para quem te convidou.</p>"
        else -> "<h1>Convite não encontrado</h1><p>Confira se o link chegou inteiro, ou peça um novo.</p>"
    }
    return """<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta property="og:title" content="Convite para o Falaí">
<meta property="og:description" content="O mensageiro da família. Toque para entrar.">
<meta property="og:image" content="$base/web/icon-512.png">
<meta name="theme-color" content="#136F7A">
<link rel="icon" type="image/svg+xml" href="/web/icon.svg">
<link rel="icon" type="image/png" sizes="32x32" href="/web/favicon-32.png">
<link rel="apple-touch-icon" href="/web/apple-touch-icon.png">
<title>Convite para o Falaí</title>
<style>
 body{margin:0;font-family:system-ui,-apple-system,Roboto,sans-serif;background:#ECF3F3;color:#1F2A2C}
 main{max-width:480px;margin:0 auto;padding:28px 18px 40px}
 .brand{display:flex;align-items:center;gap:10px;margin:0 0 22px;font-weight:800;font-size:22px;color:#136F7A}
 .brand img{width:44px;height:44px}
 h1{font-size:24px;line-height:1.25;margin:0 0 4px;color:#136F7A}
 .sub{margin:0 0 20px;color:#6E625C}
 .step{display:flex;gap:12px;background:#fff;border-radius:14px;padding:16px;margin:12px 0;box-shadow:0 1px 3px rgba(0,0,0,.08)}
 .n{flex:none;width:28px;height:28px;border-radius:50%;background:#136F7A;color:#fff;display:flex;align-items:center;justify-content:center;font-weight:700}
 .btn{display:block;box-sizing:border-box;width:100%;margin:12px 0 6px;padding:14px;border-radius:12px;background:#136F7A;color:#fff;text-align:center;text-decoration:none;font-weight:600;font-size:17px}
 .btn.outline{background:#fff;color:#136F7A;border:2px solid #136F7A}
 .small{display:block;font-size:13px;color:#6E625C}
 .code{margin-top:22px;font-size:14px;color:#6E625C;text-align:center}
 $INSTALL_GUIDE_CSS
</style></head>
<body><main><div class="brand"><img src="/web/icon.svg" alt="">Falaí</div>$body</main>
<script>
// Guarda o link do convite na área de transferência: ao abrir pela primeira vez, o app encontra o convite sozinho.
function guardarConvite(){try{navigator.clipboard.writeText(location.origin+location.pathname)}catch(e){}}
</script></body></html>"""
}

/**
 * Instalador do "ligar sozinho" no Termux do celular-servidor. No Termux:
 *   curl -s http://127.0.0.1:8443/setup/tunel | sh
 * Cria o script do Termux:Boot que liga a ponte da Cloudflare quando o celular liga
 * (e religa se ela cair).
 */
internal val TERMUX_SETUP_SCRIPT = """#!/data/data/com.termux/files/usr/bin/sh
mkdir -p ~/.termux/boot
cat > ~/.termux/boot/falai-tunel <<'FIM'
#!/data/data/com.termux/files/usr/bin/sh
# Liga a ponte da Cloudflare para o servidor do Falaí e religa se cair.
termux-wake-lock
while true; do
  if [ -f ~/falai-tunel.log ] && [ "${'$'}(wc -c < ~/falai-tunel.log)" -gt 1000000 ]; then : > ~/falai-tunel.log; fi
  echo "${'$'}(date) ligando a ponte" >> ~/falai-tunel.log
  cloudflared tunnel run --url http://127.0.0.1:8443 falai >> ~/falai-tunel.log 2>&1
  sleep 10
done
FIM
chmod +x ~/.termux/boot/falai-tunel
echo ""
echo "Pronto! A ponte do Falai vai ligar sozinha quando o celular ligar."
echo "Para testar agora: reinicie o celular."
"""

/** Manda o arquivo do app com [stamp] carimbado dentro (ou o arquivo como está, se não der para carimbar). */
private suspend fun respondApk(call: ApplicationCall, support: InviteSupport, stamp: String) {
    val f = support.apkFile
    if (support.apkInfo() == null) {
        call.respondText("O arquivo do app ainda não foi enviado ao servidor.", status = HttpStatusCode.NotFound)
        return
    }
    call.response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "falai.apk").toString(),
    )
    call.response.header(HttpHeaders.CacheControl, "no-store")
    val apkType = ContentType("application", "vnd.android.package-archive")
    val stamped = withContext(Dispatchers.IO) { ApkStamp.stamp(f, stamp) }
    if (stamped == null) {
        call.respondFile(f)
        return
    }
    call.respondOutputStream(apkType, HttpStatusCode.OK, stamped.size) {
        withContext(Dispatchers.IO) { stamped.writeTo(this@respondOutputStream) }
    }
}

fun Route.inviteRoutes(store: Store, support: InviteSupport, limiter: FailureLimiter, clientKey: (ApplicationCall) -> String) {
    get("/convite/{code}") {
        val key = clientKey(call)
        val code = (call.parameters["code"] ?: "").uppercase().filter { it.isLetterOrDigit() }.take(32)
        val preview = if (limiter.isBlocked(key)) Store.InvitePreview("invalid", null, null) else store.invitePreview(code)
        if (preview.status == "invalid") limiter.recordFailure(key)
        val base = support.publicBase() ?: run {
            val h = call.request.headers[HttpHeaders.Host] ?: "127.0.0.1"
            "http://$h"
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(invitePage(preview, code, base, support.apkInfo()), ContentType.Text.Html.withParameter("charset", "utf-8"))
    }

    get("/setup/tunel") {
        // Só para o próprio celular-servidor (não pela internet).
        val remote = call.request.origin.remoteHost
        if (call.request.headers["CF-Connecting-IP"] != null || (remote != "127.0.0.1" && remote != "localhost" && remote != "::1" && remote != "0:0:0:0:0:0:0:1")) {
            call.respondText("Só no próprio celular-servidor.", status = HttpStatusCode.Forbidden)
            return@get
        }
        call.respondText(TERMUX_SETUP_SCRIPT, ContentType.Text.Plain)
    }

    fun baseOf(call: ApplicationCall) = support.publicBase() ?: "http://" + (call.request.headers[HttpHeaders.Host] ?: "127.0.0.1")

    // Página para baixar o app (com o passo a passo dos avisos do Android).
    get("/app") {
        call.respondText(appPage(support.apkInfo()), ContentType.Text.Html)
    }

    // App "puro": já sabe o endereço do servidor (se o aparelho for conhecido, entra sozinho).
    get("/app/falai.apk") {
        respondApk(call, support, InviteLinks.serverUri(baseOf(call)))
    }

    // App com o convite dentro: abre já com o convite, só pede o nome.
    get("/convite/{code}/falai.apk") {
        val key = clientKey(call)
        val code = (call.parameters["code"] ?: "").uppercase().filter { it.isLetterOrDigit() }.take(32)
        val preview = if (limiter.isBlocked(key)) Store.InvitePreview("invalid", null, null) else store.invitePreview(code)
        if (preview.status == "invalid") limiter.recordFailure(key)
        val base = baseOf(call)
        val text = if (preview.status == "ok") {
            InviteLinks.appUri(base, code, forExistingUser = preview.forUserName != null, name = preview.suggestedName)
        } else InviteLinks.serverUri(base)
        respondApk(call, support, text)
    }

    get("/api/app-apk") {
        val info = support.apkInfo()
        val json = if (info == null) "null" else FalaiJson.encodeToString(AppApkInfo.serializer(), info)
        call.respondText(json, ContentType.Application.Json)
    }

    /** Só o administrador: envia o arquivo do app para quem for convidado poder baixar. */
    put("/api/app-apk") {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        val device = token?.takeIf { it.isNotEmpty() }?.let(store::authenticate)
        if (device == null || !device.user.isAdmin) {
            call.respondText(
                FalaiJson.encodeToString(ErrorResponse.serializer(), ErrorResponse("forbidden", "Só o administrador envia o app.")),
                ContentType.Application.Json, HttpStatusCode.Forbidden,
            )
            return@put
        }
        val versionCode = call.request.queryParameters["versionCode"]?.toLongOrNull()
        val versionName = call.request.queryParameters["versionName"]?.take(40) ?: ""
        if (versionCode == null) {
            call.respondText(
                FalaiJson.encodeToString(ErrorResponse.serializer(), ErrorResponse("invalid", "Faltou a versão.")),
                ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            return@put
        }
        val tmp = File(support.apkFile.path + ".part")
        val ok = withContext(Dispatchers.IO) {
            support.apkFile.parentFile?.mkdirs()
            var total = 0L
            call.receiveChannel().toInputStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > InviteSupport.MAX_APK_BYTES) return@withContext false
                        out.write(buf, 0, n)
                    }
                }
            }
            // Um APK é um ZIP: começa com "PK".
            val head = tmp.inputStream().use { val b = ByteArray(2); it.read(b); b }
            total > 0 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
        if (!ok) {
            tmp.delete()
            call.respondText(
                FalaiJson.encodeToString(ErrorResponse.serializer(), ErrorResponse("invalid", "Arquivo do app inválido ou grande demais.")),
                ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            return@put
        }
        support.apkFile.delete()
        if (!tmp.renameTo(support.apkFile)) {
            call.respondText(
                FalaiJson.encodeToString(ErrorResponse.serializer(), ErrorResponse("io", "Não consegui salvar o arquivo do app.")),
                ContentType.Application.Json, HttpStatusCode.InternalServerError,
            )
            return@put
        }
        store.setMeta("apk_version_code", versionCode.toString())
        store.setMeta("apk_version_name", versionName)
        val info = support.apkInfo()!!
        support.onApkPublished?.invoke(info)
        call.respondText(FalaiJson.encodeToString(AppApkInfo.serializer(), info), ContentType.Application.Json)
    }
}
