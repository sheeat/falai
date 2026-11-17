package app.falai.client

import app.falai.db.Db
import app.falai.db.Row
import app.falai.protocol.AttachmentDto
import app.falai.protocol.ConversationDto
import app.falai.protocol.FalaiJson
import app.falai.protocol.MessageDto
import app.falai.protocol.MessageKinds
import app.falai.protocol.ReceiptDto
import app.falai.protocol.UserDto
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Dados do pareamento deste aparelho. */
data class Account(
    val serverUrl: String,
    val token: String,
    val userId: String,
    val deviceId: String,
    val isAdmin: Boolean,
)

/**
 * PENDING = na fila (relógio), SENT = servidor recebeu (✓), RECEIVED = de outra pessoa, FAILED = recusada.
 * DELIVERED (✓✓ cinza) e READ (✓✓ azul) não ficam guardados: são calculados pelos recibos dos outros.
 */
enum class MessageStatus { PENDING, SENT, RECEIVED, FAILED, DELIVERED, READ }

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
    /** Null = normal; "system" = aviso do grupo (veja [MessageKinds]). */
    val kind: String? = null,
    /** Resposta a esta mensagem. */
    val replyToId: String? = null,
    /** Apagada para todos. */
    val deleted: Boolean = false,
    /** Reações: pessoa -> emoji. */
    val reactions: Map<String, String> = emptyMap(),
) {
    val isSystem get() = kind == MessageKinds.SYSTEM
}

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
    /** Conversa direta: a foto da outra pessoa; grupo: a foto do grupo (id do arquivo), se tiver. */
    val avatarMediaId: String? = null,
    /** Grupo: quem criou (pode tirar pessoas, junto com o administrador). */
    val createdBy: String? = null,
    /** false = saí (ou me tiraram) deste grupo: dá para ler, mas não escrever. */
    val iAmMember: Boolean = true,
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
        if (version < 3) {
            db.transaction {
                // M5: foto de perfil.
                db.execute("ALTER TABLE users ADD COLUMN avatar_media_id TEXT")
                db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '3')")
            }
        }
        if (version < 4) {
            db.transaction {
                // 0.9: quem saiu da família continua com o nome nas mensagens antigas.
                db.execute("ALTER TABLE users ADD COLUMN removed INTEGER NOT NULL DEFAULT 0")
                db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '4')")
            }
        }
        if (version < 5) {
            db.transaction {
                // 0.10: foto do grupo, responder, reagir, apagar para todos, tiques ✓✓.
                db.execute("ALTER TABLE conversations ADD COLUMN avatar_media_id TEXT")
                db.execute("ALTER TABLE conversations ADD COLUMN created_by TEXT")
                db.execute("ALTER TABLE messages ADD COLUMN kind TEXT")
                db.execute("ALTER TABLE messages ADD COLUMN reply_to_id TEXT")
                db.execute("ALTER TABLE messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execute("ALTER TABLE messages ADD COLUMN reactions_json TEXT")
                db.execute(
                    """CREATE TABLE receipts (
                        conversation_id TEXT NOT NULL, user_id TEXT NOT NULL,
                        delivered_seq INTEGER NOT NULL, read_seq INTEGER NOT NULL,
                        PRIMARY KEY (conversation_id, user_id))"""
                )
                db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '5')")
            }
        }
        if (version < 6) {
            db.transaction {
                // 0.11: cadeados dos aparelhos (para trancar) e os que já conheci (para conferir quem mandou).
                db.execute("ALTER TABLE users ADD COLUMN devices_json TEXT")
                db.execute("CREATE TABLE known_keys (device_id TEXT NOT NULL, public_key TEXT NOT NULL, PRIMARY KEY (device_id, public_key))")
                db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '6')")
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

    // ---------- Cadeado deste aparelho ----------

    /** As chaves deste aparelho (criadas na primeira vez). A privada nunca sai daqui. */
    fun myKeys(): E2e.Keys = db.transaction {
        val priv = getConfig("e2e_private")
        val pub = getConfig("e2e_public")
        if (priv != null && pub != null) return@transaction E2e.Keys(E2e.unb64(priv), E2e.unb64(pub))
        val k = E2e.newKeys()
        setConfig("e2e_private", E2e.b64(k.privatePkcs8))
        setConfig("e2e_public", k.publicB64)
        k
    }

    /** Cadeados dos aparelhos de quem participa da conversa (inclusive os meus). */
    fun deviceKeysFor(conversationId: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (uid in memberIds(conversationId)) user(uid)?.takeIf { !it.removed }?.devices?.forEach { out[it.deviceId] = it.publicKey }
        return out
    }

    /** Este cadeado já foi visto neste aparelho [deviceId]? (para aceitar mensagens dele) */
    fun isKnownKey(deviceId: String, publicKey: String): Boolean =
        db.query("SELECT 1 AS x FROM known_keys WHERE device_id = ? AND public_key = ?", deviceId, publicKey).isNotEmpty()

    private fun rememberKeys(u: UserDto) {
        u.devices.forEach { db.execute("INSERT OR IGNORE INTO known_keys (device_id, public_key) VALUES (?, ?)", it.deviceId, it.publicKey) }
    }

    private fun encodeDevices(u: UserDto) =
        FalaiJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(app.falai.protocol.DeviceKeyDto.serializer()), u.devices)

    /** Maior seq já sincronizado por completo. O próximo sync começa daqui. */
    fun syncedSeq(): Long = getConfig("syncedSeq")?.toLong() ?: 0L
    fun setSyncedSeq(seq: Long) = setConfig("syncedSeq", seq.toString())

    // ---------- Pessoas e conversas ----------

    fun replaceUsers(users: List<UserDto>) = db.transaction {
        db.execute("DELETE FROM users")
        users.forEach {
            db.execute(
                "INSERT INTO users (id, name, is_admin, avatar_media_id, removed, devices_json) VALUES (?, ?, ?, ?, ?, ?)",
                it.id, it.name, it.isAdmin, it.avatarMediaId, it.removed, encodeDevices(it),
            )
            rememberKeys(it)
        }
    }

    fun upsertUser(u: UserDto) = db.transaction {
        db.execute(
            "INSERT OR REPLACE INTO users (id, name, is_admin, avatar_media_id, removed, devices_json) VALUES (?, ?, ?, ?, ?, ?)",
            u.id, u.name, u.isAdmin, u.avatarMediaId, u.removed, encodeDevices(u),
        )
        rememberKeys(u)
    }

    private fun rowToUser(it: Row) =
        UserDto(
            it.string("id"), it.string("name"), it.bool("is_admin"), it.stringOrNull("avatar_media_id"), it.bool("removed"),
            devices = it.stringOrNull("devices_json")?.let { j ->
                runCatching {
                    FalaiJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(app.falai.protocol.DeviceKeyDto.serializer()), j)
                }.getOrNull()
            } ?: emptyList(),
        )

    /** Todo mundo, inclusive quem saiu da família (veja [UserDto.removed]). */
    fun users(): List<UserDto> = db.query("SELECT * FROM users ORDER BY name COLLATE NOCASE").map(::rowToUser)

    /** Quem está na família hoje (para começar conversa, por exemplo). */
    fun activeUsers(): List<UserDto> = users().filter { !it.removed }

    fun user(id: String): UserDto? =
        db.query("SELECT * FROM users WHERE id = ?", id).firstOrNull()?.let(::rowToUser)

    fun userName(id: String): String? =
        db.query("SELECT name FROM users WHERE id = ?", id).firstOrNull()?.string("name")

    fun upsertConversation(c: ConversationDto) = db.transaction {
        db.execute(
            "INSERT OR REPLACE INTO conversations (id, kind, title, created_at, avatar_media_id, created_by) VALUES (?, ?, ?, ?, ?, ?)",
            c.id, c.kind, c.title, c.createdAt, c.avatarMediaId, c.createdBy,
        )
        db.execute("DELETE FROM members WHERE conversation_id = ?", c.id)
        c.memberIds.forEach { db.execute("INSERT INTO members (conversation_id, user_id) VALUES (?, ?)", c.id, it) }
        c.receipts.forEach { saveReceipt(c.id, it) }
    }

    // ---------- Recibos (✓✓) ----------

    /** Guarda até onde a pessoa recebeu/leu (nunca volta para trás). */
    fun saveReceipt(conversationId: String, r: ReceiptDto) = db.transaction {
        val old = receipts(conversationId)[r.userId]
        val d = maxOf(old?.deliveredSeq ?: 0, r.deliveredSeq, r.readSeq)
        val rd = maxOf(old?.readSeq ?: 0, r.readSeq)
        db.execute(
            "INSERT OR REPLACE INTO receipts (conversation_id, user_id, delivered_seq, read_seq) VALUES (?, ?, ?, ?)",
            conversationId, r.userId, d, rd,
        )
    }

    fun receipts(conversationId: String): Map<String, ReceiptDto> =
        db.query("SELECT * FROM receipts WHERE conversation_id = ?", conversationId)
            .associate { it.string("user_id") to ReceiptDto(it.string("user_id"), it.long("delivered_seq"), it.long("read_seq")) }

    /**
     * O tique de uma mensagem minha: ✓ (servidor recebeu), ✓✓ cinza (chegou para todos),
     * ✓✓ azul (todos leram). Para as dos outros, devolve o que estiver guardado.
     */
    fun displayStatus(m: LocalMessage, myUserId: String): MessageStatus {
        if (m.status != MessageStatus.SENT || m.seq == null || m.authorId != myUserId) return m.status
        val others = memberIds(m.conversationId).filter { it != myUserId }
        if (others.isEmpty()) return m.status
        val rs = receipts(m.conversationId)
        return when {
            others.all { (rs[it]?.readSeq ?: 0) >= m.seq } -> MessageStatus.READ
            others.all { (rs[it]?.deliveredSeq ?: 0) >= m.seq } -> MessageStatus.DELIVERED
            else -> m.status
        }
    }

    /** Maior seq de mensagens dos outros nesta conversa (para dizer "recebi/li até aqui"). */
    fun lastSeqFromOthers(conversationId: String, myUserId: String): Long =
        db.query(
            "SELECT COALESCE(MAX(seq), 0) AS s FROM messages WHERE conversation_id = ? AND author_id <> ? AND seq IS NOT NULL",
            conversationId, myUserId,
        ).first().long("s")

    /** Conversa apagada (por mim, aqui ou em outro aparelho): some com ela e com as mensagens. */
    fun deleteConversation(id: String) = db.transaction {
        db.execute("DELETE FROM outgoing WHERE message_id IN (SELECT id FROM messages WHERE conversation_id = ?)", id)
        db.execute("DELETE FROM messages WHERE conversation_id = ?", id)
        db.execute("DELETE FROM members WHERE conversation_id = ?", id)
        db.execute("DELETE FROM receipts WHERE conversation_id = ?", id)
        db.execute("DELETE FROM conversations WHERE id = ?", id)
    }

    /** Apaga as mensagens até [seq] (a conversa foi apagada e voltou com mensagem nova). */
    fun clearMessagesUpTo(conversationId: String, seq: Long) {
        db.execute("DELETE FROM messages WHERE conversation_id = ? AND seq IS NOT NULL AND seq <= ?", conversationId, seq)
    }

    fun conversationIds(): List<String> = db.query("SELECT id FROM conversations").map { it.string("id") }

    fun memberIds(conversationId: String) =
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
            val other = if (kind == "direct") members.firstOrNull { it != myUserId }?.let(::user) else null
            val lastMsg = shown?.let(::rowToMessage)
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
                lastBody = lastMsg?.let { if (it.deleted) DELETED_LABEL else preview(it.body, it.attachment) },
                lastAuthorId = lastMsg?.takeIf { !it.isSystem }?.authorId,
                lastAt = lastMsg?.createdAt ?: c.long("created_at"),
                lastStatus = lastMsg?.let { displayStatus(it, myUserId) },
                avatarMediaId = if (kind == "direct") other?.avatarMediaId else c.stringOrNull("avatar_media_id"),
                createdBy = c.stringOrNull("created_by"),
                iAmMember = kind == "direct" || myUserId in members,
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
            kind = r.stringOrNull("kind"),
            replyToId = r.stringOrNull("reply_to_id"),
            deleted = r.bool("deleted"),
            reactions = r.stringOrNull("reactions_json")?.let(::decodeReactions) ?: emptyMap(),
        )
    }

    private val reactionsSer = MapSerializer(String.serializer(), String.serializer())
    private fun decodeReactions(json: String): Map<String, String> =
        runCatching { FalaiJson.decodeFromString(reactionsSer, json) }.getOrDefault(emptyMap())
    private fun encodeReactions(r: Map<String, String>): String? =
        if (r.isEmpty()) null else FalaiJson.encodeToString(reactionsSer, r)

    /** Alguém reagiu ("" = tirou a reação). */
    fun applyReaction(messageId: String, userId: String, emoji: String) = db.transaction {
        val m = messageById(messageId) ?: return@transaction
        if (m.deleted) return@transaction
        val r = m.reactions.toMutableMap()
        if (emoji.isEmpty()) r.remove(userId) else r[userId] = emoji
        db.update("UPDATE messages SET reactions_json = ? WHERE id = ?", encodeReactions(r), messageId)
    }

    /** Apagada para todos: some o texto, o anexo e as reações. */
    fun applyDelete(messageId: String) {
        db.update(
            "UPDATE messages SET body = '', attachment_json = NULL, reactions_json = NULL, deleted = 1 WHERE id = ?",
            messageId,
        )
        db.update("DELETE FROM outgoing WHERE message_id = ?", messageId)
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

    /** Encaminhar: mensagem nova com um arquivo que já está no servidor (não precisa subir). */
    fun insertPendingForward(id: String, conversationId: String, authorId: String, body: String, attachment: AttachmentDto): LocalMessage {
        require(attachment.mediaId.isNotEmpty())
        db.execute(
            """INSERT INTO messages (id, seq, conversation_id, author_id, body, created_at, status, attachment_json)
               VALUES (?, NULL, ?, ?, ?, ?, 'PENDING', ?)""",
            id, conversationId, authorId, body, clock(), encodeAtt(attachment),
        )
        return messageById(id)!!
    }

    /** Quantas mensagens deste aparelho ainda estão na fila (🕓). */
    fun queuedCount(): Int =
        db.query("SELECT COUNT(*) AS n FROM messages WHERE status = 'PENDING'").first().long("n").toInt()

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

    fun insertPending(id: String, conversationId: String, authorId: String, body: String, replyToId: String? = null): LocalMessage {
        val m = LocalMessage(id, null, conversationId, authorId, body, clock(), MessageStatus.PENDING, replyToId = replyToId)
        db.execute(
            "INSERT INTO messages (id, seq, conversation_id, author_id, body, created_at, status, reply_to_id) VALUES (?, NULL, ?, ?, ?, ?, 'PENDING', ?)",
            m.id, m.conversationId, m.authorId, m.body, m.createdAt, replyToId,
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
        // Reação e "apagar para todos" mudam outra mensagem; não aparecem sozinhas.
        when (m.kind) {
            MessageKinds.REACTION -> { m.refId?.let { applyReaction(it, m.authorId, m.body) }; return }
            MessageKinds.DELETE -> { m.refId?.let(::applyDelete); return }
        }
        val status = if (m.authorId == myUserId) MessageStatus.SENT else MessageStatus.RECEIVED
        val body = if (m.deleted) "" else m.body
        val att = if (m.deleted) null else m.attachment
        // Sem "ON CONFLICT ... DO UPDATE": o SQLite do Android 9 é antigo demais para isso.
        db.transaction {
            val changed = db.update(
                """UPDATE messages SET seq = ?, created_at = ?, status = ?, attachment_json = ?, kind = ?, reply_to_id = ?,
                   deleted = ?, reactions_json = ? WHERE id = ?""",
                m.seq, m.createdAt, status.name, encodeAtt(att), m.kind, m.replyToId, m.deleted, encodeReactions(m.reactions), m.id,
            )
            if (changed == 0) {
                db.execute(
                    """INSERT INTO messages (id, seq, conversation_id, author_id, body, created_at, status, attachment_json,
                       kind, reply_to_id, deleted, reactions_json)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    m.id, m.seq, m.conversationId, m.authorId, body, m.createdAt, status.name, encodeAtt(att),
                    m.kind, m.replyToId, m.deleted, encodeReactions(m.reactions),
                )
            }
        }
    }

    /** Atualiza o anexo de uma mensagem minha (ex.: depois de trancar o arquivo). */
    fun updateAttachment(messageId: String, att: AttachmentDto) {
        db.update("UPDATE messages SET attachment_json = ? WHERE id = ?", encodeAtt(att), messageId)
    }

    /** Mensagem trancada que este aparelho não conseguiu abrir: atualiza só a ordem e o estado. */
    fun upsertUnreadable(m: MessageDto, myUserId: String) = db.transaction {
        val status = if (m.authorId == myUserId) MessageStatus.SENT else MessageStatus.RECEIVED
        val changed = db.update("UPDATE messages SET seq = ?, created_at = ?, status = ? WHERE id = ?", m.seq, m.createdAt, status.name, m.id)
        if (changed == 0) {
            db.execute(
                """INSERT INTO messages (id, seq, conversation_id, author_id, body, created_at, status, reply_to_id)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                m.id, m.seq, m.conversationId, m.authorId, LOCKED_LABEL, m.createdAt, status.name, m.replyToId,
            )
        }
    }

    companion object {
        const val DELETED_LABEL = "🚫 Mensagem apagada"
        /** Mensagem trancada que não era para este aparelho (ex.: enviada antes dele entrar). */
        const val LOCKED_LABEL = "🔒 Não deu para abrir esta mensagem neste aparelho."

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

        /**
         * Tipo do arquivo em poucas letras, para mostrar na conversa: "JPG", "MP4", "APK", "PDF"…
         * Usa a extensão do nome; se não tiver, deduz pelo tipo (mime).
         */
        fun typeLabel(name: String, mime: String): String {
            val ext = name.substringAfterLast('.', "").takeIf { name.contains('.') && it.length in 1..5 && it.all(Char::isLetterOrDigit) }
            if (ext != null) return ext.uppercase().let { if (it == "JPEG") "JPG" else it }
            val full = mime.substringAfter('/', "").substringBefore(';').trim().lowercase()
            if (full.isEmpty() || full == "octet-stream") return "ARQUIVO"
            val sub = full.substringAfterLast('.').substringAfterLast('-')
            return when (sub) {
                "jpeg" -> "JPG"
                "quicktime" -> "MOV"
                "mpeg" -> if (mime.startsWith("audio/")) "MP3" else "MPG"
                "plain" -> "TXT"
                "archive" -> "APK"
                else -> sub.uppercase().take(5)
            }
        }

        fun formatDuration(ms: Long): String {
            val s = ms / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
