package com.eddyslarez.kmpsiprtc.services.livekit

import com.eddyslarez.kmpsiprtc.platform.log
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cliente WebSocket que habla el protocolo de signaling de LiveKit (protobuf binario).
 *
 * Flujo:
 * 1. connect(url, jwt) → WebSocket a ws://server:7880/rtc?access_token=JWT
 * 2. Recibe JoinResponse automaticamente
 * 3. Envia AddTrackRequest + SDP offer para publicar audio
 * 4. Recibe SDP answer + intercambia ICE candidates
 * 5. disconnect() para cerrar
 */
class LiveKitSignalingClient {

    private val TAG = "LiveKitSignaling"

    private var httpClient: HttpClient? = null
    private var wsSession: DefaultWebSocketSession? = null
    private var receiveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _connectionState = MutableStateFlow<LiveKitConnectionState>(LiveKitConnectionState.Disconnected)
    val connectionState: StateFlow<LiveKitConnectionState> = _connectionState.asStateFlow()

    // Canal para mensajes entrantes
    private val incomingMessages = Channel<LiveKitSignalMessage>(Channel.BUFFERED)

    var listener: LiveKitSignalingListener? = null

    /**
     * Conecta al servidor LiveKit via WebSocket
     * @param livekitUrl URL del servidor LiveKit (ej: "ws://localhost:7880")
     * @param jwt Token JWT generado por el conference server
     */
    suspend fun connect(livekitUrl: String, jwt: String) {
        if (_connectionState.value is LiveKitConnectionState.Connected) {
            log.w(tag = TAG) { "Ya conectado a LiveKit" }
            return
        }

        _connectionState.value = LiveKitConnectionState.Connecting
        log.d(tag = TAG) { "Conectando a LiveKit: $livekitUrl" }

        try {
            // Convertir http:// a ws:// si es necesario
            val wsUrl = livekitUrl
                .replace("http://", "ws://")
                .replace("https://", "wss://")

            httpClient = HttpClient {
                install(WebSockets)
            }

            // Extraer host y puerto de la URL
            val urlParts = wsUrl.removePrefix("ws://").removePrefix("wss://").split(":")
            val host = urlParts[0]
            val port = if (urlParts.size > 1) urlParts[1].split("/")[0].toIntOrNull() ?: 7880 else 7880
            val isSecure = wsUrl.startsWith("wss://")

            val connectBlock: suspend DefaultClientWebSocketSession.() -> Unit = {
                wsSession = this
                _connectionState.value = LiveKitConnectionState.Connected
                log.d(tag = TAG) { "WebSocket conectado a LiveKit" }

                // Loop de recepcion de mensajes
                try {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Binary -> {
                                val bytes = frame.readBytes()
                                try {
                                    val message = LiveKitProto.decodeSignalResponse(bytes)
                                    handleMessage(message)
                                } catch (e: Exception) {
                                    log.e(tag = TAG) { "Error decodificando mensaje protobuf: ${e.message}" }
                                }
                            }
                            is Frame.Text -> {
                                log.d(tag = TAG) { "Mensaje texto recibido (ignorado): ${frame.readText().take(100)}" }
                            }
                            is Frame.Close -> {
                                log.d(tag = TAG) { "WebSocket cerrado por servidor" }
                                break
                            }
                            else -> {}
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error en loop de recepcion: ${e.message}" }
                    _connectionState.value = LiveKitConnectionState.Error(e.message ?: "Error desconocido")
                    listener?.onError(e)
                } finally {
                    _connectionState.value = LiveKitConnectionState.Disconnected
                    listener?.onDisconnected()
                }
            }

            // Lanzar la conexion WebSocket en un coroutine separado
            receiveJob = scope.launch {
                try {
                    val path = "/rtc?access_token=${jwt}&auto_subscribe=1&protocol=9"
                    if (isSecure) {
                        httpClient!!.wss(
                            host = host,
                            port = port,
                            path = path,
                            block = connectBlock
                        )
                    } else {
                        httpClient!!.ws(
                            host = host,
                            port = port,
                            path = path,
                            block = connectBlock
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error conectando WebSocket: ${e.message}" }
                    _connectionState.value = LiveKitConnectionState.Error(e.message ?: "Connection failed")
                    listener?.onError(e)
                }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error iniciando conexion: ${e.message}" }
            _connectionState.value = LiveKitConnectionState.Error(e.message ?: "Init failed")
            throw e
        }
    }

    /**
     * Envia SDP offer para publicar tracks (publisher PeerConnection)
     */
    suspend fun sendOffer(sdp: String) {
        sendBinary(LiveKitProto.encodeOffer(sdp))
    }

    /**
     * Envia SDP answer para suscribirse a tracks (subscriber PeerConnection)
     */
    suspend fun sendAnswer(sdp: String) {
        sendBinary(LiveKitProto.encodeAnswer(sdp))
    }

    /**
     * Envia ICE candidate
     * @param candidateInit JSON del ICE candidate
     * @param target 0 = PUBLISHER, 1 = SUBSCRIBER
     */
    suspend fun sendTrickle(candidateInit: String, target: Int) {
        sendBinary(LiveKitProto.encodeTrickle(candidateInit, target))
    }

    /**
     * Solicita publicar un track de audio
     * @param cid Client ID local del track
     * @param name Nombre del track
     */
    suspend fun sendAddTrack(
        cid: String,
        name: String = "microphone",
        trackType: Int = LiveKitTrackType.AUDIO.value,
        source: Int = LiveKitTrackSource.MICROPHONE.value
    ) {
        sendBinary(LiveKitProto.encodeAddTrack(cid, name, trackType, source))
    }

    /**
     * Envia LeaveRequest y cierra la conexion
     */
    suspend fun sendLeave() {
        try {
            sendBinary(LiveKitProto.encodeLeave())
            delay(100) // Dar tiempo a que se envie
        } catch (e: Exception) {
            log.w(tag = TAG) { "Error enviando leave: ${e.message}" }
        }
    }

    /**
     * Desconecta del servidor LiveKit
     */
    suspend fun disconnect() {
        log.d(tag = TAG) { "Desconectando de LiveKit" }
        try {
            sendLeave()
        } catch (_: Exception) {}
        receiveJob?.cancel()
        receiveJob = null
        try {
            wsSession?.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnect"))
        } catch (_: Exception) {}
        wsSession = null
        httpClient?.close()
        httpClient = null
        _connectionState.value = LiveKitConnectionState.Disconnected
    }

    fun isConnected(): Boolean = _connectionState.value is LiveKitConnectionState.Connected

    // --- Internal ---

    private suspend fun sendBinary(data: ByteArray) {
        val session = wsSession
        if (session == null) {
            log.w(tag = TAG) { "No hay sesion WebSocket activa para enviar" }
            return
        }
        try {
            session.send(Frame.Binary(true, data))
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error enviando frame binario: ${e.message}" }
            throw e
        }
    }

    private fun handleMessage(message: LiveKitSignalMessage) {
        when (message) {
            is LiveKitSignalMessage.Join -> {
                log.d(tag = TAG) { "JoinResponse recibido: room=${message.joinResponse.room?.name}, " +
                        "participant=${message.joinResponse.participantIdentity}, " +
                        "iceServers=${message.joinResponse.iceServers.size}, " +
                        "subscriberPrimary=${message.joinResponse.subscriberPrimary}" }
                listener?.onJoinResponse(message.joinResponse)
            }
            is LiveKitSignalMessage.Answer -> {
                log.d(tag = TAG) { "SDP Answer recibido (${message.sdp.type})" }
                listener?.onAnswer(message.sdp)
            }
            is LiveKitSignalMessage.Offer -> {
                log.d(tag = TAG) { "SDP Offer recibido (subscriber)" }
                listener?.onOffer(message.sdp)
            }
            is LiveKitSignalMessage.Trickle -> {
                log.d(tag = TAG) { "ICE candidate recibido (target=${message.trickle.target})" }
                listener?.onTrickle(message.trickle)
            }
            is LiveKitSignalMessage.TrackPublished -> {
                log.d(tag = TAG) { "Track publicado: cid=${message.published.cid}, sid=${message.published.trackSid}" }
                listener?.onTrackPublished(message.published)
            }
            is LiveKitSignalMessage.Leave -> {
                log.d(tag = TAG) { "Leave recibido: canReconnect=${message.canReconnect}" }
                listener?.onLeave(message.canReconnect, message.reason)
            }
            is LiveKitSignalMessage.Unknown -> {
                log.d(tag = TAG) { "Mensaje desconocido: field=${message.fieldNumber}" }
            }
        }
    }
}

/**
 * Listener para eventos del protocolo de signaling de LiveKit
 */
interface LiveKitSignalingListener {
    fun onJoinResponse(joinResponse: LiveKitJoinResponse)
    fun onAnswer(sdp: LiveKitSessionDescription)
    fun onOffer(sdp: LiveKitSessionDescription)
    fun onTrickle(trickle: LiveKitTrickle)
    fun onTrackPublished(published: LiveKitTrackPublished)
    fun onLeave(canReconnect: Boolean, reason: Int)
    fun onDisconnected()
    fun onError(error: Exception)
}
