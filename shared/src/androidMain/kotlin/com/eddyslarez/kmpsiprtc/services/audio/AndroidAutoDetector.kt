package com.eddyslarez.kmpsiprtc.services.audio

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.os.Build
import android.media.AudioManager

class AndroidAutoDetector(private val context: Context) {
    private val TAG = "AndroidAutoDetector"

    companion object {
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
        private const val ANDROID_AUTO_MEDIA_PACKAGE = "com.google.android.carassistant"
    }

    /**
     * Detecta si Android Auto está conectado
     */
    fun isAndroidAutoConnected(): Boolean {
        return isAndroidAutoActive() || isCarModeActive()
    }

    /**
     * Verifica si Android Auto está activo
     */
    private fun isAndroidAutoActive(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningApps = activityManager.runningAppProcesses ?: return false

        return runningApps.any {
            it.processName == ANDROID_AUTO_PACKAGE ||
                    it.processName == ANDROID_AUTO_MEDIA_PACKAGE
        }
    }

    /**
     * Verifica si el modo coche está activo
     */
    private fun isCarModeActive(): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_CAR
    }

    /**
     * Obtiene el tipo de dispositivo de audio del coche
     */
    fun getCarAudioDeviceType(): AudioDeviceType? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            // Buscar dispositivo del coche
            val carDevice = devices.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_AUX_LINE
            }

            return carDevice?.let {
                AudioDeviceType(
                    type = it.type,
                    name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        it.productName.toString()
                    } else {
                        "Car Audio System"
                    },
                    address = it.address,
                    isCarAudio = true
                )
            }
        }

        return null
    }
}

data class AudioDeviceType(
    val type: Int,
    val name: String,
    val address: String,
    val isCarAudio: Boolean
)
