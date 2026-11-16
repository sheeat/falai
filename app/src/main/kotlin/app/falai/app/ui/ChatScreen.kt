package app.falai.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.falai.protocol.AttachmentDto
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.falai.client.ClientStore
import app.falai.client.FalaiClient
import app.falai.client.LocalMessage
import app.falai.client.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Fotos e áudios até este tamanho baixam sozinhos ao aparecer na tela. Vídeos e arquivos, só no toque. */
private const val AUTO_DOWNLOAD_BYTES = 25L * 1024 * 1024

@Composable
fun ChatScreen(client: FalaiClient, conversationId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val changes by client.changes.collectAsState()
    val state by client.connection.collectAsState()
    val progress by client.uploadProgress.collectAsState()
    val me = client.account.collectAsState().value?.userId ?: return
    val summary = remember(changes) { client.store.conversationSummaries(me).firstOrNull { it.id == conversationId } }
    val messages = remember(changes) { client.store.messages(conversationId) }
    var text by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var chatMenuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val isAdmin = client.account.collectAsState().value?.isAdmin == true
    val other = remember(changes, summary) {
        summary?.takeIf { it.kind == "direct" }?.memberIds?.firstOrNull { it != me }?.let { client.store.user(it) }
    }
    val otherLeft = other?.removed == true
    // A conversa foi apagada (por exemplo, no computador): volta para a lista.
    LaunchedEffect(summary == null) { if (summary == null) onBack() }
    val listState = rememberLazyListState()

    // ---------- Anexar ----------

    fun sendCopied(c: CopiedFile?, captionOverride: String? = null) {
        if (c == null) return
        val kind = kindForMime(c.mime)
        val caption = captionOverride ?: text
        text = ""
        scope.launch {
            try {
                val (dur, thumb) = withContext(Dispatchers.IO) {
                    val d = if (kind == "video" || kind == "audio") durationMs(c.file) else null
                    val t = if (kind == "image" || kind == "video") inlineThumb(c.file, kind) else null
                    d to t
                }
                client.sendMedia(conversationId, c.file, kind, c.mime, c.name, dur, caption = caption, thumb = thumb)
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Não consegui enviar o arquivo.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun copyAndSend(uri: android.net.Uri?) {
        if (uri == null) return
        scope.launch {
            val copied = withContext(Dispatchers.IO) { copyFromUri(context, uri) }
            if (copied == null) Toast.makeText(context, "Não consegui ler o arquivo.", Toast.LENGTH_LONG).show()
            sendCopied(copied)
        }
    }

    // Foto escolhida ou tirada passa antes pelo editor (recortar, desenhar, texto...).
    var editingPhoto by remember { mutableStateOf<CopiedFile?>(null) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val copied = withContext(Dispatchers.IO) { copyFromUri(context, uri) }
            when {
                copied == null -> Toast.makeText(context, "Não consegui ler o arquivo.", Toast.LENGTH_LONG).show()
                isEditableImage(copied.mime) -> editingPhoto = copied
                else -> sendCopied(copied)
            }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { copyAndSend(it) }

    var cameraFile by remember { mutableStateOf<File?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        cameraFile = null
        if (ok && f != null && f.length() > 0) editingPhoto = CopiedFile(f, f.name, "image/jpeg") else f?.delete()
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val f = cameraFile
        cameraFile = null
        if (ok && f != null && f.length() > 0) sendCopied(CopiedFile(f, f.name, "video/mp4")) else f?.delete()
    }

    // ---------- Áudio ----------

    val recorder = remember { AudioRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableStateOf(0L) }
    DisposableEffect(Unit) { onDispose { if (recorder.file != null) recorder.cancel() } }
    LaunchedEffect(recording) {
        while (recording) {
            recordSeconds = (System.currentTimeMillis() - recorder.startedAt) / 1000
            delay(250)
        }
    }
    fun startRecording() {
        try {
            recorder.start()
            recording = true
        } catch (e: Exception) {
            Toast.makeText(context, "Não consegui usar o microfone.", Toast.LENGTH_LONG).show()
        }
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
        else Toast.makeText(context, "Sem permissão do microfone não dá para gravar áudio.", Toast.LENGTH_LONG).show()
    }

    // Sempre mostra a mensagem mais nova ao abrir ou quando chega uma.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // Foto ou vídeo aberto em tela cheia (dentro do app).
    var viewing by remember { mutableStateOf<Pair<File, LocalMessage>?>(null) }

    // ---------- Salvar, encaminhar, compartilhar ----------

    var actionsFor by remember { mutableStateOf<LocalMessage?>(null) }  // menu de quem segurou a mensagem
    var forwardFor by remember { mutableStateOf<LocalMessage?>(null) }  // escolhendo para onde encaminhar
    var saveAfterPermission by remember { mutableStateOf<LocalMessage?>(null) }

    /** Garante o arquivo no aparelho (baixa se precisar) e depois faz [then]. */
    fun withFile(m: LocalMessage, then: (File) -> Unit) {
        val att = m.attachment ?: return
        scope.launch {
            val f = m.localPath?.let(::File)?.takeIf { it.exists() } ?: try {
                if (att.size > AUTO_DOWNLOAD_BYTES) Toast.makeText(context, "Baixando…", Toast.LENGTH_SHORT).show()
                client.mediaFile(att)
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Não consegui baixar.", Toast.LENGTH_LONG).show()
                return@launch
            }
            then(f)
        }
    }

    fun saveNow(m: LocalMessage) {
        val att = m.attachment ?: return
        withFile(m) { f ->
            scope.launch {
                val msg = try {
                    val where = withContext(Dispatchers.IO) { saveToDevice(context, f, att.name, att.mime) }
                    "Salvo no celular, em $where ✔"
                } catch (e: Exception) {
                    "Não consegui salvar: ${e.message ?: "erro"}"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val m = saveAfterPermission
        saveAfterPermission = null
        if (granted && m != null) saveNow(m)
        else if (!granted) Toast.makeText(context, "Sem a permissão não dá para salvar no celular.", Toast.LENGTH_LONG).show()
    }

    fun save(m: LocalMessage) {
        val granted = !needsStoragePermission() || ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) saveNow(m) else {
            saveAfterPermission = m
            askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun share(m: LocalMessage) {
        val att = m.attachment
        if (att == null) shareText(context, m.body) else withFile(m) { f -> shareFile(context, f, att.mime) }
    }

    fun forwardTo(m: LocalMessage, target: app.falai.client.ConversationSummary) {
        try {
            client.forward(target.id, m)
            Toast.makeText(context, "Encaminhado para ${target.displayName} ✔", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, e.message ?: "Não consegui encaminhar.", Toast.LENGTH_LONG).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
    val calls = (LocalContext.current.applicationContext as app.falai.app.FalaiApp).calls
    val callUi by calls.ui.collectAsState()
    val ongoingCall = callUi.byConversation[conversationId]?.takeIf { !it.ended }
    val askCall = app.falai.app.call.rememberCallPermissions()
    CompositionLocalProvider(
        LocalOpenMedia provides { f, m -> viewing = f to m },
        LocalMessageLongPress provides { m -> actionsFor = m },
    ) {
    Column(Modifier.fillMaxSize().background(FalaiColors.ChatBg).imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(FalaiColors.Primary).statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onBack)
            Avatar(summary?.displayName ?: "?", size = 38.dp, client = client, mediaId = summary?.avatarMediaId)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    summary?.displayName ?: "Conversa", color = Color.White, fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (otherLeft) Text("saiu da família", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                else ConnectionBadge(state)
            }
            // Chamadas: voz e vídeo.
            val online = state == app.falai.client.ConnectionState.ONLINE && !otherLeft
            val callBusy = ongoingCall != null
            TextButton(enabled = online && !callBusy, onClick = { askCall(false) { calls.start(conversationId, false) } }) {
                Text("📞", fontSize = 22.sp, color = Color.White.copy(alpha = if (online && !callBusy) 1f else 0.4f))
            }
            TextButton(enabled = online && !callBusy, onClick = { askCall(true) { v -> calls.start(conversationId, v) } }) {
                Text("🎥", fontSize = 22.sp, color = Color.White.copy(alpha = if (online && !callBusy) 1f else 0.4f))
            }
            // Mais opções: apagar a conversa e, para o administrador, tirar a pessoa da família.
            Box {
                TextButton(onClick = { chatMenuOpen = true }) {
                    Text("⋮", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                DropdownMenu(expanded = chatMenuOpen, onDismissRequest = { chatMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("🗑️  Apagar conversa", color = FalaiColors.Error) },
                        onClick = { chatMenuOpen = false; confirmDelete = true },
                    )
                    if (isAdmin && other != null && !other.isAdmin && !other.removed) {
                        DropdownMenuItem(
                            text = { Text("🚫  Tirar ${other.name} da família", color = FalaiColors.Error) },
                            onClick = { chatMenuOpen = false; confirmRemove = true },
                        )
                    }
                }
            }
        }
        // Chamada em andamento nesta conversa (ex.: grupo) e eu fora dela: botão para entrar.
        val call = ongoingCall
        if (call != null && callUi.active?.conversationId != conversationId && call.participants.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().background(FalaiColors.Online).clickable { askCall(false) { calls.join(conversationId, false) } }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "📞  Chamada em andamento (${call.participants.size}) · toque para entrar",
                    color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                )
            }
        }

        val isGroup = summary?.kind == "group"
        ChatBackground(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages, key = { it.id }) { m ->
                Bubble(
                    client, m,
                    mine = m.authorId == me,
                    authorName = if (isGroup) client.store.userName(m.authorId) else null,
                    uploadProgress = progress[m.id],
                )
            }
        }
        }

        if (recording) {
            Row(
                Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("●", color = FalaiColors.Error, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text("Gravando ${ClientStore.formatDuration(recordSeconds * 1000)}", modifier = Modifier.weight(1f))
                TextButton(onClick = { recorder.cancel(); recording = false }) { Text("Cancelar") }
                Button(onClick = {
                    val started = recorder.startedAt
                    val f = recorder.stop()
                    recording = false
                    if (f != null) {
                        scope.launch {
                            client.sendMedia(
                                conversationId, f, "audio", "audio/mp4", "audio.m4a",
                                durationMs = durationMs(f) ?: (System.currentTimeMillis() - started),
                            )
                        }
                    } else {
                        Toast.makeText(context, "Áudio curto demais.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Enviar") }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    TextButton(onClick = { menuOpen = true }) { Text("📎", fontSize = 22.sp) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("🖼️  Foto ou vídeo da galeria") }, onClick = {
                            menuOpen = false
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        })
                        DropdownMenuItem(text = { Text("📷  Tirar foto") }, onClick = {
                            menuOpen = false
                            val f = newOutgoingFile(context, "jpg")
                            cameraFile = f
                            takePhoto.launch(uriFor(context, f))
                        })
                        DropdownMenuItem(text = { Text("🎥  Gravar vídeo") }, onClick = {
                            menuOpen = false
                            val f = newOutgoingFile(context, "mp4")
                            cameraFile = f
                            takeVideo.launch(uriFor(context, f))
                        })
                        DropdownMenuItem(text = { Text("📄  Arquivo") }, onClick = {
                            menuOpen = false
                            pickFile.launch(arrayOf("*/*"))
                        })
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("Mensagem") },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color.White, focusedContainerColor = Color.White,
                    ),
                    maxLines = 5,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                if (text.isBlank()) {
                    Button(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            if (granted) startRecording() else askMic.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        shape = RoundedCornerShape(24.dp),
                    ) { Text("🎤") }
                } else {
                    Button(
                        onClick = {
                            client.send(conversationId, text)
                            text = ""
                        },
                        shape = RoundedCornerShape(24.dp),
                    ) { Text("Enviar") }
                }
            }
        }
    }
    }
    if (confirmDelete && summary != null) {
        DeleteConversationDialog(client, summary) { deleted -> confirmDelete = false; if (deleted) onBack() }
    }
    if (confirmRemove && other != null) {
        RemoveFromFamilyDialog(client, other) { confirmRemove = false }
    }
    editingPhoto?.let { photo ->
        ImageEditor(
            photo, initialCaption = text,
            onCancel = { editingPhoto = null; photo.file.delete() },
            onSend = { ready, caption -> editingPhoto = null; sendCopied(ready, caption) },
        )
    }
    viewing?.let { (f, m) ->
        MediaViewer(
            f, m.attachment!!, onClose = { viewing = null },
            onSave = { save(m) },
            onForward = { forwardFor = m },
            onShare = { share(m) },
        )
    }
    actionsFor?.let { m ->
        MessageActionsDialog(
            m,
            onDismiss = { actionsFor = null },
            onSave = { actionsFor = null; save(m) },
            onForward = { actionsFor = null; forwardFor = m },
            onShare = { actionsFor = null; share(m) },
            onCopy = {
                actionsFor = null
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Falaí", m.body))
                Toast.makeText(context, "Texto copiado", Toast.LENGTH_SHORT).show()
            },
        )
    }
    forwardFor?.let { m ->
        ForwardDialog(
            client, me,
            onDismiss = { forwardFor = null },
            onPick = { target -> forwardFor = null; forwardTo(m, target) },
        )
    }
    }
}

/** Abre foto/vídeo em tela cheia; quem fornece é a tela da conversa. */
private val LocalOpenMedia = staticCompositionLocalOf<(File, LocalMessage) -> Unit> { { _, _ -> } }

/** Segurar o dedo numa mensagem abre o menu (salvar, encaminhar, compartilhar). */
private val LocalMessageLongPress = staticCompositionLocalOf<(LocalMessage) -> Unit> { { } }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Bubble(client: FalaiClient, m: LocalMessage, mine: Boolean, authorName: String?, uploadProgress: Float?) {
    val longPress = LocalMessageLongPress.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (mine) FalaiColors.BubbleOut else FalaiColors.BubbleIn)
                .combinedClickable(onClick = {}, onLongClick = { longPress(m) })
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            if (!mine && authorName != null) {
                Text(authorName, color = FalaiColors.Primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            if (m.attachment != null) {
                AttachmentView(client, m)
                if (uploadProgress != null && m.status == MessageStatus.PENDING) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { uploadProgress }, modifier = Modifier.width(200.dp))
                    Text("Enviando ${(uploadProgress * 100).toInt()}%", fontSize = 11.sp, color = FalaiColors.TextSec)
                }
            }
            if (m.body.isNotEmpty()) Text(m.body, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 2.dp))
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(m.createdAt), color = FalaiColors.TextSec, fontSize = 11.sp)
                if (mine) {
                    Spacer(Modifier.width(4.dp))
                    Text(statusIcon(m.status), fontSize = 11.sp, color = FalaiColors.TextSec)
                }
            }
        }
    }
}

/** Mostra o anexo: baixa quando precisa e confere que chegou idêntico. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AttachmentView(client: FalaiClient, m: LocalMessage) {
    val att = m.attachment ?: return
    val context = LocalContext.current
    val openMedia = LocalOpenMedia.current
    val longPress = LocalMessageLongPress.current
    // Foto e vídeo abrem aqui dentro; os outros arquivos, no app do celular que sabe abrir.
    fun open(f: File) {
        if (att.kind == "image" || att.kind == "video") openMedia(f, m) else openExternally(context, f, att.mime)
    }
    val scope = rememberCoroutineScope()
    var file by remember(m.id, m.localPath) { mutableStateOf(m.localPath?.let(::File)?.takeIf { it.exists() }) }
    var downloading by remember(m.id) { mutableStateOf(false) }
    var error by remember(m.id) { mutableStateOf<String?>(null) }

    fun download(thenOpen: Boolean) {
        if (downloading || att.mediaId.isEmpty()) return
        downloading = true
        error = null
        scope.launch {
            try {
                val f = client.mediaFile(att)
                file = f
                if (thenOpen) open(f)
            } catch (e: Exception) {
                error = e.message ?: "Não consegui baixar."
            } finally {
                downloading = false
            }
        }
    }

    // Fotos e áudios pequenos baixam sozinhos (só quando aparecem na tela).
    LaunchedEffect(m.id, att.mediaId) {
        if (file == null && (att.kind == "image" || att.kind == "audio") && att.size <= AUTO_DOWNLOAD_BYTES) download(false)
    }

    when (att.kind) {
        "image", "video" -> {
            val f = file
            val isVideo = att.kind == "video"
            // Prévia: primeiro a miniatura que veio junto com a mensagem (aparece na hora);
            // depois que o arquivo é baixado, a imagem em boa qualidade.
            val inline = remember(att.thumb) { decodeInlineThumb(att.thumb) }
            var full by remember(f) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(f) {
                if (f != null) full = withContext(Dispatchers.IO) {
                    if (isVideo) videoThumbnail(f) else loadThumbnail(f)
                }
            }
            val shown = full ?: inline
            val ratio = shown?.let { (it.width.toFloat() / it.height).coerceIn(0.6f, 1.8f) } ?: 1.4f
            Box(
                Modifier
                    .width(260.dp)
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2C3638))
                    .combinedClickable(onLongClick = { longPress(m) }) { if (f != null) open(f) else download(true) },
                contentAlignment = Alignment.Center,
            ) {
                if (shown != null) {
                    Image(
                        shown.asImageBitmap(), contentDescription = if (isVideo) "Vídeo" else "Foto",
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(if (isVideo) "🎥" else "📷", fontSize = 40.sp)
                }
                // Botão no meio: baixar, carregando ou "play" do vídeo.
                when {
                    downloading -> CircularProgressIndicator(Modifier.size(40.dp), color = Color.White)
                    f == null || isVideo -> Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (f == null) "⬇" else "▶", color = Color.White, fontSize = 24.sp) }
                }
                // Etiqueta no canto: tipo, extensão, duração e tamanho.
                val info = buildString {
                    append(if (isVideo) "🎥 " else "📷 ")
                    append(ClientStore.typeLabel(att.name, att.mime))
                    att.durationMs?.takeIf { isVideo }?.let { append(" · ").append(ClientStore.formatDuration(it)) }
                    append(" · ").append(formatSize(att.size))
                }
                Text(
                    info, color = Color.White, fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        "audio" -> AudioPlayer(file, att.durationMs, downloading) { download(false) }
        else -> Row(
            Modifier
                .width(260.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.05f))
                .combinedClickable(onLongClick = { longPress(m) }) { val f = file; if (f != null) open(f) else download(true) }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Selo com a extensão (APK, PDF, ZIP…), para saber o que é sem abrir.
            val label = ClientStore.typeLabel(att.name, att.mime)
            Box(
                Modifier.size(width = 46.dp, height = 52.dp).clip(RoundedCornerShape(6.dp)).background(FalaiColors.Primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = Color.White, fontSize = if (label.length > 4) 10.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(att.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    "$label · " + formatSize(att.size) + if (file == null) " · toque para baixar" else "",
                    fontSize = 12.sp, color = FalaiColors.TextSec,
                )
            }
            if (downloading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
    error?.let { Text(it, color = FalaiColors.Error, fontSize = 12.sp) }
}

@Composable
private fun AudioPlayer(file: File?, durationMs: Long?, downloading: Boolean, onDownload: () -> Unit) {
    var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(file) { mutableStateOf(false) }
    var positionMs by remember(file) { mutableStateOf(0L) }
    DisposableEffect(file) { onDispose { player?.release(); player = null } }
    LaunchedEffect(playing) {
        while (playing) {
            positionMs = player?.currentPosition?.toLong() ?: 0
            delay(200)
        }
    }
    Row(Modifier.width(240.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                if (file == null) { onDownload(); return@Button }
                val p = player ?: MediaPlayer().apply {
                    setDataSource(file.path)
                    prepare()
                    setOnCompletionListener { playing = false; positionMs = 0; seekTo(0) }
                }.also { player = it }
                if (playing) { p.pause(); playing = false } else { p.start(); playing = true }
            },
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(44.dp),
        ) {
            if (downloading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(if (playing) "❚❚" else "▶", fontSize = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        val total = durationMs ?: 0
        Column(Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { if (total > 0) (positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "🎤 " + ClientStore.formatDuration(if (playing || positionMs > 0) positionMs else total),
                fontSize = 12.sp, color = FalaiColors.TextSec,
            )
        }
    }
}
