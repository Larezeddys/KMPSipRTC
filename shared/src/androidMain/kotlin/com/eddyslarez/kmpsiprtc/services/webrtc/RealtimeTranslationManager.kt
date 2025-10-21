package com.eddyslarez.kmpsiprtc.services.webrtc

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class RealtimeTranslationManager(
    private val serverUrl: String,
    private val webRtcManager: AndroidWebRtcManager? = null
) {
    private val TAG = "RealtimeTranslation"
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var currentSessionId: String? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _translationText = MutableStateFlow<String>("")
    val translationText: StateFlow<String> = _translationText

    // ✅ NUEVO: Flow para audio procesado
    private val _processedAudioFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 100
    )
    val processedAudioFlow: SharedFlow<ByteArray> = _processedAudioFlow

    // ✅ NUEVO: Callback para audio procesado
    var onAudioProcessed: ((ByteArray) -> Unit)? = null

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    suspend fun startTranslationSession(
        sourceLanguage: String = "es",
        targetLanguage: String = "en",
        voice: String = "alloy"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Creating translation session: $sourceLanguage -> $targetLanguage")
            _connectionState.value = ConnectionState.Connecting

            val json = JSONObject().apply {
                put("sourceLanguage", sourceLanguage)
                put("targetLanguage", targetLanguage)
                put("voice", voice)
            }

            val requestBody = json.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$serverUrl/session/create")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw Exception("Failed to create session: ${response.code}")
            }

            val responseBody = response.body?.string() ?: throw Exception("Empty response")
            val jsonResponse = JSONObject(responseBody)

            currentSessionId = jsonResponse.getString("sessionId")
            val wsUrl = jsonResponse.getString("wsUrl")

            Log.d(TAG, "Session created: $currentSessionId")

            connectWebSocket(wsUrl)

            Result.success(currentSessionId!!)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating session: ${e.message}", e)
            _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    private fun connectWebSocket(wsUrl: String) {
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                _connectionState.value = ConnectionState.Connected
                startPingPong(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWebSocketMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                _connectionState.value = ConnectionState.Error(t.message ?: "Connection failed")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code - $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                _connectionState.value = ConnectionState.Disconnected
            }
        })
    }

    private fun startPingPong(webSocket: WebSocket) {
        coroutineScope.launch {
            while (_connectionState.value is ConnectionState.Connected) {
                delay(30000)
                val ping = JSONObject().apply {
                    put("type", "ping")
                    put("timestamp", System.currentTimeMillis())
                }
                webSocket.send(ping.toString())
            }
        }
    }

    private fun handleWebSocketMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            when (type) {
                "connection.established" -> {
                    Log.d(TAG, "Connection established")
                }
                "response.audio_transcript.delta" -> {
                    val delta = json.optString("delta", "")
                    if (delta.isNotEmpty()) {
                        _translationText.value += delta
                        Log.d(TAG, "Translation delta: $delta")
                    }
                }
                "response.audio.delta" -> {
                    val audioDelta = json.optString("delta", "")
                    if (audioDelta.isNotEmpty()) {
                        handleAudioDelta(audioDelta)
                    }
                }
                "error" -> {
                    val error = json.optJSONObject("error")
                    val message = error?.optString("message", "Unknown error") ?: "Unknown error"
                    Log.e(TAG, "Server error: $message")
                    _connectionState.value = ConnectionState.Error(message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: ${e.message}", e)
        }
    }

    fun sendAudio(audioData: ByteArray) {
        webSocket?.let { ws ->
            if (_connectionState.value is ConnectionState.Connected) {
                try {
                    val base64Audio = android.util.Base64.encodeToString(
                        audioData,
                        android.util.Base64.NO_WRAP
                    )

                    val message = JSONObject().apply {
                        put("type", "input_audio_buffer.append")
                        put("audio", base64Audio)
                    }

                    ws.send(message.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending audio: ${e.message}", e)
                }
            }
        }
    }

    fun commitAudioBuffer() {
        webSocket?.let { ws ->
            if (_connectionState.value is ConnectionState.Connected) {
                val message = JSONObject().apply {
                    put("type", "input_audio_buffer.commit")
                }
                ws.send(message.toString())
            }
        }
    }

    private fun handleAudioDelta(base64Audio: String) {
        try {
            val audioBytes = android.util.Base64.decode(base64Audio, android.util.Base64.NO_WRAP)
            Log.d(TAG, "Received audio delta: ${audioBytes.size} bytes")

            // ✅ Emitir audio procesado
            coroutineScope.launch {
                _processedAudioFlow.emit(audioBytes)
            }

            // ✅ Invocar callback
            onAudioProcessed?.invoke(audioBytes)

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio: ${e.message}", e)
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting translation session")
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        currentSessionId = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun dispose() {
        disconnect()
        coroutineScope.cancel()
    }
}
