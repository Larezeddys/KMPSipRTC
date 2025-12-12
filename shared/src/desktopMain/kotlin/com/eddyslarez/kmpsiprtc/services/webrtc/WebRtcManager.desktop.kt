package com.eddyslarez.kmpsiprtc.services.webrtc
import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.AudioUnit
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitCompatibilities
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitTypes
import com.eddyslarez.kmpsiprtc.data.models.DeviceConnectionState
import com.eddyslarez.kmpsiprtc.data.models.RecordingResult
import com.eddyslarez.kmpsiprtc.data.models.SdpType
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.audio.DesktopAudioController
import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioLayer
import dev.onvoid.webrtc.media.audio.AudioOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

actual fun createWebRtcManager(): WebRtcManager = DesktopWebRtcManager()


class DesktopWebRtcManager : WebRtcManager {
    private val TAG = "DesktopWebRtcManager"
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Controllers
    private lateinit var peerConnectionController: DesktopPeerConnectionController
    private lateinit var audioController: DesktopAudioController

    // State
    private val _isInitialized = MutableStateFlow(false)
    private var webRtcEventListener: WebRtcEventListener? = null

    // ==================== INITIALIZATION ====================

    override fun initialize() {
        log.d(TAG) { "🔧 Initializing WebRTC Manager..." }

        if (_isInitialized.value) {
            log.d(TAG) { "Already initialized" }
            return
        }

        try {
            // Initialize PeerConnection Controller
            peerConnectionController = DesktopPeerConnectionController(
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

            // Initialize Audio Controller
            audioController = DesktopAudioController(
                audioDeviceModule = null, // Will be provided by PeerConnectionController if needed
                onDeviceChanged = { device ->
                    webRtcEventListener?.onAudioDeviceChanged(device)
                }
            )
            audioController.initialize()

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

        if (!peerConnectionController.hasPeerConnection()) {
            peerConnectionController.createNewPeerConnection()
        }

        audioController.startForCall()
        return peerConnectionController.createOffer()
    }

    override suspend fun createAnswer(offerSdp: String): String {
        log.d(TAG) { "📝 Creating answer..." }
        ensureInitialized()

        if (!peerConnectionController.hasPeerConnection()) {
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
            appendLine("=== DESKTOP AUDIO DIAGNOSIS ===")
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
//class DesktopWebRtcManager : WebRtcManager {
//    private var peerConnectionFactory: PeerConnectionFactory? = null
//    private var peerConnection: RTCPeerConnection? = null
//    private var audioDeviceModule: AudioDeviceModule? = null
//    private var listener: WebRtcEventListener? = null
//    private var localAudioTrack: dev.onvoid.webrtc.media.audio.AudioTrack? = null
//    private var dtmfSender: RTCDtmfSender? = null
//    private var isMutedState = false
//
//    companion object {
//        private const val TAG = "WebRtcManager"
//    }
//
//    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
//
//    private val _availableAudioDevices = MutableStateFlow<Set<AudioDevice>>(emptySet())
//    val availableAudioDevices: StateFlow<Set<AudioDevice>> = _availableAudioDevices.asStateFlow()
//
//    private val _currentAudioDevice = MutableStateFlow<AudioDevice?>(null)
//    val currentAudioDevice: StateFlow<AudioDevice?> = _currentAudioDevice.asStateFlow()
//
//    // ✅ CAMBIO: Eliminar la función duplicada, mantener solo la propiedad
//    private var _isInitialized: Boolean = false
//
//
//    override fun initialize() {
//        if (_isInitialized) return
//
//        try {
//            audioDeviceModule = AudioDeviceModule(AudioLayer.kPlatformDefaultAudio)
//            peerConnectionFactory = PeerConnectionFactory(audioDeviceModule, null)
//
//            _isInitialized = true
//            refreshAudioDevices()
//        } catch (e: Exception) {
//            e.printStackTrace()
//        }
//    }
//
//    override fun dispose() {
//        closePeerConnection()
//        localAudioTrack?.dispose()
//        localAudioTrack = null
//        audioDeviceModule?.dispose()
//        audioDeviceModule = null
//        peerConnectionFactory?.dispose()
//        peerConnectionFactory = null
//        _isInitialized = false
//        scope.cancel()
//    }
//
//    override suspend fun createOffer(): String = suspendCoroutine { continuation ->
//        scope.launch {
//            try {
//                val pc = getOrCreatePeerConnection { error ->
//                    continuation.resumeWithException(Exception(error))
//                } ?: run {
//                    continuation.resumeWithException(Exception("Failed to create PeerConnection"))
//                    return@launch
//                }
//
//                // Agregar audio track si no existe
//                if (localAudioTrack == null) {
//                    addLocalAudioTrack(pc)
//                }
//
//                val options = RTCOfferOptions()
//                pc.createOffer(options, object : CreateSessionDescriptionObserver {
//                    override fun onSuccess(description: RTCSessionDescription) {
//                        pc.setLocalDescription(description, object : SetSessionDescriptionObserver {
//                            override fun onSuccess() {
//                                continuation.resume(description.sdp)
//                            }
//
//                            override fun onFailure(error: String) {
//                                continuation.resumeWithException(Exception(error))
//                            }
//                        })
//                    }
//
//                    override fun onFailure(error: String) {
//                        continuation.resumeWithException(Exception(error))
//                    }
//                })
//            } catch (e: Exception) {
//                continuation.resumeWithException(e)
//            }
//        }
//    }
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
//    override suspend fun createAnswer(offerSdp: String): String = suspendCoroutine { continuation ->
//        scope.launch {
//            try {
//                // ✅ CRÍTICO: Primero crear/obtener PeerConnection
//                val pc = getOrCreatePeerConnection { error ->
//                    continuation.resumeWithException(Exception(error))
//                } ?: run {
//                    continuation.resumeWithException(Exception("Failed to create PeerConnection"))
//                    return@launch
//                }
//
//                // ✅ NUEVO: Configurar el remote SDP ANTES de crear la respuesta
//                val sdpType = RTCSdpType.OFFER
//                val sessionDescription = RTCSessionDescription(sdpType, offerSdp)
//
//                // Establecer remote description primero
//                pc.setRemoteDescription(sessionDescription, object : SetSessionDescriptionObserver {
//                    override fun onSuccess() {
//                        // Ahora crear el answer
//                        val options = RTCAnswerOptions()
//                        pc.createAnswer(options, object : CreateSessionDescriptionObserver {
//                            override fun onSuccess(description: RTCSessionDescription) {
//                                pc.setLocalDescription(description, object : SetSessionDescriptionObserver {
//                                    override fun onSuccess() {
//                                        continuation.resume(description.sdp)
//                                    }
//
//                                    override fun onFailure(error: String) {
//                                        continuation.resumeWithException(Exception(error))
//                                    }
//                                })
//                            }
//
//                            override fun onFailure(error: String) {
//                                continuation.resumeWithException(Exception(error))
//                            }
//                        })
//                    }
//
//                    override fun onFailure(error: String) {
//                        continuation.resumeWithException(Exception("Failed to set remote description: $error"))
//                    }
//                })
//
//            } catch (e: Exception) {
//                continuation.resumeWithException(e)
//            }
//        }
//    }
//
//    override suspend fun setRemoteDescription(sdp: String, type: SdpType) = suspendCoroutine<Unit> { continuation ->
//        scope.launch {
//            try {
//                val pc = getOrCreatePeerConnection { error ->
//                    continuation.resumeWithException(Exception(error))
//                } ?: run {
//                    continuation.resumeWithException(Exception("Failed to create PeerConnection"))
//                    return@launch
//                }
//
//                val sdpType = when (type) {
//                    SdpType.OFFER -> RTCSdpType.OFFER
//                    SdpType.ANSWER -> RTCSdpType.ANSWER
//                }
//
//                val sessionDescription = RTCSessionDescription(sdpType, sdp)
//                pc.setRemoteDescription(sessionDescription, object : SetSessionDescriptionObserver {
//                    override fun onSuccess() {
//                        continuation.resume(Unit)
//                    }
//
//                    override fun onFailure(error: String) {
//                        continuation.resumeWithException(Exception(error))
//                    }
//                })
//            } catch (e: Exception) {
//                continuation.resumeWithException(e)
//            }
//        }
//    }
//
//    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
//        try {
//            val iceCandidate = RTCIceCandidate(
//                sdpMid ?: "",
//                sdpMLineIndex ?: 0,
//                candidate
//            )
//            peerConnection?.addIceCandidate(iceCandidate)
//        } catch (e: Exception) {
//            e.printStackTrace()
//        }
//    }
//
//    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
//        val allDevices = _availableAudioDevices.value
//        val inputDevices = allDevices.filter { !it.isOutput }
//        val outputDevices = allDevices.filter { it.isOutput }
//        return Pair(inputDevices, outputDevices)
//    }
//
//    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
//        val adm = audioDeviceModule ?: return false
//
//        return try {
//            val nativeDevice = device.nativeDevice as? dev.onvoid.webrtc.media.audio.AudioDevice
//            nativeDevice?.let {
//                adm.setPlayoutDevice(it)
//                _currentAudioDevice.value = device.copy(
//                    audioUnit = device.audioUnit.copy(isCurrent = true),
//                    connectionState = DeviceConnectionState.CONNECTED
//                )
//                listener?.onAudioDeviceChanged(_currentAudioDevice.value)
//                true
//            } ?: false
//        } catch (e: Exception) {
//            e.printStackTrace()
//            false
//        }
//    }
//
//    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
//        val adm = audioDeviceModule ?: return false
//
//        return try {
//            val nativeDevice = device.nativeDevice as? dev.onvoid.webrtc.media.audio.AudioDevice
//            nativeDevice?.let {
//                adm.setRecordingDevice(it)
//                _currentAudioDevice.value = device.copy(
//                    audioUnit = device.audioUnit.copy(isCurrent = true),
//                    connectionState = DeviceConnectionState.CONNECTED
//                )
//                listener?.onAudioDeviceChanged(_currentAudioDevice.value)
//                true
//            } ?: false
//        } catch (e: Exception) {
//            e.printStackTrace()
//            false
//        }
//    }
//
//    override fun getCurrentInputDevice(): AudioDevice? {
//        return _availableAudioDevices.value.firstOrNull {
//            !it.isOutput && it.audioUnit.isCurrent
//        }
//    }
//
//    override fun getCurrentOutputDevice(): AudioDevice? {
//        return _availableAudioDevices.value.firstOrNull {
//            it.isOutput && it.audioUnit.isCurrent
//        }
//    }
//
//    override fun prepareAudioForCall() {
//        try {
//            log.d(tag = TAG) { "🎤 Preparing audio for incoming call..." }
//
//            // Asegurar que el AudioDeviceModule está listo
//            if (audioDeviceModule == null) {
//                log.w(tag = TAG) { "⚠️ AudioDeviceModule is null, creating..." }
//                audioDeviceModule = AudioDeviceModule(AudioLayer.kPlatformDefaultAudio)
//            }
//
//            audioDeviceModule?.initRecording()
//            audioDeviceModule?.initPlayout()
//
//            // ✅ IMPORTANTE: Crear PeerConnection aquí si no existe
//            if (peerConnection == null) {
//                log.d(tag = TAG) { "📞 Creating PeerConnection for incoming call..." }
//                getOrCreatePeerConnection { error ->
//                    log.e(tag = TAG) { "❌ Failed to create PeerConnection: $error" }
//                }
//            }
//
//            refreshAudioDevices()
//            log.d(tag = TAG) { "✅ Audio prepared successfully" }
//
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "❌ Error preparing audio: ${e.message}" }
//            e.printStackTrace()
//        }
//    }
//
//    override fun getConnectionState(): WebRtcConnectionState {
//        return peerConnection?.let { pc ->
//            when (pc.connectionState) {
//                RTCPeerConnectionState.NEW -> WebRtcConnectionState.NEW
//                RTCPeerConnectionState.CONNECTING -> WebRtcConnectionState.CONNECTING
//                RTCPeerConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
//                RTCPeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
//                RTCPeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
//                RTCPeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
//                else -> WebRtcConnectionState.NEW
//            }
//        } ?: WebRtcConnectionState.NEW
//    }
//
//    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
//        val pc = peerConnection ?: return
//        val localDesc = pc.localDescription ?: return
//
//        val modifiedSdp = modifySdpDirection(localDesc.sdp, direction)
//        val newDescription = RTCSessionDescription(localDesc.sdpType, modifiedSdp)
//
//        suspendCoroutine<Unit> { continuation ->
//            pc.setLocalDescription(newDescription, object : SetSessionDescriptionObserver {
//                override fun onSuccess() {
//                    continuation.resume(Unit)
//                }
//
//                override fun onFailure(error: String) {
//                    continuation.resumeWithException(Exception(error))
//                }
//            })
//        }
//    }
//
//    override fun setListener(listener: WebRtcEventListener?) {
//        this.listener = listener
//    }
//
//    override fun prepareAudioForIncomingCall() {
//        prepareAudioForCall()
//    }
//
//    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
//        return try {
//            val pc = peerConnection ?: return false
//            val currentDesc = pc.localDescription ?: return false
//
//            val newDescription = RTCSessionDescription(currentDesc.sdpType, modifiedSdp)
//
//            suspendCoroutine { continuation ->
//                pc.setLocalDescription(newDescription, object : SetSessionDescriptionObserver {
//                    override fun onSuccess() {
//                        continuation.resume(true)
//                    }
//
//                    override fun onFailure(error: String) {
//                        continuation.resume(false)
//                    }
//                })
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            false
//        }
//    }
//
//    // ✅ CAMBIO: Implementación de la interfaz usando la propiedad
//    override fun isInitialized(): Boolean {
//        return _isInitialized
//    }
//
//    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
//        try {
//            val sender = dtmfSender ?: return false
//
//            // Validar los tonos
//            val validTones = tones.filter {
//                it.isDigit() || it in "ABCD*#"
//            }
//
//            if (validTones.isEmpty()) {
//                return false
//            }
//
//            // Enviar tonos
//            sender.insertDtmf(validTones, duration, gap)
//            return true
//        } catch (e: Exception) {
//            e.printStackTrace()
//            return false
//        }
//    }
//
//    override fun onBluetoothConnectionChanged(isConnected: Boolean) {
//        refreshAudioDevices()
//    }
//
//    override fun refreshAudioDevicesWithBluetoothPriority() {
//        refreshAudioDevices()
//    }
//
//    override fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
//        val adm = audioDeviceModule ?: return false
//
//        return try {
//            val playbackDevices = adm.playoutDevices
//            val device = playbackDevices.firstOrNull {
//                it.name.contains(audioUnitType.name, ignoreCase = true)
//            } ?: playbackDevices.firstOrNull()
//
//            device?.let {
//                adm.setPlayoutDevice(it)
//                refreshAudioDevices()
//                true
//            } ?: false
//        } catch (e: Exception) {
//            e.printStackTrace()
//            false
//        }
//    }
//
//    override fun getAvailableAudioUnits(): Set<AudioUnit> {
//        return _availableAudioDevices.value.map { device ->
//            device.audioUnit.copy(
//                isCurrent = device.connectionState == DeviceConnectionState.CONNECTED,
//                isDefault = device.audioUnit.isDefault
//            )
//        }.toSet()
//    }
//
//    override fun getCurrentActiveAudioUnit(): AudioUnit? {
//        return _currentAudioDevice.value?.audioUnit?.copy(isCurrent = true)
//    }
//
//    override fun setAudioEnabled(enabled: Boolean) {
//        localAudioTrack?.setEnabled(enabled)
//    }
//
//    override fun setMuted(muted: Boolean) {
//        isMutedState = muted
//        localAudioTrack?.setEnabled(!muted)
//    }
//
//    override fun isMuted(): Boolean {
//        return isMutedState
//    }
//
//    override fun getLocalDescription(): String? {
//        return peerConnection?.localDescription?.sdp
//    }
//
//    override fun diagnoseAudioIssues(): String {
//        val diagnosis = StringBuilder()
//        diagnosis.append("=== Desktop Audio Diagnostics ===\n")
//        diagnosis.append("Initialized: $_isInitialized\n")
//        diagnosis.append("PeerConnection: ${peerConnection != null}\n")
//        diagnosis.append("Local Audio Track: ${localAudioTrack != null}\n")
//        diagnosis.append("Audio Device Module: ${audioDeviceModule != null}\n")
//        diagnosis.append("Is Muted: $isMutedState\n")
//        diagnosis.append("Connection State: ${getConnectionState()}\n")
//        diagnosis.append("Available Devices: ${_availableAudioDevices.value.size}\n")
//        diagnosis.append("Current Device: ${_currentAudioDevice.value?.name ?: "None"}\n")
//
//        audioDeviceModule?.let { adm ->
//            diagnosis.append("Recording Devices: ${adm.recordingDevices.size}\n")
//            diagnosis.append("Playout Devices: ${adm.playoutDevices.size}\n")
//        }
//
//        return diagnosis.toString()
//    }
//
//    override fun closePeerConnection() {
//        peerConnection?.close()
//        peerConnection = null
//        localAudioTrack?.dispose()
//        localAudioTrack = null
//        dtmfSender = null
//    }
//
//    // ========== Private Helper Methods ==========
//
//    private fun addLocalAudioTrack(pc: RTCPeerConnection) {
//        try {
//            val factory = peerConnectionFactory ?: return
//            val audioOptions = AudioOptions().apply {
//                noiseSuppression = true
//                autoGainControl = true
//                highpassFilter = true
//            }
//            val audioSource = factory.createAudioSource(audioOptions)
//            localAudioTrack = factory.createAudioTrack("audio0", audioSource)
//
//            val sender = pc.addTrack(localAudioTrack, listOf("stream0"))
//            dtmfSender = sender.dtmfSender
//
//        } catch (e: Exception) {
//            e.printStackTrace()
//        }
//    }
//
//    private fun refreshAudioDevices() {
//        val adm = audioDeviceModule ?: return
//        val devices = mutableSetOf<AudioDevice>()
//
//        try {
//            // Dispositivos de grabación
//            val recordingDevices = adm.recordingDevices
//            recordingDevices.forEach { device ->
//                val audioUnit = AudioUnit(
//                    type = determineDeviceType(device.name),
//                    capability = AudioUnitCompatibilities.RECORD,
//                    isCurrent = false,
//                    isDefault = false
//                )
//
//                devices.add(
//                    AudioDevice(
//                        name = device.name,
//                        descriptor = device.name,
//                        nativeDevice = device,
//                        isOutput = false,
//                        audioUnit = audioUnit
//                    )
//                )
//            }
//
//            // Dispositivos de reproducción
//            val playoutDevices = adm.playoutDevices
//            playoutDevices.forEach { device ->
//                val audioUnit = AudioUnit(
//                    type = determineDeviceType(device.name),
//                    capability = AudioUnitCompatibilities.PLAY,
//                    isCurrent = false,
//                    isDefault = false
//                )
//
//                devices.add(
//                    AudioDevice(
//                        name = device.name,
//                        descriptor = device.name,
//                        nativeDevice = device,
//                        isOutput = true,
//                        audioUnit = audioUnit
//                    )
//                )
//            }
//
//            _availableAudioDevices.value = devices
//
//            val currentOutput = devices.firstOrNull { it.isOutput }
//            val current = currentOutput?.copy(
//                audioUnit = currentOutput.audioUnit.copy(isCurrent = true, isDefault = true),
//                connectionState = DeviceConnectionState.CONNECTED
//            )
//
//            _currentAudioDevice.value = current
//            listener?.onAudioDeviceChanged(current)
//
//        } catch (e: Exception) {
//            e.printStackTrace()
//        }
//    }
//
//    private fun determineDeviceType(deviceName: String): AudioUnitTypes {
//        return when {
//            deviceName.contains("bluetooth", ignoreCase = true) -> AudioUnitTypes.BLUETOOTH
//            deviceName.contains("headset", ignoreCase = true) ||
//                    deviceName.contains("headphone", ignoreCase = true) -> AudioUnitTypes.HEADSET
//            deviceName.contains("speaker", ignoreCase = true) -> AudioUnitTypes.SPEAKER
//            else -> AudioUnitTypes.EARPIECE
//        }
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
//    private fun getOrCreatePeerConnection(onError: (String) -> Unit): RTCPeerConnection? {
//        if (peerConnection != null) return peerConnection
//
//        val factory = peerConnectionFactory ?: run {
//            onError("PeerConnectionFactory not initialized")
//            return null
//        }
//
//        val localIceServers = listOf(
//            RTCIceServer().apply {
//                urls = listOf(
//                    "stun:stun.l.google.com:19302",
//                    "stun:stun1.l.google.com:19302"
//                )
//            }
//        )
//
//        val rtcConfig = RTCConfiguration().apply {
//            iceServers.addAll(localIceServers)
//            bundlePolicy = RTCBundlePolicy.BALANCED
//            rtcpMuxPolicy = RTCRtcpMuxPolicy.REQUIRE
//        }
//
//        try {
//            peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnectionObserver {
//                override fun onIceCandidate(candidate: RTCIceCandidate) {
//                    listener?.onIceCandidate(
//                        candidate.sdp,
//                        candidate.sdpMid,
//                        candidate.sdpMLineIndex
//                    )
//                }
//
//                override fun onConnectionChange(state: RTCPeerConnectionState) {
//                    val connectionState = when (state) {
//                        RTCPeerConnectionState.NEW -> WebRtcConnectionState.NEW
//                        RTCPeerConnectionState.CONNECTING -> WebRtcConnectionState.CONNECTING
//                        RTCPeerConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
//                        RTCPeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
//                        RTCPeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
//                        RTCPeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
//                    }
//                    listener?.onConnectionStateChange(connectionState)
//                }
//
//                override fun onTrack(transceiver: RTCRtpTransceiver) {
//                    val track = transceiver.receiver?.track
//                    if (track?.kind == "audio") {
//                        listener?.onRemoteAudioTrack()
//                    }
//                }
//
//                override fun onSignalingChange(state: RTCSignalingState) {}
//                override fun onIceGatheringChange(state: RTCIceGatheringState) {}
//                override fun onIceConnectionChange(state: RTCIceConnectionState) {}
//                override fun onStandardizedIceConnectionChange(state: RTCIceConnectionState) {}
//                override fun onDataChannel(dataChannel: RTCDataChannel) {}
//                override fun onRenegotiationNeeded() {}
//                override fun onAddStream(stream: dev.onvoid.webrtc.media.MediaStream) {}
//                override fun onRemoveStream(stream: dev.onvoid.webrtc.media.MediaStream) {}
//            })
//        } catch (e: Exception) {
//            onError(e.message ?: "Failed to create PeerConnection")
//            return null
//        }
//
//        return peerConnection
//    }
//}