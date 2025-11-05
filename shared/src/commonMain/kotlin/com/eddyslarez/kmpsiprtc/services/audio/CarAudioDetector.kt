package com.eddyslarez.kmpsiprtc.services.audio

// commonMain/kotlin/CarAudioDetector.kt

/**
 * Interfaz común para detectar audio del coche en todas las plataformas
 *
 * Uso:
 * ```
 * // Android
 * val detector = CarAudioDetector(context)
 *
 * // Desktop e iOS
 * val detector = CarAudioDetector()
 *
 * if (detector.isCarAudioConnected()) {
 *     val info = detector.getCarAudioDeviceInfo()
 *     println("Conectado a: ${info?.name}")
 * }
 * ```
 */
expect class CarAudioDetector {
    /**
     * Verifica si hay un dispositivo de audio del coche conectado
     * @return true si Android Auto, CarPlay u otro sistema de coche está conectado
     */
    fun isCarAudioConnected(): Boolean

    /**
     * Obtiene información del dispositivo de audio del coche
     * @return Información del dispositivo o null si no está conectado
     */
    fun getCarAudioDeviceInfo(): CarAudioDeviceInfo?
}

/**
 * Información del dispositivo de audio del coche
 * Estructura común para todas las plataformas
 */
data class CarAudioDeviceInfo(
    /** Nombre del dispositivo (ej: "CarPlay", "Android Auto", "Toyota") */
    val name: String,

    /** Tipo de conexión del dispositivo */
    val type: CarAudioConnectionType,

    /** Indica si es definitivamente un dispositivo de coche */
    val isCarAudio: Boolean
)

/**
 * Tipos de conexión de audio del coche
 */
enum class CarAudioConnectionType {
    /** Apple CarPlay (solo iOS) */
    CARPLAY,

    /** Android Auto (solo Android) */
    ANDROID_AUTO,

    /** Bluetooth genérico (todas las plataformas) */
    BLUETOOTH,

    /** USB conectado (todas las plataformas) */
    USB,

    /** Cable auxiliar 3.5mm (todas las plataformas) */
    AUX,

    /** Tipo desconocido */
    UNKNOWN
}

/**
 * Extension function para verificar si es un sistema de coche nativo
 */
fun CarAudioDeviceInfo.isNativeCarSystem(): Boolean {
    return type == CarAudioConnectionType.CARPLAY ||
            type == CarAudioConnectionType.ANDROID_AUTO
}

/**
 * Extension function para obtener descripción amigable
 */
fun CarAudioConnectionType.getDisplayName(): String {
    return when (this) {
        CarAudioConnectionType.CARPLAY -> "Apple CarPlay"
        CarAudioConnectionType.ANDROID_AUTO -> "Android Auto"
        CarAudioConnectionType.BLUETOOTH -> "Bluetooth"
        CarAudioConnectionType.USB -> "USB"
        CarAudioConnectionType.AUX -> "Auxiliar (3.5mm)"
        CarAudioConnectionType.UNKNOWN -> "Desconocido"
    }
}