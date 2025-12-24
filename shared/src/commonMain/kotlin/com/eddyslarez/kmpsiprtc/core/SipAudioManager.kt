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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class SipAudioManager(
    private val audioManager: AudioManager,
    private val webRtcManager: WebRtcManager
) {
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
            println("[AUDIO_MANAGER] 🎤 Preparing audio for incoming call...")

            if (!webRtcManager.isInitialized()) {
                println("[AUDIO_MANAGER] ⚠️ WebRTC not initialized, initializing...")
                webRtcManager.initialize()

                // Esperar con timeout más largo
                var attempts = 0
                while (!webRtcManager.isInitialized() && attempts < 40) {
                    delay(250)
                    attempts++
                }

                if (!webRtcManager.isInitialized()) {
                    throw Exception("WebRTC failed to initialize within timeout")
                }

                println("[AUDIO_MANAGER] ✅ WebRTC initialized")
            }

            // ✅ CRÍTICO: Llamar a prepareAudioForIncomingCall en el manager
            webRtcManager.prepareAudioForIncomingCall()

            // Dar tiempo extra para que audio capture se configure
            delay(500)

            // ✅ Verificar que audio está listo
            if (!webRtcManager.isInitialized()) {
                throw Exception("WebRTC lost initialization during audio preparation")
            }

            println("[AUDIO_MANAGER] ✅ Audio prepared and verified")

        } catch (e: Exception) {
            println("[AUDIO_MANAGER] ❌ Error preparing audio: ${e.message}")
            log.e(tag = TAG) { "❌ Error preparing audio: ${e.message}" }
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
        CoroutineScope(Dispatchers.IO).launch {
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
     * Verificar si está silenciado
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
     * Detener ringtone específico
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
            CoroutineScope(Dispatchers.IO).launch {
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
            CoroutineScope(Dispatchers.IO).launch {
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
     * Cargar configuración de audio desde base de datos
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
    suspend fun createOffer(): String = webRtcManager.createOffer()

    /**
     * Crear SDP answer para llamada entrante
     */
    suspend fun createAnswer(remoteSdp: String): String {
        if (!webRtcManager.isInitialized()) {
            log.w(tag = TAG) { "⚠️ WebRTC not initialized in createAnswer, initializing..." }
            webRtcManager.initialize()
            delay(500)
        }

        return webRtcManager.createAnswer(remoteSdp)
    }


    /**
     * Limpiar recursos de audio
     */
    fun dispose() {
        audioManager.stopAllRingtones()
        webRtcManager.closePeerConnection()
    }

    /**
     * Verificar si WebRTC está inicializado
     */
    fun isWebRtcInitialized(): Boolean = webRtcManager.isInitialized()
}