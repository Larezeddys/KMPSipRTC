package com.eddyslarez.kmpsiprtc.services.webrtc

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.SdpType
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue
/**
 * Orquestador corregido para manejar dos flujos de audio asimétricos:
 *
 * FLUJO 1 (A → OpenAI → B):
 * - Usuario A captura audio local
 * - Envía a OpenAI para traducción/modificación
 * - OpenAI genera respuesta (audio)
 * - Se envía a Usuario B vía WebRTC
 *
 * FLUJO 2 (B → OpenAI → Speaker A):
 * - Usuario A recibe audio remoto de B vía WebRTC
 * - Envía a OpenAI para traducción/modificación
 * - OpenAI genera respuesta (audio)
 * - Se reproduce localmente en el speaker de A (NO se envía a B)
 */
//class DualStreamAudioOrchestrator(
//    private val webRtcManager: AndroidWebRtcManager,
//    private val serverUrl: String
//) {
//    private val TAG = "DualStreamOrchestrator"
//    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
//
//    // Flujo 1: Audio local de A → OpenAI → B (enviado a remoto vía WebRTC)
//    private var localAudioSession: RealtimeTranslationManager? = null
//
//    // Flujo 2: Audio remoto de B → OpenAI → Speaker local de A (reproducción local)
//    private var remoteAudioSession: RealtimeTranslationManager? = null
//
//    // Captura de audio local (A)
//    private var localAudioRecord: AudioRecord? = null
//    private var isCapturingLocal = false
//    private var localCaptureJob: Job? = null
//
//    // Reproducción de audio procesado por OpenAI (respuesta a audio de B)
//    private var localPlaybackTrack: AudioTrack? = null
//    private var localPlaybackQueue = ConcurrentLinkedQueue<ByteArray>()
//    private var localPlaybackJob: Job? = null
//
//    // Buffer para audio remoto recibido de B
//    private var remoteAudioBuffer = ConcurrentLinkedQueue<ByteArray>()
//    private var remoteProcessingJob: Job? = null
//
//    private val SAMPLE_RATE = 24000
//    private val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
//    private val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
//    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
//    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT) * 2
//
//    private val _orchestratorState = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
//    val orchestratorState: StateFlow<OrchestratorState> = _orchestratorState
//
//    sealed class OrchestratorState {
//        object Idle : OrchestratorState()
//        object Initializing : OrchestratorState()
//        object Active : OrchestratorState()
//        data class Error(val message: String) : OrchestratorState()
//    }
//
//    /**
//     * Iniciar como Usuario A (captura local + recibe remoto)
//     */
//    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
//    suspend fun startAsUserA(
//        sourceLanguage: String = "es",
//        targetLanguage: String = "en",
//        isInitiator: Boolean = true
//    ): Result<String> = withContext(Dispatchers.IO) {
//        try {
//            Log.d(TAG, "Starting as User A")
//            _orchestratorState.value = OrchestratorState.Initializing
//
//            // Inicializar WebRTC
//            webRtcManager.initialize()
//            setupWebRtcListener()
//
//            // FLUJO 1: Audio local A → OpenAI → enviado a B
//            localAudioSession = RealtimeTranslationManager(serverUrl, webRtcManager).apply {
//                val sessionResult = startTranslationSession(sourceLanguage, targetLanguage)
//                if (sessionResult.isFailure) {
//                    throw Exception("Failed to create local audio session: ${sessionResult.exceptionOrNull()?.message}")
//                }
//                // Escuchar respuesta de OpenAI para enviar a B
//                listenToLocalAudioResponse()
//            }
//
//            // FLUJO 2: Audio remoto B → OpenAI → reproducción local
//            remoteAudioSession = RealtimeTranslationManager(serverUrl, webRtcManager).apply {
//                val sessionResult = startTranslationSession(sourceLanguage, targetLanguage)
//                if (sessionResult.isFailure) {
//                    throw Exception("Failed to create remote audio session: ${sessionResult.exceptionOrNull()?.message}")
//                }
//                // Escuchar respuesta de OpenAI para reproducir localmente
//                listenToRemoteAudioResponse()
//            }
//
//            // Iniciar captura de audio local
//            startLocalAudioCapture()
//
//            // Iniciar procesamiento de audio remoto recibido
//            startRemoteAudioProcessing()
//
//            // Iniciar reproducción de audio procesado localmente
//            startLocalAudioPlayback()
//
//            // Crear oferta WebRTC
//            val sdp = if (isInitiator) {
//                webRtcManager.createOffer()
//            } else {
//                throw IllegalStateException("Use answerAsUserA for answering")
//            }
//
//            _orchestratorState.value = OrchestratorState.Active
//            Log.d(TAG, "User A initialized successfully")
//
//            Result.success(sdp)
//        } catch (e: Exception) {
//            Log.e(TAG, "Error starting as User A: ${e.message}", e)
//            _orchestratorState.value = OrchestratorState.Error(e.message ?: "Unknown error")
//            cleanup()
//            Result.failure(e)
//        }
//    }
//
//    /**
//     * Responder como Usuario A
//     */
//    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
//    suspend fun answerAsUserA(
//        offerSdp: String,
//        sourceLanguage: String = "es",
//        targetLanguage: String = "en"
//    ): Result<String> = withContext(Dispatchers.IO) {
//        try {
//            Log.d(TAG, "Answering as User A")
//            _orchestratorState.value = OrchestratorState.Initializing
//
//            webRtcManager.initialize()
//            setupWebRtcListener()
//
//            // FLUJO 1: Audio local
//            localAudioSession = RealtimeTranslationManager(serverUrl, webRtcManager).apply {
//                val sessionResult = startTranslationSession(sourceLanguage, targetLanguage)
//                if (sessionResult.isFailure) {
//                    throw Exception("Failed to create local audio session")
//                }
//                listenToLocalAudioResponse()
//            }
//
//            // FLUJO 2: Audio remoto
//            remoteAudioSession = RealtimeTranslationManager(serverUrl, webRtcManager).apply {
//                val sessionResult = startTranslationSession(sourceLanguage, targetLanguage)
//                if (sessionResult.isFailure) {
//                    throw Exception("Failed to create remote audio session")
//                }
//                listenToRemoteAudioResponse()
//            }
//
//            startLocalAudioCapture()
//            startRemoteAudioProcessing()
//            startLocalAudioPlayback()
//
//            val answerSdp = webRtcManager.createAnswer(offerSdp)
//
//            _orchestratorState.value = OrchestratorState.Active
//            Log.d(TAG, "User A answer created successfully")
//
//            Result.success(answerSdp)
//        } catch (e: Exception) {
//            Log.e(TAG, "Error answering as User A: ${e.message}", e)
//            _orchestratorState.value = OrchestratorState.Error(e.message ?: "Unknown error")
//            cleanup()
//            Result.failure(e)
//        }
//    }
//
//    /**
//     * Configurar listener de WebRTC para capturar audio remoto de B
//     */
//    private fun setupWebRtcListener() {
//        webRtcManager.setListener(object : WebRtcEventListener {
//            override fun onRemoteAudioTrack() {
//                Log.d(TAG, "Remote audio track from User B received")
//            }
//
//            override fun onAudioDeviceChanged(device: AudioDevice?) {
//                Log.d(TAG, "Audio device changed: ${device?.name}")
//            }
//
//            override fun onIceCandidate(
//                candidate: String,
//                sdpMid: String,
//                sdpMLineIndex: Int
//            ) {
//                // Manejar ICE candidates
//            }
//
//            override fun onConnectionStateChange(state: WebRtcConnectionState) {
//                Log.d(TAG, "WebRTC connection state: $state")
//            }
//        })
//    }
//
//    /**
//     * FLUJO 1: Captura audio local de A y lo envía a OpenAI
//     */
//    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
//    private fun startLocalAudioCapture() {
//        if (isCapturingLocal) {
//            Log.w(TAG, "Local audio capture already running")
//            return
//        }
//
//        try {
//            localAudioRecord = AudioRecord(
//                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
//                SAMPLE_RATE,
//                CHANNEL_CONFIG_IN,
//                AUDIO_FORMAT,
//                BUFFER_SIZE
//            )
//
//            if (localAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
//                Log.e(TAG, "Local AudioRecord initialization failed")
//                return
//            }
//
//            localAudioRecord?.startRecording()
//            isCapturingLocal = true
//
//            localCaptureJob = coroutineScope.launch {
//                captureLocalAudio()
//            }
//
//            Log.d(TAG, "Local audio capture started")
//        } catch (e: Exception) {
//            Log.e(TAG, "Error starting local audio capture: ${e.message}", e)
//        }
//    }
//
//    /**
//     * Captura audio local, lo envía a OpenAI para procesar
//     * La respuesta se envía a B vía WebRTC
//     */
//    private suspend fun captureLocalAudio() = withContext(Dispatchers.IO) {
//        val buffer = ByteArray(BUFFER_SIZE)
//        var silentFrames = 0
//        val silentThreshold = 500
//        val maxSilentFrames = 10
//
//        while (isCapturingLocal) {
//            val read = localAudioRecord?.read(buffer, 0, buffer.size) ?: 0
//
//            if (read > 0) {
//                val audioLevel = calculateAudioLevel(buffer, read)
//
//                if (audioLevel < silentThreshold) {
//                    silentFrames++
//                } else {
//                    silentFrames = 0
//                }
//
//                // Enviar audio local a OpenAI (FLUJO 1)
//                localAudioSession?.sendAudio(buffer.copyOf(read))
//
//                // Commit cuando hay silencio prolongado
//                if (silentFrames >= maxSilentFrames) {
//                    localAudioSession?.commitAudioBuffer()
//                    silentFrames = 0
//                }
//            }
//
//            delay(20)
//        }
//    }
//
//    /**
//     * FLUJO 1: Escucha respuesta de OpenAI y la envía a Usuario B vía WebRTC
//     *
//     * NOTA: Necesitas exponer un Flow o callback en RealtimeTranslationManager
//     * que emita los chunks de audio procesados (response.audio.delta)
//     */
//    private fun listenToLocalAudioResponse() {
//        coroutineScope.launch {
//            localAudioSession?.let { session ->
//                // Aquí necesitas implementar en RealtimeTranslationManager:
//                // una forma de exponer el audio procesado (ej: processedAudioFlow)
//
//                // Pseudocódigo - adapta según tu implementación de RealtimeTranslationManager:
//                /*
//                session.processedAudioFlow.collect { audioChunk ->
//                    // Enviar audio procesado a Usuario B vía WebRTC
//                    sendAudioToRemotePeer(audioChunk)
//                }
//                */
//
//                Log.d(TAG, "FLUJO 1: Listening to local audio responses for remote peer")
//            }
//        }
//    }
//
//    /**
//     * FLUJO 2: Procesa audio remoto recibido de B
//     * Lo envía a OpenAI para procesar
//     */
//    private fun startRemoteAudioProcessing() {
//        remoteProcessingJob = coroutineScope.launch(Dispatchers.Default) {
//            while (isActive) {
//                val audioChunk = remoteAudioBuffer.poll()
//                if (audioChunk != null) {
//                    Log.d(TAG, "FLUJO 2: Processing remote audio from B (${audioChunk.size} bytes)")
//                    // Enviar audio remoto a OpenAI para procesar
//                    remoteAudioSession?.sendAudio(audioChunk)
//                } else {
//                    delay(10)
//                }
//            }
//        }
//    }
//
//    /**
//     * FLUJO 2: Escucha respuesta de OpenAI (audio procesado de B)
//     * y lo añade a la cola de reproducción local
//     */
//    private fun listenToRemoteAudioResponse() {
//        coroutineScope.launch {
//            remoteAudioSession?.let { session ->
//                // Pseudocódigo - adapta según tu implementación:
//                /*
//                session.processedAudioFlow.collect { audioChunk ->
//                    // Encolar para reproducción local
//                    localPlaybackQueue.offer(audioChunk)
//                    Log.d(TAG, "FLUJO 2: Queued processed audio for local playback")
//                }
//                */
//
//                Log.d(TAG, "FLUJO 2: Listening to remote audio responses for local playback")
//            }
//        }
//    }
//
//    /**
//     * Intercepta audio remoto de WebRTC (audio de B)
//     * IMPORTANTE: Necesitas un hook en AndroidWebRtcManager
//     * para capturar el audio del RemoteAudioTrack
//     */
//    fun onRemoteAudioReceived(audioBytes: ByteArray) {
//        Log.d(TAG, "Received remote audio from B (${audioBytes.size} bytes)")
//        remoteAudioBuffer.offer(audioBytes)
//    }
//
//    /**
//     * FLUJO 2: Reproducción de audio procesado localmente
//     * (respuesta de OpenAI a lo que dijo B)
//     */
//    private fun startLocalAudioPlayback() {
//        try {
//            localPlaybackTrack = AudioTrack.Builder()
//                .setAudioFormat(
//                    AudioFormat.Builder()
//                        .setSampleRate(SAMPLE_RATE)
//                        .setChannelMask(CHANNEL_CONFIG_OUT)
//                        .setEncoding(AUDIO_FORMAT)
//                        .build()
//                )
//                .setBufferSizeInBytes(BUFFER_SIZE)
//                .build()
//
//            localPlaybackTrack?.play()
//
//            localPlaybackJob = coroutineScope.launch(Dispatchers.Default) {
//                while (isActive) {
//                    val audioChunk = localPlaybackQueue.poll()
//                    if (audioChunk != null) {
//                        Log.d(TAG, "FLUJO 2: Playing local audio (${audioChunk.size} bytes)")
//                        localPlaybackTrack?.write(audioChunk, 0, audioChunk.size)
//                    } else {
//                        delay(5)
//                    }
//                }
//            }
//
//            Log.d(TAG, "Local audio playback started")
//        } catch (e: Exception) {
//            Log.e(TAG, "Error starting local audio playback: ${e.message}", e)
//        }
//    }
//
//    /**
//     * FLUJO 1: Callback para enviar audio procesado a Usuario B
//     * (llamado cuando OpenAI procesa el audio de A)
//     */
//    fun onLocalProcessedAudioReady(audioBytes: ByteArray) {
//        Log.d(TAG, "FLUJO 1: Local processed audio ready, sending to B (${audioBytes.size} bytes)")
//        sendAudioToRemotePeer(audioBytes)
//    }
//
//    /**
//     * FLUJO 2: Callback para reproducir audio procesado localmente
//     * (llamado cuando OpenAI procesa el audio de B)
//     */
//    fun onRemoteProcessedAudioReady(audioBytes: ByteArray) {
//        Log.d(TAG, "FLUJO 2: Remote processed audio ready, queuing for playback (${audioBytes.size} bytes)")
//        localPlaybackQueue.offer(audioBytes)
//    }
//
//    /**
//     * Envía audio procesado a Usuario B vía WebRTC
//     * IMPORTANTE: Necesitas implementar un mecanismo en AndroidWebRtcManager
//     * para inyectar audio en el MediaStream de salida
//     */
//    private fun sendAudioToRemotePeer(audioBytes: ByteArray) {
//        Log.d(TAG, "FLUJO 1: Sending ${audioBytes.size} bytes to remote peer")
//        // TODO: Implementar inyección de audio en WebRTC
//        // Opciones:
//        // 1. AudioTrack virtual que WebRTC capture
//        // 2. DataChannel para audio
//        // 3. Modificación de MediaStream
//    }
//
//    private fun calculateAudioLevel(buffer: ByteArray, size: Int): Int {
//        var sum = 0L
//        for (i in 0 until size step 2) {
//            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
//            sum += sample * sample
//        }
//        return Math.sqrt(sum.toDouble() / (size / 2)).toInt()
//    }
//
//    fun setRemoteDescription(sdp: String, type: SdpType) {
//        coroutineScope.launch {
//            webRtcManager.setRemoteDescription(sdp, type)
//        }
//    }
//
//    fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
//        coroutineScope.launch {
//            webRtcManager.addIceCandidate(candidate, sdpMid, sdpMLineIndex)
//        }
//    }
//
//    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
//    fun toggleMute(muted: Boolean) {
//        webRtcManager.setMuted(muted)
//        if (muted) {
//            isCapturingLocal = false
//        } else if (!isCapturingLocal) {
//            startLocalAudioCapture()
//        }
//    }
//
//    private fun cleanup() {
//        Log.d(TAG, "Cleaning up orchestrator")
//
//        isCapturingLocal = false
//        localCaptureJob?.cancel()
//
//        localAudioRecord?.apply {
//            try {
//                stop()
//                release()
//            } catch (e: Exception) {
//                Log.e(TAG, "Error stopping local audio: ${e.message}")
//            }
//        }
//        localAudioRecord = null
//
//        remoteProcessingJob?.cancel()
//        localPlaybackJob?.cancel()
//
//        localPlaybackTrack?.apply {
//            stop()
//            release()
//        }
//        localPlaybackTrack = null
//
//        localAudioSession?.disconnect()
//        remoteAudioSession?.disconnect()
//
//        webRtcManager.closePeerConnection()
//
//        _orchestratorState.value = OrchestratorState.Idle
//    }
//
//    fun dispose() {
//        cleanup()
//        coroutineScope.cancel()
//    }
//}