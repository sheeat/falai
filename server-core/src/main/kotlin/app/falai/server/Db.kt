package app.falai.server

import java.sql.Connection
import java.sql.DriverManager

/**
 * Acesso mínimo a um banco SQLite.
 *
 * Existe para que toda a lógica SQL (em [Store]) seja a mesma no PC e no Android:
 * no PC/testes usamos [JdbcDb]; no Android, um adaptador para android.database.sqlite.
 *
 * Parâmetros aceitos: String, Long, Int, Boolean (vira 0/1) e null.
 */
interface Db {
    fun execute(sql: String, vararg args: Any?)

    /** Executa um INSERT/UPDATE/DELETE e devolve quantas linhas mudaram. */
    fun update(sql: String, vararg args: Any?): Int

    /** Executa um INSERT e devolve o rowid gerado. */
    fun insert(sql: String, vararg args: Any?): Long

    fun query(sql: String, vararg args: Any?): List<Row>

    fun <T> transaction(block: () -> T): T

    fun close()
}

/** Uma linha de resultado, com acesso por nome de coluna. */
class Row(private val values: Map<String, Any?>) {
    fun string(col: String): String = values[col] as String
    fun stringOrNull(col: String): String? = values[col] as String?
    fun long(col: String): Long = (values[col] as Number).toLong()
    fun longOrNull(col: String): Long? = (values[col] as Number?)?.toLong()
    fun bool(col: String): Boolean = long(col) != 0L
}

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
