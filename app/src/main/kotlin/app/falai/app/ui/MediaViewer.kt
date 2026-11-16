package app.falai.app.ui

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.falai.protocol.AttachmentDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foto ou vídeo em tela cheia, dentro do próprio app (como nos outros mensageiros).
 * Foto: dois dedos para aumentar e arrastar; dois toques para aumentar ou voltar.
 * Vídeo: começa a tocar sozinho; também dá zoom com dois dedos. Um toque mostra os controles.
 */
@Composable
fun MediaViewer(
    file: File,
    att: AttachmentDto,
    onClose: () -> Unit,
    onSave: () -> Unit = {},
    onForward: () -> Unit = {},
    onShare: () -> Unit = {},
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (att.kind == "video") {
            ZoomableVideo(file)
        } else {
            ZoomableImage(file)
        }

        // Barra de cima: voltar, nome e "abrir em outro app".
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onClose)
            Text(
                att.name, color = Color.White, fontSize = 15.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(4.dp))
            TextButton(onClick = onSave) { Text("⬇ Salvar", color = Color.White, fontSize = 14.sp) }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮", color = Color.White, fontSize = 22.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("↪  Encaminhar no Falaí") }, onClick = { menu = false; onForward() })
                    DropdownMenuItem(text = { Text("📤  Compartilhar (WhatsApp…)") }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(
                        text = { Text("Abrir com outro app") },
                        onClick = { menu = false; openExternally(context, file, att.mime) },
                    )
                }
            }
        }
    }
}

/** Zoom e posição da foto/vídeo na tela cheia. */
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
}

/**
 * Gestos de zoom: dois dedos para aumentar e arrastar, dois toques para aumentar no ponto
 * tocado (ou voltar). [onTap] = um toque só (ex.: mostrar os controles do vídeo).
 */
private fun Modifier.zoomGestures(z: ZoomState, key: Any, onTap: (() -> Unit)? = null): Modifier =
    pointerInput(key) {
        detectTapGestures(
            onTap = { onTap?.invoke() },
            onDoubleTap = { tap ->
                if (z.scale > 1.01f) {
                    z.scale = 1f
                    z.offset = Offset.Zero
                } else {
                    // Aumenta em volta do ponto tocado.
                    z.scale = 2.5f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    z.offset = (center - tap) * 1.5f
                }
            },
        )
    }.pointerInput(key) {
        detectTransformGestures { _, pan, zoom, _ ->
            val newScale = (z.scale * zoom).coerceIn(1f, 6f)
            z.scale = newScale
            z.offset = if (newScale <= 1.01f) Offset.Zero else {
                // Não deixa sumir da tela.
                val maxX = size.width * (newScale - 1) / 2f
                val maxY = size.height * (newScale - 1) / 2f
                val o = z.offset + pan
                Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
            }
        }
    }

private fun Modifier.zoomLayer(z: ZoomState): Modifier = graphicsLayer {
    scaleX = z.scale
    scaleY = z.scale
    translationX = z.offset.x
    translationY = z.offset.y
}

@Composable
private fun ZoomableImage(file: File) {
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file) {
        // Tamanho bom para a tela, sem estourar a memória com fotos enormes.
        bitmap = withContext(Dispatchers.IO) { loadThumbnail(file, 2560) }
        failed = bitmap == null
    }
    val zoom = remember(file) { ZoomState() }

    val b = bitmap
    when {
        b != null -> Image(
            b.asImageBitmap(),
            contentDescription = "Foto",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().zoomGestures(zoom, file).zoomLayer(zoom),
        )
        failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Não consegui abrir esta foto aqui. Toque em ⋮ e \"Abrir com outro app\".", color = Color.White)
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

/**
 * Vídeo dentro do app, com o mesmo zoom da foto (dois dedos, dois toques).
 * Usa TextureView (e não o VideoView) porque só ele aceita ser aumentado.
 * Um toque mostra ou esconde os controles.
 */
@Composable
private fun ZoomableVideo(file: File) {
    val player = remember(file) { MediaPlayer() }
    var playing by remember(file) { mutableStateOf(false) }
    var positionMs by remember(file) { mutableLongStateOf(0L) }
    var durationMs by remember(file) { mutableLongStateOf(0L) }
    var ratio by remember(file) { mutableFloatStateOf(16f / 9f) }
    var failed by remember(file) { mutableStateOf(false) }
    var controls by remember(file) { mutableStateOf(true) }
    var seeking by remember(file) { mutableStateOf(false) }
    val zoom = remember(file) { ZoomState() }

    DisposableEffect(file) {
        player.setOnPreparedListener { mp ->
            durationMs = mp.duration.toLong().coerceAtLeast(0)
            if (mp.videoWidth > 0 && mp.videoHeight > 0) ratio = mp.videoWidth.toFloat() / mp.videoHeight
            mp.start()
            playing = true
        }
        player.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) ratio = w.toFloat() / h }
        player.setOnCompletionListener {
            playing = false
            controls = true
        }
        player.setOnErrorListener { _, _, _ ->
            failed = true
            true
        }
        try {
            player.setDataSource(file.path)
            player.prepareAsync()
        } catch (e: Exception) {
            failed = true
        }
        onDispose { runCatching { player.release() } }
    }
    // Tempo andando enquanto toca.
    LaunchedEffect(playing) {
        while (playing) {
            if (!seeking) positionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(positionMs)
            delay(250)
        }
    }
    // Controles somem sozinhos depois de 3 segundos tocando.
    LaunchedEffect(controls, playing) {
        if (controls && playing) {
            delay(3000)
            controls = false
        }
    }
    fun togglePlay() {
        runCatching {
            if (player.isPlaying) {
                player.pause()
                playing = false
            } else {
                player.start()
                playing = true
            }
        }
        controls = true
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            runCatching { player.setSurface(Surface(st)) }
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            runCatching { player.setSurface(null) }
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.align(Alignment.Center).aspectRatio(ratio).zoomLayer(zoom),
        )
        // Camada por cima que recebe os gestos (zoom, arrastar, toque).
        Box(Modifier.fillMaxSize().zoomGestures(zoom, file, onTap = { controls = !controls }))

        if (failed) {
            Text(
                "Não consegui tocar este vídeo aqui. Toque em ⋮ e \"Abrir com outro app\".",
                color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        } else if (controls || !playing) {
            // Botão grande no meio.
            Box(
                Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)).clickable { togglePlay() },
                contentAlignment = Alignment.Center,
            ) { Text(if (playing) "❚❚" else "▶", color = Color.White, fontSize = 30.sp) }
            // Barra de baixo: tempo e onde está.
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f)).navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatMs(positionMs), color = Color.White, fontSize = 13.sp)
                Slider(
                    value = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                    onValueChange = {
                        seeking = true
                        controls = true
                        positionMs = (it * durationMs).toLong()
                    },
                    onValueChangeFinished = {
                        runCatching { player.seekTo(positionMs.toInt()) }
                        seeking = false
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(formatMs(durationMs), color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
