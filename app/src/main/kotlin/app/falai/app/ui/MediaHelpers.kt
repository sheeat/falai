package app.falai.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
fun loadThumbnail(file: File, maxSide: Int = 1024): Bitmap? = try {
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

fun videoThumbnail(file: File): Bitmap? = try {
    MediaMetadataRetriever().run {
        try {
            setDataSource(file.path)
            getFrameAtTime(0)
        } finally {
            release()
        }
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
