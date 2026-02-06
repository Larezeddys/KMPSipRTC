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
            log.w(TAG) { "Failed to configure audio session (CallKit may activate it later)" }
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
            (input as? AVAudioSessionPortDescription)?.let { port ->
                if (port.portType == AVAudioSessionPortBluetoothHFP) {
                    audioDevices.add(createBluetoothDevice(port, currentOutputPort))
                }
            }
        }

        // Headphones/Headset
        currentRoute.outputs.forEach { output ->
            (output as? AVAudioSessionPortDescription)?.let { port ->
                when (port.portType) {
                    AVAudioSessionPortHeadphones -> {
                        audioDevices.add(createHeadphonesDevice(port, currentOutputPort))
                    }
                    AVAudioSessionPortHeadsetMic -> {
                        audioDevices.add(createHeadsetDevice(port, currentOutputPort))
                    }
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
