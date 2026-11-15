package app.falai.client

import app.falai.db.Db
import app.falai.db.Row
import app.falai.protocol.ConversationDto
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
                lastBody = shown?.string("body"),
                lastAuthorId = shown?.string("author_id"),
                lastAt = shown?.long("created_at") ?: c.long("created_at"),
                lastStatus = shown?.let { MessageStatus.valueOf(it.string("status")) },
            )
        }.sortedByDescending { it.lastAt ?: 0 }
    }

    // ---------- Mensagens ----------

    private fun rowToMessage(r: Row) = LocalMessage(
        id = r.string("id"),
        seq = r.longOrNull("seq"),
        conversationId = r.string("conversation_id"),
        authorId = r.string("author_id"),
        body = r.string("body"),
        createdAt = r.long("created_at"),
        status = MessageStatus.valueOf(r.string("status")),
    )

    /** Mensagens da conversa em ordem: confirmadas pelo servidor primeiro, depois as da fila. */
    fun messages(conversationId: String): List<LocalMessage> =
        db.query(
            "SELECT * FROM messages WHERE conversation_id = ? ORDER BY (seq IS NULL), seq, rowid",
            conversationId,
        ).map(::rowToMessage)

    fun pending(): List<LocalMessage> =
        db.query("SELECT * FROM messages WHERE status = 'PENDING' ORDER BY rowid").map(::rowToMessage)

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
                "UPDATE messages SET seq = ?, created_at = ?, status = ? WHERE id = ?",
                m.seq, m.createdAt, status.name, m.id,
            )
            if (changed == 0) {
                db.execute(
                    """INSERT INTO messages (id, seq, conversation_id, author_id, body, created_at, status)
                       VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    m.id, m.seq, m.conversationId, m.authorId, m.body, m.createdAt, status.name,
                )
            }
        }
    }
}
