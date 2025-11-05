package com.eddyslarez.kmpsiprtc.services.audio

/**
 * Detecta dispositivos de audio del coche en Desktop
 */
class DesktopCarAudioDetector {
    private val TAG = "DesktopCarAudioDetector"

    /**
     * Detecta si hay un sistema de coche conectado
     * (CarPlay vía USB, Android Auto vía USB, etc)
     */
    fun isCarAudioConnected(): Boolean {
        // En Desktop, esto requiere acceso nativo al sistema
        // Por ahora, detectamos dispositivos USB/Bluetooth con nombres conocidos
        return hasCarAudioDevice()
    }

    /**
     * Verifica si hay dispositivos de audio del coche
     */
    private fun hasCarAudioDevice(): Boolean {
        // Implementación básica - puede mejorarse con JNI
        return false
    }

    /**
     * Obtiene información del dispositivo de audio del coche
     */
    fun getCarAudioDeviceInfo(): CarAudioDeviceInfo? {
        // En Desktop, necesitaríamos acceso a:
        // - Windows: Core Audio API
        // - macOS: CoreAudio framework
        // - Linux: PulseAudio/ALSA

        println("[$TAG] ⚠️ Car audio detection requires native implementation")
        return null
    }
}

/**
 * Información del dispositivo de audio del coche
 */
data class CarAudioDeviceInfo(
    val name: String,
    val type: CarAudioConnectionType,
    val isCarAudio: Boolean
)

enum class AudioDeviceConnectionType {
    USB,
    BLUETOOTH,
    AUX,
    UNKNOWN
}
