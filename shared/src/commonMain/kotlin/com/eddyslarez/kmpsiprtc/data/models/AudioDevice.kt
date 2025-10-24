package com.eddyslarez.kmpsiprtc.data.models


data class AudioUnit(
    val type: AudioUnitTypes,
    val capability: AudioUnitCompatibilities,
    val isCurrent: Boolean,
    val isDefault: Boolean,
)

enum class AudioUnitTypes {
    UNKNOWN,
    MICROPHONE,
    EARPIECE,
    SPEAKER,
    BLUETOOTH,
    TELEPHONY,
    AUXLINE,
    HEADSET,
    HEADPHONES,
    GENERICUSB,
    HEARINGAID,
    BLUETOOTHA2DP
}

enum class AudioUnitCompatibilities {
    PLAY, RECORD, ALL, UNKNOWN
}
// Agregar extensión para conversión más limpia
fun AudioDevice.toSimpleDescriptor(): String {
    return when {
        isBluetooth -> "bluetooth"
        audioUnit.type == AudioUnitTypes.SPEAKER -> "speaker"
        audioUnit.type == AudioUnitTypes.HEADSET ||
                audioUnit.type == AudioUnitTypes.HEADPHONES -> "headset"
        audioUnit.type == AudioUnitTypes.EARPIECE -> "earpiece"
        else -> "unknown"
    }
}

data class AudioDevice(
    val name: String,                    // Display name
    val descriptor: String,              // Native identifier
    val nativeDevice: Any? = null,       // Platform-specific device object
    val isOutput: Boolean,               // Input/Output flag
    val audioUnit: AudioUnit,            // Enhanced audio unit information
    var connectionState: DeviceConnectionState = DeviceConnectionState.AVAILABLE,
    val signalStrength: Int? = null,     // For Bluetooth devices (0-100)
    val batteryLevel: Int? = null,       // For Bluetooth devices (0-100)
    val isWireless: Boolean = false,     // Bluetooth, WiFi, etc.
    val supportsHDVoice: Boolean = false, // High-definition voice support
    val latency: Int? = null,            // Audio latency in milliseconds
    val vendorInfo: String? = null       // Manufacturer information
) {
    // Convenience properties
    val isInput: Boolean get() = !isOutput
    val canRecord: Boolean get() = audioUnit.capability == AudioUnitCompatibilities.RECORD ||
            audioUnit.capability == AudioUnitCompatibilities.ALL
    val canPlay: Boolean get() = audioUnit.capability == AudioUnitCompatibilities.PLAY ||
            audioUnit.capability == AudioUnitCompatibilities.ALL
    val isBluetooth: Boolean get() = audioUnit.type == AudioUnitTypes.BLUETOOTH ||
            audioUnit.type == AudioUnitTypes.BLUETOOTHA2DP
    val isBuiltIn: Boolean get() = audioUnit.type == AudioUnitTypes.EARPIECE ||
            audioUnit.type == AudioUnitTypes.SPEAKER ||
            audioUnit.type == AudioUnitTypes.MICROPHONE
    val qualityScore: Int get() = calculateQualityScore()

    private fun calculateQualityScore(): Int {
        var score = 50 // Base score

        // Audio unit type scoring
        score += when (audioUnit.type) {
            AudioUnitTypes.HEADSET, AudioUnitTypes.HEADPHONES -> 20
            AudioUnitTypes.BLUETOOTH, AudioUnitTypes.BLUETOOTHA2DP -> 15
            AudioUnitTypes.EARPIECE, AudioUnitTypes.SPEAKER -> 10
            AudioUnitTypes.HEARINGAID -> 25
            AudioUnitTypes.GENERICUSB -> 18
            else -> 0
        }

        // HD Voice support
        if (supportsHDVoice) score += 15

        // Signal strength for wireless devices
        signalStrength?.let { strength ->
            score += (strength * 0.2).toInt()
        }

        // Latency penalty
        latency?.let { lat ->
            if (lat < 50) score += 10
            else if (lat > 150) score -= 10
        }

        return score.coerceIn(0, 100)
    }
}

enum class DeviceConnectionState {
    AVAILABLE,
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    ERROR,
    LOW_BATTERY,
    OUT_OF_RANGE
}

data class AudioDeviceCapabilities(
    val supportsEchoCancellation: Boolean = false,
    val supportsNoiseSuppression: Boolean = false,
    val supportsAutoGainControl: Boolean = false,
    val supportsStereo: Boolean = false,
    val supportsMonaural: Boolean = true,
    val maxSampleRate: Int = 44100,
    val minSampleRate: Int = 8000,
    val supportedCodecs: List<String> = emptyList()
)