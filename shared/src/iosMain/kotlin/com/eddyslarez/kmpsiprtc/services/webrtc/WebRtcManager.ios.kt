package com.eddyslarez.kmpsiprtc.services.webrtc

import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.AudioUnit
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitCompatibilities
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitTypes
import com.eddyslarez.kmpsiprtc.data.models.DeviceConnectionState
import com.eddyslarez.kmpsiprtc.data.models.RecordingResult
import com.eddyslarez.kmpsiprtc.data.models.SdpType
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.audio.AudioCaptureCallback
import com.eddyslarez.kmpsiprtc.services.audio.AudioStreamListener
import com.eddyslarez.kmpsiprtc.services.audio.AudioTrackCapture
import com.eddyslarez.kmpsiprtc.services.audio.IosAudioController
import com.eddyslarez.kmpsiprtc.services.audio.createRemoteAudioCapture
import com.shepeliev.webrtckmp.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.AVFAudio.*
import platform.Foundation.NSNotificationCenter

actual fun createWebRtcManager(): WebRtcManager = IosWebRtcManager()

@OptIn(ExperimentalForeignApi::class)
class IosWebRtcManager : WebRtcManager {
    private val TAG = "IosWebRtcManager"
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Controllers
    private lateinit var peerConnectionController: IosPeerConnectionController
    private lateinit var audioController: IosAudioController

    // State
    private val _isInitialized = MutableStateFlow(false)
    private var webRtcEventListener: WebRtcEventListener? = null

    val isInitialized: Boolean get() = _isInitialized.value

    override fun initialize() {
        log.d(TAG) { "🔧 Initializing WebRTC Manager..." }

        if (_isInitialized.value) {
            log.d(TAG) { "Already initialized" }
            return
        }

        try {
            // Initialize Audio Controller
            audioController = IosAudioController(
                onDeviceChanged = { device ->
                    webRtcEventListener?.onAudioDeviceChanged(device)
                }
            )
            audioController.initialize()

            // Initialize PeerConnection Controller
            peerConnectionController = IosPeerConnectionController(
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

            _isInitialized.value = true
            log.d(TAG) { "✅✅✅ WebRTC initialized successfully ✅✅✅" }

        } catch (e: Exception) {
            log.e(TAG) { "💥 Error initializing WebRTC: ${e.message}" }
            e.printStackTrace()
            _isInitialized.value = false
        }
    }

    override fun isInitialized(): Boolean = _isInitialized.value

    // ==================== CONNECTION MANAGEMENT ====================

    override suspend fun createOffer(): String {
        log.d(TAG) { "📝 Creating offer..." }
        ensureInitialized()
        audioController.startForCall()
        return peerConnectionController.createOffer()
    }

    override suspend fun createAnswer(offerSdp: String): String {
        log.d(TAG) { "📝 Creating answer..." }
        ensureInitialized()
        audioController.startForCall()
        return peerConnectionController.createAnswer(offerSdp)
    }

    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
        ensureInitialized()
        peerConnectionController.setRemoteDescription(sdp, type)
    }

    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        peerConnectionController.addIceCandidate(candidate, sdpMid, sdpMLineIndex)
    }

    override fun closePeerConnection() {
        log.d(TAG) { "Closing peer connection" }
        if (::peerConnectionController.isInitialized) {
            peerConnectionController.closePeerConnection()
        }
        if (::audioController.isInitialized) {
            audioController.stop()
        }
    }

    override fun dispose() {
        log.d(TAG) { "Disposing WebRTC Manager" }

        try {
            if (::audioController.isInitialized) {
                audioController.stop()
                audioController.dispose()
            }

            if (::peerConnectionController.isInitialized) {
                peerConnectionController.dispose()
            }

            _isInitialized.value = false
            coroutineScope.cancel()

            log.d(TAG) { "✅ WebRTC Manager disposed successfully" }
        } catch (e: Exception) {
            log.e(TAG) { "Error during disposal: ${e.message}" }
            e.printStackTrace()
            _isInitialized.value = false
        }
    }

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
        if (!::peerConnectionController.isInitialized) return
        peerConnectionController.setAudioEnabled(enabled)
    }

    override fun setMuted(muted: Boolean) {
        if (!::peerConnectionController.isInitialized) return
        peerConnectionController.setMuted(muted)
    }

    override fun isMuted(): Boolean {
        return if (::peerConnectionController.isInitialized) {
            peerConnectionController.isMuted()
        } else {
            false
        }
    }

    override fun setActiveAudioRoute(audioUnitType: AudioUnitTypes): Boolean {
        return if (::audioController.isInitialized) {
            audioController.setActiveRoute(audioUnitType)
        } else {
            false
        }
    }

    override fun getActiveAudioRoute(): AudioUnitTypes? {
        return if (::audioController.isInitialized) {
            audioController.getActiveRoute()
        } else {
            null
        }
    }

    override fun getAvailableAudioRoutes(): Set<AudioUnitTypes> {
        return if (::audioController.isInitialized) {
            audioController.getAvailableRoutes()
        } else {
            emptySet()
        }
    }

    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        return if (::audioController.isInitialized) {
            audioController.getAllDevices()
        } else {
            Pair(emptyList(), emptyList())
        }
    }

    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        return if (::audioController.isInitialized) {
            audioController.changeOutputDevice(device)
        } else {
            false
        }
    }

    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
        return if (::audioController.isInitialized) {
            audioController.changeInputDevice(device)
        } else {
            false
        }
    }

    override fun getCurrentInputDevice(): AudioDevice? {
        return if (::audioController.isInitialized) {
            audioController.getCurrentInputDevice()
        } else {
            null
        }
    }

    override fun getCurrentOutputDevice(): AudioDevice? {
        return if (::audioController.isInitialized) {
            audioController.getCurrentOutputDevice()
        } else {
            null
        }
    }

    override fun getAvailableAudioUnits(): Set<AudioUnit> {
        return if (::audioController.isInitialized) {
            audioController.getAvailableAudioUnits()
        } else {
            emptySet()
        }
    }

    override fun getCurrentActiveAudioUnit(): AudioUnit? {
        return if (::audioController.isInitialized) {
            audioController.getCurrentActiveAudioUnit()
        } else {
            null
        }
    }

    override fun onBluetoothConnectionChanged(isConnected: Boolean) {
        if (::audioController.isInitialized) {
            audioController.refreshDevices()
        }
    }

    override fun refreshAudioDevicesWithBluetoothPriority() {
        if (::audioController.isInitialized) {
            audioController.refreshWithBluetoothPriority()
        }
    }

    override fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
        return setActiveAudioRoute(audioUnitType)
    }

    override fun prepareAudioForCall() {
        if (::audioController.isInitialized) {
            audioController.startForCall()
        }
    }

    override fun prepareAudioForIncomingCall() {
        prepareAudioForCall()
    }

    override fun diagnoseAudioIssues(): String {
        return buildString {
            appendLine("=== iOS AUDIO DIAGNOSIS ===")
            appendLine("Initialized: ${_isInitialized.value}")
            appendLine("Connection State: ${getConnectionState()}")

            if (::audioController.isInitialized) {
                appendLine("\n--- Audio Controller ---")
                appendLine(audioController.diagnose())
            }

            if (::peerConnectionController.isInitialized) {
                appendLine("\n--- PeerConnection Controller ---")
                appendLine(peerConnectionController.diagnose())
            }
        }
    }

    // ==================== RECORDING ====================

    override fun startCallRecording(callId: String) {
        if (!::peerConnectionController.isInitialized) return
        peerConnectionController.startRecording(callId)
    }

    override suspend fun stopCallRecording(): RecordingResult? {
        if (!::peerConnectionController.isInitialized) return null
        return peerConnectionController.stopRecording()
    }

    override fun isRecordingCall(): Boolean {
        if (!::peerConnectionController.isInitialized) return false
        return peerConnectionController.isRecording()
    }

    // ==================== STREAMING EN TIEMPO REAL ====================

    override fun setAudioStreamListener(listener: AudioStreamListener?) {
        if (!::peerConnectionController.isInitialized) return
        peerConnectionController.setAudioStreamListener(listener)
    }

    override fun startAudioStreaming(callId: String) {
        if (!::peerConnectionController.isInitialized) return
        peerConnectionController.startStreaming(callId)
    }

    override fun stopAudioStreaming() {
        if (!::peerConnectionController.isInitialized) return
        peerConnectionController.stopStreaming()
    }

    override fun isAudioStreaming(): Boolean {
        if (!::peerConnectionController.isInitialized) return false
        return peerConnectionController.isStreaming()
    }

    // ==================== DTMF & MEDIA DIRECTION ====================

    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
        return if (::peerConnectionController.isInitialized) {
            peerConnectionController.sendDtmf(tones, duration, gap)
        } else {
            false
        }
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
            ensureInitialized()
            peerConnectionController.setLocalDescriptionDirect(modifiedSdp)
            true
        } catch (e: Exception) {
            log.e(TAG) { "Failed to apply modified SDP: ${e.message}" }
            false
        }
    }

    // ==================== DEVICE SELECTION BY NAME (no-op on iOS) ====================

    override fun selectAudioInputDeviceByName(deviceName: String): Boolean = false
    override fun selectAudioOutputDeviceByName(deviceName: String): Boolean = false

    override fun setListener(listener: WebRtcEventListener?) {
        this.webRtcEventListener = listener
    }

    // ==================== PRIVATE HELPERS ====================

    private fun handleConnectionStateChange(state: WebRtcConnectionState) {
        when (state) {
            WebRtcConnectionState.CONNECTED -> {
                coroutineScope.launch(Dispatchers.Main) {
                    setAudioEnabled(true)
                }
            }
            WebRtcConnectionState.DISCONNECTED,
            WebRtcConnectionState.FAILED,
            WebRtcConnectionState.CLOSED -> {
                if (::audioController.isInitialized) {
                    audioController.stop()
                }
            }
            else -> {}
        }
        webRtcEventListener?.onConnectionStateChange(state)
    }

    private fun ensureInitialized() {
        if (!_isInitialized.value) {
            throw IllegalStateException("WebRTC not initialized")
        }
    }
}

//@OptIn(ExperimentalForeignApi::class)
//class IosWebRtcManager : WebRtcManager {
//    private val TAG = "IosWebRtcManager"
//    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
//
//    // Controllers
//    private lateinit var peerConnectionController: IosPeerConnectionController
//    private lateinit var audioController: IosAudioController
//
//    // State
//    private val _isInitialized = MutableStateFlow(false)
//    private var webRtcEventListener: WebRtcEventListener? = null
//
//    val isInitialized: Boolean get() = _isInitialized.value
//
//    override fun initialize() {
//        log.d(TAG) { "🔧 Initializing WebRTC Manager..." }
//
//        if (_isInitialized.value) {
//            log.d(TAG) { "Already initialized" }
//            return
//        }
//
//        try {
//            // Initialize Audio Controller
//            audioController = IosAudioController(
//                onDeviceChanged = { device ->
//                    webRtcEventListener?.onAudioDeviceChanged(device)
//                }
//            )
//            audioController.initialize()
//
//            // Initialize PeerConnection Controller
//            peerConnectionController = IosPeerConnectionController(
//                onIceCandidate = { candidate, sdpMid, sdpMLineIndex ->
//                    webRtcEventListener?.onIceCandidate(candidate, sdpMid, sdpMLineIndex)
//                },
//                onConnectionStateChange = { state ->
//                    handleConnectionStateChange(state)
//                },
//                onRemoteAudioTrack = {
//                    webRtcEventListener?.onRemoteAudioTrack()
//                }
//            )
//            peerConnectionController.initialize()
//
//            _isInitialized.value = true
//            log.d(TAG) { "✅✅✅ WebRTC initialized successfully ✅✅✅" }
//
//        } catch (e: Exception) {
//            log.e(TAG) { "💥 Error initializing WebRTC: ${e.message}" }
//            e.printStackTrace()
//            _isInitialized.value = false
//        }
//    }
//
//    override fun isInitialized(): Boolean = _isInitialized.value
//
//    // ==================== CONNECTION MANAGEMENT ====================
//
//    override suspend fun createOffer(): String {
//        log.d(TAG) { "📝 Creating offer..." }
//        ensureInitialized()
//        audioController.startForCall()
//        return peerConnectionController.createOffer()
//    }
//
//    override suspend fun createAnswer(offerSdp: String): String {
//        log.d(TAG) { "📝 Creating answer..." }
//        ensureInitialized()
//        audioController.startForCall()
//        return peerConnectionController.createAnswer(offerSdp)
//    }
//
//    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
//        ensureInitialized()
//        peerConnectionController.setRemoteDescription(sdp, type)
//    }
//
//    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
//        peerConnectionController.addIceCandidate(candidate, sdpMid, sdpMLineIndex)
//    }
//
//    override fun closePeerConnection() {
//        log.d(TAG) { "Closing peer connection" }
//        if (::peerConnectionController.isInitialized) {
//            peerConnectionController.closePeerConnection()
//        }
//        if (::audioController.isInitialized) {
//            audioController.stop()
//        }
//    }
//
//    override fun dispose() {
//        log.d(TAG) { "Disposing WebRTC Manager" }
//
//        try {
//            if (::audioController.isInitialized) {
//                audioController.stop()
//                audioController.dispose()
//            }
//
//            if (::peerConnectionController.isInitialized) {
//                peerConnectionController.dispose()
//            }
//
//            _isInitialized.value = false
//            coroutineScope.cancel()
//
//            log.d(TAG) { "✅ WebRTC Manager disposed successfully" }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error during disposal: ${e.message}" }
//            e.printStackTrace()
//            _isInitialized.value = false
//        }
//    }
//
//    override fun getConnectionState(): WebRtcConnectionState {
//        return if (::peerConnectionController.isInitialized) {
//            peerConnectionController.getConnectionState()
//        } else {
//            WebRtcConnectionState.DISCONNECTED
//        }
//    }
//
//    override fun getLocalDescription(): String? {
//        return if (::peerConnectionController.isInitialized) {
//            peerConnectionController.getLocalDescription()
//        } else {
//            null
//        }
//    }
//
//    // ==================== AUDIO MANAGEMENT ====================
//
//    override fun setAudioEnabled(enabled: Boolean) {
//        if (!::peerConnectionController.isInitialized) return
//        peerConnectionController.setAudioEnabled(enabled)
//    }
//
//    override fun setMuted(muted: Boolean) {
//        if (!::peerConnectionController.isInitialized) return
//        peerConnectionController.setMuted(muted)
//    }
//
//    override fun isMuted(): Boolean {
//        return if (::peerConnectionController.isInitialized) {
//            peerConnectionController.isMuted()
//        } else {
//            false
//        }
//    }
//
//    override fun setActiveAudioRoute(audioUnitType: AudioUnitTypes): Boolean {
//        return if (::audioController.isInitialized) {
//            audioController.setActiveRoute(audioUnitType)
//        } else {
//            false
//        }
//    }
//
//    override fun getActiveAudioRoute(): AudioUnitTypes? {
//        return if (::audioController.isInitialized) {
//            audioController.getActiveRoute()
//        } else {
//            null
//        }
//    }
//
//    override fun getAvailableAudioRoutes(): Set<AudioUnitTypes> {
//        return if (::audioController.isInitialized) {
//            audioController.getAvailableRoutes()
//        } else {
//            emptySet()
//        }
//    }
//
//    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
//        return if (::audioController.isInitialized) {
//            audioController.getAllDevices()
//        } else {
//            Pair(emptyList(), emptyList())
//        }
//    }
//
//    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
//        return if (::audioController.isInitialized) {
//            audioController.changeOutputDevice(device)
//        } else {
//            false
//        }
//    }
//
//    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
//        return if (::audioController.isInitialized) {
//            audioController.changeInputDevice(device)
//        } else {
//            false
//        }
//    }
//
//    override fun getCurrentInputDevice(): AudioDevice? {
//        return if (::audioController.isInitialized) {
//            audioController.getCurrentInputDevice()
//        } else {
//            null
//        }
//    }
//
//    override fun getCurrentOutputDevice(): AudioDevice? {
//        return if (::audioController.isInitialized) {
//            audioController.getCurrentOutputDevice()
//        } else {
//            null
//        }
//    }
//
//    override fun getAvailableAudioUnits(): Set<AudioUnit> {
//        return if (::audioController.isInitialized) {
//            audioController.getAvailableAudioUnits()
//        } else {
//            emptySet()
//        }
//    }
//
//    override fun getCurrentActiveAudioUnit(): AudioUnit? {
//        return if (::audioController.isInitialized) {
//            audioController.getCurrentActiveAudioUnit()
//        } else {
//            null
//        }
//    }
//
//    override fun onBluetoothConnectionChanged(isConnected: Boolean) {
//        if (::audioController.isInitialized) {
//            audioController.refreshDevices()
//        }
//    }
//
//    override fun refreshAudioDevicesWithBluetoothPriority() {
//        if (::audioController.isInitialized) {
//            audioController.refreshWithBluetoothPriority()
//        }
//    }
//
//    override fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
//        return setActiveAudioRoute(audioUnitType)
//    }
//
//    override fun prepareAudioForCall() {
//        if (::audioController.isInitialized) {
//            audioController.startForCall()
//        }
//    }
//
//    override fun prepareAudioForIncomingCall() {
//        prepareAudioForCall()
//    }
//
//    override fun diagnoseAudioIssues(): String {
//        return buildString {
//            appendLine("=== iOS AUDIO DIAGNOSIS ===")
//            appendLine("Initialized: ${_isInitialized.value}")
//            appendLine("Connection State: ${getConnectionState()}")
//
//            if (::audioController.isInitialized) {
//                appendLine("\n--- Audio Controller ---")
//                appendLine(audioController.diagnose())
//            }
//
//            if (::peerConnectionController.isInitialized) {
//                appendLine("\n--- PeerConnection Controller ---")
//                appendLine(peerConnectionController.diagnose())
//            }
//        }
//    }
//
//    // ==================== RECORDING ====================
//
//    override fun startCallRecording(callId: String) {
//        if (!::peerConnectionController.isInitialized) return
//        peerConnectionController.startRecording(callId)
//    }
//
//    override suspend fun stopCallRecording(): RecordingResult? {
//        if (!::peerConnectionController.isInitialized) return null
//        return peerConnectionController.stopRecording()
//    }
//
//    override fun isRecordingCall(): Boolean {
//        if (!::peerConnectionController.isInitialized) return false
//        return peerConnectionController.isRecording()
//    }
//
//    // ==================== DTMF & MEDIA DIRECTION ====================
//
//    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
//        return if (::peerConnectionController.isInitialized) {
//            peerConnectionController.sendDtmf(tones, duration, gap)
//        } else {
//            false
//        }
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
//            ensureInitialized()
//            peerConnectionController.setLocalDescriptionDirect(modifiedSdp)
//            true
//        } catch (e: Exception) {
//            log.e(TAG) { "Failed to apply modified SDP: ${e.message}" }
//            false
//        }
//    }
//
//    override fun setListener(listener: WebRtcEventListener?) {
//        this.webRtcEventListener = listener
//    }
//
//    // ==================== PRIVATE HELPERS ====================
//
//    private fun handleConnectionStateChange(state: WebRtcConnectionState) {
//        when (state) {
//            WebRtcConnectionState.CONNECTED -> {
//                coroutineScope.launch(Dispatchers.Main) {
//                    setAudioEnabled(true)
//                }
//            }
//            WebRtcConnectionState.DISCONNECTED,
//            WebRtcConnectionState.FAILED,
//            WebRtcConnectionState.CLOSED -> {
//                if (::audioController.isInitialized) {
//                    audioController.stop()
//                }
//            }
//            else -> {}
//        }
//        webRtcEventListener?.onConnectionStateChange(state)
//    }
//
//    private fun ensureInitialized() {
//        if (!_isInitialized.value) {
//            throw IllegalStateException("WebRTC not initialized")
//        }
//    }
//}
//
//class IosWebRtcManager : WebRtcManager {
//    private val TAG = "IosWebRtcManager"
//    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
//
//
//    private lateinit var peerConnectionController: IosPeerConnectionController
//
//    // State flows
//    private val _isInitialized = MutableStateFlow(false)
//    private val _availableAudioDevices = MutableStateFlow<Set<AudioDevice>>(emptySet())
//    private val _currentAudioDevice = MutableStateFlow<AudioDevice?>(null)
//    val isInitialized: Boolean
//        get() = _isInitialized.value
//
//    val availableAudioDevices: StateFlow<Set<AudioDevice>>
//        get() = _availableAudioDevices.asStateFlow()
//
//    val currentAudioDevice: StateFlow<AudioDevice?>
//        get() = _currentAudioDevice.asStateFlow()
//
//    // WebRTC components
//    private var peerConnection: PeerConnection? = null
//    private var localAudioTrack: AudioStreamTrack? = null
//    private var remoteAudioTrack: AudioStreamTrack? = null
//    private var webRtcEventListener: WebRtcEventListener? = null
//    private var dtmfSender: DtmfSender? = null
//
//    // Audio management
//    private var currentInputDevice: AudioDevice? = null
//    private var currentOutputDevice: AudioDevice? = null
//    private var isMuted = false
//    private var audioSessionConfigured = false
//    private var microphonePermissionGranted = false
//    private var audioTrackCreationRetries = 0
//    private val maxAudioTrackRetries = 3
//    private var remoteAudioCapture: AudioTrackCapture? = null
//
//    @OptIn(ExperimentalForeignApi::class)
//    override fun initialize() {
//        log.d(TAG) { "Initializing WebRTC Manager..." }
//
//        if (_isInitialized.value) {
//            log.d(TAG) { "Already initialized" }
//            return
//        }
//
//        try {
//            requestMicrophonePermission { granted ->
//                microphonePermissionGranted = granted
//                if (granted) {
//                    CoroutineScope(Dispatchers.Main).launch {
//                        configureAudioSession()
//                        initializePeerConnection()
//                        refreshAudioDevices()
//                        _isInitialized.value = true
//                        audioSessionConfigured = true
//                        log.d(TAG) { "WebRTC initialized successfully" }
//                    }
//                } else {
//                    log.e(TAG) { "Microphone permission denied" }
//                    _isInitialized.value = false
//                }
//            }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error initializing WebRTC: ${e.message}" }
//            _isInitialized.value = false
//        }
//    }
//
//    override fun dispose() {
//        log.d(TAG) { "Disposing WebRTC resources..." }
//        cleanupCall()
//        _isInitialized.value = false
//        _availableAudioDevices.value = emptySet()
//        _currentAudioDevice.value = null
//        coroutineScope.cancel()
//    }
//
//
//    override fun setActiveAudioRoute(audioUnitType: AudioUnitTypes): Boolean {
//        return applyAudioRouteChange(audioUnitType)
//    }
//
//    override fun getActiveAudioRoute(): AudioUnitTypes? {
//        return _currentAudioDevice.value?.audioUnit?.type
//    }
//
//    override fun getAvailableAudioRoutes(): Set<AudioUnitTypes> {
//        return _availableAudioDevices.value
//            .filter { it.isOutput }
//            .map { it.audioUnit.type }
//            .toSet()
//    }
//
//    override fun startCallRecording(callId: String) {
//    }
//
//    override suspend fun stopCallRecording(): RecordingResult? {
//        return null
//    }
//
//    override fun isRecordingCall(): Boolean {
//        return false
//    }
//
//
//    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
//        val allDevices = _availableAudioDevices.value
//        val inputDevices = allDevices.filter { !it.isOutput }
//        val outputDevices = allDevices.filter { it.isOutput }
//        return Pair(inputDevices, outputDevices)
//    }
//
//    @OptIn(ExperimentalForeignApi::class)
//    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
//        if (!_isInitialized.value) return false
//
//        try {
//            val audioSession = AVAudioSession.sharedInstance()
//
//            when (device.audioUnit.type) {
//                AudioUnitTypes.SPEAKER -> {
//                    audioSession.overrideOutputAudioPort(AVAudioSessionPortOverrideSpeaker, null)
//                }
//                AudioUnitTypes.EARPIECE, AudioUnitTypes.BLUETOOTH,
//                AudioUnitTypes.HEADSET, AudioUnitTypes.HEADPHONES -> {
//                    audioSession.overrideOutputAudioPort(AVAudioSessionPortOverrideNone, null)
//                }
//                else -> return false
//            }
//
//            currentOutputDevice = device
//            _currentAudioDevice.value = device
//            webRtcEventListener?.onAudioDeviceChanged(device)
//            log.d(TAG) { "Changed output device to: ${device.name}" }
//            return true
//        } catch (e: Exception) {
//            log.e(TAG) { "Error changing output device: ${e.message}" }
//            return false
//        }
//    }
//
//    @OptIn(ExperimentalForeignApi::class)
//    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
//        if (!_isInitialized.value) return false
//
//        try {
//            val audioSession = AVAudioSession.sharedInstance()
//
//            // iOS maneja automáticamente el dispositivo de entrada basado en lo conectado
//            // Solo necesitamos actualizar nuestro estado
//            currentInputDevice = device
//            log.d(TAG) { "Changed input device to: ${device.name}" }
//            return true
//        } catch (e: Exception) {
//            log.e(TAG) { "Error changing input device: ${e.message}" }
//            return false
//        }
//    }
//
//    override fun getCurrentInputDevice(): AudioDevice? {
//        return currentInputDevice ?: getCurrentInputDeviceInternal()
//    }
//
//    override fun getCurrentOutputDevice(): AudioDevice? {
//        return currentOutputDevice ?: getCurrentOutputDeviceInternal()
//    }
//
//    override fun prepareAudioForCall() {
//        log.d(TAG) { "Preparing audio for call..." }
//
//        if (!microphonePermissionGranted) {
//            requestMicrophonePermission { granted ->
//                if (granted) {
//                    microphonePermissionGranted = true
//                    continueAudioPreparation()
//                }
//            }
//            return
//        }
//
//        continueAudioPreparation()
//    }
//
//    override fun getConnectionState(): WebRtcConnectionState {
//        if (!isInitialized || peerConnection == null) {
//            return WebRtcConnectionState.NEW
//        }
//
//        val state = peerConnection?.connectionState ?: return WebRtcConnectionState.NEW
//        return mapConnectionState(state)
//    }
//
//    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
//        val peerConn = peerConnection ?: return
//        val localDesc = peerConn.localDescription ?: return
//
//        val modifiedSdp = modifySdpDirection(localDesc.sdp, direction)
//
//        val newDescription = SessionDescription(
//            type = localDesc.type,
//            sdp = modifiedSdp
//        )
//
//        peerConn.setLocalDescription(newDescription)
//        log.d(TAG) { "Media direction set to: $direction" }
//    }
//
//
//    override fun prepareAudioForIncomingCall() {
//        log.d(TAG) { "Preparing audio for incoming call..." }
//        prepareAudioForCall()
//    }
//
//    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
//        return try {
//            val peerConn = peerConnection ?: return false
//            val currentDesc = peerConn.localDescription ?: return false
//
//            val newDescription = SessionDescription(
//                type = currentDesc.type,
//                sdp = modifiedSdp
//            )
//
//            peerConn.setLocalDescription(newDescription)
//            log.d(TAG) { "Applied modified SDP" }
//            true
//        } catch (e: Exception) {
//            log.e(TAG) { "Error applying modified SDP: ${e.message}" }
//            false
//        }
//    }
//
//    override fun isInitialized(): Boolean {
//        return _isInitialized.value
//    }
//
//    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
//        if (!isInitialized || peerConnection == null) return false
//
//        return try {
//            val audioSender = peerConnection?.getSenders()?.find { sender ->
//                sender.track?.kind == MediaStreamTrackKind.Audio
//            }
//
//            val dtmfSender = audioSender?.dtmf ?: return false
//            dtmfSender.insertDtmf(tones, duration, gap)
//        } catch (e: Exception) {
//            false
//        }
//    }
//
//    override fun onBluetoothConnectionChanged(isConnected: Boolean) {
//        log.d(TAG) { "Bluetooth connection changed: $isConnected" }
//        refreshAudioDevices()
//
//        if (isConnected) {
//            val bluetoothDevices = _availableAudioDevices.value.filter {
//                it.audioUnit.type == AudioUnitTypes.BLUETOOTH
//            }
//            bluetoothDevices.firstOrNull()?.let { device ->
//                applyAudioRouteChange(AudioUnitTypes.BLUETOOTH)
//            }
//        }
//    }
//
//    override fun refreshAudioDevicesWithBluetoothPriority() {
//        log.d(TAG) { "Refreshing audio devices with Bluetooth priority..." }
//        refreshAudioDevices()
//
//        val bluetoothDevice = _availableAudioDevices.value.find {
//            it.audioUnit.type == AudioUnitTypes.BLUETOOTH
//        }
//
//        if (bluetoothDevice != null) {
//            applyAudioRouteChange(AudioUnitTypes.BLUETOOTH)
//        }
//    }
//
//    @OptIn(ExperimentalForeignApi::class)
//    override fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
//        if (!_isInitialized.value) return false
//
//        try {
//            val audioSession = AVAudioSession.sharedInstance()
//
//            when (audioUnitType) {
//                AudioUnitTypes.SPEAKER -> {
//                    audioSession.overrideOutputAudioPort(
//                        AVAudioSessionPortOverrideSpeaker,
//                        null
//                    )
//                    updateCurrentDevice(AudioUnitTypes.SPEAKER, isOutput = true)
//                }
//                AudioUnitTypes.EARPIECE -> {
//                    audioSession.overrideOutputAudioPort(
//                        AVAudioSessionPortOverrideNone,
//                        null
//                    )
//                    updateCurrentDevice(AudioUnitTypes.EARPIECE, isOutput = true)
//                }
//                AudioUnitTypes.BLUETOOTH -> {
//                    audioSession.overrideOutputAudioPort(
//                        AVAudioSessionPortOverrideNone,
//                        null
//                    )
//                    updateCurrentDevice(AudioUnitTypes.BLUETOOTH, isOutput = true)
//                }
//                AudioUnitTypes.HEADSET, AudioUnitTypes.HEADPHONES -> {
//                    audioSession.overrideOutputAudioPort(
//                        AVAudioSessionPortOverrideNone,
//                        null
//                    )
//                    updateCurrentDevice(audioUnitType, isOutput = true)
//                }
//                else -> return false
//            }
//
//            webRtcEventListener?.onAudioDeviceChanged(_currentAudioDevice.value)
//            return true
//        } catch (e: Exception) {
//            log.e(TAG) { "Error applying audio route: ${e.message}" }
//            return false
//        }
//    }
//
//    override fun getAvailableAudioUnits(): Set<AudioUnit> {
//        return _availableAudioDevices.value.map { it.audioUnit }.toSet()
//    }
//
//    override fun getCurrentActiveAudioUnit(): AudioUnit? {
//        return _currentAudioDevice.value?.audioUnit
//    }
//
//    override fun setAudioEnabled(enabled: Boolean) {
//        localAudioTrack?.enabled = enabled
//        log.d(TAG) { "Audio enabled: $enabled" }
//    }
//
//    override fun setMuted(muted: Boolean) {
//        isMuted = muted
//        localAudioTrack?.enabled = !muted
//        log.d(TAG) { "Muted: $muted" }
//    }
//
//    override fun isMuted(): Boolean {
//        return isMuted
//    }
//
//    override fun getLocalDescription(): String? {
//        return peerConnection?.localDescription?.sdp
//    }
//
//    override fun diagnoseAudioIssues(): String {
//        val diagnosis = StringBuilder()
//        diagnosis.append("=== Audio Diagnostics ===\n")
//        diagnosis.append("Initialized: ${_isInitialized.value}\n")
//        diagnosis.append("Microphone Permission: $microphonePermissionGranted\n")
//        diagnosis.append("Audio Session Configured: $audioSessionConfigured\n")
//        diagnosis.append("Local Audio Track: ${localAudioTrack != null}\n")
//        diagnosis.append("Local Track Enabled: ${localAudioTrack?.enabled}\n")
//        diagnosis.append("Remote Audio Track: ${remoteAudioTrack != null}\n")
//        diagnosis.append("Remote Track Enabled: ${remoteAudioTrack?.enabled}\n")
//        diagnosis.append("Is Muted: $isMuted\n")
//        diagnosis.append("Connection State: ${getConnectionState()}\n")
//        diagnosis.append("Available Devices: ${_availableAudioDevices.value.size}\n")
//        diagnosis.append("Current Output: ${currentOutputDevice?.name ?: "None"}\n")
//        diagnosis.append("Current Input: ${currentInputDevice?.name ?: "None"}\n")
//
//        return diagnosis.toString()
//    }
//
//    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
//        log.d(TAG) { "Adding ICE candidate" }
//
//        if (!isInitialized) {
//            initialize()
//            if (peerConnection == null) {
//                return
//            }
//        }
//
//        val peerConn = peerConnection ?: return
//
//        val iceCandidate = IceCandidate(
//            sdpMid = sdpMid ?: "",
//            sdpMLineIndex = sdpMLineIndex ?: 0,
//            candidate = candidate
//        )
//
//        peerConn.addIceCandidate(iceCandidate)
//    }
//
//    override fun closePeerConnection() {
//        log.d(TAG) { "Closing peer connection" }
//        cleanupCall()
//    }
//
//    override fun setListener(listener: WebRtcEventListener?) {
//        webRtcEventListener = listener
//        log.d(TAG) { "WebRTC event listener set" }
//    }
//
//    // ========== Private Helper Methods ==========
//
//    private fun continueAudioPreparation() {
//        try {
//            if (!configureAudioSession()) {
//                log.e(TAG) { "Failed to configure audio session" }
//                return
//            }
//
//            if (!_isInitialized.value) {
//                initialize()
//                CoroutineScope(Dispatchers.Main).launch {
//                    delay(500)
//                    setupAudioTrackForCall()
//                }
//            } else {
//                CoroutineScope(Dispatchers.Main).launch {
//                    setupAudioTrackForCall()
//                }
//            }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error in audio preparation: ${e.message}" }
//        }
//    }
//
//    private suspend fun setupAudioTrackForCall() {
//        try {
//            if (localAudioTrack == null) {
//                addLocalAudioTrack()
//                delay(500)
//            }
//            localAudioTrack?.enabled = true
//        } catch (e: Exception) {
//            log.e(TAG) { "Error setting up audio track: ${e.message}" }
//        }
//    }
//
//    private fun updateCurrentDevice(audioUnitType: AudioUnitTypes, isOutput: Boolean) {
//        val device = _availableAudioDevices.value.find {
//            it.audioUnit.type == audioUnitType && it.isOutput == isOutput
//        }
//        if (device != null) {
//            if (isOutput) {
//                currentOutputDevice = device
//            } else {
//                currentInputDevice = device
//            }
//            _currentAudioDevice.value = device
//        }
//    }
//
//    @OptIn(ExperimentalForeignApi::class)
//    private fun requestMicrophonePermission(callback: (Boolean) -> Unit) {
//        AVAudioSession.sharedInstance().requestRecordPermission { granted ->
//            callback(granted)
//        }
//    }
//
//    @OptIn(ExperimentalForeignApi::class)
//    private fun configureAudioSession(): Boolean {
//        try {
//            val audioSession = AVAudioSession.sharedInstance()
//
//            if (audioSession.category != AVAudioSessionCategoryPlayAndRecord) {
//                val success1 = audioSession.setCategory(
//                    AVAudioSessionCategoryPlayAndRecord,
//                    AVAudioSessionCategoryOptionAllowBluetooth or
//                            AVAudioSessionCategoryOptionDefaultToSpeaker,
//                    null
//                )
//                if (!success1) return false
//            }
//
//            val success2 = audioSession.setMode(AVAudioSessionModeVoiceChat, null)
//            if (!success2) return false
//
//            val success3 = audioSession.setActive(true, 0u, null)
//            if (!success3) return false
//
//            audioSessionConfigured = true
//            return true
//        } catch (e: Exception) {
//            log.e(TAG) { "Exception configuring audio session: ${e.message}" }
//            return false
//        }
//    }
//
//    private suspend fun initializePeerConnection() {
//        log.d(TAG) { "Initializing PeerConnection..." }
//        cleanupCall()
//
//        try {
//            val rtcConfig = RtcConfiguration(
//                iceServers = listOf(
//                    IceServer(
//                        urls = listOf(
//                            "stun:stun.l.google.com:19302",
//                            "stun:stun1.l.google.com:19302"
//                        )
//                    )
//                )
//            )
//
//            peerConnection = PeerConnection(rtcConfig).apply {
//                setupPeerConnectionObservers()
//            }
//
//            log.d(TAG) { "PeerConnection created successfully" }
//            addLocalAudioTrack()
//        } catch (e: Exception) {
//            log.e(TAG) { "Error initializing PeerConnection: ${e.message}" }
//            peerConnection = null
//        }
//    }
//
//    private fun PeerConnection.setupPeerConnectionObservers() {
//        onIceCandidate.onEach { candidate ->
//            log.d(TAG) { "New ICE Candidate" }
//            webRtcEventListener?.onIceCandidate(
//                candidate.candidate,
//                candidate.sdpMid,
//                candidate.sdpMLineIndex
//            )
//        }.launchIn(coroutineScope)
//
//        onConnectionStateChange.onEach { state ->
//            log.d(TAG) { "Connection state changed: $state" }
//            webRtcEventListener?.onConnectionStateChange(mapConnectionState(state))
//        }.launchIn(coroutineScope)
//
//        onTrack.onEach { event ->
//            log.d(TAG) { "Remote track received" }
//            val track = event.receiver.track
//            if (track is AudioStreamTrack) {
//                remoteAudioTrack = track
//                remoteAudioTrack?.enabled = true
//                webRtcEventListener?.onRemoteAudioTrack()
//            }
//        }.launchIn(coroutineScope)
//    }
//
//    private suspend fun addLocalAudioTrack() {
//        if (!microphonePermissionGranted) {
//            log.e(TAG) { "Cannot add audio track - no microphone permission" }
//            return
//        }
//
//        try {
//            val peerConn = peerConnection ?: return
//
//            if (localAudioTrack != null) return
//
//            log.d(TAG) { "Creating audio track" }
//
//            val mediaStream = MediaDevices.getUserMedia(audio = true, video = false)
//            val audioTrack = mediaStream.audioTracks.firstOrNull()
//
//            if (audioTrack != null) {
//                localAudioTrack = audioTrack
//                localAudioTrack?.enabled = true
//
//                val sender = peerConn.addTrack(audioTrack, mediaStream)
//                dtmfSender = sender.dtmf
//
//                audioTrackCreationRetries = 0
//                log.d(TAG) { "Audio track added successfully" }
//            } else {
//                handleAudioTrackCreationFailure()
//            }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error adding audio track: ${e.message}" }
//            handleAudioTrackCreationFailure()
//        }
//    }
//
//    private fun handleAudioTrackCreationFailure() {
//        audioTrackCreationRetries++
//        if (audioTrackCreationRetries < maxAudioTrackRetries) {
//            CoroutineScope(Dispatchers.IO).launch {
//                delay(1000)
//                addLocalAudioTrack()
//            }
//        } else {
//            log.e(TAG) { "Failed to create audio track after $maxAudioTrackRetries attempts" }
//        }
//    }
//
//    override suspend fun createOffer(): String {
//        if (!_isInitialized.value) {
//            initialize()
//            delay(1000)
//        }
//
//        if (!audioSessionConfigured) {
//            configureAudioSession()
//        }
//
//        val peerConn = peerConnection ?: run {
//            initializePeerConnection()
//            delay(500)
//            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
//        }
//
//        if (localAudioTrack == null) {
//            addLocalAudioTrack()
//            var attempts = 0
//            while (localAudioTrack == null && attempts < 10) {
//                delay(200)
//                attempts++
//            }
//            if (localAudioTrack == null) {
//                throw IllegalStateException("Cannot create offer without local audio track")
//            }
//        }
//
//        localAudioTrack?.enabled = true
//
//        val options = OfferAnswerOptions(
//            voiceActivityDetection = true,
//            iceRestart = false,
//            offerToReceiveAudio = true,
//            offerToReceiveVideo = false
//        )
//
//        val sessionDescription = peerConn.createOffer(options)
//
//        val modifiedSdp = ensureSendRecvInSdp(sessionDescription.sdp)
//        val correctedDescription = SessionDescription(
//            type = SessionDescriptionType.Offer,
//            sdp = modifiedSdp
//        )
//
//        peerConn.setLocalDescription(correctedDescription)
//
//        log.d(TAG) { "Created offer SDP" }
//        return modifiedSdp
//    }
//
//    override suspend fun createAnswer(offerSdp: String): String {
//        if (!_isInitialized.value) {
//            initialize()
//            delay(1000)
//        }
//
//        val peerConn = peerConnection ?: run {
//            initializePeerConnection()
//            delay(500)
//            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
//        }
//
//        val remoteOffer = SessionDescription(
//            type = SessionDescriptionType.Offer,
//            sdp = offerSdp
//        )
//        peerConn.setRemoteDescription(remoteOffer)
//
//        val options = OfferAnswerOptions(
//            voiceActivityDetection = true,
//            offerToReceiveAudio = true,
//            offerToReceiveVideo = false
//        )
//
//        val sessionDescription = peerConn.createAnswer(options)
//        peerConn.setLocalDescription(sessionDescription)
//        setupRemoteAudioCaptureIfNeeded()
//
//        log.d(TAG) { "Created answer SDP" }
//        return sessionDescription.sdp
//    }
//
//    private fun setupRemoteAudioCaptureIfNeeded() {
//        remoteAudioTrack?.let { track ->
//            setupRemoteAudioCapture(track)
//        }
//    }
//
//    private fun setupRemoteAudioCapture(track: AudioStreamTrack) {
//        try {
//            remoteAudioCapture?.stopCapture()
//
//            remoteAudioCapture = createRemoteAudioCapture(
//                remoteTrack = track,
//                callback = object : AudioCaptureCallback {
//                    override fun onAudioData(
//                        data: ByteArray,
//                        bitsPerSample: Int,
//                        sampleRate: Int,
//                        channels: Int,
//                        frames: Int,
//                        timestampMs: Long
//                    ) {
//                        // Enviar al recorder (cuando lo implementes para iOS)
//                        // callRecorder?.captureRemoteAudio(data)
//
//                        log.d(TAG) {
//                            "🔊 iOS Remote audio: ${data.size} bytes"
//                        }
//                    }
//                }
//            )
//
//            remoteAudioCapture?.startCapture()
//            log.d(TAG) { "✅ iOS remote audio capture configured" }
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error setting up iOS remote audio capture: ${e.message}" }
//        }
//    }
//    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
//        if (!_isInitialized.value) {
//            initialize()
//            delay(1000)
//        }
//
//        val peerConn = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")
//
//        val sdpType = when (type) {
//            SdpType.OFFER -> SessionDescriptionType.Offer
//            SdpType.ANSWER -> SessionDescriptionType.Answer
//        }
//
//
//        val sessionDescription = SessionDescription(
//            type = sdpType,
//            sdp = sdp
//        )
//
//        peerConn.setRemoteDescription(sessionDescription)
//    }
//
//    private fun ensureSendRecvInSdp(sdp: String): String {
//        val sdpLines = sdp.split("\r\n").toMutableList()
//        var hasAudioMedia = false
//        var audioLineIndex = -1
//
//        for (i in sdpLines.indices) {
//            if (sdpLines[i].startsWith("m=audio")) {
//                hasAudioMedia = true
//                audioLineIndex = i
//                break
//            }
//        }
//
//        if (hasAudioMedia) {
//            var foundDirection = false
//            for (i in (audioLineIndex + 1) until sdpLines.size) {
//                val line = sdpLines[i]
//                if (line.startsWith("m=")) break
//
//                when {
//                    line.startsWith("a=recvonly") || line.startsWith("a=sendonly") -> {
//                        sdpLines[i] = "a=sendrecv"
//                        foundDirection = true
//                        break
//                    }
//                    line.startsWith("a=sendrecv") -> {
//                        foundDirection = true
//                        break
//                    }
//                }
//            }
//
//            if (!foundDirection) {
//                sdpLines.add(audioLineIndex + 1, "a=sendrecv")
//            }
//        }
//
//        return sdpLines.joinToString("\r\n")
//    }
//
//    private fun modifySdpDirection(sdp: String, direction: WebRtcManager.MediaDirection): String {
//        val sdpLines = sdp.split("\r\n").toMutableList()
//        var audioLineIndex = -1
//
//        for (i in sdpLines.indices) {
//            if (sdpLines[i].startsWith("m=audio")) {
//                audioLineIndex = i
//                break
//            }
//        }
//
//        if (audioLineIndex != -1) {
//            val directionStr = when (direction) {
//                WebRtcManager.MediaDirection.SENDRECV -> "a=sendrecv"
//                WebRtcManager.MediaDirection.SENDONLY -> "a=sendonly"
//                WebRtcManager.MediaDirection.RECVONLY -> "a=recvonly"
//                WebRtcManager.MediaDirection.INACTIVE -> "a=inactive"
//            }
//
//            var foundDirection = false
//            for (i in (audioLineIndex + 1) until sdpLines.size) {
//                val line = sdpLines[i]
//                if (line.startsWith("m=")) break
//
//                if (line.startsWith("a=sendrecv") || line.startsWith("a=sendonly") ||
//                    line.startsWith("a=recvonly") || line.startsWith("a=inactive")) {
//                    sdpLines[i] = directionStr
//                    foundDirection = true
//                    break
//                }
//            }
//
//            if (!foundDirection) {
//                sdpLines.add(audioLineIndex + 1, directionStr)
//            }
//        }
//
//        return sdpLines.joinToString("\r\n")
//    }
//
//    @OptIn(ExperimentalForeignApi::class)
//    private fun refreshAudioDevices() {
//        try {
//            val devices = getAllAudioDevicesInternal()
//            _availableAudioDevices.value = devices
//
//            val currentOutput = getCurrentOutputDeviceInternal()
//            val currentInput = getCurrentInputDeviceInternal()
//            _currentAudioDevice.value = currentOutput ?: currentInput
//        } catch (e: Exception) {
//            log.e(TAG) { "Error refreshing audio devices: ${e.message}" }
//        }
//    }
//
//    @OptIn(ExperimentalForeignApi::class)
//    private fun getAllAudioDevicesInternal(): Set<AudioDevice> {
//        val devices = mutableSetOf<AudioDevice>()
//
//        try {
//            val audioSession = AVAudioSession.sharedInstance()
//            val currentRoute = audioSession.currentRoute
//            val currentOutputPort = currentRoute.outputs.firstOrNull()?.let {
//                (it as AVAudioSessionPortDescription).portType
//            }
//
//            // Built-in devices
//            AVAudioSessionPortBuiltInMic?.let { portType ->
//                devices.add(createAudioDevice(
//                    name = "Built-in Microphone",
//                    portType = portType,
//                    audioUnitType = AudioUnitTypes.MICROPHONE,
//                    isOutput = false,
//                    isCurrent = false
//                ))
//            }
//
//            AVAudioSessionPortBuiltInReceiver?.let { portType ->
//                devices.add(createAudioDevice(
//                    name = "iPhone",
//                    portType = portType,
//                    audioUnitType = AudioUnitTypes.EARPIECE,
//                    isOutput = true,
//                    isCurrent = currentOutputPort == portType
//                ))
//            }
//
//            AVAudioSessionPortBuiltInSpeaker?.let { portType ->
//                devices.add(createAudioDevice(
//                    name = "Speaker",
//                    portType = portType,
//                    audioUnitType = AudioUnitTypes.SPEAKER,
//                    isOutput = true,
//                    isCurrent = currentOutputPort == portType
//                ))
//            }
//
//            // External devices
//            audioSession.availableInputs?.forEach { input ->
//                val port = input as AVAudioSessionPortDescription
//                val portType = port.portType ?: return@forEach
//                val portName = port.portName ?: "Unknown Device"
//
//                when (portType) {
//                    AVAudioSessionPortBluetoothHFP -> {
//                        devices.add(createAudioDevice(
//                            name = portName,
//                            portType = portType,
//                            audioUnitType = AudioUnitTypes.BLUETOOTH,
//                            isOutput = true,
//                            isCurrent = currentOutputPort == portType,
//                            nativeDevice = port
//                        ))
//                    }
//                    AVAudioSessionPortHeadphones, AVAudioSessionPortHeadsetMic -> {
//                        devices.add(createAudioDevice(
//                            name = portName,
//                            portType = portType,
//                            audioUnitType = AudioUnitTypes.HEADSET,
//                            isOutput = portType == AVAudioSessionPortHeadphones,
//                            isCurrent = currentOutputPort == portType,
//                            nativeDevice = port
//                        ))
//                    }
//                }
//            }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error getting audio devices: ${e.message}" }
//        }
//
//        return devices
//    }
//
//    private fun createAudioDevice(
//        name: String,
//        portType: String,
//        audioUnitType: AudioUnitTypes,
//        isOutput: Boolean,
//        isCurrent: Boolean,
//        nativeDevice: Any? = null
//    ): AudioDevice {
//        val capability = if (isOutput) AudioUnitCompatibilities.PLAY else AudioUnitCompatibilities.RECORD
//
//        return AudioDevice(
//            name = name,
//            descriptor = portType,
//            nativeDevice = nativeDevice,
//            isOutput = isOutput,
//            audioUnit = AudioUnit(
//                type = audioUnitType,
//                capability = capability,
//                isCurrent = isCurrent,
//                isDefault = portType == AVAudioSessionPortBuiltInReceiver ||
//                        portType == AVAudioSessionPortBuiltInMic
//            ),
//            connectionState = DeviceConnectionState.AVAILABLE
//        )
//    }
//
//    @OptIn(ExperimentalForeignApi::class)
//    private fun getCurrentOutputDeviceInternal(): AudioDevice? {
//        try {
//            val audioSession = AVAudioSession.sharedInstance()
//            val currentOutput = audioSession.currentRoute.outputs.firstOrNull() as? AVAudioSessionPortDescription
//
//            return currentOutput?.portType?.let { portType ->
//                _availableAudioDevices.value.find {
//                    it.descriptor == portType && it.isOutput
//                }
//            }
//        } catch (e: Exception) {
//            return null
//        }
//    }
//
//    @OptIn(ExperimentalForeignApi::class)
//    private fun getCurrentInputDeviceInternal(): AudioDevice? {
//        try {
//            val audioSession = AVAudioSession.sharedInstance()
//            val currentInput = audioSession.currentRoute.inputs.firstOrNull() as? AVAudioSessionPortDescription
//
//            return currentInput?.portType?.let { portType ->
//                _availableAudioDevices.value.find {
//                    it.descriptor == portType && !it.isOutput
//                }
//            }
//        } catch (e: Exception) {
//            return null
//        }
//    }
//
//    private fun cleanupCall() {
//        try {
//            localAudioTrack?.enabled = false
//            peerConnection?.close()
//            peerConnection = null
//            localAudioTrack = null
//            remoteAudioTrack = null
//            dtmfSender = null
//        } catch (e: Exception) {
//            log.e(TAG) { "Error in cleanupCall: ${e.message}" }
//        }
//    }
//
//    private fun mapConnectionState(state: PeerConnectionState): WebRtcConnectionState {
//        return when (state) {
//            PeerConnectionState.New -> WebRtcConnectionState.NEW
//            PeerConnectionState.Connecting -> WebRtcConnectionState.CONNECTING
//            PeerConnectionState.Connected -> WebRtcConnectionState.CONNECTED
//            PeerConnectionState.Disconnected -> WebRtcConnectionState.DISCONNECTED
//            PeerConnectionState.Failed -> WebRtcConnectionState.FAILED
//            PeerConnectionState.Closed -> WebRtcConnectionState.CLOSED
//        }
//    }
//}