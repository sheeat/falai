package app.falai.server

import app.falai.protocol.*

import java.io.File

/**
 * Roda o servidor no PC, para desenvolvimento e testes manuais.
 *
 *   ./gradlew :server-core:run --args="--data ./data --port 8443"
 */
fun main(args: Array<String>) {
    fun arg(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    val dataDir = File(arg("--data") ?: "data").apply { mkdirs() }
    val port = arg("--port")?.toInt() ?: 8443
    val host = arg("--host") ?: "127.0.0.1"

    val store = Store(JdbcDb(File(dataDir, "falai.db").path))
    FalaiServer(store, ServerConfig(host = host, port = port, mediaDir = File(dataDir, "media"))).start(wait = true)
}
