package app.falai.app.call

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.projection.MediaProjection
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.WindowManager
import app.falai.client.FalaiClient
import app.falai.protocol.CallDeclineFrame
import app.falai.protocol.CallDto
import app.falai.protocol.CallLeaveFrame
import app.falai.protocol.CallMediaFrame
import app.falai.protocol.CallParticipantDto
import app.falai.protocol.CallRingFrame
import app.falai.protocol.CallSignalFrame
import app.falai.protocol.CallSignalInFrame
import app.falai.protocol.CallStateFrame
import app.falai.protocol.FalaiJson
import app.falai.protocol.IceServerDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** O que a tela da chamada mostra. Muda a cada novidade (a tela redesenha). */
data class CallUi(
    /** Alguém está ligando para mim. */
    val incoming: CallDto? = null,
    /** A chamada em que eu estou. */
    val active: ActiveCall? = null,
    /** Chamadas em andamento por conversa (para o botão "Entrar na chamada"). */
    val byConversation: Map<String, CallDto> = emptyMap(),
)

data class ActiveCall(
    val callId: String?,
    val conversationId: String,
    val video: Boolean,
    val participants: List<CallParticipantDto>,
    /** Alguém já atendeu. */
    val answered: Boolean,
    /** Quando o som começou a passar (0 = ainda não). Para o cronômetro. */
    val connectedAt: Long,
    /** Estado da ligação com cada aparelho: "conectando", "ok", "reconectando", "falhou". */
    val net: Map<String, String>,
    val micOn: Boolean,
    val hasMic: Boolean,
    val camOn: Boolean,
    val frontCamera: Boolean,
    val screenOn: Boolean,
    val speakerOn: Boolean,
    /** Muda quando um vídeo novo chega (a tela liga o vídeo de novo). */
    val videoVersion: Int,
)

/**
 * Chamadas de voz e vídeo no celular (WebRTC). O servidor apresenta os aparelhos; som e imagem
 * vão direto entre eles. Em grupo, cada um liga para cada um. Quem entra depois liga para
 * quem já estava (assim nunca há duas ofertas cruzadas).
 *
 * Tudo que mexe nas ligações roda numa linha só ([dispatcher]), em ordem.
 */
class CallManager(private val app: Application, private val client: FalaiClient, private val scope: CoroutineScope) {

    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "falai-call") }.asCoroutineDispatcher()

    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Avisos curtos para mostrar ("Chamada recusada." etc.). */
    val messages: SharedFlow<String> = _messages

    /** A tela do app está aberta (se não, o toque aparece como notificação). */
    @Volatile var appVisible = false

    // ---------- WebRTC: criado na primeira chamada ----------
    val eglBase: EglBase by lazy { EglBase.create() }
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private class Peer(val deviceId: String, val userId: String, val pc: PeerConnection, val offerer: Boolean) {
        var audioSender: RtpSender? = null
        var videoSender: RtpSender? = null
        var remoteVideo: VideoTrack? = null
        val pendingIce = mutableListOf<IceCandidate>()
        var state: String = "conectando"
    }

    private class Session(val conversationId: String, val video: Boolean) {
        var id: String? = null
        var dto: CallDto? = null
        var ice: List<PeerConnection.IceServer> = emptyList()
        val peers = HashMap<String, Peer>()
        val earlyIce = HashMap<String, MutableList<IceCandidate>>()
        var answered = false
        var connectedAt = 0L
        var micOn = true
        var speakerOn = video
        var ended = false
        var videoVersion = 0
        // Mídia local
        var audioSource: AudioSource? = null
        var audioTrack: AudioTrack? = null
        var camCapturer: CameraVideoCapturer? = null
        var camHelper: SurfaceTextureHelper? = null
        var camSource: VideoSource? = null
        var camTrack: VideoTrack? = null
        var frontCamera = true
        var screenCapturer: VideoCapturer? = null
        var screenHelper: SurfaceTextureHelper? = null
        var screenSource: VideoSource? = null
        var screenTrack: VideoTrack? = null
    }

    private var s: Session? = null
    private var ringingCall: CallDto? = null
    private val byConversation = HashMap<String, CallDto>()

    init {
        scope.launch {
            client.callEvents.collect { f ->
                launchCall {
                    when (f) {
                        is app.falai.protocol.HelloFrame -> onReconnected()
                        is CallRingFrame -> onRing(f.call)
                        is CallStateFrame -> onState(f.call)
                        is CallSignalInFrame -> onSignal(f)
                        else -> {}
                    }
                }
            }
        }
    }

    private fun launchCall(block: suspend () -> Unit) = scope.launch(dispatcher) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            client.log("Chamada: erro ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun me() = client.account.value
    private fun myDevice() = me()?.deviceId

    private fun publish() {
        val c = s
        _ui.value = CallUi(
            incoming = ringingCall,
            byConversation = byConversation.toMap(),
            active = c?.let {
                ActiveCall(
                    callId = it.id,
                    conversationId = it.conversationId,
                    video = it.video,
                    participants = it.dto?.participants ?: emptyList(),
                    answered = it.answered,
                    connectedAt = it.connectedAt,
                    net = it.peers.mapValues { (_, p) -> p.state },
                    micOn = it.micOn,
                    hasMic = it.audioTrack != null,
                    camOn = it.camTrack != null,
                    frontCamera = it.frontCamera,
                    screenOn = it.screenTrack != null,
                    speakerOn = it.speakerOn,
                    videoVersion = it.videoVersion,
                )
            },
        )
    }

    /** Vídeo que chega de um aparelho (null = sem vídeo agora). */
    fun remoteVideo(deviceId: String): VideoTrack? = s?.peers?.get(deviceId)?.remoteVideo

    /** O meu vídeo (câmera ou tela), para a miniatura. */
    fun localVideo(): VideoTrack? = s?.let { it.screenTrack ?: it.camTrack }

    private fun say(text: String) { _messages.tryEmit(text) }

    // ---------- Ligar, atender, desligar ----------

    /** Liga para a conversa (ou entra na chamada que já existe nela). Precisa do microfone já liberado. */
    fun start(conversationId: String, video: Boolean) = launchCall {
        if (s != null) { say("Você já está numa chamada."); return@launchCall }
        val existing = byConversation[conversationId]
        if (existing != null) { joinNow(existing, video); return@launchCall }
        val c = Session(conversationId, video)
        s = c
        openLocalMedia(c, video)
        CallService.start(app, video)
        setAudioMode(true, c.speakerOn)
        startRingback()
        publish()
        try {
            c.ice = toIce(client.iceServers())
            if (s !== c) return@launchCall
            val dto = client.startCall(conversationId, video)
            if (s !== c) { client.sendCallFrame(CallLeaveFrame(dto.id)); return@launchCall }
            c.id = dto.id
            dto.participants.filter { it.deviceId != myDevice() }.forEach { createPeer(c, it.deviceId, it.userId, offerer = true) }
            apply(c, dto)
            sendMedia(c) // conta se a câmera/microfone abriram de verdade
        } catch (e: Exception) {
            finish(e.message ?: "Não consegui ligar.")
        }
    }

    /** Atende a chamada que está tocando. */
    fun accept(video: Boolean) = launchCall {
        val call = ringingCall ?: return@launchCall
        stopRinging()
        joinNow(call, video)
    }

    /** Entra numa chamada em andamento (grupo). */
    fun join(conversationId: String, video: Boolean) = launchCall {
        val call = byConversation[conversationId] ?: return@launchCall
        if (s != null) { say("Você já está numa chamada."); return@launchCall }
        joinNow(call, video)
    }

    private suspend fun joinNow(call: CallDto, video: Boolean) {
        val c = Session(call.conversationId, call.video)
        c.id = call.id
        c.speakerOn = video
        s = c
        openLocalMedia(c, video)
        CallService.start(app, video)
        setAudioMode(true, c.speakerOn)
        publish()
        try {
            c.ice = toIce(client.iceServers())
            if (s !== c) return
            val dto = client.joinCall(call.id, mic = c.audioTrack != null, cam = c.camTrack != null)
            if (s !== c) { client.sendCallFrame(CallLeaveFrame(call.id)); return }
            dto.participants.filter { it.deviceId != myDevice() }.forEach { createPeer(c, it.deviceId, it.userId, offerer = true) }
            apply(c, dto)
            sendMedia(c) // conta se a câmera/microfone abriram de verdade
        } catch (e: Exception) {
            // A chamada já tinha acabado (eu estava sem conexão quando terminou): some o "em andamento".
            if ((e as? app.falai.client.FalaiClientException)?.code == "not_found") byConversation.remove(call.conversationId)
            finish(e.message ?: "Não consegui entrar na chamada.")
        }
    }

    fun decline() = launchCall {
        val call = ringingCall ?: return@launchCall
        client.sendCallFrame(CallDeclineFrame(call.id))
        stopRinging()
    }

    fun hangup() = launchCall {
        val c = s ?: return@launchCall
        c.id?.let { client.sendCallFrame(CallLeaveFrame(it)) }
        finish(if (c.answered) "Chamada encerrada." else "Chamada cancelada.")
    }

    private fun finish(text: String?) {
        val c = s ?: return
        s = null
        c.ended = true
        stopRingback()
        for (p in c.peers.values) runCatching { p.pc.dispose() }
        c.peers.clear()
        runCatching { c.camCapturer?.stopCapture() }
        runCatching { c.screenCapturer?.stopCapture() }
        listOf(c.camCapturer, c.screenCapturer).forEach { runCatching { it?.dispose() } }
        listOf(c.camTrack, c.screenTrack).forEach { runCatching { it?.dispose() } }
        listOf(c.camSource, c.screenSource).forEach { runCatching { it?.dispose() } }
        listOf(c.camHelper, c.screenHelper).forEach { runCatching { it?.dispose() } }
        runCatching { c.audioTrack?.dispose() }
        runCatching { c.audioSource?.dispose() }
        setAudioMode(false, false)
        proximity(false)
        CallService.stop(app)
        publish()
        if (text != null) say(text)
    }

    // ---------- Novidades do servidor ----------

    /** Chamadas que o servidor confirmou desde a última reconexão. */
    private val confirmed = HashSet<String>()
    private var helloEpoch = 0

    /**
     * Reconectou (ex.: a internet caiu no meio da chamada). O servidor manda de novo as chamadas
     * que continuam; o que ele não mandar em alguns segundos já terminou enquanto eu estava fora.
     */
    private fun onReconnected() {
        byConversation.clear()
        confirmed.clear()
        val epoch = ++helloEpoch
        publish()
        scope.launch(dispatcher) {
            kotlinx.coroutines.delay(4000)
            if (helloEpoch != epoch) return@launch
            ringingCall?.let { if (it.id !in confirmed) stopRinging() }
            s?.let { c -> val id = c.id; if (id != null && id !in confirmed) finish("A chamada terminou enquanto você estava sem conexão.") }
        }
    }

    private fun onRing(dto: CallDto) {
        confirmed += dto.id
        byConversation[dto.conversationId] = dto
        val c = s
        if (c != null) {
            if (c.id != dto.id) client.sendCallFrame(CallDeclineFrame(dto.id, busy = true))
            publish()
            return
        }
        if (ringingCall?.id == dto.id) return
        ringingCall = dto
        startRinging(dto)
        publish()
    }

    private fun onState(dto: CallDto) {
        confirmed += dto.id
        if (dto.ended) byConversation.remove(dto.conversationId) else byConversation[dto.conversationId] = dto
        val ring = ringingCall
        if (ring != null && ring.id == dto.id) {
            val myId = me()?.userId
            when {
                dto.ended -> {
                    stopRinging()
                    if (dto.endReason == "no_answer" || dto.endReason == "hangup") {
                        say("Chamada perdida de ${client.store.userName(dto.startedBy) ?: "alguém"}.")
                        CallNotifications.missed(app, client.store.userName(dto.startedBy) ?: "Alguém", dto.video)
                    }
                }
                dto.participants.any { it.userId == myId } -> { stopRinging(); say("Atendida em outro aparelho.") }
                myId != null && myId in dto.declinedBy -> stopRinging()
                else -> ringingCall = dto
            }
        }
        val c = s
        if (c != null && c.id == dto.id) apply(c, dto)
        publish()
    }

    private val reasons = mapOf(
        "no_answer" to "Ninguém atendeu.", "declined" to "Chamada recusada.",
        "busy" to "Ocupado em outra chamada.", "hangup" to "Chamada encerrada.",
    )

    private fun apply(c: Session, dto: CallDto) {
        if (s !== c || c.id != dto.id) return
        c.dto = dto
        if (dto.ended) { finish(reasons[dto.endReason] ?: "Chamada encerrada."); return }
        if (dto.participants.none { it.deviceId == myDevice() }) { finish("A chamada continuou em outro aparelho."); return }
        val ids = dto.participants.map { it.deviceId }.toSet()
        c.peers.keys.filter { it !in ids }.forEach { d -> c.peers.remove(d)?.let { runCatching { it.pc.dispose() } }; c.videoVersion++ }
        if (dto.participants.size >= 2 && !c.answered) {
            c.answered = true
            stopRingback()
            setAudioMode(true, c.speakerOn)
            if (!c.video && c.camTrack == null) proximity(true)
        }
        CallService.update(app, titleFor(c), c.camTrack != null)
        publish()
    }

    private fun titleFor(c: Session): String {
        val conv = client.store.conversationSummaries(me()?.userId ?: "").firstOrNull { it.id == c.conversationId }
        return conv?.displayName ?: "Chamada"
    }

    // ---------- WebRTC ----------

    private fun toIce(list: List<IceServerDto>): List<PeerConnection.IceServer> = list.map { s ->
        PeerConnection.IceServer.builder(s.urls).apply {
            s.username?.let { setUsername(it) }
            s.credential?.let { setPassword(it) }
        }.createIceServer()
    }

    private fun sig(c: Session, deviceId: String, kind: String, data: JsonObject) {
        val id = c.id ?: return
        client.sendCallFrame(CallSignalFrame(id, deviceId, kind, data.toString()))
    }

    private fun createPeer(c: Session, deviceId: String, userId: String, offerer: Boolean): Peer {
        val config = PeerConnection.RTCConfiguration(c.ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        lateinit var peer: Peer
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                launchCall {
                    if (s !== c) return@launchCall
                    sig(c, deviceId, "ice", buildJsonObject {
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    })
                }
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) launchCall {
                    peer.remoteVideo = track
                    c.videoVersion++
                    publish()
                }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                launchCall {
                    if (s !== c || c.peers[deviceId] !== peer) return@launchCall
                    peer.state = when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> "ok"
                        PeerConnection.PeerConnectionState.DISCONNECTED -> "reconectando"
                        PeerConnection.PeerConnectionState.FAILED -> "falhou"
                        else -> "conectando"
                    }
                    if (newState == PeerConnection.PeerConnectionState.CONNECTED && c.connectedAt == 0L) {
                        c.connectedAt = System.currentTimeMillis()
                    }
                    // Trocou de rede (Wi-Fi ↔ 4G): quem ofereceu tenta um caminho novo.
                    if (newState == PeerConnection.PeerConnectionState.FAILED && peer.offerer) {
                        peer.pc.restartIce()
                        offer(c, peer, MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true")) })
                    }
                    publish()
                }
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }
        val pc = factory.createPeerConnection(config, observer) ?: throw IllegalStateException("WebRTC não abriu")
        peer = Peer(deviceId, userId, pc, offerer)
        c.peers[deviceId] = peer
        c.earlyIce.remove(deviceId)?.let { peer.pendingIce += it }
        if (offerer) {
            val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            val a = c.audioTrack?.let { pc.addTransceiver(it, init) }
                ?: pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, init)
            val v = pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init)
            peer.audioSender = a.sender
            peer.videoSender = v.sender
            v.sender.setTrack(c.screenTrack ?: c.camTrack, false)
            scope.launch(dispatcher) { offer(c, peer, MediaConstraints()) }
        }
        publish()
        return peer
    }

    private suspend fun offer(c: Session, peer: Peer, constraints: MediaConstraints) {
        try {
            val o = peer.pc.createOfferAwait(constraints)
            peer.pc.setLocalAwait(o)
            sig(c, peer.deviceId, "offer", buildJsonObject { put("type", "offer"); put("sdp", o.description) })
        } catch (e: Exception) {
            client.log("Chamada: oferta falhou: ${e.message}")
        }
    }

    private suspend fun onSignal(f: CallSignalInFrame) {
        val c = s ?: return
        if (f.callId != c.id) return
        val data = runCatching { FalaiJson.parseToJsonElement(f.data) as JsonObject }.getOrNull() ?: return
        var peer = c.peers[f.fromDeviceId]
        when (f.kind) {
            "ice" -> {
                val cand = IceCandidate(
                    (data["sdpMid"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
                    (data["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull ?: 0,
                    data["candidate"]?.jsonPrimitive?.content ?: return,
                )
                if (peer == null) { c.earlyIce.getOrPut(f.fromDeviceId) { mutableListOf() } += cand; return }
                if (peer.pc.remoteDescription != null) peer.pc.addIceCandidate(cand) else peer.pendingIce += cand
            }
            "offer" -> {
                if (peer == null) peer = createPeer(c, f.fromDeviceId, f.fromUserId, offerer = false)
                val sdp = data["sdp"]?.jsonPrimitive?.content ?: return
                peer.pc.setRemoteAwait(SessionDescription(SessionDescription.Type.OFFER, sdp))
                // Quem responde: põe o meu microfone e a minha câmera nos canais que o outro abriu.
                for (t in peer.pc.transceivers) {
                    when (t.mediaType) {
                        MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> if (peer.audioSender == null) {
                            t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                            t.sender.setTrack(c.audioTrack, false)
                            peer.audioSender = t.sender
                        }
                        MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> if (peer.videoSender == null) {
                            t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                            t.sender.setTrack(c.screenTrack ?: c.camTrack, false)
                            peer.videoSender = t.sender
                        }
                        else -> {}
                    }
                }
                val answer = peer.pc.createAnswerAwait(MediaConstraints())
                peer.pc.setLocalAwait(answer)
                sig(c, peer.deviceId, "answer", buildJsonObject { put("type", "answer"); put("sdp", answer.description) })
                peer.pendingIce.forEach { peer.pc.addIceCandidate(it) }
                peer.pendingIce.clear()
            }
            "answer" -> {
                val p = peer ?: return
                if (p.pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
                val sdp = data["sdp"]?.jsonPrimitive?.content ?: return
                p.pc.setRemoteAwait(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                p.pendingIce.forEach { p.pc.addIceCandidate(it) }
                p.pendingIce.clear()
            }
        }
    }

    // ---------- Microfone, câmera, tela, alto-falante ----------

    private fun hasPermission(p: String) =
        app.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun openLocalMedia(c: Session, video: Boolean) {
        if (hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            c.audioSource = factory.createAudioSource(constraints)
            c.audioTrack = factory.createAudioTrack("mic", c.audioSource)
        } else {
            say("Sem permissão para o microfone: você vai só escutar.")
        }
        if (video) {
            if (hasPermission(android.Manifest.permission.CAMERA)) startCamera(c)
            else say("Sem permissão para a câmera: seguindo só com voz.")
        }
    }

    private fun startCamera(c: Session): Boolean {
        val e = Camera2Enumerator(app)
        val names = e.deviceNames
        val name = names.firstOrNull { if (c.frontCamera) e.isFrontFacing(it) else e.isBackFacing(it) } ?: names.firstOrNull() ?: return false
        return try {
            val capturer = e.createCapturer(name, null)
            val helper = SurfaceTextureHelper.create("falai-cam", eglBase.eglBaseContext)
            val source = factory.createVideoSource(false)
            capturer.initialize(helper, app, source.capturerObserver)
            capturer.startCapture(1280, 720, 30)
            c.camCapturer = capturer
            c.camHelper = helper
            c.camSource = source
            c.camTrack = factory.createVideoTrack("cam", source)
            c.frontCamera = e.isFrontFacing(name)
            true
        } catch (ex: Exception) {
            client.log("Câmera não abriu: ${ex.message}")
            say("Não consegui abrir a câmera.")
            false
        }
    }

    private fun stopCamera(c: Session) {
        runCatching { c.camCapturer?.stopCapture() }
        runCatching { c.camCapturer?.dispose() }
        val t = c.camTrack
        c.camTrack = null
        setVideoOnPeers(c)
        runCatching { t?.dispose() }
        runCatching { c.camSource?.dispose() }
        runCatching { c.camHelper?.dispose() }
        c.camCapturer = null; c.camSource = null; c.camHelper = null
    }

    private fun setVideoOnPeers(c: Session) {
        val t = c.screenTrack ?: c.camTrack
        for (p in c.peers.values) runCatching { p.videoSender?.setTrack(t, false) }
        c.videoVersion++
    }

    private fun sendMedia(c: Session) {
        val id = c.id ?: return
        client.sendCallFrame(CallMediaFrame(id, mic = c.audioTrack != null && c.micOn, cam = c.camTrack != null, screen = c.screenTrack != null))
    }

    fun toggleMic() = launchCall {
        val c = s ?: return@launchCall
        val t = c.audioTrack ?: run { say("Sem microfone nesta chamada."); return@launchCall }
        c.micOn = !c.micOn
        t.setEnabled(c.micOn)
        sendMedia(c); publish()
    }

    /** Liga/desliga a câmera. Precisa da permissão da câmera já dada. */
    fun toggleCamera() = launchCall {
        val c = s ?: return@launchCall
        if (c.camTrack != null) stopCamera(c)
        else {
            if (!hasPermission(android.Manifest.permission.CAMERA)) { say("Sem permissão para a câmera."); return@launchCall }
            withContext(Dispatchers.Main) { CallService.update(app, titleFor(c), camera = true) }
            if (startCamera(c)) { setVideoOnPeers(c); proximity(false); if (!c.speakerOn) setSpeaker(c, true) }
        }
        sendMedia(c); publish()
    }

    fun switchCamera() = launchCall {
        val c = s ?: return@launchCall
        val cap = c.camCapturer ?: return@launchCall
        cap.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) { launchCall { c.frontCamera = isFront; c.videoVersion++; publish() } }
            override fun onCameraSwitchError(error: String?) { say("Não consegui trocar de câmera.") }
        })
    }

    fun toggleSpeaker() = launchCall {
        val c = s ?: return@launchCall
        setSpeaker(c, !c.speakerOn)
        publish()
    }

    private fun setSpeaker(c: Session, on: Boolean) {
        c.speakerOn = on
        setAudioMode(true, on)
        proximity(!on && c.answered && c.camTrack == null && c.screenTrack == null)
    }

    /**
     * Começa a mostrar a tela. [permissionData] = a resposta do Android ao "Começar agora".
     * O serviço da chamada precisa estar avisando "mostrando a tela" antes de capturar (Android 14).
     */
    fun startScreen(permissionData: Intent) = launchCall {
        val c = s ?: return@launchCall
        if (c.screenTrack != null) return@launchCall
        withContext(Dispatchers.Main) { CallService.update(app, titleFor(c), camera = c.camTrack != null, screen = true) }
        try {
            val capturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
                override fun onStop() { stopScreen() }
            })
            val helper = SurfaceTextureHelper.create("falai-screen", eglBase.eglBaseContext)
            val source = factory.createVideoSource(true)
            capturer.initialize(helper, app, source.capturerObserver)
            val (w, h) = screenSize()
            capturer.startCapture(w, h, 15)
            c.screenCapturer = capturer
            c.screenHelper = helper
            c.screenSource = source
            c.screenTrack = factory.createVideoTrack("screen", source)
            setVideoOnPeers(c)
            proximity(false)
            sendMedia(c); publish()
            say("Todos na chamada estão vendo a sua tela.")
        } catch (e: Exception) {
            client.log("Tela não abriu: ${e.message}")
            say("Não consegui mostrar a tela (${e.message ?: e.javaClass.simpleName}).")
            withContext(Dispatchers.Main) { CallService.update(app, titleFor(c), camera = c.camTrack != null, screen = false) }
        }
    }

    fun stopScreen() = launchCall {
        val c = s ?: return@launchCall
        val cap = c.screenCapturer ?: return@launchCall
        runCatching { cap.stopCapture() }
        runCatching { cap.dispose() }
        val t = c.screenTrack
        c.screenTrack = null
        setVideoOnPeers(c)
        runCatching { t?.dispose() }
        runCatching { c.screenSource?.dispose() }
        runCatching { c.screenHelper?.dispose() }
        c.screenCapturer = null; c.screenSource = null; c.screenHelper = null
        withContext(Dispatchers.Main) { CallService.update(app, titleFor(c), camera = c.camTrack != null, screen = false) }
        sendMedia(c); publish()
    }

    /** Tamanho da captura da tela: o da tela, reduzido para no máximo 1280 no lado maior. */
    private fun screenSize(): Pair<Int, Int> {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            w = b.width(); h = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            w = m.widthPixels; h = m.heightPixels
        }
        val scale = minOf(1f, 1280f / maxOf(w, h))
        return ((w * scale).toInt() / 2 * 2) to ((h * scale).toInt() / 2 * 2)
    }

    // ---------- Som: toque, chamando, alto-falante ----------

    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null

    private fun startRinging(dto: CallDto) {
        val name = client.store.userName(dto.startedBy) ?: "Alguém"
        val group = client.store.conversationSummaries(me()?.userId ?: "").firstOrNull { it.id == dto.conversationId }
            ?.takeIf { it.kind == "group" }?.displayName
        CallNotifications.incoming(app, dto, name, group, showFullScreen = !appVisible)
        runCatching {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                ringtone = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    isLooping = true
                    play()
                }
            }
            if (am.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                val v = app.getSystemService(Vibrator::class.java)
                v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
            }
        }
    }

    private fun stopRinging() {
        ringingCall = null
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { app.getSystemService(Vibrator::class.java)?.cancel() }
        CallNotifications.cancelIncoming(app)
        publish()
    }

    private fun startRingback() {
        stopRingback()
        runCatching {
            ringback = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        }
    }

    private fun stopRingback() {
        runCatching { ringback?.stopTone(); ringback?.release() }
        ringback = null
    }

    private var savedMode: Int? = null

    private fun setAudioMode(inCall: Boolean, speaker: Boolean) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!inCall) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                @Suppress("DEPRECATION") am.isSpeakerphoneOn = false
                am.mode = savedMode ?: AudioManager.MODE_NORMAL
            }
            savedMode = null
            return
        }
        if (savedMode == null) savedMode = am.mode
        runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val wanted = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                // Fone com fio ou Bluetooth ligado: deixa o Android escolher (vai para o fone).
                val headset = am.availableCommunicationDevices.firstOrNull {
                    it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
                }
                val device = if (!speaker && headset != null) headset else am.availableCommunicationDevices.firstOrNull { it.type == wanted }
                if (device != null) am.setCommunicationDevice(device)
            } else {
                @Suppress("DEPRECATION") am.isSpeakerphoneOn = speaker
            }
        }
    }

    // Chamada de voz no ouvido: a tela apaga (e não aperta botões com a orelha).
    private var proximityLock: PowerManager.WakeLock? = null

    private fun proximity(on: Boolean) {
        runCatching {
            if (on) {
                if (proximityLock?.isHeld == true) return
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
                proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "falai:proximidade").apply { acquire(4 * 3600_000L) }
            } else {
                proximityLock?.takeIf { it.isHeld }?.release()
                proximityLock = null
            }
        }
    }
}

// ---------- WebRTC com "suspend" (mais fácil de ler em ordem) ----------

private suspend fun PeerConnection.createOfferAwait(c: MediaConstraints): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createOffer(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { cont.resume(d) }
            override fun onCreateFailure(e: String?) { cont.resumeWithException(IllegalStateException(e ?: "oferta")) }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }, c)
    }

private suspend fun PeerConnection.createAnswerAwait(c: MediaConstraints): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createAnswer(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { cont.resume(d) }
            override fun onCreateFailure(e: String?) { cont.resumeWithException(IllegalStateException(e ?: "resposta")) }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }, c)
    }

private suspend fun PeerConnection.setLocalAwait(d: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetSuccess() { cont.resume(Unit) }
            override fun onSetFailure(e: String?) { cont.resumeWithException(IllegalStateException(e ?: "local")) }
        }, d)
    }

private suspend fun PeerConnection.setRemoteAwait(d: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetSuccess() { cont.resume(Unit) }
            override fun onSetFailure(e: String?) { cont.resumeWithException(IllegalStateException(e ?: "remota")) }
        }, d)
    }
