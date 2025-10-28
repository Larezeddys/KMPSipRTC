package com.eddyslarez.kmpsiprtc.core

import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.data.models.RegistrationState
import com.eddyslarez.kmpsiprtc.data.models.SipConfig
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.sip.SipMessageHandler
import com.eddyslarez.kmpsiprtc.services.webSocket.MultiplatformWebSocket
import com.eddyslarez.kmpsiprtc.services.webSocket.createWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

class SharedWebSocketManager(
    private val config: SipConfig,
    private val messageHandler: SipMessageHandler,
    private val sipCoreManager: SipCoreManager
) {
    companion object {
        private const val TAG = "SharedWebSocketManager"
        private const val WEBSOCKET_PROTOCOL = "sip"
        private const val RECONNECT_DELAY = 3000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }
    private var lastPongTimestamp = 0L
    private var webSocketClient: MultiplatformWebSocket? = null
    private var isConnecting = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private val connectionMutex = Mutex()

    // Mantener track de qué cuentas están usando esta conexión
    private val registeredAccounts = mutableSetOf<String>()

    /**
     * Conectar el WebSocket compartido
     */
    suspend fun connect(): Boolean = connectionMutex.withLock {
        if (webSocketClient?.isConnected() == true) {
            log.d(tag = TAG) { "✅ WebSocket already connected" }
            return true
        }

        if (isConnecting) {
            log.d(tag = TAG) { "⏳ Connection already in progress" }
            return false
        }

        try {
            isConnecting = true
            log.d(tag = TAG) { "🔌 Connecting shared WebSocket to: ${config.webSocketUrl}" }

            val headers = createHeaders()
            webSocketClient = createWebSocket(config.webSocketUrl, headers)

            setupWebSocketListeners()
            webSocketClient?.connect()
            webSocketClient?.startPingTimer(config.pingIntervalMs)

            // Esperar confirmación de conexión
            var waitTime = 0L
            val maxWait = 10000L
            while (waitTime < maxWait && webSocketClient?.isConnected() != true) {
                delay(100)
                waitTime += 100
            }

            val connected = webSocketClient?.isConnected() == true
            if (connected) {
                log.d(tag = TAG) { "✅ Shared WebSocket connected successfully" }
                reconnectAttempts = 0
            } else {
                log.e(tag = TAG) { "❌ WebSocket connection timeout" }
            }

            return connected

        } catch (e: Exception) {
            log.e(tag = TAG) { "❌ Error connecting WebSocket: ${e.message}" }
            return false
        } finally {
            isConnecting = false
        }
    }

    /**
     * Registrar una cuenta usando el WebSocket compartido
     */
    suspend fun registerAccount(accountInfo: AccountInfo, isBackground: Boolean = false): Boolean {
        // Asegurar que el WebSocket está conectado
        if (!ensureConnected()) {
            log.e(tag = TAG) { "❌ Cannot register account - WebSocket not connected" }
            return false
        }

        return try {
            val accountKey = "${accountInfo.username}@${accountInfo.domain}"
            log.d(tag = TAG) { "📝 Registering account via shared WebSocket: $accountKey" }

            // Enviar REGISTER
            messageHandler.sendRegister(accountInfo, isBackground)

            // Agregar a cuentas registradas
            registeredAccounts.add(accountKey)

            log.d(tag = TAG) { "✅ Register message sent for: $accountKey" }
            true

        } catch (e: Exception) {
            log.e(tag = TAG) { "❌ Error registering account: ${e.message}" }
            false
        }
    }

    fun isWebSocketHealthy(): Boolean {
        // Si no hay cliente o no está conectado, ya está mal
        val socketConnected = webSocketClient?.isConnected() == true
        if (!socketConnected) return false

        // Si nunca se recibió pong, asumimos que está bien si recién se conectó
        if (lastPongTimestamp == 0L) return true

        // Cuánto tiempo pasó desde el último pong
        val elapsed = Clock.System.now().toEpochMilliseconds() - lastPongTimestamp

        // Considerar no saludable si pasó más de 2 intervalos de ping
        return elapsed < (config.pingIntervalMs * 2)
    }

    /**
     * Des-registrar una cuenta
     */
    suspend fun unregisterAccount(accountInfo: AccountInfo): Boolean {
        if (!isConnected()) {
            log.w(tag = TAG) { "⚠️ Cannot unregister - WebSocket not connected" }
            return false
        }

        return try {
            val accountKey = "${accountInfo.username}@${accountInfo.domain}"
            log.d(tag = TAG) { "📝 Unregistering account: $accountKey" }

            messageHandler.sendUnregister(accountInfo)
            registeredAccounts.remove(accountKey)

            log.d(tag = TAG) { "✅ Unregister message sent for: $accountKey" }
            true

        } catch (e: Exception) {
            log.e(tag = TAG) { "❌ Error unregistering account: ${e.message}" }
            false
        }
    }

    /**
     * Enviar mensaje SIP arbitrario
     */
    suspend fun sendMessage(message: String): Boolean {
        if (!ensureConnected()) {
            log.e(tag = TAG) { "❌ Cannot send message - WebSocket not connected" }
            return false
        }

        return try {
            webSocketClient?.send(message)
            true
        } catch (e: Exception) {
            log.e(tag = TAG) { "❌ Error sending message: ${e.message}" }
            false
        }
    }

    /**
     * Verificar si está conectado
     */
    fun isConnected(): Boolean = webSocketClient?.isConnected() == true

    /**
     * Asegurar que hay conexión activa
     */
    private suspend fun ensureConnected(): Boolean {
        if (isConnected()) return true
        return connect()
    }

    /**
     * Configurar listeners del WebSocket
     */
    private fun setupWebSocketListeners() {
        webSocketClient?.setListener(object : MultiplatformWebSocket.Listener {
            override fun onOpen() {
                log.d(tag = TAG) { "🔓 Shared WebSocket opened" }
                reconnectAttempts = 0

                // Re-registrar todas las cuentas que estaban registradas
                CoroutineScope(Dispatchers.IO).launch {
                    reregisterAllAccounts()
                }
            }

            override fun onMessage(message: String) {
                CoroutineScope(Dispatchers.IO).launch {
                    // Determinar a qué cuenta pertenece el mensaje
                    val accountInfo = determineAccountFromMessage(message)

                    if (accountInfo != null) {
                        messageHandler.handleSipMessage(message, accountInfo)
                    } else {
                        log.w(tag = TAG) { "⚠️ Could not determine account for message" }
                        // Procesar con primera cuenta disponible como fallback
                        sipCoreManager.activeAccounts.values.firstOrNull()?.let { account ->
                            messageHandler.handleSipMessage(message, account)
                        }
                    }
                }
            }

            override fun onClose(code: Int, reason: String) {
                log.w(tag = TAG) { "🔒 Shared WebSocket closed: $code - $reason" }

                // Marcar todas las cuentas como no registradas
                registeredAccounts.forEach { accountKey ->
                    sipCoreManager.updateRegistrationState(accountKey, RegistrationState.NONE)
                }

                // Intentar reconexión si no fue cierre normal
                if (code != 1000 && !sipCoreManager.isShuttingDown) {
                    scheduleReconnect()
                }
            }

            override fun onError(error: Exception) {
                log.e(tag = TAG) { "💥 WebSocket error: ${error.message}" }

                // Marcar cuentas como fallidas
                registeredAccounts.forEach { accountKey ->
                    sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)
                }
            }

            override fun onPong(timeMs: Long) {
                lastPongTimestamp = Clock.System.now().toEpochMilliseconds()
                log.d(tag = TAG) { "🏓 Pong received: ${timeMs}ms" }
            }


            override fun onRegistrationRenewalRequired(accountKey: String) {
                CoroutineScope(Dispatchers.IO).launch {
                    val account = sipCoreManager.activeAccounts[accountKey]
                    if (account != null && isConnected()) {
                        log.d(tag = TAG) { "🔄 Renewing registration for: $accountKey" }
                        registerAccount(account, sipCoreManager.isAppInBackground)
                    }
                }
            }
        })
    }

    /**
     * Determinar a qué cuenta pertenece un mensaje SIP
     */
    private fun determineAccountFromMessage(message: String): AccountInfo? {
        return try {
            // Buscar línea To: o From: para identificar la cuenta
            val lines = message.lines()

            // Buscar en To: header
            val toLine = lines.firstOrNull { it.startsWith("To:", ignoreCase = true) }
            if (toLine != null) {
                val username = extractUsername(toLine)
                val domain = extractDomain(toLine)

                if (username != null && domain != null) {
                    val accountKey = "$username@$domain"
                    return sipCoreManager.activeAccounts[accountKey]
                }
            }

            // Buscar en From: header como fallback
            val fromLine = lines.firstOrNull { it.startsWith("From:", ignoreCase = true) }
            if (fromLine != null) {
                val username = extractUsername(fromLine)
                val domain = extractDomain(fromLine)

                if (username != null && domain != null) {
                    val accountKey = "$username@$domain"
                    return sipCoreManager.activeAccounts[accountKey]
                }
            }

            // Si no se puede determinar, usar la cuenta actual
            sipCoreManager.currentAccountInfo

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error determining account from message: ${e.message}" }
            null
        }
    }

    private fun extractUsername(line: String): String? {
        val regex = "sip:([^@]+)@".toRegex()
        return regex.find(line)?.groupValues?.get(1)
    }

    private fun extractDomain(line: String): String? {
        val regex = "@([^>;]+)".toRegex()
        return regex.find(line)?.groupValues?.get(1)
    }

    /**
     * Re-registrar todas las cuentas después de reconexión
     */
    private suspend fun reregisterAllAccounts() {
        log.d(tag = TAG) { "🔄 Re-registering ${registeredAccounts.size} accounts" }

        val accountsToRegister = registeredAccounts.toList()

        accountsToRegister.forEach { accountKey ->
            val account = sipCoreManager.activeAccounts[accountKey]
            if (account != null) {
                delay(500) // Pequeño delay entre registros
                registerAccount(account, sipCoreManager.isAppInBackground)
            }
        }
    }

    /**
     * Programar reconexión automática
     */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            log.e(tag = TAG) { "❌ Max reconnection attempts reached" }
            return
        }

        reconnectAttempts++

        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            val delay = RECONNECT_DELAY * reconnectAttempts
            log.d(tag = TAG) { "⏰ Scheduling reconnect attempt $reconnectAttempts in ${delay}ms" }

            delay(delay)

            if (sipCoreManager.networkManager.isNetworkAvailable()) {
                log.d(tag = TAG) { "🔄 Attempting reconnection..." }
                connect()
            } else {
                log.w(tag = TAG) { "⚠️ Network not available for reconnection" }
            }
        }
    }

    /**
     * Cerrar conexión
     */
    suspend fun disconnect() = connectionMutex.withLock {
        try {
            log.d(tag = TAG) { "🔌 Disconnecting shared WebSocket" }

            reconnectJob?.cancel()

            webSocketClient?.stopPingTimer()
            webSocketClient?.stopRegistrationRenewalTimer()
            webSocketClient?.close(1000, "Normal disconnect")
            webSocketClient = null

            registeredAccounts.clear()
            reconnectAttempts = 0

            log.d(tag = TAG) { "✅ Shared WebSocket disconnected" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "❌ Error disconnecting: ${e.message}" }
        }
    }
    fun setRegistrationExpiration(accountKey: String, expirationTimeMs: Long){
        webSocketClient?.setRegistrationExpiration(accountKey,expirationTimeMs)
    }

    /**
     * Obtener información de estado
     */
    fun getStatus(): Map<String, Any> = mapOf(
        "connected" to isConnected(),
        "connecting" to isConnecting,
        "reconnectAttempts" to reconnectAttempts,
        "registeredAccountsCount" to registeredAccounts.size,
        "registeredAccounts" to registeredAccounts.toList()
    )

    private fun createHeaders(): HashMap<String, String> = hashMapOf(
        "User-Agent" to config.userAgent,
        "Origin" to "https://telephony.${config.defaultDomain}",
        "Sec-WebSocket-Protocol" to WEBSOCKET_PROTOCOL
    )

    fun dispose() {
        CoroutineScope(Dispatchers.IO).launch {
            disconnect()
        }
    }
}