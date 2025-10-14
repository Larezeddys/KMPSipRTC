package com.eddyslarez.kmpsiprtc.services.webrtc
//
//import android.app.Application
//import android.content.Context
//import com.eddyslarez.kmpsiprtc.data.models.AudioUnitTypes
//import com.eddyslarez.kmpsiprtc.platform.log
//import com.eddyslarez.kmpsiprtc.services.webrtc.WebRtcEventListener
//import kotlinx.coroutines.flow.MutableStateFlow
//import kotlinx.coroutines.flow.StateFlow
//
//import com.eddyslarez.kmpsiprtc.services.webrtc.WebRtcManager
//
//class AndroidWebRtcManager(
//    private val application: Application
//) : WebRtcManager {
//
//    private val logger = log
//
//    private var peerConnectionFactory: PeerConnectionFactory? = null
//    private var peerConnection: PeerConnection? = null
//    private var audioTrack: AudioTrack? = null
//    private var listener: WebRtcEventListener? = null
//
//    override var isInitialized: Boolean = false
//        private set
//
//    private val _availableAudioDevices = MutableStateFlow<Set<AudioDevice>>(emptySet())
//    override val availableAudioDevices: StateFlow<Set<AudioDevice>> = _availableAudioDevices.asStateFlow()
//
//    private val _currentAudioDevice = MutableStateFlow<AudioDevice?>(null)
//    override val currentAudioDevice: StateFlow<AudioDevice?> = _currentAudioDevice.asStateFlow()
//
//    override fun initialize() {
//        if (isInitialized) return
//
//        try {
//            // Initialize WebRTC
//            val options = PeerConnectionFactory.InitializationOptions.builder(application)
//                .setEnableInternalTracer(false)
//                .createInitializationOptions()
//
//            PeerConnectionFactory.initialize(options)
//
//            // Create factory
//            peerConnectionFactory = PeerConnectionFactory.builder()
//                .setOptions(PeerConnectionFactory.Options())
//                .createPeerConnectionFactory()
//
//            isInitialized = true
//            logger.d { "AndroidWebRtcManager initialized successfully" }
//
//        } catch (e: Exception) {
//            logger.e(e) { "Error initializing AndroidWebRtcManager" }
//        }
//    }
//
//    override fun dispose() {
//        closePeerConnection()
//        audioTrack?.dispose()
//        peerConnectionFactory?.dispose()
//        PeerConnectionFactory.shutdownInternalTracer()
//        isInitialized = false
//    }
//
//    override fun prepareAudioForCall() {
//        // Android specific audio preparation
//        refreshAudioDevicesWithBluetoothPriority()
//    }
//
//    override fun onBluetoothConnectionChanged(isConnected: Boolean) {
//        refreshAudioDevicesWithBluetoothPriority()
//    }
//
//    override fun refreshAudioDevicesWithBluetoothPriority() {
//        // Refresh audio devices list
//        val devices = mutableSetOf<AudioDevice>()
//        // Implement device detection logic
//        _availableAudioDevices.value = devices
//    }
//
//    override fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
//        TODO("Not yet implemented")
//    }
//
//    override fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
//        // Implement audio routing
//        return true
//    }
//
//    override fun getAvailableAudioUnits(): Set<AudioUnit> {
//        return emptySet() // Implement
//    }
//
//    override fun getCurrentActiveAudioUnit(): AudioUnit? {
//        return null // Implement
//    }
//
//    override fun createOffer(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
//        val connection = getPeerConnection()
//        val constraints = MediaConstraints().apply {
//            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
//        }
//
//        connection.createOffer(object : SdpObserver {
//            override fun onCreateSuccess(sdp: SessionDescription?) {
//                sdp?.let {
//                    connection.setLocalDescription(object : SdpObserver {
//                        override fun onSetSuccess() {
//                            onSuccess(it.description)
//                        }
//                        override fun onSetFailure(error: String?) {
//                            onError(error ?: "Unknown error")
//                        }
//                        override fun onCreateSuccess(p0: SessionDescription?) {}
//                        override fun onCreateFailure(p0: String?) {}
//                    }, it)
//                }
//            }
//
//            override fun onCreateFailure(error: String?) {
//                onError(error ?: "Unknown error")
//            }
//
//            override fun onSetSuccess() {}
//            override fun onSetFailure(p0: String?) {}
//        }, constraints)
//    }
//
//    override fun createAnswer(remoteSdp: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
//        val connection = getPeerConnection()
//        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, remoteSdp)
//
//        connection.setRemoteDescription(object : SdpObserver {
//            override fun onSetSuccess() {
//                val constraints = MediaConstraints()
//                connection.createAnswer(object : SdpObserver {
//                    override fun onCreateSuccess(sdp: SessionDescription?) {
//                        sdp?.let {
//                            connection.setLocalDescription(object : SdpObserver {
//                                override fun onSetSuccess() {
//                                    onSuccess(it.description)
//                                }
//                                override fun onSetFailure(error: String?) {
//                                    onError(error ?: "Unknown error")
//                                }
//                                override fun onCreateSuccess(p0: SessionDescription?) {}
//                                override fun onCreateFailure(p0: String?) {}
//                            }, it)
//                        }
//                    }
//
//                    override fun onCreateFailure(error: String?) {
//                        onError(error ?: "Unknown error")
//                    }
//
//                    override fun onSetSuccess() {}
//                    override fun onSetFailure(p0: String?) {}
//                }, constraints)
//            }
//
//            override fun onSetFailure(error: String?) {
//                onError(error ?: "Unknown error")
//            }
//
//            override fun onCreateSuccess(p0: SessionDescription?) {}
//            override fun onCreateFailure(p0: String?) {}
//        }, remoteDesc)
//    }
//
//    override fun setRemoteDescription(sdp: String, type: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
//        val connection = getPeerConnection()
//        val sdpType = when (type.lowercase()) {
//            "offer" -> SessionDescription.Type.OFFER
//            "answer" -> SessionDescription.Type.ANSWER
//            else -> SessionDescription.Type.OFFER
//        }
//        val remoteDesc = SessionDescription(sdpType, sdp)
//
//        connection.setRemoteDescription(object : SdpObserver {
//            override fun onSetSuccess() {
//                onSuccess()
//            }
//
//            override fun onSetFailure(error: String?) {
//                onError(error ?: "Unknown error")
//            }
//
//            override fun onCreateSuccess(p0: SessionDescription?) {}
//            override fun onCreateFailure(p0: String?) {}
//        }, remoteDesc)
//    }
//
//    override fun addIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
//        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
//        peerConnection?.addIceCandidate(iceCandidate)
//    }
//
//    override fun closePeerConnection() {
//        peerConnection?.close()
//        peerConnection = null
//    }
//
//    override fun setListener(listener: WebRtcEventListener) {
//        TODO("Not yet implemented")
//    }
//
//    override fun setListener(listener: WebRtcEventListener) {
//        this.listener = listener
//    }
//
//    private fun getPeerConnection(): PeerConnection {
//        if (peerConnection == null) {
//            val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
//                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
//            }
//
//            peerConnection = peerConnectionFactory?.createPeerConnection(
//                rtcConfig,
//                object : PeerConnection.Observer {
//                    override fun onIceCandidate(candidate: IceCandidate?) {
//                        candidate?.let {
//                            listener?.onIceCandidate(it.sdp, it.sdpMid, it.sdpMLineIndex)
//                        }
//                    }
//
//                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
//                        val state = when (newState) {
//                            PeerConnection.PeerConnectionState.NEW -> WebRtcConnectionState.NEW
//                            PeerConnection.PeerConnectionState.CONNECTING -> WebRtcConnectionState.CONNECTING
//                            PeerConnection.PeerConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
//                            PeerConnection.PeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
//                            PeerConnection.PeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
//                            PeerConnection.PeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
//                            else -> WebRtcConnectionState.NEW
//                        }
//                        listener?.onConnectionStateChange(state)
//                    }
//
//                    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
//                        listener?.onRemoteAudioTrack()
//                    }
//
//                    override fun onDataChannel(p0: DataChannel?) {}
//                    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
//                    override fun onIceConnectionReceivingChange(p0: Boolean) {}
//                    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
//                    override fun onAddStream(p0: MediaStream?) {}
//                    override fun onRemoveStream(p0: MediaStream?) {}
//                    override fun onRenegotiationNeeded() {}
//                    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
//                    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
//                }
//            )
//        }
//        return peerConnection!!
//    }
//}