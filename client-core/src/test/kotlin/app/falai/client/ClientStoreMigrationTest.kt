package app.falai.client

import app.falai.server.JdbcDb
import java.nio.file.Files
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Quem já tem o app do M2 instalado não pode perder as conversas ao atualizar. */
class ClientStoreMigrationTest {
    @Test
    fun `banco do M2 e atualizado sem perder mensagens`() {
        val dir = Files.createTempDirectory("falai-mig").toFile()
        try {
            val db = JdbcDb(File(dir, "app.db").path)
            // Esquema exatamente como o M2 criava (versão 1).
            db.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execute("CREATE TABLE config (key TEXT PRIMARY KEY, value TEXT)")
            db.execute("CREATE TABLE users (id TEXT PRIMARY KEY, name TEXT NOT NULL, is_admin INTEGER NOT NULL)")
            db.execute("CREATE TABLE conversations (id TEXT PRIMARY KEY, kind TEXT NOT NULL, title TEXT, created_at INTEGER NOT NULL)")
            db.execute("CREATE TABLE members (conversation_id TEXT NOT NULL, user_id TEXT NOT NULL, PRIMARY KEY (conversation_id, user_id))")
            db.execute("CREATE TABLE messages (id TEXT PRIMARY KEY, seq INTEGER, conversation_id TEXT NOT NULL, author_id TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL, status TEXT NOT NULL)")
            db.execute("CREATE INDEX messages_by_conv ON messages(conversation_id)")
            db.execute("INSERT INTO meta VALUES ('schema_version', '1')")
            db.execute("INSERT INTO messages VALUES ('m1', 1, 'c1', 'u1', 'oi do M2', 0, 'SENT')")
            db.execute("INSERT INTO messages VALUES ('m2', NULL, 'c1', 'u1', 'na fila', 1, 'PENDING')")

            val store = ClientStore(db)
            assertEquals(listOf("oi do M2", "na fila"), store.messages("c1").map { it.body })
            assertEquals(listOf("na fila"), store.pending().map { it.body })
        } finally {
            dir.deleteRecursively()
        }
    }
}
