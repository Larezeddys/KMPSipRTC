package com.eddyslarez.kmpsiprtc.services.audio

/**
 * Interface para recibir audio en tiempo real como buffers PCM crudos.
 * Permite enviar audio de llamada a un servidor propio o a una IA.
 * Independiente del sistema de grabación a archivo.
 */
interface AudioStreamListener {
    /**
     * Llamado cuando hay nuevos datos de audio local (micrófono) disponibles
     * @param data PCM 16-bit crudo
     * @param sampleRate Frecuencia de muestreo (ej: 8000, 24000, 48000)
     * @param channels Número de canales (1 = mono)
     * @param bitsPerSample Bits por muestra (16)
     */
    fun onLocalAudioData(data: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int)

    /**
     * Llamado cuando hay nuevos datos de audio remoto (del peer) disponibles
     * @param data PCM 16-bit crudo
     * @param sampleRate Frecuencia de muestreo
     * @param channels Número de canales (1 = mono)
     * @param bitsPerSample Bits por muestra (16)
     */
    fun onRemoteAudioData(data: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int)

    /**
     * Llamado cuando ocurre un error durante el streaming
     * @param error Descripción del error
     */
    fun onStreamError(error: String)
}
