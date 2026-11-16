package app.falai.server

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertTrue

/** APK de mentira, assinado de verdade (v2), para testar o carimbo do convite. */
object TestApks {
    private fun key(dir: File): Pair<PrivateKey, X509Certificate> {
        val ks = File(dir, "k.jks")
        if (!ks.exists()) {
            val bin = File(System.getProperty("java.home"), "bin/keytool").path
            val p = ProcessBuilder(
                bin, "-genkeypair", "-keystore", ks.path, "-storepass", "senha123", "-keypass", "senha123",
                "-alias", "k", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365", "-dname", "CN=Teste",
            ).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            check(p.waitFor() == 0) { "keytool falhou" }
        }
        val store = KeyStore.getInstance("JKS").apply { ks.inputStream().use { load(it, "senha123".toCharArray()) } }
        return store.getKey("k", "senha123".toCharArray()) as PrivateKey to store.getCertificate("k") as X509Certificate
    }

    fun signed(dir: File): File {
        val unsigned = File(dir, "u.apk")
        ZipOutputStream(unsigned.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(ByteArray(300) { it.toByte() }); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write(ByteArray(50_000) { (it * 7).toByte() }); z.closeEntry()
        }
        val (k, c) = key(dir)
        val out = File(dir, "s.apk")
        ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder("k", k, listOf(c)).build()))
            .setInputApk(unsigned).setOutputApk(out).setMinSdkVersion(28)
            .setV1SigningEnabled(false).setV2SigningEnabled(true).build().sign()
        return out
    }

    fun verify(f: File) {
        val r = ApkVerifier.Builder(f).setMinCheckedPlatformVersion(28).build().verify()
        assertTrue(r.isVerified, "assinatura inválida: ${r.errors} ${r.v2SchemeSigners.flatMap { it.errors }}")
        assertTrue(r.isVerifiedUsingV2Scheme)
    }
}
