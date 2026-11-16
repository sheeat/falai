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
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.close
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InviteLinkTest {
    private lateinit var dir: File
    private lateinit var server: FalaiServer
    private lateinit var store: Store
    private lateinit var http: HttpClient
    private var port = 0
    private lateinit var adminCode: String

    private val base get() = "http://127.0.0.1:$port"

    @BeforeTest
    fun setUp() = runBlocking {
        dir = Files.createTempDirectory("falai-invite").toFile()
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

    private suspend fun pair(code: String, name: String?): PairResponse {
        val r = http.post("$base/api/pair") {
            setBody(FalaiJson.encodeToString(PairRequest.serializer(), PairRequest(code, "Aparelho", name)))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return FalaiJson.decodeFromString(PairResponse.serializer(), r.bodyAsText())
    }

    private suspend fun invite(token: String, forUserId: String? = null): InviteFrame {
        val ws = http.webSocketSession("ws://127.0.0.1:$port/ws") { header("Authorization", "Bearer $token") }
        try {
            ws.send(Frame.Text(FalaiJson.encodeToString(ClientFrame.serializer(), CreateInviteFrame("r", forUserId))))
            return withTimeout(5000) {
                while (true) {
                    val f = ws.incoming.receive()
                    if (f is Frame.Text) {
                        val fr = FalaiJson.decodeFromString(ServerFrame.serializer(), f.readText())
                        if (fr is InviteFrame) return@withTimeout fr
                    }
                }
                @Suppress("UNREACHABLE_CODE") error("x")
            }
        } finally {
            ws.close()
        }
    }

    /** Pedido como chega pela Cloudflare: Host público e o IP real no cabeçalho. */
    private suspend fun viaCloudflare(path: String) = http.get("$base$path") {
        header("Host", "falai.falaifamilia.net")
        header("CF-Connecting-IP", "203.0.113.9")
    }

    @Test
    fun `servidor aprende o endereco publico e o convite vira link`() = runBlocking {
        val admin = pair(adminCode, "Edson")
        // Antes de alguém chegar pela internet, não há link (só o código).
        assertNull(invite(admin.token).link)
        // Pedido falso de "Cloudflare" vindo de fora (não do próprio aparelho) é ignorado? Aqui vem de 127.0.0.1, então vale.
        viaCloudflare("/health")
        assertEquals("https://falai.falaifamilia.net", server.publicBase())
        val inv = invite(admin.token)
        assertEquals("https://falai.falaifamilia.net/convite/${inv.code}", inv.link)
        // Guardado: sobrevive a reiniciar o servidor.
        assertEquals("falai.falaifamilia.net", store.getMeta("public_host"))
    }

    @Test
    fun `host estranho nao e aprendido`() = runBlocking {
        http.get("$base/health") { header("Host", "evil host<script>"); header("CF-Connecting-IP", "1.2.3.4") }
        http.get("$base/health") { header("Host", "localhost"); header("CF-Connecting-IP", "1.2.3.4") }
        http.get("$base/health") // sem Cloudflare
        assertNull(server.publicBase())
    }

    @Test
    fun `pagina do convite mostra quem convidou e o botao que abre o app`() = runBlocking {
        val admin = pair(adminCode, "Edson")
        viaCloudflare("/health")
        val inv = invite(admin.token)
        val page = viaCloudflare("/convite/${inv.code}")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue(html.contains("<b>Edson</b> convidou você"), html)
        assertTrue(html.contains("intent://convite?s=https%3A%2F%2Ffalai.falaifamilia.net&amp;c=${inv.code}"), html)
        assertTrue(html.contains("package=app.falai"))
        assertTrue(html.contains("Peça o arquivo do app")) // ainda não há APK no servidor
        // Usado: a página avisa.
        pair(inv.code, "Ana")
        assertTrue(viaCloudflare("/convite/${inv.code}").bodyAsText().contains("já foi usado"))
        // Inventado: avisa também.
        assertTrue(viaCloudflare("/convite/ABCDEFGHJK").bodyAsText().contains("não encontrado"))
    }

    @Test
    fun `convite de mais um aparelho avisa na pagina e no atalho`() = runBlocking {
        val admin = pair(adminCode, "Edson")
        viaCloudflare("/health")
        val inv = invite(admin.token, forUserId = admin.userId)
        val html = viaCloudflare("/convite/${inv.code}").bodyAsText()
        assertTrue(html.contains("mais um aparelho"))
        assertTrue(html.contains("&amp;d=1"))
    }

    @Test
    fun `admin envia o app e quem e convidado consegue baixar`() = runBlocking {
        val admin = pair(adminCode, "Edson")
        val anaInv = invite(admin.token)
        val ana = pair(anaInv.code, "Ana")
        val apk = byteArrayOf('P'.code.toByte(), 'K'.code.toByte()) + ByteArray(300_000) { (it % 251).toByte() }

        assertEquals("null", http.get("$base/api/app-apk").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, http.get("$base/app/falai.apk").status)
        // Quem não é admin não envia.
        val negado = http.put("$base/api/app-apk?versionCode=6&versionName=0.4.0") {
            header("Authorization", "Bearer ${ana.token}"); setBody(apk)
        }
        assertEquals(HttpStatusCode.Forbidden, negado.status)
        // Arquivo que não é APK é recusado.
        val lixo = http.put("$base/api/app-apk?versionCode=6&versionName=0.4.0") {
            header("Authorization", "Bearer ${admin.token}"); setBody(ByteArray(100) { 1 })
        }
        assertEquals(HttpStatusCode.BadRequest, lixo.status)

        val ok = http.put("$base/api/app-apk?versionCode=6&versionName=0.4.0") {
            header("Authorization", "Bearer ${admin.token}"); setBody(apk)
        }
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val info = FalaiJson.decodeFromString(AppApkInfo.serializer(), http.get("$base/api/app-apk").bodyAsText())
        assertEquals(6, info.versionCode)
        assertEquals("0.4.0", info.versionName)
        assertEquals(apk.size.toLong(), info.size)
        val baixado = http.get("$base/app/falai.apk")
        assertEquals(HttpStatusCode.OK, baixado.status)
        assertContentEquals(apk, baixado.bodyAsBytes())
        assertTrue(baixado.headers["Content-Disposition"]!!.contains("falai.apk"))
        // A página do convite agora oferece o download.
        val inv = invite(admin.token)
        assertTrue(http.get("$base/convite/${inv.code}").bodyAsText().contains("/app/falai.apk"))
    }

    @Test
    fun `link e atalho do convite sao entendidos de volta`() {
        val p1 = assertNotNull(InviteLinks.parse("Oi! Entra aqui: https://falai.falaifamilia.net/convite/ABCDE23456 bjs"))
        assertEquals("https://falai.falaifamilia.net", p1.server)
        assertEquals("ABCDE23456", p1.code)
        assertFalse(p1.forExistingUser)
        val uri = InviteLinks.appUri("https://falai.falaifamilia.net", "XYZ2345678", forExistingUser = true)
        val p2 = assertNotNull(InviteLinks.parse(uri))
        assertEquals("https://falai.falaifamilia.net", p2.server)
        assertEquals("XYZ2345678", p2.code)
        assertTrue(p2.forExistingUser)
        assertNull(InviteLinks.parse("ABCDE23456"))
        assertNull(InviteLinks.parse("https://falai.falaifamilia.net/health"))
    }

    @Test
    fun `instalador do termux so no proprio celular e o script funciona`() = runBlocking {
        assertEquals(HttpStatusCode.Forbidden, viaCloudflare("/setup/tunel").status)
        val script = http.get("$base/setup/tunel").bodyAsText()
        assertTrue(script.startsWith("#!/data/data/com.termux/files/usr/bin/sh"))
        assertTrue(script.contains("cloudflared tunnel run --url http://127.0.0.1:8443 falai"))
        // Roda o instalador de verdade num HOME de mentira e confere o script gerado.
        val home = File(dir, "home").apply { mkdirs() }
        val p = ProcessBuilder("sh", "-c", script.substringAfter("\n"))
            .apply { environment()["HOME"] = home.path }
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor(), out)
        assertTrue(out.contains("Pronto!"))
        val boot = File(home, ".termux/boot/falai-tunel")
        assertTrue(boot.canExecute())
        val text = boot.readText()
        assertTrue(text.contains("termux-wake-lock"))
        assertTrue(text.contains("${'$'}(wc -c < ~/falai-tunel.log)"), text)
        // O script gerado é sh válido.
        val check = ProcessBuilder("sh", "-n", boot.path).redirectErrorStream(true).start()
        assertEquals(0, check.waitFor(), check.inputStream.bufferedReader().readText())
    }
}
