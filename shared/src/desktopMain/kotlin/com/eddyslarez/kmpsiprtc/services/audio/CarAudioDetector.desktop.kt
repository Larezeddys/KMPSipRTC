package com.eddyslarez.kmpsiprtc.services.audio

actual class CarAudioDetector {
    private val detector = DesktopCarAudioDetector()

    actual fun isCarAudioConnected(): Boolean {
        return detector.isCarAudioConnected()
    }

    actual fun getCarAudioDeviceInfo(): CarAudioDeviceInfo? {
        return detector.getCarAudioDeviceInfo()?.let {
            CarAudioDeviceInfo(
                name = it.name,
                type = when (it.type) {
                    AudioDeviceConnectionType.USB -> CarAudioConnectionType.USB
                    AudioDeviceConnectionType.BLUETOOTH -> CarAudioConnectionType.BLUETOOTH
                    AudioDeviceConnectionType.AUX -> CarAudioConnectionType.AUX
                    else -> CarAudioConnectionType.UNKNOWN
                },
                isCarAudio = it.isCarAudio
            )
        }
    }
}