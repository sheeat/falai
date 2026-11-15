package app.falai.client

import app.falai.db.Db
import app.falai.db.Row
import app.falai.protocol.AttachmentDto
import app.falai.protocol.ConversationDto
import app.falai.protocol.FalaiJson
import app.falai.protocol.MessageDto
import app.falai.protocol.UserDto

/** Dados do pareamento deste aparelho. */
data class Account(
    val serverUrl: String,
    val token: String,
    val userId: String,
    val deviceId: String,
    val isAdmin: Boolean,
)

/** PENDING = na fila (relógio), SENT = servidor recebeu (✓), RECEIVED = de outra pessoa, FAILED = recusada. */
enum class MessageStatus { PENDING, SENT, RECEIVED, FAILED }

data class LocalMessage(
    val id: String,
    val seq: Long?,
    val conversationId: String,
    val authorId: String,
    val body: String,
    val createdAt: Long,
    val status: MessageStatus,
    /** Anexo. Enquanto o arquivo ainda está subindo, mediaId é "". */
    val attachment: AttachmentDto? = null,
    /** Caminho do arquivo neste aparelho, se já existir aqui (enviado daqui ou já baixado). */
    val localPath: String? = null,
)

/** Arquivo deste aparelho esperando para subir. */
data class OutgoingMedia(
    val messageId: String,
    val path: String,
    val mediaId: String?,
    val attachment: AttachmentDto,
)

data class ConversationSummary(
    val id: String,
    val kind: String,
    /** Para conversa direta: o nome da outra pessoa. Para grupo: o título. */
    val displayName: String,
    val memberIds: List<String>,
    val lastBody: String?,
    val lastAuthorId: String?,
    val lastAt: Long?,
    val lastStatus: MessageStatus?,
)

/**
 * Banco local do app (SQLite). Guarda a conta, as pessoas, as conversas e todas as mensagens,
 * inclusive as que ainda estão na fila esperando conexão.
 */
class ClientStore(private val db: Db, private val clock: () -> Long = System::currentTimeMillis) {

    init {
        db.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        val version = db.query("SELECT value FROM meta WHERE key = 'schema_version'")
            .firstOrNull()?.string("value")?.toInt() ?: 0
        if (version < 1) {
            db.transaction {
                db.execute("CREATE TABLE config (key TEXT PRIMARY KEY, value TEXT)")
                db.execute("CREATE TABLE users (id TEXT PRIMARY KEY, name TEXT NOT NULL, is_admin INTEGER NOT NULL)")
                db.execute(
                    """CREATE TABLE conversations (
                        id TEXT PRIMARY KEY, kind TEXT NOT NULL, title TEXT, created_at INTEGER NOT NULL)"""
                )
                db.execute(
                    """CREATE TABLE members (
                        conversation_id TEXT NOT NULL, user_id TEXT NOT NULL,
                        PRIMARY KEY (conversation_id, user_id))"""
                )
                // rowid (automático) dá a ordem local; seq é a ordem do servidor (null = na fila).
                db.execute(
                    """CREATE TABLE messages (
                        id TEXT PRIMARY KEY,
                        seq INTEGER,
                        conversation_id TEXT NOT NULL,
                        author_id TEXT NOT NULL,
                        body TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        status TEXT NOT NULL)"""
                )
                db.execute("CREATE INDEX messages_by_conv ON messages(conversation_id)")
                db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '1')")
            }
        }
        if (version < 2) {
            db.transaction {
                db.execute("ALTER TABLE messages ADD COLUMN attachment_json TEXT")
                // Arquivos que já estão neste aparelho (baixados ou enviados daqui).
                db.execute("CREATE TABLE local_media (media_id TEXT PRIMARY KEY, path TEXT NOT NULL)")
                // Fila de envio de arquivos.
                db.execute(
                    """CREATE TABLE outgoing (
                        message_id TEXT PRIMARY KEY,
                        path TEXT NOT NULL,
                        media_id TEXT,
                        uploaded INTEGER NOT NULL DEFAULT 0)"""
                )
                db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '2')")
            }
        }
    }

    // ---------- Configuração ----------

    private fun getConfig(key: String): String? =
        db.query("SELECT value FROM config WHERE key = ?", key).firstOrNull()?.stringOrNull("value")

    private fun setConfig(key: String, value: String?) {
        db.execute("INSERT OR REPLACE INTO config (key, value) VALUES (?, ?)", key, value)
    }

    fun account(): Account? {
        val token = getConfig("token") ?: return null
        return Account(
            serverUrl = getConfig("serverUrl")!!,
            token = token,
            userId = getConfig("userId")!!,
            deviceId = getConfig("deviceId")!!,
            isAdmin = getConfig("isAdmin") == "1",
        )
    }

    fun saveAccount(a: Account) = db.transaction {
        setConfig("serverUrl", a.serverUrl)
        setConfig("token", a.token)
        setConfig("userId", a.userId)
        setConfig("deviceId", a.deviceId)
        setConfig("isAdmin", if (a.isAdmin) "1" else "0")
    }

    /** Maior seq já sincronizado por completo. O próximo sync começa daqui. */
    fun syncedSeq(): Long = getConfig("syncedSeq")?.toLong() ?: 0L
    fun setSyncedSeq(seq: Long) = setConfig("syncedSeq", seq.toString())

    // ---------- Pessoas e conversas ----------

    fun replaceUsers(users: List<UserDto>) = db.transaction {
        db.execute("DELETE FROM users")
        users.forEach { db.execute("INSERT INTO users (id, name, is_admin) VALUES (?, ?, ?)", it.id, it.name, it.isAdmin) }
    }

    fun upsertUser(u: UserDto) {
        db.execute("INSERT OR REPLACE INTO users (id, name, is_admin) VALUES (?, ?, ?)", u.id, u.name, u.isAdmin)
    }

    fun users(): List<UserDto> =
        db.query("SELECT * FROM users ORDER BY name COLLATE NOCASE")
            .map { UserDto(it.string("id"), it.string("name"), it.bool("is_admin")) }

    fun userName(id: String): String? =
        db.query("SELECT name FROM users WHERE id = ?", id).firstOrNull()?.string("name")

    fun upsertConversation(c: ConversationDto) = db.transaction {
        db.execute(
            "INSERT OR REPLACE INTO conversations (id, kind, title, created_at) VALUES (?, ?, ?, ?)",
            c.id, c.kind, c.title, c.createdAt,
        )
        db.execute("DELETE FROM members WHERE conversation_id = ?", c.id)
        c.memberIds.forEach { db.execute("INSERT INTO members (conversation_id, user_id) VALUES (?, ?)", c.id, it) }
    }

    private fun memberIds(conversationId: String) =
        db.query("SELECT user_id FROM members WHERE conversation_id = ? ORDER BY user_id", conversationId)
            .map { it.string("user_id") }

    fun conversationSummaries(myUserId: String): List<ConversationSummary> {
        val convs = db.query("SELECT * FROM conversations")
        return convs.map { c ->
            val id = c.string("id")
            val kind = c.string("kind")
            val members = memberIds(id)
            val last = db.query(
                """SELECT * FROM messages WHERE conversation_id = ?
                   ORDER BY (seq IS NULL), seq DESC, rowid DESC LIMIT 1""",
                id,
            ).firstOrNull()
            // Pendentes ficam por último na ordenação acima; pega a mais recente de verdade:
            val newestPending = db.query(
                "SELECT * FROM messages WHERE conversation_id = ? AND seq IS NULL ORDER BY rowid DESC LIMIT 1", id,
            ).firstOrNull()
            val shown = newestPending ?: last
            val name = if (kind == "direct") {
                members.firstOrNull { it != myUserId }?.let { userName(it) } ?: "Conversa"
            } else {
                c.stringOrNull("title") ?: "Grupo"
            }
            ConversationSummary(
                id = id,
                kind = kind,
                displayName = name,
                memberIds = members,
                lastBody = shown?.let { preview(it.string("body"), it.stringOrNull("attachment_json")?.let(::decodeAtt)) },
                lastAuthorId = shown?.string("author_id"),
                lastAt = shown?.long("created_at") ?: c.long("created_at"),
                lastStatus = shown?.let { MessageStatus.valueOf(it.string("status")) },
            )
        }.sortedByDescending { it.lastAt ?: 0 }
    }

    // ---------- Mensagens ----------

    private fun decodeAtt(json: String) = FalaiJson.decodeFromString(AttachmentDto.serializer(), json)
    private fun encodeAtt(a: AttachmentDto?) = a?.let { FalaiJson.encodeToString(AttachmentDto.serializer(), it) }

    private fun rowToMessage(r: Row): LocalMessage {
        val att = r.stringOrNull("attachment_json")?.let(::decodeAtt)
        val id = r.string("id")
        val path = if (att == null) null else {
            db.query("SELECT path FROM outgoing WHERE message_id = ?", id).firstOrNull()?.string("path")
                ?: att.mediaId.takeIf { it.isNotEmpty() }?.let(::localMediaPath)
        }
        return LocalMessage(
            id = id,
            seq = r.longOrNull("seq"),
            conversationId = r.string("conversation_id"),
            authorId = r.string("author_id"),
            body = r.string("body"),
            createdAt = r.long("created_at"),
            status = MessageStatus.valueOf(r.string("status")),
            attachment = att,
            localPath = path,
        )
    }

    /** Mensagens da conversa em ordem: confirmadas pelo servidor primeiro, depois as da fila. */
    fun messages(conversationId: String): List<LocalMessage> =
        db.query(
            "SELECT * FROM messages WHERE conversation_id = ? ORDER BY (seq IS NULL), seq, rowid",
            conversationId,
        ).map(::rowToMessage)

    /** Mensagens prontas para mandar (as com arquivo só depois que ele terminou de subir). */
    fun pending(): List<LocalMessage> =
        db.query(
            """SELECT * FROM messages WHERE status = 'PENDING'
               AND id NOT IN (SELECT message_id FROM outgoing WHERE uploaded = 0)
               ORDER BY rowid"""
        ).map(::rowToMessage)

    fun insertPendingMedia(
        id: String, conversationId: String, authorId: String, body: String,
        attachment: AttachmentDto, path: String,
    ): LocalMessage = db.transaction {
        val now = clock()
        db.execute(
            """INSERT INTO messages (id, seq, conversation_id, author_id, body, created_at, status, attachment_json)
               VALUES (?, NULL, ?, ?, ?, ?, 'PENDING', ?)""",
            id, conversationId, authorId, body, now, encodeAtt(attachment.copy(mediaId = "")),
        )
        db.execute("INSERT INTO outgoing (message_id, path, media_id, uploaded) VALUES (?, ?, NULL, 0)", id, path)
        messageById(id)!!
    }

    fun messageById(id: String): LocalMessage? =
        db.query("SELECT * FROM messages WHERE id = ?", id).firstOrNull()?.let(::rowToMessage)

    fun outgoingToUpload(): List<OutgoingMedia> =
        db.query(
            """SELECT o.*, m.attachment_json FROM outgoing o JOIN messages m ON m.id = o.message_id
               WHERE o.uploaded = 0 AND m.status = 'PENDING' ORDER BY m.rowid"""
        ).map {
            OutgoingMedia(it.string("message_id"), it.string("path"), it.stringOrNull("media_id"), decodeAtt(it.string("attachment_json")))
        }

    fun setOutgoingMediaId(messageId: String, mediaId: String) {
        db.update("UPDATE outgoing SET media_id = ? WHERE message_id = ?", mediaId, messageId)
    }

    fun resetOutgoingMediaId(messageId: String) {
        db.update("UPDATE outgoing SET media_id = NULL WHERE message_id = ?", messageId)
    }

    /** O arquivo terminou de subir: a mensagem já pode ser enviada. */
    fun markUploaded(messageId: String, mediaId: String) = db.transaction {
        val m = messageById(messageId) ?: return@transaction
        db.update(
            "UPDATE messages SET attachment_json = ? WHERE id = ?",
            encodeAtt(m.attachment!!.copy(mediaId = mediaId)), messageId,
        )
        db.update("UPDATE outgoing SET uploaded = 1, media_id = ? WHERE message_id = ?", mediaId, messageId)
        val path = db.query("SELECT path FROM outgoing WHERE message_id = ?", messageId).first().string("path")
        setLocalMedia(mediaId, path)
    }

    fun localMediaPath(mediaId: String): String? =
        db.query("SELECT path FROM local_media WHERE media_id = ?", mediaId).firstOrNull()?.string("path")

    fun setLocalMedia(mediaId: String, path: String) {
        db.execute("INSERT OR REPLACE INTO local_media (media_id, path) VALUES (?, ?)", mediaId, path)
    }

    fun insertPending(id: String, conversationId: String, authorId: String, body: String): LocalMessage {
        val m = LocalMessage(id, null, conversationId, authorId, body, clock(), MessageStatus.PENDING)
        db.execute(
            "INSERT INTO messages (id, seq, conversation_id, author_id, body, created_at, status) VALUES (?, NULL, ?, ?, ?, ?, 'PENDING')",
            m.id, m.conversationId, m.authorId, m.body, m.createdAt,
        )
        return m
    }

    fun markSent(id: String, seq: Long, createdAt: Long) {
        db.update("UPDATE messages SET seq = ?, created_at = ?, status = 'SENT' WHERE id = ?", seq, createdAt, id)
    }

    fun markFailed(id: String) {
        db.update("UPDATE messages SET status = 'FAILED' WHERE id = ? AND status = 'PENDING'", id)
    }

    /** Guarda uma mensagem vinda do servidor. Repetida (pelo id) só atualiza. */
    fun upsertFromServer(m: MessageDto, myUserId: String) {
        val status = if (m.authorId == myUserId) MessageStatus.SENT else MessageStatus.RECEIVED
        // Sem "ON CONFLICT ... DO UPDATE": o SQLite do Android 9 é antigo demais para isso.
        db.transaction {
            val changed = db.update(
                "UPDATE messages SET seq = ?, created_at = ?, status = ?, attachment_json = ? WHERE id = ?",
                m.seq, m.createdAt, status.name, encodeAtt(m.attachment), m.id,
            )
            if (changed == 0) {
                db.execute(
                    """INSERT INTO messages (id, seq, conversation_id, author_id, body, created_at, status, attachment_json)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    m.id, m.seq, m.conversationId, m.authorId, m.body, m.createdAt, status.name, encodeAtt(m.attachment),
                )
            }
        }
    }

    companion object {
        /** Texto curto para a lista de conversas: "📷 Foto", "🎤 Áudio (0:42)" etc. */
        fun preview(body: String, att: AttachmentDto?): String {
            if (att == null) return body
            val label = when (att.kind) {
                "image" -> "📷 Foto"
                "video" -> "🎥 Vídeo"
                "audio" -> "🎤 Áudio" + (att.durationMs?.let { " (${formatDuration(it)})" } ?: "")
                else -> "📄 ${att.name}"
            }
            return if (body.isBlank()) label else "$label · $body"
        }

        fun formatDuration(ms: Long): String {
            val s = ms / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
