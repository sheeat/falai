package app.falai.app.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * As contas do "ajustar a foto de perfil", sem nada de tela (para dar para testar).
 *
 * A foto aparece atrás de um círculo de [diameter] pontos. [zoom] = 1 é a foto cobrindo o círculo
 * inteiro (o lado menor da foto do tamanho do círculo); menos que 1 afasta (sobra um fundo branco),
 * até a foto inteira caber no círculo. [offsetX]/[offsetY] = onde está o centro da foto em relação
 * ao centro do círculo.
 */
object AvatarCropMath {
    const val MAX_ZOOM = 5f
    const val OUTPUT = 512

    fun baseScale(w: Int, h: Int, diameter: Float) = diameter / min(w, h)

    /** Até onde dá para afastar: a foto inteira dentro do círculo. */
    fun minZoom(w: Int, h: Int) = min(w, h).toFloat() / max(w, h)

    /** Não deixa a foto "fugir": se ela é maior que o círculo, o círculo fica sempre coberto; se é menor, ela fica dentro dele. */
    fun clampOffset(w: Int, h: Int, diameter: Float, zoom: Float, ox: Float, oy: Float): Pair<Float, Float> {
        val s = baseScale(w, h, diameter) * zoom
        val lx = abs(w * s - diameter) / 2f
        val ly = abs(h * s - diameter) / 2f
        return ox.coerceIn(-lx, lx) to oy.coerceIn(-ly, ly)
    }

    /**
     * Zoom com dois dedos em volta do ponto [cx],[cy] (relativo ao centro do círculo), mais o arrasto.
     * Devolve (zoom, offsetX, offsetY) já dentro dos limites.
     */
    fun gesture(
        w: Int, h: Int, diameter: Float, zoom: Float, ox: Float, oy: Float,
        cx: Float, cy: Float, panX: Float, panY: Float, zoomChange: Float,
    ): Triple<Float, Float, Float> {
        val nz = (zoom * zoomChange).coerceIn(minZoom(w, h), MAX_ZOOM)
        val f = nz / zoom
        val nx = (ox - cx) * f + cx + panX
        val ny = (oy - cy) * f + cy + panY
        val (x, y) = clampOffset(w, h, diameter, nz, nx, ny)
        return Triple(nz, x, y)
    }

    /** O quadrado da foto (em pixels da foto) que está dentro do círculo: esquerda, topo, lado. Pode passar da borda. */
    fun sourceSquare(w: Int, h: Int, diameter: Float, zoom: Float, ox: Float, oy: Float): Triple<Float, Float, Float> {
        val s = baseScale(w, h, diameter) * zoom
        val left = w / 2f - (diameter / 2f + ox) / s
        val top = h / 2f - (diameter / 2f + oy) / s
        return Triple(left, top, diameter / s)
    }
}

/** Recorta [bmp] no quadrado escolhido e salva 512×512 em JPEG (o que passa da borda fica branco). */
fun saveCroppedAvatar(context: Context, bmp: Bitmap, left: Float, top: Float, side: Float): File? = runCatching {
    val n = AvatarCropMath.OUTPUT
    val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawColor(android.graphics.Color.WHITE)
    val m = android.graphics.Matrix()
    m.setTranslate(-left, -top)
    m.postScale(n / side, n / side)
    canvas.drawBitmap(bmp, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG))
    val file = newOutgoingFile(context, "jpg")
    file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    out.recycle()
    file
}.getOrNull()

private fun rotated90(b: Bitmap): Bitmap {
    val m = android.graphics.Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
}

/**
 * Tela "Ajustar a foto": a foto atrás de um círculo; arrasta com um dedo, aproxima e afasta com
 * dois dedos (ou com a barrinha), gira de 90 em 90. "Usar foto" devolve o arquivo pronto.
 */
@Composable
fun AvatarCropScreen(source: Bitmap, onCancel: () -> Unit, onDone: (left: Float, top: Float, side: Float, bitmap: Bitmap) -> Unit) {
    var bmp by remember { mutableStateOf(source) }
    val image = remember(bmp) { bmp.asImageBitmap() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    var diameter by remember { mutableFloatStateOf(0f) }

    BackHandler(onBack = onCancel)

    Column(Modifier.fillMaxSize().background(Color(0xFF111111))) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("✕ Cancelar", color = Color.White, fontSize = 16.sp) }
            Text(
                "Ajustar a foto", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                bmp = rotated90(bmp)
                zoom = 1f; ox = 0f; oy = 0f
            }) { Text("⟳ Girar", color = Color.White, fontSize = 16.sp) }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            val density = LocalDensity.current
            val d = with(density) { (min(maxWidth.toPx(), maxHeight.toPx()) - 40.dp.toPx()).coerceAtLeast(100f) }
            diameter = d
            val w = bmp.width
            val h = bmp.height
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(bmp, d) {
                        detectTransformGestures { centroid, pan, zoomChange, _ ->
                            val cx = centroid.x - size.width / 2f
                            val cy = centroid.y - size.height / 2f
                            val (z, x, y) = AvatarCropMath.gesture(w, h, d, zoom, ox, oy, cx, cy, pan.x, pan.y, zoomChange)
                            zoom = z; ox = x; oy = y
                        }
                    }
                    .pointerInput(bmp) {
                        // Dois toques: volta ao começo.
                        detectTapGestures(onDoubleTap = { zoom = 1f; ox = 0f; oy = 0f })
                    },
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val circle = Rect(center, d / 2f)
                // Fundo branco dentro do círculo (é o que fica na foto quando afasta).
                drawCircle(Color.White, d / 2f, center)
                val s = AvatarCropMath.baseScale(w, h, d) * zoom
                val iw = w * s
                val ih = h * s
                drawImage(
                    image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(w, h),
                    dstOffset = IntOffset((center.x + ox - iw / 2f).roundToInt(), (center.y + oy - ih / 2f).roundToInt()),
                    dstSize = IntSize(iw.roundToInt().coerceAtLeast(1), ih.roundToInt().coerceAtLeast(1)),
                )
                // Escurece fora do círculo.
                val shade = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, Size(size.width, size.height)))
                    addOval(circle)
                }
                drawPath(shade, Color.Black.copy(alpha = 0.62f))
                drawCircle(Color.White, d / 2f, center, style = Stroke(width = 2.dp.toPx()))
            }
        }

        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Arraste a foto com o dedo. Use dois dedos (ou a barrinha) para aproximar ou afastar.",
                color = Color(0xFFBBBBBB), fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("➖", fontSize = 16.sp, color = Color.White)
                Spacer(Modifier.width(8.dp))
                val minZ = AvatarCropMath.minZoom(bmp.width, bmp.height)
                Slider(
                    value = zoom,
                    onValueChange = { z ->
                        val nz = z.coerceIn(minZ, AvatarCropMath.MAX_ZOOM)
                        val f = nz / zoom
                        val (x, y) = AvatarCropMath.clampOffset(bmp.width, bmp.height, diameter, nz, ox * f, oy * f)
                        zoom = nz; ox = x; oy = y
                    },
                    valueRange = minZ..AvatarCropMath.MAX_ZOOM,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = FalaiColors.Primary),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text("➕", fontSize = 16.sp, color = Color.White)
            }
            Button(
                onClick = {
                    val (l, t, side) = AvatarCropMath.sourceSquare(bmp.width, bmp.height, diameter, zoom, ox, oy)
                    onDone(l, t, side, bmp)
                },
                colors = ButtonDefaults.buttonColors(containerColor = FalaiColors.Primary),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Usar esta foto", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}
