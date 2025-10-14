package com.eddyslarez.kmpsiprtc.services.sip

import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.data.models.CallData
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.utils.generateId
import com.eddyslarez.kmpsiprtc.platform.log

object SipMessageBuilder {
    // Constants
    private const val SIP_VERSION = "SIP/2.0"
    private const val SIP_TRANSPORT = "WS"
    private const val MAX_FORWARDS = 70
    private const val DEFAULT_EXPIRES = 604800
    private const val UNREGISTER_EXPIRES = 0
    private const val TAG = "SipMessageBuilder"

    /**
     * Build REGISTER message with optional push notification support
     */
    suspend fun buildRegisterMessage(
        accountInfo: AccountInfo,
        callId: String,
        fromTag: String,
        isAppInBackground: Boolean,
        isAuthenticated: Boolean = false,
    ): String {
        val uri = "sip:${accountInfo.domain}"
        val builder = StringBuilder()

        // Log para debug
        log.d(tag = TAG) {
            "Building REGISTER message:" +
                    "\nAccount: ${accountInfo.username}@${accountInfo.domain}" +
                    "\nIs App In Background: $isAppInBackground" +
                    "\nToken: ${accountInfo.token}" +
                    "\nProvider: ${accountInfo.provider}" +
                    "\nUser Agent: ${accountInfo.userAgent}"
        }
        val currentCSeq = accountInfo.incrementCSeq()

        builder.append("REGISTER $uri $SIP_VERSION\r\n")
        builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.domain};branch=z9hG4bK${generateId()}\r\n")
        builder.append("Max-Forwards: $MAX_FORWARDS\r\n")
        builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=$fromTag\r\n")
        builder.append("To: <sip:${accountInfo.username}@${accountInfo.domain}>\r\n")
        builder.append("Call-ID: $callId\r\n")
        builder.append("CSeq: $currentCSeq REGISTER\r\n")
        builder.append("User-Agent: ${accountInfo.userAgent}\r\n")

        // Contact header based on mode
        builder.append("Contact: <sip:${accountInfo.username}@${accountInfo.domain}")

        if (isAppInBackground) {
            log.d(tag = TAG) { "Adding push notification parameters to Contact header" }
            builder.append(";pn-prid=${accountInfo.token};pn-provider=${accountInfo.provider}")
        } else {
            log.d(tag = TAG) { "Building Contact header WITHOUT push notification parameters" }
        }

        builder.append(";transport=ws>;expires=$DEFAULT_EXPIRES\r\n")
        builder.append("Expires: $DEFAULT_EXPIRES\r\n")

        // Authorization if needed
        if (isAuthenticated && accountInfo.authorizationHeader != null) {
            builder.append("Authorization: ${accountInfo.authorizationHeader}\r\n")
        }

        builder.append("Content-Length: 0\r\n\r\n")

        val finalMessage = builder.toString()

        // Log del mensaje final para verificar
        log.d(tag = TAG) {
            "Final REGISTER message Contact header contains push params: ${
                finalMessage.contains("pn-prid")
            }"
        }

        return finalMessage
    }


    /**
     * Build authenticated REGISTER message
     */
    suspend fun buildAuthenticatedRegisterMessage(
        accountInfo: AccountInfo,
        isAppInBackground: Boolean
    ): String {
        return buildRegisterMessage(
            accountInfo = accountInfo,
            callId = accountInfo.callId.value ?: generateId(),
            fromTag = accountInfo.fromTag.value ?: generateId(),
            isAppInBackground = isAppInBackground,
            isAuthenticated = true
        )
    }


    /**
     * Build unregister message (expires=0)
     */
    suspend fun buildUnregisterMessage(
        accountInfo: AccountInfo,
        callId: String,
        fromTag: String
    ): String {
        return buildRegisterMessage(
            accountInfo = accountInfo,
            callId = callId,
            fromTag = fromTag,
            isAppInBackground = false,
            isAuthenticated = accountInfo.authorizationHeader != null
        ).replace("Expires: $DEFAULT_EXPIRES", "Expires: $UNREGISTER_EXPIRES")
            .replace("expires=$DEFAULT_EXPIRES", "expires=$UNREGISTER_EXPIRES")
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
        val md5Hash= callData.md5Hash
        val currentCSeq = accountInfo.incrementCSeq()

        // Store branch for future reference
        callData.inviteViaBranch = branch
        callData.via = "$SIP_VERSION/$SIP_TRANSPORT ${accountInfo.domain};branch=$branch"

        val builder = StringBuilder()
        builder.append("INVITE $uri $SIP_VERSION\r\n")
        builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.domain};branch=$branch\r\n")
        builder.append("Max-Forwards: $MAX_FORWARDS\r\n")
        builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.inviteFromTag}\r\n")
        builder.append("To: <$uri>\r\n")
        builder.append("Call-ID: ${callData.callId}\r\n")
        builder.append("CSeq: $currentCSeq INVITE\r\n")
        builder.append("Contact: <sip:${accountInfo.username}@${accountInfo.domain};transport=ws>\r\n")


        if (md5Hash.isNotEmpty()) {
            builder.append("X-MD5: $md5Hash\r\n")
        }

        if (isAuthenticated && accountInfo.authorizationHeader != null) {
            builder.append("Authorization: ${accountInfo.authorizationHeader}\r\n")
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
    /**
     * Build call control messages (BYE, CANCEL, ACK) - CORREGIDO
     */
    private suspend fun  buildCallControlMessage(
        method: String,
        accountInfo: AccountInfo,
        callData: CallData,
        useOriginalVia: Boolean = false
    ): String {
        val builder = StringBuilder()
        val currentCSeq = accountInfo.incrementCSeq()
        log.d(tag = TAG) { "currentCSeq $currentCSeq" }

        // CRÍTICO: URI y headers diferentes según dirección de llamada
        when (callData.direction) {
            CallDirections.OUTGOING -> {
                // Para llamadas salientes, el target es el destinatario
                val targetUri = "sip:${callData.to}@${accountInfo.domain}"
                builder.append("$method $targetUri $SIP_VERSION\r\n")

                // Via header
                if (useOriginalVia && method == "CANCEL" && callData.inviteViaBranch.isNotEmpty()) {
                    builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.domain};branch=${callData.inviteViaBranch}\r\n")
                } else {
                    val branch = if (method == "CANCEL") callData.inviteViaBranch else "z9hG4bK${generateId()}"
                    builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.domain};branch=$branch\r\n")
                }

                builder.append("Max-Forwards: $MAX_FORWARDS\r\n")
                builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.inviteFromTag}\r\n")

                // To header - CRÍTICO: incluir toTag si existe
                if (callData.inviteToTag.isNotEmpty() && method != "CANCEL") {
                    builder.append("To: <$targetUri>;tag=${callData.inviteToTag}\r\n")
                } else {
                    builder.append("To: <$targetUri>\r\n")
                }
            }

            CallDirections.INCOMING -> {
                // CRÍTICO: Para llamadas entrantes, usar el contactUri del caller
                val targetUri = if (callData.remoteContactUri?.isNotEmpty() == true) {
                    callData.remoteContactUri!!
                } else {
                    "sip:${callData.from}@${accountInfo.domain}"
                }

                builder.append("$method $targetUri $SIP_VERSION\r\n")

                // Via header - usar nuevo branch
                val branch = "z9hG4bK${generateId()}"
                builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.domain};branch=$branch\r\n")
                builder.append("Max-Forwards: $MAX_FORWARDS\r\n")

                // CRÍTICO: Intercambiar From/To para llamadas entrantes
                builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.toTag}\r\n")
                builder.append("To: <sip:${callData.from}@${accountInfo.domain}>;tag=${callData.fromTag}\r\n")
            }
        }

        builder.append("Call-ID: ${callData.callId}\r\n")

        val cseqValue = if (method == "CANCEL") {
            val originalCseqHeader = SipMessageParser.extractHeader(
                callData.originalCallInviteMessage.split("\r\n"), "CSeq"
            )
            log.d(tag = TAG) { "originalCseqHeader $originalCseqHeader" }

            originalCseqHeader.split(" ")[0].trim().toInt()
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
//    /**
//     * Build CANCEL message - CORREGIDO para usar headers exactos del INVITE original
//     */
//    fun buildCancelMessage(accountInfo: AccountInfo, callData: CallData): String {
//        try {
//            log.d(tag = "SipMessageBuilder") { "Building CANCEL for call: ${callData.callId}" }
//
//            // CRÍTICO: Verificar que tenemos el mensaje INVITE original
//            if (callData.originalCallInviteMessage.isEmpty()) {
//                log.e(tag = "SipMessageBuilder") { "ERROR: No original INVITE message stored for CANCEL" }
//                throw IllegalStateException("Cannot build CANCEL without original INVITE")
//            }
//
//            val originalLines = callData.originalCallInviteMessage.split("\r\n")
//            val originalFirstLine = originalLines.firstOrNull() ?: throw IllegalStateException("Invalid original INVITE")
//
//            // Extraer headers exactos del INVITE original
//            val originalVia = SipMessageParser.extractHeader(originalLines, "Via")
//            val originalFrom = SipMessageParser.extractHeader(originalLines, "From")
//            val originalTo = SipMessageParser.extractHeader(originalLines, "To")
//            val originalCallId = SipMessageParser.extractHeader(originalLines, "Call-ID")
//            val originalCSeq = SipMessageParser.extractHeader(originalLines, "CSeq")
//
//            // CRÍTICO: Extraer solo el número de secuencia del CSeq original
//            val originalCSeqNumber = (originalCSeq.split(" ").firstOrNull()?.trim() + 1)
//                ?: throw IllegalStateException("Invalid CSeq in original INVITE")
//
//            // CRÍTICO: Extraer URI del INVITE original
//            val requestUri = originalFirstLine.substringAfter("INVITE ").substringBefore(" SIP/2.0").trim()
//
//            log.d(tag = "SipMessageBuilder") {
//                "CANCEL details - URI: $requestUri, CSeq: $originalCSeqNumber, Via: ${originalVia.take(50)}..."
//            }
//
//            return buildString {
//                // Request line - EXACTAMENTE la misma URI que el INVITE
//                append("CANCEL $requestUri SIP/2.0\r\n")
//
//                // CRÍTICO: Headers EXACTAMENTE iguales al INVITE original
//                append("Via: $originalVia\r\n")
//                append("Max-Forwards: $MAX_FORWARDS\r\n")
//                append("From: $originalFrom\r\n")
//                append("To: $originalTo\r\n")  // SIN tag en CANCEL
//                append("Call-ID: $originalCallId\r\n")
//                append("CSeq: $originalCSeqNumber CANCEL\r\n")  // Mismo número, método CANCEL
//                append("Content-Length: 0\r\n\r\n")
//            }
//
//        } catch (e: Exception) {
//            log.e(tag = "SipMessageBuilder") { "Error building CANCEL: ${e.message}" }
//            throw e
//        }
//    }

    /**
     * Build ACK message
     */
    fun buildAckMessage(accountInfo: AccountInfo, callData: CallData): String {
        val target = callData.to
        val uri = "sip:${target}@${accountInfo.domain}"

        return buildString {
            append("ACK $uri $SIP_VERSION\r\n")
            append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.domain};branch=z9hG4bK${generateId()}\r\n")
            append("Max-Forwards: $MAX_FORWARDS\r\n")
            append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.inviteFromTag}\r\n")
            append("To: <$uri>;tag=${callData.inviteToTag}\r\n")
            append("Call-ID: ${callData.callId}\r\n")
            append("CSeq: ${accountInfo.cseq} ACK\r\n")
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

            // CRÍTICO: Via header exactamente como vino
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

            // CRÍTICO: CSeq debe coincidir con el request
            append("CSeq: ${callData.lastCSeqValue} $method\r\n")

            if (includeContact) {
                append("Contact: <sip:${accountInfo.username}@${accountInfo.domain};transport=ws>\r\n")
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

    // Response builders using the common function
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
     * Build generic OK responses for requests
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
                builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.domain};branch=z9hG4bK${generateId()}\r\n")
                builder.append("Max-Forwards: $MAX_FORWARDS\r\n")
                builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.inviteFromTag}\r\n")
                builder.append("To: <$targetUri>;tag=${callData.inviteToTag}\r\n")
            }

            CallDirections.INCOMING -> {
                // CRÍTICO: Para re-INVITE en llamadas entrantes
                val targetUri = if (callData.remoteContactUri?.isNotEmpty() == true) {
                    callData.remoteContactUri!!
                } else {
                    "sip:${callData.from}@${accountInfo.domain}"
                }

                builder.append("INVITE $targetUri $SIP_VERSION\r\n")
                builder.append("Via: $SIP_VERSION/$SIP_TRANSPORT ${accountInfo.domain};branch=z9hG4bK${generateId()}\r\n")
                builder.append("Max-Forwards: $MAX_FORWARDS\r\n")
                builder.append("From: <sip:${accountInfo.username}@${accountInfo.domain}>;tag=${callData.toTag}\r\n")
                builder.append("To: <sip:${callData.from}@${accountInfo.domain}>;tag=${callData.fromTag}\r\n")
            }
        }

        builder.append("Call-ID: ${callData.callId}\r\n")
        builder.append("CSeq: $currentCSeq INVITE\r\n")
        builder.append("Contact: <sip:${accountInfo.username}@${accountInfo.domain};transport=ws>\r\n")
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

            // Normalizar el dígito DTMF
            val normalizedDigit = when (dtmfEvent.lowercaseChar()) {
                'a', 'b', 'c', 'd' -> dtmfEvent.uppercaseChar()
                else -> dtmfEvent
            }

            // Contenido DTMF según RFC 2833
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
                append("Contact: <sip:${accountInfo.username}@${accountInfo.domain};transport=ws>\r\n")
                append("User-Agent: ${accountInfo.userAgent}\r\n")
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