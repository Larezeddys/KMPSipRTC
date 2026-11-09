package com.eddyslarez.kmpsiprtc.platform

object LogRedactor {
    const val MASK = "[REDACTED]"

    private val authHeaderLine = Regex(
        "^(\\s*(?:Proxy-)?Authorization\\s*:).*$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )
    private val secretKeyValue = Regex(
        "(\"?(?:password|passwd|pwd|secret|access_token|refresh_token|push_?token|token|" +
            "pn-prid|pn-param|api_?key|credential)\"?\\s*[:=]\\s*)" +
            "(\"[^\"]*\"|'[^']*'|[^\\s,;&}\\])>\"']+)",
        RegexOption.IGNORE_CASE
    )
    private val bearer = Regex("\\bBearer\\s+[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE)
    private val digestResponse = Regex("(response\\s*=\\s*)\"[^\"]*\"", RegexOption.IGNORE_CASE)

    private val requestLine = Regex("([A-Z]{3,}) (?:sips?|tel):\\S+ SIP/2\\.0$")
    private val statusLine = Regex("SIP/2\\.0 (\\d{3})(?: (.*))?$")

    fun redact(message: String, keepSipMessages: Boolean = SipLogConfig.logFullSipMessages): String {
        val base = if (keepSipMessages) message else summarizeSipMessage(message)
        return redactSecrets(base)
    }

    fun redactSecrets(message: String): String {
        var out = authHeaderLine.replace(message) { it.groupValues[1] + " " + MASK }
        out = digestResponse.replace(out) { it.groupValues[1] + "\"" + MASK + "\"" }
        out = bearer.replace(out) { "Bearer $MASK" }
        out = secretKeyValue.replace(out) { match ->
            val value = match.groupValues[2]
            val quote = value.firstOrNull()?.takeIf { it == '"' || it == '\'' }
            if (quote != null) "${match.groupValues[1]}$quote$MASK$quote" else match.groupValues[1] + MASK
        }
        return out
    }

    /**
     * Sustituye el primer mensaje SIP completo del texto por un resumen: método o código de estado,
     * método del CSeq y hash del Call-ID. El texto anterior al mensaje se conserva.
     */
    fun summarizeSipMessage(message: String): String {
        if (!message.contains("SIP/2.0")) return message
        val lines = message.split('\n').map { it.trimEnd('\r', ' ') }
        for ((index, line) in lines.withIndex()) {
            val request = requestLine.find(line)
            val match = request ?: statusLine.find(line) ?: continue
            val headers = lines.drop(index + 1).takeWhile { it.isNotBlank() }
            if (headers.none { it.contains(':') }) return message

            val kind = if (request != null) {
                request.groupValues[1]
            } else {
                listOf(match.groupValues[1], match.groupValues[2]).filter { it.isNotBlank() }.joinToString(" ")
            }
            val cseq = headerValue(headers, "CSeq")
            val callId = headerValue(headers, "Call-ID") ?: headerValue(headers, "i")
            val summary = buildString {
                append("[SIP ").append(kind)
                if (cseq != null) append(" cseq=").append(cseq)
                if (callId != null) append(" call#").append(shortHash(callId))
                append(']')
            }
            val prefix = (lines.take(index) + line.substring(0, match.range.first))
                .joinToString("\n")
                .trimEnd()
            return if (prefix.isEmpty()) summary else "$prefix $summary"
        }
        return message
    }

    fun shortHash(value: String): String {
        var hash = 0x811c9dc5.toInt()
        for (ch in value) {
            hash = hash xor ch.code
            hash *= 0x01000193
        }
        return hash.toUInt().toString(16).padStart(8, '0')
    }

    private fun headerValue(headers: List<String>, name: String): String? =
        headers.firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
}
