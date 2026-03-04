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
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
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
    // Flag que indica si el audio local (mic) está habilitado en WebRTC
    @Volatile
    private var localAudioEnabled = true
    // Referencia al sender de audio para poder hacer replaceTrack()
    private var audioSender: RTCRtpSender? = null
    // Buffer para audio remoto inyectado (traducido)
    private val remoteInjectionBuffer = ConcurrentLinkedQueue<ByteArray>()
    @Volatile
    private var remoteAudioInjectionActive = false

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
                log.d(TAG) { "Remote track received" }
                val track = transceiver.receiver?.track
                if (track?.kind == "audio") {
                    (track as? AudioTrack)?.let { audioTrack ->
                        remoteAudioTrack = audioTrack
                        audioTrack.setEnabled(remoteAudioEnabled)
                    }
                    // Configurar captura de audio remoto
                    setupRemoteAudioCapture(track)
                    onRemoteAudioTrack()
                }
            }

            override fun onSignalingChange(state: RTCSignalingState) {}
            override fun onIceGatheringChange(state: RTCIceGatheringState) {}
            override fun onIceConnectionChange(state: RTCIceConnectionState) {}
            override fun onStandardizedIceConnectionChange(state: RTCIceConnectionState) {}
            override fun onDataChannel(dataChannel: RTCDataChannel) {}
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
        if (localAudioEnabled == enabled) return
        localAudioEnabled = enabled

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

            // Crear CustomAudioSource (fuente programática de audio)
            customAudioSource = CustomAudioSource()

            // Crear un nuevo AudioTrack basado en el CustomAudioSource
            injectionAudioTrack = factory.createAudioTrack("injection0", customAudioSource)
            injectionAudioTrack?.setEnabled(true)

            // Reemplazar el track del mic real con el de inyección en el sender RTP
            sender.replaceTrack(injectionAudioTrack)

            localAudioInjectionActive = true
            log.d(TAG) { "✅ Local audio injection activated - mic replaced with CustomAudioSource" }
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

            // Restaurar el track original del mic
            if (localAudioTrack != null) {
                sender.replaceTrack(localAudioTrack)
                localAudioTrack?.setEnabled(!isMuted)
            }

            // Limpiar recursos de inyección
            localAudioInjectionActive = false

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
    fun injectLocalAudio(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        if (!localAudioInjectionActive) return
        val source = customAudioSource ?: return

        try {
            val targetSampleRate = 48000
            val data: ByteArray
            val actualSampleRate: Int
            val frames: Int

            if (sampleRate != targetSampleRate) {
                // Resamplear al sample rate del WebRTC (48kHz)
                if (audioResampler == null) {
                    audioResampler = AudioResampler(sampleRate, targetSampleRate, channels)
                }

                val inputFrames = pcmData.size / (channels * (bitsPerSample / 8))
                val outputFrames = (inputFrames.toLong() * targetSampleRate / sampleRate).toInt()
                val outputBytes = outputFrames * channels * (bitsPerSample / 8)
                val outputBuffer = ByteArray(outputBytes)

                val resampledFrames = audioResampler!!.resample(
                    pcmData, pcmData.size,
                    outputBuffer, outputBytes,
                    inputFrames
                )

                data = outputBuffer
                actualSampleRate = targetSampleRate
                frames = resampledFrames
            } else {
                data = pcmData
                actualSampleRate = sampleRate
                frames = pcmData.size / (channels * (bitsPerSample / 8))
            }

            // Enviar al CustomAudioSource que lo inyecta en el pipeline de WebRTC
            source.pushAudio(data, bitsPerSample, actualSampleRate, channels, frames)
        } catch (e: Exception) {
            log.e(TAG) { "Error injecting local audio: ${e.message}" }
        }
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
            remoteAudioTrack?.setEnabled(enabled)
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
        callRecorder.startStreaming(callId)
        remoteAudioCapture?.startCapture()
        log.d(TAG) { "✅ Audio streaming started for call: $callId" }
    }

    fun stopStreaming() {
        log.d(TAG) { "🛑 Stopping audio streaming" }
        callRecorder.stopStreaming()
        if (!callRecorder.isRecording()) {
            remoteAudioCapture?.stopCapture()
        }
        log.d(TAG) { "✅ Audio streaming stopped" }
    }

    fun isStreaming(): Boolean = callRecorder.isStreaming()

    // ==================== STATE & CLEANUP ====================

    fun getConnectionState(): WebRtcConnectionState = currentConnectionState

    fun getLocalDescription(): String? = peerConnection?.localDescription?.sdp

    fun closePeerConnection() {
        log.d(TAG) { "Closing PeerConnection" }

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

        log.d(TAG) { "✅ PeerConnection closed" }
    }

    fun dispose() {
        log.d(TAG) { "Disposing PeerConnectionController" }

        remoteAudioCapture?.stopCapture()
        remoteAudioCapture = null

        // Limpiar recursos de inyección de audio
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
