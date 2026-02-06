package com.eddyslarez.kmpsiprtc.services.webrtc

import com.eddyslarez.kmpsiprtc.data.models.RecordingResult
import com.eddyslarez.kmpsiprtc.data.models.SdpType
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.audio.AudioCaptureCallback
import com.eddyslarez.kmpsiprtc.services.audio.AudioTrackCapture
import com.eddyslarez.kmpsiprtc.services.audio.createRemoteAudioCapture
import com.eddyslarez.kmpsiprtc.services.recording.CallRecorder
import com.eddyslarez.kmpsiprtc.services.recording.createCallRecorder
import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.*
import dev.onvoid.webrtc.media.audio.*
import kotlinx.coroutines.*
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
    private var dtmfSender: RTCDtmfSender? = null

    // Recording
    private val callRecorder: CallRecorder by lazy { createCallRecorder() }
    private var remoteAudioCapture: AudioTrackCapture? = null

    // State
    private var isMuted = false
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

        peerConnection?.close()

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
            dtmfSender = sender.dtmfSender

            log.d(TAG) { "✅ Local audio track added" }
        } catch (e: Exception) {
            log.e(TAG) { "Error adding local audio track: ${e.message}" }
            e.printStackTrace()
        }
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
                        if (callRecorder.isRecording()) {
                            callRecorder.captureRemoteAudio(data)
                        }
                    }
                }
            )

            if (callRecorder.isRecording()) {
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
        currentConnectionState = WebRtcConnectionState.DISCONNECTED

        log.d(TAG) { "✅ PeerConnection closed" }
    }

    fun dispose() {
        log.d(TAG) { "Disposing PeerConnectionController" }

        remoteAudioCapture?.stopCapture()
        remoteAudioCapture = null

        try {
            localAudioTrack?.setEnabled(false)
        } catch (e: Exception) {
            log.w(TAG) { "Error disabling track: ${e.message}" }
        }

        try {
            peerConnection?.close()
            peerConnection = null
        } catch (e: Exception) {
            log.w(TAG) { "Error closing peer connection: ${e.message}" }
        }

        Thread.sleep(100)

        try {
            localAudioTrack?.dispose()
            localAudioTrack = null
        } catch (e: Exception) {
            log.w(TAG) { "Error disposing track: ${e.message}" }
        }

        try {
            audioDeviceModule?.dispose()
            audioDeviceModule = null
        } catch (e: Exception) {
            log.w(TAG) { "Error releasing audio module: ${e.message}" }
        }

        try {
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
        } catch (e: Exception) {
            log.e(TAG) { "Error disposing factory: ${e.message}" }
        }

        callRecorder.dispose()
        scope.cancel()

        currentConnectionState = WebRtcConnectionState.DISCONNECTED

        log.d(TAG) { "✅ All resources disposed" }
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
