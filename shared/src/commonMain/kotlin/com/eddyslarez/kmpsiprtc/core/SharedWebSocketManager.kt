package com.eddyslarez.kmpsiprtc.core

import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.data.models.RegistrationState
import com.eddyslarez.kmpsiprtc.data.models.SipConfig
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.sip.SipMessageHandler
import com.eddyslarez.kmpsiprtc.services.webSocket.MultiplatformWebSocket
import com.eddyslarez.kmpsiprtc.services.webSocket.createWebSocket
import com.eddyslarez.kmpsiprtc.utils.generateNewCallId
import com.eddyslarez.kmpsiprtc.utils.generateNewFromTag
import com.eddyslarez.kmpsiprtc.utils.ConcurrentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
        private const val RECONNECT_BASE_DELAY = 500L     // 500ms inicial
        private const val RECONNECT_MAX_DELAY = 15000L    // 15s cap
        private const val RECONNECT_DEGRADED_DELAY = 60000L // 60s despues de degradado
        private const val RECONNECT_JITTER_FACTOR = 0.1    // 10% jitter
        private const val DEGRADED_THRESHOLD = 10           // Intentos antes de notificar degradado
        private const val FAILED_NOTIFY_THRESHOLD = 30      // Intentos antes de notificar onConnectionFailed (informativo)

        // Renovacion de registro (ver scheduleRegistrationRenewal)
        private const val RENEWAL_MARGIN_SECONDS = 10       // Se re-registra 10s antes de vencer
        private const val MIN_RENEWAL_DELAY_MS = 5_000L     // Suelo por si el server da un Expires ridiculo
        private const val RENEWAL_CONFIRMATION_TIMEOUT_MS = 30_000L // Reintento si el 200 OK no llega
        private const val RENEWAL_RETRY_DELAY_MS = 15_000L  // Reintento si no se pudo ni enviar

        // Diagnostico
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastPongTimestamp = 0L
    private var webSocketClient: MultiplatformWebSocket? = null
    private var isConnecting = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private val connectionMutex = Mutex()
    private val registrationMutex = Mutex()
    private data class RegistrationTransaction(
        val accountInfo: AccountInfo,
        val usesPush: Boolean,
    )

    private val registrationTransactions = ConcurrentMap<String, RegistrationTransaction>()
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
     * Generacion del socket vigente.
     *
     * Cada `connect()` crea un socket nuevo con su propio listener, pero el listener del socket
     * anterior NO se desengancha: sus callbacks siguen llegando (un `onClose` tardio del socket
     * viejo puede tardar decenas de segundos si el servidor nunca responde al Close). Si esos
     * callbacks tocan el estado compartido, pisan al socket nuevo — el caso peligroso es un
     * `onClose` tardio que ejecuta `cancelAllRenewals()` DESPUES de que el socket nuevo ya se
     * registro, dejando la cuenta sin renovacion y caducando en silencio.
     *
     * Por eso cada listener captura su generacion y se ignora si ya no es la vigente.
     */
    private var socketGeneration = 0

    /** Deadline de renovacion por cuenta, solo para el heartbeat de diagnostico. */
    private val renewalDeadlines = mutableMapOf<String, Long>()

    private var heartbeatJob: Job? = null

    /**
     * true mientras el cierre en curso lo hayamos pedido NOSOTROS (disconnect, dispose,
     * forceReconnect). Sin esto habria que deducirlo del codigo de cierre, y no vale: un
     * servidor que corta la sesion por inactividad tambien manda 1000, asi que un cierre del
     * peer se confundiria con uno nuestro y nos quedariamos sin reconectar.
     */
    private var selfInitiatedClose = false

    /** Ultima generacion cuya caida de transporte ya se proceso (dedup). */
    private var transportDownGeneration = -1

    @OptIn(ExperimentalTime::class)
    private val startedAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds()

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
            // El cliente anterior se cierra antes de soltarlo: sus timers son hilos reales y su
            // OkHttpClient tiene su propio pool. Sustituirlo sin cerrar los dejaba vivos para
            // siempre (en escritorio, ademas, hilos no-daemon que impiden salir del proceso).
            webSocketClient?.let { previous ->
                selfInitiatedClose = true
                runCatching {
                    previous.stopPingTimer()
                    previous.stopRegistrationRenewalTimer()
                    previous.close(1000, "Replaced by new connection")
                }.onFailure { e -> log.w(tag = TAG) { "Error closing previous socket: ${e.message}" } }
                selfInitiatedClose = false
            }
            // Generacion nueva: a partir de aqui los callbacks del socket anterior se ignoran.
            val generation = ++socketGeneration
            webSocketClient = createWebSocket(config.webSocketUrl, headers)

            setupWebSocketListeners(generation)
            log.d(tag = TAG) { "[WS gen=$generation] CONNECT -> ${config.webSocketUrl}" }
            webSocketClient?.connect()
            webSocketClient?.startPingTimer(config.pingIntervalMs)
            startHeartbeatIfNeeded()

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
     * Registrar una cuenta usando el WebSocket compartido.
     *
     * CRITICO para OpenSIPS con max_contacts=1:
     * Cuando se re-registra despues de una reconexion (nuevo WebSocket -> nuevo Contact),
     * primero se envia UNREGISTER (Expires:0) del Contact anterior para evitar
     * "phantom registrations" que nunca expiran.
     */
    suspend fun registerAccount(
        accountInfo: AccountInfo,
        isBackground: Boolean = false,
        skipUnregister: Boolean = false
    ): Boolean = registrationMutex.withLock {
        registerAccountLocked(accountInfo, isBackground, skipUnregister)
    }

    /**
     * Reinicia el dialogo de registro y envia el REGISTER como una sola operacion.
     * Evita mezclar Call-ID/From-Tag antiguos con un CSeq reiniciado cuando el
     * lifecycle y el guardian intentan registrar la misma cuenta a la vez.
     */
    suspend fun registerAccountWithNewDialog(
        accountInfo: AccountInfo,
        isBackground: Boolean
    ): Boolean = registrationMutex.withLock {
        accountInfo.isRegistered.value = false
        accountInfo.callId.value = generateNewCallId()
        accountInfo.fromTag.value = generateNewFromTag()
        accountInfo.resetCSeq()
        accountInfo.toTag.value = null

        log.d(tag = TAG) {
            "New registration dialog for ${accountInfo.username}@${accountInfo.domain}: " +
                "Call-ID=${accountInfo.callId.value?.take(8)}..., " +
                "From-Tag=${accountInfo.fromTag.value?.take(8)}..., CSeq=1"
        }

        registerAccountLocked(accountInfo, isBackground, skipUnregister = true)
    }

    private suspend fun registerAccountLocked(
        accountInfo: AccountInfo,
        isBackground: Boolean = false,
        skipUnregister: Boolean = false
    ): Boolean {
        // Verificar salud del WebSocket antes de registrar
        if (!isWebSocketHealthy()) {
            log.w(tag = TAG) { "WebSocket not healthy, forcing reconnection before register" }
            forceReconnect()
            delay(500)
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

            // PROBLEMA 2: Antes de re-registrar, enviar UNREGISTER si la cuenta
            // ya estaba registrada previamente. Esto asegura que OpenSIPS
            // (con max_contacts=1) libere el Contact anterior correctamente.
            // No se hace en el primer registro ni cuando skipUnregister es true.
            if (!skipUnregister && registeredAccounts.contains(accountKey) && accountInfo.isRegistered.value) {
                log.d(tag = TAG) {
                    "Sending UNREGISTER before re-REGISTER for $accountKey " +
                    "(OpenSIPS max_contacts=1 cleanup)"
                }
                try {
                    messageHandler.sendUnregister(accountInfo)
                    // Esperar breve para que el servidor procese el UNREGISTER
                    delay(300)
                } catch (e: Exception) {
                    log.w(tag = TAG) { "UNREGISTER before re-REGISTER failed (non-fatal): ${e.message}" }
                    // Continuar con el registro aunque falle el UNREGISTER
                }
            }

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
                selfInitiatedClose = true
                webSocketClient?.stopPingTimer()
                webSocketClient?.close(1000, "Force reconnect")
                webSocketClient = null
                selfInitiatedClose = false
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
    private fun setupWebSocketListeners(generation: Int) {
        webSocketClient?.setListener(object : MultiplatformWebSocket.Listener {

            /** true si este callback viene de un socket ya sustituido: hay que ignorarlo. */
            private fun isStale(event: String): Boolean {
                if (generation == socketGeneration) return false
                log.w(tag = TAG) { "[WS gen=$generation] $event IGNORED (stale, current gen=$socketGeneration)" }
                return true
            }

            override fun onOpen() {
                if (isStale("OPEN")) return
                log.d(tag = TAG) { "[WS gen=$generation] OPEN" }
                onConnectionSuccess()

                scope.launch {
                    reregisterOnlyFailedAccounts()
                }
            }

            override fun onMessage(message: String) {
                if (isStale("MESSAGE")) return
                scope.launch {
                    // Determinar a que cuenta pertenece el mensaje
                    val accountInfo = determineAccountFromMessage(message)

                    if (accountInfo != null) {
                        messageHandler.handleSipMessage(message, accountInfo)
                        completeRegistrationTransaction(message)
                    } else {
                        log.w(tag = TAG) { "Could not determine account for message" }
                        // Procesar con primera cuenta disponible como fallback
                        sipCoreManager.activeAccounts.values.firstOrNull()?.let { account ->
                            messageHandler.handleSipMessage(message, account)
                            completeRegistrationTransaction(message)
                        }
                    }
                }
            }

            override fun onClose(code: Int, reason: String) {
                if (isStale("CLOSE($code)")) return
                log.w(tag = TAG) { "[WS gen=$generation] CLOSE code=$code reason=\"$reason\" self=$selfInitiatedClose" }
                // Cualquier cierre que NO hayamos pedido nosotros exige reconectar, tenga el
                // codigo que tenga: un servidor que corta por inactividad manda 1000 igual que
                // nosotros al desconectar a proposito.
                handleTransportDown(
                    generation = generation,
                    event = "onClose($code)",
                    reconnect = !selfInitiatedClose,
                    // Cierre ordenado: el binding ya no existe, pero no es un error que
                    // haya que pintar en rojo al usuario.
                    targetState = RegistrationState.NONE,
                )
            }

            override fun onError(error: Exception) {
                if (isStale("ERROR")) return

                // El debounce es SOLO para el log: antes hacia `return` y con el se iba tambien
                // la recuperacion. Un send() fallido puede consumir la ventana de 1s y hacer que
                // el error siguiente — el de la caida real — se descartara entero.
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                val isRepeatedError = now - lastErrorTimestamp < 1000L
                lastErrorTimestamp = now
                lastError = error
                if (!isRepeatedError) {
                    log.e(tag = TAG) { "[WS gen=$generation] ERROR ${error::class.simpleName}: ${error.message}" }
                }

                // CRITICO: OkHttp NO vuelve a llamar a ningun callback despues de onFailure —
                // onClosed no llega nunca. Si aqui no se programa la reconexion, un socket que
                // muere en silencio (NAT/router tirando el TCP ocioso) deja la app sorda hasta
                // que otra cosa la despierte por casualidad. Por eso onClose y onError van por
                // el MISMO camino.
                handleTransportDown(
                    generation = generation,
                    event = "onError(${error::class.simpleName})",
                    reconnect = true,
                    // Fallo real del transporte: la UI debe verlo en rojo. Marcarlo NONE hacia
                    // que SipManagerImpl lo mapeara a DEFAULT y la cuenta siguiera pareciendo OK.
                    targetState = RegistrationState.FAILED,
                )
            }

            override fun onPong(timeMs: Long) {
                if (isStale("PONG")) return
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                val sinceLast = if (lastPongTimestamp == 0L) -1L else now - lastPongTimestamp
                lastPongTimestamp = now
                log.d(tag = TAG) { "[WS gen=$generation] PONG rtt=${timeMs}ms since_last=${sinceLast}ms" }
            }

            override fun onRegistrationRenewalRequired(accountKey: String) {
                scope.launch {
                    val account = sipCoreManager.activeAccounts[accountKey]
                    if (account != null && isConnected()) {
                        log.d(tag = TAG) { "Renewing registration for: $accountKey (re-REGISTER before expiry)" }
                        val success = registerAccount(
                            account,
                            sipCoreManager.isAppInBackground,
                            skipUnregister = true
                        )
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

    /**
     * Camino UNICO de "el transporte se cayo", compartido por onClose y onError.
     *
     * Antes cada uno hacia una cosa distinta y solo onClose programaba reconexion, que es
     * justo el callback que OkHttp NO invoca cuando el socket muere en silencio. El resultado
     * era una app sorda sin ningun error visible.
     *
     * Las cuentas se marcan en NONE (no en FAILED) a proposito: [reregisterOnlyFailedAccounts]
     * recoge ambos estados, pero NONE describe mejor "ya no hay binding en el servidor" y no
     * ensucia los contadores de fallo de autenticacion.
     */
    @OptIn(ExperimentalTime::class)
    private fun handleTransportDown(
        generation: Int,
        event: String,
        reconnect: Boolean,
        targetState: RegistrationState,
    ) {
        // Idempotente por socket: un mismo cierre puede notificarse por varias vias (onClosing y
        // onClosed de OkHttp, o un onError seguido del onClose). Sin esto cada muerte contaba
        // doble y programaba dos reconexiones que se pisaban.
        // No se usa un guard sobre reconnectJob porque el propio job se reprograma a si mismo
        // cuando un intento falla: bloquearlo cortaria la cadena de reintentos.
        if (transportDownGeneration == generation) {
            log.d(tag = TAG) { "[WS gen=$generation] TRANSPORT_DOWN ($event) ya procesado, ignorado" }
            return
        }
        transportDownGeneration = generation

        // El pong viejo no vale para el socket que venga: sin esto isWebSocketHealthy() devuelve
        // false sobre un socket recien abierto y provoca un forceReconnect que lo tira de nuevo.
        lastPongTimestamp = 0L

        cancelAllRenewals(reason = "$event gen=$generation")

        if (disconnectedSince == 0L) {
            disconnectedSince = kotlin.time.Clock.System.now().toEpochMilliseconds()
        }

        registeredAccounts.forEach { accountKey ->
            log.d(tag = TAG) { "[REG $accountKey] -> $targetState by=$event" }
            sipCoreManager.updateRegistrationState(accountKey, targetState)
        }

        if (reconnect && !sipCoreManager.isShuttingDown) {
            log.w(tag = TAG) {
                "[WS gen=$generation] TRANSPORT_DOWN ($event) -> cancelRenewals, accounts->NONE, scheduleReconnect"
            }
            scheduleReconnect()
        } else {
            log.d(tag = TAG) { "[WS gen=$generation] TRANSPORT_DOWN ($event) -> no reconnect (shutdown or clean close)" }
            _connectionState.value = WebSocketConnectionState.DISCONNECTED
        }
    }

    /**
     * Latido de diagnostico: una linea cada 5 minutos con el estado consolidado, aunque no pase
     * nada. Es lo que permite mirar el log del tester a las 03:00 y ver que estaba mintiendo,
     * en vez de reproducir el problema a ciegas.
     */
    @OptIn(ExperimentalTime::class)
    private fun startHeartbeatIfNeeded() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                try {
                    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                    val upSeconds = (now - startedAtMs) / 1000
                    val lastPong = if (lastPongTimestamp == 0L) "never" else "${(now - lastPongTimestamp) / 1000}s"
                    val accounts = registeredAccounts.joinToString(",") { key ->
                        val state = sipCoreManager.getRegistrationState(key)
                        val renewIn = renewalDeadlines[key]?.let { "${(it - now) / 1000}s" } ?: "none"
                        "$key:$state,renew_in=$renewIn"
                    }.ifEmpty { "none" }
                    log.i(tag = TAG) {
                        "[HEARTBEAT] up=${upSeconds}s ws=${_connectionState.value}(gen=$socketGeneration," +
                                "connected=${isConnected()},last_pong=$lastPong) accounts=[$accounts] " +
                                "reconnect_attempts=$reconnectAttempts"
                    }
                } catch (e: Exception) {
                    log.w(tag = TAG) { "Heartbeat tick failed: ${e.message}" }
                }
            }
        }
    }

    /**
     * Re-registra cuentas que fallaron o perdieron su registro.
     * Despues de reconexion WebSocket, el Contact anterior ya no es valido
     * porque la conexion cambio. Con OpenSIPS max_contacts=1, el nuevo
     * REGISTER reemplazara al anterior automaticamente gracias al +sip.instance.
     * No se envia UNREGISTER previo aqui porque la conexion anterior ya murio.
     */
    private suspend fun reregisterOnlyFailedAccounts() {
        log.d(tag = TAG) { "Re-registering failed accounts after WebSocket reconnection" }

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

        log.d(tag = TAG) { "Re-registering ${accountsToRegister.size} accounts after reconnection" }

        accountsToRegister.forEach { accountKey ->
            val account = sipCoreManager.activeAccounts[accountKey]
            if (account != null) {
                delay(500)
                // skipUnregister=true: la conexion anterior murio, no podemos enviar
                // UNREGISTER por ella. OpenSIPS reemplazara el Contact gracias a +sip.instance.
                registerAccount(account, sipCoreManager.isAppInBackground, skipUnregister = true)
            }
        }
    }


    /** Registra cada REGISTER por Call-ID y CSeq para resolver su respuesta exacta. */
    suspend fun trackRegistrationTransaction(message: String, accountInfo: AccountInfo) {
        registrationTransactionKey(message)?.let { key ->
            registrationTransactions.put(
                key,
                RegistrationTransaction(
                    accountInfo = accountInfo,
                    usesPush = message.contains(";pn-prid=", ignoreCase = true),
                )
            )
        }
    }

    private suspend fun completeRegistrationTransaction(message: String) {
        if (!message.startsWith("SIP/2.0")) return
        registrationTransactionKey(message)?.let { key ->
            registrationTransactions.remove(key)
        }
    }

    private fun registrationTransactionKey(message: String): String? {
        val lines = message.split("\r\n")
        val cseq = lines.firstOrNull { it.startsWith("CSeq:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.split(Regex("\\s+"))
            ?: return null
        if (cseq.size < 2 || !cseq[1].equals("REGISTER", ignoreCase = true)) return null
        val callId = lines.firstOrNull { it.startsWith("Call-ID:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return "$callId:${cseq[0]}"
    }

    /**
     * Determinar a que cuenta pertenece un mensaje SIP
     */
    private suspend fun determineAccountFromMessage(message: String): AccountInfo? {
        registrationTransactionKey(message)?.let { key ->
            registrationTransactions.get(key)?.let { transaction ->
                transaction.accountInfo.registrationUsesPush.value = transaction.usesPush
                log.d(TAG) { "REGISTER transaction resolved by Call-ID and CSeq: $key" }
                return transaction.accountInfo
            }
        }
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
        // Evitar overflow en desconexiones muy largas (dias): cap del contador.
        if (reconnectAttempts < Int.MAX_VALUE - 1) reconnectAttempts++

        // NUNCA rendirse: una app de llamadas no puede quedar pasivamente
        // desconectada. Al alcanzar FAILED_NOTIFY_THRESHOLD se notifica
        // onConnectionFailed UNA vez (para que la app pueda avisar al usuario),
        // pero la reconexion continua en fase degradada (60s) indefinidamente
        // mientras no sea un shutdown explicito.
        if (reconnectAttempts == FAILED_NOTIFY_THRESHOLD) {
            log.e(tag = TAG) {
                "Reconnection still failing after $FAILED_NOTIFY_THRESHOLD attempts. " +
                        "Notifying app, but continuing to retry every ${RECONNECT_DEGRADED_DELAY / 1000}s."
            }
            connectionEventListener?.onConnectionFailed(
                Exception("Reconnection failing after $FAILED_NOTIFY_THRESHOLD attempts (still retrying)")
            )
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

        log.d(tag = TAG) { "Scheduling reconnect attempt $reconnectAttempts in ${delayMs}ms" }

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
            cancelAllRenewals(reason = "disconnect()")
            heartbeatJob?.cancel()
            heartbeatJob = null

            selfInitiatedClose = true
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
     * Se re-registra [RENEWAL_MARGIN_SECONDS] segundos antes del vencimiento.
     *
     * IMPORTANTE — el bucle se RE-ARMA solo. Antes esto era un disparo unico: si el
     * re-REGISTER no salia (socket reconectando, cuenta aun no cargada) o si el 200 OK no
     * llegaba nunca, no quedaba ninguna renovacion programada y la cuenta se quedaba sin
     * renovar **para siempre**, sin ningun error visible. El sintoma era justo este: el
     * escritorio pierde el registro tras un rato sin tocar la app (pantalla bloqueada) y solo
     * revive cuando el usuario vuelve a interactuar y algo dispara un registro nuevo.
     *
     * Cuando el REGISTER si es confirmado, el 200 OK vuelve a llamar a esta funcion con el
     * Expires nuevo, lo que cancela este job y arranca otro: el bucle termina por si solo en
     * el camino feliz.
     */
    @OptIn(ExperimentalTime::class)
    fun scheduleRegistrationRenewal(accountKey: String, expiresSeconds: Int) {
        // Cancelar renovacion anterior de esta cuenta
        renewalJobs[accountKey]?.cancel()

        // Programar renovacion unos segundos antes del vencimiento
        val delayMs = maxOf((expiresSeconds - RENEWAL_MARGIN_SECONDS) * 1000L, MIN_RENEWAL_DELAY_MS)

        renewalDeadlines[accountKey] = kotlin.time.Clock.System.now().toEpochMilliseconds() + delayMs

        renewalJobs[accountKey] = scope.launch {
            log.d(tag = TAG) {
                "[REG $accountKey] renewal scheduled: renew_in=${delayMs / 1000}s expires_granted=${expiresSeconds}s"
            }
            var nextDelayMs = delayMs
            while (isActive) {
                delay(nextDelayMs)

                val account = sipCoreManager.activeAccounts[accountKey]
                if (account == null) {
                    // La cuenta ya no existe (logout, cuenta eliminada): no hay nada que renovar.
                    log.w(tag = TAG) { "Renewal loop for $accountKey stops: account no longer active" }
                    return@launch
                }

                val connected = isConnected()
                val sent = if (connected) {
                    log.d(tag = TAG) { "Renewing registration for $accountKey (expires ${expiresSeconds}s)" }
                    runCatching { registerAccount(account, sipCoreManager.isAppInBackground) }
                        .onFailure { e -> log.e(tag = TAG) { "Renewal REGISTER threw for $accountKey: ${e.message}" } }
                        .getOrDefault(false)
                } else {
                    log.w(tag = TAG) { "Cannot renew $accountKey now: socket not connected" }
                    false
                }

                nextDelayMs = if (sent) {
                    // Enviado: si el 200 OK llega, reprograma y cancela este bucle. Si no llega,
                    // este reintento es la red de seguridad.
                    RENEWAL_CONFIRMATION_TIMEOUT_MS
                } else {
                    RENEWAL_RETRY_DELAY_MS
                }
            }
        }
    }

    /**
     * Cancela todas las renovaciones programadas (al desconectar)
     */
    fun cancelAllRenewals(reason: String = "unspecified") {
        val keys = renewalJobs.keys.toList()
        renewalJobs.values.forEach { it.cancel() }
        renewalJobs.clear()
        renewalDeadlines.clear()
        if (keys.isNotEmpty()) {
            log.w(tag = TAG) { "[REG] renewal jobs CANCELLED for $keys by=$reason" }
        }
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
            selfInitiatedClose = true
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
