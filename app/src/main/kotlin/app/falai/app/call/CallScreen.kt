package app.falai.app.call

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.falai.app.ui.Avatar
import app.falai.client.FalaiClient
import app.falai.protocol.CallParticipantDto
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private val CallBg = Color(0xFF0E1C1F)
private val TileBg = Color(0xFF1B2D31)
private val Green = Color(0xFF2E9E5B)
private val Red = Color(0xFFE53935)
private val Sec = Color(0xFFB8CBCD)

/**
 * Pede o microfone (e a câmera, se for vídeo) antes de ligar ou atender.
 * Devolve uma função: chamar(video) { podeLigarComVideo -> ... }.
 */
@Composable
fun rememberCallPermissions(): (video: Boolean, then: (videoOk: Boolean) -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<Boolean, (Boolean) -> Unit>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val p = pending ?: return@rememberLauncherForActivityResult
        pending = null
        val mic = has(context, Manifest.permission.RECORD_AUDIO)
        val cam = has(context, Manifest.permission.CAMERA)
        if (!mic) Toast.makeText(context, "Sem o microfone, você só vai escutar. Dá para liberar em Configurações do Android → Apps → Falaí.", Toast.LENGTH_LONG).show()
        p.second(p.first && cam)
        @Suppress("UNUSED_VARIABLE") val unused = result
    }
    return { video, then ->
        val need = buildList {
            if (!has(context, Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (video && !has(context, Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
        }
        if (need.isEmpty()) then(video) else {
            pending = video to then
            launcher.launch(need.toTypedArray())
        }
    }
}

private fun has(context: Context, p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

/** Por cima de tudo: o toque de quem está ligando, ou a chamada em andamento. */
@Composable
fun CallOverlay(client: FalaiClient, calls: CallManager, answerNow: Boolean?, onAnswerHandled: () -> Unit) {
    val ui by calls.ui.collectAsState()
    val ask = rememberCallPermissions()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        calls.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    val incoming = ui.incoming
    val active = ui.active
    // "Atender" tocado no aviso (com o celular bloqueado ou o app fechado).
    LaunchedEffect(answerNow, incoming?.id) {
        if (answerNow != null && incoming != null) {
            onAnswerHandled()
            ask(answerNow) { v -> calls.accept(v) }
        }
    }

    when {
        active != null -> ActiveCallScreen(client, calls, active)
        incoming != null -> IncomingScreen(
            client,
            callerId = incoming.startedBy,
            video = incoming.video,
            groupTitle = client.store.conversationSummaries(client.account.value?.userId ?: "")
                .firstOrNull { it.id == incoming.conversationId }?.takeIf { it.kind == "group" }?.displayName,
            onDecline = { calls.decline() },
            onAccept = { video -> ask(video) { v -> calls.accept(v) } },
        )
    }
}

@Composable
private fun IncomingScreen(
    client: FalaiClient, callerId: String, video: Boolean, groupTitle: String?,
    onDecline: () -> Unit, onAccept: (video: Boolean) -> Unit,
) {
    BackHandler { /* não fecha sem escolher */ }
    val name = client.store.userName(callerId) ?: "Alguém"
    val avatar = client.store.user(callerId)?.avatarMediaId
    Column(
        Modifier.fillMaxSize().background(CallBg).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text(if (video) "Chamada de vídeo" else "Chamada de voz", color = Sec, fontSize = 16.sp)
        groupTitle?.let { Text("no grupo $it", color = Sec, fontSize = 16.sp) }
        Spacer(Modifier.height(28.dp))
        Avatar(name, size = 140.dp, client = client, mediaId = avatar)
        Spacer(Modifier.height(20.dp))
        Text(name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("está ligando…", color = Sec, fontSize = 17.sp)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            RoundAction("📞", "Recusar", Red, 76.dp, rotate = true, onClick = onDecline)
            if (video) RoundAction("🎤", "Só voz", Color(0xFF136F7A), 76.dp) { onAccept(false) }
            RoundAction(if (video) "🎥" else "📞", "Atender", Green, 76.dp) { onAccept(video) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ActiveCallScreen(client: FalaiClient, calls: CallManager, a: ActiveCall) {
    val context = LocalContext.current
    val ask = rememberCallPermissions()
    val screenLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) calls.startScreen(data)
    }
    // Voltar não desliga: só deixa a chamada rodando por trás (o aviso traz de volta).
    BackHandler { (context as? Activity)?.moveTaskToBack(true) }

    val myId = client.account.value?.userId
    val myDevice = client.account.value?.deviceId
    val others = a.participants.filter { it.deviceId != myDevice }
    val summary = remember(a.conversationId) {
        client.store.conversationSummaries(myId ?: "").firstOrNull { it.id == a.conversationId }
    }
    val title = summary?.displayName ?: "Chamada"

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(a.connectedAt) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val status = when {
        !a.answered -> if (others.isEmpty()) "Chamando…" else "Conectando…"
        a.connectedAt == 0L -> "Conectando…"
        else -> {
            val s = ((now - a.connectedAt) / 1000).coerceAtLeast(0)
            val t = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
            t + (if (a.participants.size > 2) " · ${a.participants.size} pessoas" else "") + (if (others.isEmpty()) " · esperando os outros" else "")
        }
    }

    Column(Modifier.fillMaxSize().background(CallBg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, color = Sec, fontSize = 14.sp)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
            val localTrack = calls.localVideo()
            if (others.size <= 1) {
                // A dois: o outro em tela cheia e eu no cantinho.
                val o = others.firstOrNull()
                if (o == null) {
                    WaitingTile(client, summary?.displayName ?: title, summary?.avatarMediaId, if (a.answered) "Esperando os outros…" else "Chamando…")
                } else {
                    RemoteTile(client, calls, o, a, Modifier.fillMaxSize())
                }
                if (localTrack != null) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(12.dp).size(width = 110.dp, height = 160.dp)
                            .clip(RoundedCornerShape(12.dp)).background(TileBg),
                    ) {
                        VideoView(calls, localTrack, a.videoVersion, mirror = a.camOn && !a.screenOn && a.frontCamera, overlay = true, fit = a.screenOn)
                    }
                }
            } else {
                // Grupo: todos em grade (2 colunas), eu por último.
                val tiles: List<CallParticipantDto?> = others + listOf(null)
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(2).forEach { row ->
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { p ->
                                val m = Modifier.weight(1f).fillMaxSize()
                                if (p != null) RemoteTile(client, calls, p, a, m) else MyTile(client, calls, a, localTrack, m)
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Botões
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 22.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top,
        ) {
            RoundAction(if (a.micOn) "🎤" else "🔇", if (a.micOn) "Microfone" else "Mudo", if (a.micOn) Color(0x33FFFFFF) else Color.White, 56.dp, enabled = a.hasMic) { calls.toggleMic() }
            RoundAction("📷", if (a.camOn) "Câmera" else "Câmera off", if (a.camOn) Color(0x33FFFFFF) else Color.White, 56.dp) {
                if (a.camOn) calls.toggleCamera() else ask(true) { ok -> if (ok) calls.toggleCamera() }
            }
            if (a.camOn) RoundAction("🔄", "Virar", Color(0x33FFFFFF), 56.dp) { calls.switchCamera() }
            RoundAction(if (a.speakerOn) "🔊" else "🔈", if (a.speakerOn) "Viva-voz" else "Ouvido", if (a.speakerOn) Color(0xFF136F7A) else Color(0x33FFFFFF), 56.dp) { calls.toggleSpeaker() }
            RoundAction("📱", if (a.screenOn) "Parar tela" else "Tela", if (a.screenOn) Color(0xFF136F7A) else Color(0x33FFFFFF), 56.dp) {
                if (a.screenOn) calls.stopScreen()
                else {
                    val mpm = context.getSystemService(MediaProjectionManager::class.java)
                    screenLauncher.launch(mpm.createScreenCaptureIntent())
                }
            }
            RoundAction("📞", "Desligar", Red, 56.dp, rotate = true) { calls.hangup() }
        }
    }
}

@Composable
private fun RemoteTile(client: FalaiClient, calls: CallManager, p: CallParticipantDto, a: ActiveCall, modifier: Modifier) {
    val name = client.store.userName(p.userId) ?: "Alguém"
    val track = calls.remoteVideo(p.deviceId)
    val showVideo = track != null && (p.cam || p.screen)
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(TileBg), contentAlignment = Alignment.Center) {
        if (showVideo) VideoView(calls, track!!, a.videoVersion, mirror = false, overlay = false, fit = p.screen)
        else Avatar(name, size = 110.dp, client = client, mediaId = client.store.user(p.userId)?.avatarMediaId)
        val net = when (a.net[p.deviceId]) {
            "ok" -> null
            "reconectando" -> "reconectando…"
            "falhou" -> "sem caminho de rede"
            else -> "conectando…"
        }
        if (net != null) Label(net, Modifier.align(Alignment.TopStart))
        Label(name + (if (!p.mic) " 🔇" else "") + (if (p.screen) " · tela" else ""), Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun MyTile(client: FalaiClient, calls: CallManager, a: ActiveCall, track: VideoTrack?, modifier: Modifier) {
    val me = client.account.value?.userId?.let { client.store.user(it) }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(TileBg), contentAlignment = Alignment.Center) {
        if (track != null) VideoView(calls, track, a.videoVersion, mirror = a.camOn && !a.screenOn && a.frontCamera, overlay = false, fit = a.screenOn)
        else Avatar(me?.name ?: "Eu", size = 90.dp, client = client, mediaId = me?.avatarMediaId)
        Label("Você" + (if (!a.micOn) " 🔇" else "") + (if (a.screenOn) " · mostrando a tela" else ""), Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun WaitingTile(client: FalaiClient, name: String, mediaId: String?, text: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Avatar(name, size = 130.dp, client = client, mediaId = mediaId)
        Spacer(Modifier.height(16.dp))
        Text(text, color = Sec, fontSize = 17.sp)
    }
}

@Composable
private fun Label(text: String, modifier: Modifier) {
    Text(
        text, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x8C000000)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun RoundAction(
    icon: String, label: String, color: Color, size: Dp,
    rotate: Boolean = false, enabled: Boolean = true, onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).clip(CircleShape).background(if (enabled) color else color.copy(alpha = 0.3f))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                icon, fontSize = (size.value * 0.42f).sp,
                modifier = if (rotate) Modifier.rotate(135f) else Modifier,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Sec, fontSize = 12.sp)
    }
}

/** Mostra um vídeo do WebRTC. [version] muda quando a faixa de vídeo muda. */
@Composable
private fun VideoView(calls: CallManager, track: VideoTrack, version: Int, mirror: Boolean, overlay: Boolean, fit: Boolean) {
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(calls.eglBase.eglBaseContext, null)
                setEnableHardwareScaler(true)
                if (overlay) setZOrderMediaOverlay(true)
            }
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = { r ->
            (r.tag as? VideoTrack)?.let { t -> runCatching { t.removeSink(r) } }
            r.tag = null
            r.release()
        },
        update = { r ->
            r.setMirror(mirror)
            r.setScalingType(if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            val old = r.tag as? VideoTrack
            if (old !== track) {
                old?.let { t -> runCatching { t.removeSink(r) } }
                runCatching { track.addSink(r) }
                r.tag = track
            }
            @Suppress("UNUSED_EXPRESSION") version
        },
    )
}
