package com.eddyslarez.kmpsiprtc.services.calls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Propiedad del audio por llamada, independiente del modo de registro PushKit. */
@OptIn(ExperimentalTime::class)
internal class IncomingCallSystemAudioRegistry(
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    data class IncomingCall(val callId: String, val callerNumber: String, val account: String)

    private data class Entry(
        val callerNumber: String?,
        val account: String?,
        val createdAt: Long,
        val sipCallId: String?,
    )

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())

    /** Con numero registra un reporte previo al INVITE; sin numero confirma el SIP Call-ID. */
    fun set(callId: String, managed: Boolean, callerNumber: String? = null, account: String? = null) {
        if (callId.isBlank()) return
        val now = nowMillis()
        entries.update { current ->
            val live = current.withoutExpiredPending(now)
            if (!managed) {
                live.filter { (key, entry) -> key != callId && entry.sipCallId != callId }
            } else if (callerNumber == null) {
                live + (callId to Entry(null, null, now, callId))
            } else if (callId in live) {
                // Un push repetido no rearma un pendiente ya consumido por otro INVITE.
                live
            } else {
                live + (callId to Entry(callerNumber, account, now, null))
            }
        }
    }

    /** Consume una sola vez el reporte pendiente antes de publicar INCOMING_RECEIVED. */
    fun bindIncomingCall(
        callId: String,
        callerNumber: String,
        account: String,
        allowSinglePendingFallback: Boolean = false,
    ): Boolean {
        while (true) {
            val previous = entries.value
            val live = previous.withoutExpiredPending(nowMillis())
            if (live.values.any { it.sipCallId == callId }) {
                if (entries.compareAndSet(previous, live)) return true
                continue
            }
            val pending = live.filterValues { it.sipCallId == null }
            val match = pending.entries.firstOrNull { it.key == callId }
                ?: pending.entries.firstOrNull { (_, entry) ->
                    numbersMatch(entry.callerNumber, callerNumber) && accountsMatch(entry.account, account)
                }
                ?: pending.entries.singleOrNull()?.takeIf {
                    allowSinglePendingFallback && accountsMatch(it.value.account, account)
                }
            val next = if (match == null) live
                else live + (match.key to match.value.copy(sipCallId = callId))
            if (entries.compareAndSet(previous, next)) return match != null
        }
    }

    fun isManaged(callId: String): Boolean = getOwner(callId) != null

    /** Un push tardio solo puede tomar una entrante actual identificada sin ambiguedad. */
    fun bindPendingToExisting(owner: String, incomingCalls: List<IncomingCall>): String? {
        while (true) {
            val previous = entries.value
            val live = previous.withoutExpiredPending(nowMillis())
            val pending = live[owner]
            if (pending == null || pending.sipCallId != null) {
                if (entries.compareAndSet(previous, live)) return pending?.sipCallId
                continue
            }
            val eligible = incomingCalls.filter { call ->
                live.values.none { it.sipCallId == call.callId }
            }
            val match = eligible.firstOrNull { it.callId == owner }
                ?: eligible.filter { call ->
                    numbersMatch(pending.callerNumber, call.callerNumber) &&
                        accountsMatch(pending.account, call.account)
                }.singleOrNull()
            val next = if (match == null) live
                else live + (owner to pending.copy(sipCallId = match.callId))
            if (entries.compareAndSet(previous, next)) return match?.callId
        }
    }

    /** Clave original del reporte consumido; permite asociar CallKit sin adivinar por numero. */
    fun getOwner(callId: String): String? {
        entries.update { it.withoutExpiredPending(nowMillis()) }
        return entries.value.entries.firstOrNull { it.value.sipCallId == callId }?.key
    }

    fun clear() { entries.value = emptyMap() }

    private fun Map<String, Entry>.withoutExpiredPending(now: Long): Map<String, Entry> =
        filterValues { it.sipCallId != null || now - it.createdAt < 60_000L }

    private fun numbersMatch(left: String?, right: String): Boolean {
        if (left.isNullOrBlank() || right.isBlank() || left.equals("unknown", ignoreCase = true)) return false
        if (left.trim().equals(right.trim(), ignoreCase = true)) return true
        val leftDigits = left.filter(Char::isDigit)
        val rightDigits = right.filter(Char::isDigit)
        if (leftDigits.isEmpty() || rightDigits.isEmpty()) return false
        if (leftDigits == rightDigits) return true
        return minOf(leftDigits.length, rightDigits.length) >= 10 &&
            (leftDigits.endsWith(rightDigits) || rightDigits.endsWith(leftDigits))
    }

    private fun accountsMatch(left: String?, right: String): Boolean {
        fun normalize(value: String?): String? = value?.trim()?.lowercase()
            ?.removePrefix("sip:")?.substringBefore(';')
            ?.takeIf { it.isNotBlank() && it != "unknown" }
        val expected = normalize(left) ?: return true
        val actual = normalize(right) ?: return false
        return if ('@' in expected) expected == actual else expected == actual.substringBefore('@')
    }
}

internal val incomingCallSystemAudio = IncomingCallSystemAudioRegistry()
