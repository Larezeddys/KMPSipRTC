package com.eddyslarez.kmpsiprtc.data.models

import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.webSocket.MultiplatformWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.random.Random
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class AccountInfo(
    val username: String,
    var password: String,
    val domain: String
) {
    private val TAG = "AccountInfo"
    private val accountMutex = Mutex()
    private val cseqMutex = Mutex()

    // 🔁 Reemplazo de atomic por MutableStateFlow
    val reconnectCount = MutableStateFlow(0)
    val callId = MutableStateFlow<String?>(null)
    val fromTag = MutableStateFlow<String?>(null)
    val toTag = MutableStateFlow<String?>(null)
    private val _cseq = MutableStateFlow(1)
    fun canRegister(): Boolean = username.isNotEmpty() && password.isNotEmpty() && domain.isNotEmpty()

    val fromHeader = MutableStateFlow<String?>(null)
    val toHeader = MutableStateFlow<String?>(null)
    val viaHeader = MutableStateFlow<String?>(null)
    val fromUri = MutableStateFlow<String?>(null)
    val toUri = MutableStateFlow<String?>(null)
    val remoteContact = MutableStateFlow<String?>(null)
    val userAgent = MutableStateFlow<String?>(null)

    val authorizationHeader = MutableStateFlow<String?>(null)
    val challengeNonce = MutableStateFlow<String?>(null)
    val realm = MutableStateFlow<String?>(null)
    val authRetryCount = MutableStateFlow(0)
    val method = MutableStateFlow<String?>(null)

    val useWebRTCFormat = MutableStateFlow(false)
    val remoteSdp = MutableStateFlow<String?>(null)
    val iceUfrag = MutableStateFlow<String?>(null)
    val icePwd = MutableStateFlow<String?>(null)
    val dtlsFingerprint = MutableStateFlow<String?>(null)
    val setupRole = MutableStateFlow<String?>(null)

    // 👇 Este es el cambio que te interesa
    val currentCallData = MutableStateFlow<CallData?>(null)

    val isRegistered = MutableStateFlow(false)
    val isCallConnected = MutableStateFlow(false)
    val hasIncomingCall = MutableStateFlow(false)
    val callStartTime = MutableStateFlow(0L)

    val token = MutableStateFlow("")
    val provider = MutableStateFlow("")

    val lastCseqUpdate = MutableStateFlow(0L)
    val lastAuthReset = MutableStateFlow(0L)
    val lastCallReset = MutableStateFlow(0L)

    val reconnectionJob = MutableStateFlow<Job?>(null)
    val reconnectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val MAX_CSEQ_VALUE = Int.MAX_VALUE
        private const val MIN_CSEQ_VALUE = 1
        private const val CSEQ_RESET_THRESHOLD = MAX_CSEQ_VALUE - 1000
    }

    val cseq: Int get() = _cseq.value

    suspend fun incrementCSeq(): Int = cseqMutex.withLock {
        var cseqVal = _cseq.value
        if (cseqVal >= CSEQ_RESET_THRESHOLD) {
            log.w(TAG) { "CSeq limit reached, resetting for ${getAccountIdentity()}" }
            cseqVal = MIN_CSEQ_VALUE
        } else {
            cseqVal++
        }
        _cseq.value = cseqVal
        lastCseqUpdate.value = Clock.System.now().toEpochMilliseconds()
        log.d(TAG) { "CSeq incremented to $cseqVal" }
        return cseqVal
    }


    suspend fun resetCSeq() = cseqMutex.withLock {
        _cseq.value = MIN_CSEQ_VALUE
        lastCseqUpdate.value = Clock.System.now().toEpochMilliseconds()
        log.d(TAG) { "CSeq reset for ${getAccountIdentity()}" }
    }

    suspend fun resetAuthState() = accountMutex.withLock {
        authRetryCount.value = 0
        challengeNonce.value = null
        authorizationHeader.value = null
        realm.value = null
        method.value = null
        lastAuthReset.value = Clock.System.now().toEpochMilliseconds()
        log.d(TAG) { "Auth state reset" }
    }

    /**
     * NUEVO: Actualiza CSeq desde fuente externa (ej. mensaje SIP recibido)
     * Solo actualiza si el nuevo valor es mayor que el actual
     */
    suspend fun updateCSeqFromExternal(newCSeq: Int, source: String = "external") = cseqMutex.withLock {
        try {
            // Validar que el nuevo valor es válido
            if (newCSeq !in MIN_CSEQ_VALUE..MAX_CSEQ_VALUE) {
                log.w(tag = TAG) { "Invalid external CSeq $newCSeq from $source for ${getAccountIdentity()}" }
                return@withLock false
            }

            // Solo actualizar si es mayor (para mantener secuencia correcta)
            if (newCSeq > _cseq.value) {
                val oldCSeq = _cseq.value
                _cseq.value = newCSeq
                lastCseqUpdate.value = Clock.System.now().toEpochMilliseconds()

                log.d(tag = TAG) { "CSeq updated from $source: $oldCSeq -> ${_cseq.value} for ${getAccountIdentity()}" }
                return@withLock true
            } else {
                log.d(tag = TAG) { "External CSeq $newCSeq from $source <= current ${_cseq.value}, not updating" }
                return@withLock false
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error updating CSeq from $source: ${e.message}" }
            return@withLock false
        }
    }

    suspend fun resetCallState() = accountMutex.withLock {
        isCallConnected.value = false
        hasIncomingCall.value = false
        callStartTime.value = 0L
        currentCallData.value = null
        lastCallReset.value = Clock.System.now().toEpochMilliseconds()
        log.d(TAG) { "Call state reset" }
    }

    suspend fun resetAllState() = accountMutex.withLock {
        resetCallState()
        resetAuthState()
        resetCSeq()
        isRegistered.value = false
        reconnectCount.value = 0
        log.d(TAG) { "Account state fully reset" }
    }

    fun getAccountIdentity(): String = "$username@$domain"

    fun generateCallId(): String {
        val timestamp = Clock.System.now().toEpochMilliseconds()
        val randomPart = (1..16).joinToString("") { Random.nextInt(16).toString(16) }
        return "${randomPart}_$timestamp@$domain"
    }

    suspend fun cleanup() = accountMutex.withLock {
        reconnectionJob.value?.cancel()

//        webSocketClient.value?.let { ws ->
//            try {
//                ws.stopPingTimer()
//                ws.stopRegistrationRenewalTimer()
//                ws.close()
//            } catch (e: Exception) {
//                log.w(TAG) { "Error closing WS: ${e.message}" }
//            }
//        }
//        webSocketClient.value = null
        resetAllState()
        log.d(TAG) { "Cleanup complete for ${getAccountIdentity()}" }
    }

    override fun toString(): String {
        return "AccountInfo(${getAccountIdentity()}, reg=${isRegistered.value}, cseq=${_cseq.value}})"
    }
}
//fun AccountInfo.isWebSocketHealthy(): Boolean {
//    val ws = this.webSocketClient.value ?: return false
//    return ws.isConnected() && this.isRegistered.value
//}