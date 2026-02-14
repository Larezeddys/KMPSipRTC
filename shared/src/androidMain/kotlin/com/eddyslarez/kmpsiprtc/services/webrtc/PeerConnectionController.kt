package com.eddyslarez.kmpsiprtc.services.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.eddyslarez.kmpsiprtc.data.models.*
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.audio.AudioCaptureCallback
import com.eddyslarez.kmpsiprtc.services.audio.AudioTrackCapture
import com.eddyslarez.kmpsiprtc.services.audio.createRemoteAudioCapture
import com.eddyslarez.kmpsiprtc.services.audio.AudioStreamListener
import com.eddyslarez.kmpsiprtc.services.recording.CallRecorder
import com.eddyslarez.kmpsiprtc.services.recording.createCallRecorder
import kotlinx.coroutines.*
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PeerConnectionController(
    private val context: Context,
    private val onIceCandidate: (String, String, Int) -> Unit,
    private val onConnectionStateChange: (WebRtcConnectionState) -> Unit,
    private val onRemoteAudioTrack: () -> Unit
) {
    private val TAG = "PeerConnectionController"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callRecorder: CallRecorder by lazy {
        createCallRecorder()
    }
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioSource? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private var isMuted = false
    private var currentConnectionState = WebRtcConnectionState.DISCONNECTED
    private var remoteAudioCapture: AudioTrackCapture? = null

    private val eglBase = EglBase.create()
//    private var localAudioRecorder: AudioTrackRecorder? = null
//    private var remoteAudioRecorder: AudioTrackRecorder? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )
    suspend fun setMediaDirection(direction: RtpTransceiver.RtpTransceiverDirection): Boolean {
        return try {
            peerConnection?.transceivers?.forEach { transceiver ->
                if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) {
                    transceiver.direction = direction
                }
            }

            // Crear nuevo offer con la dirección modificada
            val offer = createOffer()
            log.d(TAG) { "✅ Media direction changed to $direction" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error changing media direction: ${e.message}" }
            false
        }
    }
    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            log.d(TAG) { "ICE candidate: ${candidate.sdpMid}:${candidate.sdpMLineIndex}" }
            onIceCandidate(candidate.sdp, candidate.sdpMid ?: "", candidate.sdpMLineIndex)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            log.d(TAG) { "Connection state: $newState" }

            val state = when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    mainHandler.post {
                        try {
                            setAudioEnabled(true)
                        } catch (e: Exception) {
                            log.w(TAG) { "Could not enable audio: ${e.message}" }
                        }
                    }
                    WebRtcConnectionState.CONNECTED
                }

                PeerConnection.PeerConnectionState.CONNECTING -> WebRtcConnectionState.CONNECTING
                PeerConnection.PeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
                PeerConnection.PeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
                PeerConnection.PeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
                else -> WebRtcConnectionState.NEW
            }

            currentConnectionState = state
            onConnectionStateChange(state)
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            log.d(TAG) { "Remote track added" }
            val track = receiver?.track()
            if (track is AudioTrack) {
                track.setEnabled(true)
                remoteAudioTrack = track

                // ✅ NUEVO: Configurar captura de audio remoto
                setupRemoteAudioCapture(track)

                onRemoteAudioTrack()
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            log.d(TAG) { "ICE connection: $state" }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dc: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onTrack(transceiver: RtpTransceiver?) {}
    }

    // ==================== INITIALIZATION ====================

    fun initialize() {
        log.d(TAG) { "Initializing PeerConnectionController" }

        try {

            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
            )

            val audioModule = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setUseStereoInput(true)
                .setUseStereoOutput(true)
                .createAudioDeviceModule()

            audioDeviceModule = audioModule

            val options = PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            }

            val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(audioModule)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            log.d(TAG) { "✅ PeerConnectionFactory created" }

            createNewPeerConnection()
            log.d(TAG) { "✅ PeerConnection created during initialization" }

        } catch (e: Exception) {
            log.e(TAG) { "Error initializing: ${e.message}" }
            throw e
        }
    }

    // ==================== PEER CONNECTION ====================

    fun createNewPeerConnection() {
        log.d(TAG) { "Creating new PeerConnection" }

        peerConnection?.close()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.BALANCED
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            peerConnectionObserver
        ) ?: throw IllegalStateException("Failed to create PeerConnection")

        prepareLocalAudio()

        log.d(TAG) { "✅ New PeerConnection created and audio prepared" }
    }

    fun hasPeerConnection(): Boolean {
        return peerConnection != null
    }

    private fun prepareLocalAudio() {
        log.d(TAG) { "Preparing local audio" }

        localAudioTrack?.let {
            try {
                it.setEnabled(false)
                // Detener captura anterior
                remoteAudioCapture?.stopCapture()
            } catch (e: Exception) {
                log.w(TAG) { "Error disabling track: ${e.message}" }
            }
        }

        if (localAudioSource == null) {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }
            localAudioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        }

        if (localAudioTrack == null) {
            localAudioTrack = peerConnectionFactory.createAudioTrack(
                "AUDIO_TRACK_${System.currentTimeMillis()}",
                localAudioSource
            )
        }

        localAudioTrack?.setEnabled(true)
        peerConnection?.addTrack(localAudioTrack, listOf("audio_stream"))

        log.d(TAG) { "✅ Local audio prepared and added to PeerConnection" }
    }

    // ==================== GRABACIÓN DE AUDIO ====================

//    /**
//     * Configurar captura de audio local (micrófono)
//     */
//    private fun setupLocalAudioCapture(track: AudioTrack) {
//        try {
//            localAudioRecorder?.stopCapture()
//
//            localAudioRecorder = AudioTrackRecorder(track) { audioData, bitsPerSample, sampleRate, channels, frames, timestampMs ->
//                callRecorder?.captureLocalAudio(audioData)
//                log.d(TAG) { "🎤 Local audio frame: ${audioData.size} bytes, $sampleRate Hz, $channels ch, $bitsPerSample bits, ts=$timestampMs" }
//            }
//
//            if (callRecorder?.isRecording() == true) {
//                localAudioRecorder?.startCapture()
//                log.d(TAG) { "✅ Local audio capture started" }
//            }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error setting up local audio capture: ${e.message}" }
//        }
//    }

    /**
     * Configurar captura de audio remoto (del peer)
     */
    private fun setupRemoteAudioCapture(track: AudioTrack) {
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
                        // Enviar al recorder si está grabando o haciendo streaming
                        if (callRecorder.isRecording() || callRecorder.isStreaming()) {
                            callRecorder.captureRemoteAudio(data, sampleRate, channels, bitsPerSample)
                        }
                    }
                }
            )

            // Iniciar captura si ya estamos grabando o haciendo streaming
            if (callRecorder.isRecording() || callRecorder.isStreaming()) {
                remoteAudioCapture?.startCapture()
                log.d(TAG) { "✅ Remote audio capture started" }
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error setting up remote audio capture: ${e.message}" }
        }
    }


    /**
     * Iniciar grabación de la llamada
     */
    fun startRecording(callId: String) {
        log.d(TAG) { "🎙️ Starting call recording" }

        callRecorder.startRecording(callId)

        // Iniciar captura de audio remoto
        remoteAudioCapture?.startCapture()

        log.d(TAG) { "✅ Recording started for call: $callId" }
    }

    /**
     * Detener grabación y obtener archivos
     */
    suspend fun stopRecording(): RecordingResult? {
        log.d(TAG) { "🛑 Stopping call recording" }

        // Detener captura
        remoteAudioCapture?.stopCapture()

        // Guardar archivos
        val result = callRecorder.stopRecording()

        log.d(TAG) { "✅ Recording stopped" }
        return result
    }

    /**
     * Verificar si se está grabando
     */
    fun isRecording(): Boolean = callRecorder?.isRecording() ?: false

    /**
     * Obtener el recorder para acceso directo
     */
    fun getRecorder(): CallRecorder? = callRecorder

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

    // ==================== SDP OPERATIONS (sin cambios) ====================

    suspend fun createOffer(): String = withContext(Dispatchers.IO) {
        log.d(TAG) { "Creating offer" }

        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }

        suspendCancellableCoroutine { cont ->
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            log.d(TAG) { "✅ Offer created" }

                            // 🟢 Aseguramos que el DTMF esté presente
                            val sdpWithDtmf = ensureDtmfCodec(desc.description)

                            cont.resume(sdpWithDtmf)
                        }

                        override fun onSetFailure(error: String?) {
                            cont.resumeWithException(Exception("SetLocal failed: $error"))
                        }

                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, desc)
                }

                override fun onCreateFailure(error: String?) {
                    cont.resumeWithException(Exception("CreateOffer failed: $error"))
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, constraints)
        }
    }

    suspend fun createAnswer(offerSdp: String): String = withContext(Dispatchers.IO) {
        log.d(TAG) { "Creating answer" }

        if (offerSdp.isBlank()) {
            throw IllegalArgumentException("Offer SDP cannot be empty")
        }

        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")

        setRemoteDescription(offerSdp, SdpType.OFFER)

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }

        suspendCancellableCoroutine { cont ->
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            log.d(TAG) { "✅ Answer created" }

                            // 🟢 Aseguramos que el DTMF esté presente
                            val sdpWithDtmf = ensureDtmfCodec(desc.description)

                            cont.resume(sdpWithDtmf)
                        }

                        override fun onSetFailure(error: String?) {
                            cont.resumeWithException(Exception("SetLocal failed: $error"))
                        }

                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, desc)
                }

                override fun onCreateFailure(error: String?) {
                    cont.resumeWithException(Exception("CreateAnswer failed: $error"))
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, constraints)
        }
    }
    suspend fun setLocalDescriptionDirect(sdp: String) = suspendCancellableCoroutine<Unit> { cont ->
        log.d(TAG) { "Setting local description directly" }

        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)

        peerConnection?.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() {
                log.d(TAG) { "✅ Local description set directly" }
                cont.resume(Unit)
            }

            override fun onSetFailure(error: String?) {
                cont.resumeWithException(Exception("SetLocalDirect failed: $error"))
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sessionDescription)
    }

    suspend fun setRemoteDescription(sdp: String, type: SdpType) =
        suspendCancellableCoroutine<Unit> { cont ->
            log.d(TAG) { "Setting remote description: $type" }

            val sdpType = when (type) {
                SdpType.OFFER -> SessionDescription.Type.OFFER
                SdpType.ANSWER -> SessionDescription.Type.ANSWER
            }

            val sessionDescription = SessionDescription(sdpType, sdp)

            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    log.d(TAG) { "✅ Remote description set" }
                    cont.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    cont.resumeWithException(Exception("SetRemote failed: $error"))
                }

                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, sessionDescription)
        }

    suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        withContext(Dispatchers.IO) {
            log.d(TAG) { "Adding ICE candidate" }
            val iceCandidate = IceCandidate(sdpMid ?: "", sdpMLineIndex ?: 0, candidate)
            peerConnection?.addIceCandidate(iceCandidate)
        }
    }

    // ==================== AUDIO CONTROL ====================

    fun setAudioEnabled(enabled: Boolean) {
        try {
            localAudioTrack?.setEnabled(enabled)
        } catch (e: Exception) {
            log.e(TAG) { "Error setting audio enabled: ${e.message}" }
            if (e.message?.contains("disposed") == true) {
                log.w(TAG) { "Track was disposed, will recreate on next connection" }
            }
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        try {
            localAudioTrack?.setEnabled(!muted)
        } catch (e: Exception) {
            log.e(TAG) { "Error setting muted: ${e.message}" }
        }
    }

    fun isMuted(): Boolean = isMuted

    // ==================== DTMF ====================

    fun sendDtmf(tones: String, duration: Int, gap: Int): Boolean {
        if (peerConnection == null) return false

        return try {
            val audioSender = peerConnection?.senders?.find { sender ->
                sender.track()?.kind() == "audio"
            }

            if (audioSender == null) {
                log.e(TAG) { "No audio sender found for DTMF" }
                return false
            }

            val dtmfSender = audioSender.dtmf()

            if (dtmfSender == null) {
                log.e(TAG) { "DTMF sender not available" }
                return false
            }
            if (!dtmfSender.canInsertDtmf()) {
                log.e(TAG) { "Cannot insert DTMF at this time" }
                return false
            }
            val success = dtmfSender.insertDtmf(tones, duration, gap)
            if (success) {
                log.d(TAG) { "✅ DTMF sent: $tones (duration: ${duration}ms, gap: ${gap}ms)" }
            } else {
                log.e(TAG) { "Failed to insert DTMF" }
            }
            log.d(TAG) { "Sending DTMF: $tones" }
            return success
        } catch (e: Exception) {
            log.e(TAG) { "Error sending DTMF: ${e.message}" }
            false
        }
    }

    private fun ensureDtmfCodec(sdp: String): String {
        // Verificar que el codec telephone-event/8000 esté presente
        if (!sdp.contains("telephone-event")) {
            log.w(TAG) { "DTMF codec not found in SDP, adding it" }

            // Agregar líneas para DTMF si no existen
            val lines = sdp.lines().toMutableList()
            val audioIndex = lines.indexOfFirst { it.startsWith("m=audio") }

            if (audioIndex >= 0) {
                // Buscar el último payload type usado
                val audioLine = lines[audioIndex]
                val payloadTypes = audioLine.split(" ").drop(3).map { it.toIntOrNull() }
                val nextPayloadType = (payloadTypes.filterNotNull().maxOrNull() ?: 96) + 1

                // Agregar el payload type a la línea m=audio
                lines[audioIndex] = "$audioLine $nextPayloadType"

                // Agregar las líneas rtpmap y fmtp
                lines.add(audioIndex + 1, "a=rtpmap:$nextPayloadType telephone-event/8000")
                lines.add(audioIndex + 2, "a=fmtp:$nextPayloadType 0-15")

                return lines.joinToString("\r\n")
            }
        }
        return sdp
    }
    // ==================== STATE & CLEANUP ====================

    fun getConnectionState(): WebRtcConnectionState = currentConnectionState

    fun getLocalDescription(): String? = peerConnection?.localDescription?.description

    fun closePeerConnection() {
        log.d(TAG) { "Closing PeerConnection (keeping resources)" }

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

        log.d(TAG) { "✅ PeerConnection closed (resources preserved)" }
    }

    fun close() {
        closePeerConnection()
    }

    fun dispose() {
        log.d(TAG) { "Disposing PeerConnectionController" }

        // 1️⃣ Detener grabación si está activa
        remoteAudioCapture?.stopCapture()

        // 2️⃣ Deshabilitar tracks
        try {
            localAudioTrack?.setEnabled(false)
        } catch (e: Exception) {
            log.w(TAG) { "Error disabling track: ${e.message}" }
        }

        // 3️⃣ Cerrar PeerConnection
        try {
            peerConnection?.close()
            peerConnection = null
        } catch (e: Exception) {
            log.w(TAG) { "Error closing peer connection: ${e.message}" }
        }

        // 4️⃣ Esperar un momento
        Thread.sleep(100)

        // 5️⃣ Disponer AudioTrack
        try {
            localAudioTrack?.dispose()
            localAudioTrack = null
        } catch (e: Exception) {
            log.w(TAG) { "Error disposing track: ${e.message}" }
        }

        // 6️⃣ Disponer AudioSource
        try {
            localAudioSource?.dispose()
            localAudioSource = null
        } catch (e: Exception) {
            log.w(TAG) { "Error disposing source: ${e.message}" }
        }

        // 7️⃣ Liberar AudioDeviceModule
        try {
            audioDeviceModule?.release()
            audioDeviceModule = null
        } catch (e: Exception) {
            log.w(TAG) { "Error releasing audio module: ${e.message}" }
        }

        // 8️⃣ Disponer PeerConnectionFactory
        try {
            peerConnectionFactory.dispose()
        } catch (e: Exception) {
            log.e(TAG) { "Error disposing factory: ${e.message}" }
        }

        // 9️⃣ Liberar EglBase
        try {
            eglBase.release()
        } catch (e: Exception) {
            log.w(TAG) { "Error releasing eglBase: ${e.message}" }
        }

        // 🔟 Disponer CallRecorder
        try {
            callRecorder?.dispose()
        } catch (e: Exception) {
            log.w(TAG) { "Error disposing recorder: ${e.message}" }
        }

        currentConnectionState = WebRtcConnectionState.DISCONNECTED

        log.d(TAG) { "✅ All resources disposed" }
    }

    fun diagnose(): String {
        return buildString {
            appendLine("PeerConnection: ${peerConnection != null}")
            appendLine("Local Audio Track: ${localAudioTrack != null}")
            appendLine("Remote Audio Track: ${remoteAudioTrack != null}")
            try {
                appendLine("Track Enabled: ${localAudioTrack?.enabled()}")
            } catch (e: Exception) {
                appendLine("Track Enabled: ERROR - ${e.message}")
            }
            appendLine("Muted: $isMuted")
            appendLine("Connection State: $currentConnectionState")
            appendLine("Recording: ${isRecording()}")
            try {
                appendLine("Local Description: ${getLocalDescription()?.take(100)}...")
            } catch (e: Exception) {
                appendLine("Local Description: ERROR")
            }
        }
    }
}
//class PeerConnectionController(
//    private val context: Context,
//    private val onIceCandidate: (String, String, Int) -> Unit,
//    private val onConnectionStateChange: (WebRtcConnectionState) -> Unit,
//    private val onRemoteAudioTrack: () -> Unit
//) {
//    private val TAG = "PeerConnectionController"
//    private val mainHandler = Handler(Looper.getMainLooper())
//
//    private lateinit var peerConnectionFactory: PeerConnectionFactory
//    private var peerConnection: PeerConnection? = null
//    private var localAudioTrack: AudioTrack? = null
//    private var localAudioSource: AudioSource? = null
//    private var audioDeviceModule: AudioDeviceModule? = null
//    private var isMuted = false
//    private var currentConnectionState = WebRtcConnectionState.DISCONNECTED
//
//    private val eglBase = EglBase.create()
//
//    private val iceServers = listOf(
//        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
//        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
//    )
//
//    private val peerConnectionObserver = object : PeerConnection.Observer {
//        override fun onIceCandidate(candidate: IceCandidate) {
//            log.d(TAG) { "ICE candidate: ${candidate.sdpMid}:${candidate.sdpMLineIndex}" }
//            onIceCandidate(candidate.sdp, candidate.sdpMid ?: "", candidate.sdpMLineIndex)
//        }
//
//        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
//            log.d(TAG) { "Connection state: $newState" }
//
//            val state = when (newState) {
//                PeerConnection.PeerConnectionState.CONNECTED -> {
//                    mainHandler.post {
//                        try {
//                            setAudioEnabled(true)
//                        } catch (e: Exception) {
//                            log.w(TAG) { "Could not enable audio: ${e.message}" }
//                        }
//                    }
//                    WebRtcConnectionState.CONNECTED
//                }
//                PeerConnection.PeerConnectionState.CONNECTING -> WebRtcConnectionState.CONNECTING
//                PeerConnection.PeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
//                PeerConnection.PeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
//                PeerConnection.PeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
//                else -> WebRtcConnectionState.NEW
//            }
//
//            currentConnectionState = state
//            onConnectionStateChange(state)
//        }
//
//        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
//            log.d(TAG) { "Remote track added" }
//            val track = receiver?.track()
//            if (track is AudioTrack) {
//                track.setEnabled(true)
//                onRemoteAudioTrack()
//            }
//        }
//
//        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
//            log.d(TAG) { "ICE connection: $state" }
//        }
//
//        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
//        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
//        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
//        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
//        override fun onAddStream(stream: MediaStream?) {}
//        override fun onRemoveStream(stream: MediaStream?) {}
//        override fun onDataChannel(dc: DataChannel?) {}
//        override fun onRenegotiationNeeded() {}
//        override fun onTrack(transceiver: RtpTransceiver?) {}
//    }
//
//    // ==================== INITIALIZATION ====================
//
//    fun initialize() {
//        log.d(TAG) { "Initializing PeerConnectionController" }
//
//        try {
//            // 1️⃣ Initialize PeerConnectionFactory
//            PeerConnectionFactory.initialize(
//                PeerConnectionFactory.InitializationOptions
//                    .builder(context)
//                    .setEnableInternalTracer(true)
//                    .createInitializationOptions()
//            )
//
//            // 2️⃣ Create AudioDeviceModule
//            val audioModule = JavaAudioDeviceModule.builder(context)
//                .setUseHardwareAcousticEchoCanceler(true)
//                .setUseHardwareNoiseSuppressor(true)
//                .setUseStereoInput(true)
//                .setUseStereoOutput(true)
//                .createAudioDeviceModule()
//
//            audioDeviceModule = audioModule
//
//            // 3️⃣ Create PeerConnectionFactory
//            val options = PeerConnectionFactory.Options().apply {
//                disableEncryption = false
//                disableNetworkMonitor = false
//            }
//
//            val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
//            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
//
//            peerConnectionFactory = PeerConnectionFactory.builder()
//                .setOptions(options)
//                .setAudioDeviceModule(audioModule)
//                .setVideoEncoderFactory(encoderFactory)
//                .setVideoDecoderFactory(decoderFactory)
//                .createPeerConnectionFactory()
//
//            log.d(TAG) { "✅ PeerConnectionFactory created" }
//
//            // 4️⃣ Crear PeerConnection inmediatamente
//            createNewPeerConnection()
//            log.d(TAG) { "✅ PeerConnection created during initialization" }
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error initializing: ${e.message}" }
//            throw e
//        }
//    }
//
//    // ==================== PEER CONNECTION ====================
//
//    /**
//     * ✅ MÉTODO PÚBLICO para recrear peer connection
//     */
//    fun createNewPeerConnection() {
//        log.d(TAG) { "Creating new PeerConnection" }
//
//        // ✅ Cerrar conexión anterior sin disponer los recursos
//        peerConnection?.close()
//
//        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
//            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
//            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
//            iceTransportsType = PeerConnection.IceTransportsType.ALL
//            bundlePolicy = PeerConnection.BundlePolicy.BALANCED
//            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
//            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
//            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
//        }
//
//        peerConnection = peerConnectionFactory.createPeerConnection(
//            rtcConfig,
//            peerConnectionObserver
//        ) ?: throw IllegalStateException("Failed to create PeerConnection")
//
//        // ✅ Preparar audio con el nuevo peer connection
//        prepareLocalAudio()
//
//        log.d(TAG) { "✅ New PeerConnection created and audio prepared" }
//    }
//
//    /**
//     * ✅ NUEVO MÉTODO: Verificar si existe peer connection
//     */
//    fun hasPeerConnection(): Boolean {
//        return peerConnection != null
//    }
//
//    private fun prepareLocalAudio() {
//        log.d(TAG) { "Preparing local audio" }
//
//        // ✅ NO disponer los tracks anteriores, solo deshabilitarlos
//        localAudioTrack?.let {
//            try {
//                it.setEnabled(false)
//            } catch (e: Exception) {
//                log.w(TAG) { "Error disabling track: ${e.message}" }
//            }
//        }
//
//        // ✅ Reusar la fuente de audio si existe
//        if (localAudioSource == null) {
//            val audioConstraints = MediaConstraints().apply {
//                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
//                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
//                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
//                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
//            }
//            localAudioSource = peerConnectionFactory.createAudioSource(audioConstraints)
//        }
//
//        // ✅ Crear nuevo track solo si no existe o fue disposed
//        if (localAudioTrack == null) {
//            localAudioTrack = peerConnectionFactory.createAudioTrack(
//                "AUDIO_TRACK_${System.currentTimeMillis()}",
//                localAudioSource
//            )
//        }
//
//        localAudioTrack?.setEnabled(true)
//        peerConnection?.addTrack(localAudioTrack, listOf("audio_stream"))
//
//        log.d(TAG) { "✅ Local audio prepared and added to PeerConnection" }
//    }
//
//    // ==================== SDP OPERATIONS ====================
//
//    suspend fun createOffer(): String = withContext(Dispatchers.IO) {
//        log.d(TAG) { "Creating offer" }
//
//        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")
//
//        val constraints = MediaConstraints().apply {
//            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
//            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
//        }
//
//        suspendCancellableCoroutine { cont ->
//            pc.createOffer(object : SdpObserver {
//                override fun onCreateSuccess(desc: SessionDescription) {
//                    pc.setLocalDescription(object : SdpObserver {
//                        override fun onSetSuccess() {
//                            log.d(TAG) { "✅ Offer created" }
//                            cont.resume(desc.description)
//                        }
//                        override fun onSetFailure(error: String?) {
//                            cont.resumeWithException(Exception("SetLocal failed: $error"))
//                        }
//                        override fun onCreateSuccess(p0: SessionDescription?) {}
//                        override fun onCreateFailure(p0: String?) {}
//                    }, desc)
//                }
//                override fun onCreateFailure(error: String?) {
//                    cont.resumeWithException(Exception("CreateOffer failed: $error"))
//                }
//                override fun onSetSuccess() {}
//                override fun onSetFailure(p0: String?) {}
//            }, constraints)
//        }
//    }
//
//    suspend fun createAnswer(offerSdp: String): String = withContext(Dispatchers.IO) {
//        log.d(TAG) { "Creating answer" }
//
//        if (offerSdp.isBlank()) {
//            throw IllegalArgumentException("Offer SDP cannot be empty")
//        }
//
//        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")
//
//        setRemoteDescription(offerSdp, SdpType.OFFER)
//
//        val constraints = MediaConstraints().apply {
//            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
//            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
//        }
//
//        suspendCancellableCoroutine { cont ->
//            pc.createAnswer(object : SdpObserver {
//                override fun onCreateSuccess(desc: SessionDescription) {
//                    pc.setLocalDescription(object : SdpObserver {
//                        override fun onSetSuccess() {
//                            log.d(TAG) { "✅ Answer created" }
//                            cont.resume(desc.description)
//                        }
//                        override fun onSetFailure(error: String?) {
//                            cont.resumeWithException(Exception("SetLocal failed: $error"))
//                        }
//                        override fun onCreateSuccess(p0: SessionDescription?) {}
//                        override fun onCreateFailure(p0: String?) {}
//                    }, desc)
//                }
//                override fun onCreateFailure(error: String?) {
//                    cont.resumeWithException(Exception("CreateAnswer failed: $error"))
//                }
//                override fun onSetSuccess() {}
//                override fun onSetFailure(p0: String?) {}
//            }, constraints)
//        }
//    }
//
//    suspend fun setRemoteDescription(sdp: String, type: SdpType) =
//        suspendCancellableCoroutine<Unit> { cont ->
//            log.d(TAG) { "Setting remote description: $type" }
//
//            val sdpType = when (type) {
//                SdpType.OFFER -> SessionDescription.Type.OFFER
//                SdpType.ANSWER -> SessionDescription.Type.ANSWER
//            }
//
//            val sessionDescription = SessionDescription(sdpType, sdp)
//
//            peerConnection?.setRemoteDescription(object : SdpObserver {
//                override fun onSetSuccess() {
//                    log.d(TAG) { "✅ Remote description set" }
//                    cont.resume(Unit)
//                }
//                override fun onSetFailure(error: String?) {
//                    cont.resumeWithException(Exception("SetRemote failed: $error"))
//                }
//                override fun onCreateSuccess(p0: SessionDescription?) {}
//                override fun onCreateFailure(p0: String?) {}
//            }, sessionDescription)
//        }
//
//    suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
//        withContext(Dispatchers.IO) {
//            log.d(TAG) { "Adding ICE candidate" }
//            val iceCandidate = IceCandidate(sdpMid ?: "", sdpMLineIndex ?: 0, candidate)
//            peerConnection?.addIceCandidate(iceCandidate)
//        }
//    }
//
//    // ==================== AUDIO CONTROL ====================
//
//    fun setAudioEnabled(enabled: Boolean) {
//        try {
//            localAudioTrack?.setEnabled(enabled)
//        } catch (e: Exception) {
//            log.e(TAG) { "Error setting audio enabled: ${e.message}" }
//            if (e.message?.contains("disposed") == true) {
//                log.w(TAG) { "Track was disposed, will recreate on next connection" }
//            }
//        }
//    }
//
//    fun setMuted(muted: Boolean) {
//        isMuted = muted
//        try {
//            localAudioTrack?.setEnabled(!muted)
//        } catch (e: Exception) {
//            log.e(TAG) { "Error setting muted: ${e.message}" }
//        }
//    }
//
//    fun isMuted(): Boolean = isMuted
//
//    // ==================== DTMF ====================
//
//    fun sendDtmf(tones: String, duration: Int, gap: Int): Boolean {
//        if (peerConnection == null) return false
//
//        return try {
//            val audioSender = peerConnection?.senders?.find { sender ->
//                sender.track()?.kind() == "audio"
//            }
//            log.d(TAG) { "Sending DTMF: $tones" }
//            // DTMF implementation would go here
//            true
//        } catch (e: Exception) {
//            log.e(TAG) { "Error sending DTMF: ${e.message}" }
//            false
//        }
//    }
//
//    // ==================== STATE & CLEANUP ====================
//
//    fun getConnectionState(): WebRtcConnectionState = currentConnectionState
//
//    fun getLocalDescription(): String? = peerConnection?.localDescription?.description
//
//    /**
//     * ✅ NUEVO: Cerrar peer connection SIN disponer recursos
//     */
//    fun closePeerConnection() {
//        log.d(TAG) { "Closing PeerConnection (keeping resources)" }
//
//        try {
//            localAudioTrack?.setEnabled(false)
//        } catch (e: Exception) {
//            log.w(TAG) { "Error disabling track: ${e.message}" }
//        }
//
//        peerConnection?.close()
//        peerConnection = null
//        currentConnectionState = WebRtcConnectionState.DISCONNECTED
//
//        log.d(TAG) { "✅ PeerConnection closed (resources preserved)" }
//    }
//
//    /**
//     * ✅ ANTIGUO: Mantener para compatibilidad (usa closePeerConnection)
//     */
//    fun close() {
//        closePeerConnection()
//    }
//
//    /**
//     * ✅ Disponer TODOS los recursos (solo al finalizar completamente)
//     */
//    /**
//     * ✅ Disponer TODOS los recursos (solo al finalizar completamente)
//     */
//    fun dispose() {
//        log.d(TAG) { "Disposing PeerConnectionController" }
//
//        // 1️⃣ Primero deshabilitar el track
//        try {
//            localAudioTrack?.setEnabled(false)
//        } catch (e: Exception) {
//            log.w(TAG) { "Error disabling track: ${e.message}" }
//        }
//
//        // 2️⃣ Cerrar la PeerConnection (esto remueve los tracks)
//        try {
//            peerConnection?.close()
//            peerConnection = null
//        } catch (e: Exception) {
//            log.w(TAG) { "Error closing peer connection: ${e.message}" }
//        }
//
//        // 3️⃣ Esperar un momento para que se complete el cierre
//        Thread.sleep(100)
//
//        // 4️⃣ Ahora sí, disponer el AudioTrack
//        try {
//            localAudioTrack?.dispose()
//            localAudioTrack = null
//        } catch (e: Exception) {
//            log.w(TAG) { "Error disposing track: ${e.message}" }
//        }
//
//        // 5️⃣ Disponer el AudioSource
//        try {
//            localAudioSource?.dispose()
//            localAudioSource = null
//        } catch (e: Exception) {
//            log.w(TAG) { "Error disposing source: ${e.message}" }
//        }
//
//        // 6️⃣ Liberar el AudioDeviceModule
//        try {
//            audioDeviceModule?.release()
//            audioDeviceModule = null
//        } catch (e: Exception) {
//            log.w(TAG) { "Error releasing audio module: ${e.message}" }
//        }
//
//        // 7️⃣ Finalmente, disponer el PeerConnectionFactory
//        try {
//            peerConnectionFactory.dispose()
//        } catch (e: Exception) {
//            log.e(TAG) { "Error disposing factory: ${e.message}" }
//        }
//
//        // 8️⃣ Liberar EglBase al final
//        try {
//            eglBase.release()
//        } catch (e: Exception) {
//            log.w(TAG) { "Error releasing eglBase: ${e.message}" }
//        }
//
//        currentConnectionState = WebRtcConnectionState.DISCONNECTED
//
//        log.d(TAG) { "✅ All resources disposed" }
//    }
//
//    fun diagnose(): String {
//        return buildString {
//            appendLine("PeerConnection: ${peerConnection != null}")
//            appendLine("Local Audio Track: ${localAudioTrack != null}")
//            try {
//                appendLine("Track Enabled: ${localAudioTrack?.enabled()}")
//            } catch (e: Exception) {
//                appendLine("Track Enabled: ERROR - ${e.message}")
//            }
//            appendLine("Muted: $isMuted")
//            appendLine("Connection State: $currentConnectionState")
//            try {
//                appendLine("Local Description: ${getLocalDescription()?.take(100)}...")
//            } catch (e: Exception) {
//                appendLine("Local Description: ERROR")
//            }
//        }
//    }
//}