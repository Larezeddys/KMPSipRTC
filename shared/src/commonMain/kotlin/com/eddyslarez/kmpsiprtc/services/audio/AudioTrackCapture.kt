package com.eddyslarez.kmpsiprtc.services.audio

/**
 * Interface común para capturar audio de tracks remotos
 */
interface AudioTrackCapture {
    /**
     * Inicia la captura de audio del track
     */
    fun startCapture()

    /**
     * Detiene la captura de audio
     */
    fun stopCapture()

    /**
     * Verifica si está capturando
     */
    fun isCapturing(): Boolean
}

/**
 * Callback común para recibir datos de audio capturados
 */
interface AudioCaptureCallback {
    fun onAudioData(
        data: ByteArray,
        bitsPerSample: Int,
        sampleRate: Int,
        channels: Int,
        frames: Int,
        timestampMs: Long
    )
}

/**
 * Factory para crear capturadores según la plataforma
 */
expect fun createRemoteAudioCapture(
    remoteTrack: Any, // El tipo real dependerá de cada plataforma
    callback: AudioCaptureCallback
): AudioTrackCapture?
