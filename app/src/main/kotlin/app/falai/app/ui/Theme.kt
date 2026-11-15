package app.falai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falai.client.ConnectionState

/** Cores próprias do Falaí: laranja-terracota, para não parecer cópia de outro app. */
object FalaiColors {
    val Primary = Color(0xFFC8553D)
    val PrimaryDark = Color(0xFF8E3B2A)
    val ChatBg = Color(0xFFF6F1EA)
    val BubbleOut = Color(0xFFFFE1D3)
    val BubbleIn = Color(0xFFFFFFFF)
    val TextSec = Color(0xFF6B6460)
    val Online = Color(0xFF2E9E5B)
    val Warning = Color(0xFFD08A00)
    val Error = Color(0xFFC62828)
    val Divider = Color(0xFFEAE4DE)
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

/** Bolinha com a inicial do nome, no lugar da foto. */
@Composable
fun Avatar(name: String, size: Dp = 44.dp) {
    val palette = listOf(0xFFC8553D, 0xFF3D7EC8, 0xFF2E9E5B, 0xFF8E5BC8, 0xFFD08A00, 0xFF3DA6A6)
    val color = Color(palette[(name.hashCode() and 0x7fffffff) % palette.size])
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
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
