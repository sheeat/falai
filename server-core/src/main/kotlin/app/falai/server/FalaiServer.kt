package app.falai.server

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
    class Conn(val device: AuthedDevice) {
        val outbox = Channel<ServerFrame>(capacity = 1000)
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

    fun onlineDevices(userId: String): Int = byUser[userId]?.size ?: 0
}

class FalaiServer(
    private val store: Store,
    private val config: ServerConfig = ServerConfig(),
    private val log: (String) -> Unit = { println("[falai] $it") },
    clock: () -> Long = System::currentTimeMillis,
) {
    val hub = Hub()
    private val limiter = FailureLimiter(config.pairMaxFailures, config.pairWindowMs, clock)
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
        install(WebSockets) {
            pingPeriod = 20.seconds
            timeout = 45.seconds
            maxFrameSize = 256 * 1024
        }
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

            webSocket("/ws") { handleSocket() }
        }
    }

    private suspend fun DefaultWebSocketServerSession.handleSocket() {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        val device = token?.takeIf { it.isNotEmpty() }?.let(store::authenticate)
        if (device == null) {
            close(CloseReason(4001, "unauthorized"))
            return
        }
        val conn = Hub.Conn(device)
        hub.add(conn)
        log("Conectado: ${device.user.name} (${device.deviceId.take(8)})")

        // Escritor: tudo que vai para este aparelho passa pela fila, em ordem.
        val writer = launch {
            try {
                for (f in conn.outbox) send(Frame.Text(json(f)))
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
                )
            )
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
                    reply(ErrorFrame(e.code, e.message ?: "", requestIdOf(parsed)))
                }
            }
        } finally {
            hub.remove(conn)
            writer.cancel()
            log("Desconectado: ${device.user.name} (${device.deviceId.take(8)})")
        }
    }

    private fun requestIdOf(f: ClientFrame): String? = when (f) {
        is SendFrame -> f.id
        is CreateConversationFrame -> f.requestId
        is ListUsersFrame -> f.requestId
        is CreateInviteFrame -> f.requestId
        is PingFrame -> f.requestId
        is SyncFrame -> null
    }

    private fun handleFrame(conn: Hub.Conn, f: ClientFrame, reply: (ServerFrame) -> Unit) {
        val device = conn.device
        when (f) {
            is PingFrame -> reply(PongFrame(f.requestId))

            is SendFrame -> {
                val stored = store.storeMessage(device, f)
                val m = stored.message
                reply(AckFrame(m.id, m.seq, m.createdAt))
                if (stored.isNew) {
                    hub.deliver(store.memberIds(m.conversationId), MessageFrame(m), exceptDeviceId = device.deviceId)
                }
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
                hub.deliver(c.memberIds, ConversationFrame(null, c), exceptDeviceId = device.deviceId)
            }

            is ListUsersFrame -> reply(UsersFrame(f.requestId, store.listUsers()))

            is CreateInviteFrame -> {
                val (code, expires) = store.createInvite(device.user, f.forUserId)
                log("Convite criado por ${device.user.name}")
                reply(InviteFrame(f.requestId, code, expires))
            }
        }
    }
}
