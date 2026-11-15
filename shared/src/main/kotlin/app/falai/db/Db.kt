package app.falai.db

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
