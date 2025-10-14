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

    override fun connect() {
        if (isConnecting) {
            log.w(tag = "AndroidWebSocket") { "Already connecting, ignoring duplicate request" }
            return
        }

        isConnecting = true

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
                    log.d(tag = "AndroidWebSocket") {
                        "✅ WebSocket opened. Protocol: ${response.protocol}"
                    }
                    listener?.onOpen()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    listener?.onMessage(text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    // Respuesta a PONG
                    listener?.onPong(System.currentTimeMillis())
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    isConnecting = false
                    log.d(tag = "AndroidWebSocket") { "WebSocket closed: $code - $reason" }
                    listener?.onClose(code, reason)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    isConnecting = false
                    log.e(tag = "AndroidWebSocket") {
                        "❌ WebSocket failure: ${t.message}. Response: ${response?.code}"
                    }
                    listener?.onError(Exception(t))
                }
            })

        } catch (e: Exception) {
            isConnecting = false
            log.e(tag = "AndroidWebSocket") { "❌ Error creating WebSocket: ${e.message}" }
            listener?.onError(e)
        }
    }

    override fun send(message: String) {
        webSocket?.send(message)
    }

    override fun close(code: Int, reason: String) {
        try {
            isConnecting = false
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

    override fun isConnected(): Boolean = webSocket != null

    override fun sendPing() {
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
