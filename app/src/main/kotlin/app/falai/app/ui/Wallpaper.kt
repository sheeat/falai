package app.falai.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falai.protocol.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Escolha do fundo das conversas, guardada neste aparelho. */
data class WallpaperChoice(val colorId: String, val doodles: Boolean, val photo: File?)

object WallpaperPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("falai", Context.MODE_PRIVATE)
    fun photoFile(c: Context) = File(c.filesDir, "fundo-conversa.jpg")

    fun load(c: Context): WallpaperChoice {
        val p = prefs(c)
        val photo = photoFile(c).takeIf { p.getBoolean("fundo_foto", false) && it.exists() }
        return WallpaperChoice(p.getString("fundo_cor", null) ?: Wallpapers.colors.first().id, p.getBoolean("fundo_desenhos", true), photo)
    }

    fun setColor(c: Context, id: String) = prefs(c).edit().putString("fundo_cor", id).putBoolean("fundo_foto", false).apply()
    fun setDoodles(c: Context, on: Boolean) = prefs(c).edit().putBoolean("fundo_desenhos", on).apply()
    fun setPhoto(c: Context, on: Boolean) = prefs(c).edit().putBoolean("fundo_foto", on).apply()

    /** Muda sempre que a escolha muda (para as telas abertas redesenharem). */
    val version = mutableStateOf(0)
    fun changed() { version.value++ }
}

private fun parseHex(hex: String) = Color(android.graphics.Color.parseColor(hex))

/** Desenha os desenhinhos num "azulejo" que se repete pelo fundo. */
private fun doodleTile(density: Float): ImageBitmap {
    val tilePx = (Wallpapers.TILE * density).toInt().coerceAtLeast(1)
    val img = ImageBitmap(tilePx, tilePx)
    val canvas = androidx.compose.ui.graphics.Canvas(img)
    val color = parseHex(Wallpapers.DOODLE_RGB).copy(alpha = Wallpapers.DOODLE_ALPHA)
    CanvasDrawScope().draw(
        androidx.compose.ui.unit.Density(density), LayoutDirection.Ltr, canvas, Size(tilePx.toFloat(), tilePx.toFloat()),
    ) {
        for (d in Wallpapers.doodles) {
            val path = PathParser().parsePathString(d.path).toPath()
            translate(d.x * density, d.y * density) {
                rotate(d.rotate, pivot = androidx.compose.ui.geometry.Offset(12f * density, 12f * density)) {
                    scale(d.scale * density, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                        drawPath(
                            path, color,
                            style = Stroke(
                                width = Wallpapers.STROKE / d.scale,
                                cap = StrokeCap.Round, join = StrokeJoin.Round,
                            ),
                        )
                    }
                }
            }
        }
    }
    return img
}

/** Fundo da conversa, conforme a escolha da pessoa. [content] fica por cima. */
@Composable
fun ChatBackground(modifier: Modifier = Modifier, choice: WallpaperChoice? = null, content: @Composable BoxScope.() -> Unit = {}) {
    val context = LocalContext.current
    val v by WallpaperPrefs.version
    val c = choice ?: remember(v) { WallpaperPrefs.load(context) }
    val bg = parseHex(Wallpapers.colorById(c.colorId).hex)
    val photo = remember(c.photo, v) {
        c.photo?.let { f -> runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() }
    }
    Box(
        modifier
            .background(bg)
            .drawWithCache {
                val brush = if (photo == null && c.doodles) {
                    ShaderBrush(ImageShader(doodleTile(density), TileMode.Repeated, TileMode.Repeated))
                } else null
                onDrawBehind { if (brush != null) drawRect(brush) }
            },
    ) {
        if (photo != null) {
            Image(photo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // Um véu claro por cima da foto, para as mensagens aparecerem bem.
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.18f)))
        }
        content()
    }
}

/** Tela "Fundo das conversas": cores, desenhos e foto da galeria. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WallpaperScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val v by WallpaperPrefs.version
    val choice = remember(v) { WallpaperPrefs.load(context) }
    var error by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // Guarda uma cópia reduzida (o fundo não precisa da foto inteira).
                    val copied = copyFromUri(context, uri) ?: return@runCatching false
                    try {
                        val b = loadThumbnail(copied.file, 1600) ?: return@runCatching false
                        WallpaperPrefs.photoFile(context).outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                        true
                    } finally {
                        copied.file.delete()
                    }
                }.getOrDefault(false)
            }
            if (ok) { WallpaperPrefs.setPhoto(context, true); WallpaperPrefs.changed(); error = null }
            else error = "Não consegui abrir essa foto. Escolha outra."
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().background(FalaiColors.Primary).statusBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onBack)
            Text("Fundo das conversas", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Prévia com duas mensagens de exemplo.
            ChatBackground(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)), choice) {
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(FalaiColors.BubbleIn).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("Oi! Tudo bem por aí? 😊", fontSize = 15.sp)
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(FalaiColors.BubbleOut).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text("Tudo ótimo! Saudades ❤️", fontSize = 15.sp)
                        }
                    }
                }
            }

            Text("Cor", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (col in Wallpapers.colors) {
                    val selected = choice.photo == null && choice.colorId == col.id
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 64.dp)) {
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))
                                .border(if (selected) 3.dp else 1.dp, if (selected) FalaiColors.Primary else FalaiColors.Divider, RoundedCornerShape(14.dp))
                                .clickable { WallpaperPrefs.setColor(context, col.id); WallpaperPrefs.changed() },
                        ) {
                            ChatBackground(Modifier.fillMaxSize(), WallpaperChoice(col.id, choice.doodles, null))
                        }
                        Text(col.name, fontSize = 12.sp, color = FalaiColors.TextSec)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Desenhinhos no fundo", fontSize = 15.sp)
                    Text("Corações, casinhas, estrelas… bem clarinhos.", fontSize = 13.sp, color = FalaiColors.TextSec)
                }
                Switch(
                    checked = choice.doodles,
                    enabled = choice.photo == null,
                    onCheckedChange = { WallpaperPrefs.setDoodles(context, it); WallpaperPrefs.changed() },
                    colors = SwitchDefaults.colors(checkedTrackColor = FalaiColors.Primary),
                )
            }

            OutlinedButton(
                onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(if (choice.photo != null) "🖼️  Trocar a foto do fundo" else "🖼️  Usar uma foto da galeria") }
            if (choice.photo != null) {
                OutlinedButton(
                    onClick = { WallpaperPrefs.setPhoto(context, false); WallpaperPrefs.changed() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("Voltar para as cores") }
            }
            error?.let { Text(it, color = FalaiColors.Error) }
            Text("O fundo vale só neste aparelho.", fontSize = 13.sp, color = FalaiColors.TextSec)
        }
    }
}
