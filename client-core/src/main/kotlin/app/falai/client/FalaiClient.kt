package app.falai.client

import app.falai.protocol.AckFrame
import app.falai.protocol.ClientFrame
import app.falai.protocol.ConversationDto
import app.falai.protocol.ConversationFrame
import app.falai.protocol.CreateConversationFrame
import app.falai.protocol.CreateInviteFrame
import app.falai.protocol.ErrorFrame
import app.falai.protocol.ErrorResponse
import app.falai.protocol.FalaiJson
import app.falai.protocol.HelloFrame
import app.falai.protocol.InviteFrame
import app.falai.protocol.ListUsersFrame
import app.falai.protocol.MessageFrame
import app.falai.protocol.PairRequest
import app.falai.protocol.PairResponse
import app.falai.protocol.PongFrame
import app.falai.protocol.SendFrame
import app.falai.protocol.ServerFrame
import app.falai.protocol.SyncBatchFrame
import app.falai.protocol.SyncDoneFrame
import app.falai.protocol.SyncFrame
import app.falai.protocol.UsersFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class ConnectionState { OFFLINE, CONNECTING, ONLINE, UNAUTHORIZED }

/** Erro que pode ser mostrado direto para a pessoa. */
class FalaiClientException(val code: String, message: String) : Exception(message)

/**
 * O "motor" do app: fala com o servidor, guarda tudo no banco local e mantém a fila de envio.
 *
 * A interface só precisa observar [connection] e [changes] e ler o banco pelo [store].
 */
class FalaiClient(
    val store: ClientStore,
    private val scope: CoroutineScope,
    private val http: OkHttpClient = defaultHttp(),
    private val log: (String) -> Unit = {},
    private val reconnectDelaysMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000, 30_000),
) {
    private val _connection = MutableStateFlow(ConnectionState.OFFLINE)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _changes = MutableStateFlow(0L)
    /** Muda de valor sempre que algo no banco local muda. A tela recarrega quando isso acontece. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private val _me = MutableStateFlow<Account?>(store.account())
    val account: StateFlow<Account?> = _me.asStateFlow()

    private var loop: Job? = null
    @Volatile private var socket: WebSocket? = null
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<ServerFrame>>()

    private fun bump() { _changes.value = _changes.value + 1 }

    // ---------- Pareamento ----------

    /** Troca o código de convite por uma conta neste aparelho. */
    suspend fun pair(serverUrl: String, code: String, deviceName: String, userName: String?): Account =
        withContext(Dispatchers.IO) {
            val base = normalizeUrl(serverUrl)
            val body = FalaiJson.encodeToString(PairRequest.serializer(), PairRequest(code.trim(), deviceName, userName))
            val req = Request.Builder()
                .url("$base/api/pair")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = try {
                http.newCall(req).execute()
            } catch (e: IOException) {
                throw FalaiClientException("network", "Não consegui falar com o servidor. Confira o endereço e a internet.")
            }
            resp.use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val err = runCatching { FalaiJson.decodeFromString(ErrorResponse.serializer(), text) }.getOrNull()
                    throw FalaiClientException(err?.error ?: "http_${r.code}", err?.message ?: "Erro do servidor (${r.code}).")
                }
                val p = FalaiJson.decodeFromString(PairResponse.serializer(), text)
                val acc = Account(base, p.token, p.userId, p.deviceId, p.isAdmin)
                store.saveAccount(acc)
                _me.value = acc
                bump()
                acc
            }
        }

    // ---------- Conexão ----------

    /** Liga a conexão automática (reconecta sozinho se cair). */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive) {
                val acc = store.account() ?: break
                _connection.value = ConnectionState.CONNECTING
                val result = runSession(acc)
                if (result == SessionEnd.UNAUTHORIZED) {
                    _connection.value = ConnectionState.UNAUTHORIZED
                    break
                }
                _connection.value = ConnectionState.OFFLINE
                if (result == SessionEnd.WAS_ONLINE) attempt = 0
                delay(reconnectDelaysMs[minOf(attempt, reconnectDelaysMs.lastIndex)])
                attempt++
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        socket?.close(1000, "bye")
        socket = null
        _connection.value = ConnectionState.OFFLINE
    }

    /** Força uma reconexão imediata (ex.: a internet voltou). */
    fun reconnectNow() {
        socket?.cancel()
    }

    private enum class SessionEnd { FAILED, WAS_ONLINE, UNAUTHORIZED }

    private sealed interface Event {
        data class Text(val text: String) : Event
        data class Closed(val code: Int) : Event
        data object Failed : Event
    }

    private suspend fun runSession(acc: Account): SessionEnd {
        val events = Channel<Event>(Channel.UNLIMITED)
        val req = Request.Builder()
            .url("${acc.serverUrl}/ws")
            .header("Authorization", "Bearer ${acc.token}")
            .build()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { events.trySend(Event.Text(text)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                events.trySend(Event.Closed(code))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.trySend(Event.Closed(code)) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log("Falha na conexão: ${t.message}")
                events.trySend(Event.Failed)
            }
        })
        socket = ws
        var wasOnline = false
        try {
            for (ev in events) {
                when (ev) {
                    is Event.Text -> {
                        val frame = try {
                            FalaiJson.decodeFromString(ServerFrame.serializer(), ev.text)
                        } catch (e: Exception) {
                            log("Quadro desconhecido ignorado")
                            continue
                        }
                        if (frame is HelloFrame) wasOnline = true
                        handle(acc, ws, frame)
                    }
                    is Event.Closed -> return when {
                        ev.code == 4001 -> SessionEnd.UNAUTHORIZED
                        wasOnline -> SessionEnd.WAS_ONLINE
                        else -> SessionEnd.FAILED
                    }
                    Event.Failed -> return if (wasOnline) SessionEnd.WAS_ONLINE else SessionEnd.FAILED
                }
            }
            return SessionEnd.FAILED
        } finally {
            ws.cancel()
            if (socket === ws) socket = null
            // Pedidos sem resposta falham na hora, em vez de esperar o tempo todo.
            pendingRequests.values.forEach {
                it.completeExceptionally(FalaiClientException("offline", "Sem conexão com o servidor."))
            }
            pendingRequests.clear()
        }
    }

    private fun sendFrame(ws: WebSocket?, f: ClientFrame): Boolean =
        ws?.send(FalaiJson.encodeToString(ClientFrame.serializer(), f)) ?: false

    private fun handle(acc: Account, ws: WebSocket, f: ServerFrame) {
        when (f) {
            is HelloFrame -> {
                store.upsertUser(f.user)
                f.conversations.forEach(store::upsertConversation)
                if (f.user.isAdmin != acc.isAdmin) {
                    val updated = acc.copy(isAdmin = f.user.isAdmin)
                    store.saveAccount(updated)
                    _me.value = updated
                }
                _connection.value = ConnectionState.ONLINE
                sendFrame(ws, ListUsersFrame("hello-users"))
                sendFrame(ws, SyncFrame(store.syncedSeq()))
                // Esvazia a fila: tudo que foi escrito sem conexão sai agora, na ordem.
                store.pending().forEach { sendFrame(ws, SendFrame(it.id, it.conversationId, it.body)) }
                bump()
            }
            is AckFrame -> { store.markSent(f.id, f.seq, f.createdAt); bump() }
            is MessageFrame -> { store.upsertFromServer(f.message, acc.userId); bump() }
            is SyncBatchFrame -> { f.messages.forEach { store.upsertFromServer(it, acc.userId) }; bump() }
            is SyncDoneFrame -> store.setSyncedSeq(maxOf(store.syncedSeq(), f.lastSeq))
            is ConversationFrame -> {
                store.upsertConversation(f.conversation)
                bump()
                f.requestId?.let { pendingRequests.remove(it)?.complete(f) }
                // Conversa nova pode trazer gente que este aparelho ainda não conhece.
                if (f.conversation.memberIds.any { store.userName(it) == null }) {
                    sendFrame(ws, ListUsersFrame("refresh-users"))
                }
            }
            is UsersFrame -> {
                store.replaceUsers(f.users)
                bump()
                pendingRequests.remove(f.requestId)?.complete(f)
            }
            is InviteFrame -> pendingRequests.remove(f.requestId)?.complete(f)
            is PongFrame -> pendingRequests.remove(f.requestId)?.complete(f)
            is ErrorFrame -> {
                log("Erro do servidor: ${f.code} ${f.message}")
                val waiting = f.requestId?.let { pendingRequests.remove(it) }
                if (waiting != null) {
                    waiting.completeExceptionally(FalaiClientException(f.code, f.message))
                } else if (f.requestId != null && f.code in setOf("forbidden", "invalid", "too_large", "conflict")) {
                    // Mensagem recusada de vez: sai da fila e aparece como falha.
                    log("Mensagem ${f.requestId} recusada: ${f.message}")
                    store.markFailed(f.requestId!!)
                    bump()
                }
            }
        }
    }

    // ---------- Ações da tela ----------

    /** Escreve a mensagem no banco (aparece na hora com o relógio) e envia se estiver online. */
    fun send(conversationId: String, body: String): LocalMessage {
        val acc = store.account() ?: throw FalaiClientException("not_paired", "Aparelho não pareado.")
        val text = body.trim()
        require(text.isNotEmpty()) { "Mensagem vazia" }
        val m = store.insertPending(UUID.randomUUID().toString(), conversationId, acc.userId, text)
        bump()
        if (_connection.value == ConnectionState.ONLINE) sendFrame(socket, SendFrame(m.id, m.conversationId, m.body))
        return m
    }

    private suspend fun request(requestId: String, frame: ClientFrame): ServerFrame {
        if (_connection.value != ConnectionState.ONLINE) {
            throw FalaiClientException("offline", "Sem conexão com o servidor. Tente de novo em instantes.")
        }
        val deferred = CompletableDeferred<ServerFrame>()
        pendingRequests[requestId] = deferred
        if (!sendFrame(socket, frame)) {
            pendingRequests.remove(requestId)
            throw FalaiClientException("offline", "Sem conexão com o servidor.")
        }
        return try {
            withTimeout(15_000) { deferred.await() }
        } finally {
            pendingRequests.remove(requestId)
        }
    }

    suspend fun createConversation(kind: String, title: String?, memberIds: List<String>): ConversationDto {
        val id = UUID.randomUUID().toString()
        val f = request(id, CreateConversationFrame(id, kind, title, memberIds)) as ConversationFrame
        return f.conversation
    }

    /** Gera um código de convite. [forUserId] preenchido = mais um aparelho para essa pessoa. */
    suspend fun createInvite(forUserId: String? = null): InviteFrame {
        val id = UUID.randomUUID().toString()
        return request(id, CreateInviteFrame(id, forUserId)) as InviteFrame
    }

    suspend fun refreshUsers() {
        val id = UUID.randomUUID().toString()
        request(id, ListUsersFrame(id))
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // WebSocket: sem limite de leitura
            .pingInterval(25, TimeUnit.SECONDS)
            .build()

        /** Aceita "falai.meudominio.com", "https://..." ou "http://10.0.2.2:8443". */
        fun normalizeUrl(input: String): String {
            var s = input.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            return s
        }
    }
}
