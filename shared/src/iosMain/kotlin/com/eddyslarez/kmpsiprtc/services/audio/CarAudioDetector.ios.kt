package com.eddyslarez.kmpsiprtc.services.audio

import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
actual class CarAudioDetector {
    private val detector = IosCarPlayDetector()

    actual fun isCarAudioConnected(): Boolean {
        return detector.isCarPlayConnected()
    }

    actual fun getCarAudioDeviceInfo(): CarAudioDeviceInfo? {
        return detector.getCarAudioDeviceType()?.let {
            CarAudioDeviceInfo(
                name = it.name,
                type = when (it.type) {
                    CarAudioConnectionType.CARPLAY -> CarAudioConnectionType.CARPLAY
                    CarAudioConnectionType.BLUETOOTH -> CarAudioConnectionType.BLUETOOTH
                    CarAudioConnectionType.USB -> CarAudioConnectionType.USB
                    CarAudioConnectionType.AUX -> CarAudioConnectionType.AUX
                    else -> CarAudioConnectionType.UNKNOWN
                },
                isCarAudio = it.isCarAudio,
            )
        }
    }
}
