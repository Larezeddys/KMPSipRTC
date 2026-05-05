package com.eddyslarez.kmpsiprtc.data.models

import kotlin.time.ExperimentalTime


data class AudioUnit(
    val type: AudioUnitTypes,
    val capability: AudioUnitCompatibilities,
    val isCurrent: Boolean,
    val isDefault: Boolean,
    val capabilities: AudioDeviceCapabilities = AudioDeviceCapabilities() // [OK] agregado
)

enum class AudioUnitTypes {
    UNKNOWN,
    MICROPHONE,
    EARPIECE,
    SPEAKER,
    BLUETOOTH,
    TELEPHONY,
    AUXLINE,     // ya lo tienes (sirve como "AUX")
    HEADSET,
    HEADPHONES,
    GENERICUSB,
    HEARINGAID,
    BLUETOOTHA2DP,
    HDMI,
    DISPLAY_AUDIO,
    VIRTUAL,
    USB,         // [OK] nuevo alias para dispositivos USB genéricos
    AUX          // [OK] nuevo alias más intuitivo para AUXLINE
}


enum class AudioUnitCompatibilities {
    PLAY,
    RECORD,
    ALL,
    UNKNOWN
}

/**
 * Extensión para convertir un AudioDevice a un descriptor simple y legible.
 */
fun AudioDevice.toSimpleDescriptor(): String {
    return when {
        isBluetooth -> "bluetooth"
        audioUnit.type == AudioUnitTypes.SPEAKER -> "speaker"
        audioUnit.type == AudioUnitTypes.HEADSET ||
                audioUnit.type == AudioUnitTypes.HEADPHONES -> "headset"
        audioUnit.type == AudioUnitTypes.EARPIECE -> "earpiece"
        audioUnit.type == AudioUnitTypes.MICROPHONE -> "microphone"
        audioUnit.type == AudioUnitTypes.HDMI -> "hdmi"
        else -> "unknown"
    }
}

data class AudioDevice @OptIn(ExperimentalTime::class) constructor(
    val name: String,
    val descriptor: String,
    val nativeDevice: Any? = null,
    val isOutput: Boolean,
    val audioUnit: AudioUnit,
    var connectionState: DeviceConnectionState = DeviceConnectionState.AVAILABLE,
    val signalStrength: Int? = null,
    val batteryLevel: Int? = null,
    val isWireless: Boolean = false,
    val supportsHDVoice: Boolean = false,
    val latency: Int? = null,
    val vendorInfo: String? = null,
    val capabilities: AudioDeviceCapabilities = AudioDeviceCapabilities(), // [OK] agregado
    val lastUpdated: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
    val deviceAddress: String? = null,                                      // [OK] para Bluetooth o USB
    val preferredSampleRate: Int? = null                                   // [OK] para sincronizar con WebRTC
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

        // Tipo de unidad
        score += when (audioUnit.type) {
            AudioUnitTypes.HEADSET, AudioUnitTypes.HEADPHONES -> 20
            AudioUnitTypes.BLUETOOTH, AudioUnitTypes.BLUETOOTHA2DP -> 15
            AudioUnitTypes.EARPIECE, AudioUnitTypes.SPEAKER -> 10
            AudioUnitTypes.HEARINGAID -> 25
            AudioUnitTypes.GENERICUSB, AudioUnitTypes.HDMI -> 18
            else -> 0
        }

        // Capacidades de voz
        if (supportsHDVoice) score += 15
        if (capabilities.supportsNoiseSuppression) score += 10
        if (capabilities.supportsEchoCancellation) score += 10

        // Fuerza de señal
        signalStrength?.let { score += (it * 0.2).toInt() }

        // Penalización por latencia
        latency?.let { lat ->
            when {
                lat < 50 -> score += 10
                lat in 50..150 -> score += 5
                lat > 200 -> score -= 10
            }
        }

        // Penalización si batería baja
        if (batteryLevel != null && batteryLevel < 20) score -= 10

        return score.coerceIn(0, 100)
    }

    /**
     * [OK] Diagnóstico rápido del dispositivo
     */
    fun describe(): String = buildString {
        appendLine("🎧 AudioDevice: $name ($descriptor)")
        appendLine("• Type: ${audioUnit.type}")
        appendLine("• Capability: ${audioUnit.capability}")
        appendLine("• Connection: ${connectionState}")
        appendLine("• Wireless: $isWireless | Signal: ${signalStrength ?: "-"} | Battery: ${batteryLevel ?: "-"}")
        appendLine("• Latency: ${latency ?: "-"} ms | HDVoice: $supportsHDVoice")
        appendLine("• QualityScore: $qualityScore")
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
    val maxSampleRate: Int = 48000,
    val minSampleRate: Int = 8000,
    val supportedCodecs: List<String> = listOf("PCM", "OPUS", "AAC"), // [OK] útil para SIP/RTC
    val dynamicRange: IntRange = 0..100, // [OK] rango dinámico (volumen)
    val supportsVoiceActivityDetection: Boolean = false, // [OK] para WebRTC optimizado
    val supportsFullDuplex: Boolean = true // [OK] importante en llamadas
)
