package com.eddyslarez.kmpsiprtc.core

import com.eddyslarez.kmpsiprtc.data.database.DatabaseAutoIntegration
import com.eddyslarez.kmpsiprtc.data.database.DatabaseManager
import com.eddyslarez.kmpsiprtc.data.database.converters.toCallLogs
import com.eddyslarez.kmpsiprtc.data.database.entities.AppConfigEntity
import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.AudioUnit
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitTypes
import com.eddyslarez.kmpsiprtc.data.models.CallData
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.data.models.CallHistoryManager
import com.eddyslarez.kmpsiprtc.data.models.CallLog
import com.eddyslarez.kmpsiprtc.data.models.CallState
import com.eddyslarez.kmpsiprtc.data.models.CallStateInfo
import com.eddyslarez.kmpsiprtc.data.models.RegistrationState
import com.eddyslarez.kmpsiprtc.data.models.SipCallbacks
import com.eddyslarez.kmpsiprtc.data.models.SipConfig
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import com.eddyslarez.kmpsiprtc.data.models.isWebSocketHealthy
import com.eddyslarez.kmpsiprtc.platform.AppLifecycleEvent
import com.eddyslarez.kmpsiprtc.platform.AppLifecycleListener
import com.eddyslarez.kmpsiprtc.platform.PlatformRegistration
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.calls.CallStateManager
import com.eddyslarez.kmpsiprtc.services.calls.MultiCallManager
import com.eddyslarez.kmpsiprtc.services.sip.SipMessageHandler
import com.eddyslarez.kmpsiprtc.services.webSocket.MultiplatformWebSocket
import com.eddyslarez.kmpsiprtc.services.webSocket.createWebSocket
import com.eddyslarez.kmpsiprtc.services.webrtc.WebRtcEventListener
import com.eddyslarez.kmpsiprtc.services.webrtc.createWebRtcManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.invoke
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.collections.containsKey
import kotlin.collections.remove
import kotlin.compareTo
import kotlin.text.clear
import kotlin.text.get
import kotlin.text.set
import kotlin.to
import com.eddyslarez.kmpsiprtc.services.audio.AudioManager
import com.eddyslarez.kmpsiprtc.services.audio.createAudioManager
import kotlinx.coroutines.withContext

class SipCoreManager private constructor(
    private val config: SipConfig,
) {
    private var databaseManager: DatabaseManager? = null
    private var loadedConfig: AppConfigEntity? = null
    private var isRegistrationInProgress = false
    private var healthCheckJob: Job? = null
    private var lastRegistrationAttempt = 0L
    internal var sipCallbacks: SipCallbacks? = null
    private var isShuttingDown = false
    val callHistoryManager = CallHistoryManager()
    internal var lifecycleCallback: ((String) -> Unit)? = null

    // Managers
    internal lateinit var audioManager: SipAudioManager
    private lateinit var reconnectionManager: SipReconnectionManager
    internal var callManager: CallManager? = null
    val networkManager = createNetworkManager()
    val webRtcManager = createWebRtcManager()
    private val platformRegistration = PlatformRegistration()
    internal val messageHandler = SipMessageHandler(this)
    private lateinit var registrationGuardian: RegistrationGuardianManager

    // Estados de registro por cuenta
    private val _registrationStates = MutableStateFlow<Map<String, RegistrationState>>(emptyMap())
    val registrationStatesFlow: StateFlow<Map<String, RegistrationState>> =
        _registrationStates.asStateFlow()

    // Mapa thread-safe de cuentas activas
    val activeAccounts = HashMap<String, AccountInfo>()
    var callStartTimeMillis: Long = 0
    var currentAccountInfo: AccountInfo? = null
    var isAppInBackground = false
    private var lastConnectionCheck = 0L
    var onCallTerminated: (() -> Unit)? = null
    private var registrationCallbackForCall: ((AccountInfo, Boolean) -> Unit)? = null

    // Sincronización de cuentas con BD
    private var accountSyncJob: Job? = null
    private val accountSyncMutex = Mutex()

    companion object {
        private const val TAG = "SipCoreManager"
        private const val WEBSOCKET_PROTOCOL = "sip"
        private const val REGISTRATION_CHECK_INTERVAL_MS = 30 * 1000L
        private const val ACCOUNT_SYNC_INTERVAL_MS = 60 * 1000L

        fun createInstance(config: SipConfig): SipCoreManager {
            return SipCoreManager(config = config)
        }
    }

    fun observeLifecycleChanges(callback: (String) -> Unit) {
        this.lifecycleCallback = callback
    }

    fun userAgent(): String = config.userAgent

    fun getDefaultDomain(): String? = currentAccountInfo?.domain

    private fun getFirstRegisteredAccount(): AccountInfo? {
        return activeAccounts.values.firstOrNull { it.isRegistered.value }
    }

    private fun ensureCurrentAccount(): AccountInfo? {
        if (currentAccountInfo == null || !currentAccountInfo!!.isRegistered.value) {
            currentAccountInfo = getFirstRegisteredAccount()
        }
        return currentAccountInfo
    }

    fun getCurrentUsername(): String? = currentAccountInfo?.username

    suspend fun initialize() {
        log.d(tag = TAG) { "Initializing SIP Core with integrated managers" }

        try {
            // CRÍTICO: Inicializar DatabaseManager primero usando getInitializedInstance
            log.d(tag = TAG) { "⏳ Waiting for database initialization..." }

            val dbManager = try {
                DatabaseManager.getInitializedInstance(timeoutMs = 15000)
            } catch (e: Exception) {
                log.e(tag = TAG) { "❌ Database initialization failed: ${e.message}" }
                throw Exception("Database initialization failed: ${e.message}")
            }

            databaseManager = dbManager
            log.d(tag = TAG) { "✅ Database initialized and ready" }

            // Inicializar managers en orden
            initializeAudioManager()
            initializeReconnectionManager()
            initializeCallManager()

            loadConfigurationFromDatabase()
            setupWebRtcEventListener()
            setupPlatformLifecycleObservers()

            // Inicializar sistema de recuperación de boot
            initializeRegistrationGuardian()
            setupBootRegistrationRecovery()

            startAccountSyncTask()
            CallStateManager.initialize()

            log.d(tag = TAG) { "✅ SIP Core initialization completed" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "❌ Critical error during initialization: ${e.message}" }
            throw e
        }
    }

    private fun initializeRegistrationGuardian() {
        try {
            val dbManager = databaseManager ?: run {
                log.e(tag = TAG) { "Cannot initialize RegistrationGuardian: database not available" }
                return
            }

            registrationGuardian = RegistrationGuardianManager(
                sipCoreManager = this,
                databaseManager = dbManager
            )
            registrationGuardian.initialize()
            log.d(tag = TAG) { "RegistrationGuardian initialized successfully" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing RegistrationGuardian: ${e.message}" }
        }
    }

    private fun setupBootRegistrationRecovery() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Esperar a que todos los managers estén completamente inicializados
                delay(2000)

                log.d(tag = TAG) { "Setting up boot registration recovery system..." }

                // Verificar si es necesario recuperar cuentas
                val needsRecovery = shouldPerformBootRecovery()

                if (needsRecovery) {
                    log.d(tag = TAG) { "Boot recovery needed, starting process..." }
                    initializeBootRegistration()
                } else {
                    log.d(tag = TAG) { "No boot recovery needed or accounts already active" }
                }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error setting up boot registration recovery: ${e.message}" }
            }
        }
    }

    private suspend fun initializeBootRegistration() {
        try {
            log.d(tag = TAG) { "🚀 Starting boot registration initialization..." }

            val dbManager = databaseManager ?: run {
                log.e(tag = TAG) { "❌ Database not available for boot registration" }
                return
            }

            // Obtener cuentas desde BD
            val dbAccounts = withContext(Dispatchers.IO) {
                try {
                    dbManager.getRegisteredSipAccounts().first()
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error loading accounts from database: ${e.message}" }
                    emptyList()
                }
            }

            log.d(tag = TAG) { "📋 Found ${dbAccounts.size} accounts in database for boot registration" }

            if (dbAccounts.isEmpty()) {
                log.d(tag = TAG) { "No accounts to register at boot" }
                return
            }

            // Registrar cada cuenta
            val registrationResults = mutableMapOf<String, Boolean>()

            dbAccounts.forEach { dbAccount ->
                try {
                    val accountKey = "${dbAccount.username}@${dbAccount.domain}"
                    log.d(tag = TAG) { "🔄 Processing boot registration for: $accountKey" }

                    // Crear AccountInfo
                    val accountInfo = AccountInfo(
                        username = dbAccount.username,
                        password = dbAccount.password,
                        domain = dbAccount.domain
                    ).apply {
                        token.value = dbAccount.pushToken ?: ""
                        provider.value = dbAccount.pushProvider ?: "fcm"
                        userAgent.value = "${userAgent()} Push"
                        isRegistered.value = false
                    }

                    // Agregar a cuentas activas
                    activeAccounts[accountKey] = accountInfo
                    updateRegistrationState(accountKey, RegistrationState.NONE)

                    // Intentar registro con modo push
                    delay(1000) // Delay entre registros

                    updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)
                    val success = safeRegister(accountInfo, isBackground = true)

                    registrationResults[accountKey] = success

                    if (success) {
                        log.d(tag = TAG) { "✅ Boot registration initiated for: $accountKey" }
                    } else {
                        log.w(tag = TAG) { "⚠️ Boot registration failed for: $accountKey, will retry" }
                        scheduleRegisterRetry(accountInfo)
                    }

                } catch (e: Exception) {
                    log.e(tag = TAG) { "❌ Error in boot registration for account: ${e.message}" }
                    registrationResults["${dbAccount.username}@${dbAccount.domain}"] = false
                }
            }

            // Resumen
            val successful = registrationResults.values.count { it }
            val failed = registrationResults.size - successful

            log.d(tag = TAG) { "🏁 Boot registration completed: $successful successful, $failed failed" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "💥 Critical error in boot registration initialization: ${e.message}" }
        }
    }

    private suspend fun shouldPerformBootRecovery(): Boolean {
        return try {
            // 1. Verificar si hay cuentas en memoria
            val activeAccountsCount = activeAccounts.size
            log.d(tag = TAG) { "Active accounts in memory: $activeAccountsCount" }

            // 2. Verificar si hay cuentas en BD
            val dbManager = databaseManager
            val dbAccountsCount = dbManager?.getRegisteredSipAccounts()?.first()?.size ?: 0
            log.d(tag = TAG) { "Accounts in database: $dbAccountsCount" }

            // 3. Verificar estados de registro
            val registeredCount = activeAccounts.values.count { it.isRegistered.value }
            log.d(tag = TAG) { "Currently registered accounts: $registeredCount" }

            // Necesita recuperación si:
            val needsRecovery = when {
                dbAccountsCount > 0 && activeAccountsCount == 0 -> {
                    log.d(tag = TAG) { "Recovery needed: accounts in DB but none in memory" }
                    true
                }
                activeAccountsCount > 0 && registeredCount == 0 -> {
                    log.d(tag = TAG) { "Recovery needed: accounts in memory but none registered" }
                    true
                }
                dbAccountsCount > activeAccountsCount -> {
                    log.d(tag = TAG) { "Recovery needed: more accounts in DB than in memory" }
                    true
                }
                else -> {
                    log.d(tag = TAG) { "No recovery needed" }
                    false
                }
            }

            needsRecovery

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error determining if boot recovery is needed: ${e.message}" }
            true // En caso de error, mejor intentar recuperación
        }
    }

    fun prepareAudioForCall() {
        webRtcManager.prepareAudioForCall()
    }

    fun onBluetoothConnectionChanged(isConnected: Boolean) {
        webRtcManager.onBluetoothConnectionChanged(isConnected)
    }

    fun refreshAudioDevicesWithBluetoothPriority() {
        webRtcManager.refreshAudioDevicesWithBluetoothPriority()
    }

    fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
        return webRtcManager.applyAudioRouteChange(audioUnitType)
    }

    fun getAvailableAudioUnits(): Set<AudioUnit> {
        return webRtcManager.getAvailableAudioUnits()
    }

    fun getCurrentActiveAudioUnit(): AudioUnit? {
        return webRtcManager.getCurrentActiveAudioUnit()
    }

    private fun initializeAudioManager() {
        try {
            val systemAudioManager = createAudioManager()
            audioManager = SipAudioManager(
                audioManager = systemAudioManager,
                webRtcManager = webRtcManager
            )
            audioManager.initialize()
            log.d(tag = TAG) { "SipAudioManager initialized successfully" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing SipAudioManager: ${e.message}" }
        }
    }

    private suspend fun initializeReconnectionManager() {
        try {
            reconnectionManager = SipReconnectionManager(
                messageHandler = messageHandler,
                sipCoreManager = this
            )

            reconnectionManager.setReconnectionListener(object : ReconnectionListener {
                override fun onNetworkLost() {
                    log.w(tag = TAG) { "🌐 Network lost detected by ReconnectionManager" }
                    lifecycleCallback?.invoke("NETWORK_LOST")

                    activeAccounts.values.forEach { account ->
                        account.isRegistered.value = false
                        val accountKey = "${account.username}@${account.domain}"
                        updateRegistrationState(accountKey, RegistrationState.NONE)
                    }
                }

                override fun onNetworkRestored() {
                    log.d(tag = TAG) { "🌐 Network restored detected by ReconnectionManager" }
                    lifecycleCallback?.invoke("NETWORK_RESTORED")
                }

                override fun onReconnectionStarted() {
                    log.d(tag = TAG) { "🔄 Reconnection process started" }
                    lifecycleCallback?.invoke("RECONNECTION_STARTED")
                }

                override fun onReconnectionCompleted(successful: Boolean) {
                    log.d(tag = TAG) { "🔄 Reconnection process completed: $successful" }
                    lifecycleCallback?.invoke("RECONNECTION_COMPLETED:$successful")
                }

                override fun onReconnectionAttempt(accountKey: String, attempt: Int) {
                    log.d(tag = TAG) { "🔄 Reconnection attempt $attempt for $accountKey" }
                    updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)
                }

                override fun onReconnectAccount(accountInfo: AccountInfo): Boolean {
                    return try {
                        log.d(tag = TAG) { "🔌 Attempting to reconnect WebSocket for ${accountInfo.username}@${accountInfo.domain}" }
                        connectWebSocketAndRegister(accountInfo)
                        true
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "❌ Error reconnecting account: ${e.message}" }
                        false
                    }
                }

                override fun onAccountReconnected(accountKey: String, successful: Boolean) {
                    if (successful) {
                        log.d(tag = TAG) { "✅ Account successfully reconnected: $accountKey" }
                        updateRegistrationState(accountKey, RegistrationState.OK)
                    } else {
                        log.w(tag = TAG) { "❌ Account reconnection failed: $accountKey" }
                        updateRegistrationState(accountKey, RegistrationState.FAILED)
                    }
                }

                override fun onReconnectionFailed(accountKey: String) {
                    log.e(tag = TAG) { "💥 Reconnection completely failed for: $accountKey" }
                    updateRegistrationState(accountKey, RegistrationState.FAILED)
                }
            })

            reconnectionManager.initialize()
            log.d(tag = TAG) { "SipReconnectionManager initialized successfully" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing SipReconnectionManager: ${e.message}" }
        }
    }

    private fun initializeCallManager() {
        try {
            callManager = CallManager(
                sipCoreManager = this,
                audioManager = audioManager,
                webRtcManager = webRtcManager,
                messageHandler = messageHandler
            )
            log.d(tag = TAG) { "CallManager initialized successfully" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing CallManager: ${e.message}" }
        }
    }

    private fun startAccountSyncTask() {
        accountSyncJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && !isShuttingDown) {
                try {
                    delay(ACCOUNT_SYNC_INTERVAL_MS)
                    syncAccountsWithDatabase()
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in account sync task: ${e.message}" }
                }
            }
        }
        log.d(tag = TAG) { "Account sync task started" }
    }

    private suspend fun syncAccountsWithDatabase() {
        accountSyncMutex.withLock {
            try {
                val dbManager = databaseManager ?: return

                val dbAccounts = dbManager.getRegisteredSipAccounts().first()

                log.d(tag = TAG) { "Syncing accounts - Memory: ${activeAccounts.size}, DB: ${dbAccounts.size}" }

                dbAccounts.forEach { dbAccount ->
                    val accountKey = "${dbAccount.username}@${dbAccount.domain}"

                    if (!activeAccounts.containsKey(accountKey)) {
                        log.d(tag = TAG) { "Found account in DB not in memory, adding: $accountKey" }

                        val accountInfo = AccountInfo(
                            username = dbAccount.username,
                            password = dbAccount.password,
                            domain = dbAccount.domain
                        ).apply {
                            token.value = dbAccount.pushToken ?: ""
                            provider.value = dbAccount.pushProvider ?: "fcm"
                            userAgent.value = userAgent()
                            isRegistered.value = false
                        }

                        activeAccounts[accountKey] = accountInfo
                        updateRegistrationState(accountKey, RegistrationState.NONE)
                    }
                }

                activeAccounts.forEach { (accountKey, accountInfo) ->
                    if (accountInfo.isRegistered.value) {
                        val dbAccount = dbAccounts.find {
                            "${it.username}@${it.domain}" == accountKey
                        }

                        dbAccount?.let {
                            dbManager.updateSipAccountRegistrationState(
                                it.id,
                                RegistrationState.OK,
                                Clock.System.now().toEpochMilliseconds() + 3600000L
                            )
                        }
                    }
                }

                log.d(tag = TAG) { "Account sync completed successfully" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error syncing accounts with database: ${e.message}" }
            }
        }
    }

    suspend fun recoverAccountsFromDatabase(): List<AccountInfo> {
        return try {
            log.d(tag = TAG) { "🔍 Recovering accounts from database..." }

            val dbManager = databaseManager ?: return emptyList()
            val dbAccounts = dbManager.getRegisteredSipAccounts().first()

            val recoveredAccounts = dbAccounts.mapNotNull { dbAccount ->
                try {
                    val accountKey = "${dbAccount.username}@${dbAccount.domain}"

                    val accountInfo = AccountInfo(
                        username = dbAccount.username,
                        password = dbAccount.password,
                        domain = dbAccount.domain
                    ).apply {
                        token.value = dbAccount.pushToken ?: ""
                        provider.value = dbAccount.pushProvider ?: "fcm"
                        userAgent.value = userAgent()
                        isRegistered.value = false
                    }

                    activeAccounts[accountKey] = accountInfo
                    updateRegistrationState(accountKey, RegistrationState.NONE)

                    log.d(tag = TAG) { "✅ Recovered account: $accountKey" }
                    accountInfo

                } catch (e: Exception) {
                    log.e(tag = TAG) { "❌ Error recovering account: ${e.message}" }
                    null
                }
            }

            log.d(tag = TAG) { "🔍 Account recovery completed: ${recoveredAccounts.size} accounts" }
            recoveredAccounts

        } catch (e: Exception) {
            log.e(tag = TAG) { "💥 Database account recovery failed: ${e.message}" }
            emptyList()
        }
    }

    internal fun getDatabaseManager(): DatabaseManager? {
        if (databaseManager == null) {
            try {
                databaseManager = DatabaseManager.getInstance()
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error getting database manager: ${e.message}" }
            }
        }
        return databaseManager
    }

    private fun loadConfigurationFromDatabase() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dbManager = databaseManager
                loadedConfig = dbManager?.loadOrCreateDefaultConfig()
                loadedConfig?.let { config ->
                    audioManager.loadAudioConfigFromDatabase(config)
                    log.d(tag = TAG) { "Configuration loaded from database" }
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error loading configuration: ${e.message}" }
            }
        }
    }

    internal fun setCallbacks(callbacks: SipCallbacks) {
        this.sipCallbacks = callbacks
        log.d(tag = TAG) { "SipCallbacks configured" }
    }

    fun updateRegistrationState(accountKey: String, newState: RegistrationState) {
        log.d(tag = TAG) { "Updating registration state for $accountKey: $newState" }

        val currentStates = _registrationStates.value.toMutableMap()
        val previousState = currentStates[accountKey]

        if (previousState == newState) {
            log.d(tag = TAG) { "Registration state unchanged for $accountKey: $newState" }
            return
        }

        currentStates[accountKey] = newState
        _registrationStates.value = currentStates

        val account = activeAccounts[accountKey]
        if (account != null) {
            when (newState) {
                RegistrationState.OK -> {
                    account.isRegistered.value = true
                    log.d(tag = TAG) { "Account marked as registered: $accountKey" }
                }

                RegistrationState.FAILED, RegistrationState.NONE, RegistrationState.CLEARED -> {
                    account.isRegistered.value = false
                    log.d(tag = TAG) { "Account marked as not registered: $accountKey" }
                }

                else -> {
                    // Estados intermedios
                }
            }

            // Actualizar BD en background
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dbManager = databaseManager
                    val dbAccount = dbManager?.getSipAccountByCredentials(account.username, account.domain)

                    dbAccount?.let {
                        val expiry = if (newState == RegistrationState.OK) {
                            Clock.System.now().toEpochMilliseconds() + 3600000L
                        } else null

                        dbManager.updateSipAccountRegistrationState(it.id, newState, expiry)
                    }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error updating registration state in database: ${e.message}" }
                }
            }

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    sipCallbacks?.onAccountRegistrationStateChanged(
                        account.username,
                        account.domain,
                        newState
                    )
                    sipCallbacks?.onRegistrationStateChanged(newState)
                    log.d(tag = TAG) { "Registration callbacks executed for $accountKey" }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in registration callbacks: ${e.message}" }
                }
            }

            notifyRegistrationStateChanged(newState, account.username, account.domain)
        }
    }

    fun updateRegistrationState(newState: RegistrationState) {
        currentAccountInfo?.let { account ->
            val accountKey = "${account.username}@${account.domain}"
            updateRegistrationState(accountKey, newState)
        }
    }

    fun getRegistrationState(accountKey: String): RegistrationState {
        return _registrationStates.value[accountKey] ?: RegistrationState.NONE
    }

    fun getAllRegistrationStates(): Map<String, RegistrationState> {
        return _registrationStates.value
    }

    private fun notifyRegistrationStateChanged(
        state: RegistrationState,
        username: String,
        domain: String
    ) {
        try {
            sipCallbacks?.onRegistrationStateChanged(state)
            sipCallbacks?.onAccountRegistrationStateChanged(username, domain, state)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error notifying registration state change: ${e.message}" }
        }
    }

    fun notifyCallStateChanged(state: CallState) {
        try {
            log.d(tag = TAG) { "Notifying call state change: $state" }

            when (state) {
                CallState.INCOMING_RECEIVED -> {
                    currentAccountInfo?.currentCallData?.let { callData ->
                        sipCallbacks?.onIncomingCall(
                            callData.value?.from ?: "",
                            callData.value?.remoteDisplayName
                        )
                    }
                }

                CallState.CONNECTED, CallState.STREAMS_RUNNING -> {
                    sipCallbacks?.onCallConnected()
                }

                CallState.ENDED -> {
                    sipCallbacks?.onCallTerminated()
                }

                else -> {
                    log.d(tag = TAG) { "Other call state: $state" }
                }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error notifying call state change: ${e.message}" }
        }
    }

    private fun setupWebRtcEventListener() {
        webRtcManager.setListener(object : WebRtcEventListener {
            override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                // Implementar envío de ICE candidate si es necesario
            }

            override fun onConnectionStateChange(state: WebRtcConnectionState) {
                when (state) {
                    WebRtcConnectionState.CONNECTED -> callManager?.handleWebRtcConnected()
                    WebRtcConnectionState.CLOSED -> callManager?.handleWebRtcClosed()
                    else -> {}
                }
            }

            override fun onRemoteAudioTrack() {
                log.d(tag = TAG) { "Remote audio track received" }
            }

            override fun onAudioDeviceChanged(device: AudioDevice?) {
                log.d(tag = TAG) { "Audio device changed: ${device?.name}" }
                audioManager.refreshAudioDevices()
            }

            override fun onRemoteAudioData(audioBytes: ByteArray) {
                log.d(tag = TAG) { "Audio device audioBytes: ${audioBytes}" }

            }

        })

    }

    private fun setupPlatformLifecycleObservers() {
        platformRegistration.setupNotificationObservers(object : AppLifecycleListener {
            override fun onEvent(event: AppLifecycleEvent) {
                when (event) {
                    AppLifecycleEvent.EnterBackground -> {
                        CoroutineScope(Dispatchers.IO).launch {
                            log.d(tag = TAG) { "App entering background" }
                            isAppInBackground = true
                            lifecycleCallback?.invoke("APP_BACKGROUNDED")
                            onAppBackgrounded()
                        }
                    }

                    AppLifecycleEvent.EnterForeground -> {
                        CoroutineScope(Dispatchers.IO).launch {
                            log.d(tag = TAG) { "App entering foreground" }
                            isAppInBackground = false
                            lifecycleCallback?.invoke("APP_FOREGROUNDED")
                            onAppForegrounded()
                        }
                    }

                    else -> {
                        log.d(tag = TAG) { "Other lifecycle event: $event" }
                    }
                }
            }
        })
    }

    internal fun handleCallTermination() {
        onCallTerminated?.invoke()
        sipCallbacks?.onCallTerminated()
    }

    private suspend fun refreshAllRegistrationsWithNewUserAgent() {
        if (CallStateManager.getCurrentState().isActive()) {
            log.d(tag = TAG) { "Skipping registration refresh - call is active" }
            return
        }

        log.d(tag = TAG) { "🔄 Starting registration refresh for all accounts" }

        val registeredAccounts = activeAccounts.values.filter { it.isRegistered.value }

        if (registeredAccounts.isEmpty()) {
            log.w(tag = TAG) { "⚠️ No registered accounts to refresh" }
            return
        }

        var successfulRefreshes = 0
        var failedRefreshes = 0

        registeredAccounts.forEach { accountInfo ->
            val accountKey = "${accountInfo.username}@${accountInfo.domain}"

            try {
                log.d(tag = TAG) { "🔄 Refreshing registration for: $accountKey" }

                if (!ensureWebSocketConnectivity(accountInfo)) {
                    log.e(tag = TAG) { "❌ Cannot ensure WebSocket connectivity for refresh: $accountKey" }
                    updateRegistrationState(accountKey, RegistrationState.FAILED)
                    failedRefreshes++
                    return@forEach
                }

                if (!accountInfo.isWebSocketHealthy()) {
                    log.e(tag = TAG) { "❌ WebSocket not healthy after connectivity check for refresh: $accountKey" }
                    updateRegistrationState(accountKey, RegistrationState.FAILED)
                    failedRefreshes++
                    return@forEach
                }

                accountInfo.userAgent.value = userAgent()
                updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)
                messageHandler.sendRegister(accountInfo, isAppInBackground)

                log.d(tag = TAG) { "✅ Registration refreshed successfully for: $accountKey" }
                successfulRefreshes++

            } catch (e: Exception) {
                log.e(tag = TAG) { "💥 Error refreshing registration for $accountKey: ${e.message}" }
                updateRegistrationState(accountKey, RegistrationState.FAILED)
                failedRefreshes++
            }
        }

        log.d(tag = TAG) { "🔄 Registration refresh completed - Success: $successfulRefreshes, Failed: $failedRefreshes" }
    }

    // === MÉTODOS PÚBLICOS DELEGADOS A MANAGERS ===

    fun register(
        username: String,
        password: String,
        domain: String,
        provider: String,
        token: String,
        forcePushMode: Boolean = false
    ) {
        val accountKey = "$username@$domain"
        if (forcePushMode) isAppInBackground = true

        try {
            log.d(tag = TAG) { "Starting register for $accountKey (push=$forcePushMode)" }

            // Guardar o actualizar en BD en background
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dbManager = databaseManager ?: return@launch
                    val dbAccounts = dbManager.getRegisteredSipAccounts().first()
                    log.d(tag = TAG) { "dbAccounts ${dbAccounts.size}" }

                    val existingAccount = dbAccounts.firstOrNull {
                        it.username == username && it.domain == domain
                    }

                    val finalPassword = if (password.isNotEmpty()) password
                    else existingAccount?.password ?: ""
                    val finalToken = if (token.isNotEmpty()) token
                    else existingAccount?.pushToken ?: ""
                    val finalProvider = if (provider.isNotEmpty()) provider
                    else existingAccount?.pushProvider ?: "fcm"

                    if (finalPassword.isEmpty()) {
                        log.e(tag = TAG) { "Cannot register $accountKey: no password available" }
                        updateRegistrationState(accountKey, RegistrationState.FAILED)
                        return@launch
                    }

                    dbManager.createOrUpdateSipAccount(
                        username = username,
                        password = finalPassword,
                        domain = domain,
                        pushToken = finalToken,
                        pushProvider = finalProvider
                    )

                    log.d(tag = TAG) { "Account saved to database: $accountKey" }

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error saving account to database: ${e.message}" }
                }
            }

            val accountInfo = AccountInfo(
                username = username,
                password = password,
                domain = domain
            ).apply {
                this.token.value = token
                this.provider.value = provider
                this.userAgent.value = if (forcePushMode) "${userAgent()} Push" else userAgent()
                this.isRegistered.value = !forcePushMode
            }

            activeAccounts[accountKey] = accountInfo

            if (forcePushMode) {
                CoroutineScope(Dispatchers.IO).launch {
                    updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)
                    val success = safeRegister(accountInfo, isBackground = true)
                    if (!success) scheduleRegisterRetry(accountInfo)
                }
            } else {
                updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)
                connectWebSocketAndRegister(accountInfo)
            }

        } catch (e: Exception) {
            updateRegistrationState(accountKey, RegistrationState.FAILED)
            throw Exception("Registration error: ${e.message}")
        }
    }

    private fun scheduleRegisterRetry(accountInfo: AccountInfo) {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"

        CoroutineScope(Dispatchers.IO).launch {
            var retryCount = 0
            val maxRetries = 5
            val baseDelay = 5000L

            while (retryCount < maxRetries) {
                retryCount++
                val delay = baseDelay * retryCount

                log.d(tag = TAG) { "Scheduling register retry $retryCount for $accountKey in ${delay}ms" }
                delay(delay)

                if (accountInfo.isRegistered.value) {
                    log.d(tag = TAG) { "Account $accountKey already registered, canceling retry" }
                    break
                }

                if (!networkManager.isNetworkAvailable()) {
                    log.d(tag = TAG) { "Network not available for retry $retryCount of $accountKey" }
                    continue
                }

                try {
                    log.d(tag = TAG) { "Attempting register retry $retryCount for $accountKey" }
                    updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)

                    val success = safeRegister(accountInfo, isBackground = true)

                    if (success) {
                        log.d(tag = TAG) { "Register retry $retryCount successful for $accountKey" }
                        break
                    } else {
                        log.w(tag = TAG) { "Register retry $retryCount failed for $accountKey" }
                        updateRegistrationState(accountKey, RegistrationState.FAILED)
                    }

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in register retry $retryCount for $accountKey: ${e.message}" }
                    updateRegistrationState(accountKey, RegistrationState.FAILED)
                }
            }

            if (retryCount >= maxRetries) {
                log.e(tag = TAG) { "All register retries exhausted for $accountKey" }
                updateRegistrationState(accountKey, RegistrationState.FAILED)
            }
        }
    }

    suspend fun unregister(username: String, domain: String) {
        val accountKey = "$username@$domain"
        val accountInfo = activeAccounts[accountKey] ?: return

        try {
            messageHandler.sendUnregister(accountInfo)
            accountInfo.webSocketClient.value?.close()
            activeAccounts.remove(accountKey)
            updateRegistrationState(accountKey, RegistrationState.NONE)

            // Eliminar de BD
            val dbManager = databaseManager
            val dbAccount = dbManager?.getSipAccountByCredentials(username, domain)
            dbAccount?.let {
                dbManager.deleteSipAccount(it.id)
                log.d(tag = TAG) { "Account removed from database: $accountKey" }
            }

            val currentStates = _registrationStates.value.toMutableMap()
            currentStates.remove(accountKey)
            _registrationStates.value = currentStates

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error unregistering account: ${e.message}" }
        }
    }

    // Métodos de llamadas (delegados a CallManager)
    fun makeCall(phoneNumber: String, sipName: String, domain: String) {
        val accountKey = "$sipName@$domain"
        val accountInfo = activeAccounts[accountKey] ?: run {
            log.e(tag = TAG) { "Account not found: $accountKey" }
            sipCallbacks?.onCallFailed("Account not found: $accountKey")
            return
        }

        currentAccountInfo = accountInfo
        callManager?.makeCall(phoneNumber, accountInfo)
    }

    fun endCall(callId: String? = null) = callManager?.endCall(callId)
    fun acceptCall(callId: String? = null) = callManager?.acceptCall(callId)
    fun declineCall(callId: String? = null) = callManager?.declineCall(callId)
    fun rejectCall(callId: String? = null) = callManager?.declineCall(callId)
    fun holdCall(callId: String? = null) = callManager?.holdCall(callId)
    fun resumeCall(callId: String? = null) = callManager?.resumeCall(callId)

    // Métodos de audio (delegados a SipAudioManager)
    fun mute() = audioManager.toggleMute()
    fun isMuted() = audioManager.isMuted()
    fun getAudioDevices() = audioManager.getAudioDevices()
    fun getCurrentDevices() = audioManager.getCurrentDevices()
    fun refreshAudioDevices() = audioManager.refreshAudioDevices()
    fun changeAudioDevice(device: AudioDevice) = audioManager.changeAudioDevice(device)
    fun saveIncomingRingtoneUri(uri: String) =
        audioManager.saveIncomingRingtoneUri(uri, databaseManager)

    fun saveOutgoingRingtoneUri(uri: String) =
        audioManager.saveOutgoingRingtoneUri(uri, databaseManager)

    suspend fun saveRingtoneUris(incomingUri: String?, outgoingUri: String?) =
        audioManager.saveRingtoneUris(incomingUri, outgoingUri, databaseManager)

    // Métodos DTMF (delegados a CallManager)
    fun sendDtmf(digit: Char, duration: Int = 160) = callManager?.sendDtmf(digit, duration)
    fun sendDtmfSequence(digits: String, duration: Int = 160) =
        callManager?.sendDtmfSequence(digits, duration)

    // Métodos de conectividad (delegados a SipReconnectionManager)
    suspend fun forceReconnection() {
        val accountsToReconnect = if (activeAccounts.isEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                val recoveredAccounts = recoverAccountsFromDatabase()
                reconnectionManager.forceReconnection(recoveredAccounts)
            }
            return
        } else {
            activeAccounts.values.toList()
        }

        reconnectionManager.forceReconnection(accountsToReconnect)
    }

    suspend fun verifyAndFixConnectivity() {
        val accountsToCheck = if (activeAccounts.isEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                val recoveredAccounts = recoverAccountsFromDatabase()
                reconnectionManager.verifyAndFixConnectivity(recoveredAccounts)
            }
            return
        } else {
            activeAccounts.values.toList()
        }

        reconnectionManager.verifyAndFixConnectivity(accountsToCheck)
    }

    suspend fun getConnectivityStatus(): Map<String, Any> {
        val reconnectionStatus = reconnectionManager.getConnectivityStatus()
        val additionalStatus = mapOf(
            "activeAccountsCount" to activeAccounts.size,
            "activeAccountKeys" to activeAccounts.keys,
            "registrationStates" to _registrationStates.value,
            "currentAccount" to currentAccountInfo?.let { "${it.username}@${it.domain}" },
            "isShuttingDown" to isShuttingDown
        )

        return (reconnectionStatus + additionalStatus) as Map<String, Any>
    }

    fun isNetworkAvailable() = reconnectionManager.isNetworkAvailable()

    // === MÉTODOS DE WEBSOCKET ===

    private fun connectWebSocketAndRegister(accountInfo: AccountInfo) {
        try {
            accountInfo.webSocketClient.value?.close()
            val headers = createHeaders()
            val webSocketClient = createWebSocketClient(accountInfo, headers)
            accountInfo.webSocketClient.value = webSocketClient
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error connecting WebSocket: ${e.stackTraceToString()}" }
        }
    }

    private fun createHeaders(): HashMap<String, String> {
        return hashMapOf(
            "User-Agent" to userAgent(),
            "Origin" to "https://telephony.${config.defaultDomain}",
            "Sec-WebSocket-Protocol" to WEBSOCKET_PROTOCOL
        )
    }

    private fun createWebSocketClient(
        accountInfo: AccountInfo,
        headers: Map<String, String>
    ): MultiplatformWebSocket {
        val websocket = createWebSocket(config.webSocketUrl, headers)
        setupWebSocketListeners(websocket, accountInfo)
        websocket.connect()
        websocket.startPingTimer(config.pingIntervalMs)
        websocket.startRegistrationRenewalTimer(REGISTRATION_CHECK_INTERVAL_MS, 60000L)
        return websocket
    }

    private fun setupWebSocketListeners(websocket: MultiplatformWebSocket, accountInfo: AccountInfo) {
        websocket.setListener(object : MultiplatformWebSocket.Listener {
            override fun onOpen() {
                CoroutineScope(Dispatchers.IO).launch {
                    log.d(tag = TAG) { "WebSocket open for ${accountInfo.username}@${accountInfo.domain}" }
                    lastConnectionCheck = Clock.System.now().toEpochMilliseconds()
                    messageHandler.sendRegister(accountInfo, isAppInBackground)
                }
            }

            override fun onMessage(message: String) {
                CoroutineScope(Dispatchers.IO).launch {
                    messageHandler.handleSipMessage(message, accountInfo)
                }
            }

            override fun onClose(code: Int, reason: String) {
                log.d(tag = TAG) { "WebSocket closed for ${accountInfo.username}@${accountInfo.domain}" }

                if (code != 1000 && !isShuttingDown) {
                    reconnectionManager.startReconnectionProcess(listOf(accountInfo))
                }
            }

            override fun onError(error: Exception) {
                log.e(tag = TAG) { "WebSocket error: ${error.message}" }
                accountInfo.isRegistered.value = false
                val accountKey = "${accountInfo.username}@${accountInfo.domain}"
                updateRegistrationState(accountKey, RegistrationState.FAILED)
            }

            override fun onPong(timeMs: Long) {
                lastConnectionCheck = Clock.System.now().toEpochMilliseconds()
            }

            override fun onRegistrationRenewalRequired(accountKey: String) {
                CoroutineScope(Dispatchers.IO).launch {
                    val account = activeAccounts[accountKey]
                    if (account != null && account.webSocketClient.value?.isConnected() == true) {
                        messageHandler.sendRegister(account, isAppInBackground)
                    } else {
                        account?.let {
                            reconnectionManager.reconnectAccountWithRetry(it)
                        }
                    }
                }
            }
        })
    }

    // === MANEJO DE EVENTOS DE REGISTRO ===

    fun handleRegistrationError(accountInfo: AccountInfo, reason: String) {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
        handleRegistrationFailure(accountKey, reason)
    }

    fun handleRegistrationSuccess(accountInfo: AccountInfo) {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
        log.d(tag = TAG) { "Registration successful for $accountKey" }

        accountInfo.isRegistered.value = true
        updateRegistrationState(accountKey, RegistrationState.OK)

        if (currentAccountInfo == null) {
            currentAccountInfo = accountInfo
            log.d(tag = TAG) { "Set current account to: $accountKey" }
        }

        CoroutineScope(Dispatchers.Main).launch {
            delay(100)
            sipCallbacks?.onAccountRegistrationStateChanged(
                accountInfo.username,
                accountInfo.domain,
                RegistrationState.OK
            )
        }
    }

    suspend fun forceGuardianRecovery(): String {
        return try {
            registrationGuardian.forceRecoverAllAccounts()
        } catch (e: Exception) {
            "Error in guardian recovery: ${e.message}"
        }
    }

    suspend fun getGuardianDiagnostic(): String {
        return try {
            registrationGuardian.getDiagnosticInfo()
        } catch (e: Exception) {
            "Guardian not available: ${e.message}"
        }
    }

    private fun handleRegistrationFailure(accountKey: String, reason: String) {
        log.e(tag = TAG) { "Handling registration failure for $accountKey: $reason" }
        val accountInfo = activeAccounts[accountKey] ?: return
        updateRegistrationState(accountKey, RegistrationState.FAILED)
    }

    private fun scheduleDelayedRecovery(accountKey: String, delayMs: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            delay(delayMs)

            val accountInfo = activeAccounts[accountKey]
            if (accountInfo != null && !accountInfo.isRegistered.value) {
                log.d(tag = TAG) { "Attempting delayed recovery for $accountKey" }

                try {
                    if (!networkManager.isNetworkAvailable()) {
                        log.w(tag = TAG) { "Network unavailable for delayed recovery of $accountKey" }
                        return@launch
                    }

                    val dbManager = databaseManager
                    val dbAccount = dbManager?.getSipAccountByCredentials(
                        accountInfo.username,
                        accountInfo.domain
                    )

                    if (dbAccount != null) {
                        accountInfo.password = dbAccount.password
                        accountInfo.token.value = dbAccount.pushToken ?: ""
                        accountInfo.provider.value = dbAccount.pushProvider ?: "fcm"

                        val success = safeRegister(accountInfo, true)

                        if (success) {
                            log.d(tag = TAG) { "Delayed recovery successful for $accountKey" }
                        } else {
                            log.e(tag = TAG) { "Delayed recovery failed for $accountKey" }
                        }
                    }

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in delayed recovery for $accountKey: ${e.message}" }
                }
            }
        }
    }

    fun performRegistrationHealthCheck(): String {
        log.d(tag = TAG) { "Performing registration health check..." }

        val report = StringBuilder()
        report.appendLine("=== REGISTRATION HEALTH CHECK ===")
        report.appendLine("Timestamp: ${Clock.System.now().toEpochMilliseconds()}")

        val issues = mutableListOf<String>()
        val corrections = mutableListOf<String>()

        try {
            val dbManager = databaseManager
            val dbAccounts = runBlocking { dbManager?.getRegisteredSipAccounts()?.first() ?: emptyList() }

            report.appendLine("Active accounts in memory: ${activeAccounts.size}")
            report.appendLine("Accounts in database: ${dbAccounts.size}")

            activeAccounts.forEach { (accountKey, accountInfo) ->
                val registrationState = getRegistrationState(accountKey)
                val webSocketConnected = accountInfo.webSocketClient.value?.isConnected() == true

                report.appendLine("\nAccount: $accountKey")
                report.appendLine("  Registration State: $registrationState")
                report.appendLine("  Internal Flag: ${accountInfo.isRegistered}")
                report.appendLine("  WebSocket Connected: $webSocketConnected")

                when {
                    registrationState == RegistrationState.FAILED -> {
                        issues.add("$accountKey has FAILED state")
                        CoroutineScope(Dispatchers.IO).launch {
                            log.d(tag = TAG) { "Auto-correcting FAILED state for $accountKey" }
                            handleRegistrationFailure(accountKey, "Health check detected failure")
                        }
                        corrections.add("Scheduled auto-correction for $accountKey")
                    }

                    registrationState == RegistrationState.OK && !webSocketConnected -> {
                        issues.add("$accountKey marked as OK but WebSocket not connected")
                        updateRegistrationState(accountKey, RegistrationState.NONE)
                        accountInfo.isRegistered.value = false
                        corrections.add("Corrected state for $accountKey to NONE")
                    }

                    registrationState == RegistrationState.NONE && webSocketConnected -> {
                        issues.add("$accountKey has WebSocket connected but state is NONE")
                        if (accountInfo.isRegistered.value) {
                            updateRegistrationState(accountKey, RegistrationState.OK)
                            corrections.add("Corrected state for $accountKey to OK")
                        }
                    }

                    registrationState == RegistrationState.IN_PROGRESS -> {
                        issues.add("$accountKey stuck in IN_PROGRESS state")
                        CoroutineScope(Dispatchers.IO).launch {
                            delay(5000)
                            if (getRegistrationState(accountKey) == RegistrationState.IN_PROGRESS) {
                                updateRegistrationState(accountKey, RegistrationState.FAILED)
                                handleRegistrationFailure(accountKey, "Stuck in progress")
                            }
                        }
                        corrections.add("Scheduled timeout correction for $accountKey")
                    }
                }
            }

            dbAccounts.forEach { dbAccount ->
                val accountKey = "${dbAccount.username}@${dbAccount.domain}"
                if (!activeAccounts.containsKey(accountKey)) {
                    issues.add("Account $accountKey exists in DB but not in memory")

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val accountInfo = AccountInfo(
                                username = dbAccount.username,
                                password = dbAccount.password,
                                domain = dbAccount.domain
                            ).apply {
                                token.value = dbAccount.pushToken ?: ""
                                provider.value = dbAccount.pushProvider ?: "fcm"
                                userAgent.value = "${userAgent()} Push"
                                isRegistered.value = false
                            }

                            activeAccounts[accountKey] = accountInfo
                            updateRegistrationState(accountKey, RegistrationState.NONE)

                            val success = safeRegister(accountInfo, true)
                            log.d(tag = TAG) { "Recovery attempt for $accountKey: $success" }

                        } catch (e: Exception) {
                            log.e(tag = TAG) { "Error recovering account $accountKey: ${e.message}" }
                        }
                    }
                    corrections.add("Initiated recovery for $accountKey")
                }
            }

            report.appendLine("\n=== SUMMARY ===")
            report.appendLine("Issues found: ${issues.size}")
            report.appendLine("Corrections applied: ${corrections.size}")

            if (issues.isNotEmpty()) {
                report.appendLine("\nIssues:")
                issues.forEach { report.appendLine("  - $it") }
            }

            if (corrections.isNotEmpty()) {
                report.appendLine("\nCorrections:")
                corrections.forEach { report.appendLine("  - $it") }
            }

            if (issues.isEmpty()) {
                report.appendLine("✅ All registrations are healthy")
            }

        } catch (e: Exception) {
            report.appendLine("❌ Error during health check: ${e.message}")
            log.e(tag = TAG) { "Error in registration health check: ${e.message}" }
        }

        val finalReport = report.toString()
        log.d(tag = TAG) { finalReport }
        return finalReport
    }

    suspend fun forceReregisterAllAccounts(): String {
        log.d(tag = TAG) { "=== FORCING RE-REGISTRATION OF ALL ACCOUNTS ===" }

        val report = StringBuilder()
        report.appendLine("=== FORCE RE-REGISTRATION REPORT ===")

        try {
            val accountsToReregister = activeAccounts.toMap()
            report.appendLine("Accounts to re-register: ${accountsToReregister.size}")

            if (accountsToReregister.isEmpty()) {
                val recoveredAccounts = recoverAccountsFromDatabase()
                report.appendLine("Recovered accounts from database: ${recoveredAccounts.size}")

                if (recoveredAccounts.isEmpty()) {
                    report.appendLine("❌ No accounts available for re-registration")
                    return report.toString()
                }
            }

            val results = mutableMapOf<String, Boolean>()

            accountsToReregister.forEach { (accountKey, accountInfo) ->
                try {
                    report.appendLine("\nProcessing: $accountKey")

                    accountInfo.webSocketClient.value?.close()
                    accountInfo.webSocketClient.value = null
                    accountInfo.isRegistered.value = false
                    updateRegistrationState(accountKey, RegistrationState.NONE)

                    delay(1000)

                    updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)
                    val success = safeRegister(accountInfo, true)

                    results[accountKey] = success
                    report.appendLine("  Result: ${if (success) "SUCCESS" else "FAILED"}")

                } catch (e: Exception) {
                    results[accountKey] = false
                    report.appendLine("  Result: ERROR - ${e.message}")
                    log.e(tag = TAG) { "Error force re-registering $accountKey: ${e.message}" }
                }

                delay(2000)
            }

            val successful = results.values.count { it }
            val failed = results.size - successful

            report.appendLine("\n=== FINAL SUMMARY ===")
            report.appendLine("Total processed: ${results.size}")
            report.appendLine("Successful: $successful")
            report.appendLine("Failed: $failed")

            if (successful > 0) {
                report.appendLine("✅ At least some accounts were successfully re-registered")
            }

            if (failed > 0) {
                report.appendLine("⚠️ Some accounts failed re-registration - automatic recovery scheduled")
                results.filter { !it.value }.keys.forEach { accountKey ->
                    scheduleDelayedRecovery(accountKey, 10000L)
                }
            }

        } catch (e: Exception) {
            report.appendLine("❌ Critical error in force re-registration: ${e.message}")
            log.e(tag = TAG) { "Critical error in force re-registration: ${e.message}" }
        }

        val finalReport = report.toString()
        log.d(tag = TAG) { finalReport }
        return finalReport
    }

    // === MÉTODOS DE ESTADO Y INFORMACIÓN ===

    fun getCallStatistics() = callHistoryManager.getCallStatistics()
    fun getRegistrationState(): RegistrationState {
        val registeredAccounts = _registrationStates.value.values.filter { it == RegistrationState.OK }
        return if (registeredAccounts.isNotEmpty()) RegistrationState.OK else RegistrationState.NONE
    }

    fun currentCall(): Boolean = callManager?.hasActiveCall() ?: false
    fun currentCallConnected(): Boolean = callManager?.hasConnectedCall() ?: false
    fun getCurrentCallState(): CallStateInfo = CallStateManager.getCurrentState()

    fun getAllActiveCalls(): List<CallData> = MultiCallManager.getAllCalls()
    fun getActiveCalls(): List<CallData> = MultiCallManager.getActiveCalls()
    fun cleanupTerminatedCalls() = MultiCallManager.cleanupTerminatedCalls()
    fun getCallsInfo(): String = MultiCallManager.getDiagnosticInfo()

    fun isSipCoreManagerHealthy(): Boolean {
        return try {
            audioManager.isWebRtcInitialized() && activeAccounts.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    // === MÉTODOS DE LIFECYCLE ===

    suspend fun onAppBackgrounded() {
        log.d(tag = TAG) { "App backgrounded - updating registrations" }
        isAppInBackground = true
        refreshAllRegistrationsWithNewUserAgent()
    }

    suspend fun onAppForegrounded() {
        log.d(tag = TAG) { "App foregrounded - updating registrations and checking connectivity" }
        isAppInBackground = false
        verifyAndFixConnectivity()
        refreshAllRegistrationsWithNewUserAgent()
    }

    fun enterPushMode(token: String? = null) {
        token?.let { newToken ->
            activeAccounts.values.forEach { accountInfo ->
                accountInfo.token.value = newToken
            }
        }
    }

    suspend fun switchToPushMode(username: String, domain: String) {
        val accountKey = "$username@$domain"
        val accountInfo = activeAccounts[accountKey] ?: run {
            log.w(tag = TAG) { "Account not found for push mode switch: $accountKey" }
            return
        }

        if (!accountInfo.isRegistered.value) {
            log.w(tag = TAG) { "Account not registered, cannot switch to push mode: $accountKey" }
            return
        }

        log.d(tag = TAG) { "Switching account to push mode: $accountKey" }
        updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)

        try {
            if (!ensureWebSocketConnectivity(accountInfo)) {
                log.e(tag = TAG) { "❌ Cannot ensure WebSocket connectivity for push mode switch: $accountKey" }
                updateRegistrationState(accountKey, RegistrationState.FAILED)
                return
            }

            if (!accountInfo.isWebSocketHealthy()) {
                log.e(tag = TAG) { "❌ WebSocket not healthy after connectivity check for push mode: $accountKey" }
                updateRegistrationState(accountKey, RegistrationState.FAILED)
                return
            }

            val pushUserAgent = "${userAgent()} Push"
            accountInfo.userAgent.value = pushUserAgent
            isAppInBackground = true

            messageHandler.sendRegister(accountInfo, true)

            log.d(tag = TAG) { "✅ Account switched to push mode successfully: $accountKey" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "💥 Error switching to push mode for $accountKey: ${e.message}" }
            updateRegistrationState(accountKey, RegistrationState.FAILED)
        }
    }

    suspend fun switchToForegroundMode(username: String, domain: String) {
        val accountKey = "$username@$domain"
        val accountInfo = activeAccounts[accountKey] ?: run {
            log.w(tag = TAG) { "Account not found for foreground mode switch: $accountKey" }
            return
        }

        if (!accountInfo.isRegistered.value) {
            log.w(tag = TAG) { "Account not registered, cannot switch to foreground mode: $accountKey" }
            return
        }

        log.d(tag = TAG) { "Switching account to foreground mode: $accountKey" }
        updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)

        try {
            if (!ensureWebSocketConnectivity(accountInfo)) {
                log.e(tag = TAG) { "❌ Cannot ensure WebSocket connectivity for foreground mode switch: $accountKey" }
                updateRegistrationState(accountKey, RegistrationState.FAILED)
                return
            }

            if (!accountInfo.isWebSocketHealthy()) {
                log.e(tag = TAG) { "❌ WebSocket not healthy after connectivity check for foreground mode: $accountKey" }
                updateRegistrationState(accountKey, RegistrationState.FAILED)
                return
            }

            accountInfo.userAgent.value = userAgent()
            isAppInBackground = false

            messageHandler.sendRegister(accountInfo, false)

            log.d(tag = TAG) { "✅ Account switched to foreground mode successfully: $accountKey" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "💥 Error switching to foreground mode for $accountKey: ${e.message}" }
            updateRegistrationState(accountKey, RegistrationState.FAILED)
        }
    }
    /**
     * Cambiar TODAS las cuentas activas a modo push con verificación de conectividad
     */
    suspend fun switchAllAccountsToPushMode() {
        log.d(tag = TAG) { "🔄 Switching all accounts to push mode" }

        val registeredAccounts = activeAccounts.filter { it.value.isRegistered.value }

        if (registeredAccounts.isEmpty()) {
            log.w(tag = TAG) { "⚠️ No registered accounts to switch to push mode" }
            return
        }

        registeredAccounts.forEach { (accountKey, accountInfo) ->
            try {
                switchToPushMode(accountInfo.username, accountInfo.domain)
            } catch (e: Exception) {
                log.e(tag = TAG) { "❌ Error switching $accountKey to push mode: ${e.message}" }
            }
        }

        log.d(tag = TAG) { "✅ Completed switching ${registeredAccounts.size} accounts to push mode" }
    }

    /**
     * Cambiar TODAS las cuentas activas a modo foreground con verificación de conectividad
     */
    suspend fun switchAllAccountsToForegroundMode() {
        log.d(tag = TAG) { "🔄 Switching all accounts to foreground mode" }

        val registeredAccounts = activeAccounts.filter { it.value.isRegistered.value }

        if (registeredAccounts.isEmpty()) {
            log.w(tag = TAG) { "⚠️ No registered accounts to switch to foreground mode" }
            return
        }

        registeredAccounts.forEach { (accountKey, accountInfo) ->
            try {
                switchToForegroundMode(accountInfo.username, accountInfo.domain)
            } catch (e: Exception) {
                log.e(tag = TAG) { "❌ Error switching $accountKey to foreground mode: ${e.message}" }
            }
        }

        log.d(tag = TAG) { "✅ Completed switching ${registeredAccounts.size} accounts to foreground mode" }
    }

    /**
     * Método de conveniencia para cambio masivo de modo basado en estado de app
     */
    suspend fun updateAllAccountsForAppState(isBackground: Boolean) {
        if (isBackground) {
            switchAllAccountsToPushMode()
        } else {
            switchAllAccountsToForegroundMode()
        }
    }

    // === MÉTODOS DE LIMPIEZA ===

    suspend fun unregisterAllAccounts() {
        log.d(tag = TAG) { "Starting complete unregister and shutdown" }
        isShuttingDown = true

        try {
            // Cancelar tareas de sincronización
            accountSyncJob?.cancel()

            healthCheckJob?.cancel()
            audioManager.stopAllRingtones()

            if (CallStateManager.getCurrentState().isActive()) {
                callManager?.endCall()
            }

            if (activeAccounts.isNotEmpty()) {
                val accountsToUnregister = activeAccounts.toMap()

                accountsToUnregister.values.forEach { accountInfo ->
                    try {
                        val accountKey = "${accountInfo.username}@${accountInfo.domain}"

                        accountInfo.webSocketClient.value?.let { webSocket ->
                            webSocket.stopPingTimer()
                            webSocket.stopRegistrationRenewalTimer()
                        }

                        if (accountInfo.isRegistered.value && accountInfo.webSocketClient.value?.isConnected() == true) {
                            messageHandler.sendUnregister(accountInfo)
                        }

                        accountInfo.webSocketClient.value?.close(1000, "User logout")
                        accountInfo.webSocketClient.value = null
                        accountInfo.isRegistered.value = false
                        accountInfo.resetCallState()

                        updateRegistrationState(accountKey, RegistrationState.CLEARED)
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error unregistering account: ${e.message}" }
                    }
                }
            }

            activeAccounts.clear()
            currentAccountInfo = null
            _registrationStates.value = emptyMap()

            audioManager.dispose()
            callStartTimeMillis = 0
            isAppInBackground = false
            isRegistrationInProgress = false
            lastConnectionCheck = 0L
            lastRegistrationAttempt = 0L

            CallStateManager.forceResetToIdle()
            CallStateManager.clearHistory()

            log.d(tag = TAG) { "Complete unregister and shutdown successful" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error during complete unregister: ${e.message}" }
        }
    }

    suspend fun dispose() {
        isShuttingDown = true

        accountSyncJob?.cancel()

        try {
            registrationGuardian.dispose()
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error disposing guardian: ${e.message}" }
        }

        audioManager.dispose()
        reconnectionManager.dispose()
        networkManager.dispose()

        MultiCallManager.clearAllCalls()
        activeAccounts.clear()
        _registrationStates.value = emptyMap()

        CallStateManager.resetToIdle()
        CallStateManager.clearHistory()

        databaseManager?.closeDatabase()
    }

    // === MÉTODOS AUXILIARES ===

    fun getMessageHandler(): SipMessageHandler = messageHandler
    fun getLoadedConfig(): AppConfigEntity? = loadedConfig
    fun notifyCallEndedForSpecificAccount(accountKey: String) {
        sipCallbacks?.onCallEndedForAccount(accountKey)
        lifecycleCallback?.invoke("CALL_ENDED:$accountKey")
    }

    // === MÉTODOS DE INTEGRACIÓN CON BD ===
    /**
     * Busca call logs en la base de datos
     */
    suspend fun searchCallLogsInDatabase(query: String): List<CallLog> {
        return try {
            val dbManager = databaseManager
            val searchResults = dbManager?.searchCallLogs(query)?.first()
            searchResults!!.toCallLogs()
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error searching call logs in database: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Obtiene missed calls desde la base de datos
     */
    suspend fun getMissedCallsFromDatabase(): List<CallLog> {
        return try {
            val dbManager = databaseManager
            val missedCallsWithContact = dbManager?.getMissedCallLogs()?.first()
            missedCallsWithContact!!.toCallLogs()
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting missed calls from database: ${e.message}" }
            callHistoryManager.getMissedCalls()
        }
    }

    suspend fun getCallLogsFromDatabase(limit: Int = 50): List<CallLog> {
        return try {
            val dbIntegration = DatabaseAutoIntegration.getInstance( this)
            val dbManager = databaseManager
            val callLogsWithContact = dbManager?.getRecentCallLogs(limit)?.first()
            callLogsWithContact!!.toCallLogs()
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting call logs from database: ${e.message}" }
            callHistoryManager.getAllCallLogs()
        }
    }

    /**
     * Sincroniza call logs de memoria a BD (útil para migrar datos existentes)
     */
    fun syncMemoryCallLogsToDB() {
        val memoryLogs = callHistoryManager.getAllCallLogs()

        if (memoryLogs.isEmpty()) {
            log.d(tag = TAG) { "No call logs in memory to sync" }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dbManager = databaseManager
                val currentAccount = currentAccountInfo

                if (currentAccount == null) {
                    log.w(tag = TAG) { "No current account for syncing call logs" }
                    return@launch
                }

                // Buscar o crear cuenta en BD
                var account = dbManager?.getSipAccountByCredentials(
                    currentAccount.username,
                    currentAccount.domain
                )

                if (account == null) {
                    account = dbManager?.createOrUpdateSipAccount(
                        username = currentAccount.username,
                        password = currentAccount.password,
                        domain = currentAccount.domain
                    )
                }

                // Convertir y guardar cada call log
                memoryLogs.forEach { callLog ->
                    try {
                        // Crear CallData desde CallLog para usar el método existente
                        val callData = CallData(
                            callId = callLog.id,
                            from = if (callLog.direction == CallDirections.INCOMING)
                                callLog.from else currentAccount.username,
                            to = if (callLog.direction == CallDirections.OUTGOING)
                                callLog.to else currentAccount.username,
                            direction = callLog.direction,
                            startTime = parseFormattedDate(callLog.formattedStartDate),
                            remoteDisplayName = callLog.contact ?: ""
                        )

                        // Calcular endTime basado en duración
                        val endTime = callData.startTime + (callLog.duration * 1000)

                        account?.let {
                            dbManager?.createCallLog(
                                accountId = it.id,
                                callData = callData,
                                callType = callLog.callType,
                                endTime = endTime
                            )
                        }

                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error syncing individual call log ${callLog.id}: ${e.message}" }
                    }
                }

                log.d(tag = TAG) { "Synced ${memoryLogs.size} call logs from memory to database" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error syncing call logs to database: ${e.message}" }
            }
        }
    }

    /**
     * Verificar y garantizar conectividad antes de enviar mensajes SIP
     */
    suspend fun ensureWebSocketConnectivity(accountInfo: AccountInfo): Boolean {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"

        try {
            // 1. Verificar si el WebSocket ya está conectado y funcional
            if (accountInfo.isWebSocketHealthy()) {
                log.d(tag = TAG) { "✅ WebSocket already healthy for: $accountKey" }
                return true
            }

            // 2. Verificar conectividad de red primero
            if (!networkManager.isNetworkAvailable()) {
                log.w(tag = TAG) { "🌐 No network connectivity available for: $accountKey" }
                return false
            }

            // 3. Si el WebSocket existe pero no está conectado, cerrarlo primero
            if (accountInfo.webSocketClient.value?.isConnected() == false) {
                log.d(tag = TAG) { "🧹 Cleaning up disconnected WebSocket for: $accountKey" }
                try {
                    accountInfo.webSocketClient.value?.close()
                    delay(1000) // Esperar cierre completo
                } catch (e: Exception) {
                    log.w(tag = TAG) { "⚠️ Error closing existing WebSocket: ${e.message}" }
                }
                accountInfo.webSocketClient.value = null
            }

            // 4. Crear nueva conexión WebSocket si es necesario
            if (accountInfo.webSocketClient == null) {
                log.d(tag = TAG) { "🔌 Creating new WebSocket connection for: $accountKey" }
                updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)

                // Crear nueva conexión
                val headers = createHeaders()
                val newWebSocket = createWebSocketClient(accountInfo, headers)
                accountInfo.webSocketClient.value  = newWebSocket

                // Esperar a que la conexión se establezca
                var waitTime = 0L
                val maxWaitTime = 10000L // 10 segundos máximo
                val checkInterval = 250L

                while (waitTime < maxWaitTime) {
                    if (accountInfo.webSocketClient.value?.isConnected() == true) {
                        log.d(tag = TAG) { "✅ WebSocket connection established for: $accountKey" }
                        return true
                    }

                    delay(checkInterval)
                    waitTime += checkInterval
                }

                log.e(tag = TAG) { "⏰ WebSocket connection timeout for: $accountKey" }
                updateRegistrationState(accountKey, RegistrationState.FAILED)
                return false
            }

            return accountInfo.isWebSocketHealthy()

        } catch (e: Exception) {
            log.e(tag = TAG) { "💥 Error ensuring WebSocket connectivity for $accountKey: ${e.message}" }
            updateRegistrationState(accountKey, RegistrationState.FAILED)
            return false
        }
    }

    /**
     * Enviar registro con verificación previa de conectividad
     */
    suspend fun safeRegister(accountInfo: AccountInfo, isBackground: Boolean = false): Boolean {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"

        try {
            log.d(tag = TAG) { "🔒 Safe register initiated for: $accountKey, background: $isBackground" }

            // 1. Asegurar conectividad WebSocket
            if (!ensureWebSocketConnectivity(accountInfo)) {
                log.e(tag = TAG) { "❌ Cannot ensure WebSocket connectivity for: $accountKey" }
                return false
            }

            // 2. Verificar una vez más antes de enviar
            if (!accountInfo.isWebSocketHealthy()) {
                log.e(tag = TAG) { "❌ WebSocket not healthy after connectivity check for: $accountKey" }
                updateRegistrationState(accountKey, RegistrationState.FAILED)
                return false
            }

            // 3. Actualizar user agent basado en modo
            accountInfo.userAgent.value = if (isBackground) {
                "${userAgent()} Push"
            } else {
                userAgent()
            }

            // 4. Enviar registro
            updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)
            messageHandler.sendRegister(accountInfo, isBackground)

            log.d(tag = TAG) { "✅ Safe register message sent for: $accountKey" }
            return true

        } catch (e: Exception) {
            log.e(tag = TAG) { "💥 Error in safe register for $accountKey: ${e.message}" }
            updateRegistrationState(accountKey, RegistrationState.FAILED)
            return false
        }
    }

    private fun parseFormattedDate(formattedDate: String): Long {
        // Implementación simple para parsear la fecha formateada
        // Formato esperado: "DD.MM.YYYY HH:MM"
        return try {
            // Para este ejemplo, usar timestamp actual si no se puede parsear
            // En producción, implementar parser completo
            Clock.System.now().toEpochMilliseconds()
        } catch (e: Exception) {
            Clock.System.now().toEpochMilliseconds()
        }
    }

    /**
     * Actualiza el método de missed calls para usar BD
     */
    fun getMissedCalls(): List<CallLog> {
        return try {
            runBlocking { getMissedCallsFromDatabase() }
        } catch (e: Exception) {
            log.w(tag = TAG) { "Database unavailable, using memory fallback: ${e.message}" }
            callHistoryManager.getMissedCalls()
        }
    }

    /**
     * Actualiza el método de call logs para número específico
     */
    fun getCallLogsForNumber(phoneNumber: String): List<CallLog> {
        return try {
            runBlocking { getCallLogsForNumberFromDatabase(phoneNumber) }
        } catch (e: Exception) {
            log.w(tag = TAG) { "Database unavailable, using memory fallback: ${e.message}" }
            callHistoryManager.getCallLogsForNumber(phoneNumber)
        }
    }

    /**
     * Limpia call logs tanto en BD como en memoria
     */
    fun clearCallLogs() {
        try {
            // Limpiar en BD
            runBlocking {
                val dbManager = DatabaseManager.getInstance()
                dbManager.clearAllCallLogs()
            }
            log.d(tag = TAG) { "Call logs cleared from database" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error clearing call logs from database: ${e.message}" }
        }

        // Limpiar en memoria también
        callHistoryManager.clearCallLogs()
        log.d(tag = TAG) { "Call logs cleared from memory" }
    }

    /**
     * Obtiene call logs para un número específico desde la BD
     */
    suspend fun getCallLogsForNumberFromDatabase(phoneNumber: String): List<CallLog> {
        return try {
            val dbManager = DatabaseManager.getInstance()
            val callLogsFlow = dbManager.getRecentCallLogs(1000) // Obtener más para filtrar
            val allLogs = callLogsFlow.first()

            // Filtrar por número de teléfono
            val filteredLogs = allLogs.filter {
                it.callLog.phoneNumber == phoneNumber
            }

            filteredLogs.toCallLogs()
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting call logs for number from database: ${e.message}" }
            callHistoryManager.getCallLogsForNumber(phoneNumber)
        }
    }

    fun callLogs(): List<CallLog> {
        return try {
            runBlocking { getCallLogsFromDatabase() }
        } catch (e: Exception) {
            callHistoryManager.getAllCallLogs()
        }
    }

    // === NUEVOS MÉTODOS DE GESTIÓN DE CUENTAS ===

    fun getAllRegisteredAccountKeys(): Set<String> {
        return activeAccounts.filter { it.value.isRegistered.value }.keys
    }

    fun getAllAccountKeys(): Set<String> = activeAccounts.keys.toSet()

    fun isAccountRegistered(username: String, domain: String): Boolean {
        val accountKey = "$username@$domain"
        return activeAccounts[accountKey]?.isRegistered?.value ?: false
    }

    fun getAccountInfo(username: String, domain: String): AccountInfo? {
        val accountKey = "$username@$domain"
        return activeAccounts[accountKey]
    }

    suspend fun updatePushTokenForAllAccounts(newToken: String, provider: String = "fcm") {
        activeAccounts.values.forEach { accountInfo ->
            if (accountInfo.isRegistered.value) {
                accountInfo.token.value = newToken
                accountInfo.provider.value = provider
                try {
                    messageHandler.sendRegister(accountInfo, isAppInBackground)

                    // Actualizar en BD también
                    val dbManager = getDatabaseManager()
                    val dbAccount = dbManager?.getSipAccountByCredentials(
                        accountInfo.username,
                        accountInfo.domain
                    )
                    dbAccount?.let {
                        dbManager.createOrUpdateSipAccount(
                            username = it.username,
                            password = it.password,
                            domain = it.domain,
                            displayName = it.displayName,
                            pushToken = newToken,
                            pushProvider = provider
                        )
                    }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error updating push token: ${e.message}" }
                }
            }
        }
    }
}