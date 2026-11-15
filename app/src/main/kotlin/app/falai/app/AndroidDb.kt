package app.falai.app

import app.falai.db.*

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase



/** Implementação de [Db] com o SQLite do próprio Android. */
class AndroidDb(path: String) : Db {
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(path, null).apply {
        setForeignKeyConstraintsEnabled(true)
        enableWriteAheadLogging()
    }
    private val lock = Any()

    private fun bindArgs(args: Array<out Any?>): Array<Any?> = args.map {
        when (it) {
            null, is String, is Long -> it
            is Int -> it.toLong()
            is Boolean -> if (it) 1L else 0L
            else -> error("Tipo de parâmetro não suportado: ${it::class}")
        }
    }.toTypedArray()

    override fun execute(sql: String, vararg args: Any?) = synchronized(lock) {
        db.execSQL(sql, bindArgs(args))
    }

    override fun update(sql: String, vararg args: Any?): Int = synchronized(lock) {
        db.compileStatement(sql).use { st ->
            bindArgs(args).forEachIndexed { i, a -> bind(st, i + 1, a) }
            st.executeUpdateDelete()
        }
    }

    override fun insert(sql: String, vararg args: Any?): Long = synchronized(lock) {
        db.compileStatement(sql).use { st ->
            bindArgs(args).forEachIndexed { i, a -> bind(st, i + 1, a) }
            st.executeInsert()
        }
    }

    private fun bind(st: android.database.sqlite.SQLiteStatement, idx: Int, a: Any?) {
        when (a) {
            null -> st.bindNull(idx)
            is String -> st.bindString(idx, a)
            is Long -> st.bindLong(idx, a)
        }
    }

    override fun query(sql: String, vararg args: Any?): List<Row> = synchronized(lock) {
        // rawQuery só aceita String: números viram texto, o SQLite converte na comparação
        // por afinidade da coluna (INTEGER), então o resultado é o mesmo.
        val strArgs = bindArgs(args).map { it?.toString() }.toTypedArray()
        db.rawQuery(sql, strArgs).use { c ->
            val out = ArrayList<Row>(c.count)
            while (c.moveToNext()) {
                val m = HashMap<String, Any?>(c.columnCount)
                for (i in 0 until c.columnCount) {
                    m[c.getColumnName(i)] = when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        else -> c.getString(i)
                    }
                }
                out += Row(m)
            }
            out
        }
    }

    override fun <T> transaction(block: () -> T): T = synchronized(lock) {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            r
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = synchronized(lock) { db.close() }
}
