package com.eddyslarez.kmpsiprtc.services.audio

import android.content.Context
import android.media.AudioDeviceInfo

actual class CarAudioDetector(private val context: Context) {
    private val detector = AndroidAutoDetector(context)

    actual fun isCarAudioConnected(): Boolean {
        return detector.isAndroidAutoConnected()
    }

    actual fun getCarAudioDeviceInfo(): CarAudioDeviceInfo? {
        val androidAutoInfo = detector.getCarAudioDeviceType() ?: return null

        return CarAudioDeviceInfo(
            name = androidAutoInfo.name,
            type = when (androidAutoInfo.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> CarAudioConnectionType.BLUETOOTH
                AudioDeviceInfo.TYPE_USB_DEVICE -> CarAudioConnectionType.USB
                AudioDeviceInfo.TYPE_AUX_LINE -> CarAudioConnectionType.AUX
                else -> CarAudioConnectionType.UNKNOWN
            },
            isCarAudio = true
        )
    }
}