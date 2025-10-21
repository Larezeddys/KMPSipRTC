package com.eddyslarez.kmpsiprtc.services.webrtc

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Integración completa de WebRTC + OpenAI Realtime
 * Maneja los dos flujos de audio asimétricos durante una llamada activa
 */
class DualStreamCallManager(
    private val webRtcManager: AndroidWebRtcManager,
    private val serverUrl: String
) {
    private val TAG = "DualStreamCallManager"
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Sesiones de traducción
    private var localAudioSession: RealtimeTranslationManager? = null  // A → OpenAI → B
    private var remoteAudioSession: RealtimeTranslationManager? = null  // B → OpenAI → Speaker A

    // Captura de audio local
    private var localAudioRecord: AudioRecord? = null
    private var isCapturingLocal = false
    private var localCaptureJob: Job? = null

    // Reproducción de audio procesado
    private var localPlaybackTrack: AudioTrack? = null
    private var localPlaybackJob: Job? = null

    // Buffer para audio remoto
    private var remoteAudioBuffer = ConcurrentLinkedQueue<ByteArray>()
    private var remoteProcessingJob: Job? = null

    // Inyección de audio en WebRTC (para enviar a B)
    private var audioInjectionTrack: AudioTrack? = null
    private var audioInjectionQueue = ConcurrentLinkedQueue<ByteArray>()
    private var audioInjectionJob: Job? = null

    private val SAMPLE_RATE = 24000
    private val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT) * 2

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState

    sealed class CallState {
        object Idle : CallState()
        object Initializing : CallState()
        object Ringing : CallState()
        object Active : CallState()
        data class Error(val message: String) : CallState()
        object Ended : CallState()
    }

    /**
     * INICIADOR: Crear oferta y configurar todo
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun initiateCall(
        sourceLanguage: String = "es",
        targetLanguage: String = "en"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initiating call...")
            _callState.value = CallState.Initializing

            // Inicializar WebRTC
            webRtcManager.initialize()
            setupWebRtcCallListener()

            // Crear sesión 1: Audio local → OpenAI → Remoto
            localAudioSession = RealtimeTranslationManager(serverUrl, webRtcManager).apply {
                val result = startTranslationSession(sourceLanguage, targetLanguage)
                if (result.isFailure) throw Exception("Failed local session: ${result.exceptionOrNull()?.message}")

                // Escuchar respuesta de OpenAI y enviar a B
                listenToLocalAudioResponse()
            }

            // Crear sesión 2: Audio remoto → OpenAI → Speaker local
            remoteAudioSession = RealtimeTranslationManager(serverUrl, webRtcManager).apply {
                val result = startTranslationSession(sourceLanguage, targetLanguage)
                if (result.isFailure) throw Exception("Failed remote session: ${result.exceptionOrNull()?.message}")

                // Escuchar respuesta de OpenAI para reproducir localmente
                listenToRemoteAudioResponse()
            }

            // Iniciar captura de audio local
            startLocalAudioCapture()

            // Iniciar procesamiento de audio remoto
            startRemoteAudioProcessing()

            // Iniciar reproducción local
            startLocalAudioPlayback()

            // Iniciar inyección de audio en WebRTC
            startAudioInjection()

            // Crear oferta
            val offer = webRtcManager.createOffer()

            _callState.value = CallState.Ringing
            Log.d(TAG, "Call initiated, waiting for answer")

            Result.success(offer)
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating call: ${e.message}", e)
            _callState.value = CallState.Error(e.message ?: "Unknown error")
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * RECEPTOR: Responder a la oferta
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun answerCall(
        offerSdp: String,
        sourceLanguage: String = "es",
        targetLanguage: String = "en"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Answering call...")
            _callState.value = CallState.Initializing

            webRtcManager.initialize()
            setupWebRtcCallListener()

            // Crear ambas sesiones
            localAudioSession = RealtimeTranslationManager(serverUrl, webRtcManager).apply {
                val result = startTranslationSession(sourceLanguage, targetLanguage)
                if (result.isFailure) throw Exception("Failed local session")
                listenToLocalAudioResponse()
            }

            remoteAudioSession = RealtimeTranslationManager(serverUrl, webRtcManager).apply {
                val result = startTranslationSession(sourceLanguage, targetLanguage)
                if (result.isFailure) throw Exception("Failed remote session")
                listenToRemoteAudioResponse()
            }

            startLocalAudioCapture()
            startRemoteAudioProcessing()
            startLocalAudioPlayback()
            startAudioInjection()

            // Procesar oferta y crear respuesta
            val answer = webRtcManager.createAnswer(offerSdp)

            _callState.value = CallState.Active
            Log.d(TAG, "Call answered")

            Result.success(answer)
        } catch (e: Exception) {
            Log.e(TAG, "Error answering call: ${e.message}", e)
            _callState.value = CallState.Error(e.message ?: "Unknown error")
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * Actualizar cuando se establece la conexión
     */
    fun onCallConnected() {
        Log.d(TAG, "Call connected!")
        _callState.value = CallState.Active
    }

    /**
     * Configurar listeners de WebRTC para capturar audio remoto
     */
    private fun setupWebRtcCallListener() {
        webRtcManager.setListener(object : WebRtcEventListener {
            override fun onRemoteAudioTrack() {
                Log.d(TAG, "Remote audio track established")
                // Aquí necesitas un hook para capturar el audio remoto
                // y pasarlo a onRemoteAudioReceived()
            }

            override fun onAudioDeviceChanged(device: AudioDevice?) {
                Log.d(TAG, "Audio device: ${device?.name}")
            }

            override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                // Manejar ICE candidates
            }

            override fun onConnectionStateChange(state: WebRtcConnectionState) {
                Log.d(TAG, "Connection state: $state")
                if (state == WebRtcConnectionState.CONNECTED) {
                    onCallConnected()
                }
            }
        })
    }

    /**
     * ============================================
     * FLUJO 1: Audio local A → OpenAI → B
     * ============================================
     */

    /**
     * Capturar audio local
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startLocalAudioCapture() {
        if (isCapturingLocal) {
            Log.w(TAG, "Audio capture already running")
            return
        }

        try {
            localAudioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )

            if (localAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            localAudioRecord?.startRecording()
            isCapturingLocal = true

            localCaptureJob = coroutineScope.launch {
                captureLocalAudio()
            }

            Log.d(TAG, "Local audio capture started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting local capture: ${e.message}", e)
        }
    }

    /**
     * Loop de captura: leer audio local, enviar a OpenAI
     */
    private suspend fun captureLocalAudio() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(BUFFER_SIZE)
        var silentFrames = 0
        val silentThreshold = 500
        val maxSilentFrames = 10

        while (isCapturingLocal && _callState.value is CallState.Active) {
            val read = localAudioRecord?.read(buffer, 0, buffer.size) ?: 0

            if (read > 0) {
                val audioLevel = calculateAudioLevel(buffer, read)

                if (audioLevel < silentThreshold) {
                    silentFrames++
                } else {
                    silentFrames = 0
                }

                // FLUJO 1: Enviar a OpenAI
                localAudioSession?.sendAudio(buffer.copyOf(read))

                // Commit en silencio
                if (silentFrames >= maxSilentFrames) {
                    localAudioSession?.commitAudioBuffer()
                    silentFrames = 0
                }
            }

            delay(20)
        }
    }

    /**
     * FLUJO 1: Escuchar respuesta de OpenAI y encolar para WebRTC
     */
    private fun listenToLocalAudioResponse() {
        coroutineScope.launch {
            localAudioSession?.processedAudioFlow?.collect { audioChunk ->
                Log.d(TAG, "FLUJO 1: Received processed audio (${audioChunk.size} bytes) → sending to B via WebRTC")
                // Encolar para inyección en WebRTC
                audioInjectionQueue.offer(audioChunk)
            }
        }
    }

    /**
     * ============================================
     * FLUJO 2: Audio remoto B → OpenAI → Speaker A
     * ============================================
     */

    /**
     * Interceptar audio remoto de WebRTC (de B)
     * IMPORTANTE: Necesitas un hook en AndroidWebRtcManager
     */
    fun onRemoteAudioReceived(audioBytes: ByteArray) {
        Log.d(TAG, "FLUJO 2: Received remote audio from B (${audioBytes.size} bytes)")
        remoteAudioBuffer.offer(audioBytes)
    }

    /**
     * Procesar audio remoto: enviar a OpenAI
     */
    private fun startRemoteAudioProcessing() {
        remoteProcessingJob = coroutineScope.launch(Dispatchers.Default) {
            while (isActive && _callState.value is CallState.Active) {
                val audioChunk = remoteAudioBuffer.poll()
                if (audioChunk != null) {
                    Log.d(TAG, "FLUJO 2: Processing remote audio (${audioChunk.size} bytes) via OpenAI")
                    // Enviar a OpenAI para procesar
                    remoteAudioSession?.sendAudio(audioChunk)
                } else {
                    delay(10)
                }
            }
        }
    }

    /**
     * FLUJO 2: Escuchar respuesta de OpenAI para reproducir localmente
     */
    private fun listenToRemoteAudioResponse() {
        coroutineScope.launch {
            remoteAudioSession?.processedAudioFlow?.collect { audioChunk ->
                Log.d(TAG, "FLUJO 2: Received processed audio (${audioChunk.size} bytes) → playing locally")
                // Encolar para reproducción local
                localPlaybackTrack?.write(audioChunk, 0, audioChunk.size)
            }
        }
    }

    /**
     * Iniciar reproducción de audio procesado (FLUJO 2)
     */
    private fun startLocalAudioPlayback() {
        try {
            localPlaybackTrack = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .setEncoding(AUDIO_FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(BUFFER_SIZE)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            localPlaybackTrack?.play()
            Log.d(TAG, "Local audio playback initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing local playback: ${e.message}", e)
        }
    }

    /**
     * ============================================
     * Inyección de audio en WebRTC (FLUJO 1)
     * ============================================
     */

    /**
     * Iniciar inyección de audio procesado en WebRTC
     * Este audio será enviado a Usuario B
     */
    private fun startAudioInjection() {
        try {
            audioInjectionTrack = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .setEncoding(AUDIO_FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(BUFFER_SIZE)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioInjectionTrack?.play()

            audioInjectionJob = coroutineScope.launch(Dispatchers.Default) {
                while (isActive && _callState.value is CallState.Active) {
                    val audioChunk = audioInjectionQueue.poll()
                    if (audioChunk != null) {
                        Log.d(TAG, "FLUJO 1: Injecting audio into WebRTC (${audioChunk.size} bytes)")
                        audioInjectionTrack?.write(audioChunk, 0, audioChunk.size)
                    } else {
                        delay(5)
                    }
                }
            }

            Log.d(TAG, "Audio injection for WebRTC started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio injection: ${e.message}", e)
        }
    }

    /**
     * ============================================
     * Utilidades
     * ============================================
     */

    private fun calculateAudioLevel(buffer: ByteArray, size: Int): Int {
        var sum = 0L
        for (i in 0 until size step 2) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            sum += sample * sample
        }
        return Math.sqrt(sum.toDouble() / (size / 2)).toInt()
    }

    /**
     * Finalizar llamada
     */
    fun endCall() {
        Log.d(TAG, "Ending call...")
        _callState.value = CallState.Ended
        cleanup()
    }

    /**
     * Mutar/desmutar
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun setMuted(muted: Boolean) {
        Log.d(TAG, "Mute: $muted")
        webRtcManager.setMuted(muted)
        isCapturingLocal = !muted
        if (!muted) {
            startLocalAudioCapture()
        }
    }

    /**
     * Cambiar dispositivo de audio
     */
    fun setAudioDevice(device: AudioDevice) {
        Log.d(TAG, "Setting audio device: ${device.name}")
//        webRtcManager.setAudioDevice(device)
    }

    /**
     * Limpiar recursos
     */
    private fun cleanup() {
        Log.d(TAG, "Cleaning up call resources")

        // Detener captura
        isCapturingLocal = false
        localCaptureJob?.cancel()
        localAudioRecord?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio record: ${e.message}")
            }
        }
        localAudioRecord = null

        // Detener procesamiento remoto
        remoteProcessingJob?.cancel()

        // Detener reproducción
        localPlaybackTrack?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping playback: ${e.message}")
            }
        }
        localPlaybackTrack = null

        // Detener inyección
        audioInjectionJob?.cancel()
        audioInjectionTrack?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping injection: ${e.message}")
            }
        }
        audioInjectionTrack = null

        // Desconectar sesiones OpenAI
        localAudioSession?.disconnect()
        remoteAudioSession?.disconnect()

        // Cerrar WebRTC
        webRtcManager.closePeerConnection()
    }

    fun dispose() {
        cleanup()
        coroutineScope.cancel()
    }
}