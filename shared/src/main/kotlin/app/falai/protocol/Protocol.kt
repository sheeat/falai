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
)

@Serializable
data class PairResponse(
    val userId: String,
    val deviceId: String,
    val token: String,
    val isAdmin: Boolean,
)

@Serializable
data class ErrorResponse(val error: String, val message: String)

// ---------- Objetos compartilhados ----------

@Serializable
data class UserDto(val id: String, val name: String, val isAdmin: Boolean)

@Serializable
data class ConversationDto(
    val id: String,
    val kind: String,
    val title: String?,
    val memberIds: List<String>,
    val createdAt: Long,
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
)

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
) : ClientFrame

@Serializable @SerialName("ping")
data class PingFrame(val requestId: String) : ClientFrame

// ---------- Servidor -> cliente ----------

@Serializable
sealed interface ServerFrame

@Serializable @SerialName("hello")
data class HelloFrame(
    val protocol: Int,
    val user: UserDto,
    val deviceId: String,
    val conversations: List<ConversationDto>,
    val lastSeq: Long,
) : ServerFrame

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
data class InviteFrame(val requestId: String, val code: String, val expiresAt: Long) : ServerFrame

@Serializable @SerialName("pong")
data class PongFrame(val requestId: String) : ServerFrame

@Serializable @SerialName("error")
data class ErrorFrame(val code: String, val message: String, val requestId: String? = null) : ServerFrame
