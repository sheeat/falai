package app.falai.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falai.client.ConnectionState
import app.falai.client.FalaiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cores próprias do Falaí: azul-petróleo, calmo e moderno (sem parecer cópia de outro app). */
object FalaiColors {
    val Primary = Color(0xFF136F7A)
    val PrimaryDark = Color(0xFF0B4F57)
    val ChatBg = Color(0xFFECF3F3)
    val BubbleOut = Color(0xFFCFEDEF)
    val BubbleIn = Color(0xFFFFFFFF)
    val TextSec = Color(0xFF5B6B6E)
    val Online = Color(0xFF2E9E5B)
    val Warning = Color(0xFFD08A00)
    val Error = Color(0xFFC62828)
    val Divider = Color(0xFFDCE6E7)
}

@Composable
fun FalaiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = FalaiColors.Primary,
            onPrimary = Color.White,
            secondary = FalaiColors.PrimaryDark,
            background = Color.White,
            surface = Color.White,
        ),
        content = content,
    )
}

/**
 * Bolinha da pessoa: a foto de perfil, se ela tiver uma (e [client] for passado);
 * senão, a inicial do nome numa cor fixa.
 */
@Composable
fun Avatar(name: String, size: Dp = 44.dp, client: FalaiClient? = null, mediaId: String? = null) {
    val palette = listOf(0xFFD9604C, 0xFF3D7EC8, 0xFF2E9E5B, 0xFF8E5BC8, 0xFFD08A00, 0xFF3DA6A6)
    val color = Color(palette[(name.hashCode() and 0x7fffffff) % palette.size])
    var photo by remember(mediaId) { mutableStateOf(mediaId?.let { AvatarCache.get(it) }) }
    if (client != null && !mediaId.isNullOrEmpty() && photo == null) {
        LaunchedEffect(mediaId) {
            // Sem internet agora? Fica a inicial e tenta de novo algumas vezes.
            for (attempt in 1..6) {
                val b = withContext(Dispatchers.IO) {
                    try {
                        loadThumbnail(client.avatarFile(mediaId), 256)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        client.log("Foto de perfil não carregou: ${e.message}")
                        null
                    }
                }
                if (b != null) {
                    AvatarCache.put(mediaId, b)
                    photo = b
                    break
                }
                kotlinx.coroutines.delay(attempt * 5_000L)
            }
        }
    }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        val p = photo
        if (p != null) {
            Image(
                p.asImageBitmap(), contentDescription = "Foto de $name",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                name.trim().take(1).uppercase().ifEmpty { "?" },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

/** Fotos de perfil já abertas, para a lista não piscar ao rolar. */
private object AvatarCache {
    private val map = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 60
        }
    )
    fun get(id: String): Bitmap? = map[id]
    fun put(id: String, b: Bitmap) { map[id] = b }
}

/** "● online", "● conectando…" etc., para o topo das telas. */
@Composable
fun ConnectionBadge(state: ConnectionState, onDark: Boolean = true) {
    val (color, text) = when (state) {
        ConnectionState.ONLINE -> FalaiColors.Online to "online"
        ConnectionState.CONNECTING -> FalaiColors.Warning to "conectando…"
        ConnectionState.OFFLINE -> FalaiColors.Warning to "sem conexão"
        ConnectionState.UNAUTHORIZED -> FalaiColors.Error to "aparelho desconectado pelo servidor"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            fontSize = 12.sp,
            color = if (onDark) Color.White.copy(alpha = 0.85f) else FalaiColors.TextSec,
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}

/** Seta de voltar, bem visível (desenhada, mais grossa que um caractere "←"). */
@Composable
fun BackButton(onClick: () -> Unit, color: Color = Color.White) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
            val w = size.width
            val h = size.height
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 3.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            )
            val path = androidx.compose.ui.graphics.Path().apply {
                // Corpo da seta
                moveTo(w * 0.85f, h * 0.5f)
                lineTo(w * 0.15f, h * 0.5f)
                // Ponta
                moveTo(w * 0.45f, h * 0.18f)
                lineTo(w * 0.15f, h * 0.5f)
                lineTo(w * 0.45f, h * 0.82f)
            }
            drawPath(path, color, style = stroke)
        }
    }
}

/** Botão de configurações: uma engrenagem desenhada, bem visível. */
@Composable
fun SettingsButton(onClick: () -> Unit, color: Color = Color.White) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
            val c = center
            val outer = size.minDimension / 2f
            val ring = outer * 0.68f
            // Dentes da engrenagem
            for (i in 0 until 8) {
                rotate(i * 45f, c) {
                    drawRoundRect(
                        color,
                        topLeft = androidx.compose.ui.geometry.Offset(c.x - outer * 0.17f, c.y - outer),
                        size = androidx.compose.ui.geometry.Size(outer * 0.34f, outer * 0.5f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(outer * 0.08f),
                    )
                }
            }
            // Corpo (anel)
            drawCircle(color, radius = ring, center = c)
            drawCircle(FalaiColors.Primary, radius = ring * 0.45f, center = c)
        }
    }
}
