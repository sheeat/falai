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

/** 0.10: mexer no grupo, responder, reagir, apagar para todos, tiques ✓✓. */
class V10Test {
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
        dir = Files.createTempDirectory("falai-v10").toFile()
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

    private suspend fun connect(token: String, protocol: Int = 2): Conn {
        val q = if (protocol >= 2) "?p=$protocol" else ""
        val c = Conn(http.webSocketSession("ws://127.0.0.1:$port/ws$q") { header("Authorization", "Bearer $token") })
        return c
    }

    private suspend fun hello(token: String, protocol: Int = 2): Pair<Conn, HelloFrame> {
        val c = connect(token, protocol)
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

    private suspend fun family(): Triple<PairResponse, PairResponse, PairResponse> {
        val admin = pair(adminCode, "Edson")
        val ana = pair(invite(admin.token).code, "Ana")
        val bia = pair(invite(admin.token).code, "Bia")
        return Triple(admin, ana, bia)
    }

    private suspend fun Conn.sendText(conv: String, body: String, replyTo: String? = null): AckFrame {
        val id = java.util.UUID.randomUUID().toString()
        send(SendFrame(id, conv, body, replyToId = replyTo))
        while (true) { val a = await<AckFrame>(); if (a.id == id) return a }
    }

    @Test
    fun `grupo - por, tirar, renomear e sair`() = runBlocking<Unit> {
        val (admin, ana, bia) = family()
        val (a, _) = hello(ana.token)
        a.send(CreateConversationFrame("c", "group", "Primos", listOf(bia.userId)))
        val g = a.await<ConversationFrame>().conversation
        assertEquals(ana.userId, g.createdBy)
        a.sendText(g.id, "antes do Edson")

        // A Bia (não criou, não é admin) pode pôr gente e mudar o nome, mas não tirar.
        val (b, _) = hello(bia.token)
        b.send(UpdateGroupFrame("u1", g.id, title = "Primos ❤️", addMemberIds = listOf(admin.userId)))
        val upd = b.await<ConversationFrame>().conversation
        assertEquals("Primos ❤️", upd.title)
        assertEquals(setOf(admin.userId, ana.userId, bia.userId), upd.memberIds.toSet())
        val avisos = listOf(b.await<MessageFrame>().message, b.await<MessageFrame>().message)
        assertTrue(avisos.all { it.kind == MessageKinds.SYSTEM })
        assertEquals(listOf("Bia mudou o nome do grupo para \"Primos ❤️\"", "Bia adicionou Edson"), avisos.map { it.body })
        b.send(UpdateGroupFrame("u2", g.id, removeMemberIds = listOf(ana.userId)))
        assertEquals("forbidden", b.await<ErrorFrame>().code)

        // O Edson entra e vê o grupo só a partir de agora.
        val (e, he) = hello(admin.token)
        assertTrue(he.conversations.any { it.id == g.id })
        e.send(SyncFrame(0))
        val seen = mutableListOf<MessageDto>()
        while (true) { when (val f = e.next()) { is SyncBatchFrame -> seen += f.messages; is SyncDoneFrame -> break; null -> error("sem sync"); else -> {} } }
        assertFalse(seen.any { it.body == "antes do Edson" })
        assertTrue(seen.any { it.body == "Bia adicionou Edson" })

        // Quem criou tira a Bia: ela recebe o aviso e nada depois.
        a.send(UpdateGroupFrame("u3", g.id, removeMemberIds = listOf(bia.userId)))
        while (true) { val cf = a.await<ConversationFrame>(); if (cf.requestId == "u3") { assertFalse(bia.userId in cf.conversation.memberIds); break } }
        while (true) { val cf = b.await<ConversationFrame>(); if (bia.userId !in cf.conversation.memberIds) break }
        while (true) { if (b.await<MessageFrame>().message.body == "Ana tirou Bia do grupo") break }
        a.sendText(g.id, "depois da Bia")
        b.send(SendFrame("x1", g.id, "oi?"))
        assertEquals("forbidden", b.await<ErrorFrame>().code)
        val (b2, hb2) = hello(bia.token)
        assertTrue(hb2.conversations.any { it.id == g.id }) // o histórico continua na lista dela
        b2.send(SyncFrame(0))
        val bSeen = mutableListOf<MessageDto>()
        while (true) { when (val f = b2.next()) { is SyncBatchFrame -> bSeen += f.messages; is SyncDoneFrame -> break; null -> error("sem sync"); else -> {} } }
        assertTrue(bSeen.any { it.body == "antes do Edson" })
        assertFalse(bSeen.any { it.body == "depois da Bia" })

        // O Edson sai do grupo.
        e.send(LeaveGroupFrame("l", g.id))
        while (true) { val cf = e.await<ConversationFrame>(); if (cf.requestId == "l") { assertEquals(listOf(ana.userId), cf.conversation.memberIds); break } }
        // Conversa a dois não vira grupo.
        a.send(CreateConversationFrame("d", "direct", null, listOf(bia.userId)))
        var d: ConversationDto
        while (true) { val cf = a.await<ConversationFrame>(); if (cf.requestId == "d") { d = cf.conversation; break } }
        a.send(UpdateGroupFrame("u4", d.id, title = "x"))
        assertEquals("invalid", a.await<ErrorFrame>().code)
    }

    @Test
    fun `responder, reagir e apagar para todos`() = runBlocking<Unit> {
        val (admin, ana, _) = family()
        val (a, _) = hello(ana.token)
        a.send(CreateConversationFrame("c", "direct", null, listOf(admin.userId)))
        val conv = a.await<ConversationFrame>().conversation
        val (e, _) = hello(admin.token)
        // Outro aparelho do Edson, com o app antigo.
        val oldDevice = pair(invite(admin.token, forUserId = admin.userId).code, null)
        val (old, _) = hello(oldDevice.token, protocol = 1)

        val m1 = a.sendText(conv.id, "Bom dia!")
        val m1id = e.await<MessageFrame>().message.id
        old.await<MessageFrame>()
        val r = e.sendText(conv.id, "Bom dia, filha", replyTo = m1id)
        assertEquals(m1id, a.await<MessageFrame>().message.replyToId)
        old.await<MessageFrame>()

        e.send(ReactFrame("r1", conv.id, m1id, "❤️"))
        assertEquals("r1", e.await<AckFrame>().id)
        val ev = a.await<MessageFrame>().message
        assertEquals(MessageKinds.REACTION, ev.kind); assertEquals(m1id, ev.refId); assertEquals("❤️", ev.body)
        assertEquals(mapOf(admin.userId to "❤️"), store.messageById(m1id)!!.reactions)

        // Só quem escreveu apaga para todos.
        e.send(DeleteMessageFrame("d0", conv.id, m1id))
        assertEquals("forbidden", e.await<ErrorFrame>().code)
        a.send(DeleteMessageFrame("d1", conv.id, m1id))
        assertEquals("d1", a.await<AckFrame>().id)
        assertEquals(MessageKinds.DELETE, e.await<MessageFrame>().message.kind)
        val apagada = store.messageById(m1id)!!
        assertTrue(apagada.deleted); assertEquals("", apagada.body); assertTrue(apagada.reactions.isEmpty())
        e.send(ReactFrame("r2", conv.id, m1id, "👍"))
        assertEquals("invalid", e.await<ErrorFrame>().code)

        // App antigo: não recebe reação nem "apagado" soltos; no sync, a apagada vem com aviso.
        assertNull(withTimeoutOrNull(800) { old.next() })
        old.send(SyncFrame(0))
        val oldSeen = mutableListOf<MessageDto>()
        while (true) { when (val f = old.next()) { is SyncBatchFrame -> oldSeen += f.messages; is SyncDoneFrame -> break; null -> error("sem sync"); else -> {} } }
        assertTrue(oldSeen.none { it.kind != null })
        assertEquals("🚫 Mensagem apagada", oldSeen.first { it.id == m1id }.body)
        assertEquals(r.id, oldSeen.last().id)
    }

    @Test
    fun `tiques - recebida e lida`() = runBlocking<Unit> {
        val (admin, ana, bia) = family()
        val (a, _) = hello(ana.token)
        a.send(CreateConversationFrame("c", "group", "Nós", listOf(admin.userId, bia.userId)))
        val g = a.await<ConversationFrame>().conversation
        val ack = a.sendText(g.id, "oi")
        val (e, _) = hello(admin.token)
        e.send(ReceiptFrame(g.id, deliveredSeq = ack.seq))
        val r1 = a.await<ReceiptsFrame>()
        assertEquals(ReceiptDto(admin.userId, ack.seq, 0), r1.receipt)
        e.send(ReceiptFrame(g.id, readSeq = ack.seq))
        assertEquals(ReceiptDto(admin.userId, ack.seq, ack.seq), a.await<ReceiptsFrame>().receipt)
        // Repetir ou voltar para trás não muda nada (nem avisa de novo).
        e.send(ReceiptFrame(g.id, deliveredSeq = 1, readSeq = 1))
        assertNull(withTimeoutOrNull(500) { a.next() })
        // Quem conecta depois já recebe o estado de todos na conversa.
        val (_, h) = hello(ana.token)
        val rs = h.conversations.first { it.id == g.id }.receipts.associateBy { it.userId }
        assertEquals(ack.seq, rs[admin.userId]!!.readSeq)
        assertEquals(0, rs[bia.userId]!!.deliveredSeq)
    }
}
