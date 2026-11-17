package app.falai.client

import app.falai.protocol.FalaiJson
import app.falai.protocol.SealedDto
import app.falai.protocol.SealedPayload
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * O "cadeado" do Falaí (criptografia de ponta a ponta), sem bibliotecas de fora: só o que já vem
 * no Java/Android. A página web faz exatamente as mesmas contas com o WebCrypto do navegador.
 *
 * - Cada aparelho tem um par de chaves ECDH P-256. A pública (o "cadeado") vai para o servidor.
 * - Cada mensagem é trancada com uma chave nova (AES-256-GCM).
 * - Essa chave vai trancada para cada aparelho da conversa: segredo ECDH(meu, dele) -> HKDF-SHA256
 *   -> AES-256-GCM. Só quem tem a chave privada de um desses aparelhos consegue abrir.
 * - Arquivos: trancados em pedaços de 1 MB (AES-256-GCM em cada pedaço), com uma chave própria
 *   que vai dentro da mensagem trancada.
 */
object E2e {
    private val rnd = SecureRandom()
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()
    private val SALT = "falai-e2e-v1".toByteArray()

    /** Começo fixo do formato X.509 de uma chave pública P-256 (antes dos 65 bytes crus). */
    private val X509_PREFIX = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200")

    const val FILE_CHUNK = 1 shl 20
    private const val TAG = 16

    class Keys(val privatePkcs8: ByteArray, val publicRaw: ByteArray) {
        val publicB64: String get() = b64e.encodeToString(publicRaw)
        val privateKey: PrivateKey by lazy { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privatePkcs8)) }
    }

    fun newKeys(): Keys {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), rnd)
        val kp = g.generateKeyPair()
        val enc = kp.public.encoded
        return Keys(kp.private.encoded, enc.copyOfRange(enc.size - 65, enc.size))
    }

    fun random(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    fun b64(b: ByteArray): String = b64e.encodeToString(b)
    fun unb64(s: String): ByteArray = b64d.decode(s)

    private fun publicFromRaw(raw: ByteArray): PublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte()) { "cadeado inválido" }
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(X509_PREFIX + raw))
    }

    private fun ecdh(priv: PrivateKey, otherRaw: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(publicFromRaw(otherRaw), true)
        return ka.generateSecret()
    }

    /** HKDF-SHA256 (RFC 5869), 32 bytes. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1)
        return mac.doFinal()
    }

    fun gcm(encrypt: Boolean, key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return c.doFinal(data)
    }

    private fun wrapKey(myPriv: PrivateKey, otherRaw: ByteArray, fromDevice: String, toDevice: String): ByteArray =
        hkdf(ecdh(myPriv, otherRaw), SALT, "wrap|$fromDevice|$toDevice".toByteArray())

    private fun contentAad(messageId: String, conversationId: String) = "$messageId|$conversationId".toByteArray()

    /** Tranca [payload] para os aparelhos [recipients] (id do aparelho -> cadeado em base64). */
    fun seal(
        payload: SealedPayload, messageId: String, conversationId: String,
        myDeviceId: String, me: Keys, recipients: Map<String, String>,
    ): SealedDto {
        val k = random(32)
        val iv = random(12)
        val ct = gcm(true, k, iv, FalaiJson.encodeToString(SealedPayload.serializer(), payload).toByteArray(), contentAad(messageId, conversationId))
        val keys = LinkedHashMap<String, String>()
        for ((dev, pub) in recipients) {
            val raw = runCatching { unb64(pub) }.getOrNull() ?: continue
            val wk = runCatching { wrapKey(me.privateKey, raw, myDeviceId, dev) }.getOrNull() ?: continue
            val wiv = random(12)
            keys[dev] = b64(wiv + gcm(true, wk, wiv, k, messageId.toByteArray()))
        }
        return SealedDto(senderKey = me.publicB64, iv = b64(iv), ct = b64(ct), keys = keys)
    }

    /**
     * Abre uma mensagem trancada. [senderDeviceId] vem do servidor (quem mandou); [trustedSenderKey]
     * diz se o cadeado que veio na mensagem é um que este aparelho já conhece daquele aparelho.
     * Devolve null se não der (mensagem não era para este aparelho, ou foi mexida no caminho).
     */
    fun open(
        s: SealedDto, messageId: String, conversationId: String, senderDeviceId: String,
        myDeviceId: String, me: Keys, trustedSenderKey: (String) -> Boolean,
    ): SealedPayload? = runCatching {
        if (!trustedSenderKey(s.senderKey)) return null
        val wrapped = unb64(s.keys[myDeviceId] ?: return null)
        val wk = wrapKey(me.privateKey, unb64(s.senderKey), senderDeviceId, myDeviceId)
        val k = gcm(false, wk, wrapped.copyOfRange(0, 12), wrapped.copyOfRange(12, wrapped.size), messageId.toByteArray())
        val plain = gcm(false, k, unb64(s.iv), unb64(s.ct), contentAad(messageId, conversationId))
        FalaiJson.decodeFromString(SealedPayload.serializer(), String(plain))
    }.getOrNull()

    // ---------- Arquivos ----------

    /** Tamanho do arquivo trancado: cada pedaço de 1 MB ganha 16 bytes (a etiqueta). */
    fun encryptedSize(plainSize: Long): Long {
        val chunks = if (plainSize == 0L) 1 else (plainSize + FILE_CHUNK - 1) / FILE_CHUNK
        return plainSize + chunks * TAG
    }

    private fun chunkIv(nonce: ByteArray, index: Int): ByteArray {
        val iv = nonce.copyOf()
        for (i in 0 until 4) iv[8 + i] = (iv[8 + i].toInt() xor (index ushr (24 - 8 * i))).toByte()
        return iv
    }

    /** AAD de cada pedaço: o número dele e se é o último (assim ninguém corta o arquivo sem perceber). */
    private fun chunkAad(index: Int, last: Boolean) =
        byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte(), if (last) 1 else 0)

    fun encryptFile(src: File, dst: File, key: ByteArray, nonce: ByteArray) {
        val total = src.length()
        val chunks = if (total == 0L) 1 else ((total + FILE_CHUNK - 1) / FILE_CHUNK).toInt()
        val buf = ByteArray(FILE_CHUNK)
        src.inputStream().buffered(FILE_CHUNK).use { input ->
            dst.outputStream().buffered(FILE_CHUNK).use { out ->
                for (i in 0 until chunks) {
                    val want = if (i == chunks - 1) (total - i.toLong() * FILE_CHUNK).toInt() else FILE_CHUNK
                    var got = 0
                    while (got < want) {
                        val n = input.read(buf, got, want - got)
                        if (n < 0) throw java.io.IOException("O arquivo mudou enquanto era trancado.")
                        got += n
                    }
                    out.write(gcm(true, key, chunkIv(nonce, i), buf.copyOf(want), chunkAad(i, i == chunks - 1)))
                }
            }
        }
    }

    /** Destranca; se algo foi mexido ou cortado, dá erro (e o arquivo de saída não vale). */
    fun decryptFile(src: File, dst: File, key: ByteArray, nonce: ByteArray) {
        val total = src.length()
        val block = FILE_CHUNK + TAG
        val chunks = if (total <= block) 1 else ((total + block - 1) / block).toInt()
        if (total < TAG) throw java.io.IOException("Arquivo trancado inválido.")
        val buf = ByteArray(block)
        src.inputStream().buffered(block).use { input ->
            dst.outputStream().buffered(block).use { out ->
                for (i in 0 until chunks) {
                    val want = if (i == chunks - 1) (total - i.toLong() * block).toInt() else block
                    var got = 0
                    while (got < want) {
                        val n = input.read(buf, got, want - got)
                        if (n < 0) throw java.io.IOException("Arquivo trancado incompleto.")
                        got += n
                    }
                    out.write(gcm(false, key, chunkIv(nonce, i), buf.copyOf(want), chunkAad(i, i == chunks - 1)))
                }
            }
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
