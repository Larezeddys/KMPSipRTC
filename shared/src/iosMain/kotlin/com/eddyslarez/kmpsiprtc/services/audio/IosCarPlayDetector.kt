package com.eddyslarez.kmpsiprtc.services.audio

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeVoiceChat
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.currentRoute
import platform.AVFAudio.setActive
import platform.UIKit.UIScreen
import platform.UIKit.UIUserInterfaceIdiom

/**
 * Detecta si CarPlay está conectado
 * Equivalente a AndroidAutoDetector
 */
@OptIn(ExperimentalForeignApi::class)
class IosCarPlayDetector {
    private val TAG = "IosCarPlayDetector"


    /**
     * Detecta si CarPlay está activo
     * ✅ CORREGIDO: Usar API correcta de UIScreen
     */
    fun isCarPlayConnected(): Boolean {
        // Verificar si hay pantallas CarPlay conectadas
        val screens = UIScreen.screens as? List<*> ?: return false

        return screens.any { screen ->
            (screen as? UIScreen)?.let { uiScreen ->
                // En iOS 13+, CarPlay tiene su propia pantalla
                // Verificar por nombre o características
                uiScreen != UIScreen.mainScreen
            } ?: false
        }
    }
    /**
     * Obtiene información del dispositivo de audio del coche
     */
    fun getCarAudioDeviceType(): CarAudioDeviceInfo? {
        if (!isCarPlayConnected()) return null

        val audioSession = AVAudioSession.sharedInstance()
        val currentRoute = audioSession.currentRoute

        // Buscar salida del coche
        val carOutput = currentRoute.outputs.firstOrNull { output ->
            val port = output as AVAudioSessionPortDescription
            port.portType == AVAudioSessionPortCarAudio
        }

        return carOutput?.let { output ->
            val port = output as AVAudioSessionPortDescription
            CarAudioDeviceInfo(
                name = port.portName ?: "CarPlay",
                type = CarAudioConnectionType.CARPLAY,
//                portType = port.portType ?: "",
                isCarAudio = true
            )
        }
    }

    /**
     * Configura la sesión de audio para CarPlay
     */
    fun configureAudioForCarPlay(): Boolean {
        if (!isCarPlayConnected()) return false

        return try {
            val audioSession = AVAudioSession.sharedInstance()

            // Configurar categoría para CarPlay
            audioSession.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                AVAudioSessionCategoryOptionAllowBluetooth or
                        AVAudioSessionCategoryOptionDefaultToSpeaker,
                null
            )

            audioSession.setMode(AVAudioSessionModeVoiceChat, null)
            audioSession.setActive(true, 0u, null)

            println("[$TAG] ✅ Audio configured for CarPlay")
            true
        } catch (e: Exception) {
            println("[$TAG] ❌ Error configuring CarPlay audio: ${e.message}")
            false
        }
    }
}

///**
// * Información del dispositivo de audio del coche (iOS)
// */
//data class CarAudioDeviceInfo(
//    val name: String,
//    val type: CarAudioConnectionType,
//    val portType: String,
//    val isCarAudio: Boolean
//)

enum class CarAudioConnectionType {
    CARPLAY,
    BLUETOOTH,
    USB,
    AUX,
    UNKNOWN
}