package com.eddyslarez.kmpsiprtc.services.sip

import com.eddyslarez.kmpsiprtc.core.SipCoreManager
import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.data.models.CallData
import com.eddyslarez.kmpsiprtc.data.models.CallDataNormalizer
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.data.models.CallErrorReason
import com.eddyslarez.kmpsiprtc.data.models.CallState
import com.eddyslarez.kmpsiprtc.data.models.SdpType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.eddyslarez.kmpsiprtc.utils.generateId
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.calls.CallStateManager
import com.eddyslarez.kmpsiprtc.services.calls.MultiCallManager
import kotlinx.coroutines.IO
import kotlin.time.ExperimentalTime

class SipMessageHandler(private val sipCoreManager: SipCoreManager) {

    private val TAG = "SipMessageHandler"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private suspend fun sendViaSharedWebSocket(message: String): Boolean {
        return sipCoreManager.sharedWebSocketManager.sendMessage(message)
    }

    /**
     * Maneja mensajes SIP entrantes
     */
    suspend fun handleSipMessage(message: String, accountInfo: AccountInfo) {
        try {
            SipMessageParser.logIncomingMessage(message)

            val lines = message.split("\r\n")
            val firstLine = lines.firstOrNull() ?: return

            when {
                // Respuestas SIP
                firstLine.startsWith("SIP/2.0") -> handleSipResponse(firstLine, lines, accountInfo)

                // Requests SIP
                firstLine.startsWith("INVITE") -> handleInviteRequest(lines, accountInfo)
                firstLine.startsWith("BYE") -> handleByeRequest(lines, accountInfo)
                firstLine.startsWith("CANCEL") -> handleCancelRequest(lines, accountInfo)
                firstLine.startsWith("ACK") -> handleAckRequest(lines, accountInfo)
                firstLine.startsWith("INFO") -> handleInfoRequest(lines, accountInfo)
                firstLine.startsWith("OPTIONS") -> handleOptionsRequest(lines, accountInfo)

                else -> {
                    log.d(tag = TAG) { "Unknown SIP message type: $firstLine" }
                }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling SIP message: ${e.message}" }
        }
    }

    /**
     * Maneja respuestas SIP
     */
    private suspend fun handleSipResponse(
        firstLine: String,
        lines: List<String>,
        accountInfo: AccountInfo
    ) {
        val statusCode = extractStatusCode(firstLine)
        val method = SipMessageParser.extractMethodFromCSeq(firstLine, lines)

        log.d(tag = TAG) { "Handling SIP response: $statusCode for method: $method" }

        when (method) {
            "REGISTER" -> handleRegisterResponse(statusCode, lines, accountInfo)
            "INVITE" -> handleInviteResponse(statusCode, lines, accountInfo)
            "BYE" -> handleByeResponse(statusCode, lines, accountInfo)
            "CANCEL" -> handleCancelResponse(statusCode, lines, accountInfo)
            "ACK" -> handleAckResponse(statusCode, lines, accountInfo)
            else -> {
                log.d(tag = TAG) { "Unhandled response for method: $method" }
            }
        }
    }

    /**
     * Maneja respuestas de REGISTER
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun handleRegisterResponse(
        statusCode: Int,
        lines: List<String>,
        accountInfo: AccountInfo
    ) {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
        val fullResponse = lines.joinToString("\r\n")
        val reason = SipMessageParser.extractStatusReason(fullResponse)

        when (statusCode) {
            200 -> {
                scope.launch {
                    log.d(tag = TAG) { "Registration successful for $accountKey" }
                    sipCoreManager.handleRegistrationSuccess(accountInfo)

                    // Extraer tiempo de expiración y programar renovación per-account
                    val expiresValue = SipMessageParser.extractExpiresValue(fullResponse)
                    log.d(tag = TAG) { "Registration expires in ${expiresValue}s for $accountKey" }

                    // Programar re-REGISTER automático antes del vencimiento
                    sipCoreManager.sharedWebSocketManager.scheduleRegistrationRenewal(
                        accountKey,
                        expiresValue
                    )
                }
            }

            401, 407 -> {
                log.d(tag = TAG) { "Authentication required for registration: $statusCode" }
                handleAuthenticationChallenge(lines, accountInfo, "REGISTER")
            }

            403 -> {
                scope.launch {
                    log.e(tag = TAG) { "Registration forbidden: $reason" }
                    // Error grave, probablemente credenciales incorrectas o cuenta deshabilitada.
                    // No se debe reintentar agresivamente.
                    sipCoreManager.handleRegistrationError(
                        accountInfo,
                        "Forbidden: $reason"
                    )
                }
            }

            in 400..499 -> {
                scope.launch {
                    log.e(tag = TAG) { "Registration client error: $statusCode - $reason" }
                    // Otros errores de cliente. Podrían ser temporales (e.g., 423 Interval Too Brief)
                    // o permanentes. Por defecto, se reintenta pero con cuidado.
                    val retryAfterMs = SipMessageParser.parseRetryAfter(fullResponse)?.times(1000L)
                    sipCoreManager.handleRegistrationError(
                        accountInfo,
                        "Client error: $reason",
                    )
                }
            }

            in 500..599 -> {
                scope.launch {
                    log.e(tag = TAG) { "Registration server error: $statusCode - $reason" }
                    // Error del servidor. Se debe reintentar, pero usando el encabezado Retry-After si está presente.
                    val retryAfterMs = SipMessageParser.parseRetryAfter(fullResponse)?.times(1000L)

                    // Lógica simple para diferenciar errores temporales vs. graves
                    val isTemporaryServerError = statusCode == 503 || statusCode == 504

                    sipCoreManager.handleRegistrationError(
                        accountInfo,
                        "Server error: $reason",
                    )
                }
            }

            else -> {
                scope.launch {
                    log.w(tag = TAG) { "Unhandled registration response: $statusCode" }
                    // Para códigos no manejados, se asume un error temporal y se reintenta.
                    sipCoreManager.handleRegistrationError(
                        accountInfo,
                        "Unhandled status: $statusCode",
                    )
                }
            }
        }
    }

    /**
     * Maneja respuestas de INVITE
     */
    private suspend fun handleInviteResponse(
        statusCode: Int,
        lines: List<String>,
        accountInfo: AccountInfo
    ) {
        // Extraer Call-ID del mensaje SIP para identificar correctamente la llamada en multi-línea.
        // En multi-línea, accountInfo.currentCallData puede apuntar a otra llamada distinta
        // a la que generó esta respuesta 200 OK.
        val messageCallId = SipMessageParser.extractCallId(lines.joinToString("\r\n"))
        val callData = if (messageCallId.isNotEmpty()) {
            MultiCallManager.getCall(messageCallId) ?: accountInfo.currentCallData.value ?: return
        } else {
            accountInfo.currentCallData.value ?: return
        }

        val cseqHeader = SipMessageParser.extractHeader(lines, "CSeq")
        // Usar estado PER-LLAMADA para determinar si es re-INVITE.
        // El estado global puede reflejar otra llamada (ej: PAUSING de call1 mientras
        // procesamos respuesta de call2), causando detección incorrecta de re-INVITE.
        val perCallStateForIsReInvite = MultiCallManager.getCallState(callData.callId)?.state
        val isReInvite = cseqHeader.contains("INVITE") && (
                perCallStateForIsReInvite == CallState.PAUSING ||
                perCallStateForIsReInvite == CallState.RESUMING ||
                perCallStateForIsReInvite == CallState.STREAMS_RUNNING ||
                perCallStateForIsReInvite == CallState.PAUSED
        )
        when (statusCode) {
            100 -> {
                log.d(tag = TAG) { "Received 100 Trying for ${if (isReInvite) "re-INVITE" else "INVITE"}" }
            }

            180 -> {
                if (!isReInvite) {
                    log.d(tag = TAG) { "Received 180 Ringing" }
                    CallStateManager.outgoingCallRinging(callData.callId, 180)
                    sipCoreManager.audioManager.playOutgoingRingtone()
                    sipCoreManager.notifyCallStateChanged(CallState.OUTGOING_RINGING)
                }
            }

            183 -> {
                if (!isReInvite) {
                    log.d(tag = TAG) { "Received 183 Session Progress" }
                    CallStateManager.outgoingCallProgress(callData.callId, 183)
                    sipCoreManager.notifyCallStateChanged(CallState.OUTGOING_PROGRESS)
                }
            }

            200 -> {
                if (isReInvite) {
                    log.d(tag = TAG) { "Received 200 OK for re-INVITE (hold/resume)" }
                    handle200OKForReInvite(lines, accountInfo, callData)
                } else {
                    log.d(tag = TAG) { "Received 200 OK for INVITE - Call connected" }
                    handle200OKForInvite(lines, accountInfo, callData)
                }
            }

            401, 407 -> {
                log.d(tag = TAG) { "Authentication required for INVITE: $statusCode" }
                handleAuthenticationChallenge(lines, accountInfo, "INVITE")
            }

            486 -> {
                log.d(tag = TAG) { "Received 486 Busy Here" }
                handleCallError(callData, statusCode, "Busy Here", CallErrorReason.BUSY)
            }

            487 -> {
                log.d(tag = TAG) { "Received 487 Request Terminated" }
                handleCallCancelled(callData, lines, accountInfo)
            }

            603 -> {
                log.d(tag = TAG) { "Received 603 Decline" }
                handleCallError(callData, statusCode, "Declined", CallErrorReason.REJECTED)
            }

            408 -> {
                log.d(tag = TAG) { "Received 408 Request Timeout" }
                handleCallError(callData, statusCode, "No Answer", CallErrorReason.NO_ANSWER)
            }

            480 -> {
                log.d(tag = TAG) { "Received 480 Temporarily Unavailable" }
                handleCallError(
                    callData,
                    statusCode,
                    "Temporarily Unavailable",
                    CallErrorReason.TEMPORARILY_UNAVAILABLE
                )
            }

            404 -> {
                log.d(tag = TAG) { "Received 404 Not Found" }
                handleCallError(callData, statusCode, "Not Found", CallErrorReason.NOT_FOUND)
            }

            in 400..499 -> {
                val reason = SipMessageParser.extractStatusReason(lines.joinToString("\r\n"))
                log.e(tag = TAG) { "INVITE client error: $statusCode - $reason" }
                handleCallError(callData, statusCode, reason, CallErrorReason.UNKNOWN)
            }

            in 500..599 -> {
                val reason = SipMessageParser.extractStatusReason(lines.joinToString("\r\n"))
                log.e(tag = TAG) { "INVITE server error: $statusCode - $reason" }
                handleCallError(callData, statusCode, reason, CallErrorReason.SERVER_ERROR)
            }

            else -> {
                log.w(tag = TAG) { "Unhandled INVITE response: $statusCode" }
            }
        }
    }

    /**
     * NUEVO: Maneja 200 OK para re-INVITE (hold/resume)
     */
    private suspend fun handle200OKForReInvite(
        lines: List<String>,
        accountInfo: AccountInfo,
        callData: CallData
    ) {
        try {
            log.d(tag = TAG) { "Processing 200 OK for re-INVITE (callId: ${callData.callId})" }

            val remoteSdp = SipMessageParser.extractSdpContent(lines.joinToString("\r\n"))
            // Usar estado PER-LLAMADA para determinar si este 200 OK es para hold o resume.
            // El estado global (getCurrentState) puede corresponder a otra llamada en multi-línea,
            // causando que se llame callOnHold/callResumed en la llamada equivocada.
            val perCallState = MultiCallManager.getCallState(callData.callId)?.state

            // Enviar ACK para re-INVITE
            val ack = SipMessageBuilder.buildAckMessage(accountInfo, callData)
            sendViaSharedWebSocket(ack)

            when (perCallState) {
                CallState.PAUSING -> {
                    // Marcar INMEDIATAMENTE como PAUSED para que los 200 OK retransmitidos
                    // no reentren en este branch y dupliquen la transición de estado.
                    MultiCallManager.updateCallState(callData.callId, CallState.PAUSED)

                    scope.launch {
                        // Completar transición a hold para esta llamada específica
                        CallStateManager.callOnHold(callData.callId)
                        sipCoreManager.notifyCallStateChanged(CallState.PAUSED)
                        log.d(tag = TAG) { "Call ${callData.callId} successfully put on hold" }
                    }
                }

                CallState.RESUMING -> {
                    // Marcar INMEDIATAMENTE como STREAMS_RUNNING en MultiCallManager para que
                    // los 200 OK retransmitidos por el servidor caigan en el branch else.
                    MultiCallManager.updateCallState(callData.callId, CallState.STREAMS_RUNNING)

                    scope.launch {
                        // Multi-llamada: la PC fue recreada en resumeCall() con createOffer(),
                        // por lo que esta en estado have-local-offer y setRemoteDescription(ANSWER)
                        // completara la negociacion ICE/DTLS con el endpoint de esta llamada.
                        // Hold/resume simple: la PC esta en stable, setRemoteDescription falla;
                        // lo capturamos y continuamos (solo necesitamos setAudioEnabled).
                        if (remoteSdp.isNotBlank()) {
                            try {
                                sipCoreManager.webRtcManager.setRemoteDescription(
                                    remoteSdp,
                                    com.eddyslarez.kmpsiprtc.data.models.SdpType.ANSWER
                                )
                                log.d(tag = TAG) { "setRemoteDescription aplicado para resume ${callData.callId}" }
                            } catch (e: Exception) {
                                // Esperado cuando la PC está en estado stable (fix multi-llamada)
                                log.w(tag = TAG) { "setRemoteDescription omitido en resume (PC en stable): ${e.message}" }
                            }
                        }
                        // Siempre re-habilitar audio: el track fue desactivado por alguna parte
                        // del ciclo hold, y la PC sigue activa con la conexión ICE/DTLS intacta.
                        try {
                            sipCoreManager.webRtcManager.setAudioEnabled(true)
                            log.d(tag = TAG) { "Audio re-habilitado para llamada reanudada ${callData.callId}" }
                        } catch (e: Exception) {
                            log.e(tag = TAG) { "Error habilitando audio en resume: ${e.message}" }
                        }
                        // Completar transición a resume para esta llamada específica
                        CallStateManager.callResumed(callData.callId)
                        sipCoreManager.notifyCallStateChanged(CallState.STREAMS_RUNNING)
                        log.d(tag = TAG) { "Call ${callData.callId} successfully resumed" }
                    }
                }

                else -> {
                    log.w(tag = TAG) { "Unexpected re-INVITE 200 OK in state: $perCallState for call ${callData.callId}" }
                }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing re-INVITE 200 OK: ${e.message}" }
        }
    }

    /**
     * Maneja 200 OK para INVITE
     */
    private fun handle200OKForInvite(
        lines: List<String>,
        accountInfo: AccountInfo,
        callData: CallData
    ) {
        try {
            // CRÍTICO: Detener tono ANTES de cualquier otra operación
            sipCoreManager.audioManager.stopAllRingtones()

            log.d(tag = TAG) { "Procesando 200 OK para call: ${callData.callId}" }

            // Extraer headers y SDP
            val toTag = SipMessageParser.extractTag(SipMessageParser.extractHeader(lines, "To"))
            val contactUri = SipMessageParser.extractUriFromContact(
                SipMessageParser.extractHeader(
                    lines,
                    "Contact"
                )
            )
            val remoteSdp = SipMessageParser.extractSdpContent(lines.joinToString("\r\n"))

            // Validar que tenemos SDP remoto
            if (remoteSdp.isEmpty()) {
                log.e(tag = TAG) { "ERROR: No remote SDP in 200 OK response" }
                handleCallError(callData, 200, "No remote SDP", CallErrorReason.NETWORK_ERROR)
                return
            }

            // Guardar en callData
            callData.inviteToTag = toTag
            callData.remoteContactUri = contactUri
            callData.remoteSdp = remoteSdp

            // Configurar WebRTC ANTES de cambiar estado a CONNECTED
            scope.launch {
                try {
                    // 1. Establecer SDP remoto
                    sipCoreManager.webRtcManager.setRemoteDescription(
                        remoteSdp,
                        SdpType.ANSWER
                    )

                    // 2. Enviar ACK
                    val ack = SipMessageBuilder.buildAckMessage(accountInfo, callData)
                    sendViaSharedWebSocket(ack)
                    log.d(tag = TAG) { "ACK enviado para call: ${callData.callId}" }

                    // 3. Esperar a que se establezca la conexion
                    delay(500)

                    // 4. Activar audio
                    sipCoreManager.webRtcManager.setAudioEnabled(true)
                    sipCoreManager.webRtcManager.setMuted(false)

                    // 5. AHORA cambiar estado (WebRTC ya esta listo)
                    CallStateManager.callConnected(callData.callId, 200)
                    sipCoreManager.notifyCallStateChanged(CallState.CONNECTED)

                    CallStateManager.streamsRunning(callData.callId)
                    sipCoreManager.notifyCallStateChanged(CallState.STREAMS_RUNNING)

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error en WebRTC tras 200 OK: ${e.message}" }
                    handleCallError(
                        callData,
                        200,
                        "Fallo WebRTC: ${e.message}",
                        CallErrorReason.NETWORK_ERROR
                    )
                }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error procesando 200 OK: ${e.message}" }
            handleCallError(callData, 200, "Error procesando respuesta", CallErrorReason.UNKNOWN)
        }
    }


    /**
     * Maneja errores de llamada
     */
    private fun handleCallError(
        callData: CallData,
        sipCode: Int?,
        reason: String,
        errorReason: CallErrorReason
    ) {
        scope.launch {
            // Detener todos los ringtones
            sipCoreManager.audioManager.stopAllRingtones()

            // Actualizar estado de error
            CallStateManager.callError(callData.callId, sipCode, reason, errorReason)
            sipCoreManager.notifyCallStateChanged(CallState.ERROR)

            // Limpiar recursos
            cleanupCall(callData)
        }
    }

    /**
     * Maneja llamada cancelada (487)
     */
    private fun handleCallCancelled(
        callData: CallData,
        lines: List<String>,
        accountInfo: AccountInfo
    ) {
        scope.launch {

            try {

                log.d(tag = TAG) { "Processing 487 Request Terminated for call: ${callData.callId}" }

                // Detener outgoing ringtone
                sipCoreManager.audioManager.stopOutgoingRingtone()

                // CRÍTICO: Enviar ACK para 487 usando headers correctos
                val ackMessage =
                    SipMessageBuilder.buildAckFor487Response(accountInfo, callData, lines)
                sendViaSharedWebSocket(ackMessage)
                log.d(tag = TAG) { "ACK sent for 487 response" }

                // Actualizar estado
                CallStateManager.callEnded(callData.callId, 487, "Request Terminated")
                sipCoreManager.notifyCallStateChanged(CallState.ENDED)

                // Limpiar recursos
                cleanupCall(callData)

                log.d(tag = TAG) { "Call cancellation completed successfully" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error handling call cancellation: ${e.message}" }
            }
        }
    }

    /**
     * Maneja request INVITE entrante con preservación robusta de SDP
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun handleInviteRequest(lines: List<String>, accountInfo: AccountInfo) {
        try {
            log.d(tag = TAG) { "[INVITE] [INVITE] Handling incoming INVITE request" }

            // Detener cualquier ringtone existente
            sipCoreManager.audioManager.stopAllRingtones()
            sipCoreManager.currentAccountInfo = accountInfo

            // === EXTRAER SDP ===
            val fullMessage = lines.joinToString("\r\n")
            val remoteSdp = SipMessageParser.extractSdpContent(fullMessage)

            if (remoteSdp.isBlank()) {
                log.e(tag = TAG) { "[ERROR] FATAL: INVITE without SDP body!" }
                // Enviar error 400
                val viaHeader = SipMessageParser.extractHeader(lines, "Via")
                val fromHeader = SipMessageParser.extractHeader(lines, "From")
                val toHeader = SipMessageParser.extractHeader(lines, "To")
                val callIdHeader = SipMessageParser.extractHeader(lines, "Call-ID")
                val cseqHeader = SipMessageParser.extractHeader(lines, "CSeq")

                val errorResponse = buildString {
                    append("SIP/2.0 400 Bad Request\r\n")
                    append("Via: $viaHeader\r\n")
                    append("From: $fromHeader\r\n")
                    append("To: $toHeader\r\n")
                    append("Call-ID: $callIdHeader\r\n")
                    append("CSeq: $cseqHeader\r\n")
                    append("Content-Length: 0\r\n\r\n")
                }
                sendViaSharedWebSocket(errorResponse)
                return
            }

            log.d(tag = TAG) { "[OK] SDP validated: ${remoteSdp.length} chars" }

            // === EXTRAER HEADERS ===
            val fromHeader = SipMessageParser.extractHeader(lines, "From")
            val toHeader = SipMessageParser.extractHeader(lines, "To")
            val callIdHeader = SipMessageParser.extractHeader(lines, "Call-ID")
            val viaHeader = SipMessageParser.extractHeader(lines, "Via")
            val contactHeader = SipMessageParser.extractHeader(lines, "Contact")
            val cseqHeader = SipMessageParser.extractHeader(lines, "CSeq")
            val paiHeader = SipMessageParser.extractHeader(lines, "P-Asserted-Identity")

            // === PARSEAR CAMPOS ===
            val fromUri = SipMessageParser.extractUriFromHeader(fromHeader)
            val toUri = SipMessageParser.extractUriFromHeader(toHeader)
            val fromUser = SipMessageParser.extractUserFromUri(fromUri)
            val fromTag = SipMessageParser.extractTag(fromHeader)
            val displayName = SipMessageParser.extractDisplayName(fromHeader)
            val remoteContactUri = if (contactHeader.isNotEmpty()) {
                SipMessageParser.extractUriFromContact(contactHeader)
            } else {
                "sip:$fromUser@${accountInfo.domain}"
            }

            // === CREAR CALLDATA UNA SOLA VEZ ===
            val callData = CallData(
                callId = callIdHeader,
                from = fromUser,
                to = accountInfo.username,
                direction = CallDirections.INCOMING,
                startTime = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                fromTag = fromTag,
                toTag = generateId(),
                remoteContactUri = remoteContactUri,
                remoteDisplayName = displayName,
                remoteSdp = remoteSdp, // [OK] Asignar inmediatamente
                localSdp = "",
                via = viaHeader,
                originalInviteMessage = fullMessage,
                assertedIdentity = paiHeader.takeIf { it.isNotBlank() },
                rawToUri = toUri.takeIf { it.isNotBlank() },
                rawFromUri = fromUri.takeIf { it.isNotBlank() }
            )

            // Extraer CSeq
            val cseqParts = cseqHeader.split(" ")
            if (cseqParts.size >= 2) {
                callData.lastCSeqValue = cseqParts[0].toIntOrNull() ?: 1
            }

            // Normalizar para callbacks (To con .invalid / transport=ws)
            val normalizedCallData = CallDataNormalizer.normalize(callData)

            // [OK] GUARDAR EN UN SOLO LUGAR
            accountInfo.currentCallData.value = normalizedCallData

            // [OK] AGREGAR A MULTICALL MANAGER UNA SOLA VEZ
            MultiCallManager.addCall(normalizedCallData)

            // Actualizar CSeq si corresponde
            SipMessageParser.updateCSeqIfPresent(lines, accountInfo)

            // === CAMBIO DE ESTADO ===
            CallStateManager.incomingCallReceived(normalizedCallData.callId, normalizedCallData.from)
            sipCoreManager.notifyCallStateChanged(CallState.INCOMING_RECEIVED)

            // === ENVIAR RESPUESTAS SIP ===
            val tryingResponse = SipMessageBuilder.buildTryingResponse(accountInfo, normalizedCallData)
            sendViaSharedWebSocket(tryingResponse)

            scope.launch {
                delay(100)
                val ringingResponse = SipMessageBuilder.buildRingingResponse(accountInfo, normalizedCallData)
                sendViaSharedWebSocket(ringingResponse)

                delay(200)
                // Solo reproducir ringtone en-app si la llamada NO viene de push.
                // Cuando viene de push, CallKit ya presenta la UI con el ringtone del sistema.
                if (!sipCoreManager.isIncomingPushCallPending) {
                    sipCoreManager.audioManager.playIncomingRingtone(syncVibration = true)
                } else {
                    log.d(tag = TAG) { "Push call — skipping in-app ringtone (CallKit managing audio)" }
                }
            }

            log.d(tag = TAG) { "[OK] [INVITE] Incoming call setup completed" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "[FATAL] Exception in handleInviteRequest: ${e.message}" }
            log.e(tag = TAG) { e.stackTraceToString() }
            sipCoreManager.audioManager.stopAllRingtones()
        }
    }
    /**
     * Maneja request BYE
     */
    private suspend fun handleByeRequest(lines: List<String>, accountInfo: AccountInfo) {
        log.d(tag = TAG) { "[BYE] Handling BYE request" }

        try {
            // [OK] DETENER RINGTONES INMEDIATAMENTE
            sipCoreManager.audioManager.stopAllRingtones()

            // Enviar 200 OK para BYE
            val okResponse = SipMessageBuilder.buildByeOkResponse(accountInfo, lines)
            sendViaSharedWebSocket(okResponse)
            log.d(tag = TAG) { "[OK] 200 OK sent for BYE" }

            // Extraer Call-ID para identificar la llamada correcta en multi-línea.
            // accountInfo.currentCallData puede apuntar a otra llamada activa distinta.
            val byeCallId = SipMessageParser.extractCallId(lines.joinToString("\r\n"))
            val callData = if (byeCallId.isNotEmpty()) {
                MultiCallManager.getCall(byeCallId) ?: accountInfo.currentCallData.value
            } else {
                accountInfo.currentCallData.value
            }

            if (callData != null) {
                log.d(tag = TAG) { "[BYE] Processing BYE for call: ${callData.callId}" }

                // Registrar en historial ANTES de cambiar el estado a ENDED.
                sipCoreManager.callManager?.registerRemoteHangup(callData)

                // Actualizar estado de ESTA llamada específica
                CallStateManager.callEnded(callData.callId, sipReason = "Remote hangup")
                sipCoreManager.notifyCallStateChanged(CallState.ENDED)

                // Notificar cuenta específica
                val accountKey = "${accountInfo.username}@${accountInfo.domain}"
                sipCoreManager.notifyCallEndedForSpecificAccount(accountKey)

                // LIMPIEZA ASÍNCRONA — solo disponer WebRTC si no quedan otras llamadas activas
                scope.launch {
                    try {
                        delay(200)

                        // Limpiar MultiCallManager PRIMERO
                        MultiCallManager.removeCall(callData.callId)

                        // Verificar si quedan llamadas activas antes de disponer WebRTC.
                        // En multi-línea NO se debe disponer WebRTC si otra llamada sigue activa.
                        val remainingActiveCalls = MultiCallManager.getActiveCalls()
                        if (remainingActiveCalls.isEmpty()) {
                            log.d(tag = TAG) { "[BYE] No more active calls — disposing WebRTC" }
                            sipCoreManager.webRtcManager.dispose()
                            accountInfo.resetCallState()
                        } else {
                            log.d(tag = TAG) { "[BYE] ${remainingActiveCalls.size} call(s) still active — keeping WebRTC alive" }
                        }

                        log.d(tag = TAG) { "[OK] BYE cleanup completed for ${callData.callId}" }
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error in BYE cleanup: ${e.message}" }
                    }
                }
            } else {
                log.w(tag = TAG) { "[WARN] No call data found for BYE (Call-ID: $byeCallId)" }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "[ERROR] Error handling BYE request: ${e.message}" }
            sipCoreManager.audioManager.stopAllRingtones()
        }
    }

    /**
     * Maneja request CANCEL
     */
    /**
     * Maneja request CANCEL
     */
    private suspend fun handleCancelRequest(lines: List<String>, accountInfo: AccountInfo) {
        log.d(tag = TAG) { "[CANCEL] Handling CANCEL request" }

        try {
            // [OK] Detener ringtones inmediatamente
            sipCoreManager.audioManager.stopAllRingtones()

            // Extraer Call-ID del CANCEL para identificar la llamada correcta en multi-línea
            val cancelCallId = SipMessageParser.extractCallId(lines.joinToString("\r\n"))
            val callData = if (cancelCallId.isNotEmpty()) {
                MultiCallManager.getCall(cancelCallId) ?: accountInfo.currentCallData.value
            } else {
                accountInfo.currentCallData.value
            }

            if (callData == null) {
                log.w(tag = TAG) { "[ERROR] No call data for CANCEL" }
            } else {
                // [OK] REGISTRAR LLAMADA PERDIDA ANTES DE RESPUESTAS
                if (callData.direction == CallDirections.INCOMING) {
                    log.d(tag = TAG) { "[LOG] Registering MISSED call from CANCEL: ${callData.callId}" }
                    sipCoreManager.callManager?.registerMissedCall(callData)
                }
            }

            // Enviar 200 OK para CANCEL
            val cancelOkResponse = SipMessageBuilder.buildCancelOkResponse(accountInfo, lines)
            sendViaSharedWebSocket(cancelOkResponse)
            log.d(tag = TAG) { "[OK] 200 OK sent for CANCEL" }

            // Enviar 487 Request Terminated para INVITE original
            callData?.let {
                val requestTerminatedResponse =
                    SipMessageBuilder.buildRequestTerminatedResponse(accountInfo, it)
                sendViaSharedWebSocket(requestTerminatedResponse)
                log.d(tag = TAG) { "[OK] 487 Request Terminated sent" }

                // Actualizar estado
                CallStateManager.callEnded(it.callId, 487, "Request Terminated")
                sipCoreManager.notifyCallStateChanged(CallState.ENDED)

                // Notificar cuenta específica
                val accountKey = "${accountInfo.username}@${accountInfo.domain}"
                sipCoreManager.notifyCallEndedForSpecificAccount(accountKey)

                // [OK] Limpieza asíncrona
                scope.launch {
                    try {
                        sipCoreManager.audioManager.stopAllRingtones()
                        delay(200)
                        accountInfo.resetCallState()
                        callData?.let { MultiCallManager.removeCall(it.callId) }
                        log.d(tag = TAG) { "[OK] CANCEL cleanup completed" }
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error in CANCEL cleanup: ${e.message}" }
                    }
                }
            } ?: run {
                log.w(tag = TAG) { "[WARN] No call data found for CANCEL" }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "[ERROR] Error handling CANCEL request: ${e.message}" }
            sipCoreManager.audioManager.stopAllRingtones()
        }
    }


    /**
     * Maneja request ACK
     */
    private fun handleAckRequest(lines: List<String>, accountInfo: AccountInfo) {
        log.d(tag = TAG) { "Handling ACK request" }

        // Extraer Call-ID para identificar la llamada correcta en multi-línea.
        // En multi-línea, accountInfo.currentCallData puede apuntar a otra llamada.
        val messageCallId = SipMessageParser.extractCallId(lines.joinToString("\r\n"))
        val callData = if (messageCallId.isNotEmpty()) {
            MultiCallManager.getCall(messageCallId) ?: accountInfo.currentCallData.value
        } else {
            accountInfo.currentCallData.value
        } ?: return

        // ACK confirma que la llamada está establecida — usar estado PER-LLAMADA.
        // El estado global puede ser de otra llamada (ej: PAUSED por call1 en hold),
        // bloqueando la transición CONNECTED → STREAMS_RUNNING de call2.
        val perCallState = MultiCallManager.getCallState(callData.callId)?.state
        if (perCallState == CallState.CONNECTED) {
            // Transición a streams running para esta llamada específica
            CallStateManager.streamsRunning(callData.callId)
            sipCoreManager.notifyCallStateChanged(CallState.STREAMS_RUNNING)
            log.d(tag = TAG) { "Call ${callData.callId} reached STREAMS_RUNNING via ACK" }
        } else {
            log.d(tag = TAG) { "ACK received for call ${callData.callId} in state $perCallState (no transition needed)" }
        }
    }

    /**
     * Maneja request INFO (para DTMF)
     */
    private suspend fun handleInfoRequest(lines: List<String>, accountInfo: AccountInfo) {
        log.d(tag = TAG) { "Handling INFO request" }

        try {
            // Enviar 200 OK para INFO
            val okResponse = buildInfoOkResponse(lines)
            sendViaSharedWebSocket(okResponse)

            // Procesar contenido DTMF si existe
            val contentType = SipMessageParser.extractHeader(lines, "Content-Type")
            if (contentType.contains("application/dtmf-relay", ignoreCase = true)) {
                val content = SipMessageParser.extractSdpContent(lines.joinToString("\r\n"))
                processDtmfContent(content, accountInfo)
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling INFO request: ${e.message}" }
        }
    }

    /**
     * Maneja request OPTIONS
     */
    private suspend fun handleOptionsRequest(lines: List<String>, accountInfo: AccountInfo) {
        log.d(tag = TAG) { "Handling OPTIONS request" }

        try {
            val optionsResponse = buildOptionsResponse(lines)
            sendViaSharedWebSocket(optionsResponse)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling OPTIONS request: ${e.message}" }
        }
    }

    /**
     * Maneja respuestas de BYE
     */
    private fun handleByeResponse(statusCode: Int, lines: List<String>, accountInfo: AccountInfo) {
        when (statusCode) {
            200 -> {
                log.d(tag = TAG) { "BYE confirmed with 200 OK" }
                accountInfo.currentCallData.value?.let { callData ->
                    cleanupCall(callData)
                }
            }

            else -> {
                log.w(tag = TAG) { "Unexpected BYE response: $statusCode" }
            }
        }
    }

    /**
     * Maneja respuestas de CANCEL
     */
    private fun handleCancelResponse(
        statusCode: Int,
        lines: List<String>,
        accountInfo: AccountInfo
    ) {
        when (statusCode) {
            200 -> {
                log.d(tag = TAG) { "CANCEL confirmed with 200 OK" }
                // El 487 debería llegar por separado
            }

            else -> {
                log.w(tag = TAG) { "Unexpected CANCEL response: $statusCode" }
            }
        }
    }

    /**
     * Maneja respuestas de ACK
     */
    private fun handleAckResponse(statusCode: Int, lines: List<String>, accountInfo: AccountInfo) {
        // ACK no debería tener respuestas normalmente
        log.d(tag = TAG) { "Received unexpected ACK response: $statusCode" }
    }

    /**
     * Maneja desafío de autenticación
     */
    private suspend fun handleAuthenticationChallenge(
        lines: List<String>,
        accountInfo: AccountInfo,
        method: String
    ) {
        try {
            log.d(tag = TAG) { "Handling authentication challenge for $method au" }

            val authData = AuthenticationHandler.extractAuthenticationData(lines)

            log.d(tag = TAG) { "Handling authentication  authData ,  authData $authData" }

            if (authData == null) {
                log.e(tag = TAG) { "Failed to extract authentication data" }
                return
            }

            val response =
                AuthenticationHandler.calculateAuthResponse(accountInfo, authData, method)
            AuthenticationHandler.updateAccountAuthInfo(accountInfo, authData, response, method)
            log.d(tag = TAG) { "Handling authentication  response ,  response $response" }

            // Reenviar request con autenticación
            when (method) {
                "REGISTER" -> {

                    val authenticatedRegister = SipMessageBuilder.buildAuthenticatedRegisterMessage(
                        accountInfo,
                        sipCoreManager.isAppInBackground
                    )

                    log.d(tag = TAG) { "Handling authentication  REGISTER ,  authenticatedRegister $authenticatedRegister" }

                    sendViaSharedWebSocket(authenticatedRegister)
                }

                "INVITE" -> {
                    accountInfo.currentCallData.value?.let { callData ->
                        val authenticatedInvite = SipMessageBuilder.buildAuthenticatedInviteMessage(
                            accountInfo,
                            callData,
                            callData.localSdp
                        )
                        callData.originalCallInviteMessage = authenticatedInvite

                        sendViaSharedWebSocket(authenticatedInvite)
                    }
                }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling authentication challenge: ${e.message}" }
        }
    }

    /**
     * Limpia recursos de llamada
     */
    private fun cleanupCall(callData: CallData) {
        scope.launch {
            try {
                // Detener todos los ringtones
                sipCoreManager.audioManager.stopAllRingtones()

                // Limpiar WebRTC
                sipCoreManager.webRtcManager.dispose()

                // Limpiar datos de cuenta
                sipCoreManager.currentAccountInfo?.resetCallState()

                // Limpiar DTMF
                sipCoreManager.callManager?.clearDtmfQueue()

                log.d(tag = TAG) { "Call cleanup completed for ${callData.callId}" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error during call cleanup: ${e.message}" }
            }
        }
    }

    /**
     * Procesa contenido DTMF
     */
    private fun processDtmfContent(content: String, accountInfo: AccountInfo) {
        try {
            val lines = content.split("\r\n")
            var signal: Char? = null
            var duration: Int = 160

            lines.forEach { line ->
                when {
                    line.startsWith("Signal=") -> {
                        signal = line.substringAfter("Signal=").firstOrNull()
                    }

                    line.startsWith("Duration=") -> {
                        duration = line.substringAfter("Duration=").toIntOrNull() ?: 160
                    }
                }
            }

            signal?.let { digit ->
                log.d(tag = TAG) { "Received DTMF: $digit (duration: $duration)" }
                // Aquí puedes notificar al callback de DTMF recibido
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing DTMF content: ${e.message}" }
        }
    }

    /**
     * Construye respuesta OK para INFO
     */
    private fun buildInfoOkResponse(lines: List<String>): String {
        val viaHeader = SipMessageParser.extractHeader(lines, "Via")
        val fromHeader = SipMessageParser.extractHeader(lines, "From")
        val toHeader = SipMessageParser.extractHeader(lines, "To")
        val callId = SipMessageParser.extractHeader(lines, "Call-ID")
        val cseqHeader = SipMessageParser.extractHeader(lines, "CSeq")

        return buildString {
            append("SIP/2.0 200 OK\r\n")
            append("Via: $viaHeader\r\n")
            append("From: $fromHeader\r\n")
            append("To: $toHeader\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $cseqHeader\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
    }

    /**
     * Construye respuesta para OPTIONS
     */
    private fun buildOptionsResponse(lines: List<String>): String {
        val viaHeader = SipMessageParser.extractHeader(lines, "Via")
        val fromHeader = SipMessageParser.extractHeader(lines, "From")
        val toHeader = SipMessageParser.extractHeader(lines, "To")
        val callId = SipMessageParser.extractHeader(lines, "Call-ID")
        val cseqHeader = SipMessageParser.extractHeader(lines, "CSeq")

        return buildString {
            append("SIP/2.0 200 OK\r\n")
            append("Via: $viaHeader\r\n")
            append("From: $fromHeader\r\n")
            append("To: $toHeader\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $cseqHeader\r\n")
            append("Allow: INVITE, ACK, CANCEL, BYE, INFO, OPTIONS\r\n")
            append("Accept: application/sdp, application/dtmf-relay\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
    }

    /**
     * Extrae código de estado de la primera línea
     */
    private fun extractStatusCode(firstLine: String): Int {
        return try {
            val parts = firstLine.split(" ")
            if (parts.size >= 2) {
                parts[1].toInt()
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    // === MÉTODOS PARA ENVIAR MENSAJES ===

    /**
     * Envía mensaje REGISTER
     */
    suspend fun sendRegister(accountInfo: AccountInfo, isAppInBackground: Boolean) {
        try {
            val callId = accountInfo.callId.value ?: generateId()
            val fromTag = accountInfo.fromTag.value ?: generateId()

            accountInfo.callId.value = callId
            accountInfo.fromTag.value = fromTag

            val registerMessage = SipMessageBuilder.buildRegisterMessage(
                accountInfo, callId, fromTag, isAppInBackground
            )

            sendViaSharedWebSocket(registerMessage)
            log.d(tag = TAG) { "REGISTER sent for ${accountInfo.username}@${accountInfo.domain}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending REGISTER: ${e.message}" }
        }
    }

    /**
     * Envía mensaje UNREGISTER
     */
    suspend fun sendUnregister(accountInfo: AccountInfo) {
        try {
            val callId = accountInfo.callId.value ?: generateId()
            val fromTag = accountInfo.fromTag.value ?: generateId()

            val unregisterMessage = SipMessageBuilder.buildUnregisterMessage(
                accountInfo, callId, fromTag
            )

            sendViaSharedWebSocket(unregisterMessage)
            log.d(tag = TAG) { "UNREGISTER sent for ${accountInfo.username}@${accountInfo.domain}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending UNREGISTER: ${e.message}" }
        }
    }

    /**
     * Envía mensaje INVITE
     */
    suspend fun sendInvite(accountInfo: AccountInfo, callData: CallData) {
        try {
            val inviteMessage = SipMessageBuilder.buildInviteMessage(
                accountInfo, callData, callData.localSdp
            )

            callData.originalCallInviteMessage = inviteMessage
            sendViaSharedWebSocket(inviteMessage)

            log.d(tag = TAG) { "INVITE sent for call ${callData.callId}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending INVITE: ${e.message}" }
        }
    }

    /**
     * Envía mensaje BYE
     */
    suspend fun sendBye(accountInfo: AccountInfo, callData: CallData) {
        try {
            val byeMessage = SipMessageBuilder.buildByeMessage(accountInfo, callData)
            sendViaSharedWebSocket(byeMessage)

            log.d(tag = TAG) { "BYE sent for call ${callData.callId}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending BYE: ${e.message}" }
        }
    }

    /**
     * Envía mensaje CANCEL
     */
    suspend fun sendCancel(accountInfo: AccountInfo, callData: CallData) {
        try {
            val cancelMessage = SipMessageBuilder.buildCancelMessage(accountInfo, callData)
            sendViaSharedWebSocket(cancelMessage)

            log.d(tag = TAG) { "CANCEL sent for call ${callData.callId}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending CANCEL: ${e.message}" }
        }
    }

    /**
     * Envía respuesta 200 OK para INVITE
     */
   suspend fun sendInviteOkResponse(accountInfo: AccountInfo, callData: CallData) {
        try {
            val okResponse = SipMessageBuilder.buildInviteOkResponse(accountInfo, callData)
            sendViaSharedWebSocket(okResponse)

            log.d(tag = TAG) { "200 OK sent for INVITE ${callData.callId}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending INVITE OK response: ${e.message}" }
        }
    }

    /**
     * Envía respuesta de rechazo
     */
    suspend fun sendDeclineResponse(accountInfo: AccountInfo, callData: CallData) {
        try {
            val declineResponse = SipMessageBuilder.buildDeclineResponse(accountInfo, callData)
            sendViaSharedWebSocket(declineResponse)

            log.d(tag = TAG) { "603 Decline sent for call ${callData.callId}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending decline response: ${e.message}" }
        }
    }

    /**
     * Envía re-INVITE para hold/resume
     */
    suspend fun sendReInvite(accountInfo: AccountInfo, callData: CallData, sdp: String) {
        try {
            val reInviteMessage = SipMessageBuilder.buildReInviteMessage(accountInfo, callData, sdp)
            sendViaSharedWebSocket(reInviteMessage)

            log.d(tag = TAG) { "Re-INVITE sent for call ${callData.callId}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending re-INVITE: ${e.message}" }
        }
    }

    /**
     * Envía mensaje INFO para DTMF
     */
    suspend fun sendDtmfInfo(accountInfo: AccountInfo, callData: CallData, digit: Char, duration: Int) {
        try {
            val infoMessage = SipMessageBuilder.buildDtmfInfoMessage(
                accountInfo, callData, digit, duration
            )
            sendViaSharedWebSocket(infoMessage)

            log.d(tag = TAG) { "DTMF INFO sent: $digit (duration: $duration)" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending DTMF INFO: ${e.message}" }
        }
    }

    fun dispose() {
        scope.cancel()
    }
}
