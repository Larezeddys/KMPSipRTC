package com.eddyslarez.kmpsiprtc.services.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.eddyslarez.kmpsiprtc.data.models.*
import com.eddyslarez.kmpsiprtc.platform.log
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

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioSource? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private var isMuted = false
    private var currentConnectionState = WebRtcConnectionState.DISCONNECTED

    private val eglBase = EglBase.create()

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

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
            // 1️⃣ Initialize PeerConnectionFactory
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
            )

            // 2️⃣ Create AudioDeviceModule
            val audioModule = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setUseStereoInput(true)
                .setUseStereoOutput(true)
                .createAudioDeviceModule()

            audioDeviceModule = audioModule

            // 3️⃣ Create PeerConnectionFactory
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

            // 4️⃣ Crear PeerConnection inmediatamente
            createNewPeerConnection()
            log.d(TAG) { "✅ PeerConnection created during initialization" }

        } catch (e: Exception) {
            log.e(TAG) { "Error initializing: ${e.message}" }
            throw e
        }
    }

    // ==================== PEER CONNECTION ====================

    /**
     * ✅ MÉTODO PÚBLICO para recrear peer connection
     */
    fun createNewPeerConnection() {
        log.d(TAG) { "Creating new PeerConnection" }

        // ✅ Cerrar conexión anterior sin disponer los recursos
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

        // ✅ Preparar audio con el nuevo peer connection
        prepareLocalAudio()

        log.d(TAG) { "✅ New PeerConnection created and audio prepared" }
    }

    /**
     * ✅ NUEVO MÉTODO: Verificar si existe peer connection
     */
    fun hasPeerConnection(): Boolean {
        return peerConnection != null
    }

    private fun prepareLocalAudio() {
        log.d(TAG) { "Preparing local audio" }

        // ✅ NO disponer los tracks anteriores, solo deshabilitarlos
        localAudioTrack?.let {
            try {
                it.setEnabled(false)
            } catch (e: Exception) {
                log.w(TAG) { "Error disabling track: ${e.message}" }
            }
        }

        // ✅ Reusar la fuente de audio si existe
        if (localAudioSource == null) {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }
            localAudioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        }

        // ✅ Crear nuevo track solo si no existe o fue disposed
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

    // ==================== SDP OPERATIONS ====================

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
                            cont.resume(desc.description)
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
                            cont.resume(desc.description)
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
            log.d(TAG) { "Sending DTMF: $tones" }
            // DTMF implementation would go here
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error sending DTMF: ${e.message}" }
            false
        }
    }

    // ==================== STATE & CLEANUP ====================

    fun getConnectionState(): WebRtcConnectionState = currentConnectionState

    fun getLocalDescription(): String? = peerConnection?.localDescription?.description

    /**
     * ✅ NUEVO: Cerrar peer connection SIN disponer recursos
     */
    fun closePeerConnection() {
        log.d(TAG) { "Closing PeerConnection (keeping resources)" }

        try {
            localAudioTrack?.setEnabled(false)
        } catch (e: Exception) {
            log.w(TAG) { "Error disabling track: ${e.message}" }
        }

        peerConnection?.close()
        peerConnection = null
        currentConnectionState = WebRtcConnectionState.DISCONNECTED

        log.d(TAG) { "✅ PeerConnection closed (resources preserved)" }
    }

    /**
     * ✅ ANTIGUO: Mantener para compatibilidad (usa closePeerConnection)
     */
    fun close() {
        closePeerConnection()
    }

    /**
     * ✅ Disponer TODOS los recursos (solo al finalizar completamente)
     */
    /**
     * ✅ Disponer TODOS los recursos (solo al finalizar completamente)
     */
    fun dispose() {
        log.d(TAG) { "Disposing PeerConnectionController" }

        // 1️⃣ Primero deshabilitar el track
        try {
            localAudioTrack?.setEnabled(false)
        } catch (e: Exception) {
            log.w(TAG) { "Error disabling track: ${e.message}" }
        }

        // 2️⃣ Cerrar la PeerConnection (esto remueve los tracks)
        try {
            peerConnection?.close()
            peerConnection = null
        } catch (e: Exception) {
            log.w(TAG) { "Error closing peer connection: ${e.message}" }
        }

        // 3️⃣ Esperar un momento para que se complete el cierre
        Thread.sleep(100)

        // 4️⃣ Ahora sí, disponer el AudioTrack
        try {
            localAudioTrack?.dispose()
            localAudioTrack = null
        } catch (e: Exception) {
            log.w(TAG) { "Error disposing track: ${e.message}" }
        }

        // 5️⃣ Disponer el AudioSource
        try {
            localAudioSource?.dispose()
            localAudioSource = null
        } catch (e: Exception) {
            log.w(TAG) { "Error disposing source: ${e.message}" }
        }

        // 6️⃣ Liberar el AudioDeviceModule
        try {
            audioDeviceModule?.release()
            audioDeviceModule = null
        } catch (e: Exception) {
            log.w(TAG) { "Error releasing audio module: ${e.message}" }
        }

        // 7️⃣ Finalmente, disponer el PeerConnectionFactory
        try {
            peerConnectionFactory.dispose()
        } catch (e: Exception) {
            log.e(TAG) { "Error disposing factory: ${e.message}" }
        }

        // 8️⃣ Liberar EglBase al final
        try {
            eglBase.release()
        } catch (e: Exception) {
            log.w(TAG) { "Error releasing eglBase: ${e.message}" }
        }

        currentConnectionState = WebRtcConnectionState.DISCONNECTED

        log.d(TAG) { "✅ All resources disposed" }
    }

    fun diagnose(): String {
        return buildString {
            appendLine("PeerConnection: ${peerConnection != null}")
            appendLine("Local Audio Track: ${localAudioTrack != null}")
            try {
                appendLine("Track Enabled: ${localAudioTrack?.enabled()}")
            } catch (e: Exception) {
                appendLine("Track Enabled: ERROR - ${e.message}")
            }
            appendLine("Muted: $isMuted")
            appendLine("Connection State: $currentConnectionState")
            try {
                appendLine("Local Description: ${getLocalDescription()?.take(100)}...")
            } catch (e: Exception) {
                appendLine("Local Description: ERROR")
            }
        }
    }
}