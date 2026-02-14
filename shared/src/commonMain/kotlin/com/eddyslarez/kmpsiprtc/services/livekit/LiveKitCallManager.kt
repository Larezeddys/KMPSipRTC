package com.eddyslarez.kmpsiprtc.services.livekit

import com.eddyslarez.kmpsiprtc.data.models.SdpType
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.webrtc.WebRtcEventListener
import com.eddyslarez.kmpsiprtc.services.webrtc.WebRtcManager
import com.eddyslarez.kmpsiprtc.services.webrtc.createWebRtcManager
import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Orquesta el flujo completo de una llamada a traves de LiveKit SFU.
 *
 * Flujo:
 * 1. Pide token JWT al conference server (POST /sfu/get)
 * 2. Conecta signaling WebSocket a LiveKit
 * 3. Recibe JoinResponse con ICE servers
 * 4. Crea publisher PeerConnection → envía offer + AddTrackRequest
 * 5. Recibe answer del servidor → establece conexion
 * 6. Maneja subscriber PeerConnection para recibir audio remoto
 * 7. Intercambia ICE candidates en ambas direcciones
 *
 * Usa DOS WebRtcManager: uno para publisher (enviar audio) y otro para subscriber (recibir audio).
 */
class LiveKitCallManager(
    private val config: LiveKitConfig
) {
    private val TAG = "LiveKitCallManager"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    // Signaling
    private val signalingClient = LiveKitSignalingClient()

    // WebRTC - dos PeerConnections separados
    private var publisherWebRtc: WebRtcManager? = null
    private var subscriberWebRtc: WebRtcManager? = null

    // Estado
    private val _connectionState = MutableStateFlow<LiveKitConnectionState>(LiveKitConnectionState.Disconnected)
    val connectionState: StateFlow<LiveKitConnectionState> = _connectionState.asStateFlow()

    private var currentRoomId: String? = null
    private var currentUserId: String? = null
    private var joinResponse: LiveKitJoinResponse? = null
    private var publisherTrackCid = "audio-track-${System.currentTimeMillis()}"

    var listener: LiveKitCallListener? = null

    // Referencia al access token de Matrix para autenticacion
    private var matrixAccessToken: String? = null

    /**
     * Establece el access token de Matrix para autenticarse con /sfu/get
     */
    fun setMatrixAccessToken(token: String) {
        matrixAccessToken = token
    }

    /**
     * Unirse a una sala LiveKit para una llamada Matrix.
     *
     * @param matrixRoomId ID de la sala Matrix (ej: "!abc:localhost")
     * @param userId ID del usuario Matrix (ej: "@user:localhost")
     * @param deviceId ID del dispositivo (opcional)
     */
    suspend fun joinCall(
        matrixRoomId: String,
        userId: String,
        deviceId: String? = null
    ): Result<Unit> {
        if (_connectionState.value is LiveKitConnectionState.Connected) {
            log.w(tag = TAG) { "Ya conectado a LiveKit" }
            return Result.success(Unit)
        }

        _connectionState.value = LiveKitConnectionState.Connecting
        currentRoomId = matrixRoomId
        currentUserId = userId

        return try {
            // 1. Pedir token al conference server
            log.d(tag = TAG) { "Pidiendo token SFU para room=$matrixRoomId, user=$userId" }
            val tokenResponse = requestSfuToken(matrixRoomId, userId, deviceId)
            log.d(tag = TAG) { "Token SFU obtenido: url=${tokenResponse.url}" }

            // 2. Configurar listener de signaling
            setupSignalingListener()

            // 3. Conectar WebSocket de signaling
            signalingClient.connect(tokenResponse.url, tokenResponse.jwt)

            // Esperar a que se conecte y reciba JoinResponse
            withTimeout(10_000) {
                while (joinResponse == null) {
                    delay(50)
                }
            }

            log.d(tag = TAG) { "Conectado a LiveKit SFU exitosamente" }
            _connectionState.value = LiveKitConnectionState.Connected
            listener?.onConnected(matrixRoomId)

            Result.success(Unit)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error uniendose a sala LiveKit: ${e.message}" }
            _connectionState.value = LiveKitConnectionState.Error(e.message ?: "Join failed")
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * Salir de la sala LiveKit
     */
    suspend fun leaveCall() {
        log.d(tag = TAG) { "Saliendo de sala LiveKit" }
        try {
            signalingClient.disconnect()
        } catch (e: Exception) {
            log.w(tag = TAG) { "Error en disconnect de signaling: ${e.message}" }
        }
        cleanup()
        _connectionState.value = LiveKitConnectionState.Disconnected
        listener?.onDisconnected()
    }

    fun isConnected(): Boolean = _connectionState.value is LiveKitConnectionState.Connected

    // ==================== PRIVATE ====================

    /**
     * Solicita un token JWT al conference server via POST /sfu/get
     */
    private suspend fun requestSfuToken(
        roomId: String,
        userId: String,
        deviceId: String?
    ): SfuTokenResponse {
        val client = HttpClient()
        try {
            val response = client.post(config.sfuServiceUrl) {
                setBody(TextContent(buildSfuRequestBody(roomId, userId, deviceId), ContentType.Application.Json))
            }

            if (response.status != HttpStatusCode.OK) {
                throw Exception("SFU token request failed: ${response.status} - ${response.bodyAsText()}")
            }

            val body = response.bodyAsText()
            return json.decodeFromString<SfuTokenResponse>(body)
        } finally {
            client.close()
        }
    }

    private fun buildSfuRequestBody(roomId: String, userId: String, deviceId: String?): String {
        val token = matrixAccessToken
        return if (token != null) {
            // Autenticacion por access_token de Matrix (app KMP)
            buildString {
                append("{")
                append("\"room\":\"$roomId\",")
                append("\"access_token\":\"$token\",")
                append("\"device_id\":\"${deviceId ?: "KMP-${userId.hashCode().toString(16)}"}\"")
                append("}")
            }
        } else {
            // Fallback sin autenticacion (solo para desarrollo)
            buildString {
                append("{")
                append("\"room\":\"$roomId\",")
                append("\"openid_token\":{\"access_token\":\"dev-token\"},")
                append("\"device_id\":\"${deviceId ?: "KMP-${userId.hashCode().toString(16)}"}\"")
                append("}")
            }
        }
    }

    /**
     * Configura el listener de signaling para manejar mensajes del servidor
     */
    private fun setupSignalingListener() {
        signalingClient.listener = object : LiveKitSignalingListener {

            override fun onJoinResponse(jr: LiveKitJoinResponse) {
                joinResponse = jr
                log.d(tag = TAG) { "JoinResponse: subscriberPrimary=${jr.subscriberPrimary}, iceServers=${jr.iceServers.size}" }

                scope.launch {
                    try {
                        // Inicializar publisher WebRTC
                        setupPublisher()

                        // Si subscriber_primary, el servidor enviara un offer para el subscriber
                        // Lo manejamos en onOffer()
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error configurando publisher: ${e.message}" }
                        _connectionState.value = LiveKitConnectionState.Error(e.message ?: "Publisher setup failed")
                    }
                }
            }

            override fun onAnswer(sdp: LiveKitSessionDescription) {
                // Answer del servidor para nuestro publisher offer
                scope.launch {
                    try {
                        log.d(tag = TAG) { "Aplicando SDP answer del servidor al publisher" }
                        publisherWebRtc?.setRemoteDescription(sdp.sdp, SdpType.ANSWER)
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error aplicando answer: ${e.message}" }
                    }
                }
            }

            override fun onOffer(sdp: LiveKitSessionDescription) {
                // Offer del servidor para subscriber PeerConnection
                scope.launch {
                    try {
                        log.d(tag = TAG) { "Offer recibido para subscriber" }
                        setupSubscriberAndAnswer(sdp.sdp)
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error configurando subscriber: ${e.message}" }
                    }
                }
            }

            override fun onTrickle(trickle: LiveKitTrickle) {
                scope.launch {
                    try {
                        // Determinar a cual PeerConnection va el ICE candidate
                        val targetWebRtc = if (trickle.target == LiveKitSignalTarget.PUBLISHER.value) {
                            publisherWebRtc
                        } else {
                            subscriberWebRtc
                        }

                        if (targetWebRtc != null) {
                            // El candidateInit es un JSON con candidate, sdpMid, sdpMLineIndex
                            val candidateJson = trickle.candidateInit
                            // Parsear los campos del candidate
                            val candidate = extractJsonField(candidateJson, "candidate") ?: ""
                            val sdpMid = extractJsonField(candidateJson, "sdpMid")
                                ?: extractJsonField(candidateJson, "sdpMLineIndex")?.let { null }
                            val sdpMLineIndex = extractJsonField(candidateJson, "sdpMLineIndex")?.toIntOrNull()

                            targetWebRtc.addIceCandidate(candidate, sdpMid, sdpMLineIndex)
                        }
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error procesando ICE candidate: ${e.message}" }
                    }
                }
            }

            override fun onTrackPublished(published: LiveKitTrackPublished) {
                log.d(tag = TAG) { "Track publicado exitosamente: ${published.trackSid}" }
                listener?.onTrackPublished(published.trackSid)
            }

            override fun onLeave(canReconnect: Boolean, reason: Int) {
                log.d(tag = TAG) { "Servidor solicita leave: canReconnect=$canReconnect, reason=$reason" }
                scope.launch {
                    cleanup()
                    _connectionState.value = LiveKitConnectionState.Disconnected
                    listener?.onDisconnected()
                }
            }

            override fun onDisconnected() {
                log.d(tag = TAG) { "Signaling desconectado" }
                _connectionState.value = LiveKitConnectionState.Disconnected
            }

            override fun onError(error: Exception) {
                log.e(tag = TAG) { "Error de signaling: ${error.message}" }
                _connectionState.value = LiveKitConnectionState.Error(error.message ?: "Signaling error")
                listener?.onError(error)
            }
        }
    }

    /**
     * Inicializa el publisher PeerConnection y publica audio
     */
    private suspend fun setupPublisher() {
        log.d(tag = TAG) { "Inicializando publisher WebRTC" }

        val pub = createWebRtcManager()
        publisherWebRtc = pub
        pub.initialize()
        pub.setAudioEnabled(true)

        // Listener para ICE candidates del publisher
        pub.setListener(object : WebRtcEventListener {
            override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                scope.launch {
                    val candidateJson = "{\"candidate\":\"$candidate\",\"sdpMid\":\"$sdpMid\",\"sdpMLineIndex\":$sdpMLineIndex}"
                    signalingClient.sendTrickle(candidateJson, LiveKitSignalTarget.PUBLISHER.value)
                }
            }

            override fun onConnectionStateChange(state: WebRtcConnectionState) {
                log.d(tag = TAG) { "Publisher WebRTC state: $state" }
                when (state) {
                    WebRtcConnectionState.CONNECTED -> listener?.onMediaConnected()
                    WebRtcConnectionState.CLOSED -> {
                        log.w(tag = TAG) { "Publisher PeerConnection cerrado" }
                    }
                    else -> {}
                }
            }

            override fun onRemoteAudioTrack() {}
            override fun onAudioDeviceChanged(device: AudioDevice?) {}
        })

        // Pedir publicar track de audio
        publisherTrackCid = "audio-${System.currentTimeMillis()}"
        signalingClient.sendAddTrack(
            cid = publisherTrackCid,
            name = "microphone",
            trackType = LiveKitTrackType.AUDIO.value,
            source = LiveKitTrackSource.MICROPHONE.value
        )

        // Crear y enviar offer
        val offer = pub.createOffer()
        signalingClient.sendOffer(offer)

        log.d(tag = TAG) { "Publisher offer enviado" }
    }

    /**
     * Configura el subscriber PeerConnection cuando el servidor envia un offer
     */
    private suspend fun setupSubscriberAndAnswer(offerSdp: String) {
        log.d(tag = TAG) { "Configurando subscriber PeerConnection" }

        // Crear subscriber WebRtcManager si no existe
        if (subscriberWebRtc == null) {
            val sub = createWebRtcManager()
            subscriberWebRtc = sub
            sub.initialize()

            // Listener para ICE candidates del subscriber
            sub.setListener(object : WebRtcEventListener {
                override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                    scope.launch {
                        val candidateJson = "{\"candidate\":\"$candidate\",\"sdpMid\":\"$sdpMid\",\"sdpMLineIndex\":$sdpMLineIndex}"
                        signalingClient.sendTrickle(candidateJson, LiveKitSignalTarget.SUBSCRIBER.value)
                    }
                }

                override fun onConnectionStateChange(state: WebRtcConnectionState) {
                    log.d(tag = TAG) { "Subscriber WebRTC state: $state" }
                }

                override fun onRemoteAudioTrack() {
                    log.d(tag = TAG) { "Audio remoto recibido via subscriber" }
                    listener?.onRemoteAudioReceived()
                }

                override fun onAudioDeviceChanged(device: AudioDevice?) {}
            })
        }

        val sub = subscriberWebRtc!!

        // Set remote offer y crear answer
        sub.setRemoteDescription(offerSdp, SdpType.OFFER)
        val answer = sub.createAnswer(offerSdp)
        signalingClient.sendAnswer(answer)

        log.d(tag = TAG) { "Subscriber answer enviado" }
    }

    private fun cleanup() {
        publisherWebRtc?.closePeerConnection()
        publisherWebRtc?.dispose()
        publisherWebRtc = null

        subscriberWebRtc?.closePeerConnection()
        subscriberWebRtc?.dispose()
        subscriberWebRtc = null

        joinResponse = null
        currentRoomId = null
        currentUserId = null
    }

    /**
     * Extrae un campo string de un JSON simple
     */
    private fun extractJsonField(json: String, field: String): String? {
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]*)\""
        val regex = Regex(pattern)
        return regex.find(json)?.groupValues?.get(1)
    }

    /**
     * Obtiene timestamp actual (compatible KMP)
     */
    private object System {
        @OptIn(ExperimentalTime::class)
        fun currentTimeMillis(): Long {
            return Clock.System.now().toEpochMilliseconds()
        }
    }
}

/**
 * Listener para eventos de alto nivel de LiveKit call
 */
interface LiveKitCallListener {
    fun onConnected(roomId: String)
    fun onDisconnected()
    fun onTrackPublished(trackSid: String)
    fun onMediaConnected()
    fun onRemoteAudioReceived()
    fun onError(error: Exception)
}
