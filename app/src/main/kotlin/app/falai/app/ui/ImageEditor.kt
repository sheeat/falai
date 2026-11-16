package app.falai.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Um texto ou emoji ainda solto em cima da foto (dá para arrastar e mudar o tamanho). Posição e tamanho em pixels da foto. */
private class EdItem(text: String, val emoji: Boolean, color: Color, x: Float, y: Float, size: Float) {
    var text by mutableStateOf(text)
    var color by mutableStateOf(color)
    var x by mutableFloatStateOf(x)
    var y by mutableFloatStateOf(y)
    var size by mutableFloatStateOf(size)
}

private val ED_COLORS = listOf(
    Color.White, Color.Black, Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835),
    Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFEC407A),
)
private val ED_EMOJIS = ("😀 😂 🥰 😍 😎 😢 😮 😡 👍 👎 👏 🙏 💪 🎉 ❤️ 💔 🔥 ⭐ ✅ ❌ ❓ ❗ 👉 👈 👆 👇 " +
    "➡️ ⬅️ ⬆️ ⬇️ ⭕ 💯 🎂 🎁 ☀️ 🌧️ 🐶 🐱 🌹 🍕 ⚽ 🚗 🏠 📍 ⏰ 💰").split(" ")

/** Fotos que dá para editar antes de mandar. */
fun isEditableImage(mime: String): Boolean =
    mime == "image/jpeg" || mime == "image/png" || mime == "image/webp" || mime == "image/heic" || mime == "image/heif"

/** Muda o retângulo de recorte conforme o dedo arrasta um canto ("nw", "ne", "sw", "se") ou o meio ("move"). */
internal fun adjustCrop(start: Rect, mode: String, dx: Float, dy: Float, maxW: Float, maxH: Float, min: Float): Rect {
    if (mode == "move") {
        val x = (start.left + dx).coerceIn(0f, maxW - start.width)
        val y = (start.top + dy).coerceIn(0f, maxH - start.height)
        return Rect(x, y, x + start.width, y + start.height)
    }
    var l = start.left; var t = start.top; var r = start.right; var b = start.bottom
    if ('w' in mode) l = (l + dx).coerceIn(0f, r - min)
    if ('e' in mode) r = (r + dx).coerceIn(l + min, maxW)
    if ('n' in mode) t = (t + dy).coerceIn(0f, b - min)
    if ('s' in mode) b = (b + dy).coerceIn(t + min, maxH)
    return Rect(l, t, r, b)
}

/**
 * Editor de foto antes de enviar, como no WhatsApp: recortar, girar, desenhar, escrever e pôr emoji.
 * Se a pessoa não mexer em nada, a foto original vai do jeito que estava.
 */
@Composable
fun ImageEditor(
    source: CopiedFile,
    initialCaption: String,
    onCancel: () -> Unit,
    onSend: (CopiedFile, String) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var version by remember { mutableIntStateOf(0) } // muda a cada risco, para redesenhar
    val undo = remember { mutableStateListOf<Bitmap>() }
    var dirty by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf<String?>(null) }
    var color by remember { mutableStateOf(ED_COLORS[2]) }
    val items = remember { mutableStateListOf<EdItem>() }
    var selected by remember { mutableStateOf<EdItem?>(null) }
    var editing by remember { mutableStateOf<EdItem?>(null) }
    var crop by remember { mutableStateOf<Rect?>(null) }
    var caption by remember { mutableStateOf(initialCaption) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(source) {
        val b = withContext(Dispatchers.IO) {
            runCatching { loadThumbnail(source.file, ImageEditOps.MAX_SIDE)?.let(ImageEditOps::editable) }.getOrNull()
        }
        // Não deu para abrir (formato estranho): manda como está.
        if (b == null) onSend(source, caption) else bmp = b
    }

    fun snapshot() {
        val b = bmp ?: return
        undo.add(ImageEditOps.copy(b))
        if (undo.size > 6) undo.removeAt(0)
    }

    /** Grava os textos e emojis soltos na foto. */
    fun bake() {
        val b = bmp ?: return
        val live = items.filter { it.text.isNotBlank() }
        if (live.isNotEmpty()) {
            snapshot()
            live.forEach { ImageEditOps.drawText(b, it.text, it.x, it.y, it.size, it.color.toArgb(), it.emoji) }
            version++
        }
        items.clear()
        selected = null
    }

    fun selectTool(t: String?) {
        if (t != "text" && t != "emoji") bake()
        tool = if (tool == t) null else t
        val b = bmp
        crop = if (tool == "crop" && b != null) Rect(0f, 0f, b.width.toFloat(), b.height.toFloat()) else null
    }

    fun applyCrop() {
        val b = bmp ?: return
        val c = crop ?: return
        val x = c.left.roundToInt(); val y = c.top.roundToInt()
        val w = c.width.roundToInt(); val h = c.height.roundToInt()
        if (w < b.width || h < b.height) {
            snapshot()
            bmp = ImageEditOps.crop(b, x, y, w, h)
            dirty = true
        }
        crop = null
        tool = null
    }

    fun rotate() {
        bake()
        val b = bmp ?: return
        snapshot()
        bmp = ImageEditOps.rotate90(b)
        dirty = true
        if (tool == "crop") { tool = null; crop = null }
    }

    fun undoLast() {
        if (items.isNotEmpty()) {
            val it = items.removeAt(items.lastIndex)
            if (selected == it) selected = null
            return
        }
        if (undo.isEmpty()) return
        bmp = undo.removeAt(undo.lastIndex)
        version++
        if (tool == "crop") crop = bmp?.let { Rect(0f, 0f, it.width.toFloat(), it.height.toFloat()) }
    }

    fun send() {
        if (busy) return
        if (tool == "crop") applyCrop()
        bake()
        val b = bmp
        if (!dirty || b == null) {
            onSend(source, caption.trim())
            return
        }
        busy = true
        scope.launch {
            val png = source.mime == "image/png"
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    newOutgoingFile(context, if (png) "png" else "jpg").also { ImageEditOps.save(b, it, png) }
                }.getOrNull()
            }
            if (out == null) {
                busy = false
                android.widget.Toast.makeText(context, "Não consegui preparar a foto.", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            source.file.delete()
            val base = source.name.substringBeforeLast('.').ifBlank { "foto" }
            onSend(CopiedFile(out, base + if (png) ".png" else ".jpg", if (png) "image/png" else "image/jpeg"), caption.trim())
        }
    }

    BackHandler {
        when {
            editing != null -> editing = null
            tool != null -> selectTool(null)
            else -> onCancel()
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF111111)).imePadding()) {
        // ---- Barra de ferramentas ----
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EdToolButton("✕", "Cancelar", on = false, onClick = onCancel)
            Spacer(Modifier.width(4.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                EdToolButton("✂", "Recortar", on = tool == "crop") { selectTool("crop") }
                EdToolButton("⟳", "Girar", on = false) { rotate() }
                EdToolButton("✏️", "Desenhar", on = tool == "draw") { selectTool("draw") }
                EdToolButton("T", "Texto", on = tool == "text") { selectTool("text") }
                EdToolButton("😀", "Emoji", on = tool == "emoji") { selectTool("emoji") }
                EdToolButton("↶", "Desfazer", on = false, enabled = items.isNotEmpty() || undo.isNotEmpty()) { undoLast() }
            }
        }

        // ---- Opções da ferramenta ----
        val sel = selected
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (tool == "draw" || tool == "text") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ED_COLORS.forEach { c ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(c)
                                .border(if (c == color) 3.dp else 1.dp, if (c == color) Color.White else Color.Gray, CircleShape)
                                .clickable {
                                    color = c
                                    selected?.let { if (!it.emoji) it.color = c }
                                },
                        )
                    }
                }
                Text(
                    if (tool == "draw") "Passe o dedo na foto para desenhar." else "Toque na foto para escrever. Arraste o texto para mudar de lugar.",
                    color = Color(0xFFBBBBBB), fontSize = 13.sp, textAlign = TextAlign.Center,
                )
            }
            if (tool == "emoji") {
                LazyVerticalGrid(GridCells.Adaptive(46.dp), Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
                    items(ED_EMOJIS) { em ->
                        Box(
                            Modifier.size(46.dp).clickable {
                                val b = bmp ?: return@clickable
                                val it = EdItem(em, true, Color.Black, b.width / 2f, b.height / 2f, min(b.width, b.height) / 6f)
                                items.add(it); selected = it; dirty = true
                            },
                            contentAlignment = Alignment.Center,
                        ) { Text(em, fontSize = 26.sp) }
                    }
                }
            }
            if (tool == "crop") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text("Arraste os cantos.", color = Color(0xFFBBBBBB), fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    EdPill("✓ Aplicar recorte", strong = true) { applyCrop() }
                }
            }
            if (sel != null && items.contains(sel)) {
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EdPill("A−") { sel.size = (sel.size / 1.2f).coerceAtLeast(10f) }
                    EdPill("A+") { sel.size = (sel.size * 1.2f).coerceAtMost(4000f) }
                    if (!sel.emoji) EdPill("✏ Mudar texto") { editing = sel }
                    EdPill("🗑 Tirar") { items.remove(sel); selected = null }
                }
            }
        }

        // ---- A foto ----
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            val b = bmp
            if (b == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                return@BoxWithConstraints
            }
            val pad = with(density) { 12.dp.toPx() }
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val scale = min((w - 2 * pad) / b.width, (h - 2 * pad) / b.height).coerceAtLeast(0.01f)
            val left = (w - b.width * scale) / 2f
            val top = (h - b.height * scale) / 2f
            fun toImg(p: Offset) = Offset((p.x - left) / scale, (p.y - top) / scale)
            val handleRadius = with(density) { 36.dp.toPx() }
            val penWidth = ImageEditOps.strokeWidth(b)

            Canvas(
                Modifier.fillMaxSize().pointerInput(tool, b, scale) {
                    when (tool) {
                        "draw" -> {
                            var last: Offset? = null
                            detectDragGestures(
                                onDragStart = { p ->
                                    snapshot()
                                    val q = toImg(p)
                                    ImageEditOps.drawDot(b, q.x, q.y, color.toArgb(), penWidth)
                                    last = q; dirty = true; version++
                                },
                                onDragEnd = { last = null },
                                onDragCancel = { last = null },
                            ) { change, _ ->
                                change.consume()
                                val q = toImg(change.position)
                                last?.let { ImageEditOps.drawLine(b, it.x, it.y, q.x, q.y, color.toArgb(), penWidth) }
                                last = q
                                version++
                            }
                        }
                        "text" -> detectTapGestures { p ->
                            val q = toImg(p)
                            val it = EdItem("", false, color, q.x.coerceIn(0f, b.width.toFloat()), q.y.coerceIn(0f, b.height.toFloat()), maxOf(b.width, b.height) / 16f)
                            items.add(it); selected = it; editing = it
                        }
                        "crop" -> {
                            var mode: String? = null
                            var start = Rect.Zero
                            var acc = Offset.Zero
                            detectDragGestures(
                                onDragStart = { p ->
                                    val r = crop
                                    mode = null
                                    if (r != null) {
                                        val corners = listOf("nw" to r.topLeft, "ne" to r.topRight, "sw" to r.bottomLeft, "se" to r.bottomRight)
                                        val near = corners.minByOrNull { (_, c) -> hypot(left + c.x * scale - p.x, top + c.y * scale - p.y) }!!
                                        val dist = hypot(left + near.second.x * scale - p.x, top + near.second.y * scale - p.y)
                                        mode = if (dist <= handleRadius) near.first else if (r.contains(toImg(p))) "move" else null
                                        start = r
                                        acc = Offset.Zero
                                    }
                                },
                                onDragEnd = { mode = null },
                                onDragCancel = { mode = null },
                            ) { change, amount ->
                                val m = mode ?: return@detectDragGestures
                                change.consume()
                                acc += amount / scale
                                val minSide = maxOf(20f, min(b.width, b.height) * 0.05f)
                                crop = adjustCrop(start, m, acc.x, acc.y, b.width.toFloat(), b.height.toFloat(), minSide)
                            }
                        }
                        else -> detectTapGestures { selected = null }
                    }
                },
            ) {
                @Suppress("UNUSED_EXPRESSION") version // redesenha quando a foto muda
                drawImage(
                    b.asImageBitmap(),
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize((b.width * scale).roundToInt(), (b.height * scale).roundToInt()),
                )
                crop?.let { r ->
                    val s = Rect(left + r.left * scale, top + r.top * scale, left + r.right * scale, top + r.bottom * scale)
                    val dim = Color.Black.copy(alpha = 0.6f)
                    drawRect(dim, Offset(0f, 0f), Size(size.width, s.top))
                    drawRect(dim, Offset(0f, s.bottom), Size(size.width, size.height - s.bottom))
                    drawRect(dim, Offset(0f, s.top), Size(s.left, s.height))
                    drawRect(dim, Offset(s.right, s.top), Size(size.width - s.right, s.height))
                    val thin = Color.White.copy(alpha = 0.5f)
                    for (i in 1..2) {
                        val gx = s.left + s.width * i / 3f
                        val gy = s.top + s.height * i / 3f
                        drawLine(thin, Offset(gx, s.top), Offset(gx, s.bottom), 1.dp.toPx())
                        drawLine(thin, Offset(s.left, gy), Offset(s.right, gy), 1.dp.toPx())
                    }
                    drawRect(Color.White, s.topLeft, s.size, style = Stroke(2.dp.toPx()))
                    listOf(s.topLeft, s.topRight, s.bottomLeft, s.bottomRight).forEach { c ->
                        drawCircle(Color.White, 11.dp.toPx(), c)
                        drawCircle(FalaiColors.Primary, 11.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                    }
                }
            }

            items.forEach { item ->
                EdItemView(
                    item, scale, left, top,
                    isSelected = selected == item,
                    onSelect = { selected = item },
                    onEdit = { if (!item.emoji) editing = item },
                )
            }

            if (busy) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }

        // ---- Legenda e enviar ----
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                placeholder = { Text("Escreva uma legenda (opcional)") },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White, unfocusedBorderColor = Color.Gray,
                    focusedPlaceholderColor = Color.Gray, unfocusedPlaceholderColor = Color.Gray,
                    cursorColor = Color.White,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(54.dp).clip(CircleShape).background(FalaiColors.Primary)
                    .clickable(enabled = !busy && bmp != null) { send() },
                contentAlignment = Alignment.Center,
            ) { Text("➤", color = Color.White, fontSize = 24.sp) }
        }
    }

    // ---- Escrever / mudar o texto ----
    editing?.let { item ->
        var value by remember(item) { mutableStateOf(item.text) }
        val focus = remember(item) { FocusRequester() }
        fun close(save: Boolean) {
            if (save) item.text = value.trim()
            if (item.text.isBlank()) { items.remove(item); if (selected == item) selected = null } else dirty = true
            editing = null
        }
        AlertDialog(
            onDismissRequest = { close(false) },
            title = { Text("Escrever na foto") },
            text = {
                Column {
                    OutlinedTextField(
                        value = value, onValueChange = { value = it.take(200) },
                        minLines = 2, maxLines = 5,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ED_COLORS.forEach { c ->
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(c)
                                    .border(if (c == item.color) 3.dp else 1.dp, if (c == item.color) FalaiColors.Primary else Color.Gray, CircleShape)
                                    .clickable { item.color = c; color = c },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { close(true) }) { Text("Pronto") } },
            dismissButton = { TextButton(onClick = { close(false) }) { Text("Cancelar") } },
        )
        LaunchedEffect(item) { runCatching { focus.requestFocus() } }
    }
}

@Composable
private fun EdItemView(
    item: EdItem,
    scale: Float,
    left: Float,
    top: Float,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    if (item.text.isBlank()) return
    var box by remember { mutableStateOf(IntSize.Zero) }
    // Tocar no texto já selecionado abre para mudar o texto; tocar em outro só seleciona.
    val selectedNow = rememberUpdatedState(isSelected)
    val fontSize = with(LocalDensity.current) { (item.size * scale).toSp() }
    Box(
        Modifier
            .offset {
                IntOffset(
                    (left + item.x * scale - box.width / 2f).roundToInt(),
                    (top + item.y * scale - box.height / 2f).roundToInt(),
                )
            }
            .onSizeChanged { box = it }
            .then(if (isSelected) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
            .pointerInput(item, scale) {
                detectTapGestures(
                    onTap = { if (selectedNow.value) onEdit() else onSelect() },
                    onDoubleTap = { onEdit() },
                )
            }
            .pointerInput(item, scale) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onSelect()
                    item.x += pan.x / scale
                    item.y += pan.y / scale
                    item.size = (item.size * zoom).coerceIn(10f, 4000f)
                }
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (!item.emoji) {
            Text(
                item.text, fontSize = fontSize, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                lineHeight = fontSize * 1.15f,
                color = if (item.color == Color.Black) Color.White else Color.Black,
                style = TextStyle(drawStyle = Stroke(width = with(LocalDensity.current) { (item.size * scale / 8f).coerceAtLeast(2f) })),
            )
        }
        Text(
            item.text, fontSize = fontSize, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            lineHeight = fontSize * 1.15f,
            color = if (item.emoji) Color.Black else item.color,
            overflow = TextOverflow.Visible,
        )
    }
}

@Composable
private fun EdToolButton(icon: String, label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (on) FalaiColors.Primary else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .width(52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, fontSize = 20.sp, color = if (enabled) Color.White else Color.Gray)
        Text(label, fontSize = 10.sp, color = if (enabled) Color.White else Color.Gray, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun EdPill(text: String, strong: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(18.dp))
            .background(if (strong) FalaiColors.Primary else Color(0xFF333333))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}
