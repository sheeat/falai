package app.falai.server

import app.falai.protocol.*

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

data class ServerConfig(
    /** 127.0.0.1 = só o cloudflared (no mesmo aparelho) alcança o servidor. Recomendado. */
    val host: String = "127.0.0.1",
    val port: Int = 8443,
    val syncBatchSize: Int = 500,
    /** Tentativas de pareamento com código errado por IP, dentro da janela. */
    val pairMaxFailures: Int = 10,
    val pairWindowMs: Long = 10 * 60_000L,
    /** Onde ficam fotos, vídeos, áudios e arquivos. No celular-servidor: o cartão microSD. */
    val mediaDir: java.io.File = java.io.File("data/media"),
    /** Chamada sem ninguém atender: desiste depois desse tempo. */
    val callRingTimeoutMs: Long = 45_000,
    /** Internet do aparelho caiu no meio da chamada: espera esse tempo antes de tirá-lo. */
    val callDropGraceMs: Long = 20_000,
    /** Como pedir credenciais do TURN à Cloudflare (trocado nos testes). */
    val turnFetch: (String, String) -> String = ::fetchCloudflareIce,
)

/** Limita tentativas de adivinhar códigos de convite. */
class FailureLimiter(private val max: Int, private val windowMs: Long, private val clock: () -> Long) {
    private val failures = ConcurrentHashMap<String, MutableList<Long>>()

    private fun recent(key: String): MutableList<Long> {
        val list = failures.getOrPut(key) { mutableListOf() }
        val cutoff = clock() - windowMs
        synchronized(list) { list.removeAll { it < cutoff } }
        return list
    }

    fun isBlocked(key: String): Boolean = recent(key).let { synchronized(it) { it.size >= max } }

    fun recordFailure(key: String) {
        val list = recent(key)
        synchronized(list) { list += clock() }
    }
}

/**
 * Conexões abertas, por pessoa. Cada conexão tem uma fila de saída própria, para que
 * um aparelho lento não trave a entrega para os outros.
 */
class Hub {
    class Conn(val device: AuthedDevice, val protocol: Int = 1) {
        val outbox = Channel<ServerFrame>(capacity = 1000)
        /** Nome atual da pessoa (muda se ela trocar o nome com o aparelho conectado). */
        @Volatile var name: String = device.user.name
    }

    private val byUser = ConcurrentHashMap<String, MutableSet<Conn>>()

    fun add(c: Conn) {
        byUser.computeIfAbsent(c.device.user.id) { ConcurrentHashMap.newKeySet() }.add(c)
    }

    fun remove(c: Conn) {
        byUser[c.device.user.id]?.remove(c)
        c.outbox.close()
    }

    /** Envia para todos os aparelhos das pessoas indicadas, exceto [exceptDeviceId]. */
    fun deliver(userIds: Collection<String>, frame: ServerFrame, exceptDeviceId: String? = null) {
        for (uid in userIds) {
            val conns = byUser[uid] ?: continue
            for (c in conns) {
                if (c.device.deviceId == exceptDeviceId) continue
                // Fila cheia = aparelho travado. Ele recupera tudo com "sync" ao reconectar.
                if (c.outbox.trySend(frame).isFailure) c.outbox.close()
            }
        }
    }

    /** Para todos os aparelhos conectados (ex.: alguém mudou a foto). */
    fun deliverAll(frame: ServerFrame, exceptDeviceId: String? = null) = deliver(byUser.keys.toList(), frame, exceptDeviceId)

    /** A pessoa trocou o nome: atualiza as conexões abertas dela (tela do servidor). */
    fun rename(userId: String, name: String) {
        byUser[userId]?.forEach { it.name = name }
    }

    /** Para um aparelho só (recados da chamada). */
    fun deliverToDevice(userId: String, deviceId: String, frame: ServerFrame) {
        byUser[userId]?.filter { it.device.deviceId == deviceId }?.forEach {
            if (it.outbox.trySend(frame).isFailure) it.outbox.close()
        }
    }

    /** A pessoa saiu da família: derruba as conexões abertas dela. */
    fun disconnectUser(userId: String) {
        byUser.remove(userId)?.forEach { it.outbox.close() }
    }

    fun isConnected(deviceId: String): Boolean = byUser.values.any { set -> set.any { it.device.deviceId == deviceId } }

    fun onlineDevices(userId: String): Int = byUser[userId]?.size ?: 0

    /** Total de aparelhos conectados agora (para a tela do servidor). */
    fun connectionCount(): Int = byUser.values.sumOf { it.size }

    /** Quem está conectado agora, ex.: "Ana (1a2b3c4d)". */
    fun connectedNames(): List<String> =
        byUser.values.flatten().map { "${it.name} (${it.device.deviceId.take(8)})" }.sorted()
}

class FalaiServer(
    private val store: Store,
    private val config: ServerConfig = ServerConfig(),
    log: (String) -> Unit = { println("[falai] $it") },
    clock: () -> Long = System::currentTimeMillis,
) {
    val hub = Hub()

    private val recent = java.util.ArrayDeque<String>()
    /** Últimos acontecimentos (para a tela do servidor ajudar a achar problemas). */
    fun recentEvents(): List<String> = synchronized(recent) { recent.toList() }

    private val log: (String) -> Unit = { msg ->
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        synchronized(recent) {
            recent.addLast("$time $msg")
            while (recent.size > 12) recent.removeFirst()
        }
        log(msg)
    }
    private val limiter = FailureLimiter(config.pairMaxFailures, config.pairWindowMs, clock)
    private val mediaFiles = MediaFiles(config.mediaDir, store, this.log)
    private val invites = InviteSupport(store, config.mediaDir, this.log)
        .also { it.onApkPublished = { info -> hub.deliverAll(AppUpdateFrame(info)) } }
    /** Falaí no computador: QR Codes esperando o celular, e um limite de pedidos por endereço. */
    val webLinks = WebLinks(clock)
    private val webLimiter = FailureLimiter(30, 10 * 60_000L, clock)
    /** Chamadas de voz e vídeo em andamento. */
    val calls = Calls(store, hub, this.log, clock, config.callRingTimeoutMs, config.callDropGraceMs)
    val iceServers = IceServers(store, this.log, config.turnFetch, clock)

    /** Endereço público aprendido pela Cloudflare (ex.: https://falai.falaifamilia.net), ou null. */
    fun publicBase(): String? = invites.publicBase()
    private var engine: EmbeddedServer<*, *>? = null

    /** Sobe o servidor. Se ainda não há ninguém cadastrado, devolve o código do administrador. */
    fun start(wait: Boolean = false): String? {
        val bootstrap = store.bootstrapInviteIfEmpty()
        if (bootstrap != null) {
            log("Nenhuma pessoa cadastrada. Código do administrador: ${bootstrap.first} (vale 24h)")
        }
        val e = embeddedServer(CIO, host = config.host, port = config.port) { module() }
        engine = e
        log("Servidor ouvindo em ${config.host}:${config.port}")
        e.start(wait = wait)
        return bootstrap?.first
    }

    /** Porta real (útil quando config.port = 0 nos testes). */
    suspend fun boundPort(): Int =
        engine!!.engine.resolvedConnectors().first().port

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
        calls.shutdown()
        engine = null
    }

    private fun clientKey(call: io.ktor.server.application.ApplicationCall): String {
        // Atrás do Cloudflare Tunnel, toda conexão vem de 127.0.0.1; o IP real vem neste cabeçalho.
        // Só confiamos nele quando a conexão é local (ou seja, veio do cloudflared).
        val remote = call.request.origin.remoteHost
        val cf = call.request.headers["CF-Connecting-IP"]
        return if (cf != null && (remote == "127.0.0.1" || remote == "localhost" || remote == "::1")) cf else remote
    }

    private fun json(frame: ServerFrame) = FalaiJson.encodeToString(ServerFrame.serializer(), frame)

    fun Application.module() {
        install(io.ktor.server.plugins.partialcontent.PartialContent)
        install(WebSockets) {
            pingPeriod = 20.seconds
            timeout = 45.seconds
            maxFrameSize = 256 * 1024
        }
        intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) { invites.noteRequest(context) }
        routing {
            get("/health") {
                call.respondText("""{"ok":true,"version":$PROTOCOL_VERSION}""", ContentType.Application.Json)
            }

            post("/api/pair") {
                val key = clientKey(call)
                if (limiter.isBlocked(key)) {
                    call.respondText(
                        FalaiJson.encodeToString(
                            ErrorResponse.serializer(),
                            ErrorResponse("rate_limited", "Muitas tentativas. Espere alguns minutos."),
                        ),
                        ContentType.Application.Json, HttpStatusCode.TooManyRequests,
                    )
                    return@post
                }
                val result = try {
                    val req = FalaiJson.decodeFromString(PairRequest.serializer(), call.receiveText())
                    Result.success(store.pair(req))
                } catch (e: FalaiException) {
                    Result.failure(e)
                } catch (e: SerializationException) {
                    Result.failure(FalaiException("invalid", "Pedido mal formado."))
                } catch (e: IllegalArgumentException) {
                    Result.failure(FalaiException("invalid", "Pedido mal formado."))
                }
                result.fold(
                    onSuccess = { resp ->
                        log("Aparelho pareado: device=${resp.deviceId} user=${resp.userId}")
                        call.respondText(
                            FalaiJson.encodeToString(PairResponse.serializer(), resp),
                            ContentType.Application.Json,
                        )
                    },
                    onFailure = { err ->
                        err as FalaiException
                        if (err.code == "invalid_code") limiter.recordFailure(key)
                        val status = if (err.code == "invalid_code") HttpStatusCode.Forbidden else HttpStatusCode.BadRequest
                        call.respondText(
                            FalaiJson.encodeToString(ErrorResponse.serializer(), ErrorResponse(err.code, err.message ?: "")),
                            ContentType.Application.Json, status,
                        )
                    },
                )
            }

            // App reinstalado num aparelho conhecido: volta sem convite.
            post("/api/reconnect") {
                val key = clientKey(call)
                fun err(code: String, msg: String) = FalaiJson.encodeToString(ErrorResponse.serializer(), ErrorResponse(code, msg))
                if (limiter.isBlocked(key)) {
                    call.respondText(err("rate_limited", "Muitas tentativas. Espere alguns minutos."), ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                    return@post
                }
                val req = try {
                    FalaiJson.decodeFromString(ReconnectRequest.serializer(), call.receiveText())
                } catch (e: Exception) {
                    call.respondText(err("invalid", "Pedido mal formado."), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
                val resp = store.reconnect(req)
                if (resp == null) {
                    limiter.recordFailure(key)
                    call.respondText(err("unknown_device", "Aparelho desconhecido. Use um convite."), ContentType.Application.Json, HttpStatusCode.NotFound)
                } else {
                    log("Aparelho voltou: ${resp.userName} (${resp.deviceId.take(8)})")
                    call.respondText(FalaiJson.encodeToString(PairResponse.serializer(), resp), ContentType.Application.Json)
                }
            }

            mediaRoutes(store, mediaFiles, log)
            inviteRoutes(store, invites, limiter, ::clientKey)
            webRoutes(store, webLinks, webLimiter, ::clientKey, log)

            webSocket("/ws") { handleSocket() }
        }
    }

    private suspend fun DefaultWebSocketServerSession.handleSocket() {
        // App: cabeçalho Authorization. Navegador: cookie (só vindo da própria página).
        val device = tokenFrom(call, checkOrigin = true)?.let(store::authenticate)
        if (device == null) {
            close(CloseReason(4001, "unauthorized"))
            return
        }
        val clientProtocol = call.request.queryParameters[CLIENT_PROTOCOL_PARAM]?.toIntOrNull() ?: 1
        val conn = Hub.Conn(device, clientProtocol)
        hub.add(conn)
        log("Conectado: ${device.user.name} (${device.deviceId.take(8)})")

        // Escritor: tudo que vai para este aparelho passa pela fila, em ordem.
        val writer = launch {
            try {
                for (f in conn.outbox) {
                    val out = forProtocol(f, conn.protocol) ?: continue
                    send(Frame.Text(json(out)))
                }
            } catch (_: ClosedSendChannelException) {
            } finally {
                // Fila fechada (aparelho travado ou desconectado): encerra a conexão.
                runCatching { close(CloseReason(CloseReason.Codes.GOING_AWAY, "bye")) }
            }
        }

        fun reply(f: ServerFrame) {
            conn.outbox.trySend(f)
        }

        try {
            reply(
                HelloFrame(
                    protocol = PROTOCOL_VERSION,
                    user = device.user,
                    deviceId = device.deviceId,
                    conversations = store.conversationsOf(device.user.id),
                    lastSeq = store.lastSeqFor(device.user.id),
                    publicBase = invites.publicBase(),
                    turnConfigured = if (device.user.isAdmin) iceServers.turnConfigured() else null,
                    app = invites.apkInfo(),
                )
            )
            calls.onConnected(conn)
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val parsed = try {
                    FalaiJson.decodeFromString(ClientFrame.serializer(), frame.readText())
                } catch (e: SerializationException) {
                    reply(ErrorFrame("invalid", "Quadro não reconhecido."))
                    continue
                } catch (e: IllegalArgumentException) {
                    reply(ErrorFrame("invalid", "Quadro não reconhecido."))
                    continue
                }
                try {
                    handleFrame(conn, parsed, ::reply)
                } catch (e: FalaiException) {
                    log("Recusado (${conn.name}): ${e.code} ${e.message}")
                    reply(ErrorFrame(e.code, e.message ?: "", requestIdOf(parsed)))
                } catch (e: Exception) {
                    // Erro inesperado: avisa o aparelho e segue com a conexão aberta.
                    log("Erro inesperado (${conn.name}): $e")
                    reply(ErrorFrame("server_error", "Erro no servidor: ${e.message ?: e.javaClass.simpleName}", requestIdOf(parsed)))
                }
            }
        } finally {
            hub.remove(conn)
            calls.onDisconnected(device)
            writer.cancel()
            log("Desconectado: ${conn.name} (${device.deviceId.take(8)})")
        }
    }

    private fun requestIdOf(f: ClientFrame): String? = when (f) {
        is SendFrame -> f.id
        is CreateConversationFrame -> f.requestId
        is ListUsersFrame -> f.requestId
        is CreateInviteFrame -> f.requestId
        is HideConversationFrame -> f.requestId
        is RemoveUserFrame -> f.requestId
        is RestoreUserFrame -> f.requestId
        is ReactFrame -> f.id
        is DeleteMessageFrame -> f.id
        is UpdateGroupFrame -> f.requestId
        is LeaveGroupFrame -> f.requestId
        is ReceiptFrame -> null
        is PingFrame -> f.requestId
        is SetPublicAddressFrame -> f.requestId
        is ApproveWebFrame -> f.requestId
        is UpdateProfileFrame -> f.requestId
        is CallStartFrame -> f.requestId
        is CallJoinFrame -> f.requestId
        is CallIceRequestFrame -> f.requestId
        is SetTurnFrame -> f.requestId
        is CallLeaveFrame, is CallDeclineFrame, is CallMediaFrame, is CallSignalFrame -> null
        is SyncFrame -> null
    }

    /**
     * Aparelho com app antigo: não entende reações nem "apagado para todos" (mostraria como
     * mensagem solta), então essas ficam de fora; a mensagem apagada chega com um aviso no texto.
     */
    private fun forProtocol(f: ServerFrame, protocol: Int): ServerFrame? {
        if (protocol >= 2) return f
        fun old(m: MessageDto): MessageDto? = when {
            m.kind == MessageKinds.REACTION || m.kind == MessageKinds.DELETE -> null
            m.deleted -> m.copy(body = "🚫 Mensagem apagada")
            else -> m
        }
        return when (f) {
            is MessageFrame -> old(f.message)?.let { MessageFrame(it) }
            is SyncBatchFrame -> SyncBatchFrame(f.messages.mapNotNull(::old))
            is ReceiptsFrame -> null
            else -> f
        }
    }

    /** Mudou o grupo: cada um recebe a conversa como vê, e depois os avisos (todos os aparelhos, inclusive o que mexeu). */
    private fun publishGroupChange(ch: Store.GroupChange, exceptDeviceId: String?) {
        for (uid in ch.notify) {
            store.conversationFor(ch.conversation.id, uid)?.let { hub.deliver(listOf(uid), ConversationFrame(null, it), exceptDeviceId) }
        }
        for (n in ch.notices) hub.deliver(ch.notify, MessageFrame(n))
    }

    private fun handleFrame(conn: Hub.Conn, f: ClientFrame, reply: (ServerFrame) -> Unit) {
        val device = conn.device
        when (f) {
            is PingFrame -> reply(PongFrame(f.requestId))

            is CallStartFrame -> reply(CallStateFrame(f.requestId, calls.start(device, f)))
            is CallJoinFrame -> reply(CallStateFrame(f.requestId, calls.join(device, f)))
            is CallLeaveFrame -> calls.leave(device, f.callId)
            is CallDeclineFrame -> calls.decline(device, f)
            is CallMediaFrame -> calls.media(device, f)
            is CallSignalFrame -> calls.signal(device, f)
            // Pode ir até a Cloudflare: responde em outra linha, sem travar a conexão.
            is CallIceRequestFrame -> Thread { reply(CallIceServersFrame(f.requestId, iceServers.servers())) }.start()
            is SetTurnFrame -> {
                if (!device.user.isAdmin) throw FalaiException("forbidden", "Só o administrador liga o ajudante de chamadas.")
                Thread {
                    try {
                        val (on, msg) = iceServers.configure(f.keyId, f.token)
                        reply(TurnStatusFrame(f.requestId, on, msg))
                    } catch (e: FalaiException) {
                        reply(ErrorFrame(e.code, e.message ?: "", f.requestId))
                    }
                }.start()
            }

            is UpdateProfileFrame -> {
                val u = store.updateProfile(device.user.id, f.name, f.avatarMediaId)
                hub.rename(u.id, u.name)
                log("Perfil atualizado: ${u.name}")
                reply(UserUpdatedFrame(f.requestId, u))
                // Todos os outros aparelhos (da família toda) veem o nome/foto novos.
                hub.deliverAll(UserUpdatedFrame(null, u), exceptDeviceId = device.deviceId)
            }

            is ApproveWebFrame -> {
                val code = WebLinkCodes.parse(f.code)
                    ?: throw FalaiException("invalid", "Código inválido. Confira o QR Code na tela do computador.")
                val link = webLinks.byCode(code)
                    ?: throw FalaiException("not_found", "Esse QR Code já venceu. Atualize a página no computador e tente de novo.")
                val (deviceId, token) = store.createLinkedDevice(device.user.id, link.browser)
                link.approvedBy = device.user.id
                link.token = token
                log("Navegador ligado: ${conn.name} (${link.browser}, ${deviceId.take(8)})")
                reply(WebApprovedFrame(f.requestId, link.browser))
            }

            is SetPublicAddressFrame -> {
                if (!device.user.isAdmin) throw FalaiException("forbidden", "Só o administrador muda o endereço.")
                val base = invites.setAddress(f.address)
                    ?: throw FalaiException("invalid", "Endereço inválido. Exemplo: falai.seudominio.com")
                reply(PublicAddressFrame(f.requestId, base))
            }

            is SendFrame -> {
                val stored = store.storeMessage(device, f)
                val m = stored.message
                reply(AckFrame(m.id, m.seq, m.createdAt))
                if (stored.isNew) {
                    // Quem tinha apagado esta conversa volta a vê-la (só com as mensagens novas).
                    for (uid in store.unhideForNewMessage(m.conversationId)) {
                        store.conversationFor(m.conversationId, uid)?.let { hub.deliver(listOf(uid), ConversationFrame(null, it)) }
                    }
                    hub.deliver(store.memberIds(m.conversationId), MessageFrame(m), exceptDeviceId = device.deviceId)
                }
            }

            is ReactFrame -> {
                val stored = store.react(device, f)
                val m = stored.message
                reply(AckFrame(m.id, m.seq, m.createdAt))
                if (stored.isNew) hub.deliver(store.memberIds(m.conversationId), MessageFrame(m), exceptDeviceId = device.deviceId)
            }

            is DeleteMessageFrame -> {
                val stored = store.deleteForEveryone(device, f)
                val m = stored.message
                reply(AckFrame(m.id, m.seq, m.createdAt))
                if (stored.isNew) hub.deliver(store.memberIds(m.conversationId), MessageFrame(m), exceptDeviceId = device.deviceId)
            }

            is UpdateGroupFrame -> {
                val ch = store.updateGroup(device, f)
                reply(ConversationFrame(f.requestId, store.conversationFor(ch.conversation.id, device.user.id)!!))
                publishGroupChange(ch, exceptDeviceId = device.deviceId)
            }

            is LeaveGroupFrame -> {
                val ch = store.leaveGroup(device, f.conversationId)
                reply(ConversationFrame(f.requestId, store.conversationFor(ch.conversation.id, device.user.id)!!))
                publishGroupChange(ch, exceptDeviceId = device.deviceId)
            }

            is ReceiptFrame -> {
                val r = store.updateReceipt(device.user.id, f.conversationId, f.deliveredSeq, f.readSeq)
                if (r != null) hub.deliver(store.memberIds(f.conversationId), ReceiptsFrame(f.conversationId, r), exceptDeviceId = device.deviceId)
            }

            is SyncFrame -> {
                var after = f.afterSeq.coerceAtLeast(0)
                while (true) {
                    val batch = store.messagesSince(device.user.id, after, config.syncBatchSize)
                    if (batch.isEmpty()) break
                    reply(SyncBatchFrame(batch))
                    after = batch.last().seq
                    if (batch.size < config.syncBatchSize) break
                }
                reply(SyncDoneFrame(maxOf(after, f.afterSeq)))
            }

            is CreateConversationFrame -> {
                val c = store.createConversation(device.user, f.kind, f.title, f.memberIds)
                reply(ConversationFrame(f.requestId, c))
                // Os outros participantes (e meus outros aparelhos) ficam sabendo da conversa nova.
                hub.deliver(listOf(device.user.id), ConversationFrame(null, c), exceptDeviceId = device.deviceId)
                hub.deliver(c.memberIds - device.user.id, ConversationFrame(null, c.copy(clearedSeq = null)))
            }

            is HideConversationFrame -> {
                val cleared = store.hideConversation(device.user.id, f.conversationId)
                reply(ConversationHiddenFrame(f.requestId, f.conversationId, cleared))
                hub.deliver(listOf(device.user.id), ConversationHiddenFrame(null, f.conversationId, cleared), exceptDeviceId = device.deviceId)
            }

            is RestoreUserFrame -> {
                val u = store.restoreUser(device.user, f.userId)
                log("${conn.name} trouxe ${u.name} de volta para a família")
                reply(UserUpdatedFrame(f.requestId, u))
                hub.deliverAll(UserUpdatedFrame(null, u), exceptDeviceId = device.deviceId)
            }

            is RemoveUserFrame -> {
                val u = store.removeUser(device.user, f.userId)
                log("${conn.name} tirou ${u.name} da família")
                hub.disconnectUser(u.id)
                reply(UserUpdatedFrame(f.requestId, u))
                hub.deliverAll(UserUpdatedFrame(null, u), exceptDeviceId = device.deviceId)
            }

            is ListUsersFrame -> reply(UsersFrame(f.requestId, store.listUsers()))

            is CreateInviteFrame -> {
                val (code, expires) = store.createInvite(device.user, f.forUserId, suggestedName = f.name)
                log("Convite criado por ${conn.name}")
                reply(InviteFrame(f.requestId, code, expires, invites.linkFor(code)))
            }
        }
    }
}
