package app.falai.app.ui

import android.graphics.Bitmap
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foto ou vídeo em tela cheia, dentro do próprio app (como nos outros mensageiros).
 * Foto: dois dedos para aumentar e arrastar; dois toques para aumentar ou voltar.
 * Vídeo: começa a tocar sozinho, com os controles do Android (pausar, avançar).
 */
@Composable
fun MediaViewer(file: File, att: AttachmentDto, onClose: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (att.kind == "video") {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val controls = MediaController(ctx)
                        controls.setAnchorView(this)
                        setMediaController(controls)
                        setVideoPath(file.path)
                        setOnPreparedListener { start(); controls.show(3000) }
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
            )
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
            TextButton(onClick = { openExternally(context, file, att.mime) }) {
                Text("Abrir com…", color = Color.White, fontSize = 13.sp)
            }
        }
    }
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
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }

    val b = bitmap
    when {
        b != null -> Image(
            b.asImageBitmap(),
            contentDescription = "Foto",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(file) {
                    detectTapGestures(onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // Aumenta em volta do ponto tocado.
                            scale = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = (center - tap) * 1.5f
                        }
                    })
                }
                .pointerInput(file) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 6f)
                        scale = newScale
                        offset = if (newScale <= 1.01f) Offset.Zero else {
                            // Não deixa a foto sumir da tela.
                            val maxX = size.width * (newScale - 1) / 2f
                            val maxY = size.height * (newScale - 1) / 2f
                            val o = offset + pan
                            Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
        failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Não consegui abrir esta foto aqui. Toque em \"Abrir com…\".", color = Color.White)
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}
