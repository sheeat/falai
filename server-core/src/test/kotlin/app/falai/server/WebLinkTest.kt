package app.falai.server

import app.falai.protocol.*

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Falaí no computador: QR Code na página, aprovação pelo celular, cookie no navegador. */
class WebLinkTest {
    private lateinit var dir: File
    private lateinit var server: FalaiServer
    private lateinit var store: Store
    private lateinit var http: HttpClient
    private var port = 0
    private lateinit var adminCode: String
    private val base get() = "http://127.0.0.1:$port"

    @BeforeTest
    fun setUp() = runBlocking {
        dir = Files.createTempDirectory("falai-web").toFile()
        store = Store(JdbcDb(File(dir, "t.db").path))
        server = FalaiServer(store, ServerConfig(port = 0, mediaDir = File(dir, "media")), log = {})
        adminCode = server.start()!!
        port = server.boundPort()
        http = HttpClient(CIO) { install(WebSockets) }
    }

    @AfterTest
    fun tearDown() {
        http.close()
        server.stop()
        dir.deleteRecursively()
    }

    private suspend fun frames(token: String? = null, cookie: String? = null, origin: String? = null) =
        http.webSocketSession("ws://127.0.0.1:$port/ws") {
            token?.let { header("Authorization", "Bearer $it") }
            cookie?.let { header("Cookie", "$WEB_COOKIE=$it") }
            origin?.let { header("Origin", it) }
        }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.next(): ServerFrame = withTimeout(5000) {
        while (true) {
            val f = incoming.receive()
            if (f is Frame.Text) return@withTimeout FalaiJson.decodeFromString(ServerFrame.serializer(), f.readText())
        }
        @Suppress("UNREACHABLE_CODE") error("x")
    }

    @Test
    fun `codigo do QR - le o link e o codigo digitado`() {
        assertEquals("ABCD2345", WebLinkCodes.parse("falai://web?c=ABCD2345"))
        assertEquals("ABCD2345", WebLinkCodes.parse(" abcd-2345 "))
        assertNull(WebLinkCodes.parse("ABCD234"))
        assertNull(WebLinkCodes.parse("ABCD2341")) // "1" não existe no alfabeto
        assertTrue(qrSvg("falai://web?c=ABCD2345").startsWith("<svg"))
        assertEquals("Chrome no Windows", browserName("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36"))
    }

    @Test
    fun `ligar o navegador pelo QR Code e usar com cookie`() = runBlocking {
        // Celular do admin pareado.
        val phone = FalaiJson.decodeFromString(
            PairResponse.serializer(),
            http.post("$base/api/pair") { setBody(FalaiJson.encodeToString(PairRequest.serializer(), PairRequest(adminCode, "Celular", "Edson"))) }.bodyAsText(),
        )
        // Página sem cookie: precisa do QR Code.
        assertEquals(HttpStatusCode.Unauthorized, http.get("$base/api/web/me").status)
        assertTrue(http.get("$base/web").bodyAsText().contains("<html"))

        val link = FalaiJson.decodeFromString(
            WebLinkResponse.serializer(),
            http.post("$base/api/web/link") { header("User-Agent", "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0") }.bodyAsText(),
        )
        assertTrue(link.qrSvg.startsWith("<svg"))
        suspend fun status() = http.get("$base/api/web/link/${link.id}?s=${link.secret}")
        assertEquals("waiting", FalaiJson.decodeFromString(WebLinkStatus.serializer(), status().bodyAsText()).status)
        // Segredo errado: não conta nada.
        assertEquals("expired", FalaiJson.decodeFromString(WebLinkStatus.serializer(), http.get("$base/api/web/link/${link.id}?s=errado").bodyAsText()).status)

        // O celular lê o QR Code e aprova.
        val ws = frames(token = phone.token)
        assertIs<HelloFrame>(ws.next())
        ws.send(Frame.Text(FalaiJson.encodeToString(ClientFrame.serializer(), ApproveWebFrame("r1", "falai://web?c=" + link.code))))
        val ok = ws.next()
        assertIs<WebApprovedFrame>(ok, "$ok")
        assertEquals("Chrome no Windows", ok.deviceName)
        // O mesmo código não serve duas vezes.
        ws.send(Frame.Text(FalaiJson.encodeToString(ClientFrame.serializer(), ApproveWebFrame("r2", link.code))))
        val again = ws.next()
        assertIs<ErrorFrame>(again)
        assertEquals("not_found", again.code)

        // A página pergunta de novo e recebe a chave no cookie (o JavaScript não consegue ler).
        val r = status()
        assertEquals("approved", FalaiJson.decodeFromString(WebLinkStatus.serializer(), r.bodyAsText()).status)
        val setCookie = assertNotNull(r.headers["Set-Cookie"])
        assertTrue("HttpOnly" in setCookie && "SameSite=Strict" in setCookie, setCookie)
        val cookie = setCookie.substringAfter("$WEB_COOKIE=").substringBefore(';')
        // Uma vez entregue, o pedido some.
        assertEquals("expired", FalaiJson.decodeFromString(WebLinkStatus.serializer(), status().bodyAsText()).status)

        val me = http.get("$base/api/web/me") { header("Cookie", "$WEB_COOKIE=$cookie") }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("Edson", FalaiJson.decodeFromString(WebMe.serializer(), me.bodyAsText()).name)

        // Conversa pelo navegador: com o Origin da própria página funciona...
        val web = frames(cookie = cookie, origin = "http://127.0.0.1:$port")
        val hello = web.next()
        assertIs<HelloFrame>(hello)
        assertEquals(phone.userId, hello.user.id)
        // ...vindo de outro site, não.
        val evil = frames(cookie = cookie, origin = "https://site-ruim.com")
        val closed = withTimeout(5000) { evil.closeReason.await() }
        assertEquals(4001, closed?.code?.toInt())

        // Sair: a chave para de valer.
        val out = http.post("$base/api/web/logout") { header("Cookie", "$WEB_COOKIE=$cookie"); header("Origin", "http://127.0.0.1:$port") }
        assertEquals(HttpStatusCode.OK, out.status)
        assertEquals(HttpStatusCode.Unauthorized, http.get("$base/api/web/me") { header("Cookie", "$WEB_COOKIE=$cookie") }.status)
    }

    @Test
    fun `navegador baixa e envia arquivos com o cookie`() = runBlocking {
        val phone = FalaiJson.decodeFromString(
            PairResponse.serializer(),
            http.post("$base/api/pair") { setBody(FalaiJson.encodeToString(PairRequest.serializer(), PairRequest(adminCode, "Celular", "Edson"))) }.bodyAsText(),
        )
        val (_, cookie) = store.createLinkedDevice(phone.userId, "Chrome no Windows")
        val bytes = "foto de teste".toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val origin = "http://127.0.0.1:$port"
        // Envio sem Origin (outro site tentando): recusado.
        val semOrigin = http.post("$base/api/media") {
            header("Cookie", "$WEB_COOKIE=$cookie")
            setBody(FalaiJson.encodeToString(MediaCreateRequest.serializer(), MediaCreateRequest(bytes.size.toLong(), sha, "image/png", "a.png")))
        }
        assertEquals(HttpStatusCode.Unauthorized, semOrigin.status, semOrigin.bodyAsText())
        val created = FalaiJson.decodeFromString(MediaStatus.serializer(), http.post("$base/api/media") {
            header("Cookie", "$WEB_COOKIE=$cookie"); header("Origin", origin)
            setBody(FalaiJson.encodeToString(MediaCreateRequest.serializer(), MediaCreateRequest(bytes.size.toLong(), sha, "image/png", "a.png")))
        }.bodyAsText())
        val done = http.put("$base/api/media/${created.mediaId}?offset=0") {
            header("Cookie", "$WEB_COOKIE=$cookie"); header("Origin", origin)
            setBody(bytes)
        }
        assertTrue(FalaiJson.decodeFromString(MediaStatus.serializer(), done.bodyAsText()).complete, done.bodyAsText())
        // Baixar como <img> (sem Origin): vem com o tipo certo.
        val get = http.get("$base/api/media/${created.mediaId}") { header("Cookie", "$WEB_COOKIE=$cookie") }
        assertEquals(HttpStatusCode.OK, get.status)
        assertEquals("image/png", get.headers["Content-Type"])
        kotlin.test.assertContentEquals(bytes, get.readRawBytes())
        // Pedaço do vídeo (Range) também funciona.
        val part = http.get("$base/api/media/${created.mediaId}") { header("Cookie", "$WEB_COOKIE=$cookie"); header("Range", "bytes=0-3") }
        assertEquals(HttpStatusCode.PartialContent, part.status)
        assertEquals("foto", part.bodyAsText())
        val dl = http.get("$base/api/media/${created.mediaId}?download=1") { header("Cookie", "$WEB_COOKIE=$cookie") }
        assertTrue(dl.headers["Content-Disposition"]!!.contains("a.png"))
    }
}
