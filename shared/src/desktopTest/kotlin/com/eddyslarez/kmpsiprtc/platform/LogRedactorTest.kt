package com.eddyslarez.kmpsiprtc.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRedactorTest {

    private val register = listOf(
        "REGISTER sip:mcn.ru SIP/2.0",
        "Via: SIP/2.0/WSS df7jal23ls0d.invalid;branch=z9hG4bK776",
        "From: <sip:74951234567@mcn.ru>;tag=abc",
        "To: <sip:74951234567@mcn.ru>",
        "Call-ID: 9f2c1d7e-secret-call",
        "CSeq: 2 REGISTER",
        "Authorization: Digest username=\"74951234567\", realm=\"mcn.ru\", nonce=\"n\", response=\"d41d8cd98f\"",
        "Contact: <sip:x@y;transport=ws;pn-prid=APNS-TOKEN-123;pn-provider=apns>",
        "",
        ""
    ).joinToString("\r\n")

    @Test
    fun fullSipMessageIsSummarised() {
        val out = LogRedactor.redact("Sending: $register", keepSipMessages = false)
        assertTrue(out.startsWith("Sending: [SIP REGISTER cseq=2 REGISTER call#"), out)
        assertFalse(out.contains("74951234567"))
        assertFalse(out.contains("9f2c1d7e-secret-call"))
        assertFalse(out.contains("APNS-TOKEN-123"))
    }

    @Test
    fun statusLineIsSummarisedWithReason() {
        val response = "SIP/2.0 401 Unauthorized\r\nCall-ID: abc\r\nCSeq: 1 REGISTER\r\n\r\n"
        val out = LogRedactor.redact(response, keepSipMessages = false)
        assertEquals("[SIP 401 Unauthorized cseq=1 REGISTER call#${LogRedactor.shortHash("abc")}]", out)
    }

    @Test
    fun debugModeKeepsMessageButMasksCredentials() {
        val out = LogRedactor.redact(register, keepSipMessages = true)
        assertTrue(out.contains("CSeq: 2 REGISTER"))
        assertTrue(out.contains("Authorization: ${LogRedactor.MASK}"))
        assertFalse(out.contains("d41d8cd98f"))
        assertFalse(out.contains("APNS-TOKEN-123"))
    }

    @Test
    fun keyValueSecretsAreMasked() {
        val out = LogRedactor.redactSecrets(
            "login password=hunter2, pushToken=fcm:XYZ; {\"access_token\":\"eyJabc\"} Bearer eyJhbGciOi.x.y"
        )
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("fcm:XYZ"))
        assertFalse(out.contains("eyJabc"))
        assertFalse(out.contains("eyJhbGciOi"))
        assertTrue(out.contains("\"access_token\":\"${LogRedactor.MASK}\""))
    }

    @Test
    fun plainMessagesAreUntouched() {
        val text = "Registration state changed to OK for account #3"
        assertEquals(text, LogRedactor.redact(text, keepSipMessages = false))
        assertEquals("Sending INVITE (SIP/2.0)", LogRedactor.redact("Sending INVITE (SIP/2.0)", keepSipMessages = false))
    }

    @Test
    fun levelFilter() {
        val previous = SipLogConfig.minLevel
        try {
            SipLogConfig.minLevel = SipLogLevel.WARN
            assertFalse(SipLogConfig.isEnabled(SipLogLevel.DEBUG))
            assertFalse(SipLogConfig.isEnabled(SipLogLevel.INFO))
            assertTrue(SipLogConfig.isEnabled(SipLogLevel.WARN))
            assertTrue(SipLogConfig.isEnabled(SipLogLevel.ERROR))
            SipLogConfig.minLevel = SipLogLevel.NONE
            assertFalse(SipLogConfig.isEnabled(SipLogLevel.ERROR))
        } finally {
            SipLogConfig.minLevel = previous
        }
    }
}
