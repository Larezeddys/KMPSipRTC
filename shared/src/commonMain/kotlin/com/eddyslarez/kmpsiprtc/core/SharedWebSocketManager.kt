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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.ExperimentalTime

/**
 * Estado de la conexion WebSocket visible para la app
 */
enum class WebSocketConnectionState {
    CONNECTED,
    CONNECTING,
    RECONNECTING,
    DEGRADED,
    DISCONNECTED
}

class SharedWebSocketManager(
    private val config: SipConfig,
    private val messageHandler: SipMessageHandler,
    private val sipCoreManager: SipCoreManager
) {
    companion object {
        private const val TAG = "SharedWebSocketManager"
        private const val WEBSOCKET_PROTOCOL = "sip"

        // Reconexion robusta con backoff exponencial
        private const val RECONNECT_BASE_DELAY = 2000L    // 2s inicial
        private const val RECONNECT_MAX_DELAY = 30000L     // 30s cap
        private const val RECONNECT_DEGRADED_DELAY = 60000L // 60s despues de degradado
        private const val RECONNECT_JITTER_FACTOR = 0.1    // 10% jitter
        private const val DEGRADED_THRESHOLD = 10           // Intentos antes de notificar degradado
        private const val MAX_RECONNECT_ATTEMPTS = 30       // Limite maximo de intentos de reconexion
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastPongTimestamp = 0L
    private var webSocketClient: MultiplatformWebSocket? = null
    private var isConnecting = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private val connectionMutex = Mutex()
    private var lastError: Exception? = null
    private var disconnectedSince = 0L  // Timestamp cuando se perdio conexion
    private var lastErrorTimestamp = 0L // Para debounce de onError()
    private var isForceReconnecting = false // Guard contra forceReconnect() concurrente

    // Estado de conexion observable
    private val _connectionState = MutableStateFlow(WebSocketConnectionState.DISCONNECTED)
    val connectionState: StateFlow<WebSocketConnectionState> = _connectionState.asStateFlow()

    // Listener externo para eventos de conexion
    private var connectionEventListener: ConnectionEventListener? = null

    // Mantener track de que cuentas estan usando esta conexion
    private val registeredAccounts = mutableSetOf<String>()

    // Jobs de renovacion de registro per-account
    private val renewalJobs = mutableMapOf<String, Job>()

    /**
     * Listener para eventos de estado de conexion - propaga a la app
     */
    interface ConnectionEventListener {
        fun onConnectionDegraded(attemptCount: Int, lastError: Exception?)
        fun onConnectionRestored(downTimeMs: Long)
        fun onConnectionFailed(error: Exception) {}  // Default vacío para retrocompatibilidad
    }

    fun setConnectionEventListener(listener: ConnectionEventListener?) {
        connectionEventListener = listener
    }

    /**
     * Conectar el WebSocket compartido
     */
    suspend fun connect(): Boolean = connectionMutex.withLock {
        if (webSocketClient?.isConnected() == true) {
            log.d(tag = TAG) { "WebSocket already connected" }
            return true
        }

        if (isConnecting) {
            log.d(tag = TAG) { "Connection already in progress" }
            return false
        }

        try {
            isConnecting = true
            _connectionState.value = WebSocketConnectionState.CONNECTING
            log.d(tag = TAG) { "Connecting shared WebSocket to: ${config.webSocketUrl}" }

            val headers = createHeaders()
            webSocketClient = createWebSocket(config.webSocketUrl, headers)

            setupWebSocketListeners()
            webSocketClient?.connect()
            webSocketClient?.startPingTimer(config.pingIntervalMs)

            // Esperar confirmacion de conexion
            var waitTime = 0L
            val maxWait = 10000L
            while (waitTime < maxWait && webSocketClient?.isConnected() != true) {
                delay(100)
                waitTime += 100
            }

            val connected = webSocketClient?.isConnected() == true
            if (connected) {
                log.d(tag = TAG) { "Shared WebSocket connected successfully" }
                onConnectionSuccess()
            } else {
                log.e(tag = TAG) { "WebSocket connection timeout" }
                _connectionState.value = WebSocketConnectionState.DISCONNECTED
            }

            return connected

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error connecting WebSocket: ${e.message}" }
            lastError = e
            _connectionState.value = WebSocketConnectionState.DISCONNECTED
            return false
        } finally {
            isConnecting = false
        }
    }

    /**
     * Llamado cuando la conexion se establece exitosamente
     */
    @OptIn(ExperimentalTime::class)
    private fun onConnectionSuccess() {
        val previousAttempts = reconnectAttempts
        val wasDisconnectedSince = disconnectedSince

        reconnectAttempts = 0
        lastError = null
        _connectionState.value = WebSocketConnectionState.CONNECTED

        // Notificar restauracion si estaba desconectado
        if (wasDisconnectedSince > 0L) {
            val downTimeMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - wasDisconnectedSince
            disconnectedSince = 0L
            log.d(tag = TAG) { "Connection restored after ${downTimeMs}ms (was $previousAttempts attempts)" }
            connectionEventListener?.onConnectionRestored(downTimeMs)
        }
    }

    /**
     * Registrar una cuenta usando el WebSocket compartido
     */
    suspend fun registerAccount(accountInfo: AccountInfo, isBackground: Boolean = false): Boolean {
        // Verificar salud del WebSocket antes de registrar
        if (!isWebSocketHealthy()) {
            log.w(tag = TAG) { "WebSocket not healthy, forcing reconnection before register" }
            forceReconnect()
            // Esperar un poco para que se establezca la conexion
            delay(2000)
            if (!isWebSocketHealthy()) {
                log.e(tag = TAG) { "Cannot register account - WebSocket still not healthy after reconnect" }
                return false
            }
        }

        if (!ensureConnected()) {
            log.e(tag = TAG) { "Cannot register account - WebSocket not connected" }
            return false
        }

        return try {
            val accountKey = "${accountInfo.username}@${accountInfo.domain}"
            log.d(tag = TAG) { "Registering account via shared WebSocket: $accountKey" }

            // Enviar REGISTER
            messageHandler.sendRegister(accountInfo, isBackground)

            // Agregar a cuentas registradas
            registeredAccounts.add(accountKey)

            log.d(tag = TAG) { "Register message sent for: $accountKey" }
            true

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error registering account: ${e.message}" }
            false
        }
    }

    /**
     * Verificar salud del WebSocket basado en conexion + ultimo pong
     */
    @OptIn(ExperimentalTime::class)
    fun getConnectionState(): WebSocketConnectionState = _connectionState.value

    fun getReconnectAttempts(): Int = reconnectAttempts

    @OptIn(ExperimentalTime::class)
    fun isWebSocketHealthy(): Boolean {
        // Si no hay cliente o no esta conectado (isConnected() refleja estado real del socket iOS)
        val socketConnected = webSocketClient?.isConnected() == true
        if (!socketConnected) return false

        // NOTA: Se elimino el check "if (isConnecting) return false" porque causaba un loop infinito:
        // onOpen() llama reregisterOnlyFailedAccounts() antes de que connect() llegue al bloque
        // finally donde pone isConnecting=false. El socket YA esta conectado en este punto, por lo
        // que es seguro considerarlo saludable aunque isConnecting siga en true.

        // Si nunca se recibio pong, verificar por estado de conexion o tiempo desde apertura
        if (lastPongTimestamp == 0L) {
            // Si el socket esta conectado y el estado es CONNECTED o aun CONNECTING pero
            // el socket ya reporta isConnected()=true, es saludable para enviar SIP
            val connectedState = _connectionState.value
            return connectedState == WebSocketConnectionState.CONNECTED ||
                    (socketConnected && connectedState == WebSocketConnectionState.CONNECTING)
        }

        // Cuanto tiempo paso desde el ultimo pong
        val elapsed = kotlin.time.Clock.System.now().toEpochMilliseconds() - lastPongTimestamp

        // Considerar no saludable si paso mas de 2 intervalos de ping
        return elapsed < (config.pingIntervalMs * 2)
    }

    /**
     * Forzar reconexion cerrando la conexion actual.
     * Guard: si ya hay un forceReconnect en curso, ignorar la solicitud.
     */
    fun forceReconnect() {
        if (isForceReconnecting) {
            log.d(tag = TAG) { "forceReconnect ya en progreso, ignorando solicitud duplicada" }
            return
        }
        log.d(tag = TAG) { "Forcing WebSocket reconnection" }
        isForceReconnecting = true
        scope.launch {
            try {
                webSocketClient?.stopPingTimer()
                webSocketClient?.close(1000, "Force reconnect")
                webSocketClient = null
                connect()
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error during force reconnect: ${e.message}" }
            } finally {
                isForceReconnecting = false
            }
        }
    }

    /**
     * Des-registrar una cuenta
     */
    suspend fun unregisterAccount(accountInfo: AccountInfo): Boolean {
        if (!isConnected()) {
            log.w(tag = TAG) { "Cannot unregister - WebSocket not connected" }
            return false
        }

        return try {
            val accountKey = "${accountInfo.username}@${accountInfo.domain}"
            log.d(tag = TAG) { "Unregistering account: $accountKey" }

            messageHandler.sendUnregister(accountInfo)
            registeredAccounts.remove(accountKey)

            log.d(tag = TAG) { "Unregister message sent for: $accountKey" }
            true

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error unregistering account: ${e.message}" }
            false
        }
    }

    /**
     * Enviar mensaje SIP arbitrario
     */
    suspend fun sendMessage(message: String): Boolean {
        if (!ensureConnected()) {
            log.e(tag = TAG) { "Cannot send message - WebSocket not connected" }
            return false
        }

        return try {
            webSocketClient?.send(message)
            true
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending message: ${e.message}" }
            false
        }
    }

    /**
     * Verificar si esta conectado
     */
    fun isConnected(): Boolean = webSocketClient?.isConnected() == true

    /**
     * Asegurar que hay conexion activa
     */
    private suspend fun ensureConnected(): Boolean {
        if (isConnected()) return true
        return connect()
    }

    /**
     * Configurar listeners del WebSocket
     */
    @OptIn(ExperimentalTime::class)
    private fun setupWebSocketListeners() {
        webSocketClient?.setListener(object : MultiplatformWebSocket.Listener {
            override fun onOpen() {
                log.d(tag = TAG) { "Shared WebSocket opened" }
                onConnectionSuccess()

                scope.launch {
                    reregisterOnlyFailedAccounts()
                }
            }

            override fun onMessage(message: String) {
                scope.launch {
                    // Determinar a que cuenta pertenece el mensaje
                    val accountInfo = determineAccountFromMessage(message)

                    if (accountInfo != null) {
                        messageHandler.handleSipMessage(message, accountInfo)
                    } else {
                        log.w(tag = TAG) { "Could not determine account for message" }
                        // Procesar con primera cuenta disponible como fallback
                        sipCoreManager.activeAccounts.values.firstOrNull()?.let { account ->
                            messageHandler.handleSipMessage(message, account)
                        }
                    }
                }
            }

            override fun onClose(code: Int, reason: String) {
                log.w(tag = TAG) { "Shared WebSocket closed: $code - $reason" }

                // Reset pong timestamp para que la proxima conexion empiece limpia
                lastPongTimestamp = 0L

                // Cancelar todas las renovaciones programadas (conexion perdida)
                cancelAllRenewals()

                // Registrar timestamp de desconexion
                if (disconnectedSince == 0L) {
                    disconnectedSince = kotlin.time.Clock.System.now().toEpochMilliseconds()
                }

                // Marcar todas las cuentas como no registradas
                registeredAccounts.forEach { accountKey ->
                    sipCoreManager.updateRegistrationState(accountKey, RegistrationState.NONE)
                }

                // Intentar reconexion si no fue cierre normal
                if (code != 1000 && !sipCoreManager.isShuttingDown) {
                    scheduleReconnect()
                } else {
                    _connectionState.value = WebSocketConnectionState.DISCONNECTED
                }
            }

            override fun onError(error: Exception) {
                // Debounce: ignorar errores repetidos dentro de 1 segundo para evitar storms
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                if (now - lastErrorTimestamp < 1000L) {
                    return
                }
                lastErrorTimestamp = now

                log.e(tag = TAG) { "WebSocket error: ${error.message}" }
                lastError = error

                // Registrar timestamp de desconexion
                if (disconnectedSince == 0L) {
                    disconnectedSince = now
                }

                // Marcar cuentas como fallidas
                registeredAccounts.forEach { accountKey ->
                    sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)
                }
            }

            override fun onPong(timeMs: Long) {
                lastPongTimestamp = kotlin.time.Clock.System.now().toEpochMilliseconds()
                log.d(tag = TAG) { "Pong received: ${timeMs}ms RTT" }
            }

            override fun onRegistrationRenewalRequired(accountKey: String) {
                scope.launch {
                    val account = sipCoreManager.activeAccounts[accountKey]
                    if (account != null && isConnected()) {
                        log.d(tag = TAG) { "Renewing registration for: $accountKey (re-REGISTER before expiry)" }
                        val success = registerAccount(account, sipCoreManager.isAppInBackground)
                        if (success) {
                            log.d(tag = TAG) { "Renewal REGISTER sent for: $accountKey" }
                        } else {
                            log.e(tag = TAG) { "Failed to send renewal REGISTER for: $accountKey" }
                        }
                    } else {
                        log.w(tag = TAG) { "Cannot renew $accountKey: account=${account != null}, connected=${isConnected()}" }
                    }
                }
            }

            override fun onConnectionDegraded(attemptCount: Int, lastError: Exception?) {
                log.w(tag = TAG) { "Connection degraded notification from platform (attempts: $attemptCount)" }
            }

            override fun onConnectionRestored(downTimeMs: Long) {
                log.d(tag = TAG) { "Connection restored notification from platform (downtime: ${downTimeMs}ms)" }
            }
        })
    }

    private suspend fun reregisterOnlyFailedAccounts() {
        log.d(tag = TAG) { "Re-registering ONLY failed accounts" }

        val accountsToRegister = registeredAccounts.toList().filter { accountKey ->
            val account = sipCoreManager.activeAccounts[accountKey]

            if (account != null) {
                val state = sipCoreManager.getRegistrationState(accountKey)
                val needsRegistration = !account.isRegistered.value ||
                        state == RegistrationState.FAILED ||
                        state == RegistrationState.NONE

                if (needsRegistration) {
                    log.d(tag = TAG) { "Account $accountKey needs re-registration (state: $state)" }
                    true
                } else {
                    log.d(tag = TAG) { "Account $accountKey already OK, skipping" }
                    false
                }
            } else {
                false
            }
        }

        if (accountsToRegister.isEmpty()) {
            log.d(tag = TAG) { "All accounts already registered, no action needed" }
            return
        }

        log.d(tag = TAG) { "Re-registering ${accountsToRegister.size} accounts" }

        accountsToRegister.forEach { accountKey ->
            val account = sipCoreManager.activeAccounts[accountKey]
            if (account != null) {
                delay(500) // Pequeno delay entre registros
                registerAccount(account, sipCoreManager.isAppInBackground)
            }
        }
    }


    /**
     * Determinar a que cuenta pertenece un mensaje SIP
     */
    private fun determineAccountFromMessage(message: String): AccountInfo? {
        return try {
            log.d(TAG) { "Determining account from SIP message:\n$message" }

            val lines = message.lines()

            // Buscar en To: header
            val toLine = lines.firstOrNull { it.startsWith("To:", ignoreCase = true) }
            log.d(TAG) { "To line found: $toLine" }
            if (toLine != null) {
                val username = extractUsername(toLine)
                val domain = extractDomain(toLine)
                log.d(TAG) { "Extracted from To -> username: $username, domain: $domain" }

                if (username != null && domain != null) {
                    val accountKey = "$username@$domain"
                    val account = sipCoreManager.activeAccounts[accountKey]
                    log.d(TAG) { "Looking for account key '$accountKey' in activeAccounts -> found: $account" }
                    if (account != null) return account
                }
            }

            // Buscar en From: header como fallback
            val fromLine = lines.firstOrNull { it.startsWith("From:", ignoreCase = true) }
            log.d(TAG) { "From line found: $fromLine" }
            if (fromLine != null) {
                val username = extractUsername(fromLine)
                val domain = extractDomain(fromLine)
                log.d(TAG) { "Extracted from From -> username: $username, domain: $domain" }

                if (username != null && domain != null) {
                    val accountKey = "$username@$domain"
                    val account = sipCoreManager.activeAccounts[accountKey]
                    log.d(TAG) { "Looking for account key '$accountKey' in activeAccounts -> found: $account" }
                    if (account != null) return account
                }
            }

            // Si no se puede determinar, usar la cuenta actual
            log.d(TAG) { "No account found in To/From headers, using currentAccountInfo: ${sipCoreManager.currentAccountInfo}" }
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
     * Programar reconexion con backoff exponencial hibrido:
     * - Intentos 1-10: backoff exponencial 2s->30s + jitter 10%
     * - Despues de 10: emitir onConnectionDegraded y seguir con 60s interval
     */
    @OptIn(ExperimentalTime::class)
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectAttempts++

        // Guard: limite maximo de intentos para evitar reconexion infinita
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            log.e(tag = TAG) {
                "Max reconnection attempts ($MAX_RECONNECT_ATTEMPTS) reached. Giving up."
            }
            _connectionState.value = WebSocketConnectionState.DISCONNECTED
            connectionEventListener?.onConnectionFailed(
                Exception("Max reconnection attempts ($MAX_RECONNECT_ATTEMPTS) exceeded")
            )
            return
        }

        // Determinar delay segun fase
        val delayMs: Long
        if (reconnectAttempts <= DEGRADED_THRESHOLD) {
            // Fase 1: Backoff exponencial 2s -> 4s -> 8s -> 16s -> 30s (cap) + jitter
            val exponentialDelay = RECONNECT_BASE_DELAY * (1L shl min(reconnectAttempts - 1, 4))
            val capped = min(exponentialDelay, RECONNECT_MAX_DELAY)
            val jitter = (capped * RECONNECT_JITTER_FACTOR * Random.nextDouble()).toLong()
            delayMs = capped + jitter
            _connectionState.value = WebSocketConnectionState.RECONNECTING
        } else {
            // Fase 2: Degradado - intervalo fijo de 60s, seguir intentando
            delayMs = RECONNECT_DEGRADED_DELAY
            _connectionState.value = WebSocketConnectionState.DEGRADED
        }

        // Notificar degradado al alcanzar el umbral
        if (reconnectAttempts == DEGRADED_THRESHOLD) {
            log.w(tag = TAG) { "Connection DEGRADED after $reconnectAttempts attempts" }
            connectionEventListener?.onConnectionDegraded(reconnectAttempts, lastError)
        }

        log.d(tag = TAG) { "Scheduling reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${delayMs}ms" }

        reconnectJob = scope.launch {
            delay(delayMs)

            if (sipCoreManager.networkManager.isNetworkAvailable()) {
                log.d(tag = TAG) { "Attempting reconnection #$reconnectAttempts..." }
                val success = connect()
                if (!success) {
                    scheduleReconnect()
                }
            } else {
                log.w(tag = TAG) { "Network not available for reconnection, will retry" }
                scheduleReconnect()
            }
        }
    }

    /**
     * Cerrar conexion
     */
    suspend fun disconnect() = connectionMutex.withLock {
        try {
            log.d(tag = TAG) { "Disconnecting shared WebSocket" }

            reconnectJob?.cancel()
            cancelAllRenewals()

            webSocketClient?.stopPingTimer()
            webSocketClient?.stopRegistrationRenewalTimer()
            webSocketClient?.close(1000, "Normal disconnect")
            webSocketClient = null

            registeredAccounts.clear()
            reconnectAttempts = 0
            disconnectedSince = 0L
            _connectionState.value = WebSocketConnectionState.DISCONNECTED

            log.d(tag = TAG) { "Shared WebSocket disconnected" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error disconnecting: ${e.message}" }
        }
    }

    /**
     * Programa la renovacion de registro para una cuenta especifica.
     * Cada cuenta se auto-renueva individualmente basandose en su propio Expires
     * (puede ser 300s, 600s, 1800s, o dias en push mode).
     * Se re-registra 10 segundos antes del vencimiento.
     */
    fun scheduleRegistrationRenewal(accountKey: String, expiresSeconds: Int) {
        // Cancelar renovacion anterior de esta cuenta
        renewalJobs[accountKey]?.cancel()

        // Programar renovacion 10 segundos antes del vencimiento
        val delayMs = maxOf((expiresSeconds - 10) * 1000L, 5_000L)

        renewalJobs[accountKey] = scope.launch {
            log.d(tag = TAG) { "Renewal scheduled for $accountKey: re-REGISTER in ${delayMs / 1000}s (expires in ${expiresSeconds}s)" }
            delay(delayMs)

            val account = sipCoreManager.activeAccounts[accountKey]
            if (account != null && isConnected()) {
                log.d(tag = TAG) { "Renewing registration for $accountKey (${expiresSeconds}s expired)" }
                val success = registerAccount(account, sipCoreManager.isAppInBackground)
                if (success) {
                    log.d(tag = TAG) { "Renewal REGISTER sent for $accountKey" }
                } else {
                    log.e(tag = TAG) { "Failed to send renewal REGISTER for $accountKey" }
                }
            } else {
                log.w(tag = TAG) { "Cannot renew $accountKey: account=${account != null}, connected=${isConnected()}" }
            }
        }
    }

    /**
     * Cancela todas las renovaciones programadas (al desconectar)
     */
    fun cancelAllRenewals() {
        renewalJobs.values.forEach { it.cancel() }
        renewalJobs.clear()
        log.d(tag = TAG) { "All renewal jobs cancelled" }
    }

    /**
     * Obtener informacion de estado
     */
    fun getStatus(): Map<String, Any> = mapOf(
        "connected" to isConnected(),
        "connecting" to isConnecting,
        "healthy" to isWebSocketHealthy(),
        "connectionState" to _connectionState.value.name,
        "reconnectAttempts" to reconnectAttempts,
        "registeredAccountsCount" to registeredAccounts.size,
        "registeredAccounts" to registeredAccounts.toList(),
        "lastPongTimestamp" to lastPongTimestamp
    )

    private fun createHeaders(): HashMap<String, String> = hashMapOf(
        "User-Agent" to config.userAgent,
        "Origin" to "https://telephony.${config.defaultDomain}",
        "Sec-WebSocket-Protocol" to WEBSOCKET_PROTOCOL
    )

    fun dispose() {
        try {
            reconnectJob?.cancel()
            cancelAllRenewals()
            webSocketClient?.stopPingTimer()
            webSocketClient?.stopRegistrationRenewalTimer()
            webSocketClient?.close(1000, "Dispose")
            webSocketClient = null
            registeredAccounts.clear()
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in dispose: ${e.message}" }
        } finally {
            scope.cancel()
        }
    }
}
