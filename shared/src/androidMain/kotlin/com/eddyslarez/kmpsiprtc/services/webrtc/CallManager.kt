package com.eddyslarez.kmpsiprtc.services.webrtc
import android.Manifest
import androidx.annotation.RequiresPermission
import com.eddyslarez.kmpsiprtc.core.SipAudioManager
import com.eddyslarez.kmpsiprtc.core.SipCoreManager
import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.CallData
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.data.models.CallErrorReason
import com.eddyslarez.kmpsiprtc.data.models.CallState
import com.eddyslarez.kmpsiprtc.data.models.CallTypes
import com.eddyslarez.kmpsiprtc.data.models.DtmfRequest
import com.eddyslarez.kmpsiprtc.platform.calculateMD5
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.calls.CallHoldManager
import com.eddyslarez.kmpsiprtc.services.calls.CallStateManager
import com.eddyslarez.kmpsiprtc.services.calls.MultiCallManager
import com.eddyslarez.kmpsiprtc.services.sip.SipMessageHandler
import com.eddyslarez.kmpsiprtc.services.sip.SipMessageParser
import com.eddyslarez.kmpsiprtc.services.webrtc.WebRtcManager
import com.eddyslarez.kmpsiprtc.utils.generateId
import com.eddyslarez.kmpsiprtc.utils.generateSipTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
class CallManager(
    private val sipCoreManager: SipCoreManager,
    private val audioManager: SipAudioManager,
    private val webRtcManager: WebRtcManager,
    private val messageHandler: SipMessageHandler,
    private val translationServerUrl: String = "http://206.245.129.101:3000"
) {
    private val callHoldManager = CallHoldManager(webRtcManager)
    private val dtmfQueue = mutableListOf<DtmfRequest>()
    private var isDtmfProcessing = false
    private val dtmfMutex = Mutex()

    // ✅ NUEVO: Manager de traducción
    private var dualStreamCallManager: DualStreamCallManager? = null

    // ✅ NUEVO: Flag para controlar si se usa traducción
    var isTranslationEnabled: Boolean = false
    var translationSourceLanguage: String = "es"
    var translationTargetLanguage: String = "en"

    companion object {
        private const val TAG = "CallManager"
    }

    /**
     * Realizar llamada saliente (con o sin traducción)
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun makeCall(
        phoneNumber: String,
        accountInfo: AccountInfo,
        enableTranslation: Boolean = isTranslationEnabled,
        sourceLang: String = translationSourceLanguage,
        targetLang: String = translationTargetLanguage
    ) {
        if (!accountInfo.isRegistered.value) {
            log.d(tag = TAG) { "Error: Not registered with SIP server" }
            sipCoreManager.sipCallbacks?.onCallFailed("Not registered with SIP server")
            return
        }

        log.d(tag = TAG) { "Making call from ${accountInfo.username}@${accountInfo.domain} to $phoneNumber" }
        log.d(tag = TAG) { "Translation enabled: $enableTranslation" }

        audioManager.stopAllRingtones()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sdp = if (enableTranslation) {
                    // ✅ Llamada con traducción
                    log.d(tag = TAG) { "🌐 Starting call with translation: $sourceLang -> $targetLang" }
                    startTranslatedOutgoingCall(sourceLang, targetLang)
                } else {
                    // ✅ Llamada normal
                    log.d(tag = TAG) { "📞 Starting normal call" }
                    audioManager.prepareAudioForOutgoingCall()
                    audioManager.createOffer()
                }

                val callId = accountInfo.generateCallId()
                val md5Hash = calculateMD5(callId)
                val callData = CallData(
                    callId = callId,
                    to = phoneNumber,
                    from = accountInfo.username,
                    direction = CallDirections.OUTGOING,
                    inviteFromTag = generateSipTag(),
                    localSdp = sdp,
                    md5Hash = md5Hash
                )

                accountInfo.currentCallData.value = callData

                CallStateManager.startOutgoingCall(callId, phoneNumber)
                sipCoreManager.notifyCallStateChanged(CallState.OUTGOING_INIT)

                audioManager.playOutgoingRingtone()
                messageHandler.sendInvite(accountInfo, callData)

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error creating call: ${e.stackTraceToString()}" }
                sipCoreManager.sipCallbacks?.onCallFailed("Error creating call: ${e.message}")

                accountInfo.currentCallData.value?.let { callData ->
                    CallStateManager.callError(
                        callData.callId,
                        errorReason = CallErrorReason.NETWORK_ERROR
                    )
                }
                audioManager.stopOutgoingRingtone()
                disposeDualStreamManager()
            }
        }
    }

    /**
     * ✅ NUEVO: Iniciar llamada saliente con traducción
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun startTranslatedOutgoingCall(
        sourceLang: String,
        targetLang: String
    ): String = withContext(Dispatchers.IO) {
        try {
            log.d(tag = TAG) { "🌐 Initializing DualStreamCallManager..." }

            dualStreamCallManager = DualStreamCallManager(
                webRtcManager = webRtcManager as AndroidWebRtcManager,
                serverUrl = translationServerUrl
            )

            val result = dualStreamCallManager!!.initiateCall(sourceLang, targetLang)

            if (result.isFailure) {
                throw result.exceptionOrNull() ?: Exception("Failed to start translation")
            }

            val sdp = result.getOrThrow()
            log.d(tag = TAG) { "✅ Translation call initialized, SDP length: ${sdp.length}" }
            sdp

        } catch (e: Exception) {
            log.e(tag = TAG) { "❌ Error starting translated call: ${e.message}" }
            throw e
        }
    }

    /**
     * Aceptar llamada entrante (con o sin traducción)
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun acceptCall(
        callId: String? = null,
        enableTranslation: Boolean = isTranslationEnabled,
        sourceLang: String = translationSourceLanguage,
        targetLang: String = translationTargetLanguage
    ) {
        val accountInfo = sipCoreManager.currentAccountInfo ?: run {
            log.e(tag = TAG) { "❌ No current account" }
            return
        }

        log.d(tag = TAG) { "🟢 Accept call requested for callId: $callId" }
        log.d(tag = TAG) { "Translation enabled: $enableTranslation" }

        val targetCallData = accountInfo.currentCallData.value ?: run {
            log.e(tag = TAG) { "❌ No call data in AccountInfo" }
            return
        }

        if (targetCallData.direction != CallDirections.INCOMING) {
            log.w(tag = TAG) { "❌ Cannot accept - not incoming" }
            return
        }

        val callState = CallStateManager.getCurrentState()
        if (callState.state != CallState.INCOMING_RECEIVED) {
            log.w(tag = TAG) { "❌ Cannot accept - invalid state: ${callState.state}" }
            return
        }

        val remoteSdp = targetCallData.remoteSdp
        log.d(tag = TAG) { "🔍 Validating remote SDP..." }
        log.d(tag = TAG) { "  - remoteSdp length: ${remoteSdp.length}" }

        if (remoteSdp.isBlank()) {
            log.e(tag = TAG) { "❌ FATAL: remoteSdp is blank!" }
            CallStateManager.callError(
                targetCallData.callId,
                errorReason = CallErrorReason.MEDIA_ERROR
            )
            sipCoreManager.sipCallbacks?.onCallFailed("Remote SDP not available")
            return
        }

        log.d(tag = TAG) { "✅ SDP validation passed: ${remoteSdp.length} chars" }
        audioManager.stopAllRingtones()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val answerSdp = if (enableTranslation) {
                    // ✅ Llamada con traducción
                    log.d(tag = TAG) { "🌐 Answering call with translation: $sourceLang -> $targetLang" }
                    answerTranslatedCall(remoteSdp, sourceLang, targetLang)
                } else {
                    // ✅ Llamada normal
                    log.d(tag = TAG) { "📞 Answering normal call" }
                    audioManager.prepareAudioForIncomingCall()
                    audioManager.createAnswer(remoteSdp)
                }

                log.d(tag = TAG) { "✅ Answer created: ${answerSdp.length} chars" }
                targetCallData.localSdp = answerSdp

                log.d(tag = TAG) { "📤 Sending 200 OK..." }
                messageHandler.sendInviteOkResponse(accountInfo, targetCallData)

                CallStateManager.callConnected(targetCallData.callId, 200)
                sipCoreManager.notifyCallStateChanged(CallState.CONNECTED)

                delay(500)

                log.d(tag = TAG) { "🔊 Enabling audio..." }
                audioManager.setAudioEnabled(true)

                log.d(tag = TAG) { "✅ Call accepted successfully" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "💥 Error accepting call: ${e.message}" }
                log.e(tag = TAG) { e.stackTraceToString() }

                CallStateManager.callError(
                    targetCallData.callId,
                    errorReason = CallErrorReason.MEDIA_ERROR
                )

                sipCoreManager.sipCallbacks?.onCallFailed("Failed to accept: ${e.message}")

                try {
                    declineCall(callId)
                } catch (declineError: Exception) {
                    log.e(tag = TAG) { "Error declining after failure: ${declineError.message}" }
                }

                disposeDualStreamManager()
            }
        }
    }

    /**
     * ✅ NUEVO: Responder llamada entrante con traducción
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun answerTranslatedCall(
        offerSdp: String,
        sourceLang: String,
        targetLang: String
    ): String = withContext(Dispatchers.IO) {
        try {
            log.d(tag = TAG) { "🌐 Initializing DualStreamCallManager for incoming call..." }

            dualStreamCallManager = DualStreamCallManager(
                webRtcManager = webRtcManager as AndroidWebRtcManager,
                serverUrl = translationServerUrl
            )

            val result = dualStreamCallManager!!.answerCall(offerSdp, sourceLang, targetLang)

            if (result.isFailure) {
                throw result.exceptionOrNull() ?: Exception("Failed to answer with translation")
            }

            val sdp = result.getOrThrow()
            log.d(tag = TAG) { "✅ Translation answer created, SDP length: ${sdp.length}" }
            sdp

        } catch (e: Exception) {
            log.e(tag = TAG) { "❌ Error answering translated call: ${e.message}" }
            throw e
        }
    }

    /**
     * Finalizar llamada
     */
    fun endCall(callId: String? = null) {
        val accountInfo = sipCoreManager.currentAccountInfo ?: run {
            log.e(tag = TAG) { "No account" }
            return
        }

        val targetCallData = accountInfo.currentCallData.value ?: run {
            log.e(tag = TAG) { "No call data" }
            return
        }

        val callState = CallStateManager.getCurrentState()

        if (!callState.isActive()) {
            log.w(tag = TAG) { "No active call to end" }
            return
        }

        val endTime = Clock.System.now().toEpochMilliseconds()
        val currentState = callState.state

        log.d(tag = TAG) { "Ending call: ${targetCallData.callId}" }

        // ✅ Detener traducción si está activa
        disposeDualStreamManager()

        audioManager.stopAllRingtones()

        CallStateManager.startEnding(targetCallData.callId)
        clearDtmfQueue()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (currentState) {
                    CallState.CONNECTED, CallState.STREAMS_RUNNING, CallState.PAUSED -> {
                        log.d(tag = TAG) { "Sending BYE" }
                        messageHandler.sendBye(accountInfo, targetCallData)
                        sipCoreManager.callHistoryManager.addCallLog(
                            targetCallData,
                            CallTypes.SUCCESS,
                            endTime
                        )
                    }

                    CallState.OUTGOING_INIT, CallState.OUTGOING_PROGRESS, CallState.OUTGOING_RINGING -> {
                        log.d(tag = TAG) { "Sending CANCEL" }
                        messageHandler.sendCancel(accountInfo, targetCallData)
                        sipCoreManager.callHistoryManager.addCallLog(
                            targetCallData,
                            CallTypes.ABORTED,
                            endTime
                        )
                    }

                    CallState.INCOMING_RECEIVED -> {
                        log.d(tag = TAG) { "Sending DECLINE" }
                        messageHandler.sendDeclineResponse(accountInfo, targetCallData)
                        sipCoreManager.callHistoryManager.addCallLog(
                            targetCallData,
                            CallTypes.DECLINED,
                            endTime
                        )
                    }

                    else -> {
                        messageHandler.sendBye(accountInfo, targetCallData)
                    }
                }

                delay(500)
                audioManager.dispose()

                delay(500)
                CallStateManager.callEnded(targetCallData.callId)
                sipCoreManager.notifyCallStateChanged(CallState.ENDED)

                accountInfo.resetCallState()
                sipCoreManager.handleCallTermination()

                log.d(tag = TAG) { "Call cleanup completed" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error during cleanup: ${e.message}" }
                audioManager.stopAllRingtones()
                accountInfo.resetCallState()
            }
        }
    }

    /**
     * Rechazar llamada entrante
     */
    fun declineCall(callId: String? = null) {
        val accountInfo = sipCoreManager.currentAccountInfo ?: run {
            log.e(tag = TAG) { "No current account available for declining call" }
            return
        }

        val targetCallData = when {
            callId != null -> {
                val accountCallData = accountInfo.currentCallData.value
                if (accountCallData?.callId == callId) {
                    log.d(tag = TAG) { "✅ Found call in AccountInfo (preferred)" }
                    accountCallData
                } else {
                    log.d(tag = TAG) { "Looking in MultiCallManager" }
                    MultiCallManager.getCall(callId)
                }
            }
            else -> {
                log.d(tag = TAG) { "Using current call from AccountInfo" }
                accountInfo.currentCallData.value
            }
        } ?: run {
            log.e(tag = TAG) { "No call data available for declining" }
            return
        }

        if (targetCallData.direction != CallDirections.INCOMING) {
            log.w(tag = TAG) { "Cannot decline call - not incoming. Direction: ${targetCallData.direction}" }
            return
        }

        log.d(tag = TAG) { "Declining call: ${targetCallData.callId}" }

        // ✅ Detener traducción si está activa
        disposeDualStreamManager()

        if (targetCallData.toTag.isNullOrEmpty()) {
            targetCallData.toTag = generateId()
            log.d(tag = TAG) { "Generated toTag: ${targetCallData.toTag}" }
        }

        log.d(tag = TAG) { "Stopping all ringtones..." }
        audioManager.stopAllRingtones()

        log.d(tag = TAG) { "Sending 603 Decline response..." }
        messageHandler.sendDeclineResponse(accountInfo, targetCallData)

        val endTime = Clock.System.now().toEpochMilliseconds()
        sipCoreManager.callHistoryManager.addCallLog(targetCallData, CallTypes.DECLINED, endTime)
        log.d(tag = TAG) { "Call log recorded as DECLINED" }

        CallStateManager.callEnded(targetCallData.callId, sipReason = "Declined")
        sipCoreManager.notifyCallStateChanged(CallState.ENDED)

        if (accountInfo.currentCallData.value?.callId == targetCallData.callId) {
            CoroutineScope(Dispatchers.IO).launch {
                accountInfo.resetCallState()
                log.d(tag = TAG) { "AccountInfo call state reset" }
            }
        }

        log.d(tag = TAG) { "✅ Call declined successfully: ${targetCallData.callId}" }
    }

    /**
     * ✅ NUEVO: Limpiar recursos de traducción
     */
    private fun disposeDualStreamManager() {
        dualStreamCallManager?.let { manager ->
            log.d(tag = TAG) { "🧹 Disposing DualStreamCallManager..." }
            try {
                manager.endCall()
                manager.dispose()
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error disposing DualStreamCallManager: ${e.message}" }
            } finally {
                dualStreamCallManager = null
            }
        }
    }

    /**
     * ✅ NUEVO: Configurar traducción globalmente
     */
    fun configureTranslation(
        enabled: Boolean,
        sourceLanguage: String = "es",
        targetLanguage: String = "en"
    ) {
        isTranslationEnabled = enabled
        translationSourceLanguage = sourceLanguage
        translationTargetLanguage = targetLanguage

        log.d(tag = TAG) { "🌐 Translation configured: enabled=$enabled, $sourceLanguage -> $targetLanguage" }
    }

    /**
     * ✅ NUEVO: Verificar estado de traducción
     */
    fun isTranslationActive(): Boolean {
        return dualStreamCallManager != null
    }

    /**
     * ✅ NUEVO: Obtener estado de traducción
     */
    fun getTranslationState(): DualStreamCallManager.CallState? {
        return dualStreamCallManager?.callState?.value
    }

    // ... (resto de métodos sin cambios)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun toggleMute(): Boolean {
        val muted = audioManager.toggleMute()
        // ✅ También mutar en DualStreamCallManager si está activo
        dualStreamCallManager?.setMuted(muted)
        return muted
    }

    fun isMuted(): Boolean = audioManager.isMuted()

    fun holdCall(callId: String? = null) {
        val accountInfo = sipCoreManager.currentAccountInfo ?: return
        val targetCallData = if (callId != null) {
            MultiCallManager.getCall(callId)
        } else {
            accountInfo.currentCallData.value
        } ?: return

        val currentState = CallStateManager.getCurrentState()
        if (currentState.state != CallState.STREAMS_RUNNING &&
            currentState.state != CallState.CONNECTED
        ) {
            log.w(tag = TAG) { "Cannot hold call in current state: ${currentState.state}" }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                CallStateManager.startHold(targetCallData.callId)
                sipCoreManager.notifyCallStateChanged(CallState.PAUSING)

                callHoldManager.holdCall()?.let { holdSdp ->
                    targetCallData.localSdp = holdSdp
                    targetCallData.isOnHold = true
                    messageHandler.sendReInvite(accountInfo, targetCallData, holdSdp)

                    delay(1000)
                    CallStateManager.callOnHold(targetCallData.callId)
                    sipCoreManager.notifyCallStateChanged(CallState.PAUSED)
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error holding call: ${e.message}" }
            }
        }
    }

    fun resumeCall(callId: String? = null) {
        val accountInfo = sipCoreManager.currentAccountInfo ?: return
        val targetCallData = if (callId != null) {
            MultiCallManager.getCall(callId)
        } else {
            accountInfo.currentCallData.value
        } ?: return

        val currentState = CallStateManager.getCurrentState()
        if (currentState.state != CallState.PAUSED) {
            log.w(tag = TAG) { "Cannot resume call in current state: ${currentState.state}" }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                CallStateManager.startResume(targetCallData.callId)
                sipCoreManager.notifyCallStateChanged(CallState.RESUMING)

                callHoldManager.resumeCall()?.let { resumeSdp ->
                    targetCallData.localSdp = resumeSdp
                    targetCallData.isOnHold = false
                    messageHandler.sendReInvite(accountInfo, targetCallData, resumeSdp)

                    delay(1000)
                    CallStateManager.callResumed(targetCallData.callId)
                    sipCoreManager.notifyCallStateChanged(CallState.STREAMS_RUNNING)
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error resuming call: ${e.message}" }
            }
        }
    }

    fun sendDtmf(digit: Char, duration: Int = 160): Boolean {
        val validDigits = setOf(
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '*', '#', 'A', 'B', 'C', 'D', 'a', 'b', 'c', 'd'
        )

        if (!validDigits.contains(digit)) {
            return false
        }

        val request = DtmfRequest(digit, duration)
        CoroutineScope(Dispatchers.IO).launch {
            dtmfMutex.withLock {
                dtmfQueue.add(request)
            }
            processDtmfQueue()
        }

        return true
    }

    fun sendDtmfSequence(digits: String, duration: Int = 160): Boolean {
        if (digits.isEmpty()) return false

        digits.forEach { digit ->
            sendDtmf(digit, duration)
        }

        return true
    }

    private suspend fun processDtmfQueue() = withContext(Dispatchers.IO) {
        dtmfMutex.withLock {
            if (isDtmfProcessing || dtmfQueue.isEmpty()) {
                return@withLock
            }
            isDtmfProcessing = true
        }

        try {
            while (true) {
                val request: DtmfRequest? = dtmfMutex.withLock {
                    if (dtmfQueue.isNotEmpty()) {
                        dtmfQueue.removeAt(0)
                    } else {
                        null
                    }
                }

                if (request == null) break

                val success = sendSingleDtmf(request.digit, request.duration)
                if (success) {
                    delay(150)
                }
            }
        } finally {
            dtmfMutex.withLock {
                isDtmfProcessing = false
            }
        }
    }

    private suspend fun sendSingleDtmf(digit: Char, duration: Int): Boolean {
        val currentAccount = sipCoreManager.currentAccountInfo
        val callData = currentAccount?.currentCallData?.value

        if (currentAccount == null || callData == null ||
            !CallStateManager.getCurrentState().isConnected()
        ) {
            return false
        }

        return try {
            webRtcManager.sendDtmfTones(
                tones = digit.toString().uppercase(),
                duration = duration,
                gap = 100
            )
        } catch (e: Exception) {
            false
        }
    }

    internal fun clearDtmfQueue() {
        CoroutineScope(Dispatchers.IO).launch {
            dtmfMutex.withLock {
                dtmfQueue.clear()
                isDtmfProcessing = false
            }
        }
    }

    fun getAudioDevices() = audioManager.getAudioDevices()

    fun changeAudioDevice(device: AudioDevice) = audioManager.changeAudioDevice(device)

    fun hasActiveCall(): Boolean = CallStateManager.getCurrentState().isActive()

    fun hasConnectedCall(): Boolean = CallStateManager.getCurrentState().isConnected()

    fun handleWebRtcConnected() {
        sipCoreManager.callStartTimeMillis = Clock.System.now().toEpochMilliseconds()

        sipCoreManager.currentAccountInfo?.currentCallData?.let { callData ->
            CallStateManager.streamsRunning(callData.value?.callId ?: generateId())
        }

        sipCoreManager.notifyCallStateChanged(CallState.STREAMS_RUNNING)

        // ✅ Notificar conexión al DualStreamCallManager si está activo
        dualStreamCallManager?.onCallConnected()
    }

    fun handleWebRtcClosed() {
        val callData = sipCoreManager.currentAccountInfo?.currentCallData?.value

        // ✅ Limpiar recursos de traducción
        disposeDualStreamManager()

        if (callData == null) {
            log.w(tag = TAG) { "WebRTC closed but no active call data found" }
            CallStateManager.callEnded(generateId())
            sipCoreManager.notifyCallStateChanged(CallState.ENDED)
            return
        }

        CallStateManager.callEnded(callData.callId)
        sipCoreManager.notifyCallStateChanged(CallState.ENDED)

        val endTime = Clock.System.now().toEpochMilliseconds()
        val finalState = CallStateManager.getCurrentState().state
        val callType = determineCallType(callData, finalState)

        try {
            sipCoreManager.callHistoryManager.addCallLog(callData, callType, endTime)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error adding call log after WebRTC closed: ${e.message}" }
        }
    }

    private fun determineCallType(callData: CallData, finalState: CallState): CallTypes {
        return when (finalState) {
            CallState.CONNECTED, CallState.STREAMS_RUNNING, CallState.ENDED -> CallTypes.SUCCESS
            CallState.ERROR -> if (callData.direction == CallDirections.INCOMING) {
                CallTypes.MISSED
            } else {
                CallTypes.ABORTED
            }
            else -> if (callData.direction == CallDirections.INCOMING) {
                CallTypes.MISSED
            } else {
                CallTypes.ABORTED
            }
        }
    }
}