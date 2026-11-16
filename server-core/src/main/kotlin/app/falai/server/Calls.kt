package app.falai.server

import app.falai.protocol.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Chamadas de voz e vídeo. O servidor só apresenta os aparelhos e repassa os recados
 * do WebRTC; o som e a imagem vão direto de um aparelho para o outro.
 *
 * Regras:
 * - Uma chamada por conversa. Quem "liga" para uma conversa que já tem chamada entra nela.
 * - Ninguém atendeu em [ringTimeoutMs]: a chamada acaba ("no_answer").
 * - Conversa a dois: quando um sai (ou recusa), acaba para os dois.
 * - Grupo: continua enquanto houver pelo menos duas pessoas; quem fica sozinho por
 *   [ringTimeoutMs] tem a chamada encerrada.
 * - A internet do aparelho caiu: espera [dropGraceMs] para ele voltar antes de tirá-lo da chamada
 *   (o som continua indo direto entre os aparelhos enquanto isso).
 */
class Calls(
    private val store: Store,
    private val hub: Hub,
    private val log: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ringTimeoutMs: Long = 45_000,
    private val dropGraceMs: Long = 20_000,
) {
    private class P(val deviceId: String, val userId: String, var mic: Boolean, var cam: Boolean, var screen: Boolean = false)

    private class Call(
        val id: String,
        val conversationId: String,
        val video: Boolean,
        val startedBy: String,
        val startedAt: Long,
        val direct: Boolean,
    ) {
        val participants = LinkedHashMap<String, P>()
        val declined = LinkedHashSet<String>()
        /** Já teve duas pessoas ao mesmo tempo (alguém atendeu). */
        var answered = false
        var ended = false
        var endReason: String? = null
    }

    private val calls = HashMap<String, Call>()
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "falai-calls").apply { isDaemon = true }
    }

    private fun Call.dto() = CallDto(
        id = id, conversationId = conversationId, video = video, startedBy = startedBy, startedAt = startedAt,
        participants = participants.values.map { CallParticipantDto(it.deviceId, it.userId, it.mic, it.cam, it.screen) },
        declinedBy = declined.toList(), ended = ended, endReason = endReason,
    )

    private fun members(c: Call) = store.memberIds(c.conversationId)

    /** Conta a novidade para todos os aparelhos de todos da conversa. */
    private fun broadcast(c: Call, exceptDeviceId: String? = null) =
        hub.deliver(members(c), CallStateFrame(null, c.dto()), exceptDeviceId)

    private fun end(c: Call, reason: String) {
        if (c.ended) return
        c.ended = true
        c.endReason = reason
        calls.remove(c.id)
        log("Chamada encerrada ($reason)")
        broadcast(c)
    }

    private fun activeIn(conversationId: String) = calls.values.firstOrNull { it.conversationId == conversationId && !it.ended }

    private fun later(ms: Long, block: () -> Unit) {
        timer.schedule({ synchronized(this) { runCatching(block) } }, ms, TimeUnit.MILLISECONDS)
    }

    private fun scheduleLonelyCheck(c: Call) = later(ringTimeoutMs) {
        if (!c.ended && c.participants.size < 2) end(c, if (c.answered) "hangup" else "no_answer")
    }

    @Synchronized
    fun start(device: AuthedDevice, f: CallStartFrame): CallDto {
        val conv = store.conversation(f.conversationId)
        if (conv == null || device.user.id !in conv.memberIds) throw FalaiException("forbidden", "Você não está nesta conversa.")
        if (conv.memberIds.size < 2) throw FalaiException("invalid", "Não há ninguém para chamar nesta conversa.")
        activeIn(f.conversationId)?.let { existing ->
            // Já tem chamada nesta conversa: entra nela.
            return join(device, CallJoinFrame(f.requestId, existing.id, mic = true, cam = f.video))
        }
        if (f.callId.isBlank() || f.callId.length > 64) throw FalaiException("invalid", "Chamada inválida.")
        if (calls.containsKey(f.callId)) throw FalaiException("conflict", "Chamada repetida.")
        val c = Call(f.callId, conv.id, f.video, device.user.id, clock(), direct = conv.kind != "group")
        c.participants[device.deviceId] = P(device.deviceId, device.user.id, mic = true, cam = f.video)
        calls[c.id] = c
        log("Chamada ${if (f.video) "de vídeo" else "de voz"} iniciada por ${device.user.name}")
        val dto = c.dto()
        // Toca nos aparelhos dos outros; nos meus outros aparelhos, só aparece "em andamento".
        hub.deliver(conv.memberIds.filter { it != device.user.id }, CallRingFrame(dto))
        hub.deliver(listOf(device.user.id), CallStateFrame(null, dto), exceptDeviceId = device.deviceId)
        scheduleLonelyCheck(c)
        return dto
    }

    @Synchronized
    fun join(device: AuthedDevice, f: CallJoinFrame): CallDto {
        val c = calls[f.callId]
        if (c == null || c.ended) throw FalaiException("not_found", "Essa chamada já terminou.")
        if (!store.isMember(c.conversationId, device.user.id)) throw FalaiException("forbidden", "Você não está nesta conversa.")
        val existing = c.participants[device.deviceId]
        if (existing == null) {
            if (c.participants.size >= MAX_CALL_PARTICIPANTS) {
                throw FalaiException("full", "A chamada já tem $MAX_CALL_PARTICIPANTS pessoas, o máximo.")
            }
            // A mesma pessoa em dois aparelhos: o novo assume (o outro sai).
            val sameUser = c.participants.values.filter { it.userId == device.user.id }.map { it.deviceId }
            sameUser.forEach { c.participants.remove(it) }
            c.participants[device.deviceId] = P(device.deviceId, device.user.id, f.mic, f.cam)
        } else {
            existing.mic = f.mic
            existing.cam = f.cam
        }
        c.declined.remove(device.user.id)
        if (c.participants.size >= 2) c.answered = true
        log("${device.user.name} entrou na chamada (${c.participants.size} na chamada)")
        broadcast(c, exceptDeviceId = device.deviceId)
        return c.dto()
    }

    @Synchronized
    fun leave(device: AuthedDevice, callId: String) {
        val c = calls[callId] ?: return
        removeDevice(c, device.deviceId, "hangup")
    }

    private fun removeDevice(c: Call, deviceId: String, reason: String) {
        val p = c.participants.remove(deviceId) ?: return
        log("Saiu da chamada: ${p.userId.take(8)}")
        when {
            c.participants.isEmpty() -> end(c, reason)
            // A dois: se um sai (ou desiste antes de atenderem), acaba para todos.
            c.direct -> end(c, reason)
            // Grupo: quem ligou desistiu antes de alguém atender.
            !c.answered && c.participants.values.none { it.userId != c.startedBy } && p.userId == c.startedBy -> end(c, reason)
            else -> {
                broadcast(c)
                if (c.participants.size < 2) scheduleLonelyCheck(c)
            }
        }
    }

    @Synchronized
    fun decline(device: AuthedDevice, f: CallDeclineFrame) {
        val c = calls[f.callId] ?: return
        if (!store.isMember(c.conversationId, device.user.id)) return
        if (c.participants.values.any { it.userId == device.user.id }) return
        c.declined += device.user.id
        if (c.direct) {
            end(c, if (f.busy) "busy" else "declined")
            return
        }
        val waitingFor = members(c).filter { uid -> uid !in c.declined && c.participants.values.none { it.userId == uid } }
        if (waitingFor.isEmpty() && c.participants.size < 2) end(c, "declined") else broadcast(c)
    }

    @Synchronized
    fun media(device: AuthedDevice, f: CallMediaFrame) {
        val c = calls[f.callId] ?: return
        val p = c.participants[device.deviceId] ?: return
        p.mic = f.mic
        p.cam = f.cam
        p.screen = f.screen
        broadcast(c, exceptDeviceId = device.deviceId)
    }

    /** Repassa um recado WebRTC. Só entre dois aparelhos que estão na mesma chamada. */
    @Synchronized
    fun signal(device: AuthedDevice, f: CallSignalFrame) {
        val c = calls[f.callId] ?: return
        if (!c.participants.containsKey(device.deviceId)) return
        val to = c.participants[f.toDeviceId] ?: return
        if (f.data.length > 200_000) throw FalaiException("too_large", "Recado grande demais.")
        hub.deliverToDevice(to.userId, to.deviceId, CallSignalInFrame(c.id, device.deviceId, device.user.id, f.kind, f.data))
    }

    /** Aparelho acabou de conectar: conta as chamadas em andamento (e toca se for o caso). */
    @Synchronized
    fun onConnected(conn: Hub.Conn) {
        val me = conn.device
        for (c in calls.values.toList()) {
            if (c.ended || !store.isMember(c.conversationId, me.user.id)) continue
            val ringing = !c.answered && c.startedBy != me.user.id && me.user.id !in c.declined &&
                c.participants.values.none { it.userId == me.user.id }
            conn.outbox.trySend(if (ringing) CallRingFrame(c.dto()) else CallStateFrame(null, c.dto()))
        }
    }

    /** A conexão caiu: se não voltar logo, sai das chamadas. */
    @Synchronized
    fun onDisconnected(device: AuthedDevice) {
        for (c in calls.values.toList()) {
            if (!c.participants.containsKey(device.deviceId)) continue
            later(dropGraceMs) {
                if (!c.ended && c.participants.containsKey(device.deviceId) && !hub.isConnected(device.deviceId)) {
                    removeDevice(c, device.deviceId, "hangup")
                }
            }
        }
    }

    /** Chamadas em andamento agora (para a tela do servidor). */
    @Synchronized
    fun activeCount(): Int = calls.size

    fun shutdown() = timer.shutdownNow()
}

/**
 * Endereços que ajudam os aparelhos a se acharem pela internet.
 * - STUN (grátis, Google e Cloudflare): descobre o endereço de cada um. Resolve a maioria dos casos.
 * - TURN (opcional, Cloudflare, grátis até 1.000 GB/mês): quando os dois estão em redes que não
 *   deixam ligação direta (comum no 4G), o som passa por ele.
 */
class IceServers(
    private val store: Store,
    private val log: (String) -> Unit,
    private val fetch: (keyId: String, token: String) -> String = ::fetchCloudflareIce,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val stun = listOf(
        IceServerDto(listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")),
    )
    @Volatile private var cached: Pair<Long, List<IceServerDto>>? = null

    fun turnConfigured(): Boolean = !store.getMeta("turn_key_id").isNullOrBlank()

    /** Pode demorar (vai à Cloudflare): chame fora da conexão principal. */
    fun servers(): List<IceServerDto> {
        val keyId = store.getMeta("turn_key_id")
        val token = store.getMeta("turn_token")
        if (keyId.isNullOrBlank() || token.isNullOrBlank()) return stun
        cached?.let { (at, list) -> if (clock() - at < 6 * 3600_000L) return list }
        return try {
            val list = stun + parseCloudflare(fetch(keyId, token))
            cached = clock() to list
            list
        } catch (e: Exception) {
            log("TURN da Cloudflare não respondeu: ${e.message}")
            stun
        }
    }

    /** Guarda (ou apaga, com vazio) a chave. Testa antes. Devolve a mensagem para mostrar. */
    fun configure(keyId: String, token: String): Pair<Boolean, String> {
        val k = keyId.trim()
        val t = token.trim()
        if (k.isEmpty() && t.isEmpty()) {
            store.setMeta("turn_key_id", "")
            store.setMeta("turn_token", "")
            cached = null
            return false to "Ajudante de chamadas desligado."
        }
        if (!Regex("^[A-Za-z0-9_-]{8,100}$").matches(k) || t.length !in 8..500 || t.any { it.isWhitespace() }) {
            throw FalaiException("invalid", "Confira o ID e o token: copie de novo da Cloudflare.")
        }
        val list = try {
            parseCloudflare(fetch(k, t))
        } catch (e: Exception) {
            throw FalaiException("invalid", "A Cloudflare recusou: ${e.message}. Confira o ID e o token.")
        }
        if (list.none { s -> s.urls.any { it.startsWith("turn") } }) throw FalaiException("invalid", "A Cloudflare não mandou o TURN.")
        store.setMeta("turn_key_id", k)
        store.setMeta("turn_token", t)
        cached = clock() to (stun + list)
        log("Ajudante de chamadas (TURN) ligado")
        return true to "Pronto! O ajudante de chamadas está ligado."
    }

    companion object {
        /** Lê a resposta da Cloudflare ({"iceServers": [...]} ou {"iceServers": {...}}). */
        fun parseCloudflare(json: String): List<IceServerDto> {
            val root = FalaiJson.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject
                ?: throw IllegalStateException("resposta estranha")
            val ice = root["iceServers"] ?: throw IllegalStateException("sem iceServers")
            val items = (ice as? kotlinx.serialization.json.JsonArray)?.toList() ?: listOf(ice)
            return items.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val urlsEl = o["urls"] ?: return@mapNotNull null
                val urls = ((urlsEl as? kotlinx.serialization.json.JsonArray)?.toList() ?: listOf(urlsEl))
                    .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    // A porta 53 é bloqueada pelos navegadores; e o STUN já vem da nossa lista.
                    .filter { it.startsWith("turn") && !Regex(":53(\\?|$)").containsMatchIn(it) }
                if (urls.isEmpty()) return@mapNotNull null
                fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                IceServerDto(urls, str("username"), str("credential"))
            }
        }
    }
}

/** Pede credenciais do TURN à Cloudflare (valem 24 h). Usa só o que existe no Android e no PC. */
fun fetchCloudflareIce(keyId: String, token: String): String {
    val url = java.net.URL("https://rtc.live.cloudflare.com/v1/turn/keys/$keyId/credentials/generate-ice-servers")
    val c = url.openConnection() as java.net.HttpURLConnection
    c.requestMethod = "POST"
    c.connectTimeout = 8000
    c.readTimeout = 8000
    c.doOutput = true
    c.setRequestProperty("Authorization", "Bearer $token")
    c.setRequestProperty("Content-Type", "application/json")
    c.outputStream.use { it.write("""{"ttl": 86400}""".toByteArray()) }
    val code = c.responseCode
    val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    if (code !in 200..299) throw java.io.IOException("código $code")
    return body
}
