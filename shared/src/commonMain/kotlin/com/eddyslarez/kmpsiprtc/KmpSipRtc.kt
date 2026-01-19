package com.eddyslarez.kmpsiprtc

import androidx.compose.runtime.mutableStateOf
import com.eddyslarez.kmpsiprtc.core.*
import com.eddyslarez.kmpsiprtc.data.database.*
import com.eddyslarez.kmpsiprtc.data.database.converters.toCallLogs
import com.eddyslarez.kmpsiprtc.data.database.entities.*
import com.eddyslarez.kmpsiprtc.data.models.*
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.repository.*
import com.eddyslarez.kmpsiprtc.services.calls.*
import com.eddyslarez.kmpsiprtc.services.pushMode.PushModeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import com.eddyslarez.kmpsiprtc.core.SipCoreManager
import com.eddyslarez.kmpsiprtc.data.database.DatabaseManager
import com.eddyslarez.kmpsiprtc.services.calls.CallStateManager
import com.eddyslarez.kmpsiprtc.services.calls.MultiCallManager
import com.eddyslarez.kmpsiprtc.services.matrix.MatrixConfig
import com.eddyslarez.kmpsiprtc.services.matrix.MatrixManager
import com.eddyslarez.kmpsiprtc.services.matrix.MatrixMessage
import com.eddyslarez.kmpsiprtc.services.matrix.MatrixRoom
import com.eddyslarez.kmpsiprtc.services.unified.UnifiedCallInfo
import com.eddyslarez.kmpsiprtc.services.unified.UnifiedCallRouter
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import com.eddyslarez.kmpsiprtc.utils.Lock
import com.eddyslarez.kmpsiprtc.utils.synchronized
/**
 * KmpSipRtc - Biblioteca principal para gestión de llamadas SIP/WebRTC
 *
 * Características principales:
 * - Gestión de registros SIP
 * - Llamadas entrantes y salientes
 * - Audio WebRTC
 * - Persistencia de historial de llamadas
 * - Reconexión automática
 * - Soporte para múltiples cuentas
 *
 * @version 1.0.0
 */
class KmpSipRtc private constructor() {

    // === DEPENDENCIAS INTERNAS ===
    private var sipCoreManager: SipCoreManager? = null
    private var databaseManager: DatabaseManager? = null

    // === ESTADO INTERNO ===
    private var isInitialized = false
    private lateinit var config: SipConfig
    private var pushModeManager: PushModeManager? = null // NUEVO

    // === LISTENERS Y OBSERVABLES ===
    private val listeners = mutableSetOf<SipEventListener>()
    private var registrationListener: RegistrationListener? = null
    private var callListener: CallListener? = null
    private var incomingCallListener: IncomingCallListener? = null

    // === GESTIÓN DE ESTADOS DE NOTIFICACIÓN ===
    private val lastNotifiedRegistrationStates = mutableMapOf<String, RegistrationState>()
    private val lastNotifiedCallState = mutableStateOf<CallStateInfo?>(null)
    private var matrixManager: MatrixManager? = null
    private var unifiedCallRouter: UnifiedCallRouter? = null

    // === GESTIÓN DE CONCURRENCIA ===
    private val initMutex = Mutex()
    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        @Volatile
        private var INSTANCE: KmpSipRtc? = null
        private val LOCK = Lock()
        private const val TAG = "KmpSipRtc"

        /**
         * Obtiene la instancia singleton de KmpSipRtc
         */
        fun getInstance(): KmpSipRtc {
            return INSTANCE ?: synchronized(LOCK) {
                INSTANCE ?: KmpSipRtc().also { INSTANCE = it }
            }
        }
    }

    // === CONFIGURACIÓN ===

//    /**
//     * Configuración principal de la biblioteca SIP
//     */
//    data class SipConfiguration(
//        val defaultDomain: String = "",
//        val webSocketUrl: String = "",
//        val userAgent: String = "KmpSipRtc/1.0",
//        val enableLogs: Boolean = true,
//        val enableAutoReconnect: Boolean = true,
//        val pingIntervalMs: Long = 30000L,
//        val incomingRingtoneUri: String? = null,
//        val outgoingRingtoneUri: String? = null
//    )

    // === INTERFACES DE LISTENERS ===

    /**
     * Listener general para eventos SIP
     */
    interface SipEventListener {
        fun onRegistrationStateChanged(
            state: RegistrationState,
            username: String,
            domain: String
        ) {
        }

        fun onCallStateChanged(stateInfo: CallStateInfo) {}
        fun onIncomingCall(callInfo: IncomingCallInfo) {}
        fun onCallConnected(callInfo: CallInfo) {}
        fun onCallEnded(callInfo: CallInfo, reason: CallEndReason) {}
        fun onCallFailed(error: String, callInfo: CallInfo?) {}
        fun onDtmfReceived(digit: Char, callInfo: CallInfo) {}
        fun onAudioDeviceChanged(device: AudioDevice) {}
        fun onNetworkStateChanged(isConnected: Boolean) {}
    }

    /**
     * Listener específico para eventos de registro
     */
    interface RegistrationListener {
        fun onRegistrationSuccessful(username: String, domain: String)
        fun onRegistrationFailed(username: String, domain: String, error: String)
        fun onUnregistered(username: String, domain: String)
        fun onRegistrationExpiring(username: String, domain: String, expiresIn: Long)
    }

    /**
     * Listener específico para eventos de llamada
     */
    interface CallListener {
        fun onCallInitiated(callInfo: CallInfo)
        fun onCallRinging(callInfo: CallInfo)
        fun onCallConnected(callInfo: CallInfo)
        fun onCallHeld(callInfo: CallInfo)
        fun onCallResumed(callInfo: CallInfo)
        fun onCallEnded(callInfo: CallInfo, reason: CallEndReason)
        fun onCallTransferred(callInfo: CallInfo, transferTo: String)
        fun onMuteStateChanged(isMuted: Boolean, callInfo: CallInfo)
        fun onCallStateChanged(stateInfo: CallStateInfo)
    }

    /**
     * Listener específico para llamadas entrantes
     */
    interface IncomingCallListener {
        fun onIncomingCall(callInfo: IncomingCallInfo)
        fun onIncomingCallCancelled(callInfo: IncomingCallInfo)
        fun onIncomingCallTimeout(callInfo: IncomingCallInfo)
    }

    // === DATA CLASSES PARA INFORMACIÓN DE LLAMADAS ===

    /**
     * Información completa de una llamada
     */
    data class CallInfo(
        val callId: String,
        val phoneNumber: String,
        val displayName: String?,
        val direction: CallDirection,
        val startTime: Long,
        val duration: Long = 0,
        val isOnHold: Boolean = false,
        val isMuted: Boolean = false,
        val localAccount: String,
        val codec: String? = null,
        val state: CallState? = null,
        val isCurrentCall: Boolean = false
    )

    /**
     * Información de llamada entrante
     */
    data class IncomingCallInfo(
        val callId: String,
        val callerNumber: String,
        val callerName: String?,
        val targetAccount: String,
        val timestamp: Long,
        val headers: Map<String, String> = emptyMap()
    )

    /**
     * Dirección de la llamada
     */
    enum class CallDirection {
        INCOMING,
        OUTGOING
    }

    /**
     * Razones de finalización de llamada
     */
    enum class CallEndReason {
        NORMAL_HANGUP,
        BUSY,
        NO_ANSWER,
        REJECTED,
        NETWORK_ERROR,
        CANCELLED,
        TIMEOUT,
        ERROR
    }

    // === INICIALIZACIÓN ===

    /**
     * Inicializa la biblioteca SIP
     *
     * ✅ MEJORA: Preserva el historial de llamadas entre inicializaciones
     * ✅ MEJORA: Carga datos existentes de la base de datos
     *
     * @param config Configuración de la biblioteca
     * @param onComplete Callback con resultado de la inicialización
     */
    /**
     * Inicializa la biblioteca SIP
     *
     * ✅ MEJORA: Preserva el historial de llamadas entre inicializaciones
     * ✅ MEJORA: Carga datos existentes de la base de datos
     *
     * @param config Configuración de la biblioteca
     * @param onComplete Callback con resultado de la inicialización
     */

    /**
     * Inicializa la biblioteca SIP con soporte opcional para Matrix
     */
    fun initialize(
        config: SipConfig = SipConfig(),
        matrixConfig: MatrixConfig? = null,
        onComplete: ((Result<Unit>) -> Unit)? = null
    ) {
        if (isInitialized) {
            log.w(tag = TAG) { "Library already initialized" }
            onComplete?.invoke(Result.success(Unit))
            return
        }

        internalScope.launch {
            initMutex.withLock {
                if (isInitialized) {
                    onComplete?.invoke(Result.success(Unit))
                    return@launch
                }

                try {
                    log.d(tag = TAG) { "🚀 Initializing KmpSipRtc v1.0.0${if (matrixConfig != null) " with Matrix support" else ""}" }

                    this@KmpSipRtc.config = config

                    // 🔹 Crear instancia SIP Core
                    sipCoreManager = SipCoreManager.createInstance(
                        SipConfig(
                            defaultDomain = config.defaultDomain,
                            webSocketUrl = config.webSocketUrl,
                            userAgent = config.userAgent,
                            enableLogs = config.enableLogs,
                            enableAutoReconnect = config.enableAutoReconnect,
                            pingIntervalMs = config.pingIntervalMs,
                            incomingRingtoneUri = config.incomingRingtoneUri,
                            outgoingRingtoneUri = config.outgoingRingtoneUri
                        )
                    )

                    sipCoreManager?.initialize()

                    val manager = sipCoreManager
                        ?: throw SipLibraryException("Failed to create SipCoreManager")

                    // 🔹 Esperar inicialización de managers SIP
                    var retries = 0
                    val maxRetries = 50
                    while (retries < maxRetries) {
                        if (manager.isSipCoreManagerHealthy()) {
                            log.d(tag = TAG) { "✅ SIP managers initialized successfully" }
                            break
                        }
                        delay(100)
                        retries++
                    }

                    if (retries >= maxRetries) {
                        log.w(tag = TAG) { "⚠️ SIP initialization timeout - some managers may not be ready" }
                    }

                    // 🔹 Inicialización opcional de Matrix
                    if (matrixConfig != null) {
                        val webRtcManager = manager.webRtcManager
                            ?: throw SipLibraryException("WebRTC manager not available for Matrix")

                        val callManager = manager.callManager
                            ?: throw SipLibraryException("Call manager not available for Matrix")

                        matrixManager = MatrixManager(matrixConfig, webRtcManager)
                        matrixManager?.initialize()

                        unifiedCallRouter = UnifiedCallRouter(
                            sipCallManager = callManager,
                            matrixManager = matrixManager!!
                        )

                        log.d(tag = TAG) { "✅ Matrix support initialized successfully" }
                    }

                    // 🔹 Setup general
                    setupInternalListeners()

                    databaseManager = manager.databaseManager
                    pushModeManager = PushModeManager(config.pushModeConfig)
                    setupPushModeManager()

                    loadExistingCallHistory()

                    isInitialized = true
                    log.d(tag = TAG) {
                        "✅ KmpSipRtc initialized successfully${if (matrixConfig != null) " with Matrix support" else ""}"
                    }

                    onComplete?.invoke(Result.success(Unit))

                } catch (e: Exception) {
                    log.e(tag = TAG) { "❌ Error initializing library: ${e.message}" }
                    onComplete?.invoke(
                        Result.failure(
                            SipLibraryException("Failed to initialize library", e)
                        )
                    )
                }
            }
        }
    }


    // En KmpSipRtc.kt - AÑADIR este método
    fun verifyCallLogPersistence(onResult: (String) -> Unit) {
        checkInitialized()
        internalScope.launch {
            try {
                val report = buildString {
                    appendLine("=== CALL LOG PERSISTENCE CHECK ===")

                    // Obtener call logs de memoria
                    val memoryLogs = sipCoreManager?.callHistoryManager?.callLogs ?: emptyList()
                    appendLine("Call logs in memory: ${memoryLogs.size}")

                    // Obtener call logs de BD
                    val dbLogs = databaseManager?.getRecentCallLogs(100)?.first() ?: emptyList()
                    appendLine("Call logs in database: ${dbLogs.size}")

                    if (memoryLogs.isEmpty() && dbLogs.isEmpty()) {
                        appendLine("\nℹ️  No call logs found (make a test call first)")
                    } else if (memoryLogs.size > dbLogs.size) {
                        appendLine("\n⚠️  WARNING: More logs in memory than database")
                        appendLine("   This means logs are NOT being saved!")
                    } else if (dbLogs.size >= memoryLogs.size) {
                        appendLine("\n✅ Logs are being persisted correctly")
                    }

                    if (dbLogs.isNotEmpty()) {
                        appendLine("\nLast 3 saved calls:")
                        dbLogs.take(3).forEach { log ->
                            appendLine("  • ${log.callLog.phoneNumber}")
                            appendLine("    ${log.callLog.callType} - ${formatTimestamp(log.callLog.startTime)}")
                        }
                    }
                }
                onResult(report)
            } catch (e: Exception) {
                onResult("❌ Error checking persistence: ${e.message}")
            }
        }
    }

    suspend fun syncCallHistoryToMemory() {
        sipCoreManager?.syncCallHistoryToMemory()
    }

    // ✅ AÑADIR este nuevo método
    private suspend fun loadExistingCallHistory() {
        try {
            log.d(tag = TAG) { "🔄 Loading existing call history from database..." }

            // Cargar historial desde la base de datos
            sipCoreManager?.callHistoryManager?.initializeFromDatabase()

            val callLogs = sipCoreManager?.callLogs() ?: emptyList()
            log.d(tag = TAG) { "✅ Loaded ${callLogs.size} existing call logs" }

            // ✅ NUEVO: Verificar que los logs se cargaron correctamente
            if (callLogs.isNotEmpty()) {
                log.d(tag = TAG) { "📊 Call history preserved successfully" }
                callLogs.take(3).forEach { log ->
                    { "   • ${log.id}: ${log.direction} - ${log.callType} - ${log.duration}s" }
                }
            } else {
                log.d(tag = TAG) { "ℹ️  No existing call history found" }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "❌ Error loading existing call history: ${e.message}" }
        }
    }

    /**
     * ⚠️ SOLO USAR CUANDO SEA NECESARIO: Limpia estados iniciales
     * (Mantener comentado para preservar historial)
     */
    private fun clearInitialStates() {
        try {
            log.d(tag = TAG) { "⚠️ Clearing initial states (call this only when needed)..." }
            // Solo limpiar estados de notificación, NO el historial
            lastNotifiedRegistrationStates.clear()
            lastNotifiedCallState.value = null
            // ❌ NO limpiar CallStateManager.clearHistory() aquí
            log.d(tag = TAG) { "Notification states cleared (history preserved)" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error clearing initial states: ${e.message}" }
        }
    }

    private fun setupPushModeManager() {
        pushModeManager?.setCallbacks(
            onModeChange = { pushModeState ->
                log.d(tag = TAG) { "Push mode changed: ${pushModeState.currentMode} (${pushModeState.reason})" }

                // Notificar a listeners si es necesario
                listeners.forEach { listener ->
                    try {
                        // Podrías añadir un método onPushModeChanged al SipEventListener si lo necesitas
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error in push mode change notification: ${e.message}" }
                    }
                }
            },
            onRegistrationRequired = { accounts, mode ->
                log.d(tag = TAG) { "Registration required for ${accounts.size} accounts in $mode mode" }

                // Reregistrar cuentas según el modo
                accounts.forEach { accountKey ->
                    val parts = accountKey.split("@")
                    if (parts.size == 2) {
                        val username = parts[0]
                        val domain = parts[1]
                        log.d(tag = TAG) { "username $username domain: $domain" }

                        when (mode) {
                            PushMode.PUSH -> {
                                CoroutineScope(Dispatchers.IO).launch {

                                    log.d(tag = TAG) { "Switching $accountKey to push mode" }
                                    sipCoreManager?.switchToPushMode(username, domain)
                                }
                            }

                            PushMode.FOREGROUND -> {
                                CoroutineScope(Dispatchers.IO).launch {

                                    log.d(tag = TAG) { "Switching $accountKey to foreground mode" }
                                    sipCoreManager?.switchToForegroundMode(username, domain)
                                }
                            }

                            else -> {}
                        }
                    }
                }
            }
        )

        // Observar cambios de lifecycle de la aplicación
        setupAppLifecycleObserver()
    }

    private fun setupAppLifecycleObserver() {
        // Conectar con el PlatformRegistration para observar cambios de lifecycle
        // y notificar al PushModeManager
        CoroutineScope(Dispatchers.Main).launch {
            // Observar estados de lifecycle desde SipCoreManager
            sipCoreManager?.let { manager ->
                // Configurar observer para cambios de lifecycle
                manager.observeLifecycleChanges { event ->
                    val registeredAccounts = manager.getAllRegisteredAccountKeys()

                    when (event) {
                        "APP_BACKGROUNDED" -> {
                            log.d(tag = TAG) { "App backgrounded - notifying PushModeManager" }
                            pushModeManager?.onAppBackgrounded(registeredAccounts)
                        }

                        "APP_FOREGROUNDED" -> {
                            log.d(tag = TAG) { "App foregrounded - notifying PushModeManager" }
                            pushModeManager?.onAppForegrounded(registeredAccounts)
                        }
                    }
                }
            }
        }
    }

    // === CONFIGURACIÓN DE LISTENERS INTERNOS ===

    /**
     * Configura los listeners internos para eventos del core SIP
     */
    @OptIn(ExperimentalTime::class)
    private fun setupInternalListeners() {
        sipCoreManager?.let { manager ->
            manager.setCallbacks(object : SipCallbacks {
                override fun onCallTerminated() {
                    log.d(tag = TAG) { "Internal callback: onCallTerminated" }
                    val callInfo = getCurrentCallInfo()
                    notifyCallEnded(callInfo, CallEndReason.NORMAL_HANGUP)
                }

                override fun onAccountRegistrationStateChanged(
                    username: String,
                    domain: String,
                    state: RegistrationState
                ) {
                    log.d(tag = TAG) { "Internal callback: onAccountRegistrationStateChanged - $username@$domain -> $state" }
                    notifyRegistrationStateChanged(state, username, domain)
                }

                override fun onIncomingCall(callerNumber: String, callerName: String?) {
                    log.d(tag = TAG) { "Internal callback: onIncomingCall from $callerNumber" }
                    val callInfo = createIncomingCallInfoFromCurrentCall(callerNumber, callerName)
                    notifyIncomingCall(callInfo)
                }

                override fun onCallConnected() {
                    log.d(tag = TAG) { "Internal callback: onCallConnected" }
                    getCurrentCallInfo()?.let { notifyCallConnected(it) }
                }

                override fun onCallFailed(error: String) {
                    log.d(tag = TAG) { "Internal callback: onCallFailed - $error" }
                    val callInfo = getCurrentCallInfo()
                    notifyCallFailed(error, callInfo)
                }

                override fun onCallEndedForAccount(accountKey: String) {
                    log.d(tag = TAG) { "Internal callback: onCallEndedForAccount - $accountKey" }
                    pushModeManager?.onCallEndedForAccount(accountKey, setOf(accountKey))

                }
            })

            // Configurar observer del flujo de estados de llamada
            internalScope.launch {
                var isFirstState = true

                CallStateManager.callStateFlow.collect { stateInfo ->
                    if (isFirstState) {
                        isFirstState = false
                        if (stateInfo.timestamp > kotlin.time.Clock.System.now().toEpochMilliseconds() + 1000) {
                            log.w(tag = TAG) { "Skipping invalid initial state with future timestamp" }
                            return@collect
                        }
                        if (MultiCallManager.getAllCalls()
                                .isEmpty() && stateInfo.state != CallState.IDLE
                        ) {
                            log.w(tag = TAG) { "Skipping invalid initial state with no active calls" }
                            return@collect
                        }
                    }

                    val callInfo = getCallInfoForState(stateInfo)
                    notifyCallStateChanged(stateInfo)

                    callInfo?.let { info ->
                        when (stateInfo.state) {
                            CallState.CONNECTED -> notifyCallConnected(info)
                            CallState.OUTGOING_RINGING -> notifyCallRinging(info)
                            CallState.OUTGOING_INIT -> notifyCallInitiated(info)
                            CallState.INCOMING_RECEIVED -> handleIncomingCall()
                            CallState.ENDED -> {
                                val reason = mapErrorReasonToCallEndReason(stateInfo.errorReason)
                                notifyCallEnded(info, reason)
                                val accountKey = determineAccountKeyFromCallInfo(info)
                                if (accountKey != null) {
                                    log.d(tag = TAG) { "Notifying PushModeManager: call ended for $accountKey" }
                                    pushModeManager?.onCallEndedForAccount(
                                        accountKey,
                                        setOf(accountKey)
                                    )
                                } else {
                                    val registeredAccounts =
                                        sipCoreManager?.getAllRegisteredAccountKeys() ?: emptySet()
                                    pushModeManager?.onCallEnded(registeredAccounts)
                                }
                            }

                            CallState.PAUSED -> notifyCallHeld(info)
                            CallState.STREAMS_RUNNING -> notifyCallResumed(info)
                            CallState.ERROR -> {
                                val reason = mapErrorReasonToCallEndReason(stateInfo.errorReason)
                                notifyCallEnded(info, reason)
                            }

                            else -> {}
                        }
                    }
                }
            }
        }
    }


    /**
     * NUEVO: Determina el accountKey desde CallInfo
     */
    private fun determineAccountKeyFromCallInfo(callInfo: CallInfo): String? {
        val manager = sipCoreManager ?: return null

        // Primero intentar obtener desde la cuenta actual
        val currentAccount = manager.currentAccountInfo
        if (currentAccount != null) {
            val accountKey = "${currentAccount.username}@${currentAccount.domain}"
            log.d(tag = TAG) { "Determined account key from current account: $accountKey" }
            return accountKey
        }

        // Si no hay cuenta actual, intentar determinar desde localAccount en CallInfo
        if (callInfo.localAccount.isNotEmpty()) {
            val registeredAccounts = manager.getAllRegisteredAccountKeys()
            val matchingAccount =
                registeredAccounts.find { it.startsWith("${callInfo.localAccount}@") }
            if (matchingAccount != null) {
                log.d(tag = TAG) { "Determined account key from CallInfo localAccount: $matchingAccount" }
                return matchingAccount
            }
        }

        log.w(tag = TAG) { "Could not determine specific account key for call ${callInfo.callId}" }
        return null
    }
    // === SISTEMA DE NOTIFICACIONES ===

    /**
     * Notifica cambios en el estado de registro
     */
    private fun notifyRegistrationStateChanged(
        state: RegistrationState,
        username: String,
        domain: String
    ) {
        val accountKey = "$username@$domain"
        val lastState = lastNotifiedRegistrationStates[accountKey]

        if (lastState == state) {
            log.d(tag = TAG) { "Skipping duplicate registration state notification for $accountKey: $state" }
            return
        }

        lastNotifiedRegistrationStates[accountKey] = state
        log.d(tag = TAG) { "Notifying registration state change: $lastState -> $state for $accountKey" }

        internalScope.launch {
            try {
                listeners.forEach { listener ->
                    try {
                        listener.onRegistrationStateChanged(state, username, domain)
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error in listener onRegistrationStateChanged: ${e.message}" }
                    }
                }

                registrationListener?.let { listener ->
                    when (state) {
                        RegistrationState.OK -> {
                            listener.onRegistrationSuccessful(username, domain)
                        }

                        RegistrationState.FAILED -> {
                            listener.onRegistrationFailed(username, domain, "Registration failed")
                        }

                        RegistrationState.NONE, RegistrationState.CLEARED -> {
                            listener.onUnregistered(username, domain)
                        }

                        else -> {}
                    }
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Critical error in registration state notification: ${e.message}" }
            }
        }
    }


    /**
     * Cambia manualmente a modo foreground para cuentas específicas
     */
    fun switchToForegroundMode(accounts: Set<String>? = null) {
        checkInitialized()

        val accountsToSwitch =
            accounts ?: sipCoreManager?.getAllRegisteredAccountKeys() ?: emptySet()
        pushModeManager?.switchToForegroundMode(accountsToSwitch)

        log.d(tag = TAG) { "Manual switch to foreground mode for ${accountsToSwitch.size} accounts" }
    }

    /**
     * Obtiene el modo push actual
     */
    fun getCurrentPushMode(): PushMode {
        checkInitialized()
        return pushModeManager?.getCurrentMode() ?: PushMode.FOREGROUND
    }

    /**
     * Verifica si está en modo push
     */
    fun isInPushMode(): Boolean {
        checkInitialized()
        return pushModeManager?.isInPushMode() ?: false
    }

    /**
     * Obtiene el estado completo del modo push
     */
    @OptIn(ExperimentalTime::class)
    fun getPushModeState(): PushModeState {
        checkInitialized()
        return pushModeManager?.getCurrentState() ?: PushModeState(
            currentMode = PushMode.FOREGROUND,
            previousMode = null,
            timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            reason = "Not initialized"
        )
    }

    /**
     * Flow para observar cambios de modo push
     */
    fun getPushModeStateFlow(): Flow<PushModeState> {
        checkInitialized()
        return pushModeManager?.pushModeStateFlow ?: flowOf(getPushModeState())
    }

    /**
     * Notifica que se recibió una notificación push (para uso interno o externo)
     */
    fun onPushNotificationReceived(data: Map<String, Any>? = null) {
        log.d(tag = TAG) { "onPushNotificationReceived: inicio data : $data" }

        checkInitialized()

        if (data != null && data.containsKey("sipName")) {
            // Notificación push específica para una cuenta
            val sipName = data["sipName"] as? String
            val phoneNumber = data["phoneNumber"] as? String
            val callId = data["callId"] as? String

            log.d(tag = TAG) {
                "Push notification for specific account: sipName=$sipName, phoneNumber=$phoneNumber, callId=$callId"
            }

            if (sipName != null) {
                // Buscar la cuenta completa (sipName@domain)
                val registeredAccounts =
                    sipCoreManager?.getAllRegisteredAccountKeys() ?: emptySet()
                val specificAccount = registeredAccounts.find { it.startsWith("$sipName@") }

                if (specificAccount != null) {
                    log.d(tag = TAG) { "Found specific account for push: $specificAccount" }
                    pushModeManager?.onPushNotificationReceived(
                        specificAccount = specificAccount,
                        allRegisteredAccounts = registeredAccounts
                    )

//                    // Preparar para la llamada entrante específica
//                    prepareForIncomingCall(specificAccount, phoneNumber, callId)
                } else {
                    log.w(tag = TAG) { "Account not found for sipName: $sipName" }
                    // Fallback: cambiar todas las cuentas
                    val allAccounts =
                        sipCoreManager?.getAllRegisteredAccountKeys() ?: emptySet()
                    pushModeManager?.onPushNotificationReceived(allRegisteredAccounts = allAccounts)
                }
            }
        } else {
            // Notificación push genérica (comportamiento anterior)
            val registeredAccounts = sipCoreManager?.getAllRegisteredAccountKeys() ?: emptySet()
            pushModeManager?.onPushNotificationReceived(allRegisteredAccounts = registeredAccounts)
        }

        log.d(tag = TAG) { "Push notification processed, managing mode transition" }
    }

    fun diagnosePushMode(): String {
        return buildString {
            appendLine("=== PUSH MODE DIAGNOSTIC ===")
            appendLine("Push Mode Manager: ${pushModeManager != null}")
            appendLine("SipCore Manager: ${sipCoreManager != null}")

            pushModeManager?.let { pm ->
                appendLine("\n--- Push Mode State ---")
                appendLine(pm.getDiagnosticInfo())
            }

            sipCoreManager?.let { sm ->
                appendLine("\n--- Registered Accounts ---")
                val accounts = sm.getAllRegisteredAccountKeys()
                appendLine("Count: ${accounts.size}")
                accounts.forEach { account ->
                    appendLine("- $account")
                }

                appendLine("\n--- App State ---")
                appendLine("Is in background: ${sm.isAppInBackground}")
            }
        }
    }

    /**
     * Notifica cambios en el estado de llamada
     */
    private fun notifyCallStateChanged(stateInfo: CallStateInfo) {
        val lastState = lastNotifiedCallState.value
        val stateChanged = lastState == null ||
                lastState.state != stateInfo.state ||
                lastState.callId != stateInfo.callId ||
                lastState.errorReason != stateInfo.errorReason

        if (!stateChanged) {
            log.d(tag = TAG) { "Skipping duplicate call state notification: ${stateInfo.state}" }
            return
        }

        lastNotifiedCallState.value = stateInfo
        log.d(tag = TAG) { "Notifying call state change: ${lastState?.state} -> ${stateInfo.state}" }

        listeners.forEach { listener ->
            try {
                listener.onCallStateChanged(stateInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in listener onCallStateChanged: ${e.message}" }
            }
        }

        callListener?.let { listener ->
            try {
                listener.onCallStateChanged(stateInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in CallListener onCallStateChanged: ${e.message}" }
            }
        }
    }

    /**
     * Notifica inicio de llamada
     */
    private fun notifyCallInitiated(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call initiated" }
        try {
            callListener?.onCallInitiated(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallInitiated: ${e.message}" }
        }
    }

    /**
     * Notifica llamada conectada
     */
    private fun notifyCallConnected(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call connected" }
        listeners.forEach { listener ->
            try {
                listener.onCallConnected(callInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in listener onCallConnected: ${e.message}" }
            }
        }
        try {
            callListener?.onCallConnected(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallConnected: ${e.message}" }
        }
    }

    /**
     * Notifica llamada sonando
     */
    private fun notifyCallRinging(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call ringing" }
        try {
            callListener?.onCallRinging(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallRinging: ${e.message}" }
        }
    }

    /**
     * Notifica llamada en espera
     */
    private fun notifyCallHeld(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call held" }
        try {
            callListener?.onCallHeld(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallHeld: ${e.message}" }
        }
    }

    /**
     * Notifica llamada reanudada
     */
    private fun notifyCallResumed(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call resumed" }
        try {
            callListener?.onCallResumed(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallResumed: ${e.message}" }
        }
    }

    /**
     * Notifica finalización de llamada
     */
    private fun notifyCallEnded(callInfo: CallInfo?, reason: CallEndReason) {
        callInfo?.let { info ->
            log.d(tag = TAG) { "Notifying call ended" }
            listeners.forEach { listener ->
                try {
                    listener.onCallEnded(info, reason)
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in listener onCallEnded: ${e.message}" }
                }
            }
            try {
                callListener?.onCallEnded(info, reason)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in CallListener onCallEnded: ${e.message}" }
            }
        }
    }

    /**
     * Notifica llamada entrante
     */
    private fun notifyIncomingCall(callInfo: IncomingCallInfo) {
        log.d(tag = TAG) { "Notifying incoming call" }
        listeners.forEach { listener ->
            try {
                listener.onIncomingCall(callInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in listener onIncomingCall: ${e.message}" }
            }
        }
        try {
            incomingCallListener?.onIncomingCall(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in IncomingCallListener: ${e.message}" }
        }
    }

    /**
     * Notifica fallo en llamada
     */
    private fun notifyCallFailed(error: String, callInfo: CallInfo?) {
        log.d(tag = TAG) { "Notifying call failed" }
        listeners.forEach { listener ->
            try {
                listener.onCallFailed(error, callInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in listener onCallFailed: ${e.message}" }
            }
        }
    }

    /**
     * Maneja llamada entrante
     */
    private fun handleIncomingCall() {


        val manager = sipCoreManager ?: return
        val account = manager.currentAccountInfo ?: return
        val callData = account.currentCallData.value ?: return
        val currentAccount = manager.currentAccountInfo
        if (currentAccount != null) {
            val accountKey = "${currentAccount.username}@${currentAccount.domain}"
            log.d(tag = TAG) { "Determined account key from current account: $accountKey" }

            internalScope.launch {
                sipCoreManager!!.callLifecycleManager.onIncomingCallReceived(accountKey)
            }
        }
        val callInfo = IncomingCallInfo(
            callId = callData.callId,
            callerNumber = callData.from,
            callerName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
            targetAccount = account.username,
            timestamp = callData.startTime
        )

        notifyIncomingCall(callInfo)
    }

    /**
     * Crea información de llamada entrante desde la llamada actual
     */
    @OptIn(ExperimentalTime::class)
    private fun createIncomingCallInfoFromCurrentCall(
        callerNumber: String,
        callerName: String?
    ): IncomingCallInfo {
        val manager = sipCoreManager
        val account = manager?.currentAccountInfo
        val callData = account?.currentCallData?.value

        return IncomingCallInfo(
            callId = callData?.callId ?: generateCallId(),
            callerNumber = callerNumber,
            callerName = callerName,
            targetAccount = account?.username ?: "",
            timestamp = callData?.startTime ?: kotlin.time.Clock.System.now().toEpochMilliseconds()
        )
    }

    // === MÉTODOS AUXILIARES ===

    /**
     * Obtiene información de llamada para un estado específico
     */
    @OptIn(ExperimentalTime::class)
    private fun getCallInfoForState(stateInfo: CallStateInfo): CallInfo? {
        val manager = sipCoreManager ?: return null
        val calls = MultiCallManager.getAllCalls()
        val callData = calls.find { it.callId == stateInfo.callId }
            ?: manager.currentAccountInfo?.currentCallData?.value
            ?: return null

        val account = manager.currentAccountInfo ?: return null
        val currentCall = calls.size == 1 &&
                stateInfo.state != CallState.ENDED &&
                stateInfo.state != CallState.ERROR &&
                stateInfo.state != CallState.ENDING &&
                stateInfo.state != CallState.IDLE

        return try {
            CallInfo(
                callId = callData.callId,
                phoneNumber = if (callData.direction == CallDirections.INCOMING) callData.from else callData.to,
                displayName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
                direction = if (callData.direction == CallDirections.INCOMING) CallDirection.INCOMING else CallDirection.OUTGOING,
                startTime = callData.startTime,
                duration = if (callData.startTime > 0) kotlin.time.Clock.System.now()
                    .toEpochMilliseconds() - callData.startTime else 0,
                isOnHold = callData.isOnHold ?: false,
                isMuted = manager.webRtcManager.isMuted(),
                localAccount = account.username,
                codec = null,
                state = stateInfo.state,
                isCurrentCall = currentCall
            )
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating CallInfo: ${e.message}" }
            null
        }
    }

    /**
     * Obtiene información de la llamada actual
     */
    @OptIn(ExperimentalTime::class)
    private fun getCurrentCallInfo(): CallInfo? {
        val manager = sipCoreManager ?: return null
        val account = manager.currentAccountInfo ?: return null
        val calls = MultiCallManager.getAllCalls()
        val callData = calls.firstOrNull() ?: account.currentCallData.value ?: return null

        val currentCall = calls.size == 1 &&
                CallStateManager.getCurrentState().let { state ->
                    state.state != CallState.ENDED &&
                            state.state != CallState.ERROR &&
                            state.state != CallState.ENDING &&
                            state.state != CallState.IDLE
                }

        return try {
            val currentState = CallStateManager.getCurrentState()

            CallInfo(
                callId = callData.callId,
                phoneNumber = if (callData.direction == CallDirections.INCOMING) callData.from else callData.to,
                displayName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
                direction = if (callData.direction == CallDirections.INCOMING) CallDirection.INCOMING else CallDirection.OUTGOING,
                startTime = manager.callStartTimeMillis,
                duration = if (manager.callStartTimeMillis > 0) kotlin.time.Clock.System.now()
                    .toEpochMilliseconds() - manager.callStartTimeMillis else 0,
                isOnHold = callData.isOnHold ?: false,
                isMuted = manager.webRtcManager.isMuted(),
                localAccount = account.username,
                codec = null,
                state = currentState.state,
                isCurrentCall = currentCall
            )
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating CallInfo: ${e.message}" }
            null
        }
    }

    /**
     * Mapea razón de error a razón de finalización de llamada
     */
    private fun mapErrorReasonToCallEndReason(errorReason: CallErrorReason): CallEndReason {
        return when (errorReason) {
            CallErrorReason.BUSY -> CallEndReason.BUSY
            CallErrorReason.NO_ANSWER -> CallEndReason.NO_ANSWER
            CallErrorReason.REJECTED -> CallEndReason.REJECTED
            CallErrorReason.NETWORK_ERROR -> CallEndReason.NETWORK_ERROR
            else -> CallEndReason.ERROR
        }
    }

    /**
     * Genera ID único para llamada
     */
    @OptIn(ExperimentalTime::class)
    private fun generateCallId(): String {
        return "call_${kotlin.time.Clock.System.now().toEpochMilliseconds()}_${(1000..9999).random()}"
    }

    // === API PÚBLICA - GESTIÓN DE LISTENERS ===

    /**
     * Agrega listener para eventos SIP generales
     */
    fun addSipEventListener(listener: SipEventListener) {
        listeners.add(listener)
        log.d(tag = TAG) { "SipEventListener added. Total listeners: ${listeners.size}" }
    }

    /**
     * Remueve listener de eventos SIP
     */
    fun removeSipEventListener(listener: SipEventListener) {
        listeners.remove(listener)
        log.d(tag = TAG) { "SipEventListener removed. Total listeners: ${listeners.size}" }
    }

    /**
     * Configura listener específico para registro
     */
    fun setRegistrationListener(listener: RegistrationListener?) {
        this.registrationListener = listener
        log.d(tag = TAG) { "RegistrationListener configured" }
    }

    /**
     * Configura listener específico para llamadas
     */
    fun setCallListener(listener: CallListener?) {
        this.callListener = listener
        log.d(tag = TAG) { "CallListener configured" }
    }

    /**
     * Configura listener específico para llamadas entrantes
     */
    fun setIncomingCallListener(listener: IncomingCallListener?) {
        this.incomingCallListener = listener
        log.d(tag = TAG) { "IncomingCallListener configured" }
    }

    // === API PÚBLICA - GESTIÓN DE REGISTROS ===

    /**
     * Registra una cuenta SIP
     */
    fun registerAccount(
        username: String,
        password: String,
        domain: String,
        pushToken: String? = null,
        pushProvider: String? = null,
        forcePushMode: Boolean = false,
        onComplete: ((Result<Unit>) -> Unit)? = null
    ) {
        checkInitialized()
        log.d(tag = TAG) { "Registering account: $username@$domain" }

        internalScope.launch {
            try {
                sipCoreManager?.register(
                    username = username,
                    password = password,
                    domain = domain,
                    provider = pushProvider ?: "",
                    token = pushToken ?: "",
                    forcePushMode = forcePushMode
                )
                onComplete?.invoke(Result.success(Unit))
            } catch (e: Exception) {
                log.e(tag = TAG) { "Registration failed: ${e.message}" }
                onComplete?.invoke(Result.failure(e))
            }
        }
    }

    /**
     * Desregistra una cuenta SIP específica
     */
    fun unregisterAccount(
        username: String,
        domain: String,
        onComplete: ((Result<Unit>) -> Unit)? = null
    ) {
        checkInitialized()
        val accountKey = "$username@$domain"
        log.d(tag = TAG) { "Unregistering account: $accountKey" }
        lastNotifiedRegistrationStates.remove(accountKey)

        internalScope.launch {
            try {
                sipCoreManager?.unregister(username, domain)
                onComplete?.invoke(Result.success(Unit))
            } catch (e: Exception) {
                log.e(tag = TAG) { "Unregister failed: ${e.message}" }
                onComplete?.invoke(Result.failure(e))
            }
        }
    }

    /**
     * Desregistra todas las cuentas SIP
     */
    fun unregisterAllAccounts(onComplete: ((Result<Unit>) -> Unit)? = null) {
        checkInitialized()
        log.d(tag = TAG) { "Unregistering all accounts" }
        lastNotifiedRegistrationStates.clear()
        lastNotifiedCallState.value = null

        internalScope.launch {
            try {
                sipCoreManager?.unregisterAllAccounts()
                onComplete?.invoke(Result.success(Unit))
            } catch (e: Exception) {
                log.e(tag = TAG) { "Unregister all failed: ${e.message}" }
                onComplete?.invoke(Result.failure(e))
            }
        }
    }

    /**
     * Obtiene estado de registro de una cuenta específica
     */
    fun getRegistrationState(username: String, domain: String): RegistrationState {
        checkInitialized()
        val accountKey = "$username@$domain"
        return sipCoreManager?.getRegistrationState(accountKey) ?: RegistrationState.NONE
    }

    /**
     * Obtiene todos los estados de registro
     */
    fun getAllRegistrationStates(): Map<String, RegistrationState> {
        checkInitialized()
        return sipCoreManager?.getAllRegistrationStates() ?: emptyMap()
    }

    /**
     * Obtiene flujo de estados de registro
     */
    fun getRegistrationStatesFlow(): Flow<Map<String, RegistrationState>> {
        checkInitialized()
        return sipCoreManager?.registrationStatesFlow ?: flowOf(emptyMap())
    }

    // === API PÚBLICA - GESTIÓN DE LLAMADAS ===

    /**
     * Realiza una llamada saliente
     */
    fun makeCall(
        phoneNumber: String,
        username: String? = null,
        domain: String? = null
    ) {
        checkInitialized()

        val finalUsername = username ?: sipCoreManager?.getCurrentUsername() ?: run {
            log.e(tag = TAG) { "No username provided and no current account available" }
            throw SipLibraryException("No username available for making call")
        }

        val finalDomain = domain ?: sipCoreManager?.getDefaultDomain() ?: run {
            log.e(tag = TAG) { "No domain provided and no current account available" }
            throw SipLibraryException("No domain available for making call")
        }

        log.d(tag = TAG) { "Making call to $phoneNumber from $finalUsername@$finalDomain" }
        sipCoreManager?.makeCall(phoneNumber, finalUsername, finalDomain)
    }

    /**
     * Acepta una llamada entrante
     */
    fun acceptCall(callId: String? = null) {
        checkInitialized()
        val calls = MultiCallManager.getAllCalls()
        val targetCallId = if (callId == null && calls.size == 1) {
            log.d(tag = TAG) { "Accepting single call" }
            sipCoreManager?.acceptCall()
            return
        } else {
            callId ?: calls.firstOrNull()?.callId
        }

        if (targetCallId != null) {
            log.d(tag = TAG) { "Accepting call: $targetCallId" }
            sipCoreManager?.acceptCall(targetCallId)
        } else {
            log.w(tag = TAG) { "No call to accept" }
        }
    }

    /**
     * Rechaza una llamada entrante
     */
    fun declineCall(callId: String? = null) {
        checkInitialized()
        val calls = MultiCallManager.getAllCalls()
        val targetCallId = if (callId == null && calls.size == 1) {
            log.d(tag = TAG) { "Declining single call" }
            sipCoreManager?.declineCall()
            return
        } else {
            callId ?: calls.firstOrNull()?.callId
        }

        if (targetCallId != null) {
            log.d(tag = TAG) { "Declining call: $targetCallId" }
            sipCoreManager?.declineCall(targetCallId)
        } else {
            log.w(tag = TAG) { "No call to decline" }
        }
    }

    /**
     * Finaliza una llamada activa
     */
    fun endCall(callId: String? = null) {
        checkInitialized()
        val calls = MultiCallManager.getAllCalls()
        val targetCallId = if (callId == null && calls.size == 1) {
            log.d(tag = TAG) { "Ending single call" }
            sipCoreManager?.endCall()
            return
        } else {
            callId ?: calls.firstOrNull()?.callId
        }

        if (targetCallId != null) {
            log.d(tag = TAG) { "Ending call: $targetCallId" }
            sipCoreManager?.endCall(targetCallId)
        } else {
            log.w(tag = TAG) { "No call to end" }
        }
    }

    /**
     * Pone una llamada en espera
     */
    fun holdCall(callId: String? = null) {
        checkInitialized()
        val calls = MultiCallManager.getAllCalls()
        val targetCallId = if (callId == null && calls.size == 1) {
            log.d(tag = TAG) { "Holding single call" }
            sipCoreManager?.holdCall()
            return
        } else {
            callId ?: calls.firstOrNull()?.callId
        }

        if (targetCallId != null) {
            log.d(tag = TAG) { "Holding call: $targetCallId" }
            sipCoreManager?.holdCall(targetCallId)
        } else {
            log.w(tag = TAG) { "No call to hold" }
        }
    }

    /**
     * Reanuda una llamada en espera
     */
    fun resumeCall(callId: String? = null) {
        checkInitialized()
        val calls = MultiCallManager.getAllCalls()
        val targetCallId = if (callId == null && calls.size == 1) {
            log.d(tag = TAG) { "Resuming single call" }
            sipCoreManager?.resumeCall()
            return
        } else {
            callId ?: calls.firstOrNull()?.callId
        }

        if (targetCallId != null) {
            log.d(tag = TAG) { "Resuming call: $targetCallId" }
            sipCoreManager?.resumeCall(targetCallId)
        } else {
            log.w(tag = TAG) { "No call to resume" }
        }
    }

    /**
     * Quita una llamada de espera (alias de resumeCall)
     */
    fun unholdCall(callId: String? = null) = resumeCall(callId)

    /**
     * Obtiene todas las llamadas activas
     */
    @OptIn(ExperimentalTime::class)
    fun getAllCalls(): List<CallInfo> {
        checkInitialized()
        return MultiCallManager.getAllCalls().mapNotNull { callData ->
            try {
                val account = sipCoreManager?.currentAccountInfo ?: return@mapNotNull null
                val allActiveCalls = MultiCallManager.getActiveCalls()
                val isCurrentCall = allActiveCalls.size == 1 &&
                        allActiveCalls.first().callId == callData.callId

                CallInfo(
                    callId = callData.callId,
                    phoneNumber = if (callData.direction == CallDirections.INCOMING) callData.from else callData.to,
                    displayName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
                    direction = if (callData.direction == CallDirections.INCOMING) CallDirection.INCOMING else CallDirection.OUTGOING,
                    startTime = callData.startTime,
                    duration = if (callData.startTime > 0) kotlin.time.Clock.System.now()
                        .toEpochMilliseconds() - callData.startTime else 0,
                    isOnHold = callData.isOnHold ?: false,
                    isMuted = sipCoreManager?.webRtcManager?.isMuted() ?: false,
                    localAccount = account.username,
                    codec = null,
                    state = CallStateManager.getStateForCall(callData.callId)?.state,
                    isCurrentCall = isCurrentCall
                )
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error creating CallInfo for ${callData.callId}: ${e.message}" }
                null
            }
        }
    }

    /**
     * Limpia llamadas terminadas
     */
    fun cleanupTerminatedCalls() {
        checkInitialized()
        sipCoreManager?.cleanupTerminatedCalls()
    }

    /**
     * Verifica si hay llamadas activas
     */
    fun hasActiveCall(): Boolean {
        checkInitialized()
        return MultiCallManager.hasActiveCalls()
    }

    // === API PÚBLICA - ESTADOS DE LLAMADA ===

    /**
     * Obtiene flujo de estados de llamada
     */
    fun getCallStateFlow(): Flow<CallStateInfo> {
        checkInitialized()
        return CallStateManager.callStateFlow
    }

    /**
     * Obtiene estado actual de llamada
     */
    fun getCurrentCallState(): CallStateInfo {
        checkInitialized()
        return CallStateManager.getCurrentState()
    }

    /**
     * Obtiene historial de estados de llamada
     */
    fun getCallStateHistory(): List<CallStateInfo> {
        checkInitialized()
        return CallStateManager.getStateHistory()
    }

    /**
     * ⚠️ Limpia historial de estados de llamada (solo usar cuando sea necesario)
     */
    fun clearCallStateHistory() {
        checkInitialized()
        log.w(tag = TAG) { "User requested call state history clearance" }
        CallStateManager.clearHistory()
    }

    // === API PÚBLICA - GESTIÓN DE AUDIO ===

    /**
     * Activa/desactiva silencio
     */
    fun toggleMute() {
        checkInitialized()
        log.d(tag = TAG) { "Toggling mute" }
        sipCoreManager?.mute()

        getCurrentCallInfo()?.let { callInfo ->
            val isMuted = sipCoreManager?.webRtcManager?.isMuted() ?: false
            val mutedCallInfo = callInfo.copy(isMuted = isMuted)
            try {
                callListener?.onMuteStateChanged(isMuted, mutedCallInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in CallListener onMuteStateChanged: ${e.message}" }
            }
        }
    }

    /**
     * Verifica si está silenciado
     */
    fun isMuted(): Boolean {
        checkInitialized()
        return sipCoreManager?.webRtcManager?.isMuted() ?: false
    }

    /**
     * Cambia dispositivo de audio
     */
    fun changeAudioDevice(device: AudioDevice) {
        checkInitialized()
        sipCoreManager?.changeAudioDevice(device)
    }

    /**
     * Actualiza lista de dispositivos de audio
     */
    fun refreshAudioDevices() {
        checkInitialized()
        sipCoreManager?.refreshAudioDevices()
    }

    /**
     * Obtiene dispositivos de audio actuales
     */
    fun getCurrentAudioDevices(): Pair<AudioDevice?, AudioDevice?> {
        checkInitialized()
        return sipCoreManager?.getCurrentDevices() ?: Pair(null, null)
    }

    /**
     * Obtiene dispositivos de audio disponibles
     */
    fun getAvailableAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        checkInitialized()
        return sipCoreManager?.getAudioDevices() ?: Pair(emptyList(), emptyList())
    }

    /**
     * Prepara audio para llamada
     */
    fun prepareAudioForCall() {
        sipCoreManager?.prepareAudioForCall()
    }

    /**
     * Maneja cambio de conexión Bluetooth
     */
    fun onBluetoothConnectionChanged(isConnected: Boolean) {
        sipCoreManager?.onBluetoothConnectionChanged(isConnected)
    }

    /**
     * Actualiza dispositivos de audio con prioridad Bluetooth
     */
    fun refreshAudioDevicesWithBluetoothPriority() {
        sipCoreManager?.refreshAudioDevicesWithBluetoothPriority()
    }

    /**
     * Aplica cambio de ruta de audio
     */
    fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
        return sipCoreManager?.applyAudioRouteChange(audioUnitType) == true
    }

    /**
     * Obtiene unidades de audio disponibles
     */
    fun getAvailableAudioUnits(): Set<AudioUnit>? {
        return sipCoreManager?.getAvailableAudioUnits()
    }

    /**
     * Obtiene unidad de audio activa actual
     */
    fun getCurrentActiveAudioUnit(): AudioUnit? {
        return sipCoreManager?.getCurrentActiveAudioUnit()
    }

    // === API PÚBLICA - TONOS DE LLAMADA ===

    /**
     * Guarda URI del tono de llamada entrante
     */
    fun saveIncomingRingtoneUri(uri: String) {
        checkInitialized()
        try {
            sipCoreManager?.saveIncomingRingtoneUri(uri)
            log.d(tag = TAG) { "Incoming ringtone URI updated: $uri" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting incoming ringtone: ${e.message}" }
        }
    }

    /**
     * Guarda URI del tono de llamada saliente
     */
    fun saveOutgoingRingtoneUri(uri: String) {
        checkInitialized()
        try {
            sipCoreManager?.saveOutgoingRingtoneUri(uri)
            log.d(tag = TAG) { "Outgoing ringtone URI updated: $uri" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting outgoing ringtone: ${e.message}" }
        }
    }

    /**
     * Guarda ambas URIs de tonos
     */
    fun saveRingtoneUris(
        incomingUri: String?,
        outgoingUri: String?,
        onComplete: ((Result<Unit>) -> Unit)? = null
    ) {
        checkInitialized()
        internalScope.launch {
            try {
                sipCoreManager?.saveRingtoneUris(incomingUri, outgoingUri)
                log.d(tag = TAG) { "Both ringtone URIs updated" }
                onComplete?.invoke(Result.success(Unit))
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error setting ringtone URIs: ${e.message}" }
                onComplete?.invoke(Result.failure(e))
            }
        }
    }

    // === API PÚBLICA - DTMF ===

    /**
     * Envía tono DTMF
     */
    fun sendDtmf(digit: Char, duration: Int = 160): Boolean {
        checkInitialized()
        return sipCoreManager?.sendDtmf(digit, duration) ?: false
    }

    /**
     * Envía secuencia de tonos DTMF
     */
    fun sendDtmfSequence(digits: String, duration: Int = 160): Boolean {
        checkInitialized()
        return sipCoreManager?.sendDtmfSequence(digits, duration) ?: false
    }

    // === API PÚBLICA - HISTORIAL DE LLAMADAS ===

    /**
     * ✅ SOLUCIÓN: Obtiene historial de llamadas preservado entre inicializaciones
     */
    fun getCallLogs(limit: Int = 50): List<CallLog> {
        checkInitialized()
        return sipCoreManager?.callLogs() ?: emptyList()
    }

    /**
     * Obtiene llamadas perdidas
     */
    fun getMissedCalls(): List<CallLog> {
        checkInitialized()
        return sipCoreManager?.getMissedCalls() ?: emptyList()
    }

    /**
     * Obtiene historial de llamadas para un número específico
     */
    fun getCallLogsForNumber(phoneNumber: String): List<CallLog> {
        checkInitialized()
        return sipCoreManager?.getCallLogsForNumber(phoneNumber) ?: emptyList()
    }

    /**
     * ⚠️ Limpia TODO el historial de llamadas (solo usar cuando sea necesario)
     */
    fun clearCallLogs() {
        checkInitialized()
        log.w(tag = TAG) { "User requested call logs clearance" }
        sipCoreManager?.clearCallLogs()
    }

    /**
     * Busca en el historial de llamadas
     */
    fun searchCallLogs(
        query: String,
        onResult: (List<CallLog>) -> Unit
    ) {
        checkInitialized()
        internalScope.launch {
            try {
                val results = sipCoreManager?.searchCallLogsInDatabase(query) ?: emptyList()
                onResult(results)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error searching call logs: ${e.message}" }
                onResult(emptyList())
            }
        }
    }

    /**
     * Obtiene flujo de historial de llamadas
     */
    fun getCallLogsFlow(limit: Int = 50): Flow<List<CallLog>>? {
        checkInitialized()
        return try {
            databaseManager?.getRecentCallLogs(limit)?.map { it.toCallLogs() }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting call logs flow: ${e.message}" }
            null
        }
    }

    /**
     * Obtiene flujo de llamadas perdidas
     */
    fun getMissedCallsFlow(): Flow<List<CallLog>>? {
        checkInitialized()
        return try {
            databaseManager?.getMissedCallLogs()?.map { it.toCallLogs() }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting missed calls flow: ${e.message}" }
            null
        }
    }

    // === API PÚBLICA - DIAGNÓSTICO Y SALUD ===

    /**
     * Realiza verificación de salud del registro
     */
    fun performRegistrationHealthCheck(): String {
        checkInitialized()
        return try {
            sipCoreManager?.performRegistrationHealthCheck()
                ?: "❌ SipCoreManager not available"
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in health check: ${e.message}" }
            "❌ Health check error: ${e.message}"
        }
    }

    /**
     * Fuerza re-registro de todas las cuentas
     */
    fun forceReregisterAll(onComplete: ((String) -> Unit)? = null) {
        checkInitialized()
        internalScope.launch {
            try {
                log.d(tag = TAG) { "Force re-registration initiated" }
                val result = sipCoreManager?.forceReregisterAllAccounts()
                    ?: "❌ SipCoreManager not available"
                onComplete?.invoke(result)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in force re-registration: ${e.message}" }
                onComplete?.invoke("❌ Force re-registration error: ${e.message}")
            }
        }
    }

    /**
     * Fuerza recuperación del guardian
     */
    fun forceGuardianRecovery(onComplete: ((String) -> Unit)? = null) {
        checkInitialized()
        internalScope.launch {
            try {
                val result = sipCoreManager?.forceGuardianRecovery()
                    ?: "❌ SipCoreManager not available"
                onComplete?.invoke(result)
            } catch (e: Exception) {
                onComplete?.invoke("❌ Guardian recovery error: ${e.message}")
            }
        }
    }

    /**
     * Obtiene diagnóstico del guardian
     */
    fun getGuardianDiagnostic(onResult: (String) -> Unit) {
        checkInitialized()
        internalScope.launch {
            try {
                val result = sipCoreManager?.getGuardianDiagnostic()
                    ?: "❌ SipCoreManager not available"
                onResult(result)
            } catch (e: Exception) {
                onResult("❌ Error getting guardian diagnostic: ${e.message}")
            }
        }
    }

    /**
     * Verifica y repara conectividad
     */
    fun verifyAndFixConnectivity() {
        checkInitialized()
        internalScope.launch {
            val accounts = sipCoreManager?.activeAccounts?.values?.toList() ?: emptyList()
            if (accounts.isEmpty()) {
                val recoveredAccounts = sipCoreManager?.recoverAccountsFromDatabase() ?: emptyList()
                withContext(Dispatchers.IO) {
                    sipCoreManager?.let { manager ->
                        manager.networkManager.isNetworkAvailable()
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    sipCoreManager?.verifyAndFixConnectivity()
                }
            }
        }
    }

    /**
     * Verifica disponibilidad de red
     */
    fun isNetworkAvailable(): Boolean {
        checkInitialized()
        return sipCoreManager?.isNetworkAvailable() ?: false
    }

    /**
     * Verifica salud del sistema
     */
    fun isSystemHealthy(): Boolean {
        checkInitialized()
        return sipCoreManager?.isSipCoreManagerHealthy() ?: false
    }

    // === API PÚBLICA - DIAGNÓSTICO DE BASE DE DATOS ===

    /**
     * Ejecuta diagnóstico completo de base de datos
     */
    fun runDatabaseDiagnostic(onResult: (String) -> Unit) {
        checkInitialized()
        internalScope.launch {
            try {
                val diagnostic = DatabaseInspector(
                    databaseManager = databaseManager ?: DatabaseManager.getInstance(),
                    sipCoreManager = sipCoreManager,
                    callHistoryManager = sipCoreManager!!.callHistoryManager
                )
                val result = diagnostic.runFullDiagnostic()
                onResult(result)
            } catch (e: Exception) {
                onResult("❌ Error running diagnostic: ${e.message}")
            }
        }
    }

    /**
     * Prueba lectura/escritura de base de datos
     */
    fun testDatabaseReadWrite(onResult: (String) -> Unit) {
        checkInitialized()
        internalScope.launch {
            try {
                val diagnostic = DatabaseInspector(
                    databaseManager = databaseManager ?: DatabaseManager.getInstance(),
                    sipCoreManager = sipCoreManager,
                    callHistoryManager = sipCoreManager!!.callHistoryManager
                )
                val result = diagnostic.testDatabaseReadWrite()
                onResult(result)
            } catch (e: Exception) {
                onResult("❌ Error in read/write test: ${e.message}")
            }
        }
    }


    /**
     * ✅ SOLUCIÓN: Obtiene información del historial de llamadas
     */
    fun getCallHistoryInfo(): String {
        checkInitialized()

        return try {
            val memoryLogs = sipCoreManager?.callHistoryManager?.callLogs ?: emptyList()
            val stateHistory = CallStateManager.getStateHistory()

            buildString {
                appendLine("=== CALL HISTORY INFO ===")
                appendLine("Call logs in memory: ${memoryLogs.size}")
                appendLine("State history entries: ${stateHistory.size}")
                appendLine("Database manager: ${if (databaseManager != null) "✅" else "❌"}")
                appendLine("Library initialized: $isInitialized")

                if (memoryLogs.isNotEmpty()) {
                    appendLine("\n--- Recent Calls ---")
                    memoryLogs.take(5).forEach { log ->
                        appendLine("  ${log.id}: ${log.direction} - ${log.callType} - ${log.duration}s")
                    }
                }

                if (stateHistory.isNotEmpty()) {
                    appendLine("\n--- Recent States ---")
                    stateHistory.takeLast(5).forEach { state ->
                        appendLine("  ${state.timestamp}: ${state.state} (${state.callId})")
                    }
                }
            }
        } catch (e: Exception) {
            "Error getting history info: ${e.message}"
        }
    }

    /**
     * ✅ SOLUCIÓN: Limpia TODO el historial (solo usar cuando sea absolutamente necesario)
     */
    fun clearAllCallHistory(onComplete: ((Result<Unit>) -> Unit)? = null) {
        checkInitialized()

        internalScope.launch {
            try {
                log.w(tag = TAG) { "🚨 USER REQUESTED: Clearing ALL call history" }

                // Limpiar en CallStateManager
                CallStateManager.clearHistory()

                // Limpiar en CallHistoryManager
                sipCoreManager?.callHistoryManager?.clearCallLogs()

                // Limpiar en base de datos
                databaseManager?.clearAllCallLogs()

                log.w(tag = TAG) { "✅ All call history cleared per user request" }
                onComplete?.invoke(Result.success(Unit))

            } catch (e: Exception) {
                log.e(tag = TAG) { "❌ Error clearing call history: ${e.message}" }
                onComplete?.invoke(Result.failure(e))
            }
        }
    }

    // === MÉTODOS DE UTILIDAD ===

    /**
     * Verifica que la biblioteca esté inicializada
     */
    private fun checkInitialized() {
        if (!isInitialized || sipCoreManager == null) {
            throw SipLibraryException("Library not initialized. Call initialize() first.")
        }
    }

    /**
     * Formatea timestamp para display
     */
    @OptIn(ExperimentalTime::class)
    private fun formatTimestamp(timestamp: Long): String {
        val instant = Instant.fromEpochMilliseconds(timestamp)
        val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${localDateTime.dayOfMonth.toString().padStart(2, '0')}/" +
                "${localDateTime.monthNumber.toString().padStart(2, '0')}/" +
                "${localDateTime.year} " +
                "${localDateTime.hour.toString().padStart(2, '0')}:" +
                "${localDateTime.minute.toString().padStart(2, '0')}"
    }

    // === LIMPIEZA Y DESTRUCCIÓN ===

    /**
     * Libera recursos de la biblioteca
     */
    fun dispose(onComplete: (() -> Unit)? = null) {
        internalScope.launch {
            initMutex.withLock {
                if (isInitialized) {
                    log.d(tag = TAG) { "Disposing KmpSipRtc" }

                    databaseManager?.closeDatabase()
                    databaseManager = null

                    sipCoreManager?.dispose()
                    sipCoreManager = null

                    listeners.clear()
                    registrationListener = null
                    callListener = null
                    incomingCallListener = null

                    lastNotifiedRegistrationStates.clear()
                    lastNotifiedCallState.value = null

                    isInitialized = false

                    internalScope.cancel()

                    log.d(tag = TAG) { "KmpSipRtc disposed completely" }
                    onComplete?.invoke()
                }
            }
        }
    }


    ////////MATRIX//////

    /**
     * Login a servidor Matrix
     */
    fun loginMatrix(userId: String, password: String, onComplete: ((Result<Unit>) -> Unit)? = null) {
        checkInitialized()
        val matrix = matrixManager ?: run {
            onComplete?.invoke(Result.failure(SipLibraryException("Matrix not initialized")))
            return
        }

        internalScope.launch {
            val result = matrix.login(userId, password)
            onComplete?.invoke(result)
        }
    }
    /**
     * Crear sala Matrix
     */
    fun createMatrixRoom(
        name: String,
        isDirect: Boolean = false,
        inviteUserIds: List<String> = emptyList(),
        onComplete: ((Result<String>) -> Unit)? = null
    ) {
        checkInitialized()
        val matrix = matrixManager ?: run {
            onComplete?.invoke(Result.failure(SipLibraryException("Matrix not initialized")))
            return
        }

        internalScope.launch {
            val result = matrix.createRoom(name, isDirect, inviteUserIds)
            onComplete?.invoke(result)
        }
    }

    /**
     * Enviar mensaje Matrix
     */
    fun sendMatrixMessage(
        roomId: String,
        message: String,
        onComplete: ((Result<Unit>) -> Unit)? = null
    ) {
        checkInitialized()
        val matrix = matrixManager ?: run {
            onComplete?.invoke(Result.failure(SipLibraryException("Matrix not initialized")))
            return
        }

        internalScope.launch {
            val result = matrix.sendTextMessage(roomId, message)
            onComplete?.invoke(result)
        }
    }

    /**
     * Llamada unificada (auto-detecta SIP o Matrix)
     */
    fun makeUnifiedCall(
        destination: String,
        isVideo: Boolean = false,
        onComplete: ((Result<UnifiedCallInfo>) -> Unit)? = null
    ) {
        checkInitialized()
        val router = unifiedCallRouter ?: run {
            onComplete?.invoke(Result.failure(SipLibraryException("Unified router not initialized")))
            return
        }

        internalScope.launch {
            val accountInfo = sipCoreManager?.currentAccountInfo
            val result = router.makeCall(destination, isVideo, accountInfo)
            onComplete?.invoke(result)
        }
    }

    /**
     * Obtener rooms Matrix
     */
    fun getMatrixRoomsFlow(): Flow<List<MatrixRoom>>? {
        checkInitialized()
        return matrixManager?.rooms
    }

    /**
     * Obtener mensajes Matrix
     */
    fun getMatrixMessagesFlow(): Flow<Map<String, List<MatrixMessage>>>? {
        checkInitialized()
        return matrixManager?.messages
    }

    // === CLASES DE EXCEPCIÓN ===

    /**
     * Excepción específica de la biblioteca SIP
     */
    class SipLibraryException(message: String, cause: Throwable? = null) :
        Exception(message, cause)
}

// === EXTENSIONES Y WRAPPERS ===

/**
 * Wrapper para MutableStateFlow (compatibilidad)
 */
private class mutableStateOf<T>(initialValue: T) {
    private val _flow = MutableStateFlow(initialValue)

    var value: T
        get() = _flow.value
        set(value) {
            _flow.value = value
        }
}


//class KmpSipRtc private constructor() {
//
//    private var sipCoreManager: SipCoreManager? = null
//    private var isInitialized = false
//    private lateinit var config: SipConfiguration
//    private val listeners = mutableSetOf<SipEventListener>()
//    private var registrationListener: RegistrationListener? = null
//    private var callListener: CallListener? = null
//    private var incomingCallListener: IncomingCallListener? = null
//    private var databaseManager: DatabaseManager? = null
//
//    private val lastNotifiedRegistrationStates = mutableMapOf<String, RegistrationState>()
//    private val lastNotifiedCallState = mutableStateOf<CallStateInfo?>(null)
//    private val initMutex = Mutex()
//
//    // ✅ Scope interno para operaciones asíncronas
//    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
//
//    companion object {
//        @Volatile
//        private var INSTANCE: KmpSipRtc? = null

//        private const val TAG = "KmpSipRtc"
//
//        fun getInstance(): KmpSipRtc {
//            return INSTANCE ?: synchronized(LOCK) {
//                INSTANCE ?: KmpSipRtc().also { INSTANCE = it }
//            }
//        }
//    }
//
//    // === CONFIGURATION ===
//
//    data class SipConfiguration(
//        val defaultDomain: String = "",
//        val webSocketUrl: String = "",
//        val userAgent: String = "KmpSipRtc/1.0",
//        val enableLogs: Boolean = true,
//        val enableAutoReconnect: Boolean = true,
//        val pingIntervalMs: Long = 30000L,
//        val incomingRingtoneUri: String? = null,
//        val outgoingRingtoneUri: String? = null
//    )
//
//    // === LISTENER INTERFACES ===
//
//    interface SipEventListener {
//        fun onRegistrationStateChanged(
//            state: RegistrationState,
//            username: String,
//            domain: String
//        ) {}
//        fun onCallStateChanged(stateInfo: CallStateInfo) {}
//        fun onIncomingCall(callInfo: IncomingCallInfo) {}
//        fun onCallConnected(callInfo: CallInfo) {}
//        fun onCallEnded(callInfo: CallInfo, reason: CallEndReason) {}
//        fun onCallFailed(error: String, callInfo: CallInfo?) {}
//        fun onDtmfReceived(digit: Char, callInfo: CallInfo) {}
//        fun onAudioDeviceChanged(device: AudioDevice) {}
//        fun onNetworkStateChanged(isConnected: Boolean) {}
//    }
//
//    interface RegistrationListener {
//        fun onRegistrationSuccessful(username: String, domain: String)
//        fun onRegistrationFailed(username: String, domain: String, error: String)
//        fun onUnregistered(username: String, domain: String)
//        fun onRegistrationExpiring(username: String, domain: String, expiresIn: Long)
//    }
//
//    interface CallListener {
//        fun onCallInitiated(callInfo: CallInfo)
//        fun onCallRinging(callInfo: CallInfo)
//        fun onCallConnected(callInfo: CallInfo)
//        fun onCallHeld(callInfo: CallInfo)
//        fun onCallResumed(callInfo: CallInfo)
//        fun onCallEnded(callInfo: CallInfo, reason: CallEndReason)
//        fun onCallTransferred(callInfo: CallInfo, transferTo: String)
//        fun onMuteStateChanged(isMuted: Boolean, callInfo: CallInfo)
//        fun onCallStateChanged(stateInfo: CallStateInfo)
//    }
//
//    interface IncomingCallListener {
//        fun onIncomingCall(callInfo: IncomingCallInfo)
//        fun onIncomingCallCancelled(callInfo: IncomingCallInfo)
//        fun onIncomingCallTimeout(callInfo: IncomingCallInfo)
//    }
//
//    // === DATA CLASSES ===
//
//    data class CallInfo(
//        val callId: String,
//        val phoneNumber: String,
//        val displayName: String?,
//        val direction: CallDirection,
//        val startTime: Long,
//        val duration: Long = 0,
//        val isOnHold: Boolean = false,
//        val isMuted: Boolean = false,
//        val localAccount: String,
//        val codec: String? = null,
//        val state: CallState? = null,
//        val isCurrentCall: Boolean = false
//    )
//
//    data class IncomingCallInfo(
//        val callId: String,
//        val callerNumber: String,
//        val callerName: String?,
//        val targetAccount: String,
//        val timestamp: Long,
//        val headers: Map<String, String> = emptyMap()
//    )
//
//    enum class CallDirection {
//        INCOMING,
//        OUTGOING
//    }
//
//    enum class CallEndReason {
//        NORMAL_HANGUP,
//        BUSY,
//        NO_ANSWER,
//        REJECTED,
//        NETWORK_ERROR,
//        CANCELLED,
//        TIMEOUT,
//        ERROR
//    }
//
//    // === INITIALIZATION ===
//    // ✅ Ahora es una función simple que se ejecuta en background
//
//    fun initialize(
//        config: SipConfiguration = SipConfiguration(),
//        onComplete: ((Result<Unit>) -> Unit)? = null
//    ) {
//        if (isInitialized) {
//            log.w(tag = TAG) { "Library already initialized" }
//            onComplete?.invoke(Result.success(Unit))
//            return
//        }
//
//        internalScope.launch {
//            initMutex.withLock {
//                if (isInitialized) {
//                    onComplete?.invoke(Result.success(Unit))
//                    return@launch
//                }
//
//                try {
//                    log.d(tag = TAG) { "Initializing KmpSipRtc v1.0.0" }
//
//                    this@KmpSipRtc.config = config
//                    sipCoreManager = SipCoreManager.createInstance(
//                        SipConfig(
//                            defaultDomain = config.defaultDomain,
//                            webSocketUrl = config.webSocketUrl,
//                            userAgent = config.userAgent,
//                            enableLogs = config.enableLogs,
//                            enableAutoReconnect = config.enableAutoReconnect,
//                            pingIntervalMs = config.pingIntervalMs,
//                            incomingRingtoneUri = config.incomingRingtoneUri,
//                            outgoingRingtoneUri = config.outgoingRingtoneUri
//                        )
//                    )
//
//                    sipCoreManager?.initialize()
//
//                    val manager = sipCoreManager
//                        ?: throw SipLibraryException("Failed to create SipCoreManager")
//
//                    // Esperar inicialización con timeout
//                    var retries = 0
//                    val maxRetries = 50
//                    while (retries < maxRetries) {
//                        if (manager.isSipCoreManagerHealthy()) {
//                            log.d(tag = TAG) { "✅ All managers initialized successfully" }
//                            break
//                        }
//                        delay(100)
//                        retries++
//                    }
//
//                    if (retries >= maxRetries) {
//                        log.w(tag = TAG) { "⚠️ Initialization timeout - some managers may not be ready" }
//                    }
//
//                    clearInitialStates()
//                    setupInternalListeners()
//
//                    databaseManager = sipCoreManager!!.databaseManager
//
//                    isInitialized = true
//                    log.d(tag = TAG) { "✅ KmpSipRtc initialized successfully" }
//
//                    onComplete?.invoke(Result.success(Unit))
//
//                } catch (e: Exception) {
//                    log.e(tag = TAG) { "❌ Error initializing library: ${e.message}" }
//                    onComplete?.invoke(Result.failure(SipLibraryException("Failed to initialize library", e)))
//                }
//            }
//        }
//    }
//
//    private fun clearInitialStates() {
//        try {
//            log.d(tag = TAG) { "Clearing initial states..." }
//            lastNotifiedRegistrationStates.clear()
//            lastNotifiedCallState.value = null
//            CallStateManager.clearHistory()
//            log.d(tag = TAG) { "Initial states cleared" }
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error clearing initial states: ${e.message}" }
//        }
//    }
//
//    private fun setupInternalListeners() {
//        sipCoreManager?.let { manager ->
//            manager.setCallbacks(object : SipCallbacks {
//                override fun onCallTerminated() {
//                    log.d(tag = TAG) { "Internal callback: onCallTerminated" }
//                    val callInfo = getCurrentCallInfo()
//                    notifyCallEnded(callInfo, CallEndReason.NORMAL_HANGUP)
//                }
//
//                override fun onAccountRegistrationStateChanged(
//                    username: String,
//                    domain: String,
//                    state: RegistrationState
//                ) {
//                    log.d(tag = TAG) { "Internal callback: onAccountRegistrationStateChanged - $username@$domain -> $state" }
//                    notifyRegistrationStateChanged(state, username, domain)
//                }
//
//                override fun onIncomingCall(callerNumber: String, callerName: String?) {
//                    log.d(tag = TAG) { "Internal callback: onIncomingCall from $callerNumber" }
//                    val callInfo = createIncomingCallInfoFromCurrentCall(callerNumber, callerName)
//                    notifyIncomingCall(callInfo)
//                }
//
//                override fun onCallConnected() {
//                    log.d(tag = TAG) { "Internal callback: onCallConnected" }
//                    getCurrentCallInfo()?.let { notifyCallConnected(it) }
//                }
//
//                override fun onCallFailed(error: String) {
//                    log.d(tag = TAG) { "Internal callback: onCallFailed - $error" }
//                    val callInfo = getCurrentCallInfo()
//                    notifyCallFailed(error, callInfo)
//                }
//
//                override fun onCallEndedForAccount(accountKey: String) {
//                    log.d(tag = TAG) { "Internal callback: onCallEndedForAccount - $accountKey" }
//                }
//            })
//
//            internalScope.launch {
//                var isFirstState = true
//
//                CallStateManager.callStateFlow.collect { stateInfo ->
//                    if (isFirstState) {
//                        isFirstState = false
//                        if (stateInfo.timestamp > Clock.System.now().toEpochMilliseconds() + 1000) {
//                            log.w(tag = TAG) { "Skipping invalid initial state with future timestamp" }
//                            return@collect
//                        }
//                        if (MultiCallManager.getAllCalls().isEmpty() && stateInfo.state != CallState.IDLE) {
//                            log.w(tag = TAG) { "Skipping invalid initial state with no active calls" }
//                            return@collect
//                        }
//                    }
//
//                    val callInfo = getCallInfoForState(stateInfo)
//                    notifyCallStateChanged(stateInfo)
//
//                    callInfo?.let { info ->
//                        when (stateInfo.state) {
//                            CallState.CONNECTED -> notifyCallConnected(info)
//                            CallState.OUTGOING_RINGING -> notifyCallRinging(info)
//                            CallState.OUTGOING_INIT -> notifyCallInitiated(info)
//                            CallState.INCOMING_RECEIVED -> handleIncomingCall()
//                            CallState.ENDED -> {
//                                val reason = mapErrorReasonToCallEndReason(stateInfo.errorReason)
//                                notifyCallEnded(info, reason)
//                            }
//                            CallState.PAUSED -> notifyCallHeld(info)
//                            CallState.STREAMS_RUNNING -> notifyCallResumed(info)
//                            CallState.ERROR -> {
//                                val reason = mapErrorReasonToCallEndReason(stateInfo.errorReason)
//                                notifyCallEnded(info, reason)
//                            }
//                            else -> {}
//                        }
//                    }
//                }
//            }
//        }
//    }
//
//    // === NOTIFICATION METHODS (sin cambios) ===
//
//    private fun notifyRegistrationStateChanged(
//        state: RegistrationState,
//        username: String,
//        domain: String
//    ) {
//        val accountKey = "$username@$domain"
//        val lastState = lastNotifiedRegistrationStates[accountKey]
//
//        if (lastState == state) {
//            log.d(tag = TAG) { "Skipping duplicate registration state notification for $accountKey: $state" }
//            return
//        }
//
//        lastNotifiedRegistrationStates[accountKey] = state
//        log.d(tag = TAG) { "Notifying registration state change: $lastState -> $state for $accountKey" }
//
//        internalScope.launch {
//            try {
//                listeners.forEach { listener ->
//                    try {
//                        listener.onRegistrationStateChanged(state, username, domain)
//                    } catch (e: Exception) {
//                        log.e(tag = TAG) { "Error in listener onRegistrationStateChanged: ${e.message}" }
//                    }
//                }
//
//                registrationListener?.let { listener ->
//                    when (state) {
//                        RegistrationState.OK -> {
//                            listener.onRegistrationSuccessful(username, domain)
//                        }
//                        RegistrationState.FAILED -> {
//                            listener.onRegistrationFailed(username, domain, "Registration failed")
//                        }
//                        RegistrationState.NONE, RegistrationState.CLEARED -> {
//                            listener.onUnregistered(username, domain)
//                        }
//                        else -> {}
//                    }
//                }
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Critical error in registration state notification: ${e.message}" }
//            }
//        }
//    }
//
//    private fun notifyCallStateChanged(stateInfo: CallStateInfo) {
//        val lastState = lastNotifiedCallState.value
//        val stateChanged = lastState == null ||
//                lastState.state != stateInfo.state ||
//                lastState.callId != stateInfo.callId ||
//                lastState.errorReason != stateInfo.errorReason
//
//        if (!stateChanged) {
//            log.d(tag = TAG) { "Skipping duplicate call state notification: ${stateInfo.state}" }
//            return
//        }
//
//        lastNotifiedCallState.value = stateInfo
//        log.d(tag = TAG) { "Notifying call state change: ${lastState?.state} -> ${stateInfo.state}" }
//
//        listeners.forEach { listener ->
//            try {
//                listener.onCallStateChanged(stateInfo)
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error in listener onCallStateChanged: ${e.message}" }
//            }
//        }
//
//        callListener?.let { listener ->
//            try {
//                listener.onCallStateChanged(stateInfo)
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error in CallListener onCallStateChanged: ${e.message}" }
//            }
//        }
//    }
//
//    private fun notifyCallInitiated(callInfo: CallInfo) {
//        log.d(tag = TAG) { "Notifying call initiated" }
//        try {
//            callListener?.onCallInitiated(callInfo)
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error in CallListener onCallInitiated: ${e.message}" }
//        }
//    }
//
//    private fun notifyCallConnected(callInfo: CallInfo) {
//        log.d(tag = TAG) { "Notifying call connected" }
//        listeners.forEach { listener ->
//            try {
//                listener.onCallConnected(callInfo)
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error in listener onCallConnected: ${e.message}" }
//            }
//        }
//        try {
//            callListener?.onCallConnected(callInfo)
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error in CallListener onCallConnected: ${e.message}" }
//        }
//    }
//
//    private fun notifyCallRinging(callInfo: CallInfo) {
//        log.d(tag = TAG) { "Notifying call ringing" }
//        try {
//            callListener?.onCallRinging(callInfo)
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error in CallListener onCallRinging: ${e.message}" }
//        }
//    }
//
//    private fun notifyCallHeld(callInfo: CallInfo) {
//        log.d(tag = TAG) { "Notifying call held" }
//        try {
//            callListener?.onCallHeld(callInfo)
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error in CallListener onCallHeld: ${e.message}" }
//        }
//    }
//
//    private fun notifyCallResumed(callInfo: CallInfo) {
//        log.d(tag = TAG) { "Notifying call resumed" }
//        try {
//            callListener?.onCallResumed(callInfo)
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error in CallListener onCallResumed: ${e.message}" }
//        }
//    }
//
//    private fun notifyCallEnded(callInfo: CallInfo?, reason: CallEndReason) {
//        callInfo?.let { info ->
//            log.d(tag = TAG) { "Notifying call ended" }
//            listeners.forEach { listener ->
//                try {
//                    listener.onCallEnded(info, reason)
//                } catch (e: Exception) {
//                    log.e(tag = TAG) { "Error in listener onCallEnded: ${e.message}" }
//                }
//            }
//            try {
//                callListener?.onCallEnded(info, reason)
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error in CallListener onCallEnded: ${e.message}" }
//            }
//        }
//    }
//
//    private fun notifyIncomingCall(callInfo: IncomingCallInfo) {
//        log.d(tag = TAG) { "Notifying incoming call" }
//        listeners.forEach { listener ->
//            try {
//                listener.onIncomingCall(callInfo)
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error in listener onIncomingCall: ${e.message}" }
//            }
//        }
//        try {
//            incomingCallListener?.onIncomingCall(callInfo)
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error in IncomingCallListener: ${e.message}" }
//        }
//    }
//
//    private fun notifyCallFailed(error: String, callInfo: CallInfo?) {
//        log.d(tag = TAG) { "Notifying call failed" }
//        listeners.forEach { listener ->
//            try {
//                listener.onCallFailed(error, callInfo)
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error in listener onCallFailed: ${e.message}" }
//            }
//        }
//    }
//
//    private fun handleIncomingCall() {
//        val manager = sipCoreManager ?: return
//        val account = manager.currentAccountInfo ?: return
//        val callData = account.currentCallData.value ?: return
//
//        val callInfo = IncomingCallInfo(
//            callId = callData.callId,
//            callerNumber = callData.from,
//            callerName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
//            targetAccount = account.username,
//            timestamp = callData.startTime
//        )
//
//        notifyIncomingCall(callInfo)
//    }
//
//    private fun createIncomingCallInfoFromCurrentCall(
//        callerNumber: String,
//        callerName: String?
//    ): IncomingCallInfo {
//        val manager = sipCoreManager
//        val account = manager?.currentAccountInfo
//        val callData = account?.currentCallData?.value
//
//        return IncomingCallInfo(
//            callId = callData?.callId ?: generateCallId(),
//            callerNumber = callerNumber,
//            callerName = callerName,
//            targetAccount = account?.username ?: "",
//            timestamp = callData?.startTime ?: Clock.System.now().toEpochMilliseconds()
//        )
//    }
//
//    // === HELPER METHODS (sin cambios) ===
//
//    private fun getCallInfoForState(stateInfo: CallStateInfo): CallInfo? {
//        val manager = sipCoreManager ?: return null
//        val calls = MultiCallManager.getAllCalls()
//        val callData = calls.find { it.callId == stateInfo.callId }
//            ?: manager.currentAccountInfo?.currentCallData?.value
//            ?: return null
//
//        val account = manager.currentAccountInfo ?: return null
//        val currentCall = calls.size == 1 &&
//                stateInfo.state != CallState.ENDED &&
//                stateInfo.state != CallState.ERROR &&
//                stateInfo.state != CallState.ENDING &&
//                stateInfo.state != CallState.IDLE
//
//        return try {
//            CallInfo(
//                callId = callData.callId,
//                phoneNumber = if (callData.direction == CallDirections.INCOMING) callData.from else callData.to,
//                displayName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
//                direction = if (callData.direction == CallDirections.INCOMING) CallDirection.INCOMING else CallDirection.OUTGOING,
//                startTime = callData.startTime,
//                duration = if (callData.startTime > 0) Clock.System.now().toEpochMilliseconds() - callData.startTime else 0,
//                isOnHold = callData.isOnHold ?: false,
//                isMuted = manager.webRtcManager.isMuted(),
//                localAccount = account.username,
//                codec = null,
//                state = stateInfo.state,
//                isCurrentCall = currentCall
//            )
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error creating CallInfo: ${e.message}" }
//            null
//        }
//    }
//
//    private fun getCurrentCallInfo(): CallInfo? {
//        val manager = sipCoreManager ?: return null
//        val account = manager.currentAccountInfo ?: return null
//        val calls = MultiCallManager.getAllCalls()
//        val callData = calls.firstOrNull() ?: account.currentCallData.value ?: return null
//
//        val currentCall = calls.size == 1 &&
//                CallStateManager.getCurrentState().let { state ->
//                    state.state != CallState.ENDED &&
//                            state.state != CallState.ERROR &&
//                            state.state != CallState.ENDING &&
//                            state.state != CallState.IDLE
//                }
//
//        return try {
//            val currentState = CallStateManager.getCurrentState()
//
//            CallInfo(
//                callId = callData.callId,
//                phoneNumber = if (callData.direction == CallDirections.INCOMING) callData.from else callData.to,
//                displayName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
//                direction = if (callData.direction == CallDirections.INCOMING) CallDirection.INCOMING else CallDirection.OUTGOING,
//                startTime = manager.callStartTimeMillis,
//                duration = if (manager.callStartTimeMillis > 0) Clock.System.now().toEpochMilliseconds() - manager.callStartTimeMillis else 0,
//                isOnHold = callData.isOnHold ?: false,
//                isMuted = manager.webRtcManager.isMuted(),
//                localAccount = account.username,
//                codec = null,
//                state = currentState.state,
//                isCurrentCall = currentCall
//            )
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error creating CallInfo: ${e.message}" }
//            null
//        }
//    }
//
//    private fun mapErrorReasonToCallEndReason(errorReason: CallErrorReason): CallEndReason {
//        return when (errorReason) {
//            CallErrorReason.BUSY -> CallEndReason.BUSY
//            CallErrorReason.NO_ANSWER -> CallEndReason.NO_ANSWER
//            CallErrorReason.REJECTED -> CallEndReason.REJECTED
//            CallErrorReason.NETWORK_ERROR -> CallEndReason.NETWORK_ERROR
//            else -> CallEndReason.ERROR
//        }
//    }
//
//    private fun generateCallId(): String {
//        return "call_${Clock.System.now().toEpochMilliseconds()}_${(1000..9999).random()}"
//    }
//
//    // === PUBLIC API METHODS ===
//
//    // Listeners
//    fun addSipEventListener(listener: SipEventListener) {
//        listeners.add(listener)
//        log.d(tag = TAG) { "SipEventListener added. Total listeners: ${listeners.size}" }
//    }
//
//    fun removeSipEventListener(listener: SipEventListener) {
//        listeners.remove(listener)
//        log.d(tag = TAG) { "SipEventListener removed. Total listeners: ${listeners.size}" }
//    }
//
//    fun setRegistrationListener(listener: RegistrationListener?) {
//        this.registrationListener = listener
//        log.d(tag = TAG) { "RegistrationListener configured" }
//    }
//
//    fun setCallListener(listener: CallListener?) {
//        this.callListener = listener
//        log.d(tag = TAG) { "CallListener configured" }
//    }
//
//    fun setIncomingCallListener(listener: IncomingCallListener?) {
//        this.incomingCallListener = listener
//        log.d(tag = TAG) { "IncomingCallListener configured" }
//    }
//
//    // ✅ Registration - Ahora sin suspend
//    fun registerAccount(
//        username: String,
//        password: String,
//        domain: String,
//        pushToken: String? = null,
//        pushProvider: String = "fcm",
//        forcePushMode: Boolean = false,
//        onComplete: ((Result<Unit>) -> Unit)? = null
//    ) {
//        checkInitialized()
//        log.d(tag = TAG) { "Registering account: $username@$domain" }
//
//        internalScope.launch {
//            try {
//                sipCoreManager?.register(
//                    username = username,
//                    password = password,
//                    domain = domain,
//                    provider = pushProvider,
//                    token = pushToken ?: "",
//                    forcePushMode = forcePushMode
//                )
//                onComplete?.invoke(Result.success(Unit))
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Registration failed: ${e.message}" }
//                onComplete?.invoke(Result.failure(e))
//            }
//        }
//    }
//
//    fun unregisterAccount(
//        username: String,
//        domain: String,
//        onComplete: ((Result<Unit>) -> Unit)? = null
//    ) {
//        checkInitialized()
//        val accountKey = "$username@$domain"
//        log.d(tag = TAG) { "Unregistering account: $accountKey" }
//        lastNotifiedRegistrationStates.remove(accountKey)
//
//        internalScope.launch {
//            try {
//                sipCoreManager?.unregister(username, domain)
//                onComplete?.invoke(Result.success(Unit))
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Unregister failed: ${e.message}" }
//                onComplete?.invoke(Result.failure(e))
//            }
//        }
//    }
//
//    fun unregisterAllAccounts(onComplete: ((Result<Unit>) -> Unit)? = null) {
//        checkInitialized()
//        log.d(tag = TAG) { "Unregistering all accounts" }
//        lastNotifiedRegistrationStates.clear()
//        lastNotifiedCallState.value = null
//
//        internalScope.launch {
//            try {
//                sipCoreManager?.unregisterAllAccounts()
//                onComplete?.invoke(Result.success(Unit))
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Unregister all failed: ${e.message}" }
//                onComplete?.invoke(Result.failure(e))
//            }
//        }
//    }
//
//    fun getRegistrationState(username: String, domain: String): RegistrationState {
//        checkInitialized()
//        val accountKey = "$username@$domain"
//        return sipCoreManager?.getRegistrationState(accountKey) ?: RegistrationState.NONE
//    }
//
//    fun getAllRegistrationStates(): Map<String, RegistrationState> {
//        checkInitialized()
//        return sipCoreManager?.getAllRegistrationStates() ?: emptyMap()
//    }
//
//    fun getRegistrationStatesFlow(): Flow<Map<String, RegistrationState>> {
//        checkInitialized()
//        return sipCoreManager?.registrationStatesFlow ?: flowOf(emptyMap())
//    }
//
//    // Call Management (sin cambios - ya son funciones simples)
//    fun makeCall(
//        phoneNumber: String,
//        username: String? = null,
//        domain: String? = null
//    ) {
//        checkInitialized()
//
//        val finalUsername = username ?: sipCoreManager?.getCurrentUsername() ?: run {
//            log.e(tag = TAG) { "No username provided and no current account available" }
//            throw SipLibraryException("No username available for making call")
//        }
//
//        val finalDomain = domain ?: sipCoreManager?.getDefaultDomain() ?: run {
//            log.e(tag = TAG) { "No domain provided and no current account available" }
//            throw SipLibraryException("No domain available for making call")
//        }
//
//        log.d(tag = TAG) { "Making call to $phoneNumber from $finalUsername@$finalDomain" }
//        sipCoreManager?.makeCall(phoneNumber, finalUsername, finalDomain)
//    }
//
//    fun acceptCall(callId: String? = null) {
//        checkInitialized()
//        val calls = MultiCallManager.getAllCalls()
//        val targetCallId = if (callId == null && calls.size == 1) {
//            log.d(tag = TAG) { "Accepting single call" }
//            sipCoreManager?.acceptCall()
//            return
//        } else {
//            callId ?: calls.firstOrNull()?.callId
//        }
//
//        if (targetCallId != null) {
//            log.d(tag = TAG) { "Accepting call: $targetCallId" }
//            sipCoreManager?.acceptCall(targetCallId)
//        } else {
//            log.w(tag = TAG) { "No call to accept" }
//        }
//    }
//
//    fun declineCall(callId: String? = null) {
//        checkInitialized()
//        val calls = MultiCallManager.getAllCalls()
//        val targetCallId = if (callId == null && calls.size == 1) {
//            log.d(tag = TAG) { "Declining single call" }
//            sipCoreManager?.declineCall()
//            return
//        } else {
//            callId ?: calls.firstOrNull()?.callId
//        }
//
//        if (targetCallId != null) {
//            log.d(tag = TAG) { "Declining call: $targetCallId" }
//            sipCoreManager?.declineCall(targetCallId)
//        } else {
//            log.w(tag = TAG) { "No call to decline" }
//        }
//    }
//
//    fun endCall(callId: String? = null) {
//        checkInitialized()
//        val calls = MultiCallManager.getAllCalls()
//        val targetCallId = if (callId == null && calls.size == 1) {
//            log.d(tag = TAG) { "Ending single call" }
//            sipCoreManager?.endCall()
//            return
//        } else {
//            callId ?: calls.firstOrNull()?.callId
//        }
//
//        if (targetCallId != null) {
//            log.d(tag = TAG) { "Ending call: $targetCallId" }
//            sipCoreManager?.endCall(targetCallId)
//        } else {
//            log.w(tag = TAG) { "No call to end" }
//        }
//    }
//
//    fun holdCall(callId: String? = null) {
//        checkInitialized()
//        val calls = MultiCallManager.getAllCalls()
//        val targetCallId = if (callId == null && calls.size == 1) {
//            log.d(tag = TAG) { "Holding single call" }
//            sipCoreManager?.holdCall()
//            return
//        } else {
//            callId ?: calls.firstOrNull()?.callId
//        }
//
//        if (targetCallId != null) {
//            log.d(tag = TAG) { "Holding call: $targetCallId" }
//            sipCoreManager?.holdCall(targetCallId)
//        } else {
//            log.w(tag = TAG) { "No call to hold" }
//        }
//    }
//
//    fun resumeCall(callId: String? = null) {
//        checkInitialized()
//        val calls = MultiCallManager.getAllCalls()
//        val targetCallId = if (callId == null && calls.size == 1) {
//            log.d(tag = TAG) { "Resuming single call" }
//            sipCoreManager?.resumeCall()
//            return
//        } else {
//            callId ?: calls.firstOrNull()?.callId
//        }
//
//        if (targetCallId != null) {
//            log.d(tag = TAG) { "Resuming call: $targetCallId" }
//            sipCoreManager?.resumeCall(targetCallId)
//        } else {
//            log.w(tag = TAG) { "No call to resume" }
//        }
//    }
//
//    fun unholdCall(callId: String? = null) = resumeCall(callId)
//
//    fun getAllCalls(): List<CallInfo> {
//        checkInitialized()
//        return MultiCallManager.getAllCalls().mapNotNull { callData ->
//            try {
//                val account = sipCoreManager?.currentAccountInfo ?: return@mapNotNull null
//                val allActiveCalls = MultiCallManager.getActiveCalls()
//                val isCurrentCall = allActiveCalls.size == 1 &&
//                        allActiveCalls.first().callId == callData.callId
//
//                CallInfo(
//                    callId = callData.callId,
//                    phoneNumber = if (callData.direction == CallDirections.INCOMING) callData.from else callData.to,
//                    displayName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
//                    direction = if (callData.direction == CallDirections.INCOMING) CallDirection.INCOMING else CallDirection.OUTGOING,
//                    startTime = callData.startTime,
//                    duration = if (callData.startTime > 0) Clock.System.now().toEpochMilliseconds() - callData.startTime else 0,
//                    isOnHold = callData.isOnHold ?: false,
//                    isMuted = sipCoreManager?.webRtcManager?.isMuted() ?: false,
//                    localAccount = account.username,
//                    codec = null,
//                    state = CallStateManager.getStateForCall(callData.callId)?.state,
//                    isCurrentCall = isCurrentCall
//                )
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error creating CallInfo for ${callData.callId}: ${e.message}" }
//                null
//            }
//        }
//    }
//
//    fun cleanupTerminatedCalls() {
//        checkInitialized()
//        sipCoreManager?.cleanupTerminatedCalls()
//    }
//
//    fun hasActiveCall(): Boolean {
//        checkInitialized()
//        return MultiCallManager.hasActiveCalls()
//    }
//
//    // Call State
//    fun getCallStateFlow(): Flow<CallStateInfo> {
//        checkInitialized()
//        return CallStateManager.callStateFlow
//    }
//
//    fun getCurrentCallState(): CallStateInfo {
//        checkInitialized()
//        return CallStateManager.getCurrentState()
//    }
//
//    fun getCallStateHistory(): List<CallStateInfo> {
//        checkInitialized()
//        return CallStateManager.getStateHistory()
//    }
//
//    fun clearCallStateHistory() {
//        checkInitialized()
//        CallStateManager.clearHistory()
//    }
//
//    // Audio Management
//    fun toggleMute() {
//        checkInitialized()
//        log.d(tag = TAG) { "Toggling mute" }
//        sipCoreManager?.mute()
//
//        getCurrentCallInfo()?.let { callInfo ->
//            val isMuted = sipCoreManager?.webRtcManager?.isMuted() ?: false
//            val mutedCallInfo = callInfo.copy(isMuted = isMuted)
//            try {
//                callListener?.onMuteStateChanged(isMuted, mutedCallInfo)
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error in CallListener onMuteStateChanged: ${e.message}" }
//            }
//        }
//    }
//
//    fun isMuted(): Boolean {
//        checkInitialized()
//        return sipCoreManager?.webRtcManager?.isMuted() ?: false
//    }
//
//    fun changeAudioDevice(device: AudioDevice) {
//        checkInitialized()
//        sipCoreManager?.changeAudioDevice(device)
//    }
//
//    fun refreshAudioDevices() {
//        checkInitialized()
//        sipCoreManager?.refreshAudioDevices()
//    }
//
//    fun getCurrentAudioDevices(): Pair<AudioDevice?, AudioDevice?> {
//        checkInitialized()
//        return sipCoreManager?.getCurrentDevices() ?: Pair(null, null)
//    }
//
//    fun getAvailableAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
//        checkInitialized()
//        return sipCoreManager?.getAudioDevices() ?: Pair(emptyList(), emptyList())
//    }
//
//    fun prepareAudioForCall() {
//        sipCoreManager?.prepareAudioForCall()
//    }
//
//    fun onBluetoothConnectionChanged(isConnected: Boolean) {
//        sipCoreManager?.onBluetoothConnectionChanged(isConnected)
//    }
//
//    fun refreshAudioDevicesWithBluetoothPriority() {
//        sipCoreManager?.refreshAudioDevicesWithBluetoothPriority()
//    }
//
//    fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
//        return sipCoreManager?.applyAudioRouteChange(audioUnitType) == true
//    }
//
//    fun getAvailableAudioUnits(): Set<AudioUnit>? {
//        return sipCoreManager?.getAvailableAudioUnits()
//    }
//
//    fun getCurrentActiveAudioUnit(): AudioUnit? {
//        return sipCoreManager?.getCurrentActiveAudioUnit()
//    }
//
//    // Ringtones
//    fun saveIncomingRingtoneUri(uri: String) {
//        checkInitialized()
//        try {
//            sipCoreManager?.saveIncomingRingtoneUri(uri)
//            log.d(tag = TAG) { "Incoming ringtone URI updated: $uri" }
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error setting incoming ringtone: ${e.message}" }
//        }
//    }
//
//    fun saveOutgoingRingtoneUri(uri: String) {
//        checkInitialized()
//        try {
//            sipCoreManager?.saveOutgoingRingtoneUri(uri)
//            log.d(tag = TAG) { "Outgoing ringtone URI updated: $uri" }
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error setting outgoing ringtone: ${e.message}" }
//        }
//    }
//
//    fun saveRingtoneUris(
//        incomingUri: String?,
//        outgoingUri: String?,
//        onComplete: ((Result<Unit>) -> Unit)? = null
//    ) {
//        checkInitialized()
//        internalScope.launch {
//            try {
//                sipCoreManager?.saveRingtoneUris(incomingUri, outgoingUri)
//                log.d(tag = TAG) { "Both ringtone URIs updated" }
//                onComplete?.invoke(Result.success(Unit))
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error setting ringtone URIs: ${e.message}" }
//                onComplete?.invoke(Result.failure(e))
//            }
//        }
//    }
//
//    // DTMF
//    fun sendDtmf(digit: Char, duration: Int = 160): Boolean {
//        checkInitialized()
//        return sipCoreManager?.sendDtmf(digit, duration) ?: false
//    }
//
//    fun sendDtmfSequence(digits: String, duration: Int = 160): Boolean {
//        checkInitialized()
//        return sipCoreManager?.sendDtmfSequence(digits, duration) ?: false
//    }
//
//    // Call Logs
//    fun getCallLogs(limit: Int = 50): List<CallLog> {
//        checkInitialized()
//        return sipCoreManager?.callLogs() ?: emptyList()
//    }
//
//    fun getMissedCalls(): List<CallLog> {
//        checkInitialized()
//        return sipCoreManager?.getMissedCalls() ?: emptyList()
//    }
//
//    fun getCallLogsForNumber(phoneNumber: String): List<CallLog> {
//        checkInitialized()
//        return sipCoreManager?.getCallLogsForNumber(phoneNumber) ?: emptyList()
//    }
//
//    fun clearCallLogs() {
//        checkInitialized()
//        sipCoreManager?.clearCallLogs()
//    }
//
//    fun searchCallLogs(
//        query: String,
//        onResult: (List<CallLog>) -> Unit
//    ) {
//        checkInitialized()
//        internalScope.launch {
//            try {
//                val results = sipCoreManager?.searchCallLogsInDatabase(query) ?: emptyList()
//                onResult(results)
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error searching call logs: ${e.message}" }
//                onResult(emptyList())
//            }
//        }
//    }
//
//    fun getCallLogsFlow(limit: Int = 50): Flow<List<CallLog>>? {
//        checkInitialized()
//        return try {
//            databaseManager?.getRecentCallLogs(limit)?.map { it.toCallLogs() }
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error getting call logs flow: ${e.message}" }
//            null
//        }
//    }
//
//    fun getMissedCallsFlow(): Flow<List<CallLog>>? {
//        checkInitialized()
//        return try {
//            databaseManager?.getMissedCallLogs()?.map { it.toCallLogs() }
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error getting missed calls flow: ${e.message}" }
//            null
//        }
//    }
//
//    // Diagnostics
//    fun performRegistrationHealthCheck(): String {
//        checkInitialized()
//        return try {
//            sipCoreManager?.performRegistrationHealthCheck()
//                ?: "❌ SipCoreManager not available"
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error in health check: ${e.message}" }
//            "❌ Health check error: ${e.message}"
//        }
//    }
//
//    fun forceReregisterAll(onComplete: ((String) -> Unit)? = null) {
//        checkInitialized()
//        internalScope.launch {
//            try {
//                log.d(tag = TAG) { "Force re-registration initiated" }
//                val result = sipCoreManager?.forceReregisterAllAccounts()
//                    ?: "❌ SipCoreManager not available"
//                onComplete?.invoke(result)
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error in force re-registration: ${e.message}" }
//                onComplete?.invoke("❌ Force re-registration error: ${e.message}")
//            }
//        }
//    }
//
//    fun forceGuardianRecovery(onComplete: ((String) -> Unit)? = null) {
//        checkInitialized()
//        internalScope.launch {
//            try {
//                val result = sipCoreManager?.forceGuardianRecovery()
//                    ?: "❌ SipCoreManager not available"
//                onComplete?.invoke(result)
//            } catch (e: Exception) {
//                onComplete?.invoke("❌ Guardian recovery error: ${e.message}")
//            }
//        }
//    }
//
//    fun getGuardianDiagnostic(onResult: (String) -> Unit) {
//        checkInitialized()
//        internalScope.launch {
//            try {
//                val result = sipCoreManager?.getGuardianDiagnostic()
//                    ?: "❌ SipCoreManager not available"
//                onResult(result)
//            } catch (e: Exception) {
//                onResult("❌ Error getting guardian diagnostic: ${e.message}")
//            }
//        }
//    }
//
//    fun verifyAndFixConnectivity() {
//        checkInitialized()
//        internalScope.launch {
//            val accounts = sipCoreManager?.activeAccounts?.values?.toList() ?: emptyList()
//            if (accounts.isEmpty()) {
//                val recoveredAccounts = sipCoreManager?.recoverAccountsFromDatabase() ?: emptyList()
//                withContext(Dispatchers.IO) {
//                    sipCoreManager?.let { manager ->
//                        manager.networkManager.isNetworkAvailable()
//                    }
//                }
//            } else {
//                withContext(Dispatchers.IO) {
//                    sipCoreManager?.verifyAndFixConnectivity()
//                }
//            }
//        }
//    }
//
//    fun isNetworkAvailable(): Boolean {
//        checkInitialized()
//        return sipCoreManager?.isNetworkAvailable() ?: false
//    }
//
//    fun isSystemHealthy(): Boolean {
//        checkInitialized()
//        return sipCoreManager?.isSipCoreManagerHealthy() ?: false
//    }
//
//    // Utilities
//    private fun checkInitialized() {
//        if (!isInitialized || sipCoreManager == null) {
//            throw SipLibraryException("Library not initialized. Call initialize() first.")
//        }
//    }
//
//    fun runDatabaseDiagnostic(onResult: (String) -> Unit) {
//        checkInitialized()
//        internalScope.launch {
//            try {
//                val diagnostic = DatabaseInspector(
//                    databaseManager = databaseManager ?: DatabaseManager.getInstance(),
//                    sipCoreManager = sipCoreManager,
//                    callHistoryManager = sipCoreManager!!.callHistoryManager
//                )
//                val result = diagnostic.runFullDiagnostic()
//                onResult(result)
//            } catch (e: Exception) {
//                onResult("❌ Error running diagnostic: ${e.message}")
//            }
//        }
//    }
//
//    fun testDatabaseReadWrite(onResult: (String) -> Unit) {
//        checkInitialized()
//        internalScope.launch {
//            try {
//                val diagnostic = DatabaseInspector(
//                    databaseManager = databaseManager ?: DatabaseManager.getInstance(),
//                    sipCoreManager = sipCoreManager,
//                    callHistoryManager = sipCoreManager!!.callHistoryManager
//                )
//                val result = diagnostic.testDatabaseReadWrite()
//                onResult(result)
//            } catch (e: Exception) {
//                onResult("❌ Error in read/write test: ${e.message}")
//            }
//        }
//    }
//
//    /**
//     * Verificar si las llamadas se están guardando correctamente
//     */
//    fun verifyCallLogPersistence(onResult: (String) -> Unit) {
//        checkInitialized()
//        internalScope.launch {
//            try {
//                val report = buildString {
//                    appendLine("=== CALL LOG PERSISTENCE CHECK ===")
//
//                    // Obtener call logs de memoria
//                    val memoryLogs = sipCoreManager?.callHistoryManager?.callLogs ?: emptyList()
//                    appendLine("Call logs in memory: ${memoryLogs.size}")
//
//                    // Obtener call logs de BD
//                    val dbLogs = databaseManager?.getRecentCallLogs(100)?.first() ?: emptyList()
//                    appendLine("Call logs in database: ${dbLogs.size}")
//
//                    if (memoryLogs.isEmpty() && dbLogs.isEmpty()) {
//                        appendLine("\nℹ️  No call logs found (make a test call first)")
//                    } else if (memoryLogs.size > dbLogs.size) {
//                        appendLine("\n⚠️  WARNING: More logs in memory than database")
//                        appendLine("   This means logs are NOT being saved!")
//                    } else if (dbLogs.size >= memoryLogs.size) {
//                        appendLine("\n✅ Logs are being persisted correctly")
//                    }
//
//                    if (dbLogs.isNotEmpty()) {
//                        appendLine("\nLast 3 saved calls:")
//                        dbLogs.take(3).forEach { log ->
//                            appendLine("  • ${log.callLog.phoneNumber}")
//                            appendLine("    ${log.callLog.callType} - ${formatTimestamp(log.callLog.startTime)}")
//                        }
//                    }
//                }
//                onResult(report)
//            } catch (e: Exception) {
//                onResult("❌ Error checking persistence: ${e.message}")
//            }
//        }
//    }
//
//    // Extension function helper
//    private fun formatTimestamp(timestamp: Long): String {
//        val instant = Instant.fromEpochMilliseconds(timestamp)
//        val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
//        return "${localDateTime.dayOfMonth.toString().padStart(2, '0')}/" +
//                "${localDateTime.monthNumber.toString().padStart(2, '0')}/" +
//                "${localDateTime.year} " +
//                "${localDateTime.hour.toString().padStart(2, '0')}:" +
//                "${localDateTime.minute.toString().padStart(2, '0')}"
//    }
//
//    fun dispose(onComplete: (() -> Unit)? = null) {
//        internalScope.launch {
//            initMutex.withLock {
//                if (isInitialized) {
//                    log.d(tag = TAG) { "Disposing KmpSipRtc" }
//
//                    databaseManager?.closeDatabase()
//                    databaseManager = null
//
//                    sipCoreManager?.dispose()
//                    sipCoreManager = null
//
//                    listeners.clear()
//                    registrationListener = null
//                    callListener = null
//                    incomingCallListener = null
//
//                    lastNotifiedRegistrationStates.clear()
//                    lastNotifiedCallState.value = null
//
//                    isInitialized = false
//
//                    internalScope.cancel()
//
//                    log.d(tag = TAG) { "KmpSipRtc disposed completely" }
//                    onComplete?.invoke()
//                }
//            }
//        }
//    }
//
//    class SipLibraryException(message: String, cause: Throwable? = null) :
//        Exception(message, cause)
//}
//
//// MutableStateFlow wrapper for compatibility
//private class mutableStateOf<T>(initialValue: T) {
//    private val _flow = MutableStateFlow(initialValue)
//
//    var value: T
//        get() = _flow.value
//        set(value) {
//            _flow.value = value
//        }
//}