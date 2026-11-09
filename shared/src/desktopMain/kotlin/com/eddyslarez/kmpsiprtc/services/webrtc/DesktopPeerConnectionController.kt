package com.eddyslarez.kmpsiprtc.services.webrtc

import com.eddyslarez.kmpsiprtc.data.models.RecordingResult
import com.eddyslarez.kmpsiprtc.data.models.SdpType
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.audio.AudioCaptureCallback
import com.eddyslarez.kmpsiprtc.services.audio.AudioStreamListener
import com.eddyslarez.kmpsiprtc.services.audio.AudioTrackCapture
import com.eddyslarez.kmpsiprtc.services.audio.createRemoteAudioCapture
import com.eddyslarez.kmpsiprtc.services.recording.CallRecorder
import com.eddyslarez.kmpsiprtc.services.recording.DesktopCallRecorder
import com.eddyslarez.kmpsiprtc.services.recording.createCallRecorder
import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.*
import dev.onvoid.webrtc.media.audio.*
import dev.onvoid.webrtc.media.video.*
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import com.eddyslarez.kmpsiprtc.services.screencapture.DesktopScreenCaptureSupport
import com.eddyslarez.kmpsiprtc.services.screencapture.PipeWireVideoCapture
import com.eddyslarez.kmpsiprtc.services.screencapture.PortalScreenCastSession
import com.eddyslarez.kmpsiprtc.services.screencapture.ScreenShareUnavailableException
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Id sintético de la fuente "elegir con el selector del sistema" (Wayland). */
const val PORTAL_SOURCE_ID = "portal"

/**
 * Identificadores de los MediaStreamTrack locales.
 *
 * **No son un detalle interno.** LiveKit correlaciona el `AddTrackRequest.cid` con el id del track
 * que aparece en el SDP; el SDK oficial manda literalmente `track.rtcTrack.id()`. Si no coinciden,
 * el SFU deja el AddTrackRequest como un track "pendiente" al que nunca se le asocian medios, y el
 * RTP real acaba en otro track distinto: por eso un MuteTrackRequest sobre ese sid no cambiaba nada
 * para el resto de la sala.
 */
object LocalTrackIds {
    const val AUDIO = "audio0"
    const val VIDEO = "video0"
    const val SCREEN = "screen0"
    const val INJECTION = "injection0"
}

class DesktopPeerConnectionController(
    private val onIceCandidate: (String, String, Int) -> Unit,
    private val onConnectionStateChange: (WebRtcConnectionState) -> Unit,
    private val onRemoteAudioTrack: () -> Unit
) {
    private val TAG = "DesktopPeerConnectionController"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // WebRTC components
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: RTCPeerConnection? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioTrackSource? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var dtmfSender: RTCDtmfSender? = null

    // === INYECCIÓN DE AUDIO PARA TRADUCCIÓN ===
    // webrtc-java 0.19 fija cada factoría a micro del ADM o a CustomAudioSource: la pista inyectada
    // sale de una factoría propia sin dispositivos y se pone en el mismo sender con replaceTrack.
    private var injectionAudioModule: HeadlessAudioDeviceModule? = null
    private var injectionFactory: PeerConnectionFactory? = null
    @Volatile
    private var customAudioSource: CustomAudioSource? = null
    private var injectionAudioTrack: AudioTrack? = null
    private var injectionPipeline: PcmInjectionPipeline? = null
    // Captura del ADM parada mientras se inyecta: el sender no admite dos productores.
    @Volatile
    private var deviceCaptureSuspended = false
    @Volatile
    private var localAudioInjectionActive = false

    // === VIDEO para conferencias ===
    private var videoDeviceSource: VideoDeviceSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoSender: RTCRtpSender? = null
    private var screenVideoSource: VideoDesktopSource? = null

    // Camino de captura por xdg-desktop-portal (Linux/Wayland).
    private var portalSession: PortalScreenCastSession? = null
    private var pipeWireCapture: PipeWireVideoCapture? = null
    private var screenCustomSource: CustomVideoSource? = null
    private var localScreenTrack: VideoTrack? = null
    private var screenSender: RTCRtpSender? = null
    // Callback para cuando llega un video track remoto
    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null
    // Conferencias necesitan el MSID/MID real para asociar cada pista a su publicación.
    var onRemoteVideoTrackAdded: ((VideoTrack, List<String>, String?) -> Unit)? = null
    var onRemoteVideoTrackRemoved: ((String) -> Unit)? = null

    // === DATA CHANNEL para conferencias ===
    private var publisherDataChannel: RTCDataChannel? = null
    private var receivedDataChannel: RTCDataChannel? = null
    var dataChannelMessageListener: ((ByteArray) -> Unit)? = null
    // Flag que indica si el audio local (mic) está habilitado en WebRTC
    @Volatile
    private var localAudioEnabled = true
    // Referencia al sender de audio para poder hacer replaceTrack()
    private var audioSender: RTCRtpSender? = null
    // Buffer para audio remoto inyectado (traducido)
    private val remoteInjectionBuffer = ConcurrentLinkedQueue<ByteArray>()
    @Volatile
    private var remoteAudioInjectionActive = false
    // Cola de frames de 10ms para inyección paceada al CustomAudioSource
    private val injectionFrameQueue = ConcurrentLinkedQueue<ByteArray>()
    // Scheduler que empuja frames adaptivamente basado en tiempo real transcurrido
    private var injectionScheduler: ScheduledExecutorService? = null
    // Contadores para pacing adaptativo (compensa la resolución del timer de Windows ~15.6ms)
    @Volatile
    private var pacerStartNanos = 0L
    @Volatile
    private var pacerFramesPushed = 0L
    private val injectionStateLock = Any()
    private val injectionSilence = PcmInjectionPipeline.silenceFrame()
    private val INJECTION_FRAME_NANOS = 10_000_000L
    private val INJECTION_MAX_LAG_FRAMES = 10L

    // Recording
    private val callRecorder: CallRecorder by lazy { createCallRecorder() }
    private var remoteAudioCapture: AudioTrackCapture? = null

    // State
    @Volatile
    private var isMuted = false
    @Volatile
    private var remoteAudioEnabled = true
    @Volatile
    private var currentConnectionState = WebRtcConnectionState.DISCONNECTED

    // ICE servers configurables. Default = STUN públicos como fallback.
    // El cliente debe llamar setIceServers() con los valores del JoinResponse
    // de LiveKit ANTES de crear el PeerConnection para incluir TURN.
    private var iceServers: List<RTCIceServer> = listOf(
        RTCIceServer().apply {
            urls = listOf(
                "stun:stun.l.google.com:19302",
                "stun:stun1.l.google.com:19302"
            )
        }
    )

    /**
     * Sustituye los iceServers actuales por los recibidos del SFU (típicamente
     * en el JoinResponse de LiveKit, que incluye TURN con credenciales generadas).
     * El primer STUN público se mantiene como fallback.
     */
    fun setIceServers(servers: List<Triple<List<String>, String?, String?>>) {
        if (servers.isEmpty()) return
        val newList = mutableListOf<RTCIceServer>()
        servers.forEach { (urls, username, credential) ->
            if (urls.isNotEmpty()) {
                newList.add(RTCIceServer().apply {
                    this.urls = urls
                    if (!username.isNullOrBlank()) this.username = username
                    if (!credential.isNullOrBlank()) this.password = credential
                })
            }
        }
        // Conservamos un STUN público como fallback adicional por robustez.
        newList.add(RTCIceServer().apply {
            urls = listOf("stun:stun.l.google.com:19302")
        })
        iceServers = newList
        log.d(TAG) { "ICE servers actualizados: ${newList.size} entries (TURN del SFU + fallback STUN)" }
    }

    // ==================== AUDIO DEVICE MODULE ====================

    fun getAudioDeviceModule(): AudioDeviceModule? = audioDeviceModule

    // ==================== INITIALIZATION ====================

    /** Lanza la causa original si la factoría o el ADM no arrancan (p. ej. `UnsatisfiedLinkError`). */
    fun initialize() {
        log.d(TAG) { "Initializing PeerConnectionController..." }

        try {
            // En Linux, kPlatformDefaultAudio resuelve a ALSA cruda (hw:), que NO mezcla
            // aperturas concurrentes del dispositivo por defecto: si hay dos ADMs en el
            // proceso (conferencia = publisher + subscriber), el segundo (playout del
            // subscriber) no puede abrir el dispositivo y el audio entrante queda mudo.
            // PulseAudio sí mezcla, igual que WASAPI/CoreAudio en Windows/Mac.
            val isLinux = System.getProperty("os.name").orEmpty().lowercase().contains("linux")
            val audioLayer = if (isLinux) AudioLayer.kLinuxPulseAudio else AudioLayer.kPlatformDefaultAudio
            val adm = AudioDeviceModule(audioLayer)
            audioDeviceModule = adm
            peerConnectionFactory = PeerConnectionFactory(adm, null)

            log.d(TAG) { "✅ PeerConnectionController initialized (audioLayer=$audioLayer)" }
        } catch (t: Throwable) {
            log.e(TAG) { "Error initializing WebRTC: ${t::class.simpleName}: ${t.message}" }
            runCatching { peerConnectionFactory?.dispose() }
            runCatching { audioDeviceModule?.dispose() }
            peerConnectionFactory = null
            audioDeviceModule = null
            throw t
        }
    }

    // ==================== PEER CONNECTION ====================

    fun hasPeerConnection(): Boolean = peerConnection != null

    /** También recupera receptores reutilizados que no generan otro evento OnTrack. */
    fun remoteVideoReceivers(): List<Pair<VideoTrack, String?>> =
        peerConnection?.transceivers.orEmpty().mapNotNull { transceiver ->
            if (transceiver.stopped()) return@mapNotNull null
            val track = transceiver.receiver?.track as? VideoTrack ?: return@mapNotNull null
            if (track.state == MediaStreamTrackState.ENDED) return@mapNotNull null
            track to transceiver.mid
        }

    fun createNewPeerConnection() {
        log.d(TAG) { "Creating new PeerConnection" }

        if (peerConnection != null) {
            closePeerConnection()
        }

        val factory = peerConnectionFactory
            ?: throw IllegalStateException("Factory not initialized")

        val rtcConfig = RTCConfiguration().apply {
            iceServers.addAll(this@DesktopPeerConnectionController.iceServers)
            bundlePolicy = RTCBundlePolicy.BALANCED
            rtcpMuxPolicy = RTCRtcpMuxPolicy.REQUIRE
        }

        try {
            peerConnection = factory.createPeerConnection(
                rtcConfig,
                createPeerConnectionObserver()
            )

            addLocalAudioTrack()

            log.d(TAG) { "✅ New PeerConnection created" }
        } catch (e: Exception) {
            log.e(TAG) { "Error creating PeerConnection: ${e.message}" }
            throw e
        }
    }

    private fun createPeerConnectionObserver(): PeerConnectionObserver {
        return object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                log.d(TAG) { "ICE candidate generated" }
                onIceCandidate(
                    candidate.sdp,
                    candidate.sdpMid,
                    candidate.sdpMLineIndex
                )
            }

            override fun onConnectionChange(state: RTCPeerConnectionState) {
                log.d(TAG) { "Connection state: $state" }
                val connectionState = when (state) {
                    RTCPeerConnectionState.NEW -> WebRtcConnectionState.NEW
                    RTCPeerConnectionState.CONNECTING -> WebRtcConnectionState.CONNECTING
                    RTCPeerConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
                    RTCPeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
                    RTCPeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
                    RTCPeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
                }
                currentConnectionState = connectionState
                onConnectionStateChange(connectionState)
            }

            override fun onTrack(transceiver: RTCRtpTransceiver) {
                val track = transceiver.receiver?.track
                log.d(TAG) { "Remote track received: kind=${track?.kind}" }
                if (track?.kind == "audio") {
                    (track as? AudioTrack)?.let { audioTrack ->
                        remoteAudioTrack = audioTrack
                        audioTrack.setEnabled(remoteAudioEnabled)
                    }
                    // Configurar captura de audio remoto
                    setupRemoteAudioCapture(track)
                    onRemoteAudioTrack()
                } else if (track?.kind == "video") {
                    (track as? VideoTrack)?.let { videoTrack ->
                        log.d(TAG) { "✅ Remote video track received" }
                        onRemoteVideoTrack?.invoke(videoTrack)
                        onRemoteVideoTrackAdded?.invoke(videoTrack, emptyList(), transceiver.mid)
                    }
                }
            }

            override fun onAddTrack(receiver: RTCRtpReceiver, streams: Array<MediaStream>) {
                try {
                    val track = receiver.track as? VideoTrack ?: return
                    // LiveKit puede crear transceivers sin stream id (MSID vacío).
                    // No permitir que un valor nulo de Java llegue al registro Kotlin.
                    val streamIds = streams.mapNotNull { stream ->
                        try {
                            stream.id()?.takeIf { it.isNotBlank() }
                        } catch (_: Exception) {
                            null
                        }
                    }
                    onRemoteVideoTrackAdded?.invoke(track, streamIds, null)
                } catch (error: Exception) {
                    // Las excepciones no deben cruzar el callback JNI de WebRTC.
                    log.e(tag = TAG) { "Error procesando OnAddTrack: ${error.message}" }
                }
            }

            override fun onRemoveTrack(receiver: RTCRtpReceiver) {
                (receiver.track as? VideoTrack)?.let { onRemoteVideoTrackRemoved?.invoke(it.id) }
            }

            override fun onSignalingChange(state: RTCSignalingState) {}
            override fun onIceGatheringChange(state: RTCIceGatheringState) {}
            override fun onIceConnectionChange(state: RTCIceConnectionState) {}
            override fun onStandardizedIceConnectionChange(state: RTCIceConnectionState) {}
            override fun onDataChannel(dataChannel: RTCDataChannel) {
                log.d(tag = TAG) { "Data channel recibido: ${dataChannel.label}" }
                dataChannel.registerObserver(object : RTCDataChannelObserver {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {
                        log.d(tag = TAG) { "Data channel state: ${dataChannel.state}" }
                    }
                    override fun onMessage(buffer: RTCDataChannelBuffer) {
                        try {
                            val bytes = ByteArray(buffer.data.remaining())
                            buffer.data.get(bytes)
                            dataChannelMessageListener?.invoke(bytes)
                        } catch (e: Exception) {
                            log.e(tag = TAG) { "Error leyendo data channel message: ${e.message}" }
                        }
                    }
                })
                receivedDataChannel = dataChannel
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {
                stream.videoTracks.forEach { onRemoteVideoTrackRemoved?.invoke(it.id) }
            }
        }
    }

    private fun addLocalAudioTrack() {
        try {
            val factory = peerConnectionFactory ?: return
            val pc = peerConnection ?: return

            val audioOptions = AudioOptions().apply {
                echoCancellation = true
                noiseSuppression = true
                autoGainControl = true
                highpassFilter = true
            }

            val audioSource = factory.createAudioSource(audioOptions)
            localAudioSource = audioSource
            localAudioTrack = factory.createAudioTrack(LocalTrackIds.AUDIO, audioSource)
            // Un MediaStreamTrack nace HABILITADO. Si ya se pidio silencio antes de que existiera
            // -que es lo normal: se entra a la conferencia en mute y el PeerConnection no se crea
            // hasta el data channel- ese setMuted solo pudo guardar el flag, y sin esta linea el
            // micro quedaba fisicamente abierto mientras la interfaz decia lo contrario.
            localAudioTrack?.setEnabled(!isMuted)

            val sender = pc.addTrack(localAudioTrack, listOf("stream0"))
            audioSender = sender  // Guardar referencia al sender para replaceTrack()
            dtmfSender = sender.dtmfSender

            log.d(TAG) { "✅ Local audio track added" }
        } catch (e: Exception) {
            log.e(TAG) { "Error adding local audio track: ${e.message}" }
        }
    }

    // ==================== INYECCIÓN DE AUDIO PARA TRADUCCIÓN ====================

    /**
     * Con `false` el sender deja de enviar el micrófono y envía lo que llegue por [injectLocalAudio]
     * (silencio mientras no llega nada); con `true` vuelve el micrófono.
     */
    fun setLocalAudioEnabled(enabled: Boolean) {
        val shouldApply = synchronized(injectionStateLock) {
            if (localAudioEnabled == enabled) {
                false
            } else {
                localAudioEnabled = enabled
                true
            }
        }
        if (!shouldApply) return

        log.d(TAG) { "setLocalAudioEnabled: $enabled" }

        if (!enabled) {
            activateLocalAudioInjection()
        } else {
            deactivateLocalAudioInjection()
        }
    }

    fun isLocalAudioEnabled(): Boolean = localAudioEnabled

    fun isDeviceCaptureSuspended(): Boolean = deviceCaptureSuspended

    private fun ensureInjectionFactory(): PeerConnectionFactory {
        injectionFactory?.let { return it }
        val module = HeadlessAudioDeviceModule()
        val factory = try {
            PeerConnectionFactory(module)
        } catch (t: Throwable) {
            runCatching { module.dispose() }
            throw t
        }
        injectionAudioModule = module
        injectionFactory = factory
        return factory
    }

    private fun activateLocalAudioInjection() {
        val sender = audioSender ?: run {
            log.e(TAG) { "Cannot activate injection: audioSender is null" }
            synchronized(injectionStateLock) { localAudioEnabled = true }
            return
        }

        stopInjectionPacer()
        var source: CustomAudioSource? = null
        var track: AudioTrack? = null
        try {
            val factory = ensureInjectionFactory()
            source = CustomAudioSource()
            track = factory.createAudioTrack(LocalTrackIds.INJECTION, source)
            // Primero se corta el ADM y después se cambia la pista: nunca hay dos productores a la vez.
            suspendDeviceCapture()
            sender.replaceTrack(track)
            reassertCaptureSuspension()
            synchronized(injectionStateLock) {
                injectionPipeline = null
                injectionFrameQueue.clear()
                customAudioSource = source
                injectionAudioTrack = track
                localAudioInjectionActive = true
            }
            startInjectionPacer()
            log.d(TAG) { "Local audio injection active" }
        } catch (t: Throwable) {
            log.e(TAG) { "Error activating local audio injection: ${t::class.simpleName}: ${t.message}" }
            synchronized(injectionStateLock) {
                localAudioInjectionActive = false
                customAudioSource = null
                injectionAudioTrack = null
            }
            localAudioTrack?.let { mic -> runCatching { sender.replaceTrack(mic) } }
            resumeDeviceCapture()
            runCatching { localAudioTrack?.setEnabled(false) }
            runCatching { track?.dispose() }
            runCatching { source?.dispose() }
        }
    }

    private fun deactivateLocalAudioInjection() {
        stopInjectionPacer()
        val released = synchronized(injectionStateLock) {
            localAudioInjectionActive = false
            val pair = injectionAudioTrack to customAudioSource
            injectionAudioTrack = null
            customAudioSource = null
            injectionPipeline = null
            pair
        }

        val sender = audioSender
        val mic = localAudioTrack
        if (sender != null && mic != null) {
            runCatching { sender.replaceTrack(mic) }
                .onFailure { log.e(TAG) { "Error restoring microphone track: ${it.message}" } }
        }
        runCatching { mic?.setEnabled(!isMuted) }
        resumeDeviceCapture()

        runCatching { released.first?.dispose() }
        runCatching { released.second?.dispose() }
        log.d(TAG) { "Local audio injection deactivated - mic restored" }
    }

    private fun suspendDeviceCapture() {
        val adm = audioDeviceModule ?: return
        adm.stopRecording()
        deviceCaptureSuspended = true
    }

    /**
     * WebRTC vuelve a abrir la captura del ADM al reiniciar el envío de audio (re-INVITE de
     * retener/reanudar, cambio de pista); mientras se inyecta hay que cerrarla otra vez para que el
     * micrófono y la traducción nunca alimenten el mismo sender.
     */
    private fun reassertCaptureSuspension() {
        if (!deviceCaptureSuspended) return
        runCatching { audioDeviceModule?.stopRecording() }
    }

    private fun resumeDeviceCapture() {
        if (!deviceCaptureSuspended) return
        deviceCaptureSuspended = false
        val adm = audioDeviceModule ?: return
        runCatching {
            adm.initRecording()
            adm.startRecording()
        }.onFailure { log.e(TAG) { "Error restarting microphone capture: ${it.message}" } }
    }

    /**
     * Encola PCM entero little-endian intercalado (8, 16, 24 o 32 bits, cualquier frecuencia y número
     * de canales); el pacer lo envía a ritmo real en bloques de 10 ms a 48 kHz mono.
     */
    fun injectLocalAudio(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        if (!localAudioInjectionActive) return
        try {
            synchronized(injectionStateLock) {
                if (!localAudioInjectionActive) return
                val pipeline = injectionPipeline?.takeIf { it.matches(sampleRate, channels, bitsPerSample) }
                    ?: PcmInjectionPipeline(sampleRate, channels, bitsPerSample).also { injectionPipeline = it }
                pipeline.process(pcmData).forEach { injectionFrameQueue.offer(it) }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error enqueuing local audio: ${e.message}" }
        }
    }

    /**
     * Cada tick calcula cuántos bloques de 10 ms tocaban según el reloj (el temporizador de Windows va a
     * ~15,6 ms) y los empuja. Sin audio pendiente envía silencio para que el RTP no se corte.
     */
    private fun startInjectionPacer() {
        pacerStartNanos = System.nanoTime()
        pacerFramesPushed = 0L
        val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "audio-injection-pacer").apply { isDaemon = true }
        }
        injectionScheduler = scheduler
        scheduler.scheduleAtFixedRate({ pushDueInjectionFrames() }, 0, 5, TimeUnit.MILLISECONDS)
        log.d(TAG) { "Injection pacer started" }
    }

    private fun pushDueInjectionFrames() {
        try {
            val source = customAudioSource ?: return
            val now = System.nanoTime()
            var due = (now - pacerStartNanos) / INJECTION_FRAME_NANOS - pacerFramesPushed
            if (due > INJECTION_MAX_LAG_FRAMES) {
                pacerStartNanos = now - INJECTION_FRAME_NANOS
                pacerFramesPushed = 0L
                due = 1L
            }
            while (due > 0) {
                val frame = nextInjectionFrame()
                source.pushAudio(
                    if (isMuted) injectionSilence else frame,
                    16,
                    PcmInjectionPipeline.TARGET_SAMPLE_RATE,
                    1,
                    PcmInjectionPipeline.FRAME_SAMPLES,
                )
                pacerFramesPushed++
                due--
            }
        } catch (e: Exception) {
            log.e(TAG) { "Injection pacer error: ${e::class.simpleName}: ${e.message}" }
        }
    }

    private fun nextInjectionFrame(): ByteArray =
        injectionFrameQueue.poll()
            ?: synchronized(injectionStateLock) {
                injectionFrameQueue.poll() ?: injectionPipeline?.drainPadded()
            }
            ?: injectionSilence

    /** Espera al tick en curso: después se libera la fuente nativa a la que empuja. */
    private fun stopInjectionPacer() {
        val scheduler = injectionScheduler
        injectionScheduler = null
        if (scheduler != null) {
            scheduler.shutdownNow()
            runCatching { scheduler.awaitTermination(200, TimeUnit.MILLISECONDS) }
        }
        injectionFrameQueue.clear()
    }

    /**
     * Inyecta audio remoto traducido para reproducción local (speaker).
     * Nota: Para Desktop, la reproducción se maneja normalmente via TranslationAudioPlayer
     * usando SourceDataLine. Este método es un fallback si se necesita inyección a nivel WebRTC.
     */
    fun injectRemoteAudio(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        // En Desktop, la reproducción de audio traducido se maneja via TranslationAudioPlayer
        // (SourceDataLine directo al speaker). No necesitamos inyectar en el pipeline de WebRTC.
        // Este método existe por simetría con la interfaz pero delega al player externo.
        remoteInjectionBuffer.offer(pcmData)
    }

    // ==================== SDP OPERATIONS ====================

    suspend fun createOffer(): String = suspendCoroutine { continuation ->
        scope.launch {
            try {
                val pc = peerConnection
                    ?: throw IllegalStateException("PeerConnection not initialized")

                if (localAudioTrack == null) {
                    addLocalAudioTrack()
                }

                val options = RTCOfferOptions()
                pc.createOffer(options, object : CreateSessionDescriptionObserver {
                    override fun onSuccess(description: RTCSessionDescription) {
                        pc.setLocalDescription(description, object : SetSessionDescriptionObserver {
                            override fun onSuccess() {
                                log.d(TAG) { "✅ Offer created" }
                                reassertCaptureSuspension()
                                continuation.resume(description.sdp)
                            }

                            override fun onFailure(error: String) {
                                continuation.resumeWithException(Exception(error))
                            }
                        })
                    }

                    override fun onFailure(error: String) {
                        continuation.resumeWithException(Exception(error))
                    }
                })
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }

    suspend fun createAnswer(offerSdp: String): String = suspendCoroutine { continuation ->
        scope.launch {
            try {
                val pc = peerConnection
                    ?: throw IllegalStateException("PeerConnection not initialized")

                // Set remote description first
                val sdpType = RTCSdpType.OFFER
                val sessionDescription = RTCSessionDescription(sdpType, offerSdp)

                pc.setRemoteDescription(sessionDescription, object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        // Now create answer
                        val options = RTCAnswerOptions()
                        pc.createAnswer(options, object : CreateSessionDescriptionObserver {
                            override fun onSuccess(description: RTCSessionDescription) {
                                pc.setLocalDescription(description, object : SetSessionDescriptionObserver {
                                    override fun onSuccess() {
                                        log.d(TAG) { "✅ Answer created" }
                                        reassertCaptureSuspension()
                                        continuation.resume(description.sdp)
                                    }

                                    override fun onFailure(error: String) {
                                        continuation.resumeWithException(Exception(error))
                                    }
                                })
                            }

                            override fun onFailure(error: String) {
                                continuation.resumeWithException(Exception(error))
                            }
                        })
                    }

                    override fun onFailure(error: String) {
                        continuation.resumeWithException(
                            Exception("Failed to set remote description: $error")
                        )
                    }
                })

            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }

    suspend fun setRemoteDescription(sdp: String, type: SdpType) = suspendCoroutine<Unit> { continuation ->
        scope.launch {
            try {
                val pc = peerConnection
                    ?: throw IllegalStateException("PeerConnection not initialized")

                val sdpType = when (type) {
                    SdpType.OFFER -> RTCSdpType.OFFER
                    SdpType.ANSWER -> RTCSdpType.ANSWER
                }

                val sessionDescription = RTCSessionDescription(sdpType, sdp)
                pc.setRemoteDescription(sessionDescription, object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        reassertCaptureSuspension()
                        continuation.resume(Unit)
                    }

                    override fun onFailure(error: String) {
                        continuation.resumeWithException(Exception(error))
                    }
                })
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }

    suspend fun setLocalDescriptionDirect(sdp: String) = suspendCoroutine<Unit> { continuation ->
        scope.launch {
            try {
                val pc = peerConnection
                    ?: throw IllegalStateException("PeerConnection not initialized")

                val currentDesc = pc.localDescription
                    ?: throw IllegalStateException("No local description")

                val newDescription = RTCSessionDescription(currentDesc.sdpType, sdp)
                pc.setLocalDescription(newDescription, object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        reassertCaptureSuspension()
                        continuation.resume(Unit)
                    }

                    override fun onFailure(error: String) {
                        continuation.resumeWithException(Exception(error))
                    }
                })
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }

    suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        try {
            val iceCandidate = RTCIceCandidate(
                sdpMid ?: "",
                sdpMLineIndex ?: 0,
                candidate
            )
            peerConnection?.addIceCandidate(iceCandidate)
        } catch (e: Exception) {
            log.e(TAG) { "Error adding ICE candidate: ${e.message}" }
        }
    }

    // ==================== AUDIO CONTROL ====================

    /**
     * Habilita o corta el track de audio local **sin tocar** el estado de mute del usuario.
     *
     * No usar para el mute: para eso esta [setMuted], que ademas recuerda la intencion para cuando
     * el track se recree. Esto es para el pipeline de traduccion en vivo.
     */
    fun setAudioEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        localAudioTrack?.setEnabled(!muted)
        (callRecorder as? DesktopCallRecorder)?.setLocalMuted(muted)
    }

    fun isMuted(): Boolean = isMuted

    fun setRemoteAudioEnabled(enabled: Boolean) {
        remoteAudioEnabled = enabled
        runCatching {
            val track = remoteAudioTrack ?: return@runCatching
            if (callRecorder.isStreaming()) {
                // Desktop: NO se puede silenciar selectivamente el playout de WebRTC.
                // - track.setEnabled(false) mata los AudioTrackSink (no llegan datos al servidor de traducción)
                // - setSpeakerMute/setSpeakerVolume afectan el endpoint de audio de Windows completo (silencia JavaSound también)
                // - stopPlayout() detiene todo el pipeline de audio, incluyendo AudioTrackSink
                // El usuario escucha ambos: audio original + voz traducida de la IA.
                // Solución futura: usar HeadlessAudioDeviceModule + enrutamiento manual de audio.
                log.d(TAG) { "setRemoteAudioEnabled($enabled) - no-op durante streaming (Desktop)" }
                return@runCatching
            }
            track.setEnabled(enabled)
            log.d(TAG) { "setRemoteAudioEnabled($enabled)" }
        }.onFailure {
            log.e(TAG) { "Error setRemoteAudioEnabled($enabled): ${it.message}" }
        }
    }

    fun isRemoteAudioEnabled(): Boolean = remoteAudioEnabled

    // ==================== DTMF ====================

    /** El sender capturado no cambia de destino al recrear la conexión. */
    fun captureDtmfSender(): ((String, Int, Int) -> Boolean)? {
        val sender = dtmfSender ?: return null
        return { tones, duration, gap ->
            try {
                if (dtmfSender !== sender) false
                else { sender.insertDtmf(tones, duration, gap); true }
            } catch (_: Exception) { false }
        }
    }

    fun sendDtmf(tones: String, duration: Int, gap: Int): Boolean {
        return try {
            val sender = dtmfSender ?: return false

            val validTones = tones.filter {
                it.isDigit() || it in "ABCD*#"
            }

            if (validTones.isEmpty()) return false

            sender.insertDtmf(validTones, duration, gap)
            log.d(TAG) { "DTMF enviado" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error sending DTMF: ${e.message}" }
            false
        }
    }

    // ==================== RECORDING ====================

    private fun setupRemoteAudioCapture(track: Any) {
        try {
            remoteAudioCapture?.stopCapture()

            remoteAudioCapture = createRemoteAudioCapture(
                remoteTrack = track,
                callback = object : AudioCaptureCallback {
                    override fun onAudioData(
                        data: ByteArray,
                        bitsPerSample: Int,
                        sampleRate: Int,
                        channels: Int,
                        frames: Int,
                        timestampMs: Long
                    ) {
                        if (callRecorder.isRecording() || callRecorder.isStreaming()) {
                            callRecorder.captureRemoteAudio(data, sampleRate, channels, bitsPerSample)
                        }
                    }
                }
            )

            if (callRecorder.isRecording() || callRecorder.isStreaming()) {
                remoteAudioCapture?.startCapture()
            }

            log.d(TAG) { "✅ Remote audio capture configured" }
        } catch (e: Exception) {
            log.e(TAG) { "Error setting up remote audio capture: ${e.message}" }
        }
    }

    fun startRecording(callId: String) {
        log.d(TAG) { "🎙️ Starting call recording" }
        callRecorder.startRecording(callId)
        remoteAudioCapture?.startCapture()
        log.d(TAG) { "✅ Recording started for call: $callId" }
    }

    suspend fun stopRecording(): RecordingResult? {
        log.d(TAG) { "🛑 Stopping call recording" }
        remoteAudioCapture?.stopCapture()
        val result = callRecorder.stopRecording()
        log.d(TAG) { "✅ Recording stopped" }
        return result
    }

    fun isRecording(): Boolean = callRecorder.isRecording()

    fun getRecorder(): CallRecorder = callRecorder

    // ==================== STREAMING EN TIEMPO REAL ====================

    fun setAudioStreamListener(listener: AudioStreamListener?) {
        callRecorder.setAudioStreamListener(listener)
    }

    fun startStreaming(callId: String) {
        log.d(TAG) { "🎙️ Starting audio streaming" }
        log.d(TAG) { "remoteAudioCapture=${if (remoteAudioCapture != null) "SET" else "NULL"}, remoteAudioTrack=${if (remoteAudioTrack != null) "SET" else "NULL"}" }
        callRecorder.startStreaming(callId)
        remoteAudioCapture?.startCapture()
        log.d(TAG) { "✅ Audio streaming started for call: $callId" }
    }

    fun stopStreaming() {
        log.d(TAG) { "Stopping audio streaming" }
        callRecorder.stopStreaming()
        // Restaurar estado del track remoto
        runCatching {
            remoteAudioTrack?.setEnabled(remoteAudioEnabled)
        }
        if (!callRecorder.isRecording()) {
            remoteAudioCapture?.stopCapture()
        }
        log.d(TAG) { "Audio streaming stopped" }
    }

    fun isStreaming(): Boolean = callRecorder.isStreaming()

    // ==================== STATE & CLEANUP ====================

    fun getConnectionState(): WebRtcConnectionState = currentConnectionState

    fun getLocalDescription(): String? = peerConnection?.localDescription?.sdp

    // === DATA CHANNEL ===

    /**
     * Crea un data channel en el publisher PeerConnection para enviar mensajes.
     * Debe llamarse ANTES de createOffer() para que el DC aparezca en el SDP.
     */
    fun createPublisherDataChannel(label: String = "_reliable") {
        val pc = peerConnection ?: run {
            log.w(TAG) { "No PeerConnection para crear data channel" }
            return
        }
        try {
            val init = RTCDataChannelInit()
            init.ordered = true
            publisherDataChannel = pc.createDataChannel(label, init)
            publisherDataChannel?.registerObserver(object : RTCDataChannelObserver {
                override fun onBufferedAmountChange(previousAmount: Long) {}
                override fun onStateChange() {
                    log.d(TAG) { "Publisher data channel state: ${publisherDataChannel?.state}" }
                }
                override fun onMessage(buffer: RTCDataChannelBuffer) {}
            })
            log.d(TAG) { "Publisher data channel creado: $label" }
        } catch (e: Exception) {
            log.e(TAG) { "Error creando data channel: ${e.message}" }
        }
    }

    /**
     * Envia datos via el publisher data channel (o el received data channel como fallback).
     */
    fun sendDataChannelMessage(data: ByteArray): Boolean {
        val channel = publisherDataChannel ?: receivedDataChannel
        if (channel == null) {
            log.w(TAG) { "No hay data channel disponible para enviar" }
            return false
        }
        return try {
            val buffer = RTCDataChannelBuffer(java.nio.ByteBuffer.wrap(data), false)
            channel.send(buffer)
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error enviando data channel message: ${e.message}" }
            false
        }
    }

    // ==================== VIDEO CAPTURE (conferencias) ====================

    /**
     * Enumera las cámaras disponibles en el sistema via MediaDevices.
     */
    fun enumerateVideoDevices(): List<VideoDevice> {
        return try {
            MediaDevices.getVideoCaptureDevices() ?: emptyList()
        } catch (e: Exception) {
            log.e(TAG) { "Error enumerando cámaras: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Agrega un video track local (cámara) al PeerConnection.
     * @param device cámara a usar, null = primera disponible
     * @return el VideoTrack creado, o null si falla
     */
    fun addLocalVideoTrack(device: VideoDevice? = null): VideoTrack? {
        val factory = peerConnectionFactory ?: return null
        val pc = peerConnection ?: return null

        try {
            val cam = device ?: MediaDevices.getVideoCaptureDevices()?.firstOrNull()
            if (cam == null) {
                log.w(TAG) { "No hay cámaras disponibles" }
                return null
            }

            val source = VideoDeviceSource()
            source.setVideoCaptureDevice(cam)
            source.setVideoCaptureCapability(VideoCaptureCapability(640, 480, 30))
            videoDeviceSource = source

            val videoTrack = factory.createVideoTrack(LocalTrackIds.VIDEO, source)
            localVideoTrack = videoTrack

            val sender = pc.addTrack(videoTrack, listOf("stream0"))
            videoSender = sender

            source.start()

            log.d(TAG) { "✅ Local video track added (${cam.name})" }
            return videoTrack
        } catch (e: Exception) {
            log.e(TAG) { "Error adding local video track: ${e.message}" }
            return null
        }
    }

    /**
     * Remueve el video track local (apaga cámara).
     */
    fun removeLocalVideoTrack() {
        try {
            videoDeviceSource?.stop()
            videoDeviceSource?.dispose()
        } catch (_: Throwable) {}
        videoDeviceSource = null

        try {
            videoSender?.let { peerConnection?.removeTrack(it) }
        } catch (_: Throwable) {}
        videoSender = null

        try {
            localVideoTrack?.setEnabled(false)
            localVideoTrack?.dispose()
        } catch (_: Throwable) {}
        localVideoTrack = null

        log.d(TAG) { "Video track removed" }
    }

    fun getLocalVideoTrack(): VideoTrack? = localVideoTrack

    fun addLocalScreenShareTrack(sourceId: String? = null): VideoTrack? {
        val factory = peerConnectionFactory ?: return null
        val pc = peerConnection ?: return null

        if (localScreenTrack != null) {
            return localScreenTrack
        }

        // En Linux el capturador nativo de webrtc-java está compilado sin PipeWire:
        // solo captura por X11 y bajo Wayland eso devuelve pantalla en negro. Ahí se
        // usa xdg-desktop-portal y se inyectan los frames con CustomVideoSource,
        // conservando el mismo camino de publicación.
        if (DesktopScreenCaptureSupport.backend() == DesktopScreenCaptureSupport.Backend.PORTAL) {
            return addPortalScreenShareTrack(factory, pc, sourceId)
        }
        if (DesktopScreenCaptureSupport.backend() == DesktopScreenCaptureSupport.Backend.NONE) {
            throw ScreenShareUnavailableException(
                DesktopScreenCaptureSupport.unavailableReason()
                    ?: "La captura de pantalla no está disponible"
            )
        }

        var capturer: ScreenCapturer? = null
        return try {
            capturer = ScreenCapturer()
            val sources = capturer.getDesktopSources()
            val screen = sources.firstOrNull { it.id.toString() == sourceId } ?: sources.firstOrNull()
            if (screen == null) {
                if (isMacHost()) {
                    log.e(TAG) {
                        "macOS: sin pantallas para compartir. Concede 'Grabación de pantalla' a " +
                            "la app (o al JDK) en Ajustes del Sistema > Privacidad y seguridad. Si ya " +
                            "está concedido y sigue vacío, el capturer nativo webrtc-java 0.10.0/macOS " +
                            "no soporta captura de escritorio (requiere upgrade nativo o ScreenCaptureKit)."
                    }
                } else {
                    log.w(TAG) { "No hay pantallas disponibles para compartir" }
                }
                return null
            }

            val source = VideoDesktopSource().apply {
                setSourceId(screen.id, false)
                setFrameRate(15)
                setMaxFrameSize(1280, 720)
            }
            screenVideoSource = source

            val track = factory.createVideoTrack(LocalTrackIds.SCREEN, source)
            localScreenTrack = track
            screenSender = pc.addTrack(track, listOf("stream0"))

            source.start()

            log.d(TAG) { "✅ Screen share track added (${screen.title})" }
            track
        } catch (e: Throwable) {
            log.e(TAG) {
                "Error adding screen share track (os=${System.getProperty("os.name")}): " +
                    "${e::class.simpleName}: ${e.message}"
            }
            removeLocalScreenShareTrack()
            null
        } finally {
            try {
                capturer?.dispose()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Captura vía xdg-desktop-portal + PipeWire (Linux/Wayland).
     *
     * Solo cambia de dónde salen los frames: la creación del track y el
     * `pc.addTrack` son idénticos al camino nativo, así que la señalización y la
     * renegociación SDP quedan intactas.
     */
    private fun addPortalScreenShareTrack(
        factory: PeerConnectionFactory,
        pc: RTCPeerConnection,
        sourceId: String?,
    ): VideoTrack? {
        var session: PortalScreenCastSession? = null
        var capture: PipeWireVideoCapture? = null
        var source: CustomVideoSource? = null
        return try {
            // "portal" como sourceId significa "quiero volver a elegir qué comparto".
            session = PortalScreenCastSession.open(forcePicker = sourceId == PORTAL_SOURCE_ID)
            portalSession = session

            source = CustomVideoSource()
            screenCustomSource = source
            capture = PipeWireVideoCapture.start(session.nodeId, source)
            pipeWireCapture = capture

            val track = factory.createVideoTrack(LocalTrackIds.SCREEN, source)
            localScreenTrack = track
            screenSender = pc.addTrack(track, listOf("stream0"))

            log.d(TAG) { "✅ Screen share por portal iniciado (node ${session.nodeId})" }
            track
        } catch (e: Throwable) {
            runCatching { capture?.stop() }
            runCatching { session?.close() }
            runCatching { source?.dispose() }
            portalSession = null
            pipeWireCapture = null
            screenCustomSource = null
            // Se propaga para que la UI muestre el motivo en vez de fallar en silencio.
            throw e
        }
    }

    fun removeLocalScreenShareTrack() {
        // Orden importante: cortar el pipeline y el hilo de frames ANTES de soltar
        // la fuente, o se libera memoria nativa mientras se está empujando un frame.
        try {
            pipeWireCapture?.stop()
        } catch (_: Throwable) {}
        pipeWireCapture = null

        try {
            portalSession?.close()
        } catch (_: Throwable) {}
        portalSession = null

        try {
            screenCustomSource?.dispose()
        } catch (_: Throwable) {}
        screenCustomSource = null

        try {
            screenVideoSource?.stop()
            screenVideoSource?.dispose()
        } catch (_: Throwable) {}
        screenVideoSource = null

        try {
            screenSender?.let { peerConnection?.removeTrack(it) }
        } catch (_: Throwable) {}
        screenSender = null

        try {
            localScreenTrack?.setEnabled(false)
            localScreenTrack?.dispose()
        } catch (_: Throwable) {}
        localScreenTrack = null

        log.d(TAG) { "Screen share track removed" }
    }

    fun getLocalScreenShareTrack(): VideoTrack? = localScreenTrack


    private fun isMacHost(): Boolean =
        System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }

    /** Cierra la conexión y libera sus pistas y fuentes; factoría y ADM siguen vivos para la siguiente. */
    fun closePeerConnection() {
        log.d(TAG) { "Closing PeerConnection" }

        stopInjectionPacer()
        localAudioInjectionActive = false

        removeLocalScreenShareTrack()
        removeLocalVideoTrack()

        runCatching { remoteAudioCapture?.stopCapture() }
        remoteAudioCapture = null

        runCatching { localAudioTrack?.setEnabled(false) }

        try {
            peerConnection?.senders?.forEach { sender ->
                runCatching { peerConnection?.removeTrack(sender) }
            }
        } catch (e: Throwable) {
            log.w(TAG) { "Error removing senders: ${e.message}" }
        }
        audioSender = null
        dtmfSender = null

        try {
            peerConnection?.close()
        } catch (e: Throwable) {
            log.w(TAG) { "Error closing peer connection: ${e.message}" }
        }
        peerConnection = null
        publisherDataChannel = null
        receivedDataChannel = null
        remoteAudioTrack = null

        releaseInjectionResources()
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        runCatching { localAudioSource?.dispose() }
        localAudioSource = null

        currentConnectionState = WebRtcConnectionState.DISCONNECTED

        log.d(TAG) { "PeerConnection closed" }
    }

    /** Sin reiniciar la captura: la próxima llamada la arranca WebRTC al añadir su sender. */
    private fun releaseInjectionResources() {
        remoteAudioInjectionActive = false
        remoteInjectionBuffer.clear()
        val released = synchronized(injectionStateLock) {
            localAudioEnabled = true
            localAudioInjectionActive = false
            val pair = injectionAudioTrack to customAudioSource
            injectionAudioTrack = null
            customAudioSource = null
            injectionPipeline = null
            pair
        }
        deviceCaptureSuspended = false

        runCatching { released.first?.dispose() }
        runCatching { released.second?.dispose() }
    }

    /** Libera todo: conexión, factoría y ADM. La instancia no se reutiliza después. */
    fun dispose() {
        log.d(TAG) { "Disposing PeerConnectionController" }

        closePeerConnection()

        runCatching { injectionFactory?.dispose() }
        injectionFactory = null
        runCatching { injectionAudioModule?.dispose() }
        injectionAudioModule = null

        try {
            peerConnectionFactory?.dispose()
        } catch (e: Throwable) {
            log.w(TAG) { "Error disposing factory: ${e.message}" }
        }
        peerConnectionFactory = null

        try {
            audioDeviceModule?.dispose()
        } catch (e: Throwable) {
            log.w(TAG) { "Error releasing audio module: ${e.message}" }
        }
        audioDeviceModule = null

        callRecorder.dispose()
        scope.cancel()

        log.d(TAG) { "All resources disposed" }
    }

    fun diagnose(): String {
        return buildString {
            appendLine("=== Desktop PeerConnection Diagnostics ===")
            appendLine("PeerConnection: ${peerConnection != null}")
            appendLine("Local Audio Track: ${localAudioTrack != null}")
            appendLine("Track Enabled: ${localAudioTrack?.isEnabled}")
            appendLine("Muted: $isMuted")
            appendLine("Connection State: $currentConnectionState")
            appendLine("Recording: ${isRecording()}")
            appendLine("Audio Device Module: ${audioDeviceModule != null}")
            try {
                appendLine("Local Description: ${getLocalDescription()?.take(100)}...")
            } catch (e: Exception) {
                appendLine("Local Description: ERROR")
            }
        }
    }
}


