package app.falai.server

import app.falai.db.Db
import app.falai.db.Row
import java.sql.Connection
import java.sql.DriverManager

/** Implementação JDBC (sqlite-jdbc). Usada no PC e nos testes automatizados. */
class JdbcDb(path: String) : Db {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
    private val lock = Any()
    private var depth = 0

    init {
        conn.createStatement().use {
            it.execute("PRAGMA foreign_keys = ON")
            it.execute("PRAGMA journal_mode = WAL")
        }
    }

    private fun bind(stmt: java.sql.PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> stmt.setObject(idx, null)
                is String -> stmt.setString(idx, a)
                is Long -> stmt.setLong(idx, a)
                is Int -> stmt.setLong(idx, a.toLong())
                is Boolean -> stmt.setLong(idx, if (a) 1 else 0)
                else -> error("Tipo de parâmetro não suportado: ${a::class}")
            }
        }
    }

    override fun execute(sql: String, vararg args: Any?) {
        synchronized(lock) {
            conn.prepareStatement(sql).use { bind(it, args); it.execute() }
        }
    }

    override fun update(sql: String, vararg args: Any?): Int = synchronized(lock) {
        conn.prepareStatement(sql).use { bind(it, args); it.executeUpdate() }
    }

    override fun insert(sql: String, vararg args: Any?): Long = synchronized(lock) {
        conn.prepareStatement(sql).use { bind(it, args); it.executeUpdate() }
        conn.createStatement().use { s ->
            s.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    override fun query(sql: String, vararg args: Any?): List<Row> = synchronized(lock) {
        conn.prepareStatement(sql).use { stmt ->
            bind(stmt, args)
            stmt.executeQuery().use { rs ->
                val meta = rs.metaData
                val out = ArrayList<Row>()
                while (rs.next()) {
                    val m = HashMap<String, Any?>(meta.columnCount)
                    for (c in 1..meta.columnCount) m[meta.getColumnLabel(c)] = rs.getObject(c)
                    out += Row(m)
                }
                out
            }
        }
    }

    override fun <T> transaction(block: () -> T): T = synchronized(lock) {
        if (depth > 0) {
            depth++
            try { return block() } finally { depth-- }
        }
        conn.autoCommit = false
        depth = 1
        try {
            val r = block()
            conn.commit()
            r
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            depth = 0
            conn.autoCommit = true
        }
    }

    override fun close() = synchronized(lock) { conn.close() }
}
