package com.eddyslarez.kmpsiprtc

import kotlinx.datetime.Clock
import com.eddyslarez.kmpsiprtc.core.*
import com.eddyslarez.kmpsiprtc.data.database.*
import com.eddyslarez.kmpsiprtc.data.database.converters.toCallLogs
import com.eddyslarez.kmpsiprtc.data.database.entities.*
import com.eddyslarez.kmpsiprtc.data.models.*
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.repository.*
import com.eddyslarez.kmpsiprtc.services.calls.*
import com.eddyslarez.kmpsiprtc.services.pushMode.PushModeManager
import com.eddyslarez.kmpsiprtc.services.sip.BootRegistrationManager
import com.eddyslarez.kmpsiprtc.services.sip.RegistrationRecoveryResult
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.compareTo
import kotlin.concurrent.Volatile
import com.eddyslarez.kmpsiprtc.core.SipCoreManager
import com.eddyslarez.kmpsiprtc.data.database.DatabaseManager
import com.eddyslarez.kmpsiprtc.data.database.entities.AppConfigEntity
import com.eddyslarez.kmpsiprtc.data.database.entities.ContactEntity
import com.eddyslarez.kmpsiprtc.data.models.*
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.repository.CallLogWithContact
import com.eddyslarez.kmpsiprtc.repository.GeneralStatistics
import com.eddyslarez.kmpsiprtc.services.calls.CallStateManager
import com.eddyslarez.kmpsiprtc.services.calls.MultiCallManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

/**
 * KMP SIP/RTC Library - Main Entry Point
 * Supports Android, iOS and Desktop platforms
 *
 * @author Eddys Larez
 * @version 2.0.0 (Multiplatform)
 */

class KmpSipRtc private constructor() {

    private var sipCoreManager: SipCoreManager? = null
    private var isInitialized = false
    private lateinit var config: SipConfiguration
    private val listeners = mutableSetOf<SipEventListener>()
    private var registrationListener: RegistrationListener? = null
    private var callListener: CallListener? = null
    private var incomingCallListener: IncomingCallListener? = null
    private var databaseManager: DatabaseManager? = null

    private val lastNotifiedRegistrationStates = mutableMapOf<String, RegistrationState>()
    private val lastNotifiedCallState = mutableStateOf<CallStateInfo?>(null)
    private val initMutex = Mutex()

    companion object {
        @Volatile
        private var INSTANCE: KmpSipRtc? = null
        private val LOCK = SynchronizedObject()

        private const val TAG = "KmpSipRtc"

        fun getInstance(): KmpSipRtc {
            return INSTANCE ?: synchronized(LOCK) {
                INSTANCE ?: KmpSipRtc().also { INSTANCE = it }
            }
        }
    }

    // === CONFIGURATION ===

    data class SipConfiguration(
        val defaultDomain: String = "",
        val webSocketUrl: String = "",
        val userAgent: String = "KmpSipRtc/1.0",
        val enableLogs: Boolean = true,
        val enableAutoReconnect: Boolean = true,
        val pingIntervalMs: Long = 30000L,
        val incomingRingtoneUri: String? = null,
        val outgoingRingtoneUri: String? = null
    )

    // === LISTENER INTERFACES ===

    interface SipEventListener {
        fun onRegistrationStateChanged(
            state: RegistrationState,
            username: String,
            domain: String
        ) {}

        fun onCallStateChanged(stateInfo: CallStateInfo) {}
        fun onIncomingCall(callInfo: IncomingCallInfo) {}
        fun onCallConnected(callInfo: CallInfo) {}
        fun onCallEnded(callInfo: CallInfo, reason: CallEndReason) {}
        fun onCallFailed(error: String, callInfo: CallInfo?) {}
        fun onDtmfReceived(digit: Char, callInfo: CallInfo) {}
        fun onAudioDeviceChanged(device: AudioDevice) {}
        fun onNetworkStateChanged(isConnected: Boolean) {}
    }

    interface RegistrationListener {
        fun onRegistrationSuccessful(username: String, domain: String)
        fun onRegistrationFailed(username: String, domain: String, error: String)
        fun onUnregistered(username: String, domain: String)
        fun onRegistrationExpiring(username: String, domain: String, expiresIn: Long)
    }

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

    interface IncomingCallListener {
        fun onIncomingCall(callInfo: IncomingCallInfo)
        fun onIncomingCallCancelled(callInfo: IncomingCallInfo)
        fun onIncomingCallTimeout(callInfo: IncomingCallInfo)
    }

    // === DATA CLASSES ===

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

    data class IncomingCallInfo(
        val callId: String,
        val callerNumber: String,
        val callerName: String?,
        val targetAccount: String,
        val timestamp: Long,
        val headers: Map<String, String> = emptyMap()
    )

    enum class CallDirection {
        INCOMING,
        OUTGOING
    }

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

    // === INITIALIZATION ===

    suspend fun initialize(config: SipConfiguration = SipConfiguration()) {
        initMutex.withLock {
            if (isInitialized) {
                log.w(tag = TAG) { "Library already initialized" }
                return
            }

            try {
                log.d(tag = TAG) { "Initializing KmpSipRtc v1.0.0" }

                this.config = config
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

                // IMPORTANTE: Esperar a que la inicialización complete
                sipCoreManager?.initialize()

                // NUEVO: Verificar que los managers críticos estén listos
                val manager = sipCoreManager
                if (manager == null) {
                    throw SipLibraryException("Failed to create SipCoreManager")
                }

                // Esperar un poco más para asegurar que los managers internos estén listos
                var retries = 0
                val maxRetries = 50 // 5 segundos máximo
                while (retries < maxRetries) {
                    if (manager.isSipCoreManagerHealthy()) {
                        log.d(tag = TAG) { "✅ All managers initialized successfully" }
                        break
                    }
                    delay(100)
                    retries++
                }

                if (retries >= maxRetries) {
                    log.w(tag = TAG) { "⚠️ Initialization timeout - some managers may not be ready" }
                }

                clearInitialStates()
                setupInternalListeners()

                // Esperar a que la base de datos esté lista
                val dbReady = databaseManager?.waitForInitialization() ?: false
                if (!dbReady) {
                    log.w(tag = TAG) { "⚠️ Database initialization timeout" }
                }

                isInitialized = true
                log.d(tag = TAG) { "✅ KmpSipRtc initialized successfully" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "❌ Error initializing library: ${e.message}" }
                throw SipLibraryException("Failed to initialize library", e)
            }
        }
    }

    private fun clearInitialStates() {
        try {
            log.d(tag = TAG) { "Clearing initial states..." }
            lastNotifiedRegistrationStates.clear()
            lastNotifiedCallState.value = null
            CallStateManager.clearHistory()
            log.d(tag = TAG) { "Initial states cleared" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error clearing initial states: ${e.message}" }
        }
    }

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
                }
            })

            CoroutineScope(Dispatchers.Main).launch {
                var isFirstState = true

                CallStateManager.callStateFlow.collect { stateInfo ->
                    if (isFirstState) {
                        isFirstState = false
                        if (stateInfo.timestamp > Clock.System.now().toEpochMilliseconds() + 1000) {
                            log.w(tag = TAG) { "Skipping invalid initial state with future timestamp" }
                            return@collect
                        }
                        if (MultiCallManager.getAllCalls().isEmpty() && stateInfo.state != CallState.IDLE) {
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

    // === NOTIFICATION METHODS ===

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

        CoroutineScope(Dispatchers.Main).launch {
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

    private fun notifyCallInitiated(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call initiated" }
        try {
            callListener?.onCallInitiated(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallInitiated: ${e.message}" }
        }
    }

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

    private fun notifyCallRinging(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call ringing" }
        try {
            callListener?.onCallRinging(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallRinging: ${e.message}" }
        }
    }

    private fun notifyCallHeld(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call held" }
        try {
            callListener?.onCallHeld(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallHeld: ${e.message}" }
        }
    }

    private fun notifyCallResumed(callInfo: CallInfo) {
        log.d(tag = TAG) { "Notifying call resumed" }
        try {
            callListener?.onCallResumed(callInfo)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in CallListener onCallResumed: ${e.message}" }
        }
    }

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

    private fun handleIncomingCall() {
        val manager = sipCoreManager ?: return
        val account = manager.currentAccountInfo ?: return
        val callData = account.currentCallData.value ?: return

        val callInfo = IncomingCallInfo(
            callId = callData.callId,
            callerNumber = callData.from,
            callerName = callData.remoteDisplayName.takeIf { it.isNotEmpty() },
            targetAccount = account.username,
            timestamp = callData.startTime
        )

        notifyIncomingCall(callInfo)
    }

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
            timestamp = callData?.startTime ?: Clock.System.now().toEpochMilliseconds()
        )
    }

    // === HELPER METHODS ===

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
                duration = if (callData.startTime > 0) Clock.System.now().toEpochMilliseconds() - callData.startTime else 0,
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
                duration = if (manager.callStartTimeMillis > 0) Clock.System.now().toEpochMilliseconds() - manager.callStartTimeMillis else 0,
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

    private fun mapErrorReasonToCallEndReason(errorReason: CallErrorReason): CallEndReason {
        return when (errorReason) {
            CallErrorReason.BUSY -> CallEndReason.BUSY
            CallErrorReason.NO_ANSWER -> CallEndReason.NO_ANSWER
            CallErrorReason.REJECTED -> CallEndReason.REJECTED
            CallErrorReason.NETWORK_ERROR -> CallEndReason.NETWORK_ERROR
            else -> CallEndReason.ERROR
        }
    }

    private fun generateCallId(): String {
        return "call_${Clock.System.now().toEpochMilliseconds()}_${(1000..9999).random()}"
    }

    // === PUBLIC API METHODS ===

    // Listeners
    fun addSipEventListener(listener: SipEventListener) {
        listeners.add(listener)
        log.d(tag = TAG) { "SipEventListener added. Total listeners: ${listeners.size}" }
    }

    fun removeSipEventListener(listener: SipEventListener) {
        listeners.remove(listener)
        log.d(tag = TAG) { "SipEventListener removed. Total listeners: ${listeners.size}" }
    }

    fun setRegistrationListener(listener: RegistrationListener?) {
        this.registrationListener = listener
        log.d(tag = TAG) { "RegistrationListener configured" }
    }

    fun setCallListener(listener: CallListener?) {
        this.callListener = listener
        log.d(tag = TAG) { "CallListener configured" }
    }

    fun setIncomingCallListener(listener: IncomingCallListener?) {
        this.incomingCallListener = listener
        log.d(tag = TAG) { "IncomingCallListener configured" }
    }

    // Registration
    suspend fun registerAccount(
        username: String,
        password: String,
        domain: String,
        pushToken: String? = null,
        pushProvider: String = "fcm",
        forcePushMode: Boolean = false
    ) {
        checkInitialized()
        log.d(tag = TAG) { "Registering account: $username@$domain" }
        sipCoreManager?.register(
            username = username,
            password = password,
            domain = domain,
            provider = pushProvider,
            token = pushToken ?: "",
            forcePushMode = forcePushMode
        )
    }

    suspend fun unregisterAccount(username: String, domain: String) {
        checkInitialized()
        val accountKey = "$username@$domain"
        log.d(tag = TAG) { "Unregistering account: $accountKey" }
        lastNotifiedRegistrationStates.remove(accountKey)
        sipCoreManager?.unregister(username, domain)
    }

    suspend fun unregisterAllAccounts() {
        checkInitialized()
        log.d(tag = TAG) { "Unregistering all accounts" }
        lastNotifiedRegistrationStates.clear()
        lastNotifiedCallState.value = null
        sipCoreManager?.unregisterAllAccounts()
    }

    fun getRegistrationState(username: String, domain: String): RegistrationState {
        checkInitialized()
        val accountKey = "$username@$domain"
        return sipCoreManager?.getRegistrationState(accountKey) ?: RegistrationState.NONE
    }

    fun getAllRegistrationStates(): Map<String, RegistrationState> {
        checkInitialized()
        return sipCoreManager?.getAllRegistrationStates() ?: emptyMap()
    }

    fun getRegistrationStatesFlow(): Flow<Map<String, RegistrationState>> {
        checkInitialized()
        return sipCoreManager?.registrationStatesFlow ?: flowOf(emptyMap())
    }

    // Call Management
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

    fun unholdCall(callId: String? = null) = resumeCall(callId)

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
                    duration = if (callData.startTime > 0) Clock.System.now().toEpochMilliseconds() - callData.startTime else 0,
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

    fun cleanupTerminatedCalls() {
        checkInitialized()
        sipCoreManager?.cleanupTerminatedCalls()
    }

    fun hasActiveCall(): Boolean {
        checkInitialized()
        return MultiCallManager.hasActiveCalls()
    }

    // Call State
    fun getCallStateFlow(): Flow<CallStateInfo> {
        checkInitialized()
        return CallStateManager.callStateFlow
    }

    fun getCurrentCallState(): CallStateInfo {
        checkInitialized()
        return CallStateManager.getCurrentState()
    }

    fun getCallStateHistory(): List<CallStateInfo> {
        checkInitialized()
        return CallStateManager.getStateHistory()
    }

    fun clearCallStateHistory() {
        checkInitialized()
        CallStateManager.clearHistory()
    }

    // Audio Management
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

    fun isMuted(): Boolean {
        checkInitialized()
        return sipCoreManager?.webRtcManager?.isMuted() ?: false
    }

    fun changeAudioDevice(device: AudioDevice) {
        checkInitialized()
        sipCoreManager?.changeAudioDevice(device)
    }

    fun refreshAudioDevices() {
        checkInitialized()
        sipCoreManager?.refreshAudioDevices()
    }

    fun getCurrentAudioDevices(): Pair<AudioDevice?, AudioDevice?> {
        checkInitialized()
        return sipCoreManager?.getCurrentDevices() ?: Pair(null, null)
    }

    fun getAvailableAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        checkInitialized()
        return sipCoreManager?.getAudioDevices() ?: Pair(emptyList(), emptyList())
    }

    fun prepareAudioForCall() {
        sipCoreManager?.prepareAudioForCall()
    }

    fun onBluetoothConnectionChanged(isConnected: Boolean) {
        sipCoreManager?.onBluetoothConnectionChanged(isConnected)
    }

    fun refreshAudioDevicesWithBluetoothPriority() {
        sipCoreManager?.refreshAudioDevicesWithBluetoothPriority()
    }

    fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
        return sipCoreManager?.applyAudioRouteChange(audioUnitType) == true
    }

    fun getAvailableAudioUnits(): Set<AudioUnit>? {
        return sipCoreManager?.getAvailableAudioUnits()
    }

    fun getCurrentActiveAudioUnit(): AudioUnit? {
        return sipCoreManager?.getCurrentActiveAudioUnit()
    }

    // Ringtones
    fun saveIncomingRingtoneUri(uri: String) {
        checkInitialized()
        try {
            sipCoreManager?.saveIncomingRingtoneUri(uri)
            log.d(tag = TAG) { "Incoming ringtone URI updated: $uri" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting incoming ringtone: ${e.message}" }
        }
    }

    fun saveOutgoingRingtoneUri(uri: String) {
        checkInitialized()
        try {
            sipCoreManager?.saveOutgoingRingtoneUri(uri)
            log.d(tag = TAG) { "Outgoing ringtone URI updated: $uri" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting outgoing ringtone: ${e.message}" }
        }
    }

    suspend fun saveRingtoneUris(incomingUri: String?, outgoingUri: String?) {
        checkInitialized()
        try {
            sipCoreManager?.saveRingtoneUris(incomingUri, outgoingUri)
            log.d(tag = TAG) { "Both ringtone URIs updated" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting ringtone URIs: ${e.message}" }
        }
    }

    // DTMF
    fun sendDtmf(digit: Char, duration: Int = 160): Boolean {
        checkInitialized()
        return sipCoreManager?.sendDtmf(digit, duration) ?: false
    }

    fun sendDtmfSequence(digits: String, duration: Int = 160): Boolean {
        checkInitialized()
        return sipCoreManager?.sendDtmfSequence(digits, duration) ?: false
    }

    // Call Logs
    fun getCallLogs(limit: Int = 50): List<CallLog> {
        checkInitialized()
        return sipCoreManager?.callLogs() ?: emptyList()
    }

    fun getMissedCalls(): List<CallLog> {
        checkInitialized()
        return sipCoreManager?.getMissedCalls() ?: emptyList()
    }

    fun getCallLogsForNumber(phoneNumber: String): List<CallLog> {
        checkInitialized()
        return sipCoreManager?.getCallLogsForNumber(phoneNumber) ?: emptyList()
    }

    fun clearCallLogs() {
        checkInitialized()
        sipCoreManager?.clearCallLogs()
    }

    suspend fun searchCallLogs(query: String): List<CallLog> {
        checkInitialized()
        return sipCoreManager?.searchCallLogsInDatabase(query) ?: emptyList()
    }

    fun getCallLogsFlow(limit: Int = 50): Flow<List<CallLog>>? {
        checkInitialized()
        return try {
            databaseManager?.getRecentCallLogs(limit)?.map { it.toCallLogs() }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting call logs flow: ${e.message}" }
            null
        }
    }

    fun getMissedCallsFlow(): Flow<List<CallLog>>? {
        checkInitialized()
        return try {
            databaseManager?.getMissedCallLogs()?.map { it.toCallLogs() }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting missed calls flow: ${e.message}" }
            null
        }
    }

    // Diagnostics
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

    suspend fun forceReregisterAll(): String {
        checkInitialized()
        return try {
            log.d(tag = TAG) { "Force re-registration initiated" }
            sipCoreManager?.forceReregisterAllAccounts()
                ?: "❌ SipCoreManager not available"
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in force re-registration: ${e.message}" }
            "❌ Force re-registration error: ${e.message}"
        }
    }

    suspend fun forceGuardianRecovery(): String {
        checkInitialized()
        return try {
            sipCoreManager?.forceGuardianRecovery()
                ?: "❌ SipCoreManager not available"
        } catch (e: Exception) {
            "❌ Guardian recovery error: ${e.message}"
        }
    }

    suspend fun getGuardianDiagnostic(): String {
        checkInitialized()
        return try {
            sipCoreManager?.getGuardianDiagnostic()
                ?: "❌ SipCoreManager not available"
        } catch (e: Exception) {
            "❌ Error getting guardian diagnostic: ${e.message}"
        }
    }

    suspend fun verifyAndFixConnectivity() {
        checkInitialized()
        val accounts = sipCoreManager?.activeAccounts?.values?.toList() ?: emptyList()
        if (accounts.isEmpty()) {
            val recoveredAccounts = sipCoreManager?.recoverAccountsFromDatabase() ?: emptyList()
            CoroutineScope(Dispatchers.IO).launch {
                sipCoreManager?.let { manager ->
                    // Use the reconnection manager to verify connectivity
                    manager.networkManager.isNetworkAvailable()
                }
            }
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                sipCoreManager?.verifyAndFixConnectivity()
            }
        }
    }

    fun isNetworkAvailable(): Boolean {
        checkInitialized()
        return sipCoreManager?.isNetworkAvailable() ?: false
    }

    fun isSystemHealthy(): Boolean {
        checkInitialized()
        return sipCoreManager?.isSipCoreManagerHealthy() ?: false
    }

    // Utilities
    private fun checkInitialized() {
        if (!isInitialized || sipCoreManager == null) {
            throw SipLibraryException("Library not initialized. Call initialize() first.")
        }
    }

    suspend fun dispose() {
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
                log.d(tag = TAG) { "KmpSipRtc disposed completely" }
            }
        }
    }

    class SipLibraryException(message: String, cause: Throwable? = null) :
        Exception(message, cause)
}


// MutableStateFlow wrapper for compatibility
private class mutableStateOf<T>(initialValue: T) {
    private val _flow = MutableStateFlow(initialValue)

    var value: T
        get() = _flow.value
        set(value) {
            _flow.value = value
        }
}
