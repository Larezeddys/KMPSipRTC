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
import com.eddyslarez.kmpsiprtc.services.recording.createCallRecorder
import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.*
import dev.onvoid.webrtc.media.audio.*
import dev.onvoid.webrtc.media.video.*
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

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
    private var remoteAudioTrack: AudioTrack? = null
    private var dtmfSender: RTCDtmfSender? = null

    // === INYECCIÓN DE AUDIO PARA TRADUCCIÓN ===
    // CustomAudioSource permite inyectar PCM directamente como fuente de audio
    private var customAudioSource: CustomAudioSource? = null
    // Track creado desde el CustomAudioSource para reemplazar el mic real
    private var injectionAudioTrack: AudioTrack? = null
    // AudioResampler para convertir 24kHz→48kHz (el ADM de WebRTC opera a 48kHz)
    private var audioResampler: AudioResampler? = null
    // Flag que indica si la inyección de audio local está activa
    @Volatile
    private var localAudioInjectionActive = false

    // === VIDEO para conferencias ===
    private var videoDeviceSource: VideoDeviceSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoSender: RTCRtpSender? = null
    private var screenVideoSource: VideoDesktopSource? = null
    private var localScreenTrack: VideoTrack? = null
    private var screenSender: RTCRtpSender? = null
    // Callback para cuando llega un video track remoto
    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null

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

    // Recording
    private val callRecorder: CallRecorder by lazy { createCallRecorder() }
    private var remoteAudioCapture: AudioTrackCapture? = null

    // State
    private var isMuted = false
    @Volatile
    private var remoteAudioEnabled = true
    @Volatile
    private var currentConnectionState = WebRtcConnectionState.DISCONNECTED

    private val iceServers = listOf(
        RTCIceServer().apply {
            urls = listOf(
                "stun:stun.l.google.com:19302",
                "stun:stun1.l.google.com:19302"
            )
        }
    )

    // ==================== AUDIO DEVICE MODULE ====================

    fun getAudioDeviceModule(): AudioDeviceModule? = audioDeviceModule

    // ==================== INITIALIZATION ====================

    fun initialize() {
        log.d(TAG) { "Initializing PeerConnectionController..." }

        try {
            audioDeviceModule = AudioDeviceModule(AudioLayer.kPlatformDefaultAudio)
            peerConnectionFactory = PeerConnectionFactory(audioDeviceModule, null)

            log.d(TAG) { "✅ PeerConnectionController initialized" }
        } catch (e: Exception) {
            log.e(TAG) { "Error initializing: ${e.message}" }
            e.printStackTrace()
        }
    }

    // ==================== PEER CONNECTION ====================

    fun hasPeerConnection(): Boolean = peerConnection != null

    fun createNewPeerConnection() {
        log.d(TAG) { "Creating new PeerConnection" }

        // Limpiar recursos del PeerConnection anterior antes de crear uno nuevo.
        // Evita acumulación de audio tracks al cambiar entre llamadas múltiples veces.
        try {
            peerConnection?.senders?.forEach { sender ->
                try { peerConnection?.removeTrack(sender) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        peerConnection?.close()
        peerConnection = null

        // Disponer el audio track anterior para liberar recursos del ADM
        try {
            localAudioTrack?.setEnabled(false)
            localAudioTrack?.dispose()
        } catch (_: Throwable) {}
        localAudioTrack = null

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
                    }
                }
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
            override fun onRemoveStream(stream: MediaStream) {}
        }
    }

    private fun addLocalAudioTrack() {
        try {
            val factory = peerConnectionFactory ?: return
            val pc = peerConnection ?: return

            val audioOptions = AudioOptions().apply {
                noiseSuppression = true
                autoGainControl = true
                highpassFilter = true
            }

            val audioSource = factory.createAudioSource(audioOptions)
            localAudioTrack = factory.createAudioTrack("audio0", audioSource)

            val sender = pc.addTrack(localAudioTrack, listOf("stream0"))
            audioSender = sender  // Guardar referencia al sender para replaceTrack()
            dtmfSender = sender.dtmfSender

            log.d(TAG) { "✅ Local audio track added" }
        } catch (e: Exception) {
            log.e(TAG) { "Error adding local audio track: ${e.message}" }
            e.printStackTrace()
        }
    }

    // ==================== INYECCIÓN DE AUDIO PARA TRADUCCIÓN ====================

    /**
     * Habilitar/deshabilitar el audio local (micrófono) que se envía al peer remoto.
     * Cuando se deshabilita, se reemplaza el track del mic con un CustomAudioSource
     * que acepta PCM inyectado via injectLocalAudio().
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
            // Activar inyección: crear CustomAudioSource y reemplazar el track del mic
            activateLocalAudioInjection()
        } else {
            // Desactivar inyección: restaurar el track original del mic
            deactivateLocalAudioInjection()
        }
    }

    fun isLocalAudioEnabled(): Boolean = localAudioEnabled

    /**
     * Activa la inyección de audio local reemplazando el track del mic real
     * con un CustomAudioSource que acepta datos PCM via pushAudio().
     */
    private fun activateLocalAudioInjection() {
        try {
            val factory = peerConnectionFactory ?: run {
                log.e(TAG) { "Cannot activate injection: factory is null" }
                return
            }
            val sender = audioSender ?: run {
                log.e(TAG) { "Cannot activate injection: audioSender is null" }
                return
            }

            stopInjectionPacer()

            // Importante: evitar doble productor (mic nativo + custom source)
            // en AudioSendStream, que dispara RaceChecker en WebRTC.
            runCatching { localAudioTrack?.setEnabled(false) }

            // Crear CustomAudioSource (fuente programática de audio)
            customAudioSource = CustomAudioSource()

            // Crear un nuevo AudioTrack basado en el CustomAudioSource
            injectionAudioTrack = factory.createAudioTrack("injection0", customAudioSource)
            injectionAudioTrack?.setEnabled(true)

            // Reemplazar el track del mic real con el de inyección en el sender RTP
            sender.replaceTrack(injectionAudioTrack)

            localAudioInjectionActive = true
            // El pacer se inicia de forma perezosa al llegar el primer chunk traducido.
            // Evita empujar audio antes de que replaceTrack termine de estabilizarse.
            log.d(TAG) { "Local audio injection armed - waiting first chunk" }
        } catch (e: Exception) {
            log.e(TAG) { "Error activating local audio injection: ${e.message}" }
            e.printStackTrace()
            // Fallback: simplemente deshabilitar el track
            localAudioTrack?.setEnabled(false)
        }
    }

    /**
     * Desactiva la inyección de audio local y restaura el track original del mic.
     */
    private fun deactivateLocalAudioInjection() {
        try {
            val sender = audioSender ?: return

            // Primero detener el productor custom para no solaparlo con el mic al restaurar.
            stopInjectionPacer()
            localAudioInjectionActive = false

            // Restaurar el track original del mic
            if (localAudioTrack != null) {
                sender.replaceTrack(localAudioTrack)
                localAudioTrack?.setEnabled(!isMuted)
            }

            try {
                injectionAudioTrack?.setEnabled(false)
                injectionAudioTrack?.dispose()
            } catch (_: Throwable) {}
            injectionAudioTrack = null

            try {
                customAudioSource?.dispose()
            } catch (_: Throwable) {}
            customAudioSource = null

            try {
                audioResampler?.dispose()
            } catch (_: Throwable) {}
            audioResampler = null

            log.d(TAG) { "✅ Local audio injection deactivated - mic restored" }
        } catch (e: Exception) {
            log.e(TAG) { "Error deactivating local audio injection: ${e.message}" }
            e.printStackTrace()
        }
    }

    /**
     * Inyecta datos de audio PCM que se enviarán al peer remoto.
     * Los datos deben ser PCM 16-bit little-endian mono.
     * Se resamplea automáticamente si el sampleRate difiere de 48kHz.
     *
     * @param pcmData Audio PCM 16-bit LE mono
     * @param sampleRate Frecuencia de muestreo (ej: 24000)
     * @param channels Número de canales (1 = mono)
     * @param bitsPerSample Bits por muestra (16)
     */
    // Tamaño máximo de un frame de audio WebRTC: 10ms a 48kHz mono 16-bit = 480 frames = 960 bytes
    // El AudioFrame nativo tiene un buffer de 3840 samples (7680 bytes), pero usamos 10ms
    // para máxima compatibilidad y mínima latencia.
    private val WEBRTC_FRAME_SAMPLES = 480  // 10ms @ 48kHz
    private val WEBRTC_FRAME_BYTES = WEBRTC_FRAME_SAMPLES * 2  // 16-bit mono

    fun injectLocalAudio(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        if (!localAudioInjectionActive) return
        if (customAudioSource == null) return

        try {
            ensureInjectionPacerStarted()

            val targetSampleRate = 48000
            val data: ByteArray = if (sampleRate != targetSampleRate) {
                resamplePcm16(pcmData, sampleRate, targetSampleRate)
            } else {
                pcmData
            }

            // Encolar frames de 10ms para que el scheduler los empuje a ritmo real
            var offset = 0
            while (offset < data.size) {
                val chunkBytes = minOf(WEBRTC_FRAME_BYTES, data.size - offset)
                val chunk = data.copyOfRange(offset, offset + chunkBytes)
                injectionFrameQueue.offer(chunk)
                offset += chunkBytes
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error enqueuing local audio: ${e.message}" }
        }
    }

    /**
     * Inicia un scheduler que empuja frames al CustomAudioSource de forma adaptativa.
     * En Windows, ScheduledExecutorService tiene ~15.6ms de resolución real en vez de 10ms.
     * Para compensar, cada tick calcula cuántos frames DEBERÍAN haberse empujado basándose
     * en el tiempo real transcurrido, y empuja los que falten de una vez.
     * Esto mantiene la velocidad correcta del audio independientemente de la resolución del timer.
     */
    private fun startInjectionPacer() {
        // Detener pacer anterior sin modificar localAudioInjectionActive
        injectionScheduler?.shutdownNow()
        injectionScheduler = null
        pacerStartNanos = System.nanoTime()
        pacerFramesPushed = 0L
        val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "audio-injection-pacer").apply { isDaemon = true }
        }
        injectionScheduler = scheduler
        scheduler.scheduleAtFixedRate({
            try {
                val source = customAudioSource ?: return@scheduleAtFixedRate
                val now = System.nanoTime()
                val elapsedNanos = now - pacerStartNanos
                // Cuántos frames de 10ms deberían haberse empujado hasta ahora
                val targetFrames = elapsedNanos / 10_000_000L

                // Empujar los frames pendientes (máximo 5 por tick para evitar burst excesivo)
                var pushed = 0
                while (pacerFramesPushed < targetFrames && pushed < 5) {
                    val frame = injectionFrameQueue.poll()
                    if (frame == null) {
                        // Cola vacía: resetear el reloj lógico para que cuando lleguen
                        // nuevos frames se empujen a ritmo real sin burst ni delay.
                        // Sin esto, el reloj avanzaría en vacío y los nuevos frames
                        // se empujarían como burst instantáneo → audio distorsionado.
                        pacerStartNanos = now
                        pacerFramesPushed = 0L
                        break
                    }
                    val chunkFrames = frame.size / 2  // 16-bit mono
                    source.pushAudio(frame, 16, 48000, 1, chunkFrames)
                    pacerFramesPushed++
                    pushed++
                }
            } catch (_: Exception) {}
        }, 0, 5, TimeUnit.MILLISECONDS) // 5ms schedule → fires cada ~8-15ms en Windows
        log.d(TAG) { "Injection pacer started (adaptive timing)" }
    }

    private fun ensureInjectionPacerStarted() {
        if (injectionScheduler != null) return
        synchronized(injectionStateLock) {
            if (injectionScheduler != null) return
            if (!localAudioInjectionActive) return
            if (customAudioSource == null) return
            startInjectionPacer()
        }
    }

    private fun stopInjectionPacer() {
        injectionScheduler?.shutdownNow()
        injectionScheduler = null
        injectionFrameQueue.clear()
        log.d(TAG) { "Injection pacer stopped" }
    }

    /** Resamplea PCM16 little-endian mono con interpolación lineal */
    private fun resamplePcm16(pcm: ByteArray, fromRate: Int, toRate: Int): ByteArray {
        if (fromRate == toRate) return pcm
        val bytesPerSample = 2
        val inputFrames = pcm.size / bytesPerSample
        val ratio = toRate.toDouble() / fromRate.toDouble()
        val outputFrames = (inputFrames * ratio).toInt()
        val output = ByteArray(outputFrames * bytesPerSample)

        for (i in 0 until outputFrames) {
            val srcPos = i / ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx

            val s0 = if (srcIdx < inputFrames) pcm16At(pcm, srcIdx) else 0
            val s1 = if (srcIdx + 1 < inputFrames) pcm16At(pcm, srcIdx + 1) else s0

            val interpolated = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)
            output[i * 2] = (interpolated and 0xFF).toByte()
            output[i * 2 + 1] = (interpolated shr 8).toByte()
        }
        return output
    }

    /** Lee una muestra PCM16 little-endian signed por índice de frame */
    private fun pcm16At(buf: ByteArray, frameIdx: Int): Int {
        val off = frameIdx * 2
        val lo = buf[off].toInt() and 0xFF
        val hi = buf[off + 1].toInt()
        return (hi shl 8) or lo
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
            e.printStackTrace()
        }
    }

    // ==================== AUDIO CONTROL ====================

    fun setAudioEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        localAudioTrack?.setEnabled(!muted)
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

    fun sendDtmf(tones: String, duration: Int, gap: Int): Boolean {
        return try {
            val sender = dtmfSender ?: return false

            val validTones = tones.filter {
                it.isDigit() || it in "ABCD*#"
            }

            if (validTones.isEmpty()) return false

            sender.insertDtmf(validTones, duration, gap)
            log.d(TAG) { "✅ DTMF sent: $validTones" }
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

            val videoTrack = factory.createVideoTrack("video0", source)
            localVideoTrack = videoTrack

            val sender = pc.addTrack(videoTrack, listOf("stream0"))
            videoSender = sender

            source.start()

            log.d(TAG) { "✅ Local video track added (${cam.name})" }
            return videoTrack
        } catch (e: Exception) {
            log.e(TAG) { "Error adding local video track: ${e.message}" }
            e.printStackTrace()
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

        var capturer: ScreenCapturer? = null
        return try {
            capturer = ScreenCapturer()
            val sources = capturer.getDesktopSources()
            val screen = sources.firstOrNull { it.id.toString() == sourceId } ?: sources.firstOrNull()
            if (screen == null) {
                log.w(TAG) { "No hay pantallas disponibles para compartir" }
                return null
            }

            val source = VideoDesktopSource().apply {
                setSourceId(screen.id, false)
                setFrameRate(15)
                setMaxFrameSize(1280, 720)
            }
            screenVideoSource = source

            val track = factory.createVideoTrack("screen0", source)
            localScreenTrack = track
            screenSender = pc.addTrack(track, listOf("stream0"))

            source.start()

            log.d(TAG) { "✅ Screen share track added (${screen.title})" }
            track
        } catch (e: Exception) {
            log.e(TAG) { "Error adding screen share track: ${e.message}" }
            e.printStackTrace()
            removeLocalScreenShareTrack()
            null
        } finally {
            try {
                capturer?.dispose()
            } catch (_: Throwable) {}
        }
    }

    fun removeLocalScreenShareTrack() {
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

    fun closePeerConnection() {
        log.d(TAG) { "Closing PeerConnection" }

        stopInjectionPacer()

        // Limpiar video
        removeLocalScreenShareTrack()
        removeLocalVideoTrack()

        try {
            localAudioTrack?.setEnabled(false)
            remoteAudioCapture?.stopCapture()
        } catch (e: Exception) {
            log.w(TAG) { "Error disabling track: ${e.message}" }
        }

        peerConnection?.close()
        peerConnection = null
        remoteAudioTrack = null
        currentConnectionState = WebRtcConnectionState.DISCONNECTED

        log.d(TAG) { "PeerConnection closed" }
    }

    fun dispose() {
        log.d(TAG) { "Disposing PeerConnectionController" }

        remoteAudioCapture?.stopCapture()
        remoteAudioCapture = null

        // Detener hilo de inyección y limpiar recursos
        stopInjectionPacer()
        localAudioInjectionActive = false
        remoteAudioInjectionActive = false
        remoteInjectionBuffer.clear()

        try {
            injectionAudioTrack?.setEnabled(false)
            injectionAudioTrack?.dispose()
        } catch (_: Throwable) {}
        injectionAudioTrack = null

        try {
            customAudioSource?.dispose()
        } catch (_: Throwable) {}
        customAudioSource = null

        try {
            audioResampler?.dispose()
        } catch (_: Throwable) {}
        audioResampler = null

        try {
            localAudioTrack?.setEnabled(false)
        } catch (e: Throwable) {
            log.w(TAG) { "Error disabling track: ${e.message}" }
        }

        // Remover senders del PeerConnection antes de cerrar para liberar referencias a tracks
        try {
            peerConnection?.senders?.forEach { sender ->
                try {
                    peerConnection?.removeTrack(sender)
                } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            log.w(TAG) { "Error removing senders: ${e.message}" }
        }

        audioSender = null

        try {
            peerConnection?.close()
            peerConnection = null
        } catch (e: Throwable) {
            log.w(TAG) { "Error closing peer connection: ${e.message}" }
        }

        remoteAudioTrack = null

        Thread.sleep(200)

        try {
            localAudioTrack?.dispose()
        } catch (e: Throwable) {
            log.w(TAG) { "Error disposing track: ${e.message}" }
        }
        localAudioTrack = null

        try {
            audioDeviceModule?.dispose()
        } catch (e: Throwable) {
            log.w(TAG) { "Error releasing audio module: ${e.message}" }
        }
        audioDeviceModule = null

        try {
            peerConnectionFactory?.dispose()
        } catch (e: Throwable) {
            log.w(TAG) { "Error disposing factory: ${e.message}" }
        }
        peerConnectionFactory = null

        callRecorder.dispose()
        scope.cancel()

        currentConnectionState = WebRtcConnectionState.DISCONNECTED

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


