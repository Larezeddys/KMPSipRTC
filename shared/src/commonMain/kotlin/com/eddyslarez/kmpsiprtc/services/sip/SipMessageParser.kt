package com.eddyslarez.kmpsiprtc.services.sip

import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.platform.log

object SipMessageParser {
    private const val TAG = "SipMessageParser"

    /**
     * Logs an incoming SIP message
     */
    fun logIncomingMessage(message: String) {
        log.d(tag = TAG) { "\n=== SIP MESSAGE RECEIVED ===" }
        log.d(tag = TAG) { message }
        log.d(tag = TAG) { "=== END OF MESSAGE ===" }
    }

    /**
     * Extracts the reason phrase from a SIP response status line, e.g. "SIP/2.0 403 Forbidden"
     */
    fun extractStatusReason(message: String): String {
        val statusLine = message.lines().firstOrNull { it.startsWith("SIP/2.0") }
        return statusLine?.substringAfter(" ", "")?.substringAfter(" ", "")?.trim() ?: "Unknown error"
    }

    /**
     * Extrae el Call-ID de un mensaje SIP
     */
    fun extractCallId(message: String): String {
        return try {
            val lines = message.lines()
            extractHeader(lines, "Call-ID")
        } catch (_: Exception) {
            ""
        }
    }

    fun parseRetryAfter(response: String): Int? {
        val regex = Regex("(?i)Retry-After:\\s*(\\d+)")
        return regex.find(response)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Extracts the branch parameter from a Via header
     */
    fun extractBranchFromVia(viaHeader: String): String {
        val branchPattern = ";branch=([^;\\s]+)".toRegex()
        val matchResult = branchPattern.find(viaHeader)
        return matchResult?.groupValues?.get(1) ?: ""
    }


    /**
     * Extracts and updates the sequence number (CSeq) if present
     */
    suspend fun updateCSeqIfPresent(lines: List<String>, accountInfo: AccountInfo) {
        lines.find { it.startsWith("CSeq:", ignoreCase = true) }?.let { cseqLine ->
            val parts = cseqLine.split("\\s+".toRegex())
            if (parts.size >= 2) {
                parts[1].toIntOrNull()?.let { seqNum ->
                    // CAMBIO: Usar método thread-safe para actualizar CSeq
                    val updated = accountInfo.updateCSeqFromExternal(seqNum, "SIP_MESSAGE")
                    if (updated) {
                        log.d(tag = TAG) { "[OK] Updated accountInfo.cseq = $seqNum" }
                    } else {
                        log.d(tag = TAG) { "CSeq $seqNum not applied (current: ${accountInfo.cseq})" }
                    }
                }
            }
        }
    }

    fun isSdpOnHold(sdp: String): Boolean {
        return sdp.contains("a=sendonly", ignoreCase = true) ||
                sdp.contains("a=inactive", ignoreCase = true)
    }

    /**
     * Extracts the method from the CSeq header
     */
    fun extractMethodFromCSeq(message: String, lines: List<String>): String {
        val cseqLine = lines.find { it.startsWith("CSeq:") }
        val cseqParts = cseqLine?.substringAfter("CSeq:")?.trim()?.split(" ")
        return cseqParts?.getOrNull(1) ?: "REGISTER"
    }

    /**
     * Extracts a specific header from message lines
     */
    fun extractHeader(lines: List<String>, headerName: String): String {
        val directHeader = lines.firstOrNull { it.startsWith("$headerName:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
        if (!directHeader.isNullOrEmpty()) return directHeader

        // Soporte de cabeceras compactas SIP (RFC 3261)
        if (headerName.equals("Call-ID", ignoreCase = true)) {
            val compact = lines.firstOrNull { it.startsWith("i:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
            if (!compact.isNullOrEmpty()) return compact
        }

        return ""
    }

    /**
     * Extracts the SDP content from a message
     */
    fun extractSdpContent(message: String): String {
        val parts = message.split("\r\n\r\n", limit = 2)
        val sdp = if (parts.size > 1) parts[1] else ""
        return sdp
    }

    /**
     * Extracts the tag from a From or To header
     */
    fun extractTag(header: String): String {
        val tagMatch = Regex("""tag=([^;]+)""").find(header)
        return tagMatch?.groupValues?.get(1) ?: ""
    }

    /**
     * Extracts the URI from a Contact header
     */
    fun extractUriFromContact(contact: String): String {
        val uriMatch = Regex("<([^>]+)>").find(contact)
        return uriMatch?.groupValues?.get(1) ?: ""
    }

    /**
     * Extracts the display name from a From or To header
     */
    fun extractDisplayName(header: String): String {
        val nameMatch = Regex("""\"([^\"]+)\"""").find(header)
        return nameMatch?.groupValues?.get(1) ?: ""
    }

    /**
     * Extracts the URI from a From or To header
     */
    fun extractUriFromHeader(header: String): String {
        val uriMatch = Regex("<([^>]+)>").find(header)
        return uriMatch?.groupValues?.get(1) ?: ""
    }

    /**
     * Extracts the user from a SIP URI
     */
    fun extractUserFromUri(uri: String): String {
        val userMatch = Regex("""sip:([^@]+)@""").find(uri)
        return userMatch?.groupValues?.get(1) ?: uri
    }

    /**
     * Extracts the expiration value from a response
     */
    fun extractExpiresValue(message: String): Int {
        // First look in the Expires header
        val expiresMatch = Regex("Expires:\\s*(\\d+)").find(message)
        val expiresHeader = expiresMatch?.groupValues?.get(1)?.toIntOrNull()

        if (expiresHeader != null) {
            return expiresHeader
        }

        // If no Expires header, look in Contact: <...>;expires=X
        val contactExpiresMatch = Regex("Contact:.*?expires=(\\d+)").find(message)
        return contactExpiresMatch?.groupValues?.get(1)?.toIntOrNull() ?: 3600 // Default 1 hour
    }
}
