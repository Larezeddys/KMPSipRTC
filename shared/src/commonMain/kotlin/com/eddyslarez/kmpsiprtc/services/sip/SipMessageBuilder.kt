package com.eddyslarez.kmpsiprtc.services.sip

import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.data.models.CallData
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.utils.generateId
import com.eddyslarez.kmpsiprtc.platform.log
object SipMessageBuilder {
    // Constantes SIP
    private const val SIP_VERSION = "SIP/2.0"
    private const val SIP_TRANSPORT = "WS"
    private const val MAX_FORWARDS = 70
    // RFC: Usar un Expires razonable. El servidor puede reducirlo en la respuesta 200 OK.
    // El equipo OpenSIPS confirmo: "usar el Expires que nosotros enviamos".
    // 600s (10 min) es un buen equilibrio entre tráfico y disponibilidad.
    private const val DEFAULT_EXPIRES = 600
    private const val UNREGISTER_EXPIRES = 0
    private const val TAG = "SipMessageBuilder"

    /**
     * Build REGISTER message with optional push notification support
     *
     * @param pushProduction true = entorno de produccion (APNS produccion, RuStore produccion),
     *                       false = sandbox/debug. Se incluye en el Contact header como
     *                       ;pn-production=true/false cuando la app esta en background.
     *                       Requerido por OpenSIPS para enrutar pushes correctamente.
     */
    suspend fun buildRegisterMessage(
        accountInfo: AccountInfo,
        callId: String,
        fromTag: String,
        isAppInBackground: Boolean,
        isAuthenticated: Boolean = false,
        pushProduction: Boolean = false,
    ): String {
        val uri = "sip:${accountInfo.domain}"
        val builder = StringBuilder()

        log.d(tag = TAG) {
            "Building REGISTER message:" +
                    "\nAccount: ${accountInfo.username}@${accountInfo.domain}" +
                    "\nIs App In Background: $isAppInBackground" +
                    "\nToken: ${accountInfo.token.value}" +
                    "\nProvider: ${accountInfo.provider.value}" +
                    "\nUser Agent: ${accountInfo.userAgent.value}" +
                    "\nLocalContactHost: ${accountInfo.localContactHost}" +
                    "\nLocalContactId: ${accountInfo.localContactId}" +
                    "\nPush Production: $pushProduction"
        }

        val currentCSeq = accountInfo.incrementCSeq()

        builder.append("REGISTER $uri $SIP_VERSION\r\n")
        // ✅ CORREGIDO: Via usa localContactHost en lugar de domain
        builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.localContactHost};branch=z9hG4bK${generateId()}\r\n")
        builder.append("Max-Forwards: $MAX_FORWARDS\r\n")
        builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=$fromTag\r\n")
        builder.append("To: <sip:${accountInfo.username}@${accountInfo.domain}>\r\n")
        builder.append("Call-ID: $callId\r\n")
        builder.append("CSeq: $currentCSeq REGISTER\r\n")
        builder.append("User-Agent: ${accountInfo.userAgent.value}\r\n")

        // Contact header con soporte RFC 5626 (outbound) y RFC 5627 (instance-id)
        builder.append("Contact: <sip:${accountInfo.localContactId}@${accountInfo.localContactHost}")

        if (isAppInBackground) {
            log.d(tag = TAG) { "Adding push notification parameters to Contact header (pn-production=$pushProduction)" }
            // pn-prid y pn-provider son obligatorios para push (RFC 8599)
            // pn-production requerido por OpenSIPS: false=sandbox/debug, true=produccion
            builder.append(";pn-prid=${accountInfo.token.value};pn-provider=${accountInfo.provider.value};pn-production=$pushProduction")
        } else {
            log.d(tag = TAG) { "Building Contact header WITHOUT push notification parameters" }
        }

        // ;transport=ws base + ;ob (RFC 5626 outbound)
        builder.append(";transport=ws;ob>")
        // +sip.instance (RFC 5626) - permite a OpenSIPS identificar este dispositivo
        // de forma unica y reemplazar Contact anterior (max_contacts=1)
        builder.append(";+sip.instance=${accountInfo.instanceId}")
        // Feature tag para push (RFC 8599) cuando esta en background
        if (isAppInBackground) {
            builder.append(";+sip.pnsreg")
        }
        builder.append(";expires=$DEFAULT_EXPIRES\r\n")
        builder.append("Expires: $DEFAULT_EXPIRES\r\n")

        // RFC 3261: Usar Authorization para 401, Proxy-Authorization para 407
        if (isAuthenticated && accountInfo.authorizationHeader.value != null) {
            val headerName = AuthenticationHandler.getAuthHeaderName(accountInfo.lastChallengeType.value)
            builder.append("$headerName: ${accountInfo.authorizationHeader.value}\r\n")
        }

        builder.append("Content-Length: 0\r\n\r\n")

        val finalMessage = builder.toString()

        log.d(tag = TAG) {
            "Final REGISTER message Contact header contains push params: ${
                finalMessage.contains("pn-prid")
            }, pn-production=$pushProduction"
        }

        return finalMessage
    }

    /**
     * Build authenticated REGISTER message
     *
     * @param pushProduction true = produccion, false = sandbox/debug.
     *                       Se pasa directamente a buildRegisterMessage.
     */
    suspend fun buildAuthenticatedRegisterMessage(
        accountInfo: AccountInfo,
        isAppInBackground: Boolean,
        pushProduction: Boolean = false,
    ): String {
        return buildRegisterMessage(
            accountInfo = accountInfo,
            callId = accountInfo.callId.value ?: generateId(),
            fromTag = accountInfo.fromTag.value ?: generateId(),
            isAppInBackground = isAppInBackground,
            isAuthenticated = true,
            pushProduction = pushProduction,
        )
    }

    /**
     * Construye mensaje UNREGISTER (Expires: 0) para desregistrar el Contact.
     * IMPORTANTE para OpenSIPS con max_contacts=1: enviar UNREGISTER antes de
     * re-registrar desde otra conexion evita "phantom registrations".
     * Construido directamente (no con replace) para robustez.
     */
    suspend fun buildUnregisterMessage(
        accountInfo: AccountInfo,
        callId: String,
        fromTag: String
    ): String {
        val uri = "sip:${accountInfo.domain}"
        val builder = StringBuilder()
        val currentCSeq = accountInfo.incrementCSeq()

        builder.append("REGISTER $uri $SIP_VERSION\r\n")
        builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.localContactHost};branch=z9hG4bK${generateId()}\r\n")
        builder.append("Max-Forwards: $MAX_FORWARDS\r\n")
        builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=$fromTag\r\n")
        builder.append("To: <sip:${accountInfo.username}@${accountInfo.domain}>\r\n")
        builder.append("Call-ID: $callId\r\n")
        builder.append("CSeq: $currentCSeq REGISTER\r\n")
        builder.append("User-Agent: ${accountInfo.userAgent.value}\r\n")
        // Contact con * para desregistrar todos los bindings, o especifico con expires=0
        builder.append("Contact: <sip:${accountInfo.localContactId}@${accountInfo.localContactHost};transport=ws;ob>")
        builder.append(";+sip.instance=${accountInfo.instanceId}")
        builder.append(";expires=$UNREGISTER_EXPIRES\r\n")
        builder.append("Expires: $UNREGISTER_EXPIRES\r\n")

        // Incluir autenticacion si esta disponible
        if (accountInfo.authorizationHeader.value != null) {
            val headerName = AuthenticationHandler.getAuthHeaderName(accountInfo.lastChallengeType.value)
            builder.append("$headerName: ${accountInfo.authorizationHeader.value}\r\n")
        }

        builder.append("Content-Length: 0\r\n\r\n")

        return builder.toString()
    }

    /**
     * Build INVITE message (handles both regular and authenticated)
     */
    suspend fun buildInviteMessage(
        accountInfo: AccountInfo,
        callData: CallData,
        sdp: String,
        isAuthenticated: Boolean = false
    ): String {
        val target = callData.to
        val uri = "sip:${target}@${accountInfo.domain}"
        val branch = "z9hG4bK${generateId()}"
        val md5Hash = callData.md5Hash
        val currentCSeq = accountInfo.incrementCSeq()

        callData.inviteViaBranch = branch
        // ✅ CORREGIDO: Via usa localContactHost
        callData.via = "$SIP_VERSION/$SIP_TRANSPORT ${accountInfo.localContactHost};branch=$branch"

        val builder = StringBuilder()
        builder.append("INVITE $uri $SIP_VERSION\r\n")
        // ✅ CORREGIDO: Via usa localContactHost
        builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.localContactHost};branch=$branch\r\n")
        builder.append("Max-Forwards: $MAX_FORWARDS\r\n")
        builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.inviteFromTag}\r\n")
        builder.append("To: <$uri>\r\n")
        builder.append("Call-ID: ${callData.callId}\r\n")
        builder.append("CSeq: $currentCSeq INVITE\r\n")
        // Contact con ;ob (RFC 5626 outbound) para llamadas
        builder.append("Contact: <sip:${accountInfo.localContactId}@${accountInfo.localContactHost};transport=ws;ob>\r\n")

        if (md5Hash.isNotEmpty()) {
            builder.append("X-MD5: $md5Hash\r\n")
        }

        // RFC 3261: Usar Authorization para 401, Proxy-Authorization para 407
        if (isAuthenticated && accountInfo.authorizationHeader.value != null) {
            val headerName = AuthenticationHandler.getAuthHeaderName(accountInfo.lastChallengeType.value)
            builder.append("$headerName: ${accountInfo.authorizationHeader.value}\r\n")
        }

        builder.append("Content-Type: application/sdp\r\n")
        builder.append("Content-Length: ${sdp.length}\r\n\r\n")
        builder.append(sdp)

        return builder.toString()
    }

    /**
     * Build authenticated INVITE message
     */
    suspend fun buildAuthenticatedInviteMessage(
        accountInfo: AccountInfo,
        callData: CallData,
        sdp: String
    ): String = buildInviteMessage(accountInfo, callData, sdp, isAuthenticated = true)

    /**
     * Build call control messages (BYE, CANCEL, ACK)
     */
    private suspend fun buildCallControlMessage(
        method: String,
        accountInfo: AccountInfo,
        callData: CallData,
        useOriginalVia: Boolean = false
    ): String {
        val builder = StringBuilder()
        val currentCSeq = accountInfo.incrementCSeq()
        log.d(tag = TAG) { "currentCSeq $currentCSeq" }

        when (callData.direction) {
            CallDirections.OUTGOING -> {
                val targetUri = "sip:${callData.to}@${accountInfo.domain}"
                builder.append("$method $targetUri $SIP_VERSION\r\n")

                if (useOriginalVia && method == "CANCEL" && callData.inviteViaBranch.isNotEmpty()) {
                    // ✅ CORREGIDO: Via usa localContactHost
                    builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.localContactHost};branch=${callData.inviteViaBranch}\r\n")
                } else {
                    val branch = if (method == "CANCEL") callData.inviteViaBranch else "z9hG4bK${generateId()}"
                    // ✅ CORREGIDO: Via usa localContactHost
                    builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.localContactHost};branch=$branch\r\n")
                }

                builder.append("Max-Forwards: $MAX_FORWARDS\r\n")
                builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.inviteFromTag}\r\n")

                if (callData.inviteToTag.isNotEmpty() && method != "CANCEL") {
                    builder.append("To: <$targetUri>;tag=${callData.inviteToTag}\r\n")
                } else {
                    builder.append("To: <$targetUri>\r\n")
                }
            }

            CallDirections.INCOMING -> {
                val targetUri = if (callData.remoteContactUri?.isNotEmpty() == true) {
                    callData.remoteContactUri!!
                } else {
                    "sip:${callData.from}@${accountInfo.domain}"
                }

                builder.append("$method $targetUri $SIP_VERSION\r\n")
                val branch = "z9hG4bK${generateId()}"
                // ✅ CORREGIDO: Via usa localContactHost
                builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.localContactHost};branch=$branch\r\n")
                builder.append("Max-Forwards: $MAX_FORWARDS\r\n")
                builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.toTag}\r\n")
                builder.append("To: <sip:${callData.from}@${accountInfo.domain}>;tag=${callData.fromTag}\r\n")
            }
        }

        builder.append("Call-ID: ${callData.callId}\r\n")

        val cseqValue = if (method == "CANCEL") {
            if (callData.originalCallInviteMessage.isNotEmpty()) {
                val originalCseqHeader = SipMessageParser.extractHeader(
                    callData.originalCallInviteMessage.split("\r\n"), "CSeq"
                )
                log.d(tag = TAG) { "originalCseqHeader '$originalCseqHeader'" }
                if (originalCseqHeader.isNotEmpty()) {
                    originalCseqHeader.split(" ")[0].trim().toIntOrNull() ?: (currentCSeq - 1)
                } else {
                    log.w(tag = TAG) { "CSeq header not found in original INVITE, using fallback" }
                    currentCSeq - 1
                }
            } else {
                log.w(tag = TAG) { "originalCallInviteMessage is empty, using fallback CSeq" }
                currentCSeq - 1
            }
        } else {
            currentCSeq
        }

        log.d(tag = TAG) { "cseqValue $cseqValue" }

        builder.append("CSeq: $cseqValue $method\r\n")
        builder.append("Content-Length: 0\r\n\r\n")

        return builder.toString()
    }

    /**
     * Get target URI based on call direction
     */
    private fun getTargetUri(accountInfo: AccountInfo, callData: CallData): String {
        return if (callData.direction == CallDirections.OUTGOING) {
            "sip:${callData.to}@${accountInfo.domain}"
        } else {
            val contactHeader = SipMessageParser.extractHeader(
                callData.originalInviteMessage.split("\r\n"), "Contact"
            )
            val contactUri = SipMessageParser.extractUriFromContact(contactHeader)
            contactUri.ifEmpty { "sip:${callData.from}@${accountInfo.domain}" }
        }
    }

    /**
     * Append From/To headers based on call direction
     */
    private fun appendFromToHeaders(
        builder: StringBuilder,
        accountInfo: AccountInfo,
        callData: CallData,
        method: String
    ) {
        when {
            callData.direction == CallDirections.OUTGOING -> {
                builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.inviteFromTag}\r\n")
                val toUri = "sip:${callData.to}@${accountInfo.domain}"
                val toTagStr = if (method == "CANCEL") "" else ";tag=${callData.inviteToTag}"
                builder.append("To: <$toUri>$toTagStr\r\n")
            }
            callData.direction == CallDirections.INCOMING -> {
                builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.inviteToTag}\r\n")
                builder.append("To: <sip:${callData.from}@${accountInfo.domain}>;tag=${callData.inviteFromTag}\r\n")
            }
        }
    }

    /**
     * Build BYE message
     */
    suspend fun buildByeMessage(accountInfo: AccountInfo, callData: CallData): String =
        buildCallControlMessage("BYE", accountInfo, callData)

    /**
     * Build CANCEL message
     */
    suspend fun buildCancelMessage(accountInfo: AccountInfo, callData: CallData): String =
        buildCallControlMessage("CANCEL", accountInfo, callData, useOriginalVia = true)

    /**
     * Build ACK message
     */
    fun buildAckMessage(
        accountInfo: AccountInfo,
        callData: CallData,
        // En re-INVITE multi-llamada el cseq global puede haber avanzado antes de que llegue
        // el 200 OK, por lo que el ACK debe usar el CSeq EXACTO del 200 OK, no el global.
        explicitCSeq: Int? = null,
        // El Request-URI del ACK para re-INVITE debe ser el Contact del 200 OK (RFC 3261 §17.1.1.3)
        explicitRequestUri: String? = null
    ): String {
        val defaultUri = when (callData.direction) {
            CallDirections.OUTGOING -> "sip:${callData.to}@${accountInfo.domain}"
            CallDirections.INCOMING -> {
                callData.remoteContactUri
                    ?.takeIf { it.isNotBlank() }
                    ?: "sip:${callData.from}@${accountInfo.domain}"
            }
        }
        val requestUri = explicitRequestUri ?: defaultUri
        val cseq = explicitCSeq ?: accountInfo.cseq

        val fromTag = when (callData.direction) {
            CallDirections.OUTGOING -> callData.inviteFromTag
            CallDirections.INCOMING -> callData.toTag ?: callData.inviteToTag
        }
        val toUri = when (callData.direction) {
            CallDirections.OUTGOING -> "sip:${callData.to}@${accountInfo.domain}"
            CallDirections.INCOMING -> "sip:${callData.from}@${accountInfo.domain}"
        }
        val toTag = when (callData.direction) {
            CallDirections.OUTGOING -> callData.inviteToTag
            CallDirections.INCOMING -> callData.fromTag ?: callData.inviteFromTag
        }

        return buildString {
            append("ACK $requestUri $SIP_VERSION\r\n")
            // ✅ CORREGIDO: Via usa localContactHost
            append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.localContactHost};branch=z9hG4bK${generateId()}\r\n")
            append("Max-Forwards: $MAX_FORWARDS\r\n")
            append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=$fromTag\r\n")
            append("To: <$toUri>;tag=$toTag\r\n")
            append("Call-ID: ${callData.callId}\r\n")
            append("CSeq: $cseq ACK\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
    }

    /**
     * Build SIP responses (200 OK, 180 Ringing, etc.)
     */
    private fun buildSipResponse(
        statusCode: Int,
        reasonPhrase: String,
        accountInfo: AccountInfo,
        callData: CallData,
        method: String = "INVITE",
        includeToTag: Boolean = true,
        includeContact: Boolean = false,
        contentType: String? = null,
        content: String = ""
    ): String {
        return buildString {
            append("$SIP_VERSION $statusCode $reasonPhrase\r\n")

            // CRÍTICO: Via header exactamente como vino del servidor
            append("Via: ${callData.via}\r\n")

            // CRÍTICO: From header exactamente como vino (incluyendo tag)
            append("From: <sip:${callData.from}@${accountInfo.domain}>;tag=${callData.fromTag}\r\n")

            // To header con nuestro tag si es necesario
            append("To: <sip:${accountInfo.username}@${accountInfo.domain}>")
            if (includeToTag && callData.toTag?.isNotEmpty() == true) {
                append(";tag=${callData.toTag}")
            }
            append("\r\n")

            append("Call-ID: ${callData.callId}\r\n")
            append("CSeq: ${callData.lastCSeqValue} $method\r\n")

            if (includeContact) {
                append("Contact: <sip:${accountInfo.localContactId}@${accountInfo.localContactHost};transport=ws;ob>\r\n")
            }

            contentType?.let {
                append("Content-Type: $it\r\n")
                append("Content-Length: ${content.length}\r\n\r\n")
                append(content)
            } ?: run {
                append("Content-Length: 0\r\n\r\n")
            }
        }
    }

    // Response builders
    fun buildTryingResponse(accountInfo: AccountInfo, callData: CallData): String =
        buildSipResponse(100, "Trying", accountInfo, callData, includeToTag = false)

    fun buildRingingResponse(accountInfo: AccountInfo, callData: CallData): String =
        buildSipResponse(180, "Ringing", accountInfo, callData, includeContact = true)

    fun buildInviteOkResponse(accountInfo: AccountInfo, callData: CallData): String =
        buildSipResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            accountInfo = accountInfo,
            callData = callData,
            includeContact = true,
            contentType = "application/sdp",
            content = callData.localSdp
        )

    fun buildDeclineResponse(accountInfo: AccountInfo, callData: CallData): String =
        buildSipResponse(603, "Decline", accountInfo, callData, includeContact = true)
            .replace(
                "Content-Length: 0\r\n\r\n",
                "Reason: SIP;cause=603;text=\"Decline\"\r\nContent-Length: 0\r\n\r\n"
            )

    fun buildBusyHereResponse(accountInfo: AccountInfo, callData: CallData): String =
        buildSipResponse(486, "Busy Here", accountInfo, callData, includeContact = true)

    fun buildRequestTerminatedResponse(accountInfo: AccountInfo, callData: CallData): String =
        buildSipResponse(487, "Request Terminated", accountInfo, callData)

    /**
     * Build generic OK responses for requests (BYE, CANCEL)
     */
    private fun buildGenericOkResponse(lines: List<String>): String {
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

    fun buildByeOkResponse(accountInfo: AccountInfo, lines: List<String>): String =
        buildGenericOkResponse(lines)

    fun buildCancelOkResponse(accountInfo: AccountInfo, lines: List<String>): String =
        buildGenericOkResponse(lines)

    /**
     * Build ACK for 487 response
     */
    fun buildAckFor487Response(
        accountInfo: AccountInfo,
        callData: CallData,
        lines: List<String>
    ): String {
        val toHeader = SipMessageParser.extractHeader(lines, "To")
        val fromHeader = SipMessageParser.extractHeader(lines, "From")
        val viaHeader = SipMessageParser.extractHeader(lines, "Via")
        val cseqValue = SipMessageParser.extractHeader(lines, "CSeq").split(" ")[0]
        val uri = "sip:${callData.to}@${accountInfo.domain}"

        return buildString {
            append("ACK $uri $SIP_VERSION\r\n")
            append("Via: $viaHeader\r\n")
            append("From: $fromHeader\r\n")
            append("To: $toHeader\r\n")
            append("Call-ID: ${callData.callId}\r\n")
            append("CSeq: $cseqValue ACK\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
    }

    /**
     * Build re-INVITE message
     */
    suspend fun buildReInviteMessage(
        accountInfo: AccountInfo,
        callData: CallData,
        sdp: String
    ): String {
        val builder = StringBuilder()
        val currentCSeq = accountInfo.incrementCSeq()

        when (callData.direction) {
            CallDirections.OUTGOING -> {
                val targetUri = "sip:${callData.to}@${accountInfo.domain}"
                builder.append("INVITE $targetUri $SIP_VERSION\r\n")
                // ✅ CORREGIDO: Via usa localContactHost
                builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.localContactHost};branch=z9hG4bK${generateId()}\r\n")
                builder.append("Max-Forwards: $MAX_FORWARDS\r\n")
                builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.inviteFromTag}\r\n")
                builder.append("To: <$targetUri>;tag=${callData.inviteToTag}\r\n")
            }

            CallDirections.INCOMING -> {
                val targetUri = if (callData.remoteContactUri?.isNotEmpty() == true) {
                    callData.remoteContactUri!!
                } else {
                    "sip:${callData.from}@${accountInfo.domain}"
                }
                builder.append("INVITE $targetUri $SIP_VERSION\r\n")
                // ✅ CORREGIDO: Via usa localContactHost
                builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.localContactHost};branch=z9hG4bK${generateId()}\r\n")
                builder.append("Max-Forwards: $MAX_FORWARDS\r\n")
                builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.toTag}\r\n")
                builder.append("To: <sip:${callData.from}@${accountInfo.domain}>;tag=${callData.fromTag}\r\n")
            }
        }

        builder.append("Call-ID: ${callData.callId}\r\n")
        builder.append("CSeq: $currentCSeq INVITE\r\n")
        builder.append("Contact: <sip:${accountInfo.localContactId}@${accountInfo.localContactHost};transport=ws;ob>\r\n")
        builder.append("Content-Type: application/sdp\r\n")
        builder.append("Content-Length: ${sdp.length}\r\n\r\n")
        builder.append(sdp)

        return builder.toString()
    }

    /**
     * Build INFO message for DTMF
     */
    suspend fun buildDtmfInfoMessage(
        accountInfo: AccountInfo,
        callData: CallData,
        dtmfEvent: Char,
        duration: Int
    ): String {
        val currentCSeq = accountInfo.incrementCSeq()

        try {
            val targetUri = getTargetUri(accountInfo, callData)
            val fromTag = if (callData.direction == CallDirections.OUTGOING) {
                callData.inviteFromTag
            } else {
                callData.inviteToTag
            }
            val toTag = if (callData.direction == CallDirections.OUTGOING) {
                callData.inviteToTag
            } else {
                callData.inviteFromTag
            }

            val normalizedDigit = when (dtmfEvent.lowercaseChar()) {
                'a', 'b', 'c', 'd' -> dtmfEvent.uppercaseChar()
                else -> dtmfEvent
            }

            val dtmfContent = buildString {
                append("Signal=${normalizedDigit}\r\n")
                append("Duration=${duration}\r\n")
            }

            val message = buildString {
                append("INFO $targetUri $SIP_VERSION\r\n")
                append("Via: ${callData.via}\r\n")
                append("Max-Forwards: $MAX_FORWARDS\r\n")

                if (callData.direction == CallDirections.OUTGOING) {
                    append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=$fromTag\r\n")
                    append("To: <$targetUri>;tag=$toTag\r\n")
                } else {
                    append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=$fromTag\r\n")
                    append("To: <sip:${callData.from}@${accountInfo.domain}>;tag=$toTag\r\n")
                }

                append("Call-ID: ${callData.callId}\r\n")
                append("CSeq: $currentCSeq INFO\r\n")
                append("Contact: <sip:${accountInfo.localContactId}@${accountInfo.localContactHost};transport=ws;ob>\r\n")
                append("User-Agent: ${accountInfo.userAgent.value}\r\n")
                append("Content-Type: application/dtmf-relay\r\n")
                append("Content-Length: ${dtmfContent.length}\r\n")
                append("\r\n")
                append(dtmfContent)
            }

            log.d(tag = "SipMessageBuilder") { "DTMF INFO message built for digit '$normalizedDigit'" }
            return message

        } catch (e: Exception) {
            log.e(tag = "SipMessageBuilder") { "Error building DTMF INFO message: ${e.message}" }
            throw e
        }
    }
}
