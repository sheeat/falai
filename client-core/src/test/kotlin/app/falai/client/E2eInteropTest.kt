package app.falai.client

import app.falai.protocol.AttachmentDto
import app.falai.protocol.FalaiJson
import app.falai.protocol.SealedDto
import app.falai.protocol.SealedPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Celular (Java) <-> navegador (WebCrypto): só roda quando E2E_INTEROP_DIR está definido; um
 * script de Node faz a parte do navegador com o mesmo código da página. Fases: E2E_INTEROP_PHASE=1
 * tranca para o navegador; =2 abre o que o navegador trancou.
 */
class E2eInteropTest {
    private val dir = System.getenv("E2E_INTEROP_DIR")?.let(::File)
    private val phase = System.getenv("E2E_INTEROP_PHASE")
    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes())

    @Test
    fun interop() {
        val d = dir ?: return
        val node = Json.parseToJsonElement(File(d, "node.json").readText()) as JsonObject
        val nodePub = node["pub"]!!.jsonPrimitive.content
        if (phase == "1") {
            val me = E2e.newKeys()
            val plain = File(d, "jvm-plain.bin").apply { writeBytes(E2e.random(2_621_440)) }
            val key = E2e.random(32); val nonce = E2e.random(12)
            val enc = File(d, "jvm-file.enc")
            E2e.encryptFile(plain, enc, key, nonce)
            assertEquals(E2e.encryptedSize(plain.length()), enc.length())
            val att = AttachmentDto(mediaId = "m1", kind = "file", mime = "application/pdf", name = "receita.pdf", size = plain.length(),
                sha256 = "", key = E2e.b64(key), nonce = E2e.b64(nonce), encSize = enc.length(), encSha256 = "")
            val s = E2e.seal(SealedPayload("Olá do celular 📱 ção", att), "msg-jvm", "conv-1", "dev-jvm", me,
                mapOf("dev-node" to nodePub, "dev-jvm" to me.publicB64))
            File(d, "jvm.json").writeText(buildJsonObject {
                put("priv", E2e.b64(me.privatePkcs8)); put("pub", me.publicB64)
                put("sealed", FalaiJson.encodeToString(SealedDto.serializer(), s))
            }.toString())
        } else if (phase == "2") {
            val jvm = Json.parseToJsonElement(File(d, "jvm.json").readText()) as JsonObject
            val me = E2e.Keys(E2e.unb64(jvm["priv"]!!.jsonPrimitive.content), E2e.unb64(jvm["pub"]!!.jsonPrimitive.content))
            val s = FalaiJson.decodeFromString(SealedDto.serializer(), node["sealed"]!!.jsonPrimitive.content)
            val p = assertNotNull(E2e.open(s, "msg-node", "conv-1", "dev-node", "dev-jvm", me) { it == nodePub })
            assertEquals("Oi do navegador 💻 ação", p.body)
            val att = assertNotNull(p.attachment)
            val out = File(d, "node-file.out")
            E2e.decryptFile(File(d, "node-file.enc"), out, E2e.unb64(att.key!!), E2e.unb64(att.nonce!!))
            assertContentEquals(sha(File(d, "node-plain.bin")), sha(out))
            File(d, "jvm-ok").writeText("ok")
        }
    }
}
