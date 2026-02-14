package com.eddyslarez.kmpsiprtc.services.webSocket

import com.eddyslarez.kmpsiprtc.platform.log
import okhttp3.*
import okio.ByteString
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.iterator
import kotlin.concurrent.timer

actual fun createWebSocket(url: String, headers: Map<String, String>): MultiplatformWebSocket = AndroidWebSocket(url,headers)

class AndroidWebSocket(private val url: String, private val headers: Map<String, String>) : MultiplatformWebSocket {
    private var listener: MultiplatformWebSocket.Listener? = null
    private var webSocket: WebSocket? = null
    private var pingTimer: Timer? = null
    private var renewalTimer: Timer? = null
    private var expirationMap = mutableMapOf<String, Long>()
    private var isConnecting = false
    private var client: OkHttpClient? = null

    // Estado real de conexion (como iOS usa isConnectedFlag)
    private var isOpen = false

    // Timestamp del ultimo ping enviado para calcular latencia
    private var lastPingSentTime = 0L

    override fun connect() {
        if (isConnecting) {
            log.w(tag = "AndroidWebSocket") { "Already connecting, ignoring duplicate request" }
            return
        }

        isConnecting = true
        isOpen = false

        try {
            // Crear cliente OkHttp con configuración robusta
            client = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            // Construir petición con headers
            val requestBuilder = Request.Builder().url(url)

            // Agregar headers
            headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
                log.d(tag = "AndroidWebSocket") { "Adding header: $key = $value" }
            }

            val request = requestBuilder.build()

            log.d(tag = "AndroidWebSocket") { "Connecting to: $url" }

            // Crear WebSocket
            webSocket = client?.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    isConnecting = false
                    isOpen = true
                    log.d(tag = "AndroidWebSocket") {
                        "WebSocket opened. Protocol: ${response.protocol}"
                    }
                    listener?.onOpen()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    log.d(tag = "AndroidWebSocket") {
                        "Text message received: ${text.take(120)}..."
                    }
                    listener?.onMessage(text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    // Mensajes binarios: si esta vacio es respuesta a nuestro ping custom,
                    // si tiene contenido es un mensaje SIP que debemos procesar
                    if (bytes.size == 0) {
                        val latency = System.currentTimeMillis() - lastPingSentTime
                        listener?.onPong(latency)
                    } else {
                        log.d(tag = "AndroidWebSocket") { "Binary message received (${bytes.size} bytes), converting to text" }
                        listener?.onMessage(bytes.utf8())
                    }
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    isConnecting = false
                    isOpen = false
                    log.d(tag = "AndroidWebSocket") { "WebSocket closed: $code - $reason" }
                    listener?.onClose(code, reason)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    isConnecting = false
                    isOpen = false
                    log.e(tag = "AndroidWebSocket") {
                        "WebSocket failure: ${t.message}. Response: ${response?.code}"
                    }
                    listener?.onError(Exception(t))
                }
            })

        } catch (e: Exception) {
            isConnecting = false
            isOpen = false
            log.e(tag = "AndroidWebSocket") { "Error creating WebSocket: ${e.message}" }
            listener?.onError(e)
        }
    }

    override fun send(message: String) {
        if (!isOpen || webSocket == null) {
            log.w(tag = "AndroidWebSocket") { "Cannot send message - WebSocket not connected" }
            listener?.onError(Exception("Cannot send message - WebSocket not connected"))
            return
        }
        val sent = webSocket?.send(message) ?: false
        if (!sent) {
            log.e(tag = "AndroidWebSocket") { "Failed to enqueue message (WebSocket closing or closed)" }
        }
    }

    override fun close(code: Int, reason: String) {
        try {
            isConnecting = false
            isOpen = false
            stopPingTimer()
            stopRegistrationRenewalTimer()

            webSocket?.close(code, reason)
            webSocket = null

            // Cerrar el cliente OkHttp
            client?.dispatcher?.executorService?.shutdown()
            client = null

        } catch (e: Exception) {
            log.e(tag = "AndroidWebSocket") { "Error closing WebSocket: ${e.message}" }
        }
    }

    override fun isConnected(): Boolean = isOpen

    override fun sendPing() {
        if (!isOpen || webSocket == null) return
        lastPingSentTime = System.currentTimeMillis()
        webSocket?.send(ByteString.EMPTY)
    }

    override fun startPingTimer(intervalMs: Long) {
        stopPingTimer()
        pingTimer = timer(period = intervalMs) {
            sendPing()
        }
    }

    override fun stopPingTimer() {
        pingTimer?.cancel()
        pingTimer = null
    }

    override fun startRegistrationRenewalTimer(checkIntervalMs: Long, renewBeforeExpirationMs: Long) {
        stopRegistrationRenewalTimer()
        renewalTimer = timer(period = checkIntervalMs) {
            val now = System.currentTimeMillis()
            for ((key, expiration) in expirationMap) {
                if (expiration - now <= renewBeforeExpirationMs) {
                    listener?.onRegistrationRenewalRequired(key)
                }
            }
        }
    }

    override fun stopRegistrationRenewalTimer() {
        renewalTimer?.cancel()
        renewalTimer = null
    }

    override fun setRegistrationExpiration(accountKey: String, expirationTimeMs: Long) {
        expirationMap[accountKey] = expirationTimeMs
    }

    override fun renewRegistration(accountKey: String) {
        listener?.onRegistrationRenewalRequired(accountKey)
    }

    override fun setListener(listener: MultiplatformWebSocket.Listener) {
        this.listener = listener
    }
}
