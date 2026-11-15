package app.falai.server

import app.falai.protocol.*
import app.falai.db.*

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * No Android, rawQuery só aceita parâmetros String. Este teste imita isso
 * (converte todo parâmetro de consulta para texto) e confere que o [Store]
 * continua dando o mesmo resultado — cobre o AndroidDb, que não roda aqui.
 */
class StringArgsDbTest {

    private class StringArgsDb(private val inner: Db) : Db by inner {
        override fun query(sql: String, vararg args: Any?): List<Row> =
            inner.query(sql, *args.map { a ->
                when (a) {
                    null -> null
                    is Boolean -> if (a) "1" else "0"
                    else -> a.toString()
                }
            }.toTypedArray())
    }

    @Test
    fun `store funciona com parametros de consulta em texto`() {
        val dir = Files.createTempDirectory("falai-str").toFile()
        try {
            val store = Store(StringArgsDb(JdbcDb(File(dir, "s.db").path)))
            val (code, _) = store.bootstrapInviteIfEmpty()!!
            val admin = store.pair(PairRequest(code, "Celular", "Chefe"))
            val adminDev = store.authenticate(admin.token)!!

            val (code2, _) = store.createInvite(adminDev.user, null)
            val ana = store.pair(PairRequest(code2, "Celular", "Ana"))
            val conv = store.createConversation(adminDev.user, "direct", null, listOf(ana.userId))

            repeat(12) { store.storeMessage(adminDev, SendFrame(UUID.randomUUID().toString(), conv.id, "m$it")) }

            val all = store.messagesSince(ana.userId, 0, 5)
            assertEquals(listOf("m0", "m1", "m2", "m3", "m4"), all.map { it.body })
            val after = store.messagesSince(ana.userId, all.last().seq, 100)
            assertEquals(7, after.size)
            assertEquals(12L, store.lastSeqFor(ana.userId))
            assertEquals(true, store.isMember(conv.id, ana.userId))
            assertEquals(conv.id, store.createConversation(adminDev.user, "direct", null, listOf(ana.userId)).id)
        } finally {
            dir.deleteRecursively()
        }
    }
}
