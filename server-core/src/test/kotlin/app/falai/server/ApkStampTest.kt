package app.falai.server

import app.falai.protocol.ApkStamp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** O carimbo do convite dentro do APK não pode quebrar a assinatura (senão o Android não instala). */
class ApkStampTest {
    private val dir = kotlin.io.path.createTempDirectory("apkstamp").toFile()

    private fun signedApk() = TestApks.signed(dir)

    private fun verify(f: File) = TestApks.verify(f)

    private fun write(s: ApkStamp.Stamped, name: String): File {
        val f = File(dir, name)
        f.outputStream().use { s.writeTo(it) }
        assertEquals(s.size, f.length())
        return f
    }

    @Test
    fun stampKeepsSignatureAndCanBeRead() {
        val apk = signedApk()
        verify(apk)
        assertNull(ApkStamp.read(apk))
        val text = "falai://convite?s=https%3A%2F%2Ffalai.exemplo.net&c=ABCDE23456&n=Ana%20Lu%C3%ADza"
        val stamped = write(ApkStamp.stamp(apk, text)!!, "c1.apk")
        verify(stamped)
        assertEquals(text, ApkStamp.read(stamped))
        // Carimbar de novo troca o texto (não acumula).
        val again = write(ApkStamp.stamp(stamped, "falai://servidor?s=x")!!, "c2.apk")
        verify(again)
        assertEquals("falai://servidor?s=x", ApkStamp.read(again))
        assertEquals(1, RandomAccessFileLayout.pairs(again).count { it == ApkStamp.BLOCK_ID })
    }

    @Test
    fun keepsPaddingAlignedTo4096() {
        val apk = signedApk()
        // Simula o APK do Android Studio, que tem um bloco de alinhamento (id 0x42726577).
        val l = java.io.RandomAccessFile(apk, "r").use { ApkStamp.layout(it)!! }
        val withPad = ApkStamp.buildBlock(l.pairs + (0x42726577 to ByteArray(100)), "x".toByteArray())
        assertEquals(0, withPad.size % 4096)
        val f = File(dir, "pad.apk")
        java.io.RandomAccessFile(apk, "r").use { src ->
            val head = ByteArray(l.blockStart.toInt()); src.seek(0); src.readFully(head)
            val cd = ByteArray((l.eocdStart - l.cdStart).toInt()); src.seek(l.cdStart); src.readFully(cd)
            val eocd = ByteArray((l.fileSize - l.eocdStart).toInt()); src.seek(l.eocdStart); src.readFully(eocd)
            java.nio.ByteBuffer.wrap(eocd).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(16, (l.blockStart + withPad.size).toInt())
            f.outputStream().use { it.write(head); it.write(withPad); it.write(cd); it.write(eocd) }
        }
        verify(f)
        val stamped = write(ApkStamp.stamp(f, "falai://servidor?s=https%3A%2F%2Fa.b")!!, "pad2.apk")
        verify(stamped)
        val l2 = java.io.RandomAccessFile(stamped, "r").use { ApkStamp.layout(it)!! }
        assertEquals(0L, (l2.cdStart - l2.blockStart) % 4096)
        assertEquals("falai://servidor?s=https%3A%2F%2Fa.b", ApkStamp.read(stamped))
    }

    @Test
    fun notAnApkGivesNull() {
        val f = File(dir, "x.txt").apply { writeText("PK nada") }
        assertNull(ApkStamp.stamp(f, "oi"))
        assertNull(ApkStamp.read(f))
    }

    private object RandomAccessFileLayout {
        fun pairs(f: File) = java.io.RandomAccessFile(f, "r").use { ApkStamp.layout(it)!!.pairs.map { p -> p.first } }
    }
}
