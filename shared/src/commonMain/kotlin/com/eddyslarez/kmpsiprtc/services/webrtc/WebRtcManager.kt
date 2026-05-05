package com.eddyslarez.kmpsiprtc.services.webrtc

import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.AudioUnit
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitTypes
import com.eddyslarez.kmpsiprtc.data.models.RecordingResult
import com.eddyslarez.kmpsiprtc.data.models.SdpType
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import com.eddyslarez.kmpsiprtc.services.audio.AudioStreamListener

interface WebRtcManager {
    /**
     * Initialize the WebRTC subsystem
     */
    fun initialize()
    fun closePeerConnection()
    /**
     * Clean up and release WebRTC resources
     */
    fun dispose()

    /**
     * Create an SDP offer for starting a call
     * @return The SDP offer string
     */
    suspend fun createOffer(): String

    fun setActiveAudioRoute(audioUnitType: AudioUnitTypes): Boolean

    fun getActiveAudioRoute(): AudioUnitTypes?

    fun getAvailableAudioRoutes(): Set<AudioUnitTypes>
    fun startCallRecording(callId: String)
    suspend fun stopCallRecording(): RecordingResult?
    fun isRecordingCall(): Boolean
    /**
     * Create an SDP answer in response to an offer
     * @param accountInfo The current account information
     * @param offerSdp The SDP offer from the remote party
     * @return The SDP answer string
     */
    suspend fun createAnswer(offerSdp: String): String

    /**
     * Set the remote description (offer or answer)
     * @param sdp The remote SDP string
     * @param type The SDP type (offer or answer)
     */
    suspend fun setRemoteDescription(
        sdp: String,
        type: SdpType,
    )
    /**
     * Add an ICE candidate received from the remote party
     * @param candidate The ICE candidate string
     * @param sdpMid The media ID
     * @param sdpMLineIndex The media line index
     */
    suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?)

    fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>>

    fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean
    fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean
    fun getCurrentInputDevice(): AudioDevice?
    fun getCurrentOutputDevice(): AudioDevice?
    fun onBluetoothConnectionChanged(isConnected: Boolean)
    fun refreshAudioDevicesWithBluetoothPriority()
    fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean
    fun getAvailableAudioUnits(): Set<AudioUnit>
    fun getCurrentActiveAudioUnit(): AudioUnit?
    /**
     * Enable or disable the local audio track
     * @param enabled Whether audio should be enabled
     */
    fun setAudioEnabled(enabled: Boolean)

    fun setMuted(muted: Boolean)
    fun isMuted(): Boolean
    fun setRemoteAudioEnabled(enabled: Boolean)
    fun isRemoteAudioEnabled(): Boolean
    fun getLocalDescription(): String?
    fun diagnoseAudioIssues(): String
    fun prepareAudioForCall()
    /**
     * Get current connection state
     * @return The connection state
     */
    fun getConnectionState(): WebRtcConnectionState

    suspend fun setMediaDirection(direction: MediaDirection)
    enum class MediaDirection { SENDRECV, SENDONLY, RECVONLY, INACTIVE }

    /**
     * Set a listener for WebRTC events
     * @param listener The WebRTC event listener
     */
    fun setListener(listener: WebRtcEventListener?)
    fun prepareAudioForIncomingCall()

    /**
     * Android-only: activa el modo "gestionado por TelecomManager".
     * Cuando está activo, el AudioController interno NO toca AudioManager.mode,
     * audio focus, speakerphone ni Bluetooth SCO — deja que el framework Telecom
     * los gestione. Imprescindible cuando la app usa ConnectionService/VoipConnection
     * para que WebRTC no pelee con Telecom por el audio (y el micrófono funcione
     * en llamadas entrantes desde push/segundo plano).
     *
     * @param managed true cuando hay una llamada activa gestionada por Telecom
     * @param routeHandler opcional: handler que traduce setActiveRoute() a
     *        Connection.setAudioRoute() en la capa app. Si es null, los cambios
     *        de ruta se ignoran en modo telecom-managed.
     */
    fun setAndroidTelecomManaged(
        managed: Boolean,
        routeHandler: ((AudioUnitTypes) -> Boolean)? = null,
    ) { /* default no-op para iOS/Desktop */ }
    suspend fun applyModifiedSdp(modifiedSdp: String): Boolean
    fun isInitialized(): Boolean

    /**
     * Send DTMF tones via RTP (RFC 2833)
     * @param tones The DTMF tones to send (0-9, *, #, A-D)
     * @param duration Duration in milliseconds for each tone (optional, default 100ms)
     * @param gap Gap between tones in milliseconds (optional, default 70ms)
     * @return true if successfully started sending tones, false otherwise
     */
    fun sendDtmfTones(tones: String, duration: Int = 100, gap: Int = 70): Boolean

    /**
     * Seleccionar dispositivo de entrada de audio por nombre (solo Desktop)
     * @param deviceName Nombre del dispositivo de entrada
     * @return true si se seleccionó correctamente
     */
    fun selectAudioInputDeviceByName(deviceName: String): Boolean

    /**
     * Seleccionar dispositivo de salida de audio por nombre (solo Desktop)
     * @param deviceName Nombre del dispositivo de salida
     * @return true si se seleccionó correctamente
     */
    fun selectAudioOutputDeviceByName(deviceName: String): Boolean

    // ==================== STREAMING EN TIEMPO REAL ====================

    /**
     * Configurar listener para recibir audio en tiempo real
     * @param listener Listener que recibirá datos PCM crudos, o null para desregistrar
     */
    fun setAudioStreamListener(listener: AudioStreamListener?)

    /**
     * Iniciar streaming de audio en tiempo real (independiente de grabación)
     * @param callId Identificador único de la llamada
     */
    fun startAudioStreaming(callId: String)

    /**
     * Detener streaming de audio en tiempo real
     */
    fun stopAudioStreaming()

    /**
     * Verificar si el streaming de audio está activo
     */
    fun isAudioStreaming(): Boolean

    // ==================== INYECCIÓN DE AUDIO PARA TRADUCCIÓN ====================

    /**
     * Habilitar o deshabilitar el audio local (micrófono) que se envía al peer remoto.
     * Cuando se deshabilita, el micrófono real se silencia en el pipeline de WebRTC
     * pero el streaming/captura de audio local continúa funcionando para poder
     * enviar el audio al servidor de traducción.
     *
     * Diferencia con setMuted(): setMuted() es para el usuario (mute manual),
     * setLocalAudioEnabled() es para el sistema de traducción (reemplazar mic con audio traducido).
     *
     * @param enabled true = audio del micrófono real se envía al remoto (normal),
     *                false = micrófono silenciado en WebRTC (para inyectar audio traducido)
     */
    fun setLocalAudioEnabled(enabled: Boolean)

    /**
     * Verificar si el audio local está habilitado
     */
    fun isLocalAudioEnabled(): Boolean

    /**
     * Inyectar datos de audio PCM que se enviarán al peer remoto en lugar del micrófono.
     * Solo funciona cuando setLocalAudioEnabled(false) ha sido llamado previamente.
     *
     * El audio inyectado reemplaza completamente lo que el remoto escucha.
     * Se usa para enviar el audio traducido por IA al otro participante de la llamada.
     *
     * @param pcmData Audio PCM 16-bit little-endian, mono
     * @param sampleRate Frecuencia de muestreo del audio (ej: 24000)
     * @param channels Número de canales (normalmente 1 = mono)
     * @param bitsPerSample Bits por muestra (normalmente 16)
     */
    fun injectLocalAudio(pcmData: ByteArray, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16)

    /**
     * Inyectar datos de audio PCM que se reproducirán localmente (en el speaker del usuario)
     * en lugar del audio remoto original.
     * Solo funciona cuando setRemoteAudioEnabled(false) ha sido llamado previamente.
     *
     * Se usa para reproducir la voz traducida del participante remoto.
     * Nota: En la mayoría de plataformas esto se maneja via TranslationAudioPlayer
     * directamente, pero esta API permite inyección a nivel de WebRTC si es necesario.
     *
     * @param pcmData Audio PCM 16-bit little-endian, mono
     * @param sampleRate Frecuencia de muestreo del audio (ej: 24000)
     * @param channels Número de canales (normalmente 1 = mono)
     * @param bitsPerSample Bits por muestra (normalmente 16)
     */
    fun injectRemoteAudio(pcmData: ByteArray, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16)
}
interface WebRtcEventListener {
    /**
     * Called when a new ICE candidate is generated
     */
    fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int)

    /**
     * Called when the connection state changes
     */
    fun onConnectionStateChange(state: WebRtcConnectionState)

    /**
     * Called when an audio track is received from the remote peer
     */
    fun onRemoteAudioTrack()

    fun onAudioDeviceChanged(device: AudioDevice?)
}


expect fun createWebRtcManager(): WebRtcManager
