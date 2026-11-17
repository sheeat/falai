package app.falai.protocol

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * "Carimbo" no arquivo do app: o servidor escreve o convite (ou só o endereço do servidor) dentro
 * do APK no momento do download, e o app lê do próprio arquivo quando abre pela primeira vez.
 * Assim a pessoa não precisa colar nem digitar nada.
 *
 * Onde fica: no "APK Signing Block", a área da assinatura v2/v3 que o Android deixa de fora da
 * conta da assinatura e onde ignora blocos que não conhece (a mesma técnica que lojas usam para
 * marcar o canal de download). A assinatura continua válida e o app instala normalmente.
 */
object ApkStamp {
    /** Identificador do nosso bloco ("Fala" em ASCII). */
    const val BLOCK_ID = 0x46616c61
    private const val PADDING_ID = 0x42726577 // bloco de alinhamento que o Android Studio põe
    private const val MAX_PAYLOAD = 4096
    private val MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

    /** Posições importantes de um APK assinado (v2 ou mais novo). */
    class Layout(
        val blockStart: Long,
        val cdStart: Long,
        val eocdStart: Long,
        val fileSize: Long,
        /** Pares (id, valor) do bloco de assinatura, na ordem original. */
        val pairs: List<Pair<Int, ByteArray>>,
    )

    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    private fun readAt(f: RandomAccessFile, pos: Long, n: Int): ByteArray {
        val b = ByteArray(n)
        f.seek(pos)
        f.readFully(b)
        return b
    }

    /** Lê a estrutura do APK. Null = não é um APK com assinatura v2/v3 (nada a fazer). */
    fun layout(f: RandomAccessFile): Layout? {
        val size = f.length()
        if (size < 22) return null
        // Fim do ZIP (EOCD): procura de trás para frente (pode ter comentário de até 64 KB).
        val tailLen = minOf(size, 22L + 65535).toInt()
        val tail = readAt(f, size - tailLen, tailLen)
        var eocdInTail = -1
        for (i in tailLen - 22 downTo 0) {
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 5.toByte() && tail[i + 3] == 6.toByte()) {
                val commentLen = le(tail).getShort(i + 20).toInt() and 0xffff
                if (i + 22 + commentLen == tailLen) { eocdInTail = i; break }
            }
        }
        if (eocdInTail < 0) return null
        val eocdStart = size - tailLen + eocdInTail
        val cdStart = le(tail).getInt(eocdInTail + 16).toLong() and 0xffffffffL
        if (cdStart < 32 || cdStart > eocdStart) return null
        val footer = readAt(f, cdStart - 24, 24)
        if (!footer.copyOfRange(8, 24).contentEquals(MAGIC)) return null
        val blockSize = le(footer).getLong(0) // sem contar os 8 bytes do primeiro campo de tamanho
        val blockStart = cdStart - blockSize - 8
        if (blockSize < 24 || blockSize > Int.MAX_VALUE || blockStart < 0) return null
        val block = readAt(f, blockStart, (blockSize + 8).toInt())
        val bb = le(block)
        if (bb.getLong(0) != blockSize) return null
        val pairs = mutableListOf<Pair<Int, ByteArray>>()
        var p = 8
        val end = block.size - 24
        while (p < end) {
            if (end - p < 12) return null
            val len = bb.getLong(p)
            if (len < 4 || len > end - p - 8) return null
            val id = bb.getInt(p + 8)
            pairs += id to block.copyOfRange(p + 12, p + 8 + len.toInt())
            p += 8 + len.toInt()
        }
        return Layout(blockStart, cdStart, eocdStart, size, pairs)
    }

    /** O texto carimbado no APK, ou null se não tiver. */
    fun read(apk: File): String? = runCatching {
        RandomAccessFile(apk, "r").use { f ->
            layout(f)?.pairs?.firstOrNull { it.first == BLOCK_ID }?.second
                ?.takeIf { it.size <= MAX_PAYLOAD }?.toString(Charsets.UTF_8)
        }
    }.getOrNull()

    fun buildBlock(original: List<Pair<Int, ByteArray>>, payload: ByteArray): ByteArray {
        val hadPadding = original.any { it.first == PADDING_ID }
        val pairs = original.filter { it.first != BLOCK_ID && it.first != PADDING_ID } + (BLOCK_ID to payload)
        var pairsLen = pairs.sumOf { 12L + it.second.size }
        var padding = 0
        if (hadPadding) {
            // Mantém o bloco com tamanho múltiplo de 4096, como o Android Studio deixa.
            val total = 8 + pairsLen + 8 + 16
            var pad = ((4096 - total % 4096) % 4096).toInt()
            if (pad in 1 until 12) pad += 4096
            padding = pad
            pairsLen += pad
        }
        val sizeField = pairsLen + 8 + 16
        val out = ByteBuffer.allocate((sizeField + 8).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        out.putLong(sizeField)
        for ((id, value) in pairs) {
            out.putLong(4L + value.size)
            out.putInt(id)
            out.put(value)
        }
        if (padding > 0) {
            out.putLong(padding - 8L)
            out.putInt(PADDING_ID)
            out.put(ByteArray(padding - 12))
        }
        out.putLong(sizeField)
        out.put(MAGIC)
        return out.array()
    }

    /** Uma cópia carimbada do APK, pronta para ser enviada. */
    class Stamped(private val apk: File, private val layout: Layout, private val block: ByteArray, private val eocd: ByteArray) {
        val size: Long get() = layout.blockStart + block.size + (layout.eocdStart - layout.cdStart) + eocd.size

        fun writeTo(out: OutputStream) {
            RandomAccessFile(apk, "r").use { f ->
                copy(f, 0, layout.blockStart, out)
                out.write(block)
                copy(f, layout.cdStart, layout.eocdStart - layout.cdStart, out)
                out.write(eocd)
            }
        }

        private fun copy(f: RandomAccessFile, from: Long, len: Long, out: OutputStream) {
            f.seek(from)
            val buf = ByteArray(256 * 1024)
            var left = len
            while (left > 0) {
                val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw java.io.EOFException()
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    /**
     * Prepara o APK com [text] carimbado. Null se o APK não tiver assinatura v2/v3 (aí é melhor
     * mandar o arquivo como está) ou se o texto for grande demais.
     */
    fun stamp(apk: File, text: String): Stamped? {
        val payload = text.toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_PAYLOAD) return null
        return runCatching {
            RandomAccessFile(apk, "r").use { f ->
                val l = layout(f) ?: return null
                val block = buildBlock(l.pairs, payload)
                val eocd = readAt(f, l.eocdStart, (l.fileSize - l.eocdStart).toInt())
                val newCdStart = l.blockStart + block.size
                if (newCdStart > 0xffffffffL) return null
                le(eocd).putInt(16, newCdStart.toInt())
                Stamped(apk, l, block, eocd)
            }
        }.getOrNull()
    }
}
