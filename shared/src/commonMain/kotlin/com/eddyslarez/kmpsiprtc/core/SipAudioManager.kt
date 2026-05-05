package com.eddyslarez.kmpsiprtc.core

import com.eddyslarez.kmpsiprtc.data.database.DatabaseManager
import com.eddyslarez.kmpsiprtc.data.database.entities.AppConfigEntity
import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.audio.AudioDeviceManager
import com.eddyslarez.kmpsiprtc.services.audio.AudioManager
import com.eddyslarez.kmpsiprtc.services.webrtc.WebRtcEventListener
import com.eddyslarez.kmpsiprtc.services.webrtc.WebRtcManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout


class SipAudioManager(
    private val audioManager: AudioManager,
    private val webRtcManager: WebRtcManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioDeviceManager = AudioDeviceManager()
    private var loadedConfig: AppConfigEntity? = null

    companion object {
        private const val TAG = "SipAudioManager"
    }

    /**
     * Inicializar componentes de audio
     */
    fun initialize() {
        webRtcManager.initialize()
        setupWebRtcAudioListener()
        refreshAudioDevices()
    }

    /**
     * Configurar listener de eventos de audio WebRTC
     */
    private fun setupWebRtcAudioListener() {
        webRtcManager.setListener(object : WebRtcEventListener {
            override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                // Manejado por el call manager
            }

            override fun onConnectionStateChange(state: WebRtcConnectionState) {
                // Manejado por el call manager
            }

            override fun onRemoteAudioTrack() {
                log.d(tag = TAG) { "Remote audio track received" }
            }

            override fun onAudioDeviceChanged(device: AudioDevice?) {
                log.d(tag = TAG) { "Audio device changed: ${device?.name}" }
                refreshAudioDevices()
            }
        })
    }

    /**
     * Preparar audio para llamada entrante
     */
    suspend fun prepareAudioForIncomingCall() {
        try {

            if (!webRtcManager.isInitialized()) {
                webRtcManager.initialize()

                // Esperar con timeout mÃ¡s largo
                var attempts = 0
                while (!webRtcManager.isInitialized() && attempts < 40) {
                    delay(250)
                    attempts++
                }

                if (!webRtcManager.isInitialized()) {
                    throw Exception("WebRTC failed to initialize within timeout")
                }

            }

            // âœ… CRÃTICO: Llamar a prepareAudioForIncomingCall en el manager
            webRtcManager.prepareAudioForIncomingCall()

            // Dar tiempo extra para que audio capture se configure
            delay(500)

            // âœ… Verificar que audio estÃ¡ listo
            if (!webRtcManager.isInitialized()) {
                throw Exception("WebRTC lost initialization during audio preparation")
            }


        } catch (e: Exception) {
            log.e(tag = TAG) { "âŒ Error preparing audio: ${e.message}" }
            throw e
        }
    }

    /**
     * Configurar audio para llamada saliente
     */
    suspend fun prepareAudioForOutgoingCall() {
        webRtcManager.setAudioEnabled(true)
    }

    /**
     * Obtener dispositivos de audio disponibles
     */
    fun getAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        return webRtcManager.getAllAudioDevices()
    }

    /**
     * Obtener dispositivos de audio actuales
     */
    fun getCurrentDevices(): Pair<AudioDevice?, AudioDevice?> {
        return Pair(
            webRtcManager.getCurrentInputDevice(),
            webRtcManager.getCurrentOutputDevice()
        )
    }

    /**
     * Refrescar lista de dispositivos de audio
     */
    fun refreshAudioDevices() {
        val (inputs, outputs) = webRtcManager.getAllAudioDevices()
        audioDeviceManager.updateDevices(inputs, outputs)
    }

    /**
     * Cambiar dispositivo de audio durante llamada
     */
    fun changeAudioDevice(device: AudioDevice) {
        scope.launch {
            val isInput = audioDeviceManager.inputDevices.value.contains(device)

            val success = if (isInput) {
                webRtcManager.changeAudioInputDeviceDuringCall(device)
            } else {
                webRtcManager.changeAudioOutputDeviceDuringCall(device)
            }

            if (success) {
                if (isInput) {
                    audioDeviceManager.selectInputDevice(device)
                } else {
                    audioDeviceManager.selectOutputDevice(device)
                }
            }
        }
    }

    /**
     * Silenciar/Desactivar silencio
     */
    fun toggleMute(): Boolean {
        val newMuteState = !webRtcManager.isMuted()
        webRtcManager.setMuted(newMuteState)
        return newMuteState
    }

    /**
     * Verificar si estÃ¡ silenciado
     */
    fun isMuted(): Boolean = webRtcManager.isMuted()

    /**
     * Habilitar/Deshabilitar audio
     */
    fun setAudioEnabled(enabled: Boolean) {
        webRtcManager.setAudioEnabled(enabled)
    }

    /**
     * Detener todos los ringtones
     */
    fun stopAllRingtones() {
        audioManager.stopAllRingtones()
    }

    /**
     * Reproducir ringtone de llamada entrante
     */
    fun playIncomingRingtone(syncVibration: Boolean = true) {
        audioManager.playRingtone(syncVibration)
    }

    /**
     * Reproducir ringtone de llamada saliente
     */
    fun playOutgoingRingtone() {
        audioManager.playOutgoingRingtone()
    }

    /**
     * Detener ringtone especÃ­fico
     */
    fun stopRingtone() {
        audioManager.stopRingtone()
    }

    /**
     * Detener ringtone de llamada saliente
     */
    fun stopOutgoingRingtone() {
        audioManager.stopOutgoingRingtone()
    }

    /**
     * Configurar ringtone de llamada entrante
     */
    fun saveIncomingRingtoneUri(uri: String, databaseManager: DatabaseManager?) {
        try {
            scope.launch {
                databaseManager?.updateIncomingRingtoneUri(uri)
                audioManager.setIncomingRingtone(uri)
                log.d(tag = TAG) { "Incoming ringtone URI saved to database: $uri" }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error saving incoming ringtone URI: ${e.message}" }
        }
    }

    /**
     * Configurar ringtone de llamada saliente
     */
    fun saveOutgoingRingtoneUri(uri: String, databaseManager: DatabaseManager?) {
        try {
            scope.launch {
                databaseManager?.updateOutgoingRingtoneUri(uri)
                audioManager.setOutgoingRingtone(uri)
                log.d(tag = TAG) { "Outgoing ringtone URI saved to database: $uri" }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error saving outgoing ringtone URI: ${e.message}" }
        }
    }

    /**
     * Configurar ambos ringtones
     */
    suspend fun saveRingtoneUris(incomingUri: String?, outgoingUri: String?, databaseManager: DatabaseManager?) {
        try {
            databaseManager?.updateRingtoneUris(incomingUri, outgoingUri)

            incomingUri?.let { audioManager.setIncomingRingtone(it) }
            outgoingUri?.let { audioManager.setOutgoingRingtone(it) }

            log.d(tag = TAG) { "Both ringtone URIs saved to database - Incoming: $incomingUri, Outgoing: $outgoingUri" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error saving ringtone URIs: ${e.message}" }
        }
    }

    /**
     * Cargar configuraciÃ³n de audio desde base de datos
     */
    fun loadAudioConfigFromDatabase(config: AppConfigEntity?) {
        loadedConfig = config
        config?.let { appConfig ->
            // Aplicar ringtones
            appConfig.incomingRingtoneUri?.let { string ->
                try {
                    val uri = (string)
                    audioManager.setIncomingRingtone(uri)
                    log.d(tag = TAG) { "Loaded incoming ringtone from DB: $string" }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error loading incoming ringtone URI: ${e.message}" }
                }
            }

            appConfig.outgoingRingtoneUri?.let { string ->
                try {
                    val uri = (string)
                    audioManager.setOutgoingRingtone(uri)
                    log.d(tag = TAG) { "Loaded outgoing ringtone from DB: $string" }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error loading outgoing ringtone URI: ${e.message}" }
                }
            }
        }
    }

    /**
     * Crear SDP offer para llamada saliente
     */
    suspend fun createOffer(): String = withTimeout(15_000L) {
        webRtcManager.createOffer()
    }

    /**
     * Crear SDP answer para llamada entrante
     */
    suspend fun createAnswer(remoteSdp: String): String = withTimeout(15_000L) {
        if (!webRtcManager.isInitialized()) {
            log.w(tag = TAG) { "[WARN] WebRTC not initialized in createAnswer, initializing..." }
            webRtcManager.initialize()
            delay(500)
        }

        webRtcManager.createAnswer(remoteSdp)
    }


    /**
     * Limpiar recursos de audio
     */
    fun dispose() {
        scope.cancel()
        audioManager.stopAllRingtones()
        webRtcManager.closePeerConnection()
    }

    /**
     * Verificar si WebRTC estÃ¡ inicializado
     */
    fun isWebRtcInitialized(): Boolean = webRtcManager.isInitialized()
}
