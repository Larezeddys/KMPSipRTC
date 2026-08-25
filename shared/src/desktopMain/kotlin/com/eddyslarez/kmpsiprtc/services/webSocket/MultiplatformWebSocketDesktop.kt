package com.eddyslarez.kmpsiprtc.services.webSocket

import com.eddyslarez.kmpsiprtc.platform.log
import okhttp3.*
import okio.ByteString
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.iterator
import kotlin.concurrent.timer



actual fun createWebSocket(url: String, headers: Map<String, String>): MultiplatformWebSocket = DesktopWebSocket(url, headers)

class DesktopWebSocket(private val url: String, private val headers: Map<String, String>) : MultiplatformWebSocket {
    private var listener: MultiplatformWebSocket.Listener? = null
    private var webSocket: WebSocket? = null
    private var pingTimer: Timer? = null
    private var renewalTimer: Timer? = null
    private val expirationLock = Any()
    private val expirationMap = mutableMapOf<String, Long>()
    private var client: OkHttpClient? = null
    private var isConnecting = false

    // Estado real de conexion (como iOS usa isConnectedFlag)
    private var isOpen = false

    // Timestamp del ultimo ping enviado para calcular latencia
    private var lastPingSentTime = 0L

    // OkHttp puede invocar onClosing Y onClosed para el mismo cierre: solo se notifica una vez.
    @Volatile
    private var closeNotified = false

    override fun connect() {
        if (isConnecting) {
            log.w(tag = "DesktopWebSocket") { "Already connecting, ignoring duplicate request" }
            return
        }

        isConnecting = true
        isOpen = false
        closeNotified = false
        log.i(tag = "DesktopWebSocket") { "Connecting to $url" }

        try {
            // Crear cliente OkHttp con timeouts explicitos - igualar a Android
            client = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            // Construir el request con los headers
            val requestBuilder = Request.Builder().url(url)

            // Anadir todos los headers recibidos
            headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
                log.d(tag = "DesktopWebSocket") { "Added header $key = $value" }
            }

            val request = requestBuilder.build()

            webSocket = client!!.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    isConnecting = false
                    isOpen = true
                    log.i(tag = "DesktopWebSocket") { "WebSocket connection opened with protocol: ${response.protocol}" }
                    listener?.onOpen()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    log.d(tag = "DesktopWebSocket") { "Text message received: ${text.take(120)}..." }
                    listener?.onMessage(text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    // Mensajes binarios: si esta vacio es respuesta a nuestro ping custom,
                    // si tiene contenido es un mensaje SIP que debemos procesar
                    if (bytes.size == 0) {
                        val latency = System.currentTimeMillis() - lastPingSentTime
                        listener?.onPong(latency)
                    } else {
                        log.d(tag = "DesktopWebSocket") { "Binary message received (${bytes.size} bytes), converting to text" }
                        listener?.onMessage(bytes.utf8())
                    }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    // Cuando el cierre lo inicia el SERVIDOR (p.ej. OpenSIPS corta la sesion por
                    // inactividad) OkHttp invoca onClosing y NO invoca onClosed salvo que nosotros
                    // completemos el handshake. Sin este override no se ejecutaba nada: isOpen
                    // seguia en true y la cuenta parecia registrada con el binding ya muerto.
                    isConnecting = false
                    isOpen = false
                    log.i(tag = "DesktopWebSocket") { "WebSocket closing by peer: code=$code, reason=$reason" }
                    runCatching { ws.close(1000, null) }
                    if (!closeNotified) {
                        closeNotified = true
                        listener?.onClose(code, reason)
                    }
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    isConnecting = false
                    isOpen = false
                    log.i(tag = "DesktopWebSocket") { "WebSocket closed with code: $code, reason: $reason" }
                    if (!closeNotified) {
                        closeNotified = true
                        listener?.onClose(code, reason)
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    isConnecting = false
                    isOpen = false
                    log.e(tag = "DesktopWebSocket") { "WebSocket error: ${t.message}. Response: ${response?.code}" }
                    listener?.onError(Exception(t))
                }
            })
        } catch (e: Exception) {
            isConnecting = false
            isOpen = false
            log.e(tag = "DesktopWebSocket") { "Error creating WebSocket: ${e.message}" }
            listener?.onError(e)
        }
    }

    override fun send(message: String) {
        if (!isOpen || webSocket == null) {
            log.w(tag = "DesktopWebSocket") { "Cannot send message - WebSocket not connected" }
            listener?.onError(Exception("Cannot send message - WebSocket not connected"))
            return
        }
        val sent = webSocket?.send(message) ?: false
        if (!sent) {
            log.e(tag = "DesktopWebSocket") { "Failed to enqueue message (WebSocket closing or closed)" }
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
            // Se desengancha el listener: si no, los callbacks tardios de este socket (un
            // onFailure por readTimeout puede llegar decenas de segundos despues) siguen
            // tocando el estado compartido del socket que ya lo sustituyo.
            listener = null

            // Cerrar el cliente OkHttp
            client?.dispatcher?.executorService?.shutdown()
            client = null

        } catch (e: Exception) {
            log.e(tag = "DesktopWebSocket") { "Error closing WebSocket: ${e.message}" }
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
        // El cuerpo va envuelto: con java.util.Timer, UNA excepcion no capturada mata el timer
        // entero y ya no vuelve a ejecutarse nunca, sin ruido en el log.
        pingTimer = timer(daemon = true, period = intervalMs) {
            try {
                sendPing()
            } catch (e: Throwable) {
                log.w(tag = "DesktopWebSocket") { "Ping timer tick failed: ${e.message}" }
            }
        }
    }

    override fun stopPingTimer() {
        pingTimer?.cancel()
        pingTimer = null
    }

    /**
     * Renovacion de registro.
     *
     * Dos cosas que parecen detalles y no lo son, porque su fallo es SILENCIOSO y deja al
     * cliente creyendose registrado mientras el servidor ya lo ha caducado (sintoma tipico:
     * el escritorio "pierde el registro" tras un rato inactivo — pantalla bloqueada — y solo
     * se recupera cuando el usuario vuelve a tocar la app):
     *
     * 1. Se itera sobre una COPIA del mapa. [setRegistrationExpiration] se llama desde los
     *    hilos de red mientras el timer recorre el mapa; iterar el original lanzaba
     *    ConcurrentModificationException.
     * 2. Todo el tick va en try/catch. `java.util.Timer` cancela la tarea para siempre en
     *    cuanto una ejecucion lanza, asi que una sola excepcion aqui equivalia a quedarse sin
     *    renovaciones hasta reiniciar la app.
     */
    override fun startRegistrationRenewalTimer(checkIntervalMs: Long, renewBeforeExpirationMs: Long) {
        stopRegistrationRenewalTimer()
        renewalTimer = timer(daemon = true, period = checkIntervalMs) {
            try {
                val now = System.currentTimeMillis()
                val snapshot = synchronized(expirationLock) { expirationMap.toMap() }
                for ((key, expiration) in snapshot) {
                    if (expiration - now <= renewBeforeExpirationMs) {
                        log.d(tag = "DesktopWebSocket") {
                            "Registration renewal due for $key (expires in ${expiration - now}ms)"
                        }
                        listener?.onRegistrationRenewalRequired(key)
                    }
                }
            } catch (e: Throwable) {
                log.w(tag = "DesktopWebSocket") { "Registration renewal tick failed: ${e.message}" }
            }
        }
    }

    override fun stopRegistrationRenewalTimer() {
        renewalTimer?.cancel()
        renewalTimer = null
    }

    override fun setRegistrationExpiration(accountKey: String, expirationTimeMs: Long) {
        synchronized(expirationLock) { expirationMap[accountKey] = expirationTimeMs }
    }

    override fun renewRegistration(accountKey: String) {
        listener?.onRegistrationRenewalRequired(accountKey)
    }

    override fun setListener(listener: MultiplatformWebSocket.Listener) {
        this.listener = listener
    }
}
