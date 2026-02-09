package com.eddyslarez.kmpsiprtc.services.webSocket


import kotlinx.coroutines.*
import platform.Foundation.NSTimer
import platform.darwin.NSObject
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*
import platform.darwin.NSInteger
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

actual fun createWebSocket(url: String, headers: Map<String, String>): MultiplatformWebSocket = IOSWebSocket(url,headers)


/**
 * iOS implementation of MultiplatformWebSocket using NSURLSession
 */
class IOSWebSocket(
    private val url: String,
    private val headers: Map<String, String>? = null
) : MultiplatformWebSocket {

    private var webSocket: NSURLSessionWebSocketTask? = null
    private var session: NSURLSession? = null
    private var listener: MultiplatformWebSocket.Listener? = null
    private var isConnectedFlag = false
    private var isReceivingMessages = false

    // Timers
    private var pingTimer: NSTimer? = null
    private var registrationRenewalTimer: NSTimer? = null

    // Registro de expiración para cuentas
    private val registrationExpirations = HashMap<String, Double>()

    // Constantes
    private var registrationCheckIntervalMs: Double = 300000.0 // 5 minutos por defecto
    private var renewBeforeExpirationMs: Double = 60000.0 // 1 minuto por defecto

    // Variables para medir pings
    private var lastPingSentTime: Double = 0.0

    @OptIn(ExperimentalForeignApi::class)
    override fun connect() {
        println("IOSWebSocket: Connecting to $url")

        val nsUrl = NSURL(string = url)
        val request = NSMutableURLRequest(uRL = nsUrl)

        // Add headers if provided
        headers?.forEach { (key, value) ->
            request.setValue(value, forHTTPHeaderField = key)
            println("IOSWebSocket: Added header $key = $value")
        }

        // Create session configuration
        val configuration = NSURLSessionConfiguration.defaultSessionConfiguration

        // Create delegate
        val delegateCallback = object : NSObject(), NSURLSessionWebSocketDelegateProtocol {
            override fun URLSession(
                session: NSURLSession,
                webSocketTask: NSURLSessionWebSocketTask,
                didOpenWithProtocol: String?
            ) {
                println("IOSWebSocket: WebSocket connection opened with protocol: $didOpenWithProtocol")
                dispatch_async(dispatch_get_main_queue()) {
                    isConnectedFlag = true
                    listener?.onOpen()
                    // Start receiving messages after confirmed connection
                    if (!isReceivingMessages) {
                        startReceivingMessages()
                    }
                }
            }

            override fun URLSession(
                session: NSURLSession,
                webSocketTask: NSURLSessionWebSocketTask,
                didCloseWithCode: NSInteger,
                reason: NSData?
            ) {
                val reasonString = reason?.let {
                    NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString()
                } ?: "No reason provided"

                println("IOSWebSocket: WebSocket closed with code: $didCloseWithCode, reason: $reasonString")
                dispatch_async(dispatch_get_main_queue()) {
                    isConnectedFlag = false
                    isReceivingMessages = false
                    stopPingTimer()
                    stopRegistrationRenewalTimer()
                    listener?.onClose(didCloseWithCode.toInt(), reasonString)
                }
            }
        }

        // Create session with delegate
        session = NSURLSession.sessionWithConfiguration(
            configuration,
            delegateCallback,
            NSOperationQueue.mainQueue
        )

        // Create and start WebSocket task
        webSocket = session?.webSocketTaskWithRequest(request)
        webSocket?.resume()
        println("IOSWebSocket: WebSocket task created and resumed")
    }

    private fun startReceivingMessages() {
        isReceivingMessages = true
        receiveMessage()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun receiveMessage() {
        if (!isConnectedFlag || webSocket == null) {
            println("IOSWebSocket: Cannot receive messages - not connected")
            isReceivingMessages = false
            return
        }

        println("IOSWebSocket: Setting up message receiver")
        webSocket?.receiveMessageWithCompletionHandler { message, error ->
            if (error != null) {
                println("IOSWebSocket: Error receiving message: ${error.localizedDescription}")

                // Handle error based on domain and code
                val nsError = error as NSError
                if (nsError.domain == "NSPOSIXErrorDomain" && nsError.code == 57L) {
                    // Socket is not connected - señalar reconexion inmediata via onClose
                    println("IOSWebSocket: POSIX 57 detected - socket not connected, signaling for reconnection")
                    dispatch_async(dispatch_get_main_queue()) {
                        isConnectedFlag = false
                        isReceivingMessages = false
                        stopPingTimer()
                        // Usar onClose con codigo especial para que SharedWebSocketManager haga reconexion
                        listener?.onClose(1006, "POSIX 57: Socket not connected")
                    }
                    return@receiveMessageWithCompletionHandler
                }

                dispatch_async(dispatch_get_main_queue()) {
                    listener?.onError(Exception(error.toString()))

                    // Only continue receiving if still connected
                    if (isConnectedFlag) {
                        receiveMessage()
                    } else {
                        isReceivingMessages = false
                    }
                }
                return@receiveMessageWithCompletionHandler
            }

            when (message) {
                is NSURLSessionWebSocketMessage -> {
                    val messageData = message.data
                    val messageString = message.string

                    if (messageString != null) {
                        println("IOSWebSocket: Text message received: ${messageString.take(100)}${if (messageString.length > 100) "..." else ""}")
                        dispatch_async(dispatch_get_main_queue()) {
                            listener?.onMessage(messageString)
                        }
                    } else if (messageData != null) {
                        println("IOSWebSocket: Binary message received: ${messageData.length} bytes")
                        val binaryString = messageData.description
                        dispatch_async(dispatch_get_main_queue()) {
                            if (binaryString != null) {
                                listener?.onMessage(binaryString)
                            }
                        }
                    } else {
                        println("IOSWebSocket: Empty message received")
                    }
                }
                else -> {
                    println("IOSWebSocket: Unknown message type received")
                }
            }

            // Continue receiving messages if still connected
            if (isConnectedFlag) {
                receiveMessage()
            } else {
                isReceivingMessages = false
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun send(message: String) {
        if (!isConnectedFlag || webSocket == null) {
            println("IOSWebSocket: Cannot send message - not connected")
            listener?.onError(Exception("Cannot send message - WebSocket not connected"))
            return
        }

        println("IOSWebSocket: Sending message: ${message.take(100)}${if (message.length > 100) "..." else ""}")
        val nsString = NSString.create(string = message)
        val data = nsString.dataUsingEncoding(NSUTF8StringEncoding)

        data?.let {
            val wsMessage = NSURLSessionWebSocketMessage(it)
            webSocket?.sendMessage(wsMessage) { error ->
                if (error != null) {
                    println("IOSWebSocket: Error sending message: ${error.localizedDescription}")
                    dispatch_async(dispatch_get_main_queue()) {
                        listener?.onError(Exception("Send error: ${error.localizedDescription}"))
                    }
                } else {
                    println("IOSWebSocket: Message sent successfully")
                }
            }
        } ?: run {
            println("IOSWebSocket: Failed to encode message")
            listener?.onError(Exception("Failed to encode message"))
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun close(code: Int, reason: String) {
        println("IOSWebSocket: Closing connection with code $code, reason: $reason")

        isConnectedFlag = false
        isReceivingMessages = false
        stopPingTimer()
        stopRegistrationRenewalTimer()

        val reasonNSString = NSString.create(string = reason)
        val reasonData = reasonNSString.dataUsingEncoding(NSUTF8StringEncoding)

        webSocket?.cancelWithCloseCode(code.toLong(), reasonData)

        // Clean up resources
        webSocket = null
        session?.invalidateAndCancel()
        session = null

        dispatch_async(dispatch_get_main_queue()) {
            listener?.onClose(code, reason)
        }

        println("IOSWebSocket: Connection closed")
    }

    override fun isConnected(): Boolean {
        return isConnectedFlag
    }

    override fun setListener(listener: MultiplatformWebSocket.Listener) {
        println("IOSWebSocket: Setting listener")
        this.listener = listener
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun sendPing() {
        if (!isConnectedFlag || webSocket == null) {
            println("IOSWebSocket: Cannot send ping - not connected")
            return
        }

        try {
            lastPingSentTime = NSDate.timeIntervalSinceReferenceDate * 1000 // en milisegundos
            println("IOSWebSocket: Sending ping at time: $lastPingSentTime")

            // Usar sendPingWithPongReceiveHandler para ping/pong nativo de WebSocket
            webSocket?.sendPingWithPongReceiveHandler { error ->
                val currentTime = NSDate.timeIntervalSinceReferenceDate * 1000

                if (error != null) {
                    println("IOSWebSocket: Error sending ping: ${error.localizedDescription}")
                    dispatch_async(dispatch_get_main_queue()) {
                        listener?.onError(Exception("Ping error: ${error.localizedDescription}"))
                    }
                } else {
                    val latency = currentTime - lastPingSentTime
                    println("IOSWebSocket: Received pong response, latency: $latency ms")
                    dispatch_async(dispatch_get_main_queue()) {
                        listener?.onPong(latency.toLong())
                    }
                }
            }
        } catch (e: Exception) {
            println("IOSWebSocket: Exception sending ping: ${e.message}")
            dispatch_async(dispatch_get_main_queue()) {
                listener?.onError(Exception("Ping error: ${e.message}"))
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun startPingTimer(intervalMs: Long) {
        stopPingTimer() // Detener timer existente si hay uno

        val interval = intervalMs / 1000.0 // Convertir a segundos para NSTimer
        println("IOSWebSocket: Starting ping timer with interval $interval seconds")

        // Crear un NSTimer y programarlo en el runloop principal
        dispatch_async(dispatch_get_main_queue()) {
            // Crear el timer con un bloque que capture self correctamente
            pingTimer = NSTimer.scheduledTimerWithTimeInterval(
                interval,
                true  // repeats
            ) { timer ->
                println("IOSWebSocket: Timer fired, checking connection...")
                if (isConnectedFlag && webSocket != null) {
                    println("IOSWebSocket: Connection active, sending ping...")
                    sendPing()
                } else {
                    println("IOSWebSocket: Connection not active, stopping timer")
                    timer?.invalidate()
                    pingTimer = null
                }
            }

            // Asegurar que el timer se agregue al runloop principal
            NSRunLoop.mainRunLoop.addTimer(pingTimer!!, NSDefaultRunLoopMode)
            println("IOSWebSocket: Ping timer created and added to runloop")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun stopPingTimer() {
        dispatch_async(dispatch_get_main_queue()) {
            pingTimer?.invalidate()
            pingTimer = null
        }
        println("IOSWebSocket: Ping timer stopped")
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun startRegistrationRenewalTimer(checkIntervalMs: Long, renewBeforeExpirationMs: Long) {
        stopRegistrationRenewalTimer() // Detener timer existente si hay uno

        this.registrationCheckIntervalMs = checkIntervalMs.toDouble()
        this.renewBeforeExpirationMs = renewBeforeExpirationMs.toDouble()

        val interval = checkIntervalMs / 1000.0 // Convertir a segundos para NSTimer
        println("IOSWebSocket: Starting registration renewal timer with interval $interval seconds")

        // Crear un NSTimer y programarlo en el runloop principal
        dispatch_async(dispatch_get_main_queue()) {
            // Ejecutar inmediatamente la primera verificación
            checkRegistrationRenewals()

            // Crear el timer
            registrationRenewalTimer = NSTimer.scheduledTimerWithTimeInterval(
                interval,
                true  // repeats
            ) { timer ->
                println("IOSWebSocket: Registration renewal timer fired")
                if (isConnectedFlag) {
                    checkRegistrationRenewals()
                } else {
                    println("IOSWebSocket: Not connected, stopping registration renewal timer")
                    timer?.invalidate()
                    registrationRenewalTimer = null
                }
            }

            // Asegurar que el timer se agregue al runloop principal
            NSRunLoop.mainRunLoop.addTimer(registrationRenewalTimer!!, NSDefaultRunLoopMode)
            println("IOSWebSocket: Registration renewal timer created and added to runloop")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun stopRegistrationRenewalTimer() {
        dispatch_async(dispatch_get_main_queue()) {
            registrationRenewalTimer?.invalidate()
            registrationRenewalTimer = null
        }
        println("IOSWebSocket: Registration renewal timer stopped")
    }

    override fun setRegistrationExpiration(accountKey: String, expirationTimeMs: Long) {
        registrationExpirations[accountKey] = expirationTimeMs.toDouble()
        println("IOSWebSocket: Set registration expiration for $accountKey at $expirationTimeMs")
    }

    override fun renewRegistration(accountKey: String) {
        println("IOSWebSocket: Requesting registration renewal for $accountKey")
        dispatch_async(dispatch_get_main_queue()) {
            listener?.onRegistrationRenewalRequired(accountKey)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun checkRegistrationRenewals() {
        val currentTime = NSDate.timeIntervalSinceReferenceDate * 1000 // en milisegundos

        // Crear una copia para evitar problemas de concurrencia
        val expirationsToCheck = HashMap(registrationExpirations)

        for ((accountKey, expirationTime) in expirationsToCheck) {
            val timeToExpiration = expirationTime - currentTime

            // Si faltan menos del tiempo configurado para que expire, renovar el registro
            if (timeToExpiration < renewBeforeExpirationMs) {
                println("IOSWebSocket: Registration for $accountKey is about to expire in ${timeToExpiration / 1000} seconds. Renewing...")
                renewRegistration(accountKey)
            }
        }
    }
}