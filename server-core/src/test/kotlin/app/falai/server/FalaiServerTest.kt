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
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Testes de ponta a ponta: sobe o servidor de verdade numa porta livre e conecta
 * clientes WebSocket simulando aparelhos da família.
 */
class FalaiServerTest {

    private lateinit var dir: File
    private lateinit var server: FalaiServer
    private lateinit var store: Store
    private lateinit var http: HttpClient
    private var port = 0
    private var adminCode: String? = null

    @BeforeTest
    fun setUp() = runBlocking {
        dir = Files.createTempDirectory("falai-test").toFile()
        store = Store(JdbcDb(File(dir, "t.db").path))
        server = FalaiServer(store, ServerConfig(port = 0, syncBatchSize = 3, pairMaxFailures = 3), log = {})
        adminCode = server.start()
        port = server.boundPort()
        http = HttpClient(CIO) { install(WebSockets) }
    }

    @AfterTest
    fun tearDown() {
        http.close()
        server.stop()
        dir.deleteRecursively()
    }

    // ---------- ajudantes ----------

    private val base get() = "http://127.0.0.1:$port"

    private suspend fun pair(code: String, device: String, name: String?): PairResponse {
        val r = http.post("$base/api/pair") {
            setBody(FalaiJson.encodeToString(PairRequest.serializer(), PairRequest(code, device, name)))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return FalaiJson.decodeFromString(PairResponse.serializer(), r.bodyAsText())
    }

    private suspend fun pairRaw(code: String, device: String, name: String?) =
        http.post("$base/api/pair") {
            setBody(FalaiJson.encodeToString(PairRequest.serializer(), PairRequest(code, device, name)))
        }

    /** Um aparelho conectado, com leitura de quadros tipada. */
    inner class Client(val session: DefaultClientWebSocketSession) {
        suspend fun send(f: ClientFrame) =
            session.send(Frame.Text(FalaiJson.encodeToString(ClientFrame.serializer(), f)))

        suspend fun next(): ServerFrame = withTimeout(5000) {
            while (true) {
                val f = session.incoming.receive()
                if (f is Frame.Text) return@withTimeout FalaiJson.decodeFromString(ServerFrame.serializer(), f.readText())
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

        suspend inline fun <reified T : ServerFrame> expect(): T {
            val f = next()
            assertIs<T>(f, "esperava ${T::class.simpleName}, veio $f")
            return f
        }

        /** Garante que nada chega em [ms] milissegundos. */
        suspend fun expectSilence(ms: Long = 300) {
            val f = withTimeoutOrNull(ms) {
                while (true) {
                    val x = session.incoming.receive()
                    if (x is Frame.Text) return@withTimeoutOrNull x.readText()
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            assertNull(f, "não devia ter chegado nada")
        }

        suspend fun close() = session.close()
    }

    private suspend fun connect(token: String): Client =
        Client(http.webSocketSession("ws://127.0.0.1:$port/ws") { header("Authorization", "Bearer $token") })

    private suspend fun connectAndHello(token: String): Pair<Client, HelloFrame> {
        val c = connect(token)
        return c to c.expect<HelloFrame>()
    }

    private fun uuid() = UUID.randomUUID().toString()

    /** Admin + Ana pareados, com uma conversa direta entre eles. */
    private suspend fun family(): Triple<PairResponse, PairResponse, ConversationDto> {
        val admin = pair(adminCode!!, "Celular do Chefe", "Chefe")
        val (a, _) = connectAndHello(admin.token)
        a.send(CreateInviteFrame("r1"))
        val inv = a.expect<InviteFrame>()
        val ana = pair(inv.code, "Celular da Ana", "Ana")
        a.send(CreateConversationFrame("r2", "direct", null, listOf(ana.userId)))
        val conv = a.expect<ConversationFrame>().conversation
        a.close()
        return Triple(admin, ana, conv)
    }

    // ---------- testes ----------

    @Test
    fun `health responde`() = runBlocking {
        val r = http.get("$base/health")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"ok\":true"))
    }

    @Test
    fun `primeiro pareamento vira administrador e o codigo nao serve duas vezes`() = runBlocking {
        val code = assertNotNull(adminCode)
        val admin = pair(code, "Celular do Chefe", "Chefe")
        assertTrue(admin.isAdmin)

        val again = pairRaw(code, "Outro", "Intruso")
        assertEquals(HttpStatusCode.Forbidden, again.status)
        assertTrue(again.bodyAsText().contains("invalid_code"))
        // Depois de ter gente cadastrada, não existe mais código de bootstrap.
        assertNull(store.bootstrapInviteIfEmpty())
    }

    @Test
    fun `websocket sem token ou com token errado e recusado`() = runBlocking {
        for (t in listOf("", "token-inventado")) {
            val c = connect(t)
            val reason = withTimeout(5000) { c.session.closeReason.await() }
            assertEquals(4001, reason?.code?.toInt())
        }
    }

    @Test
    fun `codigo errado repetido bloqueia por um tempo`() = runBlocking {
        repeat(3) { assertEquals(HttpStatusCode.Forbidden, pairRaw("ERRADO$it", "x", "y").status) }
        // Mesmo o código certo é barrado enquanto o bloqueio vale.
        assertEquals(HttpStatusCode.TooManyRequests, pairRaw(adminCode!!, "x", "y").status)
    }

    @Test
    fun `so administrador cria convite`() = runBlocking {
        val (_, ana, _) = family()
        val (c, hello) = connectAndHello(ana.token)
        assertFalse(hello.user.isAdmin)
        c.send(CreateInviteFrame("r9"))
        val err = c.expect<ErrorFrame>()
        assertEquals("forbidden", err.code)
        assertEquals("r9", err.requestId)
    }

    @Test
    fun `mensagem chega em tempo real e tem ack`() = runBlocking {
        val (admin, ana, conv) = family()
        val (chefe, _) = connectAndHello(admin.token)
        val (anaC, helloAna) = connectAndHello(ana.token)
        assertEquals(listOf(conv.id), helloAna.conversations.map { it.id })

        val id = uuid()
        chefe.send(SendFrame(id, conv.id, "bom dia ☀️"))
        val ack = chefe.expect<AckFrame>()
        assertEquals(id, ack.id)

        val got = anaC.expect<MessageFrame>().message
        assertEquals("bom dia ☀️", got.body)
        assertEquals(admin.userId, got.authorId)
        assertEquals(ack.seq, got.seq)

        // O próprio aparelho que enviou não recebe eco.
        chefe.expectSilence()
    }

    @Test
    fun `reenvio com o mesmo id nao duplica`() = runBlocking {
        val (admin, ana, conv) = family()
        val (chefe, _) = connectAndHello(admin.token)
        val (anaC, _) = connectAndHello(ana.token)

        val id = uuid()
        chefe.send(SendFrame(id, conv.id, "oi"))
        val ack1 = chefe.expect<AckFrame>()
        anaC.expect<MessageFrame>()

        chefe.send(SendFrame(id, conv.id, "oi"))
        val ack2 = chefe.expect<AckFrame>()
        assertEquals(ack1.seq, ack2.seq)
        anaC.expectSilence()
        assertEquals(1, store.messagesSince(ana.userId, 0, 100).size)
    }

    @Test
    fun `quem nao participa nao envia nem recebe`() = runBlocking {
        val (admin, ana, conv) = family()
        // Bruno entra na família, mas não está na conversa Chefe-Ana.
        val (chefe, _) = connectAndHello(admin.token)
        chefe.send(CreateInviteFrame("r1"))
        val bruno = pair(chefe.expect<InviteFrame>().code, "Celular do Bruno", "Bruno")
        val (brunoC, helloBruno) = connectAndHello(bruno.token)
        assertTrue(helloBruno.conversations.isEmpty())

        brunoC.send(SendFrame(uuid(), conv.id, "posso entrar?"))
        assertEquals("forbidden", brunoC.expect<ErrorFrame>().code)

        chefe.send(SendFrame(uuid(), conv.id, "segredo"))
        chefe.expect<AckFrame>()
        brunoC.expectSilence()

        brunoC.send(SyncFrame(0))
        assertEquals(0, brunoC.expect<SyncDoneFrame>().lastSeq)
        assertEquals(1, store.messagesSince(ana.userId, 0, 10).size)
    }

    @Test
    fun `aparelho novo da mesma pessoa recebe o historico inteiro em lotes`() = runBlocking {
        val (admin, ana, conv) = family()
        val (anaC, _) = connectAndHello(ana.token)
        val bodies = (1..7).map { "mensagem $it" }
        for (b in bodies) {
            anaC.send(SendFrame(uuid(), conv.id, b))
            anaC.expect<AckFrame>()
        }

        // Chefe liga um tablet: convite para a pessoa que já existe.
        val (chefe, _) = connectAndHello(admin.token)
        chefe.send(CreateInviteFrame("r1", forUserId = admin.userId))
        val tablet = pair(chefe.expect<InviteFrame>().code, "Tablet do Chefe", null)
        assertEquals(admin.userId, tablet.userId)
        assertTrue(tablet.isAdmin)

        val (tab, hello) = connectAndHello(tablet.token)
        assertEquals(listOf(conv.id), hello.conversations.map { it.id })
        tab.send(SyncFrame(0))
        // syncBatchSize = 3 nos testes: 7 mensagens = lotes de 3, 3 e 1.
        val received = mutableListOf<MessageDto>()
        val sizes = mutableListOf<Int>()
        while (true) {
            when (val f = tab.next()) {
                is SyncBatchFrame -> { sizes += f.messages.size; received += f.messages }
                is SyncDoneFrame -> { assertEquals(received.last().seq, f.lastSeq); break }
                else -> error("quadro inesperado: $f")
            }
        }
        assertEquals(listOf(3, 3, 1), sizes)
        assertEquals(bodies, received.map { it.body })

        // Sync incremental: nada novo depois do último seq.
        tab.send(SyncFrame(received.last().seq))
        assertEquals(received.last().seq, tab.expect<SyncDoneFrame>().lastSeq)

        // Mensagem nova chega nos DOIS aparelhos do Chefe.
        anaC.send(SendFrame(uuid(), conv.id, "chegou no tablet?"))
        anaC.expect<AckFrame>()
        assertEquals("chegou no tablet?", tab.expect<MessageFrame>().message.body)
        assertEquals("chegou no tablet?", chefe.expect<MessageFrame>().message.body)
    }

    @Test
    fun `mensagem enviada de um aparelho aparece no outro aparelho da mesma pessoa`() = runBlocking {
        val (admin, _, conv) = family()
        val (chefe, _) = connectAndHello(admin.token)
        chefe.send(CreateInviteFrame("r1", forUserId = admin.userId))
        val tablet = pair(chefe.expect<InviteFrame>().code, "Tablet", null)
        val (tab, _) = connectAndHello(tablet.token)

        chefe.send(SendFrame(uuid(), conv.id, "escrevi no celular"))
        chefe.expect<AckFrame>()
        assertEquals("escrevi no celular", tab.expect<MessageFrame>().message.body)
    }

    @Test
    fun `conversa direta repetida devolve a mesma e grupo avisa os membros`() = runBlocking {
        val (admin, ana, conv) = family()
        val (chefe, _) = connectAndHello(admin.token)
        val (anaC, _) = connectAndHello(ana.token)

        anaC.send(CreateConversationFrame("x", "direct", null, listOf(admin.userId)))
        assertEquals(conv.id, anaC.expect<ConversationFrame>().conversation.id)
        chefe.expect<ConversationFrame>() // aviso (mesma conversa)

        chefe.send(CreateConversationFrame("g", "group", "Família 💚", listOf(ana.userId)))
        val g = chefe.expect<ConversationFrame>()
        assertEquals("g", g.requestId)
        assertEquals("Família 💚", g.conversation.title)
        val notice = anaC.expect<ConversationFrame>()
        assertNull(notice.requestId)
        assertEquals(g.conversation.id, notice.conversation.id)
    }

    @Test
    fun `quadro invalido nao derruba a conexao`() = runBlocking {
        val (admin, _, _) = family()
        val (c, _) = connectAndHello(admin.token)
        c.session.send(Frame.Text("isso nao e json"))
        assertEquals("invalid", c.expect<ErrorFrame>().code)
        c.session.send(Frame.Text("""{"type":"tipo_que_nao_existe"}"""))
        assertEquals("invalid", c.expect<ErrorFrame>().code)
        c.send(PingFrame("p"))
        assertEquals("p", c.expect<PongFrame>().requestId)
    }

    @Test
    fun `dados sobrevivem a reinicio do servidor`() = runBlocking {
        val (admin, _, conv) = family()
        val (chefe, _) = connectAndHello(admin.token)
        chefe.send(SendFrame(uuid(), conv.id, "antes de reiniciar"))
        chefe.expect<AckFrame>()
        chefe.close()
        server.stop()

        val store2 = Store(JdbcDb(File(dir, "t.db").path))
        server = FalaiServer(store2, ServerConfig(port = 0), log = {})
        assertNull(server.start(), "não deve gerar código de admin de novo")
        port = server.boundPort()

        val (c, hello) = connectAndHello(admin.token)
        assertEquals(1, hello.lastSeq)
        c.send(SyncFrame(0))
        assertEquals("antes de reiniciar", c.expect<SyncBatchFrame>().messages.single().body)
    }
}
