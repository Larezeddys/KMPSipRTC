package com.eddyslarez.kmpsiprtc.services.audio


import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.AudioUnit
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitCompatibilities
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitTypes
import com.eddyslarez.kmpsiprtc.data.models.DeviceConnectionState
import com.eddyslarez.kmpsiprtc.platform.log
import platform.AVFAudio.*
import platform.Foundation.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalForeignApi::class)
class IosAudioController(
    private val onDeviceChanged: (AudioDevice?) -> Unit
) {
    private val TAG = "IosAudioController"
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val audioDevices = mutableListOf<AudioDevice>()
    private var savedAudioCategory: String? = null
    private var isStarted = false
    private var audioSessionConfigured = false

    private val _availableDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    val availableDevices: StateFlow<List<AudioDevice>> = _availableDevices.asStateFlow()

    // ==================== INITIALIZATION ====================

    fun initialize() {
        log.d(TAG) { "Initializing AudioController" }

        // Registrar observer para cambios de ruta de audio
        setupAudioRouteChangeObserver()

        scanDevices()
        log.d(TAG) { "✅ AudioController initialized" }
    }

    private fun setupAudioRouteChangeObserver() {
        val notificationCenter = NSNotificationCenter.defaultCenter

        notificationCenter.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = null,
            queue = null
        ) { notification ->
            log.d(TAG) { "Audio route changed" }
            coroutineScope.launch {
                delay(200)
                scanDevices()
                onDeviceChanged(getCurrentOutputDevice())
            }
        }
    }

    fun startForCall() {
        if (isStarted) {
            log.d(TAG) { "Audio already started" }
            return
        }

        log.d(TAG) { "🔊 Starting audio for call" }

        val audioSession = AVAudioSession.sharedInstance()
        savedAudioCategory = audioSession.category

        if (!configureAudioSession()) {
            log.e(TAG) { "Failed to configure audio session" }
            return
        }

        scanDevices()
        selectDefaultDeviceWithPriority()

        isStarted = true
        log.d(TAG) { "✅ Audio started" }
    }

    fun stop() {
        if (!isStarted) return

        log.d(TAG) { "🔇 Stopping audio" }

        val audioSession = AVAudioSession.sharedInstance()

        // Restaurar categoría anterior
        savedAudioCategory?.let { category ->
            try {
                audioSession.setCategory(category, null)
            } catch (e: Exception) {
                log.w(TAG) { "Error restoring audio category: ${e.message}" }
            }
        }

        try {
            audioSession.setActive(false, null)
        } catch (e: Exception) {
            log.w(TAG) { "Error deactivating audio session: ${e.message}" }
        }

        isStarted = false
        audioSessionConfigured = false
        log.d(TAG) { "✅ Audio stopped" }
    }

    fun dispose() {
        stop()

        // Remover observers
        NSNotificationCenter.defaultCenter.removeObserver(this)

        coroutineScope.cancel()
        log.d(TAG) { "AudioController disposed" }
    }

    // ==================== AUDIO SESSION MANAGEMENT ====================

    private fun configureAudioSession(): Boolean {
        return try {
            val audioSession = AVAudioSession.sharedInstance()

            // Configurar categoría para llamadas de voz
            val success1 = audioSession.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                AVAudioSessionCategoryOptionAllowBluetooth or
                        AVAudioSessionCategoryOptionDefaultToSpeaker,
                null
            )
            if (!success1) {
                log.e(TAG) { "Failed to set audio category" }
                return false
            }

            // Configurar modo de voz
            val success2 = audioSession.setMode(AVAudioSessionModeVoiceChat, null)
            if (!success2) {
                log.e(TAG) { "Failed to set audio mode" }
                return false
            }

            // Activar sesión
            val success3 = audioSession.setActive(true, null)
            if (!success3) {
                log.e(TAG) { "Failed to activate audio session" }
                return false
            }

            audioSessionConfigured = true
            log.d(TAG) { "✅ Audio session configured" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Exception configuring audio session: ${e.message}" }
            false
        }
    }

    // ==================== DEVICE MANAGEMENT ====================

    fun setActiveRoute(audioUnitType: AudioUnitTypes): Boolean {
        val audioSession = AVAudioSession.sharedInstance()

        return try {
            log.d(TAG) { "Setting active route to: $audioUnitType" }

            when (audioUnitType) {
                AudioUnitTypes.SPEAKER -> {
                    audioSession.overrideOutputAudioPort(
                        AVAudioSessionPortOverrideSpeaker,
                        null
                    )
                    log.d(TAG) { "✅ Switched to SPEAKER" }
                }
                AudioUnitTypes.EARPIECE -> {
                    audioSession.overrideOutputAudioPort(
                        AVAudioSessionPortOverrideNone,
                        null
                    )
                    log.d(TAG) { "✅ Switched to EARPIECE" }
                }
                AudioUnitTypes.BLUETOOTH -> {
                    // En iOS, Bluetooth se selecciona automáticamente
                    audioSession.overrideOutputAudioPort(
                        AVAudioSessionPortOverrideNone,
                        null
                    )
                    log.d(TAG) { "✅ Switched to BLUETOOTH" }
                }
                AudioUnitTypes.HEADSET, AudioUnitTypes.HEADPHONES -> {
                    audioSession.overrideOutputAudioPort(
                        AVAudioSessionPortOverrideNone,
                        null
                    )
                    log.d(TAG) { "✅ Switched to HEADSET" }
                }
                else -> {
                    log.w(TAG) { "Unsupported audio route: $audioUnitType" }
                    return false
                }
            }

            // Actualizar dispositivos después del cambio
            coroutineScope.launch {
                delay(200)
                scanDevices()
                onDeviceChanged(getCurrentOutputDevice())
            }

            true
        } catch (e: Exception) {
            log.e(TAG) { "Error setting route: ${e.message}" }
            false
        }
    }

    fun getActiveRoute(): AudioUnitTypes? {
        val audioSession = AVAudioSession.sharedInstance()
        val currentRoute = audioSession.currentRoute
        val outputs = currentRoute.outputs

        if (outputs.isEmpty()) {
            return AudioUnitTypes.EARPIECE
        }

        val firstOutput = outputs.firstOrNull() as? AVAudioSessionPortDescription
        val portType = firstOutput?.portType

        return when (portType) {
            AVAudioSessionPortBuiltInSpeaker -> AudioUnitTypes.SPEAKER
            AVAudioSessionPortBuiltInReceiver -> AudioUnitTypes.EARPIECE
            AVAudioSessionPortBluetoothHFP -> AudioUnitTypes.BLUETOOTH
            AVAudioSessionPortBluetoothA2DP -> AudioUnitTypes.BLUETOOTH
            AVAudioSessionPortHeadphones -> AudioUnitTypes.HEADPHONES
            AVAudioSessionPortHeadsetMic -> AudioUnitTypes.HEADSET
            else -> AudioUnitTypes.EARPIECE
        }
    }

    fun getAvailableRoutes(): Set<AudioUnitTypes> {
        val routes = mutableSetOf<AudioUnitTypes>()

        // Siempre disponibles
        routes.add(AudioUnitTypes.EARPIECE)
        routes.add(AudioUnitTypes.SPEAKER)

        // Verificar dispositivos disponibles
        val audioSession = AVAudioSession.sharedInstance()

        // Bluetooth
        val hasBluetoothInput = audioSession.availableInputs?.any { input ->
            val port = input as? AVAudioSessionPortDescription
            port?.portType == AVAudioSessionPortBluetoothHFP
        } ?: false

        if (hasBluetoothInput) {
            routes.add(AudioUnitTypes.BLUETOOTH)
        }

        // Headphones/Headset (detectar por ruta actual)
        val currentRoute = audioSession.currentRoute
        currentRoute.outputs.forEach { output ->
            val port = output as? AVAudioSessionPortDescription
            when (port?.portType) {
                AVAudioSessionPortHeadphones -> routes.add(AudioUnitTypes.HEADPHONES)
                AVAudioSessionPortHeadsetMic -> routes.add(AudioUnitTypes.HEADSET)
            }
        }

        return routes
    }

    fun getAllDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        scanDevices()
        val inputs = audioDevices.filter { !it.isOutput }
        val outputs = audioDevices.filter { it.isOutput }

        log.d(TAG) { "All devices - Inputs: ${inputs.size}, Outputs: ${outputs.size}" }
        return Pair(inputs, outputs)
    }

    fun changeOutputDevice(device: AudioDevice): Boolean {
        log.d(TAG) { "Changing output device to: ${device.name}" }
        val success = setActiveRoute(device.audioUnit.type)
        if (success) {
            onDeviceChanged(device)
        }
        return success
    }

    fun changeInputDevice(device: AudioDevice): Boolean {
        log.d(TAG) { "Input device change requested (iOS auto-manages)" }
        return true
    }

    fun getCurrentInputDevice(): AudioDevice? {
        return audioDevices.firstOrNull { !it.isOutput && it.audioUnit.isCurrent }
    }

    fun getCurrentOutputDevice(): AudioDevice? {
        val activeRoute = getActiveRoute()
        return audioDevices.firstOrNull {
            it.isOutput && it.audioUnit.type == activeRoute
        }
    }

    fun getAvailableAudioUnits(): Set<AudioUnit> {
        scanDevices()
        return audioDevices.map { it.audioUnit }.toSet()
    }

    fun getCurrentActiveAudioUnit(): AudioUnit? {
        return getCurrentOutputDevice()?.audioUnit?.copy(isCurrent = true)
    }

    fun refreshDevices() {
        scanDevices()
        onDeviceChanged(getCurrentOutputDevice())
    }

    fun refreshWithBluetoothPriority() {
        scanDevices()
        selectDefaultDeviceWithPriority()
    }

    // ==================== PRIVATE HELPERS ====================

    private fun selectDefaultDeviceWithPriority() {
        val availableTypes = audioDevices
            .filter { it.isOutput }
            .map { it.audioUnit.type }
            .toSet()

        log.d(TAG) { "Available output devices: $availableTypes" }

        val priorityType = when {
            AudioUnitTypes.BLUETOOTH in availableTypes -> {
                log.d(TAG) { "✅ Selecting BLUETOOTH (highest priority)" }
                AudioUnitTypes.BLUETOOTH
            }
            AudioUnitTypes.HEADSET in availableTypes ||
                    AudioUnitTypes.HEADPHONES in availableTypes -> {
                log.d(TAG) { "Selecting HEADSET/HEADPHONES" }
                availableTypes.firstOrNull {
                    it == AudioUnitTypes.HEADSET || it == AudioUnitTypes.HEADPHONES
                } ?: AudioUnitTypes.EARPIECE
            }
            else -> {
                log.d(TAG) { "Selecting EARPIECE (default)" }
                AudioUnitTypes.EARPIECE
            }
        }

        setActiveRoute(priorityType)
    }

    private fun scanDevices() {
        audioDevices.clear()

        val audioSession = AVAudioSession.sharedInstance()
        val currentRoute = audioSession.currentRoute
        val currentOutputPort = currentRoute.outputs.firstOrNull()?.let {
            (it as AVAudioSessionPortDescription).portType
        }

        // Dispositivos siempre disponibles
        audioDevices.add(createMicrophoneDevice())
        audioDevices.add(createEarpieceDevice(currentOutputPort))
        audioDevices.add(createSpeakerDevice(currentOutputPort))

        // Detectar dispositivos conectados
        // Bluetooth
        audioSession.availableInputs?.forEach { input ->
            val port = input as? AVAudioSessionPortDescription
            if (port?.portType == AVAudioSessionPortBluetoothHFP) {
                audioDevices.add(createBluetoothDevice(port, currentOutputPort))
            }
        }

        // Headphones/Headset
        currentRoute.outputs.forEach { output ->
            val port = output as? AVAudioSessionPortDescription
            when (port?.portType) {
                AVAudioSessionPortHeadphones -> {
                    audioDevices.add(createHeadphonesDevice(port, currentOutputPort))
                }
                AVAudioSessionPortHeadsetMic -> {
                    audioDevices.add(createHeadsetDevice(port, currentOutputPort))
                }
            }
        }

        _availableDevices.value = audioDevices.toList()
        log.d(TAG) { "Total devices scanned: ${audioDevices.size}" }
    }

    // ==================== DEVICE CREATION ====================

    private fun createMicrophoneDevice() = AudioDevice(
        name = "Built-in Microphone",
        descriptor = "builtin_mic",
        nativeDevice = null,
        isOutput = false,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.MICROPHONE,
            capability = AudioUnitCompatibilities.RECORD,
            isCurrent = true,
            isDefault = true
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        isWireless = false,
        supportsHDVoice = true,
        latency = 10
    )

    private fun createEarpieceDevice(currentPort: String?) = AudioDevice(
        name = "iPhone",
        descriptor = AVAudioSessionPortBuiltInReceiver ?: "builtin_receiver",
        nativeDevice = null,
        isOutput = true,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.EARPIECE,
            capability = AudioUnitCompatibilities.PLAY,
            isCurrent = currentPort == AVAudioSessionPortBuiltInReceiver,
            isDefault = true
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        isWireless = false,
        supportsHDVoice = true,
        latency = 15
    )

    private fun createSpeakerDevice(currentPort: String?) = AudioDevice(
        name = "Speaker",
        descriptor = AVAudioSessionPortBuiltInSpeaker ?: "builtin_speaker",
        nativeDevice = null,
        isOutput = true,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.SPEAKER,
            capability = AudioUnitCompatibilities.PLAY,
            isCurrent = currentPort == AVAudioSessionPortBuiltInSpeaker,
            isDefault = false
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        isWireless = false,
        supportsHDVoice = false,
        latency = 20
    )

    private fun createBluetoothDevice(
        port: AVAudioSessionPortDescription,
        currentPort: String?
    ) = AudioDevice(
        name = port.portName ?: "Bluetooth Device",
        descriptor = port.portType ?: "bluetooth",
        nativeDevice = port,
        isOutput = true,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.BLUETOOTH,
            capability = AudioUnitCompatibilities.ALL,
            isCurrent = currentPort == AVAudioSessionPortBluetoothHFP,
            isDefault = false
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        isWireless = true,
        supportsHDVoice = true,
        latency = 50
    )

    private fun createHeadphonesDevice(
        port: AVAudioSessionPortDescription,
        currentPort: String?
    ) = AudioDevice(
        name = port.portName ?: "Headphones",
        descriptor = port.portType ?: "headphones",
        nativeDevice = port,
        isOutput = true,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.HEADPHONES,
            capability = AudioUnitCompatibilities.PLAY,
            isCurrent = currentPort == AVAudioSessionPortHeadphones,
            isDefault = false
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        isWireless = false,
        supportsHDVoice = true,
        latency = 10
    )

    private fun createHeadsetDevice(
        port: AVAudioSessionPortDescription,
        currentPort: String?
    ) = AudioDevice(
        name = port.portName ?: "Headset",
        descriptor = port.portType ?: "headset",
        nativeDevice = port,
        isOutput = true,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.HEADSET,
            capability = AudioUnitCompatibilities.ALL,
            isCurrent = currentPort == AVAudioSessionPortHeadsetMic,
            isDefault = false
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        isWireless = false,
        supportsHDVoice = true,
        latency = 10
    )

    fun diagnose(): String {
        return buildString {
            appendLine("=== iOS Audio Diagnostics ===")
            appendLine("Started: $isStarted")
            appendLine("Audio Session Configured: $audioSessionConfigured")
            appendLine("Active Route: ${getActiveRoute()}")

            val audioSession = AVAudioSession.sharedInstance()
            appendLine("Category: ${audioSession.category}")
            appendLine("Mode: ${audioSession.mode}")
            appendLine("Is Active: ${audioSession.secondaryAudioShouldBeSilencedHint}")

            val (inputs, outputs) = getAllDevices()
            appendLine("Input Devices: ${inputs.size}")
            appendLine("Output Devices: ${outputs.size}")
            appendLine("\nOutput Devices Detail:")
            outputs.forEach { device ->
                appendLine("  - ${device.name} (${device.audioUnit.type}) - Current: ${device.audioUnit.isCurrent}")
            }
        }
    }
}

// ==================== iOS WebRtcManager Completo ====================

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
