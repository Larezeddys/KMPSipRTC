package com.eddyslarez.kmpsiprtc.services.audio

// ==================== Desktop AudioController ====================

import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.AudioUnit
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitCompatibilities
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitTypes
import com.eddyslarez.kmpsiprtc.data.models.DeviceConnectionState
import com.eddyslarez.kmpsiprtc.platform.log
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.sampled.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class DesktopAudioController(
    private val audioDeviceModule: AudioDeviceModule?,
    private val onDeviceChanged: (AudioDevice?) -> Unit
) {
    private val TAG = "DesktopAudioController"
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val audioDevices = CopyOnWriteArrayList<AudioDevice>()
    private var isStarted = false
    private var currentInputDevice: AudioDevice? = null
    private var currentOutputDevice: AudioDevice? = null

    private val _availableDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    val availableDevices: StateFlow<List<AudioDevice>> = _availableDevices.asStateFlow()

    // ==================== INITIALIZATION ====================

    fun initialize() {
        log.d(TAG) { "Initializing AudioController" }
        scanDevices()
        log.d(TAG) { "✅ AudioController initialized with ${audioDevices.size} devices" }
    }

    fun startForCall() {
        if (isStarted) {
            log.d(TAG) { "Audio already started" }
            return
        }

        log.d(TAG) { "🔊 Starting audio for call" }

        scanDevices()
        selectDefaultDeviceWithPriority()

        isStarted = true
        log.d(TAG) { "✅ Audio started" }
    }

    fun stop() {
        if (!isStarted) return

        log.d(TAG) { "🔇 Stopping audio" }
        isStarted = false
        log.d(TAG) { "✅ Audio stopped" }
    }

    fun dispose() {
        stop()
        audioDevices.clear()
        coroutineScope.cancel()
        log.d(TAG) { "AudioController disposed" }
    }

    // ==================== DEVICE MANAGEMENT ====================

    fun setActiveRoute(audioUnitType: AudioUnitTypes): Boolean {
        return try {
            log.d(TAG) { "Setting active route to: $audioUnitType" }

            val device = audioDevices.firstOrNull {
                it.isOutput && it.audioUnit.type == audioUnitType
            }

            if (device != null) {
                currentOutputDevice = device
                log.d(TAG) { "✅ Switched to ${device.name}" }

                coroutineScope.launch {
                    delay(100)
                    updateCurrentDeviceState()
                    onDeviceChanged(getCurrentOutputDevice())
                }
                true
            } else {
                log.w(TAG) { "Device type $audioUnitType not found" }
                false
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error setting route: ${e.message}" }
            false
        }
    }

    fun getActiveRoute(): AudioUnitTypes? {
        return currentOutputDevice?.audioUnit?.type
            ?: audioDevices.firstOrNull { it.isOutput }?.audioUnit?.type
            ?: AudioUnitTypes.SPEAKER
    }

    fun getAvailableRoutes(): Set<AudioUnitTypes> {
        val routes = mutableSetOf<AudioUnitTypes>()

        audioDevices.filter { it.isOutput }.forEach { device ->
            routes.add(device.audioUnit.type)
        }

        // Siempre disponible en desktop
        if (routes.isEmpty()) {
            routes.add(AudioUnitTypes.SPEAKER)
        }

        return routes
    }

    fun getAllDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        scanDevices()
        val inputs = audioDevices.filter { !it.isOutput && it.canRecord }
        val outputs = audioDevices.filter { it.isOutput && it.canPlay }

        log.d(TAG) { "All devices - Inputs: ${inputs.size}, Outputs: ${outputs.size}" }
        return Pair(inputs, outputs)
    }

    fun changeOutputDevice(device: AudioDevice): Boolean {
        log.d(TAG) { "Changing output device to: ${device.name}" }
        currentOutputDevice = device

        val success = setActiveRoute(device.audioUnit.type)
        if (success) {
            onDeviceChanged(device)
        }
        return success
    }

    fun changeInputDevice(device: AudioDevice): Boolean {
        log.d(TAG) { "Changing input device to: ${device.name}" }
        currentInputDevice = device
        onDeviceChanged(device)
        return true
    }

    fun getCurrentInputDevice(): AudioDevice? {
        return currentInputDevice ?: audioDevices.firstOrNull {
            !it.isOutput && it.canRecord
        }
    }

    fun getCurrentOutputDevice(): AudioDevice? {
        return currentOutputDevice ?: audioDevices.firstOrNull {
            it.isOutput && it.canPlay
        }
    }

    fun getAvailableAudioUnits(): Set<AudioUnit> {
        scanDevices()
        return audioDevices.map { it.audioUnit }.toSet()
    }

    fun getCurrentActiveAudioUnit(): AudioUnit? {
        val activeRoute = getActiveRoute()
        val device = audioDevices.firstOrNull {
            it.isOutput && it.audioUnit.type == activeRoute
        }
        return device?.audioUnit?.copy(isCurrent = true)
    }

    fun updateCurrentDeviceState() {
        val activeRoute = getActiveRoute()

        log.d(TAG) { "🔄 Updating device states - Active route: $activeRoute" }

        audioDevices.forEach { device ->
            if (device.isOutput) {
                val shouldBeCurrent = device.audioUnit.type == activeRoute
                // Update would be reflected in the AudioUnit
                if (device.audioUnit.isCurrent != shouldBeCurrent) {
                    log.d(TAG) { "  📝 Updating ${device.name}: isCurrent = $shouldBeCurrent" }
                }
            }
        }
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
        scanDevices()

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
            AudioUnitTypes.HEADSET in availableTypes -> {
                log.d(TAG) { "Selecting HEADSET" }
                AudioUnitTypes.HEADSET
            }
            AudioUnitTypes.HEADPHONES in availableTypes -> {
                log.d(TAG) { "Selecting HEADPHONES" }
                AudioUnitTypes.HEADPHONES
            }
            else -> {
                log.d(TAG) { "Selecting SPEAKER (default)" }
                AudioUnitTypes.SPEAKER
            }
        }

        setActiveRoute(priorityType)
    }

    private fun scanDevices() {
        audioDevices.clear()

        try {
            // Scan input devices
            val inputMixers = AudioSystem.getMixerInfo()
            for (mixerInfo in inputMixers) {
                try {
                    val mixer = AudioSystem.getMixer(mixerInfo)
                    val targetLineInfo = mixer.targetLineInfo

                    if (targetLineInfo.isNotEmpty()) {
                        // This is an input device
                        val device = createInputDevice(mixerInfo, mixer)
                        audioDevices.add(device)
                        log.d(TAG) { "Input device: ${device.name}" }
                    }
                } catch (e: Exception) {
                    // Skip problematic devices
                }
            }

            // Scan output devices
            for (mixerInfo in inputMixers) {
                try {
                    val mixer = AudioSystem.getMixer(mixerInfo)
                    val sourceLineInfo = mixer.sourceLineInfo

                    if (sourceLineInfo.isNotEmpty()) {
                        // This is an output device
                        val device = createOutputDevice(mixerInfo, mixer)
                        audioDevices.add(device)
                        log.d(TAG) { "Output device: ${device.name}" }
                    }
                } catch (e: Exception) {
                    // Skip problematic devices
                }
            }

            // Always add default devices if none found
            if (audioDevices.none { it.isOutput }) {
                audioDevices.add(createDefaultSpeaker())
            }
            if (audioDevices.none { !it.isOutput }) {
                audioDevices.add(createDefaultMicrophone())
            }

            _availableDevices.value = audioDevices.toList()
            log.d(TAG) { "Total devices scanned: ${audioDevices.size}" }

        } catch (e: Exception) {
            log.e(TAG) { "Error scanning devices: ${e.message}" }
            e.printStackTrace()

            // Add fallback devices
            audioDevices.add(createDefaultMicrophone())
            audioDevices.add(createDefaultSpeaker())
        }
    }

    // ==================== DEVICE CREATION ====================

    private fun createInputDevice(mixerInfo: Mixer.Info, mixer: Mixer): AudioDevice {
        val name = mixerInfo.name
        val deviceType = detectInputDeviceType(name)
        val isDefault = name.contains("primary", ignoreCase = true) ||
                name.contains("default", ignoreCase = true)

        return AudioDevice(
            name = name,
            descriptor = mixerInfo.description ?: name,
            nativeDevice = mixer,
            isOutput = false,
            audioUnit = AudioUnit(
                type = deviceType,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = isDefault,
                isDefault = isDefault
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = deviceType == AudioUnitTypes.BLUETOOTH,
            supportsHDVoice = true,
            latency = 20
        )
    }

    private fun createOutputDevice(mixerInfo: Mixer.Info, mixer: Mixer): AudioDevice {
        val name = mixerInfo.name
        val deviceType = detectOutputDeviceType(name)
        val isDefault = name.contains("primary", ignoreCase = true) ||
                name.contains("default", ignoreCase = true)
        val isCurrent = currentOutputDevice?.name == name

        return AudioDevice(
            name = name,
            descriptor = mixerInfo.description ?: name,
            nativeDevice = mixer,
            isOutput = true,
            audioUnit = AudioUnit(
                type = deviceType,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = isCurrent,
                isDefault = isDefault
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = deviceType == AudioUnitTypes.BLUETOOTH,
            supportsHDVoice = deviceType != AudioUnitTypes.SPEAKER,
            latency = when (deviceType) {
                AudioUnitTypes.BLUETOOTH -> 50
                AudioUnitTypes.HEADSET, AudioUnitTypes.HEADPHONES -> 10
                else -> 20
            }
        )
    }

    private fun createDefaultMicrophone(): AudioDevice {
        return AudioDevice(
            name = "Default Microphone",
            descriptor = "default_mic",
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
    }

    private fun createDefaultSpeaker(): AudioDevice {
        return AudioDevice(
            name = "Default Speaker",
            descriptor = "default_speaker",
            nativeDevice = null,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.SPEAKER,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = currentOutputDevice == null,
                isDefault = true
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = false,
            supportsHDVoice = false,
            latency = 20
        )
    }

    private fun detectInputDeviceType(name: String): AudioUnitTypes {
        val lowerName = name.lowercase()
        return when {
            lowerName.contains("bluetooth") -> AudioUnitTypes.BLUETOOTH
            lowerName.contains("headset") || lowerName.contains("headphone") ->
                AudioUnitTypes.HEADSET
            else -> AudioUnitTypes.MICROPHONE
        }
    }

    private fun detectOutputDeviceType(name: String): AudioUnitTypes {
        val lowerName = name.lowercase()
        return when {
            lowerName.contains("bluetooth") -> AudioUnitTypes.BLUETOOTH
            lowerName.contains("headset") -> AudioUnitTypes.HEADSET
            lowerName.contains("headphone") -> AudioUnitTypes.HEADPHONES
            lowerName.contains("speaker") -> AudioUnitTypes.SPEAKER
            else -> AudioUnitTypes.SPEAKER
        }
    }

    // ==================== DIAGNOSTICS ====================

    fun diagnose(): String {
        return buildString {
            appendLine("=== Desktop Audio Diagnostics ===")
            appendLine("Started: $isStarted")
            appendLine("Active Route: ${getActiveRoute()}")
            appendLine("Current Input: ${currentInputDevice?.name ?: "none"}")
            appendLine("Current Output: ${currentOutputDevice?.name ?: "none"}")

            val (inputs, outputs) = getAllDevices()
            appendLine("\nInput Devices: ${inputs.size}")
            inputs.forEach { device ->
                appendLine("  - ${device.name} (${device.audioUnit.type})")
            }

            appendLine("\nOutput Devices: ${outputs.size}")
            outputs.forEach { device ->
                appendLine("  - ${device.name} (${device.audioUnit.type}) - Current: ${device.audioUnit.isCurrent}")
            }
        }
    }
}