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
/** Quanto tempo um convite vale (7 dias: dá tempo de a pessoa ver o WhatsApp e instalar). */
const val INVITE_VALID_MS = 7 * 24 * 3600_000L

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
        if (version < 4) {
            db.transaction {
                // M5: foto de perfil.
                db.execute("ALTER TABLE users ADD COLUMN avatar_media_id TEXT")
                db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '4')")
            }
        }
        if (version < 5) {
            db.transaction {
                // 0.9: reconhecer o aparelho que reinstalou o app, apagar conversa, tirar pessoa da família,
                // nome sugerido no convite.
                db.execute("ALTER TABLE devices ADD COLUMN install_key_hash TEXT")
                db.execute("CREATE INDEX devices_by_install_key ON devices(install_key_hash)")
                db.execute("ALTER TABLE users ADD COLUMN removed_at INTEGER")
                db.execute("ALTER TABLE members ADD COLUMN cleared_seq INTEGER NOT NULL DEFAULT 0")
                db.execute("ALTER TABLE members ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                db.execute("ALTER TABLE invites ADD COLUMN suggested_name TEXT")
                db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '5')")
            }
        }
        if (version < 6) {
            db.transaction {
                // 0.10: mexer no grupo, responder, reagir, apagar para todos, tiques ✓✓.
                db.execute("ALTER TABLE conversations ADD COLUMN avatar_media_id TEXT")
                // left_seq: saiu (ou foi tirado) do grupo nesta mensagem; null = participa.
                db.execute("ALTER TABLE members ADD COLUMN left_seq INTEGER")
                db.execute("ALTER TABLE members ADD COLUMN delivered_seq INTEGER NOT NULL DEFAULT 0")
                db.execute("ALTER TABLE members ADD COLUMN read_seq INTEGER NOT NULL DEFAULT 0")
                db.execute("ALTER TABLE messages ADD COLUMN kind TEXT")
                db.execute("ALTER TABLE messages ADD COLUMN ref_id TEXT")
                db.execute("ALTER TABLE messages ADD COLUMN reply_to_id TEXT")
                db.execute("ALTER TABLE messages ADD COLUMN deleted_at INTEGER")
                db.execute(
                    """CREATE TABLE reactions (
                        message_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        emoji TEXT NOT NULL,
                        PRIMARY KEY (message_id, user_id))"""
                )
                db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '6')")
            }
        }
        if (version < 7) {
            db.transaction {
                // 0.11: mensagens trancadas. Cada aparelho mostra o seu cadeado (chave pública).
                db.execute("ALTER TABLE devices ADD COLUMN public_key TEXT")
                db.execute("ALTER TABLE messages ADD COLUMN sealed_json TEXT")
                db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '7')")
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

    // ---------- Configurações guardadas ----------

    fun getMeta(key: String): String? =
        db.query("SELECT value FROM meta WHERE key = ?", key).firstOrNull()?.string("value")

    fun setMeta(key: String, value: String) {
        db.transaction {
            db.update("DELETE FROM meta WHERE key = ?", key)
            db.execute("INSERT INTO meta (key, value) VALUES (?, ?)", key, value)
        }
    }

    /** O que a página do convite mostra. [status]: "ok", "used", "expired" ou "invalid". */
    data class InvitePreview(val status: String, val inviterName: String?, val forUserName: String?, val suggestedName: String? = null)

    fun invitePreview(code: String): InvitePreview {
        val c = normalizeCode(code)
        if (c.isEmpty() || c.length > 32) return InvitePreview("invalid", null, null)
        val invite = db.query("SELECT * FROM invites WHERE code_hash = ?", sha256(c)).firstOrNull()
            ?: return InvitePreview("invalid", null, null)
        val inviter = invite.stringOrNull("created_by")?.let(::userById)?.name
        val forUser = invite.stringOrNull("for_user_id")?.let(::userById)?.name
        val status = when {
            invite.longOrNull("used_at") != null -> "used"
            invite.long("expires_at") < clock() -> "expired"
            else -> "ok"
        }
        return InvitePreview(status, inviter, forUser, invite.stringOrNull("suggested_name"))
    }

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

    fun createInvite(by: UserDto, forUserId: String?, validForMs: Long = INVITE_VALID_MS, suggestedName: String? = null): Pair<String, Long> {
        if (!by.isAdmin) throw FalaiException("forbidden", "Só o administrador cria convites.")
        if (forUserId != null && userById(forUserId) == null) {
            throw FalaiException("not_found", "Pessoa não encontrada.")
        }
        val code = newInviteCode()
        val expires = clock() + validForMs
        db.execute(
            "INSERT INTO invites (code_hash, created_by, for_user_id, make_admin, expires_at, suggested_name) VALUES (?, ?, ?, 0, ?, ?)",
            sha256(code), by.id, forUserId, expires,
            suggestedName?.trim()?.take(60)?.takeIf { it.isNotEmpty() && forUserId == null },
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
        val installHash = installKeyHash(req.installKey)
        // Aparelho que o servidor já conhece (o app foi instalado de novo): volta para a mesma
        // pessoa, em vez de criar uma conta repetida. O convite é gasto do mesmo jeito.
        if (installHash != null) {
            val known = knownDevice(installHash)
            if (known != null) {
                val token = newToken()
                db.update("UPDATE devices SET token_hash = ?, name = ? WHERE id = ?", sha256(token), deviceName, known.first)
                db.update(
                    "UPDATE invites SET used_at = ?, used_attempt_hash = ?, used_device_id = ? WHERE code_hash = ? AND used_at IS NULL",
                    now, attemptHash, known.first, sha256(code),
                )
                return@transaction PairResponse(known.second.id, known.first, token, known.second.isAdmin, known.second.name, returning = true)
            }
        }
        val existingUserId = invite.stringOrNull("for_user_id")
        val user: UserDto = if (existingUserId != null) {
            userById(existingUserId)?.takeIf { !it.removed }
                ?: throw FalaiException("invalid_code", "A pessoa deste convite não existe mais.")
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
            "INSERT INTO devices (id, user_id, name, token_hash, created_at, install_key_hash) VALUES (?, ?, ?, ?, ?, ?)",
            deviceId, user.id, deviceName, sha256(token), now, installHash,
        )
        val changed = db.update(
            "UPDATE invites SET used_at = ?, used_attempt_hash = ?, used_device_id = ? WHERE code_hash = ? AND used_at IS NULL",
            now, attemptHash, deviceId, sha256(code),
        )
        if (changed != 1) throw FalaiException("invalid_code", "Este convite já foi usado.")

        PairResponse(user.id, deviceId, token, user.isAdmin, user.name)
    }

    /** O app guarda só um resumo (hash) da identidade do aparelho. Curta demais = ignora. */
    private fun installKeyHash(key: String?): String? =
        key?.trim()?.takeIf { it.length in 16..128 }?.let { sha256("install:$it") }

    /** (deviceId, pessoa) do aparelho com essa identidade, se ele ainda vale e a pessoa ainda está na família. */
    private fun knownDevice(installHash: String): Pair<String, UserDto>? {
        val row = db.query(
            """SELECT d.id AS device_id, d.user_id AS user_id FROM devices d JOIN users u ON u.id = d.user_id
               WHERE d.install_key_hash = ? AND d.revoked_at IS NULL AND u.removed_at IS NULL
               ORDER BY d.created_at DESC LIMIT 1""",
            installHash,
        ).firstOrNull() ?: return null
        val user = userById(row.string("user_id")) ?: return null
        return row.string("device_id") to user
    }

    /**
     * O app voltou num aparelho conhecido (reinstalado, ou dados apagados): entrega uma chave nova
     * para o mesmo aparelho, sem convite. Null = aparelho desconhecido.
     */
    fun reconnect(req: ReconnectRequest): PairResponse? = db.transaction {
        val hash = installKeyHash(req.installKey) ?: return@transaction null
        val (deviceId, user) = knownDevice(hash) ?: return@transaction null
        val token = newToken()
        val name = req.deviceName.trim().take(80).ifEmpty { "Aparelho" }
        db.update("UPDATE devices SET token_hash = ?, name = ? WHERE id = ?", sha256(token), name, deviceId)
        PairResponse(user.id, deviceId, token, user.isAdmin, user.name, returning = true)
    }

    fun authenticate(token: String): AuthedDevice? {
        val row = db.query(
            """SELECT d.id AS device_id, u.id AS user_id, u.name AS name, u.is_admin AS is_admin,
                      u.avatar_media_id AS avatar_media_id
               FROM devices d JOIN users u ON u.id = d.user_id
               WHERE d.token_hash = ? AND d.revoked_at IS NULL AND u.removed_at IS NULL""",
            sha256(token),
        ).firstOrNull() ?: return null
        db.update("UPDATE devices SET last_seen_at = ? WHERE id = ?", clock(), row.string("device_id"))
        return AuthedDevice(
            row.string("device_id"),
            UserDto(row.string("user_id"), row.string("name"), row.bool("is_admin"), row.stringOrNull("avatar_media_id")),
        )
    }

    private fun rowToUser(it: Row) = UserDto(
        it.string("id"), it.string("name"), it.bool("is_admin"), it.stringOrNull("avatar_media_id"),
        removed = it.longOrNull("removed_at") != null,
    )

    fun userById(id: String): UserDto? =
        db.query("SELECT id, name, is_admin, avatar_media_id, removed_at FROM users WHERE id = ?", id).firstOrNull()
            ?.let(::rowToUser)?.let { u -> u.copy(devices = deviceKeys(u.id).getOrDefault(u.id, emptyList())) }

    /** Todo mundo, inclusive quem saiu da família (marcado com removed, para o nome aparecer nas mensagens antigas). */
    fun listUsers(): List<UserDto> {
        val keys = deviceKeys(null)
        return db.query("SELECT id, name, is_admin, avatar_media_id, removed_at FROM users ORDER BY name COLLATE NOCASE")
            .map(::rowToUser).map { u -> u.copy(devices = keys.getOrDefault(u.id, emptyList())) }
    }

    /** Cadeados dos aparelhos que funcionam (de uma pessoa, ou de todos com [userId] null). */
    private fun deviceKeys(userId: String?): Map<String, List<DeviceKeyDto>> =
        db.query(
            """SELECT d.id, d.user_id, d.public_key FROM devices d JOIN users u ON u.id = d.user_id
               WHERE d.revoked_at IS NULL AND d.public_key IS NOT NULL AND u.removed_at IS NULL
               ${if (userId != null) "AND d.user_id = ?" else ""} ORDER BY d.created_at""",
            *listOfNotNull(userId).toTypedArray(),
        ).groupBy({ it.string("user_id") }, { DeviceKeyDto(it.string("id"), it.string("public_key")) })

    /** O aparelho mostra o seu cadeado. Devolve true se mudou (aí todos precisam saber). */
    fun setDeviceKey(deviceId: String, publicKey: String): Boolean {
        val raw = runCatching { java.util.Base64.getDecoder().decode(publicKey) }.getOrNull()
        if (raw == null || raw.size != 65 || raw[0] != 4.toByte()) throw FalaiException("invalid", "Cadeado inválido.")
        val canonical = java.util.Base64.getEncoder().encodeToString(raw)
        val cur = db.query("SELECT public_key FROM devices WHERE id = ?", deviceId).firstOrNull()?.stringOrNull("public_key")
        if (cur == canonical) return false
        db.update("UPDATE devices SET public_key = ? WHERE id = ?", canonical, deviceId)
        return true
    }

    /** Aparelhos que precisam conseguir abrir uma mensagem trancada desta conversa. */
    fun requiredDeviceIds(conversationId: String): Set<String> =
        db.query(
            """SELECT d.id FROM devices d
               JOIN members mb ON mb.user_id = d.user_id AND mb.conversation_id = ? AND mb.left_seq IS NULL
               JOIN users u ON u.id = d.user_id
               WHERE d.revoked_at IS NULL AND d.public_key IS NOT NULL AND u.removed_at IS NULL""",
            conversationId,
        ).map { it.string("id") }.toSet()

    /**
     * Só o administrador: tira a pessoa da família. Os aparelhos dela param de funcionar na hora;
     * as mensagens antigas continuam nas conversas dos outros.
     */
    fun removeUser(by: UserDto, userId: String): UserDto = db.transaction {
        if (!by.isAdmin) throw FalaiException("forbidden", "Só o administrador tira alguém da família.")
        val u = userById(userId) ?: throw FalaiException("not_found", "Pessoa não encontrada.")
        if (u.id == by.id) throw FalaiException("invalid", "Você não pode tirar a si mesmo.")
        if (u.isAdmin) throw FalaiException("invalid", "Não dá para tirar o administrador.")
        val now = clock()
        db.update("UPDATE users SET removed_at = ? WHERE id = ? AND removed_at IS NULL", now, userId)
        db.update("UPDATE devices SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL", now, userId)
        // Convites para ligar aparelhos a essa pessoa deixam de valer.
        db.update("UPDATE invites SET expires_at = ? WHERE for_user_id = ? AND used_at IS NULL", 0L, userId)
        userById(userId)!!
    }

    /** Muda nome e/ou foto. [avatarMediaId] "" = tirar a foto. */
    fun updateProfile(userId: String, name: String?, avatarMediaId: String?): UserDto = db.transaction {
        if (name != null) {
            val n = name.trim().take(60)
            if (n.isEmpty()) throw FalaiException("invalid", "O nome não pode ficar vazio.")
            db.update("UPDATE users SET name = ? WHERE id = ?", n, userId)
        }
        if (avatarMediaId != null) {
            if (avatarMediaId.isEmpty()) {
                db.update("UPDATE users SET avatar_media_id = NULL WHERE id = ?", userId)
            } else {
                val m = media(avatarMediaId) ?: throw FalaiException("invalid", "Foto não encontrada.")
                if (!m.complete) throw FalaiException("invalid", "A foto ainda não terminou de subir.")
                if (m.uploaderId != userId) throw FalaiException("forbidden", "Foto de outra pessoa.")
                if (!m.mime.startsWith("image/")) throw FalaiException("invalid", "A foto de perfil precisa ser uma imagem.")
                db.update("UPDATE users SET avatar_media_id = ? WHERE id = ?", avatarMediaId, userId)
            }
        }
        userById(userId) ?: throw FalaiException("not_found", "Pessoa não encontrada.")
    }

    // ---------- Falaí no computador ----------

    /** Liga um aparelho novo (o navegador) à pessoa, sem convite: ela aprovou pelo celular. */
    /**
     * Só o administrador: traz de volta quem foi tirado da família. Os aparelhos desligados na
     * hora da saída voltam a funcionar (a pessoa só precisa abrir o app de novo).
     */
    fun restoreUser(by: UserDto, userId: String): UserDto = db.transaction {
        if (!by.isAdmin) throw FalaiException("forbidden", "Só o administrador traz alguém de volta.")
        val removedAt = db.query("SELECT removed_at FROM users WHERE id = ?", userId).firstOrNull()
            ?: throw FalaiException("not_found", "Pessoa não encontrada.")
        val at = removedAt.longOrNull("removed_at") ?: return@transaction userById(userId)!!
        db.update("UPDATE users SET removed_at = NULL WHERE id = ?", userId)
        db.update("UPDATE devices SET revoked_at = NULL WHERE user_id = ? AND revoked_at = ?", userId, at)
        userById(userId)!!
    }

    fun createLinkedDevice(userId: String, name: String): Pair<String, String> {
        val u = userById(userId) ?: throw FalaiException("not_found", "Pessoa não encontrada.")
        if (u.removed) throw FalaiException("forbidden", "Pessoa fora da família.")
        val token = newToken()
        val deviceId = newId()
        db.execute(
            "INSERT INTO devices (id, user_id, name, token_hash, created_at) VALUES (?, ?, ?, ?, ?)",
            deviceId, userId, name.take(60), sha256(token), clock(),
        )
        return deviceId to token
    }

    /** Desliga um aparelho: o token dele para de funcionar na hora. */
    fun revokeDevice(deviceId: String) {
        db.update("UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", clock(), deviceId)
    }

    // ---------- Conversas ----------

    fun createConversation(by: UserDto, kind: String, title: String?, otherMemberIds: List<String>): ConversationDto =
        db.transaction {
            if (kind != "direct" && kind != "group") throw FalaiException("invalid", "Tipo de conversa inválido.")
            val members = (otherMemberIds + by.id).distinct()
            members.forEach {
                val u = userById(it) ?: throw FalaiException("not_found", "Pessoa não encontrada: $it")
                if (u.removed) throw FalaiException("invalid", "${u.name} não faz mais parte da família.")
            }
            if (kind == "direct") {
                if (members.size != 2) throw FalaiException("invalid", "Conversa direta tem exatamente duas pessoas.")
                // Conversa direta entre as mesmas duas pessoas é única: devolve a que já existe.
                findDirect(members[0], members[1])?.let {
                    // Quem tinha apagado a conversa volta a vê-la (vazia, se apagou tudo).
                    db.update("UPDATE members SET hidden = 0 WHERE conversation_id = ? AND user_id = ?", it.id, by.id)
                    return@transaction conversationFor(it.id, by.id)!!
                }
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
            avatarMediaId = c.stringOrNull("avatar_media_id"),
            createdBy = c.stringOrNull("created_by"),
            receipts = receipts(id),
        )
    }

    /** Quem participa hoje (quem saiu do grupo fica de fora). */
    fun memberIds(conversationId: String): List<String> =
        db.query("SELECT user_id FROM members WHERE conversation_id = ? AND left_seq IS NULL ORDER BY user_id", conversationId)
            .map { it.string("user_id") }

    fun receipts(conversationId: String): List<ReceiptDto> =
        db.query(
            "SELECT user_id, delivered_seq, read_seq FROM members WHERE conversation_id = ? AND left_seq IS NULL ORDER BY user_id",
            conversationId,
        ).map { ReceiptDto(it.string("user_id"), it.long("delivered_seq"), it.long("read_seq")) }

    /** Tem (ou teve) a conversa na lista: participa ou já saiu do grupo. */
    private fun hasMemberRow(conversationId: String, userId: String): Boolean =
        db.query("SELECT 1 AS x FROM members WHERE conversation_id = ? AND user_id = ?", conversationId, userId).isNotEmpty()

    private fun lastSeqOf(conversationId: String): Long =
        db.query("SELECT COALESCE(MAX(seq), 0) AS s FROM messages WHERE conversation_id = ?", conversationId).first().long("s")

    /** A conversa como [userId] vê: com o ponto até onde ela apagou (se apagou). */
    fun conversationFor(id: String, userId: String): ConversationDto? {
        val c = conversation(id) ?: return null
        val cleared = db.query("SELECT cleared_seq FROM members WHERE conversation_id = ? AND user_id = ?", id, userId)
            .firstOrNull()?.long("cleared_seq") ?: 0L
        return if (cleared > 0) c.copy(clearedSeq = cleared) else c
    }

    /** Conversas que aparecem na lista da pessoa (as apagadas ficam de fora até chegar mensagem nova). */
    fun conversationsOf(userId: String): List<ConversationDto> =
        db.query(
            "SELECT conversation_id FROM members WHERE user_id = ? AND hidden = 0 ORDER BY conversation_id", userId,
        ).mapNotNull { conversationFor(it.string("conversation_id"), userId) }

    /**
     * "Apagar conversa" (só para mim, em todos os meus aparelhos): some da lista e as mensagens até
     * agora não voltam mais. Se chegar mensagem nova, a conversa reaparece só com as novas.
     */
    fun hideConversation(userId: String, conversationId: String): Long = db.transaction {
        if (!hasMemberRow(conversationId, userId)) throw FalaiException("forbidden", "Você não participa desta conversa.")
        val last = lastSeqOf(conversationId)
        db.update(
            "UPDATE members SET hidden = 1, cleared_seq = ? WHERE conversation_id = ? AND user_id = ?",
            last, conversationId, userId,
        )
        last
    }

    /** Mensagem nova numa conversa: quem tinha apagado volta a vê-la. Devolve quem "reapareceu". */
    fun unhideForNewMessage(conversationId: String): List<String> = db.transaction {
        val ids = db.query("SELECT user_id FROM members WHERE conversation_id = ? AND hidden = 1 AND left_seq IS NULL", conversationId)
            .map { it.string("user_id") }
        if (ids.isNotEmpty()) db.update("UPDATE members SET hidden = 0 WHERE conversation_id = ? AND left_seq IS NULL", conversationId)
        ids
    }

    fun isMember(conversationId: String, userId: String): Boolean =
        db.query(
            "SELECT 1 AS x FROM members WHERE conversation_id = ? AND user_id = ? AND left_seq IS NULL", conversationId, userId,
        ).isNotEmpty()

    // ---------- Mensagens ----------

    fun storeMessage(device: AuthedDevice, frame: SendFrame): StoredMessage = db.transaction {
        val id = frame.id.trim()
        if (id.isEmpty() || id.length > 64) throw FalaiException("invalid", "Id de mensagem inválido.")
        if (frame.body.isEmpty() && frame.attachment == null && frame.sealed == null) throw FalaiException("invalid", "Mensagem vazia.")
        frame.sealed?.let { s ->
            if (frame.body.isNotEmpty()) throw FalaiException("invalid", "Mensagem trancada não leva texto aberto.")
            if (s.ct.length > 400_000 || s.keys.size > 500 || s.keys.values.any { it.length > 200 } || s.senderKey.length > 100) {
                throw FalaiException("too_large", "Mensagem grande demais.")
            }
        }
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
        frame.sealed?.let { s ->
            // Todo aparelho da conversa precisa conseguir abrir; se faltou algum (aparelho novo),
            // o celular busca os cadeados de novo e tranca outra vez.
            val missing = requiredDeviceIds(frame.conversationId) - s.keys.keys
            if (missing.isNotEmpty()) throw FalaiException("keys_changed", "Tem aparelho novo na conversa. Tentando de novo…")
        }
        val att = frame.attachment?.let { a ->
            val m = media(a.mediaId) ?: throw FalaiException("invalid", "Anexo não encontrado.")
            if (!m.complete) throw FalaiException("invalid", "O anexo ainda não terminou de subir.")
            // Encaminhar: pode usar um arquivo que a pessoa já consegue ver (sem subir de novo).
            if (m.uploaderId != device.user.id && !canDownload(m.id, device.user.id)) {
                throw FalaiException("forbidden", "Anexo de outra pessoa.")
            }
            if (!a.kind.matches(Regex("image|video|audio|file"))) throw FalaiException("invalid", "Tipo de anexo inválido.")
            // Os dados confiáveis vêm do servidor; do cliente só o tipo, a duração e a miniatura.
            a.copy(mime = m.mime, name = m.name, size = m.size, sha256 = m.sha256, thumb = sanitizeThumb(a.thumb))
        }
        // Resposta: só vale para uma mensagem da mesma conversa.
        val replyTo = frame.replyToId?.takeIf { r ->
            db.query("SELECT 1 AS x FROM messages WHERE id = ? AND conversation_id = ? AND kind IS NULL", r, frame.conversationId).isNotEmpty()
        }
        val now = clock()
        val seq = db.insert(
            """INSERT INTO messages (id, conversation_id, author_id, author_device_id, body, created_at, attachment_json, media_id, reply_to_id, sealed_json)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, frame.conversationId, device.user.id, device.deviceId, frame.body, now,
            att?.let { FalaiJson.encodeToString(AttachmentDto.serializer(), it) }, att?.mediaId, replyTo,
            frame.sealed?.let { FalaiJson.encodeToString(SealedDto.serializer(), it) },
        )
        StoredMessage(
            MessageDto(seq, id, frame.conversationId, device.user.id, device.deviceId, frame.body, now, att, replyToId = replyTo, sealed = frame.sealed),
            isNew = true,
        )
    }

    /** Guarda uma mensagem "especial" (aviso do grupo, reação, apagada). */
    private fun insertEvent(
        id: String, conversationId: String, device: AuthedDevice, kind: String, body: String, refId: String? = null,
    ): MessageDto {
        val now = clock()
        val seq = db.insert(
            """INSERT INTO messages (id, conversation_id, author_id, author_device_id, body, created_at, kind, ref_id)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            id, conversationId, device.user.id, device.deviceId, body, now, kind, refId,
        )
        return MessageDto(seq, id, conversationId, device.user.id, device.deviceId, body, now, kind = kind, refId = refId)
    }

    /** Reação ("" tira). Fica guardada como mensagem (para os aparelhos desligados receberem depois). */
    fun react(device: AuthedDevice, f: ReactFrame): StoredMessage = db.transaction {
        val id = f.id.trim()
        if (id.isEmpty() || id.length > 64) throw FalaiException("invalid", "Id inválido.")
        messageById(id)?.let { existing ->
            if (existing.authorId != device.user.id) throw FalaiException("conflict", "Id de mensagem já usado.")
            return@transaction StoredMessage(existing, isNew = false)
        }
        val emoji = f.emoji.trim()
        if (emoji.length > 16) throw FalaiException("invalid", "Reação inválida.")
        if (!isMember(f.conversationId, device.user.id)) throw FalaiException("forbidden", "Você não participa desta conversa.")
        val target = messageById(f.messageId)
        if (target == null || target.conversationId != f.conversationId || target.kind != null) {
            throw FalaiException("not_found", "Mensagem não encontrada.")
        }
        if (target.deleted) throw FalaiException("invalid", "Essa mensagem foi apagada.")
        if (emoji.isEmpty()) {
            db.update("DELETE FROM reactions WHERE message_id = ? AND user_id = ?", target.id, device.user.id)
        } else {
            db.execute("INSERT OR REPLACE INTO reactions (message_id, user_id, emoji) VALUES (?, ?, ?)", target.id, device.user.id, emoji)
        }
        StoredMessage(insertEvent(id, f.conversationId, device, MessageKinds.REACTION, emoji, target.id), isNew = true)
    }

    /** Apagar para todos: só quem escreveu. O texto e o anexo somem do servidor. */
    fun deleteForEveryone(device: AuthedDevice, f: DeleteMessageFrame): StoredMessage = db.transaction {
        val id = f.id.trim()
        if (id.isEmpty() || id.length > 64) throw FalaiException("invalid", "Id inválido.")
        messageById(id)?.let { existing ->
            if (existing.authorId != device.user.id) throw FalaiException("conflict", "Id de mensagem já usado.")
            return@transaction StoredMessage(existing, isNew = false)
        }
        val target = messageById(f.messageId)
        if (target == null || target.conversationId != f.conversationId || target.kind != null) {
            throw FalaiException("not_found", "Mensagem não encontrada.")
        }
        if (target.authorId != device.user.id) throw FalaiException("forbidden", "Só quem escreveu pode apagar para todos.")
        if (!isMember(f.conversationId, device.user.id)) throw FalaiException("forbidden", "Você não participa desta conversa.")
        db.update(
            "UPDATE messages SET body = '', attachment_json = NULL, media_id = NULL, sealed_json = NULL, deleted_at = ? WHERE id = ?",
            clock(), target.id,
        )
        db.update("DELETE FROM reactions WHERE message_id = ?", target.id)
        StoredMessage(insertEvent(id, f.conversationId, device, MessageKinds.DELETE, "", target.id), isNew = true)
    }

    /** Resultado de mexer no grupo: a conversa, o aviso no grupo e quem precisa saber. */
    data class GroupChange(val conversation: ConversationDto, val notices: List<MessageDto>, val notify: Set<String>)

    private fun nameOf(userId: String) = userById(userId)?.name ?: "Alguém"

    /** Mexer no grupo: nome, foto, pôr e tirar pessoas (com um aviso no grupo para cada coisa). */
    fun updateGroup(device: AuthedDevice, f: UpdateGroupFrame): GroupChange = db.transaction {
        val c = conversation(f.conversationId) ?: throw FalaiException("not_found", "Conversa não encontrada.")
        if (c.kind != "group") throw FalaiException("invalid", "Só dá para mudar grupos.")
        val me = device.user
        if (!isMember(c.id, me.id)) throw FalaiException("forbidden", "Você não participa deste grupo.")
        val before = c.memberIds.toSet()
        val notices = mutableListOf<MessageDto>()
        val removedNow = mutableSetOf<String>()
        fun notice(text: String) { notices += insertEvent(newId(), c.id, device, MessageKinds.SYSTEM, text) }

        f.title?.trim()?.take(80)?.let { t ->
            if (t.isEmpty()) throw FalaiException("invalid", "O grupo precisa de um nome.")
            if (t != c.title) {
                db.update("UPDATE conversations SET title = ? WHERE id = ?", t, c.id)
                notice("${me.name} mudou o nome do grupo para \"$t\"")
            }
        }
        f.avatarMediaId?.let { mid ->
            if (mid.isEmpty()) {
                if (c.avatarMediaId != null) {
                    db.update("UPDATE conversations SET avatar_media_id = NULL WHERE id = ?", c.id)
                    notice("${me.name} tirou a foto do grupo")
                }
            } else {
                val m = media(mid) ?: throw FalaiException("invalid", "Foto não encontrada.")
                if (!m.complete || m.uploaderId != me.id) throw FalaiException("invalid", "Foto inválida.")
                db.update("UPDATE conversations SET avatar_media_id = ? WHERE id = ?", mid, c.id)
                notice("${me.name} mudou a foto do grupo")
            }
        }
        for (uid in f.addMemberIds.distinct()) {
            if (uid in before) continue
            val u = userById(uid) ?: throw FalaiException("not_found", "Pessoa não encontrada.")
            if (u.removed) throw FalaiException("invalid", "${u.name} não faz mais parte da família.")
            // Quem entra vê o grupo a partir de agora (como nos outros mensageiros).
            val from = lastSeqOf(c.id)
            if (hasMemberRow(c.id, uid)) {
                db.update("UPDATE members SET left_seq = NULL, hidden = 0 WHERE conversation_id = ? AND user_id = ?", c.id, uid)
            } else {
                db.execute("INSERT INTO members (conversation_id, user_id, cleared_seq) VALUES (?, ?, ?)", c.id, uid, from)
            }
            notice("${me.name} adicionou ${u.name}")
        }
        val canRemoveOthers = me.isAdmin || c.createdBy == me.id
        for (uid in f.removeMemberIds.distinct()) {
            if (uid !in before || uid == me.id) continue
            if (!canRemoveOthers) throw FalaiException("forbidden", "Só quem criou o grupo (ou o administrador) tira pessoas.")
            notice("${me.name} tirou ${nameOf(uid)} do grupo")
            // A pessoa ainda recebe o aviso de que saiu, e nada depois disso.
            db.update("UPDATE members SET left_seq = ? WHERE conversation_id = ? AND user_id = ?", notices.last().seq, c.id, uid)
            removedNow += uid
        }
        val after = conversation(c.id)!!
        GroupChange(after, notices, before + after.memberIds + removedNow)
    }

    /** Sair do grupo (o histórico fica no aparelho, mas não chega mais nada). */
    fun leaveGroup(device: AuthedDevice, conversationId: String): GroupChange = db.transaction {
        val c = conversation(conversationId) ?: throw FalaiException("not_found", "Conversa não encontrada.")
        if (c.kind != "group") throw FalaiException("invalid", "Só dá para sair de grupos.")
        if (!isMember(c.id, device.user.id)) throw FalaiException("invalid", "Você já saiu deste grupo.")
        val n = insertEvent(newId(), c.id, device, MessageKinds.SYSTEM, "${device.user.name} saiu do grupo")
        db.update("UPDATE members SET left_seq = ? WHERE conversation_id = ? AND user_id = ?", n.seq, c.id, device.user.id)
        GroupChange(conversation(c.id)!!, listOf(n), c.memberIds.toSet())
    }

    /** "Recebi/li até aqui". Devolve o novo estado se mudou (para avisar os outros), ou null. */
    fun updateReceipt(userId: String, conversationId: String, delivered: Long, read: Long): ReceiptDto? = db.transaction {
        val row = db.query(
            "SELECT delivered_seq, read_seq FROM members WHERE conversation_id = ? AND user_id = ? AND left_seq IS NULL",
            conversationId, userId,
        ).firstOrNull() ?: return@transaction null
        val last = lastSeqOf(conversationId)
        val newRead = maxOf(row.long("read_seq"), read.coerceIn(0, last))
        val newDelivered = maxOf(row.long("delivered_seq"), delivered.coerceIn(0, last), newRead)
        if (newRead == row.long("read_seq") && newDelivered == row.long("delivered_seq")) return@transaction null
        db.update(
            "UPDATE members SET delivered_seq = ?, read_seq = ? WHERE conversation_id = ? AND user_id = ?",
            newDelivered, newRead, conversationId, userId,
        )
        ReceiptDto(userId, newDelivered, newRead)
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
        kind = r.stringOrNull("kind"),
        refId = r.stringOrNull("ref_id"),
        replyToId = r.stringOrNull("reply_to_id"),
        deleted = r.longOrNull("deleted_at") != null,
        sealed = r.stringOrNull("sealed_json")?.let { FalaiJson.decodeFromString(SealedDto.serializer(), it) },
    )

    /** Junta as reações às mensagens (uma consulta só para a lista toda). */
    private fun withReactions(list: List<MessageDto>): List<MessageDto> {
        val ids = list.filter { it.kind == null && !it.deleted }.map { it.id }
        if (ids.isEmpty()) return list
        val byMsg = HashMap<String, MutableMap<String, String>>()
        for (chunk in ids.chunked(400)) {
            db.query(
                "SELECT message_id, user_id, emoji FROM reactions WHERE message_id IN (${chunk.joinToString(",") { "?" }})",
                *chunk.toTypedArray(),
            ).forEach { byMsg.getOrPut(it.string("message_id")) { linkedMapOf() }[it.string("user_id")] = it.string("emoji") }
        }
        if (byMsg.isEmpty()) return list
        return list.map { m -> byMsg[m.id]?.let { m.copy(reactions = it) } ?: m }
    }

    fun messageById(id: String): MessageDto? =
        db.query("SELECT * FROM messages WHERE id = ?", id).firstOrNull()?.let { withReactions(listOf(rowToMessage(it))).first() }

    /** Mensagens das conversas da pessoa com seq maior que [afterSeq], em ordem. */
    fun messagesSince(userId: String, afterSeq: Long, limit: Int): List<MessageDto> =
        db.query(
            """SELECT m.* FROM messages m
               JOIN members mb ON mb.conversation_id = m.conversation_id AND mb.user_id = ?
               WHERE m.seq > ? AND m.seq > mb.cleared_seq AND (mb.left_seq IS NULL OR m.seq <= mb.left_seq)
               ORDER BY m.seq
               LIMIT ?""",
            userId, afterSeq, limit,
        ).map(::rowToMessage).let(::withReactions)

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
    fun isAvatar(mediaId: String): Boolean =
        db.query("SELECT 1 AS x FROM users WHERE avatar_media_id = ? LIMIT 1", mediaId).isNotEmpty() ||
            db.query("SELECT 1 AS x FROM conversations WHERE avatar_media_id = ? LIMIT 1", mediaId).isNotEmpty()

    fun canDownload(mediaId: String, userId: String): Boolean {
        val m = media(mediaId) ?: return false
        if (m.uploaderId == userId) return true
        // Foto de perfil: todo mundo da família pode ver.
        if (isAvatar(mediaId)) return true
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
