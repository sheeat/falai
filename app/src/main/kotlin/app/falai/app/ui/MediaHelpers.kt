package app.falai.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Pasta onde o app guarda os arquivos (a mesma que o FalaiClient usa). */
fun mediaDir(context: Context): File = File(context.filesDir, "media").apply { mkdirs() }

/** Arquivo novo e vazio para a câmera ou o gravador escreverem. */
fun newOutgoingFile(context: Context, ext: String): File =
    File(File(mediaDir(context), "enviados").apply { mkdirs() }, "${UUID.randomUUID()}.$ext")

fun uriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, context.packageName + ".files", file)

fun kindForMime(mime: String): String = when {
    mime.startsWith("image/") -> "image"
    mime.startsWith("video/") -> "video"
    mime.startsWith("audio/") -> "audio"
    else -> "file"
}

data class CopiedFile(val file: File, val name: String, val mime: String)

/**
 * Copia o arquivo escolhido (galeria ou "Arquivos") para a pasta do app, byte a byte,
 * sem recomprimir nada: o que sai daqui é o original.
 */
fun copyFromUri(context: Context, uri: Uri): CopiedFile? {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    var name = "arquivo"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.let { name = it }
    }
    val ext = name.substringAfterLast('.', "").takeIf { it.length in 1..5 } ?: "bin"
    val target = newOutgoingFile(context, ext)
    val input = resolver.openInputStream(uri) ?: return null
    input.use { i -> target.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
    return CopiedFile(target, name, mime)
}

fun durationMs(file: File): Long? = try {
    MediaMetadataRetriever().run {
        try {
            setDataSource(file.path)
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            release()
        }
    }
} catch (e: Exception) {
    null
}

/** Abre o arquivo no app do sistema (galeria, player de vídeo, leitor de PDF...). */
fun openExternally(context: Context, file: File, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uriFor(context, file), mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Nenhum app neste celular abre esse tipo de arquivo.", Toast.LENGTH_LONG).show()
    }
}

/**
 * Miniatura só para mostrar na conversa (o arquivo original não é tocado).
 * Respeita a rotação gravada pela câmera.
 */
/**
 * Abre a foto em tamanho reduzido para mostrar na tela. Usa o decodificador moderno do Android
 * (entende HEIC, WebP, AVIF e a rotação da câmera); se falhar, tenta o antigo.
 */
fun loadThumbnail(file: File, maxSide: Int = 1024): Bitmap? =
    try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = maxSide.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        null
    } ?: legacyThumbnail(file, maxSide)

private fun legacyThumbnail(file: File, maxSide: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
    val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    val rotation = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (bmp != null && rotation != 0f) {
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
    } else bmp
} catch (e: Exception) {
    null
}

/** Um quadro do vídeo para servir de capa. */
fun videoThumbnail(file: File, maxSide: Int = 1024): Bitmap? = try {
    MediaMetadataRetriever().run {
        try {
            setDataSource(file.path)
            val frame = getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: getFrameAtTime(0)
                ?: getFrameAtTime(-1)
            frame?.let { scaleDown(it, maxSide) }
        } finally {
            release()
        }
    }
} catch (e: Exception) {
    null
}

private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
    val scale = maxSide.toFloat() / maxOf(b.width, b.height)
    if (scale >= 1f) return b
    return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * scale).toInt()), maxOf(1, (b.height * scale).toInt()), true)
}

/**
 * Miniatura bem pequena (JPEG em base64) que vai junto com a mensagem, para quem recebe
 * ver a prévia antes de baixar. Devolve null se não der para gerar.
 */
fun inlineThumb(file: File, kind: String): String? = try {
    val big = when (kind) {
        "image" -> loadThumbnail(file, 320)
        "video" -> videoThumbnail(file, 320)
        else -> null
    }
    big?.let { bmp ->
        val small = scaleDown(bmp, 320)
        var quality = 70
        var bytes: ByteArray
        do {
            val out = java.io.ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            quality -= 15
        } while (bytes.size > 17_000 && quality >= 25)
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            .takeIf { it.length <= app.falai.protocol.MAX_THUMB_CHARS }
    }
} catch (e: Exception) {
    null
}

/** Abre a miniatura que veio junto com a mensagem. */
fun decodeInlineThumb(b64: String?): Bitmap? = try {
    b64?.let {
        val bytes = android.util.Base64.decode(it, android.util.Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
} catch (e: Exception) {
    null
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** Gravador de áudio simples (AAC em .m4a, a mesma qualidade de voz dos mensageiros comuns). */
class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    var file: File? = null
        private set
    var startedAt: Long = 0
        private set

    fun start() {
        val f = newOutgoingFile(context, "m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(96_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(f.path)
        r.prepare()
        r.start()
        recorder = r
        file = f
        startedAt = System.currentTimeMillis()
    }

    /** Para e devolve o arquivo gravado (ou null se ficou curto demais). */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false // gravação curta demais
        } finally {
            r.release()
        }
        val f = file
        file = null
        return if (ok && f != null && f.length() > 0) f else { f?.delete(); null }
    }

    fun cancel() {
        stop()?.delete()
    }
}

/**
 * Prepara a foto de perfil: corta um quadrado do meio da foto escolhida, reduz para 512×512
 * e salva em JPEG (fica pequena, uns 50 KB). Devolve null se não deu para abrir a foto.
 */
fun makeProfilePhoto(context: Context, uri: Uri): File? {
    val copied = copyFromUri(context, uri) ?: return null
    try {
        val bmp = loadThumbnail(copied.file, 1024) ?: return null
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val small = if (side > 512) Bitmap.createScaledBitmap(square, 512, 512, true) else square
        val out = newOutgoingFile(context, "jpg")
        out.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return out
    } catch (e: Exception) {
        return null
    } finally {
        copied.file.delete()
    }
}
