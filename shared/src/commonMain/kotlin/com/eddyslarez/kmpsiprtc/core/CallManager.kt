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
import com.eddyslarez.kmpsiprtc.services.matrix.MatrixManager
import com.eddyslarez.kmpsiprtc.services.sip.SipMessageHandler
import com.eddyslarez.kmpsiprtc.services.sip.SipMessageParser
import com.eddyslarez.kmpsiprtc.services.unified.CallType
import com.eddyslarez.kmpsiprtc.services.webrtc.WebRtcManager
import com.eddyslarez.kmpsiprtc.utils.generateId
import com.eddyslarez.kmpsiprtc.utils.generateSipTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.ExperimentalTime

class CallManager(
    private val sipCoreManager: SipCoreManager,
    private val audioManager: SipAudioManager,
    private val webRtcManager: WebRtcManager,
    private val messageHandler: SipMessageHandler
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val callHoldManager = CallHoldManager(webRtcManager)
    private val dtmfQueue = mutableListOf<DtmfRequest>()
    private var isDtmfProcessing = false
    private val dtmfMutex = Mutex()
    private var matrixManager: MatrixManager? = null

    fun setMatrixManager(manager: MatrixManager) {
        this.matrixManager = manager
    }

    companion object {
        private const val TAG = "CallManager"
    }

    /**
     * Registrar llamada en historial basandose en el estado actual
     */
    @OptIn(ExperimentalTime::class)
    private fun registerCallInHistory(callData: CallData, finalState: CallState? = null) {
        val endTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val state = finalState ?: CallStateManager.getCurrentState().state

        val callType = determineCallType(callData, state)

        log.d(TAG) {
            "Registering call in history: ${callData.callId}, type: $callType, state: $state"
        }

        sipCoreManager.callHistoryManager.addCallLog(callData, callType, endTime)
    }

    /**
     * Metodo publico para registrar llamadas desde otros lugares (como SipMessageHandler)
     */
    fun registerMissedCall(callData: CallData) {
        if (callData.direction == CallDirections.INCOMING) {
            log.d(TAG) { "Registering missed call: ${callData.callId}" }
            registerCallInHistory(callData, CallState.INCOMING_RECEIVED)
        } else {
            log.w(TAG) { "Cannot register missed call for non-incoming direction: ${callData.direction}" }
        }
    }

    /**
     * Determinar tipo de llamada con logica precisa
     */
    private fun determineCallType(callData: CallData, finalState: CallState): CallTypes {
        return when {
            // Llamadas exitosas: conectadas con streams funcionando
            finalState == CallState.STREAMS_RUNNING ||
                    finalState == CallState.CONNECTED -> CallTypes.SUCCESS

            // Llamadas ENTRANTES no contestadas = MISSED
            callData.direction == CallDirections.INCOMING && (
                    finalState == CallState.INCOMING_RECEIVED ||
                            finalState == CallState.ERROR ||
                            finalState == CallState.ENDING ||
                            finalState == CallState.ENDED
                    ) -> CallTypes.MISSED

            // Llamadas SALIENTES no completadas = ABORTED
            callData.direction == CallDirections.OUTGOING && (
                    finalState == CallState.OUTGOING_INIT ||
                            finalState == CallState.OUTGOING_PROGRESS ||
                            finalState == CallState.OUTGOING_RINGING ||
                            finalState == CallState.ERROR ||
                            finalState == CallState.ENDING ||
                            finalState == CallState.ENDED
                    ) -> CallTypes.ABORTED

            else -> {
                // Fallback por direccion
                if (callData.direction == CallDirections.INCOMING) {
                    CallTypes.MISSED
                } else {
                    CallTypes.ABORTED
                }
            }
        }
    }

    fun makeCall(
        phoneNumber: String,
        accountInfo: AccountInfo,
        enableTranslation: Boolean = false,
        recordCall: Boolean = false,
    ) {
        if (!accountInfo.isRegistered.value) {
            log.d(tag = TAG) { "Error: Not registered with SIP server" }
            sipCoreManager.sipCallbacks?.onCallFailed("Not registered with SIP server")
            return
        }

        log.d(tag = TAG) { "Making call to $phoneNumber" }
        audioManager.stopAllRingtones()

        scope.launch {
            try {
                // Asegurar que WebRTC esta inicializado ANTES de continuar
                if (!webRtcManager.isInitialized()) {
                    log.d(TAG) { "WebRTC not initialized, initializing now..." }
                    webRtcManager.initialize()

                    // Esperar confirmacion de inicializacion
                    var attempts = 0
                    while (!webRtcManager.isInitialized() && attempts < 20) {
                        delay(250)
                        attempts++
                    }

                    if (!webRtcManager.isInitialized()) {
                        throw Exception("WebRTC initialization timeout")
                    }

                    log.d(TAG) { "WebRTC initialized successfully" }
                }

                // Preparar audio DESPUES de confirmar inicializacion
                audioManager.prepareAudioForOutgoingCall()

                // Pequeno delay adicional para estabilidad en iOS
                delay(500)

                log.d(TAG) { "Creating SDP offer..." }
                val sdp = audioManager.createOffer()
                log.d(TAG) { "SDP offer created (${sdp.length} chars)" }

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
                CallStateManager.startOutgoingCall(callId, phoneNumber, callData)
                sipCoreManager.notifyCallStateChanged(CallState.OUTGOING_INIT)

                if (recordCall) {
                    webRtcManager.startCallRecording(callId)
                    log.d(TAG) { "Recording started for outgoing call $callId" }
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
                    registerCallInHistory(callData, CallState.ERROR)
                }
                audioManager.stopOutgoingRingtone()
            }
        }
    }

    /**
     * Finaliza una llamada activa - despacha por callType
     */
    fun endCall(callId: String? = null) {
        val accountInfo = sipCoreManager.currentAccountInfo

        // Resolver callData desde AccountInfo o MultiCallManager
        val targetCallData = when {
            callId != null -> MultiCallManager.getCall(callId) ?: accountInfo?.currentCallData?.value
            else -> accountInfo?.currentCallData?.value ?: MultiCallManager.getAllCalls().firstOrNull()
        }

        if (targetCallData == null) {
            log.e(tag = TAG) { "No call data available for ending" }
            return
        }

        // Despachar segun callType
        when (targetCallData.callType) {
            CallType.MATRIX_INTERNAL -> endMatrixCall(targetCallData)
            CallType.SIP_EXTERNAL -> endSipCall(targetCallData, accountInfo)
            else -> {}
        }
    }

    /**
     * Finaliza una llamada Matrix
     */
    private fun endMatrixCall(callData: CallData) {
        log.d(tag = TAG) { "Ending Matrix call: ${callData.callId}" }

        audioManager.stopAllRingtones()
        CallStateManager.startEnding(callData.callId)

        scope.launch {
            try {
                matrixManager?.hangupCall(callData.callId)
                delay(500)
                CallStateManager.callEnded(callData.callId)
                sipCoreManager.notifyCallStateChanged(CallState.ENDED)
                sipCoreManager.handleCallTermination()
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error ending Matrix call: ${e.message}" }
                CallStateManager.callEnded(callData.callId, sipReason = "Error: ${e.message}")
            }
        }
    }

    /**
     * Finaliza una llamada SIP (logica original)
     */
    private fun endSipCall(callData: CallData, accountInfo: AccountInfo?) {
        if (accountInfo == null) {
            log.e(tag = TAG) { "No account for ending SIP call" }
            return
        }

        val callState = CallStateManager.getCurrentState()

        if (!callState.isActive()) {
            log.w(tag = TAG) { "No active call to end" }
            return
        }

        val currentState = callState.state
        log.d(tag = TAG) { "Ending SIP call: ${callData.callId}, state: $currentState" }

        audioManager.stopAllRingtones()
        CallStateManager.startEnding(callData.callId)
        clearDtmfQueue()

        scope.launch {
            try {
                when (currentState) {
                    CallState.CONNECTED, CallState.STREAMS_RUNNING, CallState.PAUSED -> {
                        log.d(tag = TAG) { "Sending BYE" }
                        messageHandler.sendBye(accountInfo, callData)
                        // Registrar como exitosa
                        registerCallInHistory(callData, CallState.STREAMS_RUNNING)
                    }

                    CallState.OUTGOING_INIT, CallState.OUTGOING_PROGRESS, CallState.OUTGOING_RINGING -> {
                        log.d(tag = TAG) { "Sending CANCEL" }
                        messageHandler.sendCancel(accountInfo, callData)
                        // Registrar como abortada
                        registerCallInHistory(callData, currentState)
                    }

                    CallState.INCOMING_RECEIVED -> {
                        log.d(tag = TAG) { "Sending DECLINE" }
                        messageHandler.sendDeclineResponse(accountInfo, callData)
                        // Registrar como perdida (no contestada)
                        registerCallInHistory(callData, CallState.INCOMING_RECEIVED)
                    }

                    else -> {
                        messageHandler.sendBye(accountInfo, callData)
                        // Registrar con estado actual
                        registerCallInHistory(callData, currentState)
                    }
                }

                // Detener grabacion si estaba activa
                if (webRtcManager.isRecordingCall()) {
                    log.d(TAG) { "Stopping recording for call ${callData.callId}" }
                    val result = webRtcManager.stopCallRecording()
                    result?.mixedFile?.let { file ->
                        log.d(TAG) { "Saved mixed recording: ${file}" }
                        saveRecordingMetadata(callData.callId, file)
                    }
                }

                delay(500)
                audioManager.dispose()

                delay(500)
                CallStateManager.callEnded(callData.callId)
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
            log.d(TAG) { "Recording saved: $callId -> ${file}" }
        } catch (e: Exception) {
            log.e(TAG) { "Error saving metadata: ${e.message}" }
        }
    }

    /**
     * Acepta una llamada entrante - despacha por callType
     */
    fun acceptCall(callId: String? = null, recordCall: Boolean = false) {

        // Resolver callData desde AccountInfo o MultiCallManager
        val accountInfo = sipCoreManager.currentAccountInfo
        val targetCallData = when {
            callId != null -> MultiCallManager.getCall(callId) ?: accountInfo?.currentCallData?.value
            else -> accountInfo?.currentCallData?.value ?: MultiCallManager.getAllCalls().firstOrNull { it.direction == CallDirections.INCOMING }
        }

        if (targetCallData == null) {
            log.e(tag = TAG) { "No call data available for accepting" }
            return
        }

        // Despachar segun callType
        when (targetCallData.callType) {
            CallType.MATRIX_INTERNAL -> acceptMatrixCall(targetCallData)
            CallType.SIP_EXTERNAL -> acceptSipCall(targetCallData, accountInfo, recordCall)
            else -> {}
        }
    }

    /**
     * Acepta una llamada Matrix entrante
     */
    private fun acceptMatrixCall(callData: CallData) {
        log.d(tag = TAG) { "Accepting Matrix call: ${callData.callId}" }

        audioManager.stopAllRingtones()

        scope.launch {
            try {
                val result = matrixManager?.answerCall(callData.callId)
                if (result?.isSuccess == true) {
                    log.d(tag = TAG) { "Matrix call accepted successfully: ${callData.callId}" }
                } else {
                    log.e(tag = TAG) { "Error accepting Matrix call: ${result?.exceptionOrNull()?.message}" }
                    CallStateManager.callError(callData.callId, errorReason = CallErrorReason.MEDIA_ERROR)
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error accepting Matrix call: ${e.message}" }
                CallStateManager.callError(callData.callId, errorReason = CallErrorReason.MEDIA_ERROR)
            }
        }
    }

    /**
     * Acepta una llamada SIP entrante (logica original)
     */
    private fun acceptSipCall(callData: CallData, accountInfo: AccountInfo?, recordCall: Boolean) {
        if (accountInfo == null) {
            log.e(tag = TAG) { "No current account for SIP accept" }
            return
        }

        if (callData.direction != CallDirections.INCOMING) {
            log.w(tag = TAG) { "Cannot accept - not incoming" }
            return
        }

        val callState = CallStateManager.getCurrentState()

        if (callState.state != CallState.INCOMING_RECEIVED) {
            log.w(tag = TAG) { "Cannot accept - invalid state: ${callState.state}" }
            return
        }

        val remoteSdp = callData.remoteSdp

        if (remoteSdp.isBlank()) {
            log.e(tag = TAG) { "FATAL: remoteSdp is blank!" }

            CallStateManager.callError(
                callData.callId,
                errorReason = CallErrorReason.MEDIA_ERROR
            )
            registerCallInHistory(callData, CallState.ERROR)
            sipCoreManager.sipCallbacks?.onCallFailed("Remote SDP not available")
            return
        }

        log.d(tag = TAG) { "Accepting SIP call: ${callData.callId}" }

        audioManager.stopAllRingtones()

        scope.launch {
            try {
                // PASO 1: Inicializar WebRTC PRIMERO
                if (!webRtcManager.isInitialized()) {
                    webRtcManager.initialize()

                    // Esperar inicializacion con timeout
                    var attempts = 0
                    while (!webRtcManager.isInitialized() && attempts < 40) {
                        delay(250)
                        attempts++
                    }

                    if (!webRtcManager.isInitialized()) {
                        throw Exception("WebRTC initialization timeout after ${attempts * 250}ms")
                    }
                }

                // PASO 2: Preparar Audio Session (iOS CallKit)
                audioManager.prepareAudioForIncomingCall()

                // Delay para que audio session se estabilice
                delay(500)

                // PASO 3: Crear Answer SDP
                val answerSdp = audioManager.createAnswer(remoteSdp)
                callData.localSdp = answerSdp

                // PASO 4: Iniciar grabacion ANTES de conectar audio
                if (recordCall) {
                    webRtcManager.startCallRecording(callData.callId)
                    delay(300) // Dar tiempo al recorder para iniciar
                }

                // PASO 5: Enviar 200 OK
                messageHandler.sendInviteOkResponse(accountInfo, callData)

                // PASO 6: Actualizar estado a CONNECTED
                CallStateManager.callConnected(callData.callId, 200)
                sipCoreManager.notifyCallStateChanged(CallState.CONNECTED)

                // PASO 7: Esperar ACK antes de activar audio
                delay(1000)

                // PASO 8: Habilitar audio DESPUES de todo lo demas
                audioManager.setAudioEnabled(true)

                // PASO 9: Verificar que el audio esta funcionando
                delay(500)

                if (audioManager.isMuted()) {
                    audioManager.toggleMute()
                }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error accepting SIP call: ${e.message}" }
                log.e(tag = TAG) { e.stackTraceToString() }

                CallStateManager.callError(
                    callData.callId,
                    errorReason = CallErrorReason.MEDIA_ERROR
                )

                registerCallInHistory(callData, CallState.ERROR)
                sipCoreManager.sipCallbacks?.onCallFailed("Failed to accept: ${e.message}")

                try {
                    declineCall(callData.callId)
                } catch (declineError: Exception) {
                    log.e(tag = TAG) { "Error declining after failure: ${declineError.message}" }
                }
            }
        }
    }

    /**
     * Rechaza una llamada entrante - despacha por callType
     */
    fun declineCall(callId: String? = null) {
        val accountInfo = sipCoreManager.currentAccountInfo

        // Resolver callData desde AccountInfo o MultiCallManager
        val targetCallData = when {
            callId != null -> {
                val accountCallData = accountInfo?.currentCallData?.value
                if (accountCallData?.callId == callId) {
                    accountCallData
                } else {
                    MultiCallManager.getCall(callId)
                }
            }
            else -> {
                accountInfo?.currentCallData?.value
            }
        }

        if (targetCallData == null) {
            log.e(tag = TAG) { "No call data available for declining" }
            return
        }

        // Despachar segun callType
        when (targetCallData.callType) {
            CallType.MATRIX_INTERNAL -> declineMatrixCall(targetCallData)
            CallType.SIP_EXTERNAL -> declineSipCall(targetCallData, accountInfo)
            else -> {}
        }
    }

    /**
     * Rechaza una llamada Matrix entrante
     */
    private fun declineMatrixCall(callData: CallData) {
        log.d(tag = TAG) { "Declining Matrix call: ${callData.callId}" }

        audioManager.stopAllRingtones()

        scope.launch {
            try {
                matrixManager?.hangupCall(callData.callId)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error declining Matrix call: ${e.message}" }
            }
        }

        CallStateManager.callEnded(callData.callId, sipReason = "Declined")
        sipCoreManager.notifyCallStateChanged(CallState.ENDED)

        log.d(tag = TAG) { "Matrix call declined successfully: ${callData.callId}" }
    }

    /**
     * Rechaza una llamada SIP entrante (logica original)
     */
    private fun declineSipCall(callData: CallData, accountInfo: AccountInfo?) {
        if (accountInfo == null) {
            log.e(tag = TAG) { "No current account available for declining SIP call" }
            return
        }

        if (callData.direction != CallDirections.INCOMING) {
            log.w(tag = TAG) { "Cannot decline call - not incoming" }
            return
        }

        log.d(tag = TAG) { "Declining SIP call: ${callData.callId}" }

        if (callData.toTag.isNullOrEmpty()) {
            callData.toTag = generateId()
        }

        audioManager.stopAllRingtones()

        // Enviar respuesta de rechazo
        scope.launch {
            messageHandler.sendDeclineResponse(accountInfo, callData)
        }

        // Registrar como DECLINED (no como MISSED)
        registerCallInHistory(callData, CallState.INCOMING_RECEIVED)

        // Actualizar estado
        CallStateManager.callEnded(callData.callId, sipReason = "Declined")
        sipCoreManager.notifyCallStateChanged(CallState.ENDED)

        // Limpiar recursos
        if (accountInfo.currentCallData.value?.callId == callData.callId) {
            scope.launch {
                accountInfo.resetCallState()
            }
        }

        log.d(tag = TAG) { "SIP call declined successfully: ${callData.callId}" }
    }

    fun toggleMute(): Boolean = audioManager.toggleMute()
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

        scope.launch {
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

        scope.launch {
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

        if (!validDigits.contains(digit)) return false

        val request = DtmfRequest(digit, duration)
        scope.launch {
            dtmfMutex.withLock {
                dtmfQueue.add(request)
            }
            processDtmfQueue()
        }

        return true
    }

    fun sendDtmfSequence(digits: String, duration: Int = 160): Boolean {
        if (digits.isEmpty()) return false
        digits.forEach { digit -> sendDtmf(digit, duration) }
        return true
    }

    private suspend fun processDtmfQueue() = withContext(Dispatchers.IO) {
        dtmfMutex.withLock {
            if (isDtmfProcessing || dtmfQueue.isEmpty()) return@withLock
            isDtmfProcessing = true
        }

        try {
            while (true) {
                val request: DtmfRequest? = dtmfMutex.withLock {
                    if (dtmfQueue.isNotEmpty()) dtmfQueue.removeAt(0) else null
                }

                if (request == null) break

                val success = sendSingleDtmf(request.digit, request.duration)
                if (success) delay(150)
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
        scope.launch {
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

    fun dispose() {
        scope.cancel()
    }

    /**
     * handleWebRtcConnected - con guard para llamadas Matrix
     */
    @OptIn(ExperimentalTime::class)
    fun handleWebRtcConnected() {
        // Guard: si la llamada activa es Matrix, el estado ya fue manejado por MatrixManager
        val activeCallData = sipCoreManager.currentAccountInfo?.currentCallData?.value
            ?: MultiCallManager.getAllCalls().firstOrNull()
        if (activeCallData?.callType == CallType.MATRIX_INTERNAL) {
            log.d(TAG) { "handleWebRtcConnected: skipping for Matrix call ${activeCallData.callId}" }
            return
        }

        sipCoreManager.callStartTimeMillis = kotlin.time.Clock.System.now().toEpochMilliseconds()

        sipCoreManager.currentAccountInfo?.currentCallData?.let { callData ->
            CallStateManager.streamsRunning(callData.value?.callId ?: generateId())
        }

        sipCoreManager.notifyCallStateChanged(CallState.STREAMS_RUNNING)
    }

    /**
     * handleWebRtcClosed - con guard para llamadas Matrix
     * Solo para llamadas YA conectadas. Para llamadas perdidas, el registro ya se hizo en endCall/declineCall
     */
    fun handleWebRtcClosed() {
        // Guard: si la llamada activa es Matrix, el estado ya fue manejado por MatrixManager
        val activeCallData = sipCoreManager.currentAccountInfo?.currentCallData?.value
            ?: MultiCallManager.getAllCalls().firstOrNull()
        if (activeCallData?.callType == CallType.MATRIX_INTERNAL) {
            log.d(TAG) { "handleWebRtcClosed: skipping for Matrix call ${activeCallData?.callId}" }
            return
        }

        val callData = sipCoreManager.currentAccountInfo?.currentCallData?.value

        if (callData == null) {
            log.w(tag = TAG) { "WebRTC closed but no active call data found" }
            CallStateManager.callEnded(generateId())
            sipCoreManager.notifyCallStateChanged(CallState.ENDED)
            return
        }

        val currentState = CallStateManager.getCurrentState()
        log.d(TAG) {
            "Handling WebRTC closed for call: ${callData.callId}, state: ${currentState.state}"
        }

        // Solo registrar si la llamada estuvo conectada
        val wasConnected = currentState.state == CallState.CONNECTED ||
                currentState.state == CallState.STREAMS_RUNNING ||
                currentState.state == CallState.PAUSED

        if (wasConnected) {
            registerCallInHistory(callData, currentState.state)
            log.d(TAG) { "Registered connected call in history: ${callData.callId}" }
        } else {
            log.d(TAG) {
                "Skipping history registration (call was not connected or already registered)"
            }
        }

        CallStateManager.callEnded(callData.callId)
        sipCoreManager.notifyCallStateChanged(CallState.ENDED)
    }
}
