package com.eddyslarez.kmpsiprtc.services.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.AudioUnit
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitTypes
import com.eddyslarez.kmpsiprtc.data.models.SdpType
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import com.eddyslarez.kmpsiprtc.platform.AndroidContext.getApplication
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.audio.AudioController
import com.eddyslarez.kmpsiprtc.services.audio.BluetoothController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.*

actual fun createWebRtcManager(): WebRtcManager = AndroidWebRtcManager()

class AndroidWebRtcManager : WebRtcManager {
    private val TAG = "AndroidWebRtcManager"
    private val context: Context = getApplication()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Controllers
    private lateinit var peerConnectionController: PeerConnectionController
    private lateinit var audioController: AudioController
    private lateinit var bluetoothController: BluetoothController

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isFactoryInitialized = false

    private var webRtcEventListener: WebRtcEventListener? = null

    // ==================== INITIALIZATION ====================

    override fun initialize() {
        log.d(TAG) { "🔧 Initializing WebRTC Manager..." }

        if (isFactoryInitialized) {
            log.d(TAG) { "✅ Factory already initialized, creating new peer connection" }
            // ✅ Solo recrear peer connection si la factory ya existe
            if (!::peerConnectionController.isInitialized || !peerConnectionController.hasPeerConnection()) {
                peerConnectionController.createNewPeerConnection()
            }
            isInitialized = true
            return
        }

        try {
            // Initialize Bluetooth Controller first
            bluetoothController = BluetoothController(context)
            bluetoothController.initialize()

            // Initialize Audio Controller
            audioController = AudioController(
                context = context,
                bluetoothController = bluetoothController,
                onDeviceChanged = { device ->
                    webRtcEventListener?.onAudioDeviceChanged(device)
                }
            )
            audioController.initialize()

            // Initialize PeerConnection Controller
            peerConnectionController = PeerConnectionController(
                context = context,
                onIceCandidate = { candidate, sdpMid, sdpMLineIndex ->
                    webRtcEventListener?.onIceCandidate(candidate, sdpMid, sdpMLineIndex)
                },
                onConnectionStateChange = { state ->
                    handleConnectionStateChange(state)
                },
                onRemoteAudioTrack = {
                    webRtcEventListener?.onRemoteAudioTrack()
                }
            )
            peerConnectionController.initialize()

            isFactoryInitialized = true  // ✅ MARCAR FACTORY COMO INICIALIZADA
            isInitialized = true
            log.d(TAG) { "✅✅✅ WebRTC initialized successfully ✅✅✅" }

        } catch (e: Exception) {
            log.e(TAG) { "💥 Error initializing WebRTC: ${e.message}" }
            e.printStackTrace()
            isFactoryInitialized = false
            isInitialized = false
        }
    }

    override fun isInitialized(): Boolean = isInitialized

    // ==================== CONNECTION MANAGEMENT ====================

    override suspend fun createOffer(): String {
        log.d(TAG) { "📝 Creating offer..." }
        ensureInitialized()

        // ✅ Verificar que el peer connection existe
        if (!peerConnectionController.hasPeerConnection()) {
            log.w(TAG) { "⚠️ PeerConnection not found, recreating..." }
            peerConnectionController.createNewPeerConnection()
        }

        audioController.startForCall()
        return peerConnectionController.createOffer()
    }

    override suspend fun createAnswer(offerSdp: String): String {
        log.d(TAG) { "📝 Creating answer..." }
        ensureInitialized()

        // ✅ Verificar que el peer connection existe
        if (!peerConnectionController.hasPeerConnection()) {
            log.w(TAG) { "⚠️ PeerConnection not found, recreating..." }
            peerConnectionController.createNewPeerConnection()
        }

        audioController.startForCall()
        return peerConnectionController.createAnswer(offerSdp)
    }


    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
        ensureInitialized()
        peerConnectionController.setRemoteDescription(sdp, type)
    }

    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        ensureInitialized()
        peerConnectionController.addIceCandidate(candidate, sdpMid, sdpMLineIndex)
    }


    override fun closePeerConnection() {
        log.d(TAG) { "Closing peer connection" }

        // ✅ CRÍTICO: Solo cerrar, NO cambiar isInitialized
        peerConnectionController.closePeerConnection()
        audioController.stop()
    }

    override fun dispose() {
        log.d(TAG) { "Disposing WebRTC Manager" }

        try {
            // 1️⃣ Primero detener el audio
            if (::audioController.isInitialized) {
                audioController.stop()
            }

            // 2️⃣ Cerrar la conexión peer (sin disponer recursos aún)
            if (::peerConnectionController.isInitialized) {
                peerConnectionController.closePeerConnection()
            }

            // 3️⃣ Esperar un poco para que se complete el cierre
            Thread.sleep(100)

            // 4️⃣ Ahora sí, disponer el PeerConnectionController
            if (::peerConnectionController.isInitialized) {
                peerConnectionController.dispose()
            }

            // 5️⃣ Disponer AudioController
            if (::audioController.isInitialized) {
                audioController.dispose()
            }

            // 6️⃣ Disponer BluetoothController
            if (::bluetoothController.isInitialized) {
                bluetoothController.dispose()
            }

            // 7️⃣ Marcar como no inicializado
            isInitialized = false
            isFactoryInitialized = false

            log.d(TAG) { "✅ WebRTC Manager disposed successfully" }

        } catch (e: Exception) {
            log.e(TAG) { "Error during disposal: ${e.message}" }
            e.printStackTrace()

            // Asegurarse de marcar como no inicializado incluso si hay error
            isInitialized = false
            isFactoryInitialized = false
        }
    }
//    override fun dispose() {
//        log.d(TAG) { "Disposing WebRTC Manager" }
//
//        try {
//            peerConnectionController.dispose()
//            audioController.dispose()
//            bluetoothController.dispose()
//
//            // ✅ Solo ahora marcar como no inicializado
//            isInitialized = false
//            isFactoryInitialized = false
//        } catch (e: Exception) {
//            log.e(TAG) { "Error during disposal: ${e.message}" }
//        }
//    }


    override fun getConnectionState(): WebRtcConnectionState {
        return if (::peerConnectionController.isInitialized) {
            peerConnectionController.getConnectionState()
        } else {
            WebRtcConnectionState.DISCONNECTED
        }
    }


    override fun getLocalDescription(): String? {
        return if (::peerConnectionController.isInitialized) {
            peerConnectionController.getLocalDescription()
        } else {
            null
        }
    }

    // ==================== AUDIO MANAGEMENT ====================

    override fun setAudioEnabled(enabled: Boolean) {
        if (!::peerConnectionController.isInitialized) {
            log.w(TAG) { "⚠️ Cannot set audio - controller not initialized" }
            return
        }
        peerConnectionController.setAudioEnabled(enabled)
        audioController.setMicrophoneMute(!enabled)
    }

    override fun setMuted(muted: Boolean) {
        if (!::peerConnectionController.isInitialized) {
            log.w(TAG) { "⚠️ Cannot set mute - controller not initialized" }
            return
        }
        peerConnectionController.setMuted(muted)
        audioController.setMicrophoneMute(muted)
    }

    override fun isMuted(): Boolean {
        return if (::audioController.isInitialized) {
            audioController.isMicrophoneMuted()
        } else {
            false
        }
    }

    override fun setActiveAudioRoute(audioUnitType: AudioUnitTypes): Boolean {
        return audioController.setActiveRoute(audioUnitType)
    }

    override fun getActiveAudioRoute(): AudioUnitTypes? {
        return audioController.getActiveRoute()
    }

    override fun getAvailableAudioRoutes(): Set<AudioUnitTypes> {
        return audioController.getAvailableRoutes()
    }

    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        return audioController.getAllDevices()
    }

    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        return audioController.changeOutputDevice(device)
    }

    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
        return audioController.changeInputDevice(device)
    }

    override fun getCurrentInputDevice(): AudioDevice? {
        return audioController.getCurrentInputDevice()
    }

    override fun getCurrentOutputDevice(): AudioDevice? {
        return audioController.getCurrentOutputDevice()
    }

    override fun getAvailableAudioUnits(): Set<AudioUnit> {
        return audioController.getAvailableAudioUnits()
    }

    override fun getCurrentActiveAudioUnit(): AudioUnit? {
        return audioController.getCurrentActiveAudioUnit()
    }

    override fun onBluetoothConnectionChanged(isConnected: Boolean) {
        bluetoothController.onConnectionChanged(isConnected)
        audioController.refreshDevices()
    }

    override fun refreshAudioDevicesWithBluetoothPriority() {
        audioController.refreshWithBluetoothPriority()
    }

    override fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
        return audioController.setActiveRoute(audioUnitType)
    }

    override fun prepareAudioForCall() {
        audioController.startForCall()
    }

    override fun prepareAudioForIncomingCall() {
        audioController.startForCall()
    }

    override fun diagnoseAudioIssues(): String {
        return buildString {
            appendLine("=== AUDIO DIAGNOSIS ===")
            appendLine("Factory Initialized: $isFactoryInitialized")
            appendLine("WebRTC Initialized: $isInitialized")
            appendLine("Connection State: ${getConnectionState()}")

            if (::audioController.isInitialized) {
                appendLine("\n--- Audio Controller ---")
                appendLine(audioController.diagnose())
            }

            if (::bluetoothController.isInitialized) {
                appendLine("\n--- Bluetooth Controller ---")
                appendLine(bluetoothController.diagnose())
            }

            if (::peerConnectionController.isInitialized) {
                appendLine("\n--- PeerConnection Controller ---")
                appendLine(peerConnectionController.diagnose())
            }
        }
    }


    // ==================== DTMF & MEDIA DIRECTION ====================

    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
        return peerConnectionController.sendDtmf(tones, duration, gap)
    }

    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
        when (direction) {
            WebRtcManager.MediaDirection.SENDRECV -> setAudioEnabled(true)
            WebRtcManager.MediaDirection.SENDONLY -> setAudioEnabled(true)
            WebRtcManager.MediaDirection.RECVONLY -> setAudioEnabled(false)
            WebRtcManager.MediaDirection.INACTIVE -> setAudioEnabled(false)
        }
    }

    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
        return try {
            setRemoteDescription(modifiedSdp, SdpType.ANSWER)
            true
        } catch (e: Exception) {
            log.e(TAG) { "Failed to apply modified SDP: ${e.message}" }
            false
        }
    }

    // ==================== LISTENER ====================

    override fun setListener(listener: WebRtcEventListener?) {
        this.webRtcEventListener = listener
    }

    // ==================== PRIVATE HELPERS ====================

    private fun handleConnectionStateChange(state: WebRtcConnectionState) {
        when (state) {
            WebRtcConnectionState.CONNECTED -> {
                mainHandler.post {
                    setAudioEnabled(true)
                    audioController.setMicrophoneMute(false)
                }
            }
            WebRtcConnectionState.DISCONNECTED,
            WebRtcConnectionState.FAILED,
            WebRtcConnectionState.CLOSED -> {
                audioController.stop()
            }
            else -> {}
        }
        webRtcEventListener?.onConnectionStateChange(state)
    }

    private fun ensureInitialized() {
        if (!isInitialized) {
            log.w(TAG) { "⚠️ WebRTC not initialized, initializing now..." }
            initialize()
        }

        // ✅ Verificación adicional del peer connection
        if (isInitialized && !peerConnectionController.hasPeerConnection()) {
            log.w(TAG) { "⚠️ PeerConnection missing, recreating..." }
            peerConnectionController.createNewPeerConnection()
        }
    }

    suspend fun awaitInitialization(timeoutMs: Long = 5000): Boolean =
        withContext(Dispatchers.Default) {
            if (isInitialized) return@withContext true

            val startTime = System.currentTimeMillis()
            while (!isInitialized && (System.currentTimeMillis() - startTime) < timeoutMs) {
                delay(100)
            }

            if (!isInitialized) {
                log.e(TAG) { "❌ Initialization timeout after ${timeoutMs}ms" }
            }

            isInitialized
        }
}
//
//actual fun createWebRtcManager(): WebRtcManager = AndroidWebRtcManager()
//
//class AndroidWebRtcManager : WebRtcManager {
//    private val TAG = "AndroidWebRtcManager"
//    private val context: Context = getApplication()
//    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
//    private val mainHandler = Handler(Looper.getMainLooper())
//
//    // WebRTC Components
//    private lateinit var peerConnectionFactory: PeerConnectionFactory
//    private var peerConnection: PeerConnection? = null
//    private var localAudioTrack: AudioTrack? = null
//    private var localAudioSource: AudioSource? = null
//    private var audioDeviceModule: AudioDeviceModule? = null
//
//    // Audio Management
//    private var audioManager: AudioManager? = null
//    private var audioFocusRequest: AudioFocusRequest? = null
//    private var bluetoothAdapter: BluetoothAdapter? = null
//    private var bluetoothHeadset: BluetoothHeadset? = null
//
//    // State
//    @Volatile
//    private var isInitialized = false
//    private var isMuted = false
//    private var isAudioManagerStarted = false
//    private var currentConnectionState = WebRtcConnectionState.DISCONNECTED
//
//    // Audio Device Management
//    private val audioDevices = mutableListOf<AudioDevice>()
//    private val bluetoothDevices = mutableListOf<AudioDevice>()
//    private var savedAudioMode = AudioManager.MODE_NORMAL
//    private var savedIsSpeakerPhoneOn = false
//
//    private var webRtcEventListener: WebRtcEventListener? = null
//
//    // EGL for video support (if needed later)
//    private val eglBase = EglBase.create()
//
//    private val iceServers = listOf(
//        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
//        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
//    )
//
//    // ==================== BLUETOOTH PROFILE LISTENER ====================
//
//    private val bluetoothProfileListener = object : BluetoothProfile.ServiceListener {
//        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
//            if (profile == BluetoothProfile.HEADSET) {
//                bluetoothHeadset = proxy as BluetoothHeadset
//                updateBluetoothDevices()
//            }
//        }
//
//        override fun onServiceDisconnected(profile: Int) {
//            if (profile == BluetoothProfile.HEADSET) {
//                bluetoothHeadset = null
//                bluetoothDevices.clear()
//            }
//        }
//    }
//
//    // ==================== PEER CONNECTION OBSERVER ====================
//
//    private val peerConnectionObserver = object : PeerConnection.Observer {
//        override fun onIceCandidate(candidate: IceCandidate) {
//            log.d(TAG) { "ICE candidate: ${candidate.sdpMid}:${candidate.sdpMLineIndex}" }
//            webRtcEventListener?.onIceCandidate(
//                candidate.sdp,
//                candidate.sdpMid ?: "",
//                candidate.sdpMLineIndex
//            )
//        }
//
//        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
//            log.d(TAG) { "Connection state changed: $newState" }
//
//            val state = when (newState) {
//                PeerConnection.PeerConnectionState.CONNECTED -> {
//                    mainHandler.post {
//                        setAudioEnabled(true)
//                        audioManager?.isMicrophoneMute = false
//                    }
//                    WebRtcConnectionState.CONNECTED
//                }
//                PeerConnection.PeerConnectionState.CONNECTING -> WebRtcConnectionState.CONNECTING
//                PeerConnection.PeerConnectionState.DISCONNECTED -> {
//                    stopAudioManager()
//                    WebRtcConnectionState.DISCONNECTED
//                }
//                PeerConnection.PeerConnectionState.FAILED -> {
//                    stopAudioManager()
//                    WebRtcConnectionState.FAILED
//                }
//                PeerConnection.PeerConnectionState.CLOSED -> {
//                    stopAudioManager()
//                    WebRtcConnectionState.CLOSED
//                }
//                else -> WebRtcConnectionState.NEW
//            }
//
//            currentConnectionState = state
//            webRtcEventListener?.onConnectionStateChange(state)
//        }
//
//        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
//        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
//            log.d(TAG) { "Remote track added" }
//            val track = receiver?.track()
//            if (track is AudioTrack) {
//                track.setEnabled(true)
//                webRtcEventListener?.onRemoteAudioTrack()
//            }
//        }
//
//        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
//            log.d(TAG) { "ICE connection state: $state" }
//        }
//
//        override fun onIceConnectionReceivingChange(receiving: Boolean) {
//            log.d(TAG) { "ICE connection receiving: $receiving" }
//        }
//
//        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
//            log.d(TAG) { "ICE gathering state: $state" }
//        }
//
//        override fun onSignalingChange(state: PeerConnection.SignalingState) {
//            log.d(TAG) { "Signaling state: $state" }
//        }
//
//        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
//            log.d(TAG) { "ICE candidates removed" }
//        }
//
//        override fun onAddStream(stream: MediaStream?) {}
//        override fun onRemoveStream(stream: MediaStream?) {}
//        override fun onDataChannel(dc: DataChannel?) {}
//        override fun onRenegotiationNeeded() {}
//        override fun onTrack(transceiver: RtpTransceiver?) {}
//    }
//
//    // ==================== INITIALIZATION ====================
//
//    override fun initialize() {
//        log.d(TAG) { "🔧 Initializing WebRTC Manager..." }
//
//        if (isInitialized) {
//            log.d(TAG) { "✅ Already initialized" }
//            return
//        }
//
//        try {
//            // Initialize PeerConnectionFactory
//            PeerConnectionFactory.initialize(
//                PeerConnectionFactory.InitializationOptions
//                    .builder(context)
//                    .setEnableInternalTracer(true)
//                    .createInitializationOptions()
//            )
//            log.d(TAG) { "✅ PeerConnectionFactory initialized" }
//
//            // Create AudioDeviceModule
//            val audioModule = JavaAudioDeviceModule.builder(context)
//                .setUseHardwareAcousticEchoCanceler(true)
//                .setUseHardwareNoiseSuppressor(true)
//                .setUseStereoInput(true)
//                .setUseStereoOutput(true)
//                .createAudioDeviceModule()
//
//            this.audioDeviceModule = audioModule
//            log.d(TAG) { "✅ AudioDeviceModule created" }
//
//            // Create PeerConnectionFactory
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
//            // Initialize AudioManager
//            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
//            log.d(TAG) { "✅ AudioManager obtained" }
//
//            // Initialize Bluetooth
//            try {
//                bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
//                bluetoothAdapter?.getProfileProxy(
//                    context,
//                    bluetoothProfileListener,
//                    BluetoothProfile.HEADSET
//                )
//                log.d(TAG) { "✅ Bluetooth setup initiated" }
//            } catch (e: Exception) {
//                log.w(TAG) { "⚠️ Bluetooth not available: ${e.message}" }
//            }
//
//            isInitialized = true
//            currentConnectionState = WebRtcConnectionState.NEW
//
//            log.d(TAG) { "✅✅✅ WebRTC initialized successfully ✅✅✅" }
//
//        } catch (e: Exception) {
//            log.e(TAG) { "💥 Error initializing WebRTC: ${e.message}" }
//            e.printStackTrace()
//            isInitialized = false
//        }
//    }
//
//    // ==================== PEER CONNECTION MANAGEMENT ====================
//
//    private fun createPeerConnection() {
//        log.d(TAG) { "Creating PeerConnection..." }
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
//        log.d(TAG) { "✅ PeerConnection created" }
//    }
//
//    private fun prepareLocalAudio() {
//        log.d(TAG) { "Preparing local audio..." }
//
//        // Clean up old tracks
//        localAudioTrack?.let {
//            try {
//                it.setEnabled(false)
//                it.dispose()
//            } catch (e: Exception) {
//                log.w(TAG) { "Error disposing old audio track: ${e.message}" }
//            }
//        }
//
//        localAudioSource?.let {
//            try {
//                it.dispose()
//            } catch (e: Exception) {
//                log.w(TAG) { "Error disposing old audio source: ${e.message}" }
//            }
//        }
//
//        val audioConstraints = MediaConstraints().apply {
//            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
//            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
//            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
//            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
//        }
//
//        localAudioSource = peerConnectionFactory.createAudioSource(audioConstraints)
//        localAudioTrack = peerConnectionFactory.createAudioTrack(
//            "AUDIO_TRACK_${System.currentTimeMillis()}",
//            localAudioSource
//        )
//        localAudioTrack?.setEnabled(true)
//
//        peerConnection?.addTrack(localAudioTrack, listOf("audio_stream"))
//
//        log.d(TAG) { "✅ Local audio prepared and added to PeerConnection" }
//    }
//
//    // ==================== SDP OPERATIONS ====================
//
//    override suspend fun createOffer(): String = withContext(Dispatchers.IO) {
//        log.d(TAG) { "📝 Creating offer..." }
//
//        if (!isInitialized) {
//            initialize()
//        }
//
//        if (peerConnection == null) {
//            createPeerConnection()
//        }
//
//        prepareLocalAudio()
//
//        if (!isAudioManagerStarted) {
//            startAudioManager()
//        }
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
//                    log.d(TAG) { "Offer created successfully" }
//                    pc.setLocalDescription(object : SdpObserver {
//                        override fun onSetSuccess() {
//                            log.d(TAG) { "✅ Local description set" }
//                            cont.resume(desc.description)
//                        }
//                        override fun onSetFailure(error: String?) {
//                            log.e(TAG) { "Failed to set local description: $error" }
//                            cont.resumeWithException(Exception("SetLocalDescription failed: $error"))
//                        }
//                        override fun onCreateSuccess(p0: SessionDescription?) {}
//                        override fun onCreateFailure(p0: String?) {}
//                    }, desc)
//                }
//
//                override fun onCreateFailure(error: String?) {
//                    log.e(TAG) { "Failed to create offer: $error" }
//                    cont.resumeWithException(Exception("CreateOffer failed: $error"))
//                }
//
//                override fun onSetSuccess() {}
//                override fun onSetFailure(p0: String?) {}
//            }, constraints)
//        }
//    }
//
//    override suspend fun createAnswer(offerSdp: String): String = withContext(Dispatchers.IO) {
//        log.d(TAG) { "📝 Creating answer..." }
//
//        if (offerSdp.isBlank()) {
//            throw IllegalArgumentException("Offer SDP cannot be empty")
//        }
//
//        if (!isInitialized) {
//            initialize()
//        }
//
//        if (peerConnection == null) {
//            createPeerConnection()
//        }
//
//        prepareLocalAudio()
//
//        // Set remote description
//        setRemoteDescription(offerSdp, SdpType.OFFER)
//
//        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")
//
//        if (!isAudioManagerStarted) {
//            startAudioManager()
//        }
//
//        val constraints = MediaConstraints().apply {
//            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
//            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
//        }
//
//        suspendCancellableCoroutine { cont ->
//            pc.createAnswer(object : SdpObserver {
//                override fun onCreateSuccess(desc: SessionDescription) {
//                    log.d(TAG) { "Answer created successfully" }
//                    pc.setLocalDescription(object : SdpObserver {
//                        override fun onSetSuccess() {
//                            log.d(TAG) { "✅ Local description set" }
//                            cont.resume(desc.description)
//                        }
//                        override fun onSetFailure(error: String?) {
//                            log.e(TAG) { "Failed to set local description: $error" }
//                            cont.resumeWithException(Exception("SetLocalDescription failed: $error"))
//                        }
//                        override fun onCreateSuccess(p0: SessionDescription?) {}
//                        override fun onCreateFailure(p0: String?) {}
//                    }, desc)
//                }
//
//                override fun onCreateFailure(error: String?) {
//                    log.e(TAG) { "Failed to create answer: $error" }
//                    cont.resumeWithException(Exception("CreateAnswer failed: $error"))
//                }
//
//                override fun onSetSuccess() {}
//                override fun onSetFailure(p0: String?) {}
//            }, constraints)
//        }
//    }
//
//    override suspend fun setRemoteDescription(sdp: String, type: SdpType) =
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
//                    if (type == SdpType.ANSWER) {
//                        setAudioEnabled(true)
//                        audioManager?.isMicrophoneMute = false
//                    }
//                    cont.resume(Unit)
//                }
//
//                override fun onSetFailure(error: String?) {
//                    log.e(TAG) { "Failed to set remote description: $error" }
//                    cont.resumeWithException(Exception("SetRemoteDescription failed: $error"))
//                }
//
//                override fun onCreateSuccess(p0: SessionDescription?) {}
//                override fun onCreateFailure(p0: String?) {}
//            }, sessionDescription)
//        }
//
//    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
//        withContext(Dispatchers.IO) {
//            log.d(TAG) { "Adding ICE candidate" }
//            val iceCandidate = IceCandidate(sdpMid ?: "", sdpMLineIndex ?: 0, candidate)
//            peerConnection?.addIceCandidate(iceCandidate)
//        }
//    }
//
//    // ==================== AUDIO MANAGEMENT ====================
//
//    private fun startAudioManager() {
//        if (isAudioManagerStarted) {
//            log.d(TAG) { "AudioManager already started" }
//            return
//        }
//
//        log.d(TAG) { "🔊 Starting AudioManager for call" }
//
//        audioManager?.let { am ->
//            savedAudioMode = am.mode
//            savedIsSpeakerPhoneOn = am.isSpeakerphoneOn
//
//            requestAudioFocus()
//
//            am.mode = AudioManager.MODE_IN_COMMUNICATION
//            am.isSpeakerphoneOn = false
//            am.isMicrophoneMute = false
//
//            scanAudioDevices()
//
//            mainHandler.postDelayed({
//                selectDefaultAudioDeviceWithPriority()
//            }, 100)
//
//            isAudioManagerStarted = true
//            log.d(TAG) { "✅ AudioManager started" }
//        }
//    }
//
//    private fun stopAudioManager() {
//        if (!isAudioManagerStarted) return
//
//        log.d(TAG) { "🔇 Stopping AudioManager" }
//
//        audioManager?.let { am ->
//            am.mode = savedAudioMode
//            am.isSpeakerphoneOn = savedIsSpeakerPhoneOn
//            am.stopBluetoothSco()
//            abandonAudioFocus()
//        }
//
//        isAudioManagerStarted = false
//    }
//
//    private fun requestAudioFocus() {
//        audioManager?.let { am ->
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                val audioAttributes = AudioAttributes.Builder()
//                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
//                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
//                    .build()
//
//                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
//                    .setAudioAttributes(audioAttributes)
//                    .setAcceptsDelayedFocusGain(true)
//                    .build()
//
//                am.requestAudioFocus(audioFocusRequest!!)
//            } else {
//                @Suppress("DEPRECATION")
//                am.requestAudioFocus(
//                    null,
//                    AudioManager.STREAM_VOICE_CALL,
//                    AudioManager.AUDIOFOCUS_GAIN
//                )
//            }
//        }
//    }
//
//    private fun abandonAudioFocus() {
//        audioManager?.let { am ->
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
//            } else {
//                @Suppress("DEPRECATION")
//                am.abandonAudioFocus(null)
//            }
//        }
//    }
//
//    // ==================== AUDIO DEVICE MANAGEMENT ====================
//
//    private fun selectDefaultAudioDeviceWithPriority() {
//        scanAudioDevices()
//
//        val availableTypes = audioDevices.map { it.audioUnit.type }.toSet()
//        log.d(TAG) { "Available audio devices: $availableTypes" }
//
//        val priorityType = when {
//            AudioUnitTypes.BLUETOOTH in availableTypes -> AudioUnitTypes.BLUETOOTH
//            AudioUnitTypes.HEADSET in availableTypes -> AudioUnitTypes.HEADSET
//            else -> AudioUnitTypes.EARPIECE
//        }
//
//        log.d(TAG) { "Selected priority device: $priorityType" }
//        setActiveAudioRoute(priorityType)
//    }
//
//    override fun setActiveAudioRoute(audioUnitType: AudioUnitTypes): Boolean {
//        return audioManager?.let { am ->
//            try {
//                when (audioUnitType) {
//                    AudioUnitTypes.SPEAKER -> {
//                        am.isSpeakerphoneOn = true
//                        am.isBluetoothScoOn = false
//                        am.stopBluetoothSco()
//                    }
//                    AudioUnitTypes.EARPIECE -> {
//                        am.isSpeakerphoneOn = false
//                        am.isBluetoothScoOn = false
//                        am.stopBluetoothSco()
//                    }
//                    AudioUnitTypes.BLUETOOTH, AudioUnitTypes.BLUETOOTHA2DP -> {
//                        am.isSpeakerphoneOn = false
//                        am.isBluetoothScoOn = true
//                        am.startBluetoothSco()
//                    }
//                    AudioUnitTypes.HEADSET, AudioUnitTypes.HEADPHONES -> {
//                        am.isSpeakerphoneOn = false
//                        am.isBluetoothScoOn = false
//                        am.stopBluetoothSco()
//                    }
//                    else -> return false
//                }
//
//                serviceScope.launch {
//                    delay(100)
//                    webRtcEventListener?.onAudioDeviceChanged(getCurrentOutputDevice())
//                }
//
//                true
//            } catch (e: Exception) {
//                log.e(TAG) { "Error setting audio route: ${e.message}" }
//                false
//            }
//        } ?: false
//    }
//
//    override fun getActiveAudioRoute(): AudioUnitTypes? {
//        return audioManager?.let { am ->
//            when {
//                am.isBluetoothScoOn -> AudioUnitTypes.BLUETOOTH
//                am.isSpeakerphoneOn -> AudioUnitTypes.SPEAKER
//                am.isWiredHeadsetOn -> AudioUnitTypes.HEADSET
//                else -> AudioUnitTypes.EARPIECE
//            }
//        }
//    }
//
//    override fun getAvailableAudioRoutes(): Set<AudioUnitTypes> {
//        val routes = mutableSetOf<AudioUnitTypes>()
//        audioManager?.let { am ->
//            routes.add(AudioUnitTypes.EARPIECE)
//            routes.add(AudioUnitTypes.SPEAKER)
//            if (am.isWiredHeadsetOn) routes.add(AudioUnitTypes.HEADSET)
//            if (am.isBluetoothScoAvailableOffCall || bluetoothDevices.isNotEmpty()) {
//                routes.add(AudioUnitTypes.BLUETOOTH)
//            }
//        }
//        return routes
//    }
//
//    private fun scanAudioDevices() {
//        audioDevices.clear()
//        bluetoothDevices.clear()
//
//        audioManager?.let { am ->
//            audioDevices.add(createMicrophoneDevice(am))
//            audioDevices.add(createBuiltInEarpieceDevice(am))
//            audioDevices.add(createBuiltInSpeakerDevice(am))
//
//            if (am.isWiredHeadsetOn) {
//                audioDevices.add(createWiredHeadsetDevice(am))
//            }
//
//            scanBluetoothDevices(am)
//            audioDevices.addAll(bluetoothDevices)
//        }
//    }
//
//    @SuppressLint("MissingPermission")
//    private fun scanBluetoothDevices(am: AudioManager) {
//        bluetoothHeadset?.let { headset ->
//            try {
//                val connectedDevices = headset.connectedDevices
//                connectedDevices.forEach { device ->
//                    val audioDevice = AudioDevice(
//                        name = device.name ?: "Dispositivo Bluetooth",
//                        descriptor = device.address,
//                        nativeDevice = device,
//                        isOutput = true,
//                        audioUnit = AudioUnit(
//                            type = AudioUnitTypes.BLUETOOTH,
//                            capability = AudioUnitCompatibilities.ALL,
//                            isCurrent = getActiveAudioRoute() == AudioUnitTypes.BLUETOOTH,
//                            isDefault = false
//                        ),
//                        connectionState = DeviceConnectionState.CONNECTED,
//                        isWireless = true,
//                        supportsHDVoice = true,
//                        latency = 50
//                    )
//                    bluetoothDevices.add(audioDevice)
//                }
//            } catch (e: Exception) {
//                log.e(TAG) { "Error scanning Bluetooth devices: ${e.message}" }
//            }
//        }
//    }
//
//    private fun updateBluetoothDevices() {
//        scanAudioDevices()
//        webRtcEventListener?.onAudioDeviceChanged(getCurrentOutputDevice())
//    }
//
//    // ==================== AUDIO DEVICE CREATION ====================
//
//    private fun createMicrophoneDevice(am: AudioManager) = AudioDevice(
//        name = "Micrófono integrado",
//        descriptor = "builtin_mic",
//        nativeDevice = null,
//        isOutput = false,
//        audioUnit = AudioUnit(
//            type = AudioUnitTypes.MICROPHONE,
//            capability = AudioUnitCompatibilities.RECORD,
//            isCurrent = getActiveAudioRoute() == AudioUnitTypes.EARPIECE,
//            isDefault = true
//        ),
//        connectionState = DeviceConnectionState.CONNECTED,
//        isWireless = false,
//        supportsHDVoice = true,
//        latency = 10
//    )
//
//    private fun createBuiltInEarpieceDevice(am: AudioManager) = AudioDevice(
//        name = "Auricular integrado",
//        descriptor = "builtin_earpiece",
//        nativeDevice = null,
//        isOutput = true,
//        audioUnit = AudioUnit(
//            type = AudioUnitTypes.EARPIECE,
//            capability = AudioUnitCompatibilities.PLAY,
//            isCurrent = getActiveAudioRoute() == AudioUnitTypes.EARPIECE,
//            isDefault = true
//        ),
//        connectionState = DeviceConnectionState.CONNECTED,
//        isWireless = false,
//        supportsHDVoice = true,
//        latency = 15
//    )
//
//    private fun createBuiltInSpeakerDevice(am: AudioManager) = AudioDevice(
//        name = "Altavoz",
//        descriptor = "builtin_speaker",
//        nativeDevice = null,
//        isOutput = true,
//        audioUnit = AudioUnit(
//            type = AudioUnitTypes.SPEAKER,
//            capability = AudioUnitCompatibilities.PLAY,
//            isCurrent = getActiveAudioRoute() == AudioUnitTypes.SPEAKER,
//            isDefault = false
//        ),
//        connectionState = DeviceConnectionState.CONNECTED,
//        isWireless = false,
//        supportsHDVoice = false,
//        latency = 20
//    )
//
//    private fun createWiredHeadsetDevice(am: AudioManager) = AudioDevice(
//        name = "Auricular cableado",
//        descriptor = "wired_headset",
//        nativeDevice = null,
//        isOutput = true,
//        audioUnit = AudioUnit(
//            type = AudioUnitTypes.HEADSET,
//            capability = AudioUnitCompatibilities.ALL,
//            isCurrent = getActiveAudioRoute() == AudioUnitTypes.HEADSET,
//            isDefault = false
//        ),
//        connectionState = DeviceConnectionState.CONNECTED,
//        isWireless = false,
//        supportsHDVoice = true,
//        latency = 10
//    )
//
//    // ==================== INTERFACE IMPLEMENTATIONS ====================
//
//    override fun isInitialized(): Boolean = isInitialized
//
//    override fun closePeerConnection() {
//        log.d(TAG) { "Closing peer connection" }
//        peerConnection?.close()
//        peerConnection = null
//        currentConnectionState = WebRtcConnectionState.DISCONNECTED
//        stopAudioManager()
//    }0
//
//    override fun dispose() {
//        log.d(TAG) { "Disposing WebRTC Manager" }
//
//        serviceScope.cancel()
//        closePeerConnection()
//
//        try {
//            localAudioTrack?.setEnabled(false)
//            localAudioTrack?.dispose()
//        } catch (e: Exception) {
//            log.w(TAG) { "Error disposing audio track: ${e.message}" }
//        }
//
//        try {
//            localAudioSource?.dispose()
//        } catch (e: Exception) {
//            log.w(TAG) { "Error disposing audio source: ${e.message}" }
//        }
//
//        try {
//            audioDeviceModule?.release()
//            peerConnectionFactory.dispose()
//        } catch (e: Exception) {
//            log.e(TAG) { "Error disposing factory: ${e.message}" }
//        }
//
//        bluetoothHeadset?.let {
//            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it)
//        }
//
//        eglBase.release()
//        isInitialized = false
//    }
//
//    override fun setAudioEnabled(enabled: Boolean) {
//        try {
//            localAudioTrack?.setEnabled(enabled)
//            audioManager?.isMicrophoneMute = !enabled
//        } catch (e: Exception) {
//            log.e(TAG) { "Error setting audio enabled: ${e.message}" }
//        }
//    }
//
//    override fun setMuted(muted: Boolean) {
//        isMuted = muted
//        localAudioTrack?.setEnabled(!muted)
//        audioManager?.isMicrophoneMute = muted
//    }
//
//    override fun isMuted(): Boolean = isMuted
//
//    override fun getConnectionState(): WebRtcConnectionState = currentConnectionState
//
//    override fun setListener(listener: WebRtcEventListener?) {
//        this.webRtcEventListener = listener
//    }
//
//    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
//        if (!isInitialized || peerConnection == null) return false
//
//        return try {
//            val audioSender = peerConnection?.senders?.find { sender ->
//                sender.track()?.kind() == "audio"
//            }
//            // Note: DTMF support requires additional setup
//            log.d(TAG) { "Sending DTMF tones: $tones" }
//            true
//        } catch (e: Exception) {
//            log.e(TAG) { "Error sending DTMF: ${e.message}" }
//            false
//        }
//    }
//
//    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
//        scanAudioDevices()
//        val inputs = audioDevices.filter { it.isInput && it.canRecord }
//        val outputs = audioDevices.filter { it.isOutput && it.canPlay }
//        return Pair(inputs, outputs)
//    }
//
//    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
//        return setActiveAudioRoute(device.audioUnit.type)
//    }
//
//    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean = true
//
//    override fun getCurrentInputDevice(): AudioDevice? {
//        return audioDevices.firstOrNull { device ->
//            when (getActiveAudioRoute()) {
//                AudioUnitTypes.BLUETOOTH -> device.isBluetooth && device.isInput
//                else -> device.audioUnit.type == AudioUnitTypes.MICROPHONE
//            }
//        }
//    }
//
//    override fun getCurrentOutputDevice(): AudioDevice? {
//        return audioDevices.firstOrNull { device ->
//            device.isOutput && device.audioUnit.type == getActiveAudioRoute()
//        }
//    }
//
//    override fun onBluetoothConnectionChanged(isConnected: Boolean) {
//        if (isConnected) {
//            mainHandler.postDelayed({
//                scanAudioDevices()
//                setActiveAudioRoute(AudioUnitTypes.BLUETOOTH)
//            }, 300)
//        } else {
//            scanAudioDevices()
//        }
//    }
//
//    override fun refreshAudioDevicesWithBluetoothPriority() {
//        selectDefaultAudioDeviceWithPriority()
//    }
//
//    override fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
//        return setActiveAudioRoute(audioUnitType)
//    }
//
//    override fun getAvailableAudioUnits(): Set<AudioUnit> {
//        scanAudioDevices()
//        return audioDevices.map { it.audioUnit }.toSet()
//    }
//
//    override fun getCurrentActiveAudioUnit(): AudioUnit? {
//        return getCurrentOutputDevice()?.audioUnit
//    }
//
//    override fun prepareAudioForCall() {
//        if (!isAudioManagerStarted) {
//            startAudioManager()
//        }
//    }
//
//    override fun prepareAudioForIncomingCall() {
//        prepareAudioForCall()
//    }
//
//    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
//        when (direction) {
//            WebRtcManager.MediaDirection.SENDRECV -> setAudioEnabled(true)
//            WebRtcManager.MediaDirection.SENDONLY -> setAudioEnabled(true)
//            WebRtcManager.MediaDirection.RECVONLY -> setAudioEnabled(false)
//            WebRtcManager.MediaDirection.INACTIVE -> setAudioEnabled(false)
//        }
//    }
//
//    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
//        return try {
//            setRemoteDescription(modifiedSdp, SdpType.ANSWER)
//            true
//        } catch (e: Exception) {
//            log.e(TAG) { "Failed to apply modified SDP: ${e.message}" }
//            false
//        }
//    }
//
//    override fun getLocalDescription(): String? {
//        return peerConnection?.localDescription?.description
//    }
//
//    override fun diagnoseAudioIssues(): String {
//        return buildString {
//            appendLine("=== AUDIO DIAGNOSIS ===")
//            appendLine("Initialized: $isInitialized")
//            appendLine("AudioManager Started: $isAudioManagerStarted")
//            appendLine("Muted: $isMuted")
//            appendLine("Audio Track Enabled: ${localAudioTrack?.enabled()}")
//            appendLine("Connection State: $currentConnectionState")
//            appendLine("Active Route: ${getActiveAudioRoute()}")
//            audioManager?.let { am ->
//                appendLine("Mode: ${am.mode}")
//                appendLine("Speakerphone: ${am.isSpeakerphoneOn}")
//                appendLine("Bluetooth SCO: ${am.isBluetoothScoOn}")
//                appendLine("Wired Headset: ${am.isWiredHeadsetOn}")
//                appendLine("Mic Mute: ${am.isMicrophoneMute}")
//            }
//            appendLine("\nAvailable Devices:")
//            val (inputs, outputs) = getAllAudioDevices()
//            appendLine("Inputs: ${inputs.size}")
//            inputs.forEach { appendLine("  - ${it.name}") }
//            appendLine("Outputs: ${outputs.size}")
//            outputs.forEach { appendLine("  - ${it.name}") }
//        }
//    }
//
//    // ==================== UTILITY METHOD ====================
//
//    suspend fun awaitInitialization(timeoutMs: Long = 5000): Boolean =
//        withContext(Dispatchers.Default) {
//            if (isInitialized) return@withContext true
//
//            val startTime = System.currentTimeMillis()
//            while (!isInitialized && (System.currentTimeMillis() - startTime) < timeoutMs) {
//                delay(100)
//            }
//
//            if (!isInitialized) {
//                log.e(TAG) { "❌ Initialization timeout after ${timeoutMs}ms" }
//            }
//
//            isInitialized
//        }
//}
//
///**
// * Simple SDP observer implementation
// */
//open class SimpleSdpObserver : SdpObserver {
//    override fun onCreateSuccess(sessionDescription: SessionDescription?) {
//        log.d("SimpleSdpObserver") { "SDP created successfully" }
//    }
//
//    override fun onSetSuccess() {
//        log.d("SimpleSdpObserver") { "SDP set successfully" }
//    }
//
//    override fun onCreateFailure(error: String?) {
//        log.e("SimpleSdpObserver") { "SDP creation failed: $error" }
//    }
//
//    override fun onSetFailure(error: String?) {
//        log.e("SimpleSdpObserver") { "SDP set failed: $error" }
//    }
//}