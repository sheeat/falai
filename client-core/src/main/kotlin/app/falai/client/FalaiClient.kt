package app.falai.client

import app.falai.protocol.AckFrame
import app.falai.protocol.AppApkInfo
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
import app.falai.protocol.PublicAddressFrame
import app.falai.protocol.SetPublicAddressFrame
import app.falai.protocol.SendFrame
import app.falai.protocol.ServerFrame
import app.falai.protocol.SyncBatchFrame
import app.falai.protocol.SyncDoneFrame
import app.falai.protocol.SyncFrame
import app.falai.protocol.UsersFrame
import app.falai.protocol.UpdateProfileFrame
import app.falai.protocol.UserUpdatedFrame
import app.falai.protocol.UserDto
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
import okhttp3.RequestBody.Companion.asRequestBody
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
    /** Pasta deste aparelho onde ficam os arquivos baixados e enviados. */
    private val mediaDir: java.io.File,
    private val http: OkHttpClient = defaultHttp(),
    val log: (String) -> Unit = {},
    private val reconnectDelaysMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000, 30_000),
    /** Se o servidor não disser "olá" nesse tempo, a tentativa é abandonada e refeita. */
    private val handshakeTimeoutMs: Long = 20_000,
) {
    /** Conexões HTTP comuns (envio e download de arquivos) não podem ficar presas para sempre. */
    private val mediaHttp: OkHttpClient = http.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Acorda a espera entre tentativas (internet voltou, app abriu, botão "Reconectar"). */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val uploadWake = Channel<Unit>(Channel.CONFLATED)

    private val _lastError = MutableStateFlow<String?>(null)
    /** Último problema de conexão ou de envio, para mostrar na tela. Some quando volta a funcionar. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private suspend fun sleepOrWake(ms: Long, channel: Channel<Unit>) {
        kotlinx.coroutines.withTimeoutOrNull(ms) { channel.receive() }
    }
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

    /** Um id de tentativa por código: repetir com o mesmo código reaproveita o id (ver PairRequest). */
    private val pairAttempts = ConcurrentHashMap<String, String>()

    /**
     * Troca o código de convite por uma conta neste aparelho. Se a internet oscilar, tenta de novo
     * sozinho; o servidor reconhece a repetição e o convite não se perde.
     */
    suspend fun pair(serverUrl: String, code: String, deviceName: String, userName: String?, installKey: String? = null): Account =
        withContext(Dispatchers.IO) {
            val base = normalizeUrl(serverUrl)
            val cleanCode = code.trim()
            val attemptId = pairAttempts.getOrPut(cleanCode.uppercase().filter { it.isLetterOrDigit() }) { UUID.randomUUID().toString() }
            val body = FalaiJson.encodeToString(PairRequest.serializer(), PairRequest(cleanCode, deviceName, userName, attemptId, installKey))
            val req = Request.Builder()
                .url("$base/api/pair")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            var lastError: IOException? = null
            for (attempt in 0 until 3) {
                if (attempt > 0) delay(1500L * attempt)
                val resp = try {
                    mediaHttp.newCall(req).execute()
                } catch (e: IOException) {
                    log("Pareamento: falha de rede (${e.message}), tentativa ${attempt + 1}")
                    lastError = e
                    continue
                }
                return@withContext resp.use { r ->
                    val text = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        val err = runCatching { FalaiJson.decodeFromString(ErrorResponse.serializer(), text) }.getOrNull()
                        throw FalaiClientException(err?.error ?: "http_${r.code}", err?.message ?: "Erro do servidor (${r.code}).")
                    }
                    val p = FalaiJson.decodeFromString(PairResponse.serializer(), text)
                    saveAccountFrom(base, p)
                }
            }
            throw FalaiClientException(
                "network",
                "Não consegui falar com o servidor (${lastError?.message ?: "sem resposta"}). Confira o endereço e a internet e tente de novo com o mesmo código.",
            )
        }

    private fun saveAccountFrom(base: String, p: PairResponse): Account {
        val acc = Account(base, p.token, p.userId, p.deviceId, p.isAdmin)
        store.saveAccount(acc)
        p.userName?.let { store.upsertUser(UserDto(p.userId, it, p.isAdmin)) }
        _me.value = acc
        bump()
        return acc
    }

    /**
     * O app foi instalado de novo num aparelho que o servidor já conhece ([installKey] igual):
     * entra direto na conta da pessoa, sem convite. Devolve o nome da pessoa, ou null se o
     * servidor não conhece este aparelho (aí precisa de convite).
     */
    suspend fun reconnect(serverUrl: String, installKey: String, deviceName: String): String? = withContext(Dispatchers.IO) {
        val base = normalizeUrl(serverUrl)
        val body = FalaiJson.encodeToString(
            app.falai.protocol.ReconnectRequest.serializer(), app.falai.protocol.ReconnectRequest(installKey, deviceName),
        )
        val req = Request.Builder().url("$base/api/reconnect").post(body.toRequestBody("application/json".toMediaType())).build()
        val resp = try {
            mediaHttp.newCall(req).execute()
        } catch (e: IOException) {
            throw FalaiClientException("network", "Não consegui falar com o servidor. Confira a internet.")
        }
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            when {
                r.code == 404 -> null
                !r.isSuccessful -> {
                    val err = runCatching { FalaiJson.decodeFromString(ErrorResponse.serializer(), text) }.getOrNull()
                    throw FalaiClientException(err?.error ?: "http_${r.code}", err?.message ?: "Erro do servidor (${r.code}).")
                }
                else -> {
                    val p = FalaiJson.decodeFromString(PairResponse.serializer(), text)
                    saveAccountFrom(base, p)
                    p.userName ?: ""
                }
            }
        }
    }

    // ---------- Arquivo do app (para quem for convidado baixar) ----------

    /**
     * Só o administrador: se o servidor não tem este app (ou tem um mais antigo), envia o arquivo.
     * Devolve a versão que ficou no servidor, ou null se não deu.
     */
    suspend fun publishAppIfNewer(apk: java.io.File, versionCode: Long, versionName: String): AppApkInfo? =
        withContext(Dispatchers.IO) {
            val acc = store.account() ?: return@withContext null
            if (!acc.isAdmin || !apk.exists()) return@withContext null
            val current = runCatching {
                mediaHttp.newCall(Request.Builder().url("${acc.serverUrl}/api/app-apk").build()).execute().use { r ->
                    val t = r.body?.string().orEmpty().trim()
                    if (!r.isSuccessful || t == "null" || t.isEmpty()) null
                    else FalaiJson.decodeFromString(AppApkInfo.serializer(), t)
                }
            }.getOrNull()
            if (current != null && current.versionCode >= versionCode) return@withContext current
            val req = Request.Builder()
                .url("${acc.serverUrl}/api/app-apk?versionCode=$versionCode&versionName=" + java.net.URLEncoder.encode(versionName, "UTF-8"))
                .header("Authorization", "Bearer ${acc.token}")
                .put(apk.asRequestBody("application/vnd.android.package-archive".toMediaType()))
                .build()
            try {
                mediaHttp.newCall(req).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    if (!r.isSuccessful) { log("Envio do app falhou: ${r.code} $t"); null }
                    else FalaiJson.decodeFromString(AppApkInfo.serializer(), t).also { log("App ${it.versionName} enviado ao servidor") }
                }
            } catch (e: IOException) {
                log("Envio do app falhou: ${e.message}")
                null
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
                sleepOrWake(reconnectDelaysMs[minOf(attempt, reconnectDelaysMs.lastIndex)], wake)
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

    /**
     * Força uma reconexão imediata (ex.: a internet voltou).
     * [force] = true (botão "Reconectar"): derruba e refaz a conexão mesmo se parecer online.
     */
    fun reconnectNow(force: Boolean = false) {
        // Desligado pelo servidor (ex.: tirado da família e trazido de volta): tenta de novo do zero.
        if (loop?.isActive != true && store.account() != null) {
            start()
            return
        }
        if (force || _connection.value != ConnectionState.ONLINE) socket?.cancel()
        wake.trySend(Unit)
        uploadWake.trySend(Unit)
    }

    private enum class SessionEnd { FAILED, WAS_ONLINE, UNAUTHORIZED }

    private sealed interface Event {
        data class Text(val text: String) : Event
        data class Closed(val code: Int) : Event
        data object Failed : Event
    }

    private suspend fun runSession(acc: Account): SessionEnd {
        val events = Channel<Event>(Channel.UNLIMITED)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
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
                if (!timedOut.get()) {
                    _lastError.value = "Não consegui falar com o servidor (${t.message ?: t.javaClass.simpleName})."
                }
                events.trySend(Event.Failed)
            }
        })
        socket = ws
        var wasOnline = false
        // Se a conexão abrir mas o servidor nunca responder (ex.: "ponte" do cabo meio caída),
        // não fica preso em "conectando…" para sempre.
        val watchdog = scope.launch {
            delay(handshakeTimeoutMs)
            if (!wasOnline) {
                timedOut.set(true)
                log("Servidor não respondeu em ${handshakeTimeoutMs / 1000}s; tentando de novo")
                _lastError.value = "O servidor não respondeu. Tentando de novo…"
                ws.cancel()
            }
        }
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
                        if (frame is HelloFrame) {
                            wasOnline = true
                            _lastError.value = null
                        }
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
            watchdog.cancel()
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
                // A lista do servidor é a verdade: conversa apagada em outro aparelho some daqui também.
                val keep = f.conversations.map { it.id }.toSet()
                store.conversationIds().filter { it !in keep }.forEach(store::deleteConversation)
                f.conversations.forEach {
                    store.upsertConversation(it)
                    it.clearedSeq?.let { s -> store.clearMessagesUpTo(it.id, s) }
                }
                if (f.user.isAdmin != acc.isAdmin) {
                    val updated = acc.copy(isAdmin = f.user.isAdmin)
                    store.saveAccount(updated)
                    _me.value = updated
                }
                _publicBase.value = f.publicBase
                _turnConfigured.value = f.turnConfigured
                // Chamadas: o servidor manda logo em seguida as que ainda estão em andamento.
                _callEvents.tryEmit(f)
                _connection.value = ConnectionState.ONLINE
                sendFrame(ws, ListUsersFrame("hello-users"))
                sendFrame(ws, SyncFrame(store.syncedSeq()))
                // Esvazia a fila: tudo que foi escrito sem conexão sai agora, na ordem.
                store.pending().forEach { sendFrame(ws, SendFrame(it.id, it.conversationId, it.body, it.attachment)) }
                bump()
                kickUploads()
            }
            is AckFrame -> { store.markSent(f.id, f.seq, f.createdAt); bump() }
            is MessageFrame -> { store.upsertFromServer(f.message, acc.userId); bump() }
            is SyncBatchFrame -> { f.messages.forEach { store.upsertFromServer(it, acc.userId) }; bump() }
            is SyncDoneFrame -> store.setSyncedSeq(maxOf(store.syncedSeq(), f.lastSeq))
            is ConversationFrame -> {
                store.upsertConversation(f.conversation)
                f.conversation.clearedSeq?.let { store.clearMessagesUpTo(f.conversation.id, it) }
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
            is app.falai.protocol.ConversationHiddenFrame -> {
                store.deleteConversation(f.conversationId)
                bump()
                f.requestId?.let { pendingRequests.remove(it)?.complete(f) }
            }
            is PongFrame -> pendingRequests.remove(f.requestId)?.complete(f)
            is app.falai.protocol.WebApprovedFrame -> pendingRequests.remove(f.requestId)?.complete(f)
            is UserUpdatedFrame -> {
                store.upsertUser(f.user)
                bump()
                f.requestId?.let { pendingRequests.remove(it)?.complete(f) }
            }
            is PublicAddressFrame -> {
                _publicBase.value = f.publicBase
                pendingRequests.remove(f.requestId)?.complete(f)
            }
            is app.falai.protocol.CallStateFrame -> {
                f.requestId?.let { pendingRequests.remove(it)?.complete(f) }
                _callEvents.tryEmit(f)
            }
            is app.falai.protocol.CallRingFrame -> _callEvents.tryEmit(f)
            is app.falai.protocol.CallSignalInFrame -> _callEvents.tryEmit(f)
            is app.falai.protocol.CallIceServersFrame -> pendingRequests.remove(f.requestId)?.complete(f)
            is app.falai.protocol.TurnStatusFrame -> {
                _turnConfigured.value = f.configured
                pendingRequests.remove(f.requestId)?.complete(f)
            }
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

    /**
     * Encaminha uma mensagem para outra conversa. Com arquivo, usa o que já está no servidor:
     * não sobe de novo (rápido e não gasta internet).
     */
    fun forward(conversationId: String, original: LocalMessage): LocalMessage {
        val att = original.attachment
        if (att == null) return send(conversationId, original.body)
        val acc = store.account() ?: throw FalaiClientException("not_paired", "Aparelho não pareado.")
        if (att.mediaId.isEmpty()) throw FalaiClientException("uploading", "Espere o arquivo terminar de subir.")
        val m = store.insertPendingForward(UUID.randomUUID().toString(), conversationId, acc.userId, "", att)
        bump()
        if (_connection.value == ConnectionState.ONLINE) sendFrame(socket, SendFrame(m.id, m.conversationId, m.body, m.attachment))
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
    suspend fun createInvite(forUserId: String? = null, name: String? = null): InviteFrame {
        val id = UUID.randomUUID().toString()
        return request(id, CreateInviteFrame(id, forUserId, name?.trim()?.takeIf { it.isNotEmpty() })) as InviteFrame
    }

    /** "Apagar conversa": some da lista em todos os meus aparelhos (para os outros nada muda). */
    suspend fun hideConversation(conversationId: String) {
        val id = UUID.randomUUID().toString()
        request(id, app.falai.protocol.HideConversationFrame(id, conversationId))
        store.deleteConversation(conversationId)
        bump()
    }

    /** Só o administrador: traz de volta quem foi tirado da família. */
    suspend fun restoreUser(userId: String): UserDto {
        val id = UUID.randomUUID().toString()
        val u = (request(id, app.falai.protocol.RestoreUserFrame(id, userId)) as UserUpdatedFrame).user
        store.upsertUser(u)
        bump()
        return u
    }

    /** Só o administrador: tira a pessoa da família (os aparelhos dela são desligados). */
    suspend fun removeUser(userId: String): UserDto {
        val id = UUID.randomUUID().toString()
        return (request(id, app.falai.protocol.RemoveUserFrame(id, userId)) as UserUpdatedFrame).user
    }

    private val _publicBase = MutableStateFlow<String?>(null)
    /** Endereço do Falaí na internet (ex.: https://falai.falaifamilia.net), se o servidor já souber. */
    val publicBase: StateFlow<String?> = _publicBase.asStateFlow()

    /** Só o administrador: informa o endereço do Falaí na internet. Devolve a base (https://...). */
    suspend fun setPublicAddress(address: String): String {
        val id = UUID.randomUUID().toString()
        return (request(id, SetPublicAddressFrame(id, address)) as PublicAddressFrame).publicBase
    }

    suspend fun refreshUsers() {
        val id = UUID.randomUUID().toString()
        request(id, ListUsersFrame(id))
    }

    // ---------- Arquivos ----------

    private val _uploadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Progresso de envio por id de mensagem (0.0 a 1.0). */
    val uploadProgress: StateFlow<Map<String, Float>> = _uploadProgress.asStateFlow()

    private var uploadJob: Job? = null
    private val downloadLocks = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /**
     * Envia um arquivo que já está na pasta do app. A mensagem aparece na hora com o relógio;
     * o arquivo sobe em pedaços e, quando termina, a mensagem é enviada.
     */
    suspend fun sendMedia(
        conversationId: String,
        file: java.io.File,
        kind: String,
        mime: String,
        name: String = file.name,
        durationMs: Long? = null,
        caption: String = "",
        /** Miniatura JPEG em base64 (fotos e vídeos). Quem recebe vê a prévia antes de baixar. */
        thumb: String? = null,
    ): LocalMessage = withContext(Dispatchers.IO) {
        val acc = store.account() ?: throw FalaiClientException("not_paired", "Aparelho não pareado.")
        require(file.exists() && file.length() > 0) { "Arquivo vazio" }
        if (file.length() > app.falai.protocol.MEDIA_MAX_BYTES) {
            throw FalaiClientException("too_large", "Arquivo grande demais (máximo 2 GB).")
        }
        val att = app.falai.protocol.AttachmentDto(
            mediaId = "", kind = kind, mime = mime, name = name,
            size = file.length(), sha256 = sha256Hex(file), durationMs = durationMs,
            thumb = app.falai.protocol.sanitizeThumb(thumb),
        )
        val m = store.insertPendingMedia(UUID.randomUUID().toString(), conversationId, acc.userId, caption.trim(), att, file.path)
        bump()
        kickUploads()
        m
    }

    /** Começa (ou continua) a subir os arquivos da fila, um por vez. */
    fun kickUploads() {
        if (uploadJob?.isActive == true) {
            uploadWake.trySend(Unit)
            return
        }
        uploadJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val next = store.outgoingToUpload().firstOrNull() ?: break
                val ok = try {
                    upload(next)
                    true
                } catch (e: FalaiClientException) {
                    log("Envio de arquivo falhou: ${e.code} ${e.message}")
                    _lastError.value = "Envio de arquivo: ${e.message}"
                    if (e.code == "not_found") {
                        // O servidor não conhece mais esse envio: recomeça do zero.
                        store.resetOutgoingMediaId(next.messageId)
                        true
                    } else if (e.code in setOf("too_large", "forbidden", "invalid", "missing")) {
                        store.markFailed(next.messageId)
                        bump()
                        true // segue para o próximo
                    } else false
                } catch (e: IOException) {
                    log("Envio de arquivo interrompido: ${e.message}")
                    _lastError.value = "Arquivo esperando conexão para subir (${e.message ?: "sem rede"})."
                    false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Qualquer outro problema: mostra o motivo e tenta de novo, sem parar a fila.
                    log("Envio de arquivo com erro inesperado: $e")
                    _lastError.value = "Envio de arquivo: erro inesperado (${e.message ?: e.javaClass.simpleName})."
                    false
                }
                if (!ok) {
                    // Sem conexão: tenta de novo daqui a pouco (ou quando reconectar).
                    sleepOrWake(reconnectDelaysMs.last(), uploadWake)
                }
            }
        }
    }

    private fun authed(b: Request.Builder, acc: Account) = b.header("Authorization", "Bearer ${acc.token}")

    private fun <T> call(req: Request, parse: (String) -> T): T {
        mediaHttp.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val err = runCatching { FalaiJson.decodeFromString(ErrorResponse.serializer(), text) }.getOrNull()
                if (r.code >= 500 || err == null) throw IOException(err?.message ?: "HTTP ${r.code}")
                throw FalaiClientException(err.error, err.message)
            }
            return parse(text)
        }
    }

    private fun upload(o: OutgoingMedia) {
        val acc = store.account() ?: return
        val file = java.io.File(o.path)
        if (!file.exists()) throw FalaiClientException("missing", "O arquivo não existe mais neste aparelho.")
        val json = "application/json".toMediaType()
        val parseStatus = { t: String -> FalaiJson.decodeFromString(app.falai.protocol.MediaStatus.serializer(), t) }

        var status = if (o.mediaId == null) {
            val body = FalaiJson.encodeToString(
                app.falai.protocol.MediaCreateRequest.serializer(),
                app.falai.protocol.MediaCreateRequest(o.attachment.size, o.attachment.sha256, o.attachment.mime, o.attachment.name),
            )
            call(authed(Request.Builder().url("${acc.serverUrl}/api/media").post(body.toRequestBody(json)), acc).build(), parseStatus)
                .also { store.setOutgoingMediaId(o.messageId, it.mediaId) }
        } else {
            // Retomando: pergunta ao servidor onde parou.
            call(authed(Request.Builder().url("${acc.serverUrl}/api/media/${o.mediaId}/status"), acc).build(), parseStatus)
        }

        java.io.RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(app.falai.protocol.MEDIA_CHUNK_BYTES)
            while (!status.complete) {
                raf.seek(status.received)
                val want = minOf(buf.size.toLong(), status.size - status.received).toInt()
                raf.readFully(buf, 0, want)
                val req = authed(
                    Request.Builder()
                        .url("${acc.serverUrl}/api/media/${status.mediaId}?offset=${status.received}")
                        .put(buf.toRequestBody("application/octet-stream".toMediaType(), 0, want)),
                    acc,
                ).build()
                status = try {
                    call(req, parseStatus)
                } catch (e: FalaiClientException) {
                    if (e.code == "wrong_offset" || e.code == "hash_mismatch") {
                        // Fora de sincronia: pergunta de novo e continua de onde o servidor diz.
                        call(authed(Request.Builder().url("${acc.serverUrl}/api/media/${status.mediaId}/status"), acc).build(), parseStatus)
                    } else throw e
                }
                _uploadProgress.value = _uploadProgress.value + (o.messageId to status.received.toFloat() / status.size)
            }
        }

        store.markUploaded(o.messageId, status.mediaId)
        _uploadProgress.value = _uploadProgress.value - o.messageId
        if (_lastError.value?.contains("arquivo", ignoreCase = true) == true) _lastError.value = null
        bump()
        val m = store.messageById(o.messageId)
        if (m != null && _connection.value == ConnectionState.ONLINE) {
            sendFrame(socket, SendFrame(m.id, m.conversationId, m.body, m.attachment))
        }
    }

    /**
     * Devolve o arquivo do anexo neste aparelho, baixando do servidor se ainda não tiver.
     * Confere o SHA-256: o arquivo é idêntico ao original.
     */
    suspend fun mediaFile(att: app.falai.protocol.AttachmentDto): java.io.File = withContext(Dispatchers.IO) {
        require(att.mediaId.isNotEmpty()) { "Anexo ainda subindo" }
        store.localMediaPath(att.mediaId)?.let { java.io.File(it) }?.takeIf { it.exists() }?.let { return@withContext it }
        val lock = downloadLocks.computeIfAbsent(att.mediaId) { kotlinx.coroutines.sync.Mutex() }
        lock.lock()
        try {
            store.localMediaPath(att.mediaId)?.let { java.io.File(it) }?.takeIf { it.exists() }?.let { return@withContext it }
            val acc = store.account() ?: throw FalaiClientException("not_paired", "Aparelho não pareado.")
            mediaDir.mkdirs()
            val ext = att.name.substringAfterLast('.', "").takeIf { it.length in 1..5 }?.let { ".$it" } ?: ""
            val target = java.io.File(mediaDir, att.mediaId + ext)
            val tmp = java.io.File(mediaDir, att.mediaId + ".download")
            val req = authed(Request.Builder().url("${acc.serverUrl}/api/media/${att.mediaId}"), acc).build()
            val resp = try {
                mediaHttp.newCall(req).execute()
            } catch (e: IOException) {
                throw FalaiClientException("network", "Sem conexão para baixar o arquivo.")
            }
            // Fotos de perfil não vêm com o SHA na mensagem: o servidor manda no cabeçalho.
            val expected = resp.use { r ->
                if (!r.isSuccessful) throw FalaiClientException("http_${r.code}", "Não consegui baixar o arquivo (${r.code}).")
                r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
                att.sha256.ifEmpty { r.header("X-Content-SHA256").orEmpty() }
            }
            if (expected.isEmpty() || sha256Hex(tmp) != expected) {
                tmp.delete()
                throw FalaiClientException("hash_mismatch", "O arquivo chegou corrompido. Tente de novo.")
            }
            if (!tmp.renameTo(target)) throw FalaiClientException("io", "Não consegui salvar o arquivo.")
            store.setLocalMedia(att.mediaId, target.path)
            bump()
            target
        } finally {
            lock.unlock()
        }
    }

    // ---------- Falaí no computador ----------

    /**
     * Liga o navegador que está mostrando o QR Code. [qrOrCode] = o texto lido do QR Code
     * ou o código digitado. Devolve o nome do navegador (ex.: "Chrome no Windows").
     */
    suspend fun approveWeb(qrOrCode: String): String {
        val code = app.falai.protocol.WebLinkCodes.parse(qrOrCode)
            ?: throw FalaiClientException("invalid", "Esse não é o código do Falaí no computador. Confira e tente de novo.")
        val id = UUID.randomUUID().toString()
        val f = request(id, app.falai.protocol.ApproveWebFrame(id, code)) as app.falai.protocol.WebApprovedFrame
        return f.deviceName
    }

    // ---------- Chamadas ----------

    private val _callEvents = kotlinx.coroutines.flow.MutableSharedFlow<ServerFrame>(extraBufferCapacity = 256)
    /** Chamadas: [app.falai.protocol.CallRingFrame], [app.falai.protocol.CallStateFrame] e recados do WebRTC. */
    val callEvents: kotlinx.coroutines.flow.SharedFlow<ServerFrame> = _callEvents

    private val _turnConfigured = MutableStateFlow<Boolean?>(null)
    /** Só para o administrador: o ajudante de chamadas (TURN) está ligado? null = não sei / não sou admin. */
    val turnConfigured: StateFlow<Boolean?> = _turnConfigured.asStateFlow()

    /** Liga para uma conversa (ou entra na chamada que já existe nela). */
    suspend fun startCall(conversationId: String, video: Boolean): app.falai.protocol.CallDto {
        val id = UUID.randomUUID().toString()
        val f = request(id, app.falai.protocol.CallStartFrame(id, UUID.randomUUID().toString(), conversationId, video))
        return (f as app.falai.protocol.CallStateFrame).call
    }

    suspend fun joinCall(callId: String, mic: Boolean, cam: Boolean): app.falai.protocol.CallDto {
        val id = UUID.randomUUID().toString()
        return (request(id, app.falai.protocol.CallJoinFrame(id, callId, mic, cam)) as app.falai.protocol.CallStateFrame).call
    }

    /** Recados sem resposta (sair, recusar, câmera ligada, WebRTC). Devolve false se estiver sem conexão. */
    fun sendCallFrame(f: ClientFrame): Boolean = sendFrame(socket, f)

    /** Servidores STUN/TURN para o WebRTC. Sem conexão: só os STUN grátis. */
    suspend fun iceServers(): List<app.falai.protocol.IceServerDto> = try {
        val id = UUID.randomUUID().toString()
        (request(id, app.falai.protocol.CallIceRequestFrame(id)) as app.falai.protocol.CallIceServersFrame).servers
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
        listOf(app.falai.protocol.IceServerDto(listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302")))
    }

    /** Só o administrador: liga (ou desliga, com vazio) o ajudante de chamadas. Devolve a mensagem. */
    suspend fun setTurn(keyId: String, token: String): String {
        val id = UUID.randomUUID().toString()
        return (request(id, app.falai.protocol.SetTurnFrame(id, keyId, token)) as app.falai.protocol.TurnStatusFrame).message
    }

    // ---------- Perfil ----------

    /** Foto de perfil de alguém, baixada (e guardada) se precisar. */
    suspend fun avatarFile(mediaId: String): java.io.File =
        mediaFile(app.falai.protocol.AttachmentDto(mediaId, "image", "image/jpeg", "perfil.jpg", 0, ""))

    /** Envia um arquivo pequeno direto (sem fila), ex.: foto de perfil. Devolve o id no servidor. */
    private fun uploadDirect(file: java.io.File, mime: String): String {
        val acc = store.account() ?: throw FalaiClientException("not_paired", "Aparelho não pareado.")
        val json = "application/json".toMediaType()
        val parseStatus = { t: String -> FalaiJson.decodeFromString(app.falai.protocol.MediaStatus.serializer(), t) }
        val body = FalaiJson.encodeToString(
            app.falai.protocol.MediaCreateRequest.serializer(),
            app.falai.protocol.MediaCreateRequest(file.length(), sha256Hex(file), mime, file.name),
        )
        var status = call(authed(Request.Builder().url("${acc.serverUrl}/api/media").post(body.toRequestBody(json)), acc).build(), parseStatus)
        java.io.RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(app.falai.protocol.MEDIA_CHUNK_BYTES)
            while (!status.complete) {
                raf.seek(status.received)
                val want = minOf(buf.size.toLong(), status.size - status.received).toInt()
                raf.readFully(buf, 0, want)
                val req = authed(
                    Request.Builder()
                        .url("${acc.serverUrl}/api/media/${status.mediaId}?offset=${status.received}")
                        .put(buf.toRequestBody("application/octet-stream".toMediaType(), 0, want)),
                    acc,
                ).build()
                status = call(req, parseStatus)
            }
        }
        // A foto já está aqui: guarda como baixada, para não baixar de volta.
        store.setLocalMedia(status.mediaId, file.path)
        return status.mediaId
    }

    /**
     * Muda o meu nome e/ou a minha foto. [photo] = nova foto (JPEG já reduzido);
     * [removePhoto] = tirar a foto. Os outros aparelhos da família veem na hora.
     */
    suspend fun updateProfile(name: String? = null, photo: java.io.File? = null, removePhoto: Boolean = false): UserDto {
        val mediaId = when {
            photo != null -> try {
                withContext(Dispatchers.IO) { uploadDirect(photo, "image/jpeg") }
            } catch (e: IOException) {
                throw FalaiClientException("network", "Sem conexão para enviar a foto.")
            }
            removePhoto -> ""
            else -> null
        }
        val id = UUID.randomUUID().toString()
        val f = request(id, UpdateProfileFrame(id, name?.trim(), mediaId)) as UserUpdatedFrame
        return f.user
    }

    companion object {
        fun sha256Hex(f: java.io.File): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

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
