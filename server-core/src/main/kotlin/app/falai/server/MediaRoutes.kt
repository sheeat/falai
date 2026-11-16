package app.falai.server

import app.falai.protocol.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Envio e download de arquivos. O arquivo sobe em pedaços de até 8 MB
 * (o plano grátis da Cloudflare limita cada envio a 100 MB) e pode ser retomado
 * de onde parou. No fim, o servidor confere o SHA-256: o arquivo guardado é
 * idêntico ao original, byte a byte.
 */
class MediaFiles(private val dir: File, private val store: Store, private val log: (String) -> Unit) {

    init { dir.mkdirs() }

    private val locks = ConcurrentHashMap<String, Any>()
    private fun lockFor(id: String) = locks.computeIfAbsent(id) { Any() }

    fun finalFile(id: String) = File(dir, id)
    private fun partFile(id: String) = File(dir, "$id.part")

    /** Grava um pedaço em [offset]. Devolve o novo estado. */
    fun writeChunk(m: Store.MediaRow, offset: Long, bytes: ByteArray): MediaStatus = synchronized(lockFor(m.id)) {
        val current = store.media(m.id)!!
        if (current.complete) return current.status()
        if (offset != current.received) {
            throw FalaiException("wrong_offset", "Pedaço fora de ordem: esperado ${current.received}.")
        }
        if (offset + bytes.size > current.size) throw FalaiException("too_large", "Mais bytes que o tamanho combinado.")
        RandomAccessFile(partFile(m.id), "rw").use { f ->
            f.setLength(offset) // descarta restos de um envio interrompido
            f.seek(offset)
            f.write(bytes)
        }
        val received = offset + bytes.size
        if (received < current.size) {
            store.setMediaProgress(m.id, received, complete = false)
            return MediaStatus(m.id, current.size, received, false)
        }
        // Chegou tudo: confere se é idêntico ao original.
        val hash = sha256Hex(partFile(m.id))
        if (hash != current.sha256) {
            partFile(m.id).delete()
            store.setMediaProgress(m.id, 0, complete = false)
            throw FalaiException("hash_mismatch", "O arquivo chegou diferente do original. Envie de novo.")
        }
        if (!partFile(m.id).renameTo(finalFile(m.id))) throw FalaiException("io", "Não consegui salvar o arquivo.")
        store.setMediaProgress(m.id, received, complete = true)
        log("Arquivo recebido: ${m.id.take(8)} (${current.size} bytes)")
        MediaStatus(m.id, current.size, received, true)
    }

    companion object {
        fun sha256Hex(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

private suspend fun ApplicationCall.respondJson(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(text, ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(e: FalaiException) {
    val status = when (e.code) {
        "unauthorized" -> HttpStatusCode.Unauthorized
        "forbidden" -> HttpStatusCode.Forbidden
        "not_found" -> HttpStatusCode.NotFound
        "wrong_offset" -> HttpStatusCode.Conflict
        "too_large" -> HttpStatusCode.PayloadTooLarge
        "hash_mismatch" -> HttpStatusCode.UnprocessableEntity
        else -> HttpStatusCode.BadRequest
    }
    respondJson(FalaiJson.encodeToString(ErrorResponse.serializer(), ErrorResponse(e.code, e.message ?: "")), status)
}

private fun ApplicationCall.authed(store: Store): AuthedDevice {
    val token = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
    return token?.takeIf { it.isNotEmpty() }?.let(store::authenticate)
        ?: throw FalaiException("unauthorized", "Aparelho não autorizado.")
}

private fun mediaOwnedBy(store: Store, id: String, device: AuthedDevice): Store.MediaRow {
    val m = store.media(id) ?: throw FalaiException("not_found", "Arquivo não encontrado.")
    if (m.uploaderId != device.user.id) throw FalaiException("forbidden", "Este envio é de outra pessoa.")
    return m
}

private suspend fun ApplicationCall.guard(log: (String) -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: FalaiException) {
        if (e.code != "wrong_offset") log("Arquivo recusado: ${e.code} ${e.message}")
        respondError(e)
    } catch (e: SerializationException) {
        respondError(FalaiException("invalid", "Pedido mal formado."))
    } catch (e: IllegalArgumentException) {
        respondError(FalaiException("invalid", "Pedido mal formado."))
    } catch (e: Exception) {
        // Erro inesperado (ex.: cartão de memória cheio ou sem permissão): explica em vez de só "500".
        log("Erro no envio de arquivo: $e")
        respondJson(
            FalaiJson.encodeToString(ErrorResponse.serializer(), ErrorResponse("server_error", "Erro no servidor: ${e.message ?: e.javaClass.simpleName}")),
            HttpStatusCode.InternalServerError,
        )
    }
}

/** Lê o corpo inteiro, mas no máximo [limit] bytes (mais que isso = erro). */
private suspend fun ApplicationCall.readBodyLimited(limit: Int): ByteArray = withContext(Dispatchers.IO) {
    val input = receiveChannel().toInputStream()
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > limit) throw FalaiException("too_large", "Pedaço maior que ${limit / (1024 * 1024)} MB.")
    }
    out.toByteArray()
}

fun Route.mediaRoutes(store: Store, files: MediaFiles, log: (String) -> Unit = {}) {
    post("/api/media") {
        call.guard(log) {
            val device = call.authed(store)
            val req = FalaiJson.decodeFromString(MediaCreateRequest.serializer(), call.receiveText())
            val m = store.createMedia(device.user.id, req)
            call.respondJson(FalaiJson.encodeToString(MediaStatus.serializer(), m.status()))
        }
    }

    get("/api/media/{id}/status") {
        call.guard(log) {
            val device = call.authed(store)
            val m = mediaOwnedBy(store, call.parameters["id"]!!, device)
            call.respondJson(FalaiJson.encodeToString(MediaStatus.serializer(), m.status()))
        }
    }

    put("/api/media/{id}") {
        call.guard(log) {
            val device = call.authed(store)
            val m = mediaOwnedBy(store, call.parameters["id"]!!, device)
            val offset = call.request.queryParameters["offset"]?.toLongOrNull()
                ?: throw FalaiException("invalid", "Faltou o offset.")
            val bytes = call.readBodyLimited(MEDIA_CHUNK_BYTES)
            if (bytes.isEmpty()) throw FalaiException("invalid", "Pedaço vazio.")
            val status = withContext(Dispatchers.IO) { files.writeChunk(m, offset, bytes) }
            call.respondJson(FalaiJson.encodeToString(MediaStatus.serializer(), status))
        }
    }

    get("/api/media/{id}") {
        call.guard(log) {
            val device = call.authed(store)
            val id = call.parameters["id"]!!
            if (!store.canDownload(id, device.user.id)) throw FalaiException("not_found", "Arquivo não encontrado.")
            val m = store.media(id)!!
            val f = files.finalFile(id)
            if (!m.complete || !f.exists()) throw FalaiException("not_found", "Arquivo ainda não está disponível.")
            call.response.headers.append("X-Content-SHA256", m.sha256)
            // Ajuda a achar problema: a tela do servidor mostra quem pegou fotos de perfil.
            if (store.isAvatar(id)) log("Foto de perfil enviada para ${device.user.name}")
            call.respondFile(f)
        }
    }
}
