package com.eddyslarez.kmpsiprtc.services.webSocket

import okhttp3.*
import okio.ByteString
import java.util.*
import kotlin.collections.iterator
import kotlin.concurrent.timer

actual fun createWebSocket(url: String): MultiplatformWebSocket = AndroidWebSocket(url)

class AndroidWebSocket(private val url: String) : MultiplatformWebSocket {
    private var listener: MultiplatformWebSocket.Listener? = null
    private var webSocket: WebSocket? = null
    private var pingTimer: Timer? = null
    private var renewalTimer: Timer? = null
    private var expirationMap = mutableMapOf<String, Long>()

    override fun connect() {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                listener?.onOpen()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                listener?.onMessage(text)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                listener?.onClose(code, reason)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                listener?.onError(Exception(t))
            }
        })
    }

    override fun send(message: String) {
        webSocket?.send(message)
    }

    override fun close(code: Int, reason: String) {
        webSocket?.close(code, reason)
        stopPingTimer()
        stopRegistrationRenewalTimer()
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
