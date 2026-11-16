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
 * Chamadas de voz e vídeo: o servidor apresenta os aparelhos e repassa os recados do WebRTC.
 */
class CallsTest {

    private lateinit var dir: File
    private lateinit var server: FalaiServer
    private lateinit var store: Store
    private lateinit var http: HttpClient
    private var port = 0
    private var adminCode: String? = null
    private var turnCalls = 0

    @BeforeTest
    fun setUp() = runBlocking {
        dir = Files.createTempDirectory("falai-test").toFile()
        store = Store(JdbcDb(File(dir, "t.db").path))
        server = FalaiServer(store, ServerConfig(port = 0, mediaDir = File(dir, "media"), callRingTimeoutMs = 800, callDropGraceMs = 400, turnFetch = { k, t -> turnCalls++; if (t == "token-bom-12345") CF_REPLY else throw java.io.IOException("código 401") }), log = {})
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


    /** Lê quadros até achar um do tipo pedido (ignora usuários, sync etc.). */
    private suspend inline fun <reified T : ServerFrame> Client.waitFor(): T = withTimeout(5000) {
        while (true) { val f = next(); if (f is T) return@withTimeout f }
        @Suppress("UNREACHABLE_CODE") error("x")
    }

    /** Espera o estado da chamada que satisfaz [ok] (pula os anteriores). */
    private suspend fun Client.state(ok: (CallStateFrame) -> Boolean): CallStateFrame = withTimeout(5000) {
        while (true) { val f = next(); if (f is CallStateFrame && ok(f)) return@withTimeout f }
        @Suppress("UNREACHABLE_CODE") error("x")
    }

    /** Chefe + Ana + Bia, com uma conversa direta (Chefe–Ana) e um grupo com os três. */
    private suspend fun family3(): Family {
        val admin = pair(adminCode!!, "Celular do Chefe", "Chefe")
        val (a, _) = connectAndHello(admin.token)
        a.send(CreateInviteFrame("r1")); val inv1 = a.expect<InviteFrame>()
        val ana = pair(inv1.code, "Celular da Ana", "Ana")
        a.send(CreateInviteFrame("r2")); val inv2 = a.expect<InviteFrame>()
        val bia = pair(inv2.code, "Celular da Bia", "Bia")
        a.send(CreateConversationFrame("r3", "direct", null, listOf(ana.userId)))
        val direct = a.expect<ConversationFrame>().conversation
        a.send(CreateConversationFrame("r4", "group", "Família", listOf(ana.userId, bia.userId)))
        val group = a.expect<ConversationFrame>().conversation
        a.close()
        return Family(admin, ana, bia, direct, group)
    }

    data class Family(val admin: PairResponse, val ana: PairResponse, val bia: PairResponse, val direct: ConversationDto, val group: ConversationDto)

    @Test
    fun `chamada a dois - toca, atende, troca recados e desliga`() = runBlocking {
        val fam = family3()
        val (chefe, _) = connectAndHello(fam.admin.token)
        val (ana, _) = connectAndHello(fam.ana.token)
        val (bia, _) = connectAndHello(fam.bia.token)

        val callId = uuid()
        chefe.send(CallStartFrame("s1", callId, fam.direct.id, video = true))
        val started = chefe.waitFor<CallStateFrame>()
        assertEquals("s1", started.requestId)
        assertEquals(listOf(fam.admin.deviceId), started.call.participants.map { it.deviceId })
        assertTrue(started.call.participants.single().cam)

        val ring = ana.waitFor<CallRingFrame>()
        assertEquals(callId, ring.call.id)
        assertTrue(ring.call.video)
        bia.expectSilence() // a Bia não está nessa conversa

        ana.send(CallJoinFrame("j1", callId, mic = true, cam = true))
        val joined = ana.waitFor<CallStateFrame>()
        assertEquals("j1", joined.requestId)
        assertEquals(2, joined.call.participants.size)
        val chefeSees = chefe.waitFor<CallStateFrame>()
        assertEquals(2, chefeSees.call.participants.size)

        // Quem entra liga para quem estava (offer); a resposta volta (answer); caminhos (ice) nos dois sentidos.
        ana.send(CallSignalFrame(callId, fam.admin.deviceId, "offer", "{\"sdp\":\"v=0 oferta\"}"))
        val offer = chefe.waitFor<CallSignalInFrame>()
        assertEquals("offer", offer.kind)
        assertEquals(fam.ana.deviceId, offer.fromDeviceId)
        assertEquals(fam.ana.userId, offer.fromUserId)
        assertEquals("{\"sdp\":\"v=0 oferta\"}", offer.data)
        chefe.send(CallSignalFrame(callId, fam.ana.deviceId, "answer", "resposta"))
        assertEquals("resposta", ana.waitFor<CallSignalInFrame>().data)
        chefe.send(CallSignalFrame(callId, fam.ana.deviceId, "ice", "candidato"))
        assertEquals("ice", ana.waitFor<CallSignalInFrame>().kind)

        // Câmera desligada: o outro fica sabendo.
        ana.send(CallMediaFrame(callId, mic = false, cam = false))
        val media = chefe.waitFor<CallStateFrame>()
        val anaP = media.call.participants.first { it.userId == fam.ana.userId }
        assertFalse(anaP.mic); assertFalse(anaP.cam)

        chefe.send(CallLeaveFrame(callId))
        val endedForAna = ana.waitFor<CallStateFrame>()
        assertTrue(endedForAna.call.ended)
        assertEquals("hangup", endedForAna.call.endReason)
        bia.expectSilence()
    }

    @Test
    fun `ninguem atende - a chamada acaba sozinha`() = runBlocking {
        val fam = family3()
        val (chefe, _) = connectAndHello(fam.admin.token)
        val (ana, _) = connectAndHello(fam.ana.token)
        val callId = uuid()
        chefe.send(CallStartFrame("s1", callId, fam.direct.id, video = false))
        chefe.waitFor<CallStateFrame>()
        ana.waitFor<CallRingFrame>()
        val end = ana.waitFor<CallStateFrame>()
        assertTrue(end.call.ended)
        assertEquals("no_answer", end.call.endReason)
        assertTrue(chefe.waitFor<CallStateFrame>().call.ended)
        assertEquals(0, server.calls.activeCount())
    }

    @Test
    fun `recusar ou estar ocupado encerra a chamada a dois`() = runBlocking {
        val fam = family3()
        val (chefe, _) = connectAndHello(fam.admin.token)
        val (ana, _) = connectAndHello(fam.ana.token)
        for ((busy, reason) in listOf(false to "declined", true to "busy")) {
            val callId = uuid()
            chefe.send(CallStartFrame("s-$reason", callId, fam.direct.id, video = false))
            chefe.waitFor<CallStateFrame>()
            ana.waitFor<CallRingFrame>()
            ana.send(CallDeclineFrame(callId, busy))
            val end = chefe.waitFor<CallStateFrame>()
            assertTrue(end.call.ended)
            assertEquals(reason, end.call.endReason)
            assertTrue(ana.waitFor<CallStateFrame>().call.ended)
        }
    }

    @Test
    fun `chamada em grupo - entra, recusa, sai e quem sobra sozinho tem a chamada encerrada`() = runBlocking {
        val fam = family3()
        val (chefe, _) = connectAndHello(fam.admin.token)
        val (ana, _) = connectAndHello(fam.ana.token)
        val (bia, _) = connectAndHello(fam.bia.token)
        val callId = uuid()
        chefe.send(CallStartFrame("s1", callId, fam.group.id, video = true))
        chefe.waitFor<CallStateFrame>()
        ana.waitFor<CallRingFrame>()
        bia.waitFor<CallRingFrame>()

        ana.send(CallJoinFrame("j1", callId, cam = true))
        assertEquals(2, ana.waitFor<CallStateFrame>().call.participants.size)
        chefe.waitFor<CallStateFrame>()
        // A Bia vê que a chamada já tem duas pessoas (para mostrar "Entrar na chamada").
        assertEquals(2, bia.waitFor<CallStateFrame>().call.participants.size)

        bia.send(CallDeclineFrame(callId))
        val afterDecline = chefe.state { it.call.declinedBy.isNotEmpty() }
        assertFalse(afterDecline.call.ended)
        assertEquals(listOf(fam.bia.userId), afterDecline.call.declinedBy)

        // A Bia muda de ideia e entra depois: agora são três.
        bia.send(CallJoinFrame("j2", callId))
        val three = bia.state { it.requestId == "j2" }
        assertEquals(3, three.call.participants.size)
        assertTrue(three.call.declinedBy.isEmpty())

        chefe.send(CallLeaveFrame(callId))
        val two = ana.state { s -> s.call.participants.none { it.userId == fam.admin.userId } }
        assertFalse(two.call.ended)
        assertEquals(setOf(fam.ana.userId, fam.bia.userId), two.call.participants.map { it.userId }.toSet())

        bia.send(CallLeaveFrame(callId))
        val alone = ana.state { it.call.participants.size == 1 }
        assertFalse(alone.call.ended)
        val end = ana.state { it.call.ended }
        assertEquals("hangup", end.call.endReason)
        assertEquals(0, server.calls.activeCount())
    }

    @Test
    fun `ligar para uma conversa que ja tem chamada entra nela`() = runBlocking {
        val fam = family3()
        val (chefe, _) = connectAndHello(fam.admin.token)
        val (ana, _) = connectAndHello(fam.ana.token)
        val callId = uuid()
        chefe.send(CallStartFrame("s1", callId, fam.direct.id, video = false))
        chefe.waitFor<CallStateFrame>()
        ana.waitFor<CallRingFrame>()
        ana.send(CallStartFrame("s2", uuid(), fam.direct.id, video = false))
        val st = ana.waitFor<CallStateFrame>()
        assertEquals("s2", st.requestId)
        assertEquals(callId, st.call.id)
        assertEquals(2, st.call.participants.size)
    }

    @Test
    fun `quem nao esta na conversa nao entra nem manda recados`() = runBlocking {
        val fam = family3()
        val (chefe, _) = connectAndHello(fam.admin.token)
        val (bia, _) = connectAndHello(fam.bia.token)
        val callId = uuid()
        chefe.send(CallStartFrame("s1", callId, fam.direct.id, video = false))
        chefe.waitFor<CallStateFrame>()
        bia.send(CallJoinFrame("j1", callId))
        assertEquals("forbidden", bia.waitFor<ErrorFrame>().code)
        bia.send(CallSignalFrame(callId, fam.admin.deviceId, "offer", "x"))
        chefe.expectSilence()
        bia.send(CallStartFrame("s2", uuid(), fam.direct.id, video = false))
        assertEquals("forbidden", bia.waitFor<ErrorFrame>().code)
    }

    @Test
    fun `aparelho que conecta durante a chamada toca na hora`() = runBlocking {
        val fam = family3()
        val (chefe, _) = connectAndHello(fam.admin.token)
        val callId = uuid()
        chefe.send(CallStartFrame("s1", callId, fam.direct.id, video = false))
        chefe.waitFor<CallStateFrame>()
        val (ana, _) = connectAndHello(fam.ana.token)
        assertEquals(callId, ana.waitFor<CallRingFrame>().call.id)
    }

    @Test
    fun `internet caiu no meio da chamada - volta a tempo e continua, senao sai`() = runBlocking {
        val fam = family3()
        val (chefe, _) = connectAndHello(fam.admin.token)
        var (ana, _) = connectAndHello(fam.ana.token)
        val callId = uuid()
        chefe.send(CallStartFrame("s1", callId, fam.direct.id, video = false))
        chefe.waitFor<CallStateFrame>()
        ana.waitFor<CallRingFrame>()
        ana.send(CallJoinFrame("j1", callId))
        ana.waitFor<CallStateFrame>()
        chefe.waitFor<CallStateFrame>()

        // Cai e volta rápido: continua na chamada (e recebe o estado ao voltar).
        ana.close()
        ana = connectAndHello(fam.ana.token).first
        val back = ana.waitFor<CallStateFrame>()
        assertEquals(2, back.call.participants.size)
        kotlinx.coroutines.delay(700)
        assertEquals(1, server.calls.activeCount())

        // Cai e não volta: sai da chamada, que acaba (conversa a dois).
        ana.close()
        val end = chefe.waitFor<CallStateFrame>()
        assertTrue(end.call.ended)
    }

    @Test
    fun `servidores de rede - STUN gratis e TURN da Cloudflare so pelo administrador`() = runBlocking {
        val fam = family3()
        val (ana, helloAna) = connectAndHello(fam.ana.token)
        assertNull(helloAna.turnConfigured)
        ana.send(CallIceRequestFrame("i1"))
        val ice = ana.waitFor<CallIceServersFrame>()
        assertTrue(ice.servers.flatMap { it.urls }.all { it.startsWith("stun:") })

        ana.send(SetTurnFrame("t0", "abcdef0123456789", "token-bom-12345"))
        assertEquals("forbidden", ana.waitFor<ErrorFrame>().code)

        val (chefe, helloChefe) = connectAndHello(fam.admin.token)
        assertEquals(false, helloChefe.turnConfigured)
        chefe.send(SetTurnFrame("t1", "abcdef0123456789", "token-errado-999"))
        val bad = chefe.waitFor<ErrorFrame>()
        assertEquals("t1", bad.requestId)
        assertTrue(bad.message.contains("401"))

        chefe.send(SetTurnFrame("t2", "abcdef0123456789", "token-bom-12345"))
        val ok = chefe.waitFor<TurnStatusFrame>()
        assertTrue(ok.configured)

        ana.send(CallIceRequestFrame("i2"))
        val withTurn = ana.waitFor<CallIceServersFrame>().servers
        val turn = withTurn.first { s -> s.urls.any { it.startsWith("turn") } }
        assertEquals("usuario", turn.username)
        assertEquals("senha", turn.credential)
        assertFalse(turn.urls.any { it.contains(":53?") }, "porta 53 fica de fora")
        assertTrue(turn.urls.contains("turns:turn.cloudflare.com:443?transport=tcp"))
        val before = turnCalls
        ana.send(CallIceRequestFrame("i3"))
        ana.waitFor<CallIceServersFrame>()
        assertEquals(before, turnCalls, "credenciais ficam guardadas por algumas horas")

        chefe.send(SetTurnFrame("t3", "", ""))
        assertFalse(chefe.waitFor<TurnStatusFrame>().configured)
    }

    @Test
    fun `entende os dois formatos de resposta da Cloudflare`() {
        val obj = """{"iceServers":{"urls":["stun:stun.cloudflare.com:3478","turn:turn.cloudflare.com:3478?transport=udp"],"username":"u","credential":"c"}}"""
        val list = IceServers.parseCloudflare(obj)
        assertEquals(listOf("turn:turn.cloudflare.com:3478?transport=udp"), list.single().urls)
        assertEquals(3, IceServers.parseCloudflare(CF_REPLY).flatMap { it.urls }.size)
    }

    companion object {
        val CF_REPLY = """{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478","stun:stun.cloudflare.com:53"]},
            {"urls":["turn:turn.cloudflare.com:3478?transport=udp","turn:turn.cloudflare.com:53?transport=udp",
            "turn:turn.cloudflare.com:3478?transport=tcp","turns:turn.cloudflare.com:443?transport=tcp"],
            "username":"usuario","credential":"senha"}]}"""
    }
}
