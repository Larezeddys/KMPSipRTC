package com.eddyslarez.kmpsiprtc.core

import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.CallData
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.data.models.CallErrorReason
import com.eddyslarez.kmpsiprtc.data.models.CallState
import com.eddyslarez.kmpsiprtc.data.models.CallTypes
import com.eddyslarez.kmpsiprtc.data.models.DtmfRequest
import com.eddyslarez.kmpsiprtc.platform.KFile
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
    private val messageHandler: SipMessageHandler
) {
    private val callHoldManager = CallHoldManager(webRtcManager)
    private val dtmfQueue = mutableListOf<DtmfRequest>()
    private var isDtmfProcessing = false
    private val dtmfMutex = Mutex()

    companion object {
        private const val TAG = "CallManager"
    }

    /**
     * Realizar llamada saliente
     */
    /**
     * Realizar llamada saliente
     */
    fun makeCall(
        phoneNumber: String,
        accountInfo: AccountInfo,
        enableTranslation: Boolean = true,
        recordCall: Boolean = true,
    ) {
        if (!accountInfo.isRegistered.value) {
            log.d(tag = TAG) { "Error: Not registered with SIP server" }
            sipCoreManager.sipCallbacks?.onCallFailed("Not registered with SIP server")
            return
        }

        log.d(tag = TAG) { "Making call to $phoneNumber (Translation: $enableTranslation)" }
        audioManager.stopAllRingtones()

        CoroutineScope(Dispatchers.IO).launch {
            try {

                // Flujo normal sin traducción
                audioManager.prepareAudioForOutgoingCall()
                val sdp = audioManager.createOffer()

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

                if (recordCall) {
                    webRtcManager.startCallRecording(callId)
                    log.d(TAG) { "🎙️ Recording started for outgoing call $callId" }
                }

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
            }
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

        // Obtener CallData de AccountInfo (fuente primaria)
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

        // Detener ringtones
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
                // ✅ Detener grabación si estaba activa
                if (webRtcManager.isRecordingCall()) {
                    log.d(TAG) { "🛑 Stopping recording for call ${targetCallData.callId}" }
                    val result = webRtcManager.stopCallRecording()

                    result?.mixedFile?.let { file ->
                        log.d(TAG) { "📁 Saved mixed recording: ${file}" }
                        saveRecordingMetadata(targetCallData.callId, file)
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

    private fun saveRecordingMetadata(callId: String, file: String) {
        try {
            // Aquí podrías guardar en base de datos local o Firebase, por ejemplo:
            log.d(TAG) { "✅ Recording saved: $callId → ${file}" }
            // callDatabase.insert(CallRecordingEntity(callId, file.absolutePath, System.currentTimeMillis()))
        } catch (e: Exception) {
            log.e(TAG) { "Error saving metadata: ${e.message}" }
        }
    }

    /**
     * Aceptar llamada entrante
     */
    fun acceptCall(callId: String? = null, recordCall: Boolean = true) {

        val accountInfo = sipCoreManager.currentAccountInfo ?: run {
            log.e(tag = TAG) { "❌ No current account" }
            return
        }

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

        if (remoteSdp.isBlank()) {
            log.e(tag = TAG) { "❌ FATAL: remoteSdp is blank!" }
            CallStateManager.callError(
                targetCallData.callId,
                errorReason = CallErrorReason.MEDIA_ERROR
            )
            sipCoreManager.sipCallbacks?.onCallFailed("Remote SDP not available")
            return
        }

        log.d(tag = TAG) { "Accepting call: ${targetCallData.callId}" }
        audioManager.stopAllRingtones()

        CoroutineScope(Dispatchers.IO).launch {
            try {

                // Flujo normal sin traducción
                log.d(tag = TAG) { "🎤 Preparing audio..." }
                audioManager.prepareAudioForIncomingCall()
                log.d(tag = TAG) { "📞 Creating answer with remote SDP" }
                val answerSdp = audioManager.createAnswer(remoteSdp)

                log.d(tag = TAG) { "✅ Answer created: ${answerSdp.length} chars" }
                targetCallData.localSdp = answerSdp

                if (recordCall) {
                    webRtcManager.startCallRecording(targetCallData.callId)
                    log.d(TAG) { "🎙️ Recording started for incoming call ${targetCallData.callId}" }
                }

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

        // ✅ CORRECCIÓN: Usar misma lógica que acceptCall para obtener CallData
        val targetCallData = when {
            callId != null -> {
                // Primero buscar en AccountInfo
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

        // ✅ CORRECCIÓN: Asegurar toTag
        if (targetCallData.toTag.isNullOrEmpty()) {
            targetCallData.toTag = generateId()
            log.d(tag = TAG) { "Generated toTag: ${targetCallData.toTag}" }
        }

        // ✅ CORRECCIÓN: Detener todos los ringtones, no solo uno
        log.d(tag = TAG) { "Stopping all ringtones..." }
        audioManager.stopAllRingtones()

        // Enviar respuesta de rechazo
        log.d(tag = TAG) { "Sending 603 Decline response..." }
        messageHandler.sendDeclineResponse(accountInfo, targetCallData)

        // Registrar en historial
        val endTime = Clock.System.now().toEpochMilliseconds()
        sipCoreManager.callHistoryManager.addCallLog(targetCallData, CallTypes.DECLINED, endTime)
        log.d(tag = TAG) { "Call log recorded as DECLINED" }

        // Actualizar estado
        CallStateManager.callEnded(targetCallData.callId, sipReason = "Declined")
        sipCoreManager.notifyCallStateChanged(CallState.ENDED)

        // Limpiar recursos
        if (accountInfo.currentCallData.value?.callId == targetCallData.callId) {
            CoroutineScope(Dispatchers.IO).launch {
                accountInfo.resetCallState()
                log.d(tag = TAG) { "AccountInfo call state reset" }

            }
        }

        log.d(tag = TAG) { "✅ Call declined successfully: ${targetCallData.callId}" }
    }

    /**
     * Silenciar/Desactivar silencio
     */
    fun toggleMute(): Boolean {
        return audioManager.toggleMute()
    }

    /**
     * Verificar si está silenciado
     */
    fun isMuted(): Boolean = audioManager.isMuted()

    /**
     * Poner llamada en espera
     */
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

    /**
     * Reanudar llamada en espera
     */
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

    /**
     * Enviar DTMF
     */
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

    /**
     * Enviar secuencia DTMF
     */
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

    /**
     * Obtener dispositivos de audio
     */
    fun getAudioDevices() = audioManager.getAudioDevices()

    /**
     * Cambiar dispositivo de audio
     */
    fun changeAudioDevice(device: AudioDevice) = audioManager.changeAudioDevice(device)

    /**
     * Verificar si hay llamada activa
     */
    fun hasActiveCall(): Boolean = CallStateManager.getCurrentState().isActive()

    /**
     * Verificar si hay llamada conectada
     */
    fun hasConnectedCall(): Boolean = CallStateManager.getCurrentState().isConnected()

    /**
     * Manejar conexión WebRTC establecida
     */
    fun handleWebRtcConnected() {
        sipCoreManager.callStartTimeMillis = Clock.System.now().toEpochMilliseconds()

        sipCoreManager.currentAccountInfo?.currentCallData?.let { callData ->
            CallStateManager.streamsRunning(callData.value?.callId ?: generateId())
        }

        sipCoreManager.notifyCallStateChanged(CallState.STREAMS_RUNNING)
    }

    /**
     * Manejar cierre de WebRTC
     */
    fun handleWebRtcClosed() {
        val callData = sipCoreManager.currentAccountInfo?.currentCallData?.value

        if (callData == null) {
            log.w(tag = TAG) { "WebRTC closed but no active call data found" }
            CallStateManager.callEnded(generateId())
            sipCoreManager.notifyCallStateChanged(CallState.ENDED)
            return
        }

        // Marca la llamada como terminada
        CallStateManager.callEnded(callData.callId)
        sipCoreManager.notifyCallStateChanged(CallState.ENDED)

        // Guarda registro de la llamada si aún existe el CallData
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

//
//
//import com.eddyslarez.kmpsiprtc.platform.calculateMD5
//import com.eddyslarez.kmpsiprtc.platform.log
//
//import com.eddyslarez.kmpsiprtc.utils.generateId
//import com.eddyslarez.kmpsiprtc.utils.generateSipTag
//
//import kotlinx.coroutines.IO
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.sync.withLock
//import kotlinx.coroutines.withContext
//
//
//class CallManager(
//    private val sipCoreManager: SipCoreManager,
//    private val audioManager: SipAudioManager,
//    private val webRtcManager: WebRtcManager,
//    private val messageHandler: SipMessageHandler
//) {
//    private val callHoldManager = CallHoldManager(webRtcManager)
//    private val dtmfQueue = mutableListOf<DtmfRequest>()
//    private var isDtmfProcessing = false
//    private val dtmfMutex = Mutex()
//
//    companion object {
//        private const val TAG = "CallManager"
//    }
//    // ✅ NUEVAS PROPIEDADES para traducción
//
//
//
//
//    /**
//     * Realizar llamada saliente
//     */
//    /**
//     * Realizar llamada saliente
//     */
//    fun makeCall(
//        phoneNumber: String,
//        accountInfo: AccountInfo,
//        enableTranslation: Boolean = true,
//        sourceLanguage: String = "es",
//        targetLanguage: String = "en"
//    ) {
//        if (!accountInfo.isRegistered.value) {
//            log.d(tag = TAG) { "Error: Not registered with SIP server" }
//            sipCoreManager.sipCallbacks?.onCallFailed("Not registered with SIP server")
//            return
//        }
//
//        log.d(tag = TAG) { "Making call to $phoneNumber (Translation: $enableTranslation)" }
//        audioManager.stopAllRingtones()
//
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//
//                // Flujo normal sin traducción
//                audioManager.prepareAudioForOutgoingCall()
//                val sdp = audioManager.createOffer()
//
//                val callId = accountInfo.generateCallId()
//                val md5Hash = calculateMD5(callId)
//                val callData = CallData(
//                    callId = callId,
//                    to = phoneNumber,
//                    from = accountInfo.username,
//                    direction = CallDirections.OUTGOING,
//                    inviteFromTag = generateSipTag(),
//                    localSdp = sdp,
//                    md5Hash = md5Hash
//                )
//
//                accountInfo.currentCallData.value = callData
//                CallStateManager.startOutgoingCall(callId, phoneNumber)
//                sipCoreManager.notifyCallStateChanged(CallState.OUTGOING_INIT)
//
//                audioManager.playOutgoingRingtone()
//                messageHandler.sendInvite(accountInfo, callData)
//
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error creating call: ${e.stackTraceToString()}" }
//                sipCoreManager.sipCallbacks?.onCallFailed("Error creating call: ${e.message}")
//
//                accountInfo.currentCallData.value?.let { callData ->
//                    CallStateManager.callError(
//                        callData.callId,
//                        errorReason = CallErrorReason.NETWORK_ERROR
//                    )
//                }
//                audioManager.stopOutgoingRingtone()
//            }
//        }
//    }
//
//
//    /**
//     * Finalizar llamada
//     */
//    fun endCall(callId: String? = null) {
//        val accountInfo = sipCoreManager.currentAccountInfo ?: run {
//            log.e(tag = TAG) { "No account" }
//            return
//        }
//
//        // Obtener CallData de AccountInfo (fuente primaria)
//        val targetCallData = accountInfo.currentCallData.value ?: run {
//            log.e(tag = TAG) { "No call data" }
//            return
//        }
//
//        val callState = CallStateManager.getCurrentState()
//
//        if (!callState.isActive()) {
//            log.w(tag = TAG) { "No active call to end" }
//            return
//        }
//
//        val endTime = Clock.System.now().toEpochMilliseconds()
//        val currentState = callState.state
//
//        log.d(tag = TAG) { "Ending call: ${targetCallData.callId}" }
//
//        // Detener ringtones
//        audioManager.stopAllRingtones()
//
//        CallStateManager.startEnding(targetCallData.callId)
//        clearDtmfQueue()
//
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//                when (currentState) {
//                    CallState.CONNECTED, CallState.STREAMS_RUNNING, CallState.PAUSED -> {
//                        log.d(tag = TAG) { "Sending BYE" }
//                        messageHandler.sendBye(accountInfo, targetCallData)
//                        sipCoreManager.callHistoryManager.addCallLog(
//                            targetCallData,
//                            CallTypes.SUCCESS,
//                            endTime
//                        )
//                    }
//
//                    CallState.OUTGOING_INIT, CallState.OUTGOING_PROGRESS, CallState.OUTGOING_RINGING -> {
//                        log.d(tag = TAG) { "Sending CANCEL" }
//                        messageHandler.sendCancel(accountInfo, targetCallData)
//                        sipCoreManager.callHistoryManager.addCallLog(
//                            targetCallData,
//                            CallTypes.ABORTED,
//                            endTime
//                        )
//                    }
//
//                    CallState.INCOMING_RECEIVED -> {
//                        log.d(tag = TAG) { "Sending DECLINE" }
//                        messageHandler.sendDeclineResponse(accountInfo, targetCallData)
//                        sipCoreManager.callHistoryManager.addCallLog(
//                            targetCallData,
//                            CallTypes.DECLINED,
//                            endTime
//                        )
//                    }
//
//                    else -> {
//                        messageHandler.sendBye(accountInfo, targetCallData)
//                    }
//                }
//
//                delay(500)
//                audioManager.dispose()
//
//                delay(500)
//                CallStateManager.callEnded(targetCallData.callId)
//                sipCoreManager.notifyCallStateChanged(CallState.ENDED)
//
//                accountInfo.resetCallState()
//                sipCoreManager.handleCallTermination()
//
//                log.d(tag = TAG) { "Call cleanup completed" }
//
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error during cleanup: ${e.message}" }
//                audioManager.stopAllRingtones()
//                accountInfo.resetCallState()
//            }
//        }
//
//    }
//
//
//    /**
//     * Aceptar llamada entrante
//     */
//    fun acceptCall(
//        callId: String? = null,
//        enableTranslation: Boolean = false,
//        sourceLanguage: String = "es",
//        targetLanguage: String = "en"
//    ) {
//        val accountInfo = sipCoreManager.currentAccountInfo ?: run {
//            log.e(tag = TAG) { "❌ No current account" }
//            return
//        }
//
//        log.d(tag = TAG) { "🟢 Accept call (Translation: $enableTranslation)" }
//
//        val targetCallData = accountInfo.currentCallData.value ?: run {
//            log.e(tag = TAG) { "❌ No call data in AccountInfo" }
//            return
//        }
//
//        if (targetCallData.direction != CallDirections.INCOMING) {
//            log.w(tag = TAG) { "❌ Cannot accept - not incoming" }
//            return
//        }
//
//        val callState = CallStateManager.getCurrentState()
//        if (callState.state != CallState.INCOMING_RECEIVED) {
//            log.w(tag = TAG) { "❌ Cannot accept - invalid state: ${callState.state}" }
//            return
//        }
//
//        val remoteSdp = targetCallData.remoteSdp
//
//        if (remoteSdp.isBlank()) {
//            log.e(tag = TAG) { "❌ FATAL: remoteSdp is blank!" }
//            CallStateManager.callError(
//                targetCallData.callId,
//                errorReason = CallErrorReason.MEDIA_ERROR
//            )
//            sipCoreManager.sipCallbacks?.onCallFailed("Remote SDP not available")
//            return
//        }
//
//        log.d(tag = TAG) { "Accepting call: ${targetCallData.callId}" }
//        audioManager.stopAllRingtones()
//
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//
//                // Flujo normal sin traducción
//                log.d(tag = TAG) { "🎤 Preparing audio..." }
//                audioManager.prepareAudioForIncomingCall()
//
//                log.d(tag = TAG) { "📞 Creating answer with remote SDP" }
//                val answerSdp = audioManager.createAnswer(remoteSdp)
//
//                log.d(tag = TAG) { "✅ Answer created: ${answerSdp.length} chars" }
//                targetCallData.localSdp = answerSdp
//
//                log.d(tag = TAG) { "📤 Sending 200 OK..." }
//                messageHandler.sendInviteOkResponse(accountInfo, targetCallData)
//
//                CallStateManager.callConnected(targetCallData.callId, 200)
//                sipCoreManager.notifyCallStateChanged(CallState.CONNECTED)
//
//                delay(500)
//
//                log.d(tag = TAG) { "🔊 Enabling audio..." }
//                audioManager.setAudioEnabled(true)
//
//                log.d(tag = TAG) { "✅ Call accepted successfully" }
//
//
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "💥 Error accepting call: ${e.message}" }
//                log.e(tag = TAG) { e.stackTraceToString() }
//
//                CallStateManager.callError(
//                    targetCallData.callId,
//                    errorReason = CallErrorReason.MEDIA_ERROR
//                )
//
//                sipCoreManager.sipCallbacks?.onCallFailed("Failed to accept: ${e.message}")
//
//                try {
//                    declineCall(callId)
//                } catch (declineError: Exception) {
//                    log.e(tag = TAG) { "Error declining after failure: ${declineError.message}" }
//                }
//            }
//        }
//    }
//
//
//    /**
//     * Rechazar llamada entrante
//     */
//    fun declineCall(callId: String? = null) {
//        val accountInfo = sipCoreManager.currentAccountInfo ?: run {
//            log.e(tag = TAG) { "No current account available for declining call" }
//            return
//        }
//
//        // ✅ CORRECCIÓN: Usar misma lógica que acceptCall para obtener CallData
//        val targetCallData = when {
//            callId != null -> {
//                // Primero buscar en AccountInfo
//                val accountCallData = accountInfo.currentCallData.value
//                if (accountCallData?.callId == callId) {
//                    log.d(tag = TAG) { "✅ Found call in AccountInfo (preferred)" }
//                    accountCallData
//                } else {
//                    log.d(tag = TAG) { "Looking in MultiCallManager" }
//                    MultiCallManager.getCall(callId)
//                }
//            }
//
//            else -> {
//                log.d(tag = TAG) { "Using current call from AccountInfo" }
//                accountInfo.currentCallData.value
//            }
//        } ?: run {
//            log.e(tag = TAG) { "No call data available for declining" }
//            return
//        }
//
//        if (targetCallData.direction != CallDirections.INCOMING) {
//            log.w(tag = TAG) { "Cannot decline call - not incoming. Direction: ${targetCallData.direction}" }
//            return
//        }
//
//        log.d(tag = TAG) { "Declining call: ${targetCallData.callId}" }
//
//        // ✅ CORRECCIÓN: Asegurar toTag
//        if (targetCallData.toTag.isNullOrEmpty()) {
//            targetCallData.toTag = generateId()
//            log.d(tag = TAG) { "Generated toTag: ${targetCallData.toTag}" }
//        }
//
//        // ✅ CORRECCIÓN: Detener todos los ringtones, no solo uno
//        log.d(tag = TAG) { "Stopping all ringtones..." }
//        audioManager.stopAllRingtones()
//
//        // Enviar respuesta de rechazo
//        log.d(tag = TAG) { "Sending 603 Decline response..." }
//        messageHandler.sendDeclineResponse(accountInfo, targetCallData)
//
//        // Registrar en historial
//        val endTime = Clock.System.now().toEpochMilliseconds()
//        sipCoreManager.callHistoryManager.addCallLog(targetCallData, CallTypes.DECLINED, endTime)
//        log.d(tag = TAG) { "Call log recorded as DECLINED" }
//
//        // Actualizar estado
//        CallStateManager.callEnded(targetCallData.callId, sipReason = "Declined")
//        sipCoreManager.notifyCallStateChanged(CallState.ENDED)
//
//        // Limpiar recursos
//        if (accountInfo.currentCallData.value?.callId == targetCallData.callId) {
//            CoroutineScope(Dispatchers.IO).launch {
//                accountInfo.resetCallState()
//                log.d(tag = TAG) { "AccountInfo call state reset" }
//
//            }
//        }
//
//        log.d(tag = TAG) { "✅ Call declined successfully: ${targetCallData.callId}" }
//    }
//
//    /**
//     * Silenciar/Desactivar silencio
//     */
//    fun toggleMute(): Boolean {
//        return audioManager.toggleMute()
//    }
//
//    /**
//     * Verificar si está silenciado
//     */
//    fun isMuted(): Boolean = audioManager.isMuted()
//
//    /**
//     * Poner llamada en espera
//     */
//    fun holdCall(callId: String? = null) {
//        val accountInfo = sipCoreManager.currentAccountInfo ?: return
//        val targetCallData = if (callId != null) {
//            MultiCallManager.getCall(callId)
//        } else {
//            accountInfo.currentCallData.value
//        } ?: return
//
//        val currentState = CallStateManager.getCurrentState()
//        if (currentState.state != CallState.STREAMS_RUNNING &&
//            currentState.state != CallState.CONNECTED
//        ) {
//            log.w(tag = TAG) { "Cannot hold call in current state: ${currentState.state}" }
//            return
//        }
//
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//                CallStateManager.startHold(targetCallData.callId)
//                sipCoreManager.notifyCallStateChanged(CallState.PAUSING)
//
//                callHoldManager.holdCall()?.let { holdSdp ->
//                    targetCallData.localSdp = holdSdp
//                    targetCallData.isOnHold = true
//                    messageHandler.sendReInvite(accountInfo, targetCallData, holdSdp)
//
//                    delay(1000)
//                    CallStateManager.callOnHold(targetCallData.callId)
//                    sipCoreManager.notifyCallStateChanged(CallState.PAUSED)
//                }
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error holding call: ${e.message}" }
//            }
//        }
//    }
//
//    /**
//     * Reanudar llamada en espera
//     */
//    fun resumeCall(callId: String? = null) {
//        val accountInfo = sipCoreManager.currentAccountInfo ?: return
//        val targetCallData = if (callId != null) {
//            MultiCallManager.getCall(callId)
//        } else {
//            accountInfo.currentCallData.value
//        } ?: return
//
//        val currentState = CallStateManager.getCurrentState()
//        if (currentState.state != CallState.PAUSED) {
//            log.w(tag = TAG) { "Cannot resume call in current state: ${currentState.state}" }
//            return
//        }
//
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//                CallStateManager.startResume(targetCallData.callId)
//                sipCoreManager.notifyCallStateChanged(CallState.RESUMING)
//
//                callHoldManager.resumeCall()?.let { resumeSdp ->
//                    targetCallData.localSdp = resumeSdp
//                    targetCallData.isOnHold = false
//                    messageHandler.sendReInvite(accountInfo, targetCallData, resumeSdp)
//
//                    delay(1000)
//                    CallStateManager.callResumed(targetCallData.callId)
//                    sipCoreManager.notifyCallStateChanged(CallState.STREAMS_RUNNING)
//                }
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error resuming call: ${e.message}" }
//            }
//        }
//    }
//
//    /**
//     * Enviar DTMF
//     */
//    fun sendDtmf(digit: Char, duration: Int = 160): Boolean {
//        val validDigits = setOf(
//            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
//            '*', '#', 'A', 'B', 'C', 'D', 'a', 'b', 'c', 'd'
//        )
//
//        if (!validDigits.contains(digit)) {
//            return false
//        }
//
//        val request = DtmfRequest(digit, duration)
//        CoroutineScope(Dispatchers.IO).launch {
//            dtmfMutex.withLock {
//                dtmfQueue.add(request)
//            }
//            processDtmfQueue()
//        }
//
//        return true
//    }
//
//    /**
//     * Enviar secuencia DTMF
//     */
//    fun sendDtmfSequence(digits: String, duration: Int = 160): Boolean {
//        if (digits.isEmpty()) return false
//
//        digits.forEach { digit ->
//            sendDtmf(digit, duration)
//        }
//
//        return true
//    }
//
//    private suspend fun processDtmfQueue() = withContext(Dispatchers.IO) {
//        dtmfMutex.withLock {
//            if (isDtmfProcessing || dtmfQueue.isEmpty()) {
//                return@withLock
//            }
//            isDtmfProcessing = true
//        }
//
//        try {
//            while (true) {
//                val request: DtmfRequest? = dtmfMutex.withLock {
//                    if (dtmfQueue.isNotEmpty()) {
//                        dtmfQueue.removeAt(0)
//                    } else {
//                        null
//                    }
//                }
//
//                if (request == null) break
//
//                val success = sendSingleDtmf(request.digit, request.duration)
//                if (success) {
//                    delay(150)
//                }
//            }
//        } finally {
//            dtmfMutex.withLock {
//                isDtmfProcessing = false
//            }
//        }
//    }
//
//    private suspend fun sendSingleDtmf(digit: Char, duration: Int): Boolean {
//        val currentAccount = sipCoreManager.currentAccountInfo
//        val callData = currentAccount?.currentCallData?.value
//
//        if (currentAccount == null || callData == null ||
//            !CallStateManager.getCurrentState().isConnected()
//        ) {
//            return false
//        }
//
//        return try {
//            webRtcManager.sendDtmfTones(
//                tones = digit.toString().uppercase(),
//                duration = duration,
//                gap = 100
//            )
//        } catch (e: Exception) {
//            false
//        }
//    }
//
//    internal fun clearDtmfQueue() {
//        CoroutineScope(Dispatchers.IO).launch {
//            dtmfMutex.withLock {
//                dtmfQueue.clear()
//                isDtmfProcessing = false
//            }
//        }
//    }
//
//    /**
//     * Obtener dispositivos de audio
//     */
//    fun getAudioDevices() = audioManager.getAudioDevices()
//
//    /**
//     * Cambiar dispositivo de audio
//     */
//    fun changeAudioDevice(device: AudioDevice) = audioManager.changeAudioDevice(device)
//
//    /**
//     * Verificar si hay llamada activa
//     */
//    fun hasActiveCall(): Boolean = CallStateManager.getCurrentState().isActive()
//
//    /**
//     * Verificar si hay llamada conectada
//     */
//    fun hasConnectedCall(): Boolean = CallStateManager.getCurrentState().isConnected()
//
//    /**
//     * Manejar conexión WebRTC establecida
//     */
//    fun handleWebRtcConnected() {
//        sipCoreManager.callStartTimeMillis = Clock.System.now().toEpochMilliseconds()
//
//        sipCoreManager.currentAccountInfo?.currentCallData?.let { callData ->
//            CallStateManager.streamsRunning(callData.value?.callId ?: generateId())
//        }
//
//        sipCoreManager.notifyCallStateChanged(CallState.STREAMS_RUNNING)
//    }
//
//    /**
//     * Manejar cierre de WebRTC
//     */
//    fun handleWebRtcClosed() {
//        val callData = sipCoreManager.currentAccountInfo?.currentCallData?.value
//
//        if (callData == null) {
//            log.w(tag = TAG) { "WebRTC closed but no active call data found" }
//            CallStateManager.callEnded(generateId())
//            sipCoreManager.notifyCallStateChanged(CallState.ENDED)
//            return
//        }
//
//        // Marca la llamada como terminada
//        CallStateManager.callEnded(callData.callId)
//        sipCoreManager.notifyCallStateChanged(CallState.ENDED)
//
//        // Guarda registro de la llamada si aún existe el CallData
//        val endTime = Clock.System.now().toEpochMilliseconds()
//        val finalState = CallStateManager.getCurrentState().state
//        val callType = determineCallType(callData, finalState)
//
//        try {
//            sipCoreManager.callHistoryManager.addCallLog(callData, callType, endTime)
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error adding call log after WebRTC closed: ${e.message}" }
//        }
//    }
//
//
//    private fun determineCallType(callData: CallData, finalState: CallState): CallTypes {
//        return when (finalState) {
//            CallState.CONNECTED, CallState.STREAMS_RUNNING, CallState.ENDED -> CallTypes.SUCCESS
//            CallState.ERROR -> if (callData.direction == CallDirections.INCOMING) {
//                CallTypes.MISSED
//            } else {
//                CallTypes.ABORTED
//            }
//
//            else -> if (callData.direction == CallDirections.INCOMING) {
//                CallTypes.MISSED
//            } else {
//                CallTypes.ABORTED
//            }
//        }
//    }
//}
