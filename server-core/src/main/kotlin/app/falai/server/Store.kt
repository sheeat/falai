package app.falai.server

import app.falai.protocol.*
import app.falai.db.*

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Erro de regra de negócio, devolvido ao cliente com um código estável. */
class FalaiException(val code: String, message: String) : RuntimeException(message)

data class AuthedDevice(val deviceId: String, val user: UserDto)

data class StoredMessage(val message: MessageDto, val isNew: Boolean)

/**
 * Toda a persistência do servidor. A lógica SQL fica aqui, igual no PC e no Android.
 */
/** Por quanto tempo um pareamento pode ser repetido com o mesmo id de tentativa. */
const val PAIR_RETRY_WINDOW_MS = 30 * 60_000L

class Store(private val db: Db, private val clock: () -> Long = System::currentTimeMillis) {

    private val random = SecureRandom()

    init {
        migrate()
    }

    private fun migrate() {
        db.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        val version = db.query("SELECT value FROM meta WHERE key = 'schema_version'")
            .firstOrNull()?.string("value")?.toInt() ?: 0
        if (version < 1) {
            db.transaction {
                db.execute(
                    """CREATE TABLE users (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        is_admin INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL)"""
                )
                db.execute(
                    """CREATE TABLE devices (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL REFERENCES users(id),
                        name TEXT NOT NULL,
                        token_hash TEXT NOT NULL UNIQUE,
                        created_at INTEGER NOT NULL,
                        last_seen_at INTEGER,
                        revoked_at INTEGER)"""
                )
                db.execute(
                    """CREATE TABLE invites (
                        code_hash TEXT PRIMARY KEY,
                        created_by TEXT REFERENCES users(id),
                        for_user_id TEXT REFERENCES users(id),
                        make_admin INTEGER NOT NULL DEFAULT 0,
                        expires_at INTEGER NOT NULL,
                        used_at INTEGER)"""
                )
                db.execute(
                    """CREATE TABLE conversations (
                        id TEXT PRIMARY KEY,
                        kind TEXT NOT NULL,
                        title TEXT,
                        created_by TEXT NOT NULL REFERENCES users(id),
                        created_at INTEGER NOT NULL)"""
                )
                db.execute(
                    """CREATE TABLE members (
                        conversation_id TEXT NOT NULL REFERENCES conversations(id),
                        user_id TEXT NOT NULL REFERENCES users(id),
                        PRIMARY KEY (conversation_id, user_id))"""
                )
                db.execute("CREATE INDEX members_by_user ON members(user_id)")
                db.execute(
                    """CREATE TABLE messages (
                        seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        id TEXT NOT NULL UNIQUE,
                        conversation_id TEXT NOT NULL REFERENCES conversations(id),
                        author_id TEXT NOT NULL REFERENCES users(id),
                        author_device_id TEXT NOT NULL REFERENCES devices(id),
                        body TEXT NOT NULL,
                        created_at INTEGER NOT NULL)"""
                )
                db.execute("CREATE INDEX messages_by_conversation ON messages(conversation_id, seq)")
                db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '1')")
            }
        }
        if (version < 2) {
            db.transaction {
                // M3: anexos. O arquivo fica em disco; aqui só os dados dele.
                db.execute("ALTER TABLE messages ADD COLUMN attachment_json TEXT")
                db.execute("ALTER TABLE messages ADD COLUMN media_id TEXT")
                db.execute("CREATE INDEX messages_by_media ON messages(media_id)")
                db.execute(
                    """CREATE TABLE media (
                        id TEXT PRIMARY KEY,
                        uploader_id TEXT NOT NULL REFERENCES users(id),
                        size INTEGER NOT NULL,
                        sha256 TEXT NOT NULL,
                        mime TEXT NOT NULL,
                        name TEXT NOT NULL,
                        received INTEGER NOT NULL DEFAULT 0,
                        complete INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL)"""
                )
                db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '2')")
            }
        }
        if (version < 3) {
            db.transaction {
                // Pareamento que pode ser repetido com segurança se a resposta se perder.
                db.execute("ALTER TABLE invites ADD COLUMN used_attempt_hash TEXT")
                db.execute("ALTER TABLE invites ADD COLUMN used_device_id TEXT")
                db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '3')")
            }
        }
    }

    // ---------- Utilidades ----------

    private fun newId(): String = UUID.randomUUID().toString()

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun newToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Código curto para digitar ou ler em QR: 10 caracteres sem letras ambíguas (0/O, 1/I/L). */
    private fun newInviteCode(): String {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return (1..10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun normalizeCode(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

    // ---------- Usuários e convites ----------

    fun hasUsers(): Boolean = db.query("SELECT 1 AS x FROM users LIMIT 1").isNotEmpty()

    /**
     * Quando ainda não existe ninguém, gera o convite do administrador (você).
     * Devolve null se já existe pelo menos uma pessoa cadastrada.
     */
    fun bootstrapInviteIfEmpty(validForMs: Long = 24 * 3600_000L): Pair<String, Long>? = db.transaction {
        if (hasUsers()) return@transaction null
        // Um convite de bootstrap por vez: invalida os anteriores não usados.
        db.update("DELETE FROM invites WHERE created_by IS NULL AND used_at IS NULL")
        val code = newInviteCode()
        val expires = clock() + validForMs
        db.execute(
            "INSERT INTO invites (code_hash, created_by, for_user_id, make_admin, expires_at) VALUES (?, NULL, NULL, 1, ?)",
            sha256(code), expires,
        )
        code to expires
    }

    fun createInvite(by: UserDto, forUserId: String?, validForMs: Long = 24 * 3600_000L): Pair<String, Long> {
        if (!by.isAdmin) throw FalaiException("forbidden", "Só o administrador cria convites.")
        if (forUserId != null && userById(forUserId) == null) {
            throw FalaiException("not_found", "Pessoa não encontrada.")
        }
        val code = newInviteCode()
        val expires = clock() + validForMs
        db.execute(
            "INSERT INTO invites (code_hash, created_by, for_user_id, make_admin, expires_at) VALUES (?, ?, ?, 0, ?)",
            sha256(code), by.id, forUserId, expires,
        )
        return code to expires
    }

    /** Usa um convite (uma vez só) e cria o aparelho, e a pessoa se for nova. */
    fun pair(req: PairRequest): PairResponse = db.transaction {
        val code = normalizeCode(req.code)
        val deviceName = req.deviceName.trim().take(80)
        if (deviceName.isEmpty()) throw FalaiException("invalid", "Informe o nome do aparelho.")

        val invite = db.query("SELECT * FROM invites WHERE code_hash = ?", sha256(code)).firstOrNull()
            ?: throw FalaiException("invalid_code", "Código de convite inválido.")
        val attemptHash = req.attemptId?.trim()?.takeIf { it.length in 8..64 }?.let(::sha256)
        val usedAt = invite.longOrNull("used_at")
        if (usedAt != null) {
            // O mesmo aparelho repetindo o pedido (a resposta anterior se perdeu): responde de novo.
            val sameAttempt = attemptHash != null && attemptHash == invite.stringOrNull("used_attempt_hash")
            val deviceId = invite.stringOrNull("used_device_id")
            if (sameAttempt && deviceId != null && clock() - usedAt <= PAIR_RETRY_WINDOW_MS) {
                val row = db.query(
                    """SELECT u.id AS user_id, u.is_admin AS is_admin FROM devices d JOIN users u ON u.id = d.user_id
                       WHERE d.id = ? AND d.revoked_at IS NULL""",
                    deviceId,
                ).firstOrNull()
                if (row != null) {
                    val token = newToken()
                    db.update("UPDATE devices SET token_hash = ? WHERE id = ?", sha256(token), deviceId)
                    return@transaction PairResponse(row.string("user_id"), deviceId, token, row.bool("is_admin"))
                }
            }
            throw FalaiException("invalid_code", "Este convite já foi usado.")
        }
        if (invite.long("expires_at") < clock()) throw FalaiException("invalid_code", "Este convite expirou.")

        val now = clock()
        val existingUserId = invite.stringOrNull("for_user_id")
        val user: UserDto = if (existingUserId != null) {
            userById(existingUserId) ?: throw FalaiException("invalid_code", "A pessoa deste convite não existe mais.")
        } else {
            val name = req.userName?.trim()?.take(60).orEmpty()
            if (name.isEmpty()) throw FalaiException("invalid", "Informe o seu nome.")
            val u = UserDto(newId(), name, invite.bool("make_admin"))
            db.execute(
                "INSERT INTO users (id, name, is_admin, created_at) VALUES (?, ?, ?, ?)",
                u.id, u.name, u.isAdmin, now,
            )
            u
        }

        val token = newToken()
        val deviceId = newId()
        db.execute(
            "INSERT INTO devices (id, user_id, name, token_hash, created_at) VALUES (?, ?, ?, ?, ?)",
            deviceId, user.id, deviceName, sha256(token), now,
        )
        val changed = db.update(
            "UPDATE invites SET used_at = ?, used_attempt_hash = ?, used_device_id = ? WHERE code_hash = ? AND used_at IS NULL",
            now, attemptHash, deviceId, sha256(code),
        )
        if (changed != 1) throw FalaiException("invalid_code", "Este convite já foi usado.")

        PairResponse(user.id, deviceId, token, user.isAdmin)
    }

    fun authenticate(token: String): AuthedDevice? {
        val row = db.query(
            """SELECT d.id AS device_id, u.id AS user_id, u.name AS name, u.is_admin AS is_admin
               FROM devices d JOIN users u ON u.id = d.user_id
               WHERE d.token_hash = ? AND d.revoked_at IS NULL""",
            sha256(token),
        ).firstOrNull() ?: return null
        db.update("UPDATE devices SET last_seen_at = ? WHERE id = ?", clock(), row.string("device_id"))
        return AuthedDevice(
            row.string("device_id"),
            UserDto(row.string("user_id"), row.string("name"), row.bool("is_admin")),
        )
    }

    fun userById(id: String): UserDto? =
        db.query("SELECT id, name, is_admin FROM users WHERE id = ?", id).firstOrNull()
            ?.let { UserDto(it.string("id"), it.string("name"), it.bool("is_admin")) }

    fun listUsers(): List<UserDto> =
        db.query("SELECT id, name, is_admin FROM users ORDER BY name COLLATE NOCASE")
            .map { UserDto(it.string("id"), it.string("name"), it.bool("is_admin")) }

    // ---------- Conversas ----------

    fun createConversation(by: UserDto, kind: String, title: String?, otherMemberIds: List<String>): ConversationDto =
        db.transaction {
            if (kind != "direct" && kind != "group") throw FalaiException("invalid", "Tipo de conversa inválido.")
            val members = (otherMemberIds + by.id).distinct()
            members.forEach { if (userById(it) == null) throw FalaiException("not_found", "Pessoa não encontrada: $it") }
            if (kind == "direct") {
                if (members.size != 2) throw FalaiException("invalid", "Conversa direta tem exatamente duas pessoas.")
                // Conversa direta entre as mesmas duas pessoas é única: devolve a que já existe.
                findDirect(members[0], members[1])?.let { return@transaction it }
            } else if (members.size < 2) {
                throw FalaiException("invalid", "Grupo precisa de pelo menos duas pessoas.")
            }
            val id = newId()
            val now = clock()
            db.execute(
                "INSERT INTO conversations (id, kind, title, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
                id, kind, title?.trim()?.take(80), by.id, now,
            )
            members.forEach { db.execute("INSERT INTO members (conversation_id, user_id) VALUES (?, ?)", id, it) }
            conversation(id)!!
        }

    private fun findDirect(a: String, b: String): ConversationDto? {
        val row = db.query(
            """SELECT c.id FROM conversations c
               WHERE c.kind = 'direct'
                 AND EXISTS (SELECT 1 FROM members m WHERE m.conversation_id = c.id AND m.user_id = ?)
                 AND EXISTS (SELECT 1 FROM members m WHERE m.conversation_id = c.id AND m.user_id = ?)
               LIMIT 1""",
            a, b,
        ).firstOrNull() ?: return null
        return conversation(row.string("id"))
    }

    fun conversation(id: String): ConversationDto? {
        val c = db.query("SELECT * FROM conversations WHERE id = ?", id).firstOrNull() ?: return null
        return ConversationDto(
            id = c.string("id"),
            kind = c.string("kind"),
            title = c.stringOrNull("title"),
            memberIds = memberIds(id),
            createdAt = c.long("created_at"),
        )
    }

    fun memberIds(conversationId: String): List<String> =
        db.query("SELECT user_id FROM members WHERE conversation_id = ? ORDER BY user_id", conversationId)
            .map { it.string("user_id") }

    fun conversationsOf(userId: String): List<ConversationDto> =
        db.query(
            "SELECT conversation_id FROM members WHERE user_id = ? ORDER BY conversation_id", userId,
        ).mapNotNull { conversation(it.string("conversation_id")) }

    fun isMember(conversationId: String, userId: String): Boolean =
        db.query(
            "SELECT 1 AS x FROM members WHERE conversation_id = ? AND user_id = ?", conversationId, userId,
        ).isNotEmpty()

    // ---------- Mensagens ----------

    fun storeMessage(device: AuthedDevice, frame: SendFrame): StoredMessage = db.transaction {
        val id = frame.id.trim()
        if (id.isEmpty() || id.length > 64) throw FalaiException("invalid", "Id de mensagem inválido.")
        if (frame.body.isEmpty() && frame.attachment == null) throw FalaiException("invalid", "Mensagem vazia.")
        if (frame.body.length > 64_000) throw FalaiException("too_large", "Mensagem grande demais.")

        val existing = messageById(id)
        if (existing != null) {
            // Reenvio (ex.: a conexão caiu antes do ack). Só aceita se for do mesmo autor.
            if (existing.authorId != device.user.id) throw FalaiException("conflict", "Id de mensagem já usado.")
            return@transaction StoredMessage(existing, isNew = false)
        }
        if (!isMember(frame.conversationId, device.user.id)) {
            throw FalaiException("forbidden", "Você não participa desta conversa.")
        }
        val att = frame.attachment?.let { a ->
            val m = media(a.mediaId) ?: throw FalaiException("invalid", "Anexo não encontrado.")
            if (!m.complete) throw FalaiException("invalid", "O anexo ainda não terminou de subir.")
            if (m.uploaderId != device.user.id) throw FalaiException("forbidden", "Anexo de outra pessoa.")
            if (!a.kind.matches(Regex("image|video|audio|file"))) throw FalaiException("invalid", "Tipo de anexo inválido.")
            // Os dados confiáveis vêm do servidor; do cliente só o tipo, a duração e a miniatura.
            a.copy(mime = m.mime, name = m.name, size = m.size, sha256 = m.sha256, thumb = sanitizeThumb(a.thumb))
        }
        val now = clock()
        val seq = db.insert(
            """INSERT INTO messages (id, conversation_id, author_id, author_device_id, body, created_at, attachment_json, media_id)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            id, frame.conversationId, device.user.id, device.deviceId, frame.body, now,
            att?.let { FalaiJson.encodeToString(AttachmentDto.serializer(), it) }, att?.mediaId,
        )
        StoredMessage(
            MessageDto(seq, id, frame.conversationId, device.user.id, device.deviceId, frame.body, now, att),
            isNew = true,
        )
    }

    private fun rowToMessage(r: Row) = MessageDto(
        seq = r.long("seq"),
        id = r.string("id"),
        conversationId = r.string("conversation_id"),
        authorId = r.string("author_id"),
        authorDeviceId = r.string("author_device_id"),
        body = r.string("body"),
        createdAt = r.long("created_at"),
        attachment = r.stringOrNull("attachment_json")?.let { FalaiJson.decodeFromString(AttachmentDto.serializer(), it) },
    )

    fun messageById(id: String): MessageDto? =
        db.query("SELECT * FROM messages WHERE id = ?", id).firstOrNull()?.let(::rowToMessage)

    /** Mensagens das conversas da pessoa com seq maior que [afterSeq], em ordem. */
    fun messagesSince(userId: String, afterSeq: Long, limit: Int): List<MessageDto> =
        db.query(
            """SELECT m.* FROM messages m
               JOIN members mb ON mb.conversation_id = m.conversation_id AND mb.user_id = ?
               WHERE m.seq > ?
               ORDER BY m.seq
               LIMIT ?""",
            userId, afterSeq, limit,
        ).map(::rowToMessage)

    fun lastSeqFor(userId: String): Long =
        db.query(
            """SELECT COALESCE(MAX(m.seq), 0) AS s FROM messages m
               JOIN members mb ON mb.conversation_id = m.conversation_id AND mb.user_id = ?""",
            userId,
        ).first().long("s")

    // ---------- Mídia ----------

    data class MediaRow(
        val id: String, val uploaderId: String, val size: Long, val sha256: String,
        val mime: String, val name: String, val received: Long, val complete: Boolean,
    ) {
        fun status() = MediaStatus(id, size, received, complete)
    }

    private fun rowToMedia(r: Row) = MediaRow(
        r.string("id"), r.string("uploader_id"), r.long("size"), r.string("sha256"),
        r.string("mime"), r.string("name"), r.long("received"), r.bool("complete"),
    )

    fun media(id: String): MediaRow? =
        db.query("SELECT * FROM media WHERE id = ?", id).firstOrNull()?.let(::rowToMedia)

    fun createMedia(uploaderId: String, req: MediaCreateRequest): MediaRow {
        if (req.size <= 0 || req.size > MEDIA_MAX_BYTES) throw FalaiException("too_large", "Arquivo vazio ou grande demais (máximo 2 GB).")
        if (!req.sha256.matches(Regex("[0-9a-f]{64}"))) throw FalaiException("invalid", "Hash inválido.")
        val id = newId()
        db.execute(
            """INSERT INTO media (id, uploader_id, size, sha256, mime, name, received, complete, created_at)
               VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?)""",
            id, uploaderId, req.size, req.sha256, req.mime.take(100).ifBlank { "application/octet-stream" },
            req.name.take(200).ifBlank { "arquivo" }, clock(),
        )
        return media(id)!!
    }

    fun setMediaProgress(id: String, received: Long, complete: Boolean) {
        db.update("UPDATE media SET received = ?, complete = ? WHERE id = ?", received, complete, id)
    }

    /** Quem pode baixar: quem enviou, ou quem participa de uma conversa onde o anexo apareceu. */
    fun canDownload(mediaId: String, userId: String): Boolean {
        val m = media(mediaId) ?: return false
        if (m.uploaderId == userId) return true
        return db.query(
            """SELECT 1 AS x FROM messages msg
               JOIN members mb ON mb.conversation_id = msg.conversation_id AND mb.user_id = ?
               WHERE msg.media_id = ? LIMIT 1""",
            userId, mediaId,
        ).isNotEmpty()
    }

    /** Uso total dos arquivos completos, em bytes (para o alerta de armazenamento, no futuro). */
    fun mediaBytes(): Long = db.query("SELECT COALESCE(SUM(size), 0) AS s FROM media WHERE complete = 1").first().long("s")
}
