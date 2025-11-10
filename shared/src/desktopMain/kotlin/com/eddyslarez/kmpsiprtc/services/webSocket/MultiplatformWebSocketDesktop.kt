package com.eddyslarez.kmpsiprtc.services.webSocket

import com.eddyslarez.kmpsiprtc.platform.log
import okhttp3.*
import okio.ByteString
import java.util.*
import kotlin.collections.iterator
import kotlin.concurrent.timer



actual fun createWebSocket(url: String, headers: Map<String, String>): MultiplatformWebSocket = DesktopWebSocket(url, headers)

class DesktopWebSocket(private val url: String, private val headers: Map<String, String>) : MultiplatformWebSocket {
    private var listener: MultiplatformWebSocket.Listener? = null
    private var webSocket: WebSocket? = null
    private var pingTimer: Timer? = null
    private var renewalTimer: Timer? = null
    private var expirationMap = mutableMapOf<String, Long>()
    private var client: OkHttpClient? = null
    private var isConnecting = false

    override fun connect() {
        log.i(tag = "DesktopWebSocket") { "Connecting to $url" }
        log.i(tag = "DesktopWebSocket") { "Headers: $headers" }

        client = OkHttpClient()

        // Construir el request con los headers
        val requestBuilder = Request.Builder().url(url)

        // Añadir todos los headers recibidos
        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
            log.i(tag = "DesktopWebSocket") { "Added header $key = $value" }
        }

        val request = requestBuilder.build()

        webSocket = client!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                log.i(tag = "DesktopWebSocket") { "WebSocket connection opened with protocol: ${response.protocol}" }
                listener?.onOpen()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                listener?.onMessage(text)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                log.i(tag = "DesktopWebSocket") { "WebSocket closed with code: $code, reason: $reason" }
                listener?.onClose(code, reason)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                log.e(tag = "DesktopWebSocket") { "WebSocket error: ${t.message}" }
                log.e(tag = "DesktopWebSocket") { "Response: ${response?.code} - ${response?.message}" }
                listener?.onError(Exception(t))
            }
        })
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
            log.e(tag = "DesktopWebSocket") { "Error closing WebSocket: ${e.message}" }
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