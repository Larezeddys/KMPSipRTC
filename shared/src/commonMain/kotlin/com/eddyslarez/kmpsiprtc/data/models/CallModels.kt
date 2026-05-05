package com.eddyslarez.kmpsiprtc.data.models

import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.unified.CallType
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

@Serializable
enum class CallDirections {
    INCOMING,
    OUTGOING
}

@Serializable
enum class CallTypes {
    SUCCESS,
    MISSED,
    DECLINED,
    ABORTED
}

@Serializable
enum class RegistrationState {
    PROGRESS,
    OK,
    CLEARED,
    NONE,
    IN_PROGRESS,
    FAILED
}

/**
 * Datos de una llamada SIP activa. Intencionalmente mutable (`var` properties)
 * porque MultiCallManager comparte la misma referencia entre componentes
 * y los campos SIP se actualizan durante el ciclo de vida de la llamada.
 */
@Serializable
data class CallData @OptIn(ExperimentalTime::class) constructor(
    var callId: String = "",
    val from: String = "",
    val to: String = "",
    val direction: CallDirections = CallDirections.OUTGOING,
    val startTime: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
    val callType: CallType = CallType.SIP_EXTERNAL,
    val roomId: String? = null,
    var toTag: String? = null,
    var fromTag: String? = null,
    var remoteContactUri: String? = null,
    var remoteContactParams: Map<String, String> = emptyMap(),
    var remoteDisplayName: String = "",
    var inviteFromTag: String = "",
    var inviteToTag: String = "",
    var remoteSdp: String = "",
    var localSdp: String = "",
    var inviteViaBranch: String = "",
    var via: String = "",
    var originalInviteMessage: String = "",
    var originalCallInviteMessage: String = "",
    var isOnHold: Boolean? = null,
    var lastCSeqValue: Int = 0,
    var sipName: String = "",
    val md5Hash: String = "",
    var isCallback: Boolean = false,
    var assertedIdentity: String? = null,  // Valor del header P-Asserted-Identity
    var rawToUri: String? = null,           // To URI original (.invalid) para debugging
    var rawFromUri: String? = null          // From URI original completo
) {
    fun storeInviteMessage(message: String) {
        originalInviteMessage = message
    }

    fun getRemoteParty(): String {
        val remote = when (direction) {
            CallDirections.OUTGOING -> to
            CallDirections.INCOMING -> from
        }
        log.d("CallData") { "getRemoteParty: direction=$direction, from=$from, to=$to, remote=$remote" }
        return remote
    }

    fun getLocalParty(): String {
        val local = when (direction) {
            CallDirections.OUTGOING -> from
            CallDirections.INCOMING -> to
        }
        log.d("CallData") { "getLocalParty: direction=$direction, from=$from, to=$to, local=$local" }
        return local
    }

    override fun toString(): String {
        return "CallData(id=$callId, $from→$to, dir=$direction, started=$startTime, " +
                "fromTag=$fromTag, toTag=$toTag)"
    }
}

@Serializable
data class CallLog(
    val id: String,
    val direction: CallDirections,
    val to: String,
    val formattedTo: String,
    val from: String,
    val formattedFrom: String,
    val contact: String?,
    val formattedStartDate: String,
    val duration: Int, // in seconds
    val callType: CallTypes,
    val localAddress: String
)

@Serializable
data class DtmfRequest(
    val digit: Char,
    val duration: Int = 160,
    val useInfo: Boolean = true
)

@Serializable
data class DtmfQueueStatus(
    val queueSize: Int,
    val isProcessing: Boolean,
    val pendingDigits: String
)
//
//enum class AppLifecycleEvent {
//    EnterBackground,
//    FinishedLaunching,
//    EnterForeground,
//    WillTerminate,
//    ProtectedDataAvailable,
//    ProtectedDataWillBecomeUnavailable
//}
//
//interface AppLifecycleListener {
//    fun onEvent(event: AppLifecycleEvent)
//}
