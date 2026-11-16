package app.falai.server

import app.falai.protocol.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
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
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.9: aparelho reconhecido sem convite, apagar conversa, tirar da família, convite com nome e APK carimbado. */
class V09Test {
    private lateinit var dir: File
    private lateinit var server: FalaiServer
    private lateinit var store: Store
    private lateinit var http: HttpClient
    private var port = 0
    private lateinit var adminCode: String
    private val base get() = "http://127.0.0.1:$port"

    private val keyAna = "ana-android-id-0123456789abcdef"

    @BeforeTest
    fun setUp() = runBlocking<Unit> {
        dir = Files.createTempDirectory("falai-v09").toFile()
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

    private suspend fun pair(code: String, name: String?, installKey: String? = null): PairResponse {
        val r = http.post("$base/api/pair") {
            setBody(FalaiJson.encodeToString(PairRequest.serializer(), PairRequest(code, "Aparelho", name, installKey = installKey)))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return FalaiJson.decodeFromString(PairResponse.serializer(), r.bodyAsText())
    }

    private inner class Conn(val ws: DefaultClientWebSocketSession) {
        val received = mutableListOf<ServerFrame>()
        suspend fun send(f: ClientFrame) = ws.send(Frame.Text(FalaiJson.encodeToString(ClientFrame.serializer(), f)))
        suspend fun next(): ServerFrame? = withTimeoutOrNull(3000) {
            while (true) {
                val f = try { ws.incoming.receive() } catch (e: ClosedReceiveChannelException) { return@withTimeoutOrNull null }
                if (f is Frame.Text) {
                    val fr = FalaiJson.decodeFromString(ServerFrame.serializer(), f.readText())
                    received += fr
                    return@withTimeoutOrNull fr
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        suspend inline fun <reified T : ServerFrame> await(): T {
            while (true) {
                val f = next() ?: error("esperava ${T::class.simpleName}, recebi: $received")
                if (f is T) return f
            }
        }
        suspend fun close() = ws.close()
    }

    private suspend fun connect(token: String): Conn {
        val c = Conn(http.webSocketSession("ws://127.0.0.1:$port/ws") { header("Authorization", "Bearer $token") })
        return c
    }

    private suspend fun hello(token: String): Pair<Conn, HelloFrame> {
        val c = connect(token)
        return c to c.await<HelloFrame>()
    }

    private suspend fun invite(adminToken: String, forUserId: String? = null, name: String? = null): InviteFrame {
        val (c, _) = hello(adminToken)
        c.send(CreateInviteFrame("i", forUserId, name))
        return c.await<InviteFrame>().also { c.close() }
    }

    private suspend fun reconnect(key: String) = http.post("$base/api/reconnect") {
        setBody(FalaiJson.encodeToString(ReconnectRequest.serializer(), ReconnectRequest(key, "Galaxy A10")))
    }

    @Test
    fun `aparelho reinstalado volta para a mesma pessoa sem convite`() = runBlocking<Unit> {
        val admin = pair(adminCode, "Edson")
        val ana = pair(invite(admin.token).code, "Ana", installKey = keyAna)
        assertEquals("Ana", ana.userName)
        assertFalse(ana.returning)

        // Desconhecido: precisa de convite.
        assertEquals(HttpStatusCode.NotFound, reconnect("outro-aparelho-0000000000").status)
        // Chave curta demais nem é considerada.
        assertEquals(HttpStatusCode.NotFound, reconnect("curta").status)

        val r = reconnect(keyAna)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val back = FalaiJson.decodeFromString(PairResponse.serializer(), r.bodyAsText())
        assertEquals(ana.userId, back.userId)
        assertEquals(ana.deviceId, back.deviceId)
        assertEquals("Ana", back.userName)
        assertTrue(back.returning)
        // A chave antiga parou de funcionar; a nova funciona.
        assertNull(store.authenticate(ana.token))
        assertNotNull(store.authenticate(back.token))
    }

    @Test
    fun `convite novo num aparelho conhecido nao cria pessoa repetida`() = runBlocking<Unit> {
        val admin = pair(adminCode, "Edson")
        val ana = pair(invite(admin.token).code, "Ana", installKey = keyAna)
        val again = pair(invite(admin.token).code, "Ana 2", installKey = keyAna)
        assertEquals(ana.userId, again.userId)
        assertEquals(ana.deviceId, again.deviceId)
        assertTrue(again.returning)
        assertEquals(listOf("Ana", "Edson"), store.listUsers().map { it.name })
        // Outro aparelho (outra chave) com convite novo vira pessoa nova normalmente.
        val bia = pair(invite(admin.token).code, "Bia", installKey = "bia-android-id-0123456789abcdef")
        assertNotEquals(ana.userId, bia.userId)
    }

    @Test
    fun `apagar conversa some em todos os meus aparelhos e volta com mensagem nova`() = runBlocking<Unit> {
        val admin = pair(adminCode, "Edson")
        val ana = pair(invite(admin.token).code, "Ana")
        val ana2 = pair(invite(admin.token, forUserId = ana.userId).code, null)

        val (a, _) = hello(admin.token)
        a.send(CreateConversationFrame("c", "direct", null, listOf(ana.userId)))
        val conv = a.await<ConversationFrame>().conversation
        a.send(SendFrame("m1", conv.id, "oi Ana"))
        a.await<AckFrame>()
        a.send(SendFrame("m2", conv.id, "tudo bem?"))
        val ack2 = a.await<AckFrame>()

        val (anaWs, h1) = hello(ana.token)
        assertTrue(h1.conversations.any { it.id == conv.id })
        val (ana2Ws, _) = hello(ana2.token)
        anaWs.send(HideConversationFrame("h", conv.id))
        val hidden = anaWs.await<ConversationHiddenFrame>()
        assertEquals(ack2.seq, hidden.clearedSeq)
        // O outro aparelho da Ana fica sabendo na hora.
        assertEquals(conv.id, ana2Ws.await<ConversationHiddenFrame>().conversationId)

        // Ao conectar de novo, a conversa não vem e o histórico apagado não volta.
        anaWs.close()
        val (anaWs2, h2) = hello(ana.token)
        assertFalse(h2.conversations.any { it.id == conv.id })
        anaWs2.send(SyncFrame(0))
        assertEquals(0, anaWs2.await<SyncDoneFrame>().let { anaWs2.received.filterIsInstance<SyncBatchFrame>().sumOf { it.messages.size } })
        // Para o Edson continua tudo lá.
        val (_, ha) = hello(admin.token)
        assertTrue(ha.conversations.any { it.id == conv.id && it.clearedSeq == null })

        // Mensagem nova: a conversa reaparece para a Ana, só com a nova.
        a.send(SendFrame("m3", conv.id, "voltei"))
        a.await<AckFrame>()
        val again = anaWs2.await<ConversationFrame>().conversation
        assertEquals(conv.id, again.id)
        assertEquals(ack2.seq, again.clearedSeq)
        assertEquals("voltei", anaWs2.await<MessageFrame>().message.body)
        anaWs2.send(SyncFrame(0))
        anaWs2.await<SyncDoneFrame>()
        val synced = anaWs2.received.filterIsInstance<SyncBatchFrame>().flatMap { it.messages }.map { it.body }
        assertEquals(listOf("voltei"), synced)

        // Apagar de novo e abrir "Nova conversa" com o Edson: volta a conversa (vazia para a Ana).
        anaWs2.send(HideConversationFrame("h2", conv.id))
        anaWs2.await<ConversationHiddenFrame>()
        anaWs2.send(CreateConversationFrame("c2", "direct", null, listOf(admin.userId)))
        val reopened = anaWs2.await<ConversationFrame>().conversation
        assertEquals(conv.id, reopened.id)
        assertTrue(reopened.clearedSeq!! > ack2.seq)
        val (_, h3) = hello(ana.token)
        assertTrue(h3.conversations.any { it.id == conv.id })
    }

    @Test
    fun `administrador tira alguem da familia`() = runBlocking<Unit> {
        val admin = pair(adminCode, "Edson")
        val ana = pair(invite(admin.token).code, "Ana", installKey = keyAna)
        val bia = pair(invite(admin.token).code, "Bia")

        // Quem não é administrador não pode.
        val (b, _) = hello(bia.token)
        b.send(RemoveUserFrame("x", ana.userId))
        assertEquals("forbidden", b.await<ErrorFrame>().code)

        val (anaWs, _) = hello(ana.token)
        val (a, _) = hello(admin.token)
        a.send(RemoveUserFrame("r", admin.userId))
        assertEquals("invalid", a.await<ErrorFrame>().code)
        a.send(RemoveUserFrame("r2", ana.userId))
        val upd = a.await<UserUpdatedFrame>()
        assertTrue(upd.user.removed)
        // A Bia fica sabendo (para tirar a Ana da lista de "Nova conversa").
        assertTrue(b.await<UserUpdatedFrame>().user.removed)
        // A conexão da Ana cai e a chave não serve mais, nem a volta pelo aparelho.
        while (anaWs.next() != null) { }
        assertNull(store.authenticate(ana.token))
        assertEquals(HttpStatusCode.NotFound, reconnect(keyAna).status)
        // Continua na lista (para o nome aparecer nas mensagens antigas), marcada.
        assertTrue(store.listUsers().first { it.id == ana.userId }.removed)
        // Não dá para começar conversa com quem saiu.
        a.send(CreateConversationFrame("c", "direct", null, listOf(ana.userId)))
        assertEquals("invalid", a.await<ErrorFrame>().code)
    }

    @Test
    fun `convite com nome e app carimbado com o convite`() = runBlocking<Unit> {
        val admin = pair(adminCode, "Edson")
        val inv = invite(admin.token, name = "Vó Maria")
        // Envia um APK assinado de verdade (v2) como o app do admin faria.
        val apk = TestApks.signed(File(dir, "apk").apply { mkdirs() })
        val up = http.put("$base/api/app-apk?versionCode=18&versionName=0.9.0") {
            header("Authorization", "Bearer ${admin.token}"); setBody(apk.readBytes())
        }
        assertEquals(HttpStatusCode.OK, up.status, up.bodyAsText())

        val page = http.get("$base/convite/${inv.code}").bodyAsText()
        assertTrue(page.contains("Vó Maria"), page)
        assertTrue(page.contains("&amp;n=V%C3%B3%20Maria"), page)
        assertTrue(page.contains("/convite/${inv.code}/falai.apk"))
        assertTrue(page.contains("/web/icon.svg"))

        val got = http.get("$base/convite/${inv.code}/falai.apk")
        assertEquals("application/vnd.android.package-archive", got.headers["Content-Type"])
        val f = File(dir, "baixado.apk").apply { writeBytes(got.bodyAsBytes()) }
        TestApks.verify(f)
        val parsed = InviteLinks.parse(ApkStamp.read(f)!!)!!
        assertEquals(inv.code, parsed.code)
        assertEquals("Vó Maria", parsed.name)
        assertEquals(base, parsed.server)

        // Sem convite: o app vem só com o endereço do servidor.
        val plain = File(dir, "puro.apk").apply { writeBytes(http.get("$base/app/falai.apk").bodyAsBytes()) }
        TestApks.verify(plain)
        assertEquals(base, InviteLinks.parseServer(ApkStamp.read(plain)!!))

        // Convite gasto: o app ainda vem, só com o endereço.
        pair(inv.code, "Maria")
        val used = File(dir, "usado.apk").apply { writeBytes(http.get("$base/convite/${inv.code}/falai.apk").bodyAsBytes()) }
        assertEquals(base, InviteLinks.parseServer(ApkStamp.read(used)!!))
        assertNull(InviteLinks.parse(ApkStamp.read(used)!!))
        // E o nome sugerido não vale para o convite de "mais um aparelho".
        assertNull(store.invitePreview(invite(admin.token, forUserId = admin.userId, name = "X").code).suggestedName)
    }

    @Test
    fun `icones e pagina web com a versao`() = runBlocking<Unit> {
        val svg = http.get("$base/web/icon.svg")
        assertEquals(HttpStatusCode.OK, svg.status)
        assertTrue(svg.headers["Content-Type"]!!.startsWith("image/svg+xml"))
        val png = http.get("$base/favicon.ico").bodyAsBytes()
        assertEquals(0x89.toByte(), png[0])
        assertEquals(HttpStatusCode.OK, http.get("$base/web/icon-512.png").status)
        assertTrue(http.get("$base/web/manifest.webmanifest").bodyAsText().contains("\"Falaí\""))
        assertTrue(http.get("$base/web/desenhos.svg").bodyAsText().startsWith("<svg"))
        assertEquals(HttpStatusCode.NotFound, http.get("$base/web/falai-web.html").status)
        val page = http.get("$base/web").bodyAsText()
        assertTrue(page.contains(FALAI_VERSION))
        assertFalse(page.contains("{{FALAI_VERSION}}"))
    }
}
