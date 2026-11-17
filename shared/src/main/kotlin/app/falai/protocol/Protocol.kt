package app.falai.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Protocolo do Falaí (versão 1).
 *
 * HTTP:
 *   GET  /health          -> {"ok":true,"version":1}
 *   POST /api/pair        -> troca um código de convite por um token de dispositivo
 *
 * WebSocket (/ws, cabeçalho "Authorization: Bearer <token>"):
 *   cada quadro é um objeto JSON com o campo "type".
 */
const val PROTOCOL_VERSION = 1

/** Versão do Falaí (servidor e página web). O app usa a do build.gradle. */
const val FALAI_VERSION = "0.9.4"

val FalaiJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

// ---------- HTTP ----------

@Serializable
data class PairRequest(
    val code: String,
    val deviceName: String,
    /** Nome da pessoa. Ignorado quando o convite é para adicionar aparelho a alguém que já existe. */
    val userName: String? = null,
    /**
     * Identificador desta tentativa, gerado pelo aparelho. Se a resposta se perder (internet
     * oscilando) e o aparelho repetir o pedido com o mesmo código e o mesmo id, o servidor
     * reconhece e responde de novo, em vez de dizer que o convite já foi usado.
     */
    val attemptId: String? = null,
    /**
     * Identidade deste aparelho, que sobrevive a desinstalar e instalar de novo (no Android, vem do
     * ANDROID_ID). Se o servidor já conhece este aparelho, ele volta para a mesma pessoa, sem
     * criar outra conta.
     */
    val installKey: String? = null,
)

@Serializable
data class PairResponse(
    val userId: String,
    val deviceId: String,
    val token: String,
    val isAdmin: Boolean,
    /** Nome da pessoa (para dizer "Bem-vindo de volta, Ana"). */
    val userName: String? = null,
    /** true = o aparelho já era conhecido e voltou para a mesma pessoa. */
    val returning: Boolean = false,
)

/**
 * POST /api/reconnect: o app foi instalado de novo (ou os dados foram apagados) num aparelho que o
 * servidor já conhece. Volta sem convite. 404 = aparelho desconhecido (aí precisa de convite).
 */
@Serializable
data class ReconnectRequest(val installKey: String, val deviceName: String)

@Serializable
data class ErrorResponse(val error: String, val message: String)

// ---------- Objetos compartilhados ----------

@Serializable
data class UserDto(
    val id: String,
    val name: String,
    val isAdmin: Boolean,
    /** Foto de perfil (um arquivo já enviado ao servidor), ou null. */
    val avatarMediaId: String? = null,
    /** O administrador tirou a pessoa da família (as mensagens antigas continuam com o nome dela). */
    val removed: Boolean = false,
)

@Serializable
data class ConversationDto(
    val id: String,
    val kind: String,
    val title: String?,
    val memberIds: List<String>,
    val createdAt: Long,
    /** A pessoa apagou a conversa até esta mensagem: as mais antigas somem dos aparelhos dela. */
    val clearedSeq: Long? = null,
)

@Serializable
data class MessageDto(
    /** Ordem global no servidor. O cliente guarda o maior que já viu e pede o resto no sync. */
    val seq: Long,
    /** UUID gerado no aparelho que escreveu. Reenviar o mesmo id não duplica. */
    val id: String,
    val conversationId: String,
    val authorId: String,
    val authorDeviceId: String,
    val body: String,
    val createdAt: Long,
    /** Foto, vídeo, áudio ou arquivo anexado (o arquivo em si é baixado à parte, sob demanda). */
    val attachment: AttachmentDto? = null,
)

@Serializable
data class AttachmentDto(
    val mediaId: String,
    /** "image", "video", "audio" ou "file". */
    val kind: String,
    val mime: String,
    val name: String,
    val size: Long,
    /** SHA-256 do arquivo original, em hexadecimal. Garante que chegou idêntico. */
    val sha256: String,
    val durationMs: Long? = null,
    /**
     * Miniatura pequena (JPEG em base64) de fotos e vídeos, para quem recebe ver a prévia
     * antes de baixar, como nos outros mensageiros. No máximo [MAX_THUMB_CHARS] caracteres.
     */
    val thumb: String? = null,
)

/** Tamanho máximo da miniatura em base64 (cerca de 18 KB de JPEG). */
const val MAX_THUMB_CHARS = 24_000

private val BASE64_CHARS = Regex("^[A-Za-z0-9+/]+={0,2}$")

/** A miniatura só é aceita se for pequena e parecer base64; senão, é descartada. */
fun sanitizeThumb(thumb: String?): String? =
    thumb?.takeIf { it.length in 4..MAX_THUMB_CHARS && BASE64_CHARS.matches(it) }

// ---------- Convite por link ----------

/**
 * Link de convite e o "atalho" que abre o app já preenchido.
 * Página: https://SERVIDOR/convite/CODIGO  ·  App: falai://convite?s=https://SERVIDOR&c=CODIGO
 */
object InviteLinks {
    fun page(base: String, code: String) = "${base.trimEnd('/')}/convite/$code"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun appUri(base: String, code: String, forExistingUser: Boolean = false, name: String? = null): String =
        "falai://convite?s=" + enc(base.trimEnd('/')) + "&c=" + code +
            (if (forExistingUser) "&d=1" else "") +
            (name?.takeIf { it.isNotBlank() }?.let { "&n=" + enc(it.trim()) } ?: "")

    /** Carimbo do APK baixado sem convite: só o endereço, para o app achar o servidor sozinho. */
    fun serverUri(base: String): String = "falai://servidor?s=" + enc(base.trimEnd('/'))

    /** Endereço de um texto "falai://servidor?s=..." (ou de um convite). */
    fun parseServer(text: String): String? {
        parse(text)?.let { return it.server }
        val m = Regex("""falai://servidor\?s=([^\s&]+)""").find(text.trim()) ?: return null
        return java.net.URLDecoder.decode(m.groupValues[1], "UTF-8").takeIf { it.startsWith("http") }
    }

    data class Parsed(val server: String, val code: String, val forExistingUser: Boolean, val name: String? = null)

    /** Entende o link da página, o atalho do app ou um texto colado que contenha um deles. */
    fun parse(text: String): Parsed? {
        val t = text.trim()
        Regex("""falai://convite\?([^\s]+)""").find(t)?.let { m ->
            val params = m.groupValues[1].split('&').mapNotNull {
                val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val s = params["s"] ?: return null
            val c = params["c"] ?: return null
            return Parsed(s, c.uppercase(), params["d"] == "1", params["n"]?.trim()?.take(60)?.takeIf { it.isNotEmpty() })
        }
        Regex("""(https?://[A-Za-z0-9.\-]+(?::\d+)?)/convite/([A-Za-z0-9]+)""").find(t)?.let { m ->
            return Parsed(m.groupValues[1], m.groupValues[2].uppercase(), false)
        }
        return null
    }
}

@Serializable
data class AppApkInfo(val versionCode: Long, val versionName: String, val size: Long)

// ---------- Mídia (HTTP) ----------
//
//   POST /api/media                      MediaCreateRequest -> MediaStatus   (começa um envio)
//   GET  /api/media/{id}/status          -> MediaStatus                      (para retomar)
//   PUT  /api/media/{id}?offset=N        bytes (até 8 MB) -> MediaStatus     (um pedaço)
//   GET  /api/media/{id}                 -> o arquivo original
//
// Todas com "Authorization: Bearer <token>".

const val MEDIA_CHUNK_BYTES = 8 * 1024 * 1024
const val MEDIA_MAX_BYTES = 2L * 1024 * 1024 * 1024

@Serializable
data class MediaCreateRequest(val size: Long, val sha256: String, val mime: String, val name: String)

@Serializable
data class MediaStatus(val mediaId: String, val size: Long, val received: Long, val complete: Boolean)

// ---------- Cliente -> servidor ----------

@Serializable
sealed interface ClientFrame

@Serializable @SerialName("send")
data class SendFrame(
    val id: String,
    val conversationId: String,
    val body: String,
    /** Só depois que o arquivo terminou de subir (MediaStatus.complete). */
    val attachment: AttachmentDto? = null,
) : ClientFrame

@Serializable @SerialName("sync")
data class SyncFrame(val afterSeq: Long = 0) : ClientFrame

@Serializable @SerialName("create_conversation")
data class CreateConversationFrame(
    /** Id que o cliente usa para casar a resposta com o pedido. */
    val requestId: String,
    val kind: String,
    val title: String? = null,
    /** Outras pessoas da conversa. Quem cria entra automaticamente. */
    val memberIds: List<String>,
) : ClientFrame

@Serializable @SerialName("list_users")
data class ListUsersFrame(val requestId: String) : ClientFrame

@Serializable @SerialName("create_invite")
data class CreateInviteFrame(
    val requestId: String,
    /** Preenchido = convite para ligar mais um aparelho a essa pessoa. Vazio = pessoa nova. */
    val forUserId: String? = null,
    /** Pessoa nova: o nome que já aparece preenchido para ela (ela pode mudar). */
    val name: String? = null,
) : ClientFrame

/** "Apagar conversa": some da minha lista (em todos os meus aparelhos) até chegar mensagem nova. */
@Serializable @SerialName("hide_conversation")
data class HideConversationFrame(val requestId: String, val conversationId: String) : ClientFrame

/** Só o administrador: tira a pessoa da família. Os aparelhos dela são desligados na hora. */
@Serializable @SerialName("remove_user")
data class RemoveUserFrame(val requestId: String, val userId: String) : ClientFrame

/** Só o administrador: traz de volta quem foi tirado da família (com os mesmos aparelhos e conversas). */
@Serializable @SerialName("restore_user")
data class RestoreUserFrame(val requestId: String, val userId: String) : ClientFrame

@Serializable @SerialName("ping")
data class PingFrame(val requestId: String) : ClientFrame

/** Muda o próprio nome e/ou a foto. [avatarMediaId] = "" tira a foto; null não mexe. */
@Serializable @SerialName("update_profile")
data class UpdateProfileFrame(val requestId: String, val name: String? = null, val avatarMediaId: String? = null) : ClientFrame

/** Só o administrador: informa o endereço do Falaí na internet (ex.: falai.falaifamilia.net). */
@Serializable @SerialName("set_public_address")
data class SetPublicAddressFrame(val requestId: String, val address: String) : ClientFrame

// ---------- Servidor -> cliente ----------

/** O celular aprova o navegador que mostrou o QR Code (Falaí no computador). */
@Serializable @SerialName("approve_web")
data class ApproveWebFrame(val requestId: String, val code: String) : ClientFrame

@Serializable
sealed interface ServerFrame

@Serializable @SerialName("hello")
data class HelloFrame(
    val protocol: Int,
    val user: UserDto,
    val deviceId: String,
    val conversations: List<ConversationDto>,
    val lastSeq: Long,
    /** Endereço do servidor na internet (ex.: https://falai.falaifamilia.net), se já for conhecido. */
    val publicBase: String? = null,
    /** Só para o administrador: o "ajudante de chamadas" (TURN da Cloudflare) está configurado. */
    val turnConfigured: Boolean? = null,
    /** O app do Falaí que está no servidor para baixar (para o app saber se tem versão nova). */
    val app: AppApkInfo? = null,
) : ServerFrame

/** Chegou uma versão nova do app no servidor: os celulares mostram "Atualizar". */
@Serializable @SerialName("app_update")
data class AppUpdateFrame(val app: AppApkInfo) : ServerFrame

@Serializable @SerialName("ack")
data class AckFrame(val id: String, val seq: Long, val createdAt: Long) : ServerFrame

@Serializable @SerialName("message")
data class MessageFrame(val message: MessageDto) : ServerFrame

@Serializable @SerialName("sync_batch")
data class SyncBatchFrame(val messages: List<MessageDto>) : ServerFrame

@Serializable @SerialName("sync_done")
data class SyncDoneFrame(val lastSeq: Long) : ServerFrame

@Serializable @SerialName("conversation")
data class ConversationFrame(val requestId: String? = null, val conversation: ConversationDto) : ServerFrame

@Serializable @SerialName("users")
data class UsersFrame(val requestId: String, val users: List<UserDto>) : ServerFrame

@Serializable @SerialName("invite")
data class InviteFrame(
    val requestId: String,
    val code: String,
    val expiresAt: Long,
    /** Link para mandar pelo WhatsApp (https://.../convite/CODIGO). Null se o servidor ainda não sabe o próprio endereço. */
    val link: String? = null,
) : ServerFrame

/** Alguém mudou o nome ou a foto. Vai para todos os aparelhos conectados (e responde a [UpdateProfileFrame]). */
@Serializable @SerialName("user_updated")
data class UserUpdatedFrame(val requestId: String?, val user: UserDto) : ServerFrame

/** Resposta a [SetPublicAddressFrame]. */
@Serializable @SerialName("public_address")
data class PublicAddressFrame(val requestId: String, val publicBase: String) : ServerFrame

/** A conversa foi apagada por mim: sai da lista e as mensagens até [clearedSeq] somem. */
@Serializable @SerialName("conversation_hidden")
data class ConversationHiddenFrame(val requestId: String? = null, val conversationId: String, val clearedSeq: Long) : ServerFrame

@Serializable @SerialName("pong")
data class PongFrame(val requestId: String) : ServerFrame

@Serializable @SerialName("error")
data class ErrorFrame(val code: String, val message: String, val requestId: String? = null) : ServerFrame

@Serializable @SerialName("web_approved")
data class WebApprovedFrame(val requestId: String, val deviceName: String) : ServerFrame

/** Falaí no computador: o QR Code leva "falai://web?c=CODIGO"; o código também pode ser digitado. */
object WebLinkCodes {
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun qrText(code: String) = "falai://web?c=$code"

    /** Aceita o texto lido do QR Code ou o código digitado ("abcd 2345", "ABCD-2345"...). */
    fun parse(input: String): String? {
        val t = input.trim()
        val raw = if (t.startsWith("falai://web", ignoreCase = true)) {
            t.substringAfter("c=", "").substringBefore('&')
        } else t
        val code = raw.uppercase().filter { it.isLetterOrDigit() }
        return code.takeIf { it.length == 8 && it.all { c -> c in ALPHABET } }
    }
}

// ---------- Chamadas de voz e vídeo (WebRTC) ----------
//
// O servidor só "apresenta" os aparelhos: o áudio e o vídeo vão direto de um aparelho para o
// outro (WebRTC). Em grupo, cada um liga para cada um (até [MAX_CALL_PARTICIPANTS]).
//
//   A: call_start          -> servidor cria a chamada, responde call_state e manda call_ring aos outros
//   B: call_join           -> servidor manda call_state (com A e B) para todos da conversa
//   B: call_signal(offer)  -> quem entra liga para quem já estava; A responde com call_signal(answer)
//   *: call_signal(ice)    -> caminhos de rede (ICE), nos dois sentidos
//   *: call_leave          -> sai; conversa a dois termina quando um sai
//   B: call_decline        -> recusa (a dois: termina a chamada; em grupo: só para de tocar para B)

const val MAX_CALL_PARTICIPANTS = 6

@Serializable
data class CallParticipantDto(
    val deviceId: String,
    val userId: String,
    val mic: Boolean = true,
    val cam: Boolean = false,
    /** Mostrando a tela do aparelho/computador. */
    val screen: Boolean = false,
)

@Serializable
data class CallDto(
    val id: String,
    val conversationId: String,
    /** Começou como chamada de vídeo (true) ou só de voz (false). */
    val video: Boolean,
    val startedBy: String,
    val startedAt: Long,
    val participants: List<CallParticipantDto>,
    /** Quem recusou (os aparelhos dessa pessoa param de tocar). */
    val declinedBy: List<String> = emptyList(),
    /** Terminou. [endReason]: "hangup", "no_answer", "declined", "busy". */
    val ended: Boolean = false,
    val endReason: String? = null,
)

/** Servidor STUN/TURN que o aparelho deve usar para achar o caminho até os outros. */
@Serializable
data class IceServerDto(val urls: List<String>, val username: String? = null, val credential: String? = null)

@Serializable @SerialName("call_start")
data class CallStartFrame(val requestId: String, val callId: String, val conversationId: String, val video: Boolean) : ClientFrame

@Serializable @SerialName("call_join")
data class CallJoinFrame(val requestId: String, val callId: String, val mic: Boolean = true, val cam: Boolean = false) : ClientFrame

@Serializable @SerialName("call_leave")
data class CallLeaveFrame(val callId: String) : ClientFrame

@Serializable @SerialName("call_decline")
data class CallDeclineFrame(val callId: String, val busy: Boolean = false) : ClientFrame

/** Microfone, câmera e tela ligados ou não (os outros mostram a foto quando a câmera está desligada). */
@Serializable @SerialName("call_media")
data class CallMediaFrame(val callId: String, val mic: Boolean, val cam: Boolean, val screen: Boolean = false) : ClientFrame

/** Recado WebRTC para um aparelho: [kind] = "offer", "answer" ou "ice"; [data] = SDP ou candidato (JSON). */
@Serializable @SerialName("call_signal")
data class CallSignalFrame(val callId: String, val toDeviceId: String, val kind: String, val data: String) : ClientFrame

@Serializable @SerialName("call_ice")
data class CallIceRequestFrame(val requestId: String) : ClientFrame

@Serializable @SerialName("call_state")
data class CallStateFrame(val requestId: String? = null, val call: CallDto) : ServerFrame

/** Alguém está ligando para uma conversa minha. */
@Serializable @SerialName("call_ring")
data class CallRingFrame(val call: CallDto) : ServerFrame

@Serializable @SerialName("call_signal_in")
data class CallSignalInFrame(
    val callId: String,
    val fromDeviceId: String,
    val fromUserId: String,
    val kind: String,
    val data: String,
) : ServerFrame

@Serializable @SerialName("call_ice_servers")
data class CallIceServersFrame(val requestId: String, val servers: List<IceServerDto>) : ServerFrame

/**
 * Só o administrador: liga o "ajudante de chamadas" (TURN da Cloudflare, grátis até 1.000 GB/mês),
 * que faz a chamada funcionar mesmo quando os dois estão no 4G. Vazio = desliga.
 */
@Serializable @SerialName("set_turn")
data class SetTurnFrame(val requestId: String, val keyId: String, val token: String) : ClientFrame

@Serializable @SerialName("turn_status")
data class TurnStatusFrame(val requestId: String, val configured: Boolean, val message: String) : ServerFrame
