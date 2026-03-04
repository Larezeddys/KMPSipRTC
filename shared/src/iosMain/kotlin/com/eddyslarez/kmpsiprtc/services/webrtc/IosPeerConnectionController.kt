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
import com.shepeliev.webrtckmp.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.AVFAudio.*
import kotlin.concurrent.Volatile

class IosPeerConnectionController(
    private val onIceCandidate: (String, String, Int) -> Unit,
    private val onConnectionStateChange: (WebRtcConnectionState) -> Unit,
    private val onRemoteAudioTrack: () -> Unit
) {
    private val TAG = "IosPeerConnectionController"
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // WebRTC components
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioStreamTrack? = null
    private var remoteAudioTrack: AudioStreamTrack? = null
    private var dtmfSender: DtmfSender? = null

    // Recording
    private val callRecorder: CallRecorder by lazy { createCallRecorder() }
    private var remoteAudioCapture: AudioTrackCapture? = null

    // Audio state
    private var isMuted = false
    @Volatile
    private var remoteAudioEnabled = true
    private var audioSessionConfigured = false
    private var microphonePermissionGranted = false
    private var audioTrackCreationRetries = 0
    private val maxAudioTrackRetries = 3

    @Volatile
    private var currentConnectionState = WebRtcConnectionState.DISCONNECTED

    private val iceServers = listOf(
        IceServer(
            urls = listOf(
                "stun:stun.l.google.com:19302",
                "stun:stun1.l.google.com:19302"
            )
        )
    )

    // ==================== INITIALIZATION ====================

    @OptIn(ExperimentalForeignApi::class)
    fun initialize() {
        log.d(TAG) { "Initializing PeerConnectionController..." }

        requestMicrophonePermission { granted ->
            microphonePermissionGranted = granted
            if (granted) {
                CoroutineScope(Dispatchers.Main).launch {
                    configureAudioSession()
                    initializePeerConnection()
                    log.d(TAG) { "✅ PeerConnectionController initialized" }
                }
            } else {
                log.e(TAG) { "Microphone permission denied" }
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun requestMicrophonePermission(callback: (Boolean) -> Unit) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted ->
            callback(granted)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun configureAudioSession(): Boolean {
        return try {
            val audioSession = AVAudioSession.sharedInstance()

            if (audioSession.category != AVAudioSessionCategoryPlayAndRecord) {
                val success1 = audioSession.setCategory(
                    AVAudioSessionCategoryPlayAndRecord,
                    AVAudioSessionCategoryOptionAllowBluetooth or
                            AVAudioSessionCategoryOptionDefaultToSpeaker,
                    null
                )
                if (!success1) return false
            }

            val success2 = audioSession.setMode(AVAudioSessionModeVoiceChat, null)
            if (!success2) return false

            val success3 = audioSession.setActive(true, 0u, null)
            if (!success3) return false

            audioSessionConfigured = true
            log.d(TAG) { "✅ Audio session configured" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error configuring audio session: ${e.message}" }
            false
        }
    }

    // ==================== PEER CONNECTION ====================

    fun hasPeerConnection(): Boolean = peerConnection != null

    suspend fun createNewPeerConnection() {
        log.d(TAG) { "Creating new PeerConnection..." }
        cleanupPeerConnection()

        try {
            val rtcConfig = RtcConfiguration(iceServers = iceServers)

            peerConnection = PeerConnection(rtcConfig).apply {
                setupPeerConnectionObservers()
            }

            log.d(TAG) { "✅ PeerConnection created" }
            addLocalAudioTrack()
        } catch (e: Exception) {
            log.e(TAG) { "Error creating PeerConnection: ${e.message}" }
            peerConnection = null
        }
    }

    private suspend fun initializePeerConnection() {
        createNewPeerConnection()
    }

    private fun PeerConnection.setupPeerConnectionObservers() {
        onIceCandidate.onEach { candidate ->
            log.d(TAG) { "New ICE Candidate" }
            onIceCandidate(
                candidate.candidate,
                candidate.sdpMid,
                candidate.sdpMLineIndex
            )
        }.launchIn(coroutineScope)

        onConnectionStateChange.onEach { state ->
            log.d(TAG) { "Connection state changed: $state" }
            currentConnectionState = mapConnectionState(state)
            onConnectionStateChange(currentConnectionState)
        }.launchIn(coroutineScope)

        onTrack.onEach { event ->
            log.d(TAG) { "Remote track received" }
            val track = event.receiver.track
            if (track is AudioStreamTrack) {
                remoteAudioTrack = track
                remoteAudioTrack?.enabled = remoteAudioEnabled

                // Configurar captura de audio remoto
                setupRemoteAudioCapture(track)

                onRemoteAudioTrack()
            }
        }.launchIn(coroutineScope)
    }

    private suspend fun addLocalAudioTrack() {
        if (!microphonePermissionGranted) {
            log.e(TAG) { "Cannot add audio track - no microphone permission" }
            return
        }

        try {
            val peerConn = peerConnection ?: return

            if (localAudioTrack != null) return

            log.d(TAG) { "Creating audio track" }

            val mediaStream = MediaDevices.getUserMedia(audio = true, video = false)
            val audioTrack = mediaStream.audioTracks.firstOrNull()

            if (audioTrack != null) {
                localAudioTrack = audioTrack
                localAudioTrack?.enabled = true

                val sender = peerConn.addTrack(audioTrack, mediaStream)
                dtmfSender = sender.dtmf

                audioTrackCreationRetries = 0
                log.d(TAG) { "✅ Audio track added successfully" }
            } else {
                handleAudioTrackCreationFailure()
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error adding audio track: ${e.message}" }
            handleAudioTrackCreationFailure()
        }
    }

    private fun handleAudioTrackCreationFailure() {
        audioTrackCreationRetries++
        if (audioTrackCreationRetries < maxAudioTrackRetries) {
            CoroutineScope(Dispatchers.IO).launch {
                delay(1000)
                addLocalAudioTrack()
            }
        } else {
            log.e(TAG) { "Failed to create audio track after $maxAudioTrackRetries attempts" }
        }
    }

    // ==================== SDP OPERATIONS ====================

    suspend fun createOffer(): String {
        val peerConn = peerConnection ?: run {
            initializePeerConnection()
            delay(500)
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        if (!audioSessionConfigured) {
            configureAudioSession()
        }

        if (localAudioTrack == null) {
            addLocalAudioTrack()
            var attempts = 0
            while (localAudioTrack == null && attempts < 10) {
                delay(200)
                attempts++
            }
            if (localAudioTrack == null) {
                throw IllegalStateException("Cannot create offer without local audio track")
            }
        }

        localAudioTrack?.enabled = true

        val options = OfferAnswerOptions(
            voiceActivityDetection = true,
            iceRestart = false,
            offerToReceiveAudio = true,
            offerToReceiveVideo = false
        )

        val sessionDescription = peerConn.createOffer(options)

        val modifiedSdp = ensureSendRecvInSdp(sessionDescription.sdp)
        val correctedDescription = SessionDescription(
            type = SessionDescriptionType.Offer,
            sdp = modifiedSdp
        )

        peerConn.setLocalDescription(correctedDescription)

        log.d(TAG) { "✅ Created offer SDP" }
        return modifiedSdp
    }

    suspend fun createAnswer(offerSdp: String): String {
        val peerConn = peerConnection ?: run {
            initializePeerConnection()
            delay(500)
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        val remoteOffer = SessionDescription(
            type = SessionDescriptionType.Offer,
            sdp = offerSdp
        )
        peerConn.setRemoteDescription(remoteOffer)

        val options = OfferAnswerOptions(
            voiceActivityDetection = true,
            offerToReceiveAudio = true,
            offerToReceiveVideo = false
        )

        val sessionDescription = peerConn.createAnswer(options)
        peerConn.setLocalDescription(sessionDescription)

        log.d(TAG) { "✅ Created answer SDP" }
        return sessionDescription.sdp
    }

    suspend fun setRemoteDescription(sdp: String, type: SdpType) {
        val peerConn = peerConnection
            ?: throw IllegalStateException("PeerConnection not initialized")

        val sdpType = when (type) {
            SdpType.OFFER -> SessionDescriptionType.Offer
            SdpType.ANSWER -> SessionDescriptionType.Answer
        }

        val sessionDescription = SessionDescription(type = sdpType, sdp = sdp)
        peerConn.setRemoteDescription(sessionDescription)
    }

    suspend fun setLocalDescriptionDirect(sdp: String) {
        val peerConn = peerConnection
            ?: throw IllegalStateException("PeerConnection not initialized")

        val sessionDescription = SessionDescription(
            type = SessionDescriptionType.Offer,
            sdp = sdp
        )
        peerConn.setLocalDescription(sessionDescription)
    }

    suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        val peerConn = peerConnection ?: return

        val iceCandidate = IceCandidate(
            sdpMid = sdpMid ?: "",
            sdpMLineIndex = sdpMLineIndex ?: 0,
            candidate = candidate
        )

        peerConn.addIceCandidate(iceCandidate)
    }

    // ==================== AUDIO CONTROL ====================

    fun setAudioEnabled(enabled: Boolean) {
        localAudioTrack?.enabled = enabled
        log.d(TAG) { "Audio enabled: $enabled" }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        localAudioTrack?.enabled = !muted
        log.d(TAG) { "Muted: $muted" }
    }

    fun isMuted(): Boolean = isMuted

    fun setRemoteAudioEnabled(enabled: Boolean) {
        remoteAudioEnabled = enabled
        runCatching {
            remoteAudioTrack?.enabled = enabled
        }
    }

    fun isRemoteAudioEnabled(): Boolean = remoteAudioEnabled

    // ==================== INYECCIÓN DE AUDIO PARA TRADUCCIÓN ====================

    // Flag que indica si el audio local está habilitado
    @Volatile
    private var localAudioEnabled = true
    // Flag que indica si la inyección de audio local está activa
    @Volatile
    private var localAudioInjectionActive = false

    /**
     * Habilitar/deshabilitar el audio local (micrófono) que se envía al peer remoto.
     * Cuando se deshabilita, el track del mic se silencia en WebRTC.
     * El audio traducido se inyecta via injectLocalAudio().
     *
     * NOTA iOS: Actualmente la inyección en el pipeline de WebRTC se implementa
     * silenciando el track local. Para inyectar audio traducido al remoto,
     * se necesita acceso al RTCAudioSource nativo (fase 2).
     * El audio del micrófono sigue siendo capturado por el CallRecorder/AudioStreamListener
     * para enviar al servidor de traducción.
     */
    fun setLocalAudioEnabled(enabled: Boolean) {
        if (localAudioEnabled == enabled) return
        localAudioEnabled = enabled
        localAudioInjectionActive = !enabled

        log.d(TAG) { "setLocalAudioEnabled: $enabled (injection: $localAudioInjectionActive)" }

        if (!enabled) {
            // Silenciar el track local en WebRTC (el remoto no escucha nada del mic)
            // IMPORTANTE: No usar setMuted() porque eso es para control del usuario
            localAudioTrack?.enabled = false
            log.d(TAG) { "✅ Local audio injection activated - mic muted in WebRTC" }
        } else {
            // Restaurar el track local
            localAudioTrack?.enabled = !isMuted
            localAudioInjectionActive = false
            log.d(TAG) { "✅ Local audio injection deactivated - mic restored" }
        }
    }

    fun isLocalAudioEnabled(): Boolean = localAudioEnabled

    /**
     * Inyecta datos de audio PCM que se enviarán al peer remoto.
     *
     * NOTA iOS: En la implementación actual, el audio traducido NO se inyecta
     * directamente en el pipeline de WebRTC (requiere acceso al RTCAudioSource nativo).
     * El audio se almacena en buffer para una futura implementación con interop nativo.
     *
     * TODO: Implementar acceso al RTCAudioSource nativo via Kotlin/Native interop
     * para llamar a pushBuffer() y enviar el audio traducido al peer remoto.
     */
    fun injectLocalAudio(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        if (!localAudioInjectionActive) return
        // TODO: Implementar inyección real via RTCAudioSource nativo
        // Por ahora el mic está silenciado y el audio traducido se pierde.
        // La implementación completa requiere:
        // 1. Obtener RTCPeerConnectionFactory nativo
        // 2. Crear RTCAudioSource con audioSourceWithConstraints
        // 3. Usar RTCRtpSender.replaceTrack() para reemplazar el track
        // 4. Llamar audioSource.pushBuffer(CMSampleBuffer) con los datos PCM
        log.d(TAG) { "injectLocalAudio: ${pcmData.size} bytes @ ${sampleRate}Hz (iOS injection pending full implementation)" }
    }

    /**
     * Inyecta audio remoto traducido para reproducción local.
     * En iOS, la reproducción se maneja via TranslationAudioPlayer (AVAudioEngine).
     */
    fun injectRemoteAudio(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        // En iOS, la reproducción de audio traducido se maneja via TranslationAudioPlayer
    }

    // ==================== DTMF ====================

    fun sendDtmf(tones: String, duration: Int, gap: Int): Boolean {
        return try {
            val sender = dtmfSender ?: return false
            sender.insertDtmf(tones, duration, gap)
            log.d(TAG) { "✅ DTMF sent: $tones" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error sending DTMF: ${e.message}" }
            false
        }
    }

    // ==================== RECORDING ====================

    private fun setupRemoteAudioCapture(track: AudioStreamTrack) {
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
        log.d(TAG) { "Closing PeerConnection (keeping resources)" }

        try {
            localAudioTrack?.enabled = false
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

    private fun cleanupPeerConnection() {
        try {
            localAudioTrack?.enabled = false
        } catch (e: Exception) {
            log.w(TAG) { "Error disabling track: ${e.message}" }
        }

        peerConnection?.close()
        peerConnection = null
        localAudioTrack = null
        remoteAudioTrack = null
        dtmfSender = null
    }

    fun dispose() {
        log.d(TAG) { "Disposing PeerConnectionController" }

        remoteAudioCapture?.stopCapture()
        remoteAudioCapture = null

        try {
            localAudioTrack?.enabled = false
        } catch (e: Exception) {
            log.w(TAG) { "Error disabling track: ${e.message}" }
        }

        try {
            peerConnection?.close()
            peerConnection = null
        } catch (e: Exception) {
            log.w(TAG) { "Error closing peer connection: ${e.message}" }
        }

        localAudioTrack = null
        remoteAudioTrack = null
        dtmfSender = null

        callRecorder.dispose()
        coroutineScope.cancel()

        currentConnectionState = WebRtcConnectionState.DISCONNECTED

        log.d(TAG) { "✅ All resources disposed" }
    }

    fun diagnose(): String {
        return buildString {
            appendLine("=== iOS PeerConnection Diagnostics ===")
            appendLine("PeerConnection: ${peerConnection != null}")
            appendLine("Local Audio Track: ${localAudioTrack != null}")
            appendLine("Remote Audio Track: ${remoteAudioTrack != null}")
            appendLine("Track Enabled: ${localAudioTrack?.enabled}")
            appendLine("Muted: $isMuted")
            appendLine("Connection State: $currentConnectionState")
            appendLine("Recording: ${isRecording()}")
            appendLine("Microphone Permission: $microphonePermissionGranted")
            appendLine("Audio Session Configured: $audioSessionConfigured")
            appendLine("Local Description: ${getLocalDescription()?.take(100)}...")
        }
    }

    // ==================== HELPERS ====================

    private fun ensureSendRecvInSdp(sdp: String): String {
        val sdpLines = sdp.split("\r\n").toMutableList()
        var hasAudioMedia = false
        var audioLineIndex = -1

        for (i in sdpLines.indices) {
            if (sdpLines[i].startsWith("m=audio")) {
                hasAudioMedia = true
                audioLineIndex = i
                break
            }
        }

        if (hasAudioMedia) {
            var foundDirection = false
            for (i in (audioLineIndex + 1) until sdpLines.size) {
                val line = sdpLines[i]
                if (line.startsWith("m=")) break

                when {
                    line.startsWith("a=recvonly") || line.startsWith("a=sendonly") -> {
                        sdpLines[i] = "a=sendrecv"
                        foundDirection = true
                        break
                    }
                    line.startsWith("a=sendrecv") -> {
                        foundDirection = true
                        break
                    }
                }
            }

            if (!foundDirection) {
                sdpLines.add(audioLineIndex + 1, "a=sendrecv")
            }
        }

        return sdpLines.joinToString("\r\n")
    }

    private fun mapConnectionState(state: PeerConnectionState): WebRtcConnectionState {
        return when (state) {
            PeerConnectionState.New -> WebRtcConnectionState.NEW
            PeerConnectionState.Connecting -> WebRtcConnectionState.CONNECTING
            PeerConnectionState.Connected -> WebRtcConnectionState.CONNECTED
            PeerConnectionState.Disconnected -> WebRtcConnectionState.DISCONNECTED
            PeerConnectionState.Failed -> WebRtcConnectionState.FAILED
            PeerConnectionState.Closed -> WebRtcConnectionState.CLOSED
        }
    }
}
