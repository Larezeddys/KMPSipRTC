package com.eddyslarez.kmpsiprtc.services.unified

import com.eddyslarez.kmpsiprtc.core.CallManager
import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.matrix.MatrixManager


/**
 * Router que decide si usar SIP o Matrix para una llamada
 */
class UnifiedCallRouter(
    private val sipCallManager: CallManager,
    private val matrixManager: MatrixManager
) {
    private val TAG = "UnifiedCallRouter"

    /**
     * Determina el tipo de llamada basándose en el destino
     */
    fun determineCallType(destination: String): CallType {
        return when {
            // Si es un Matrix ID (@user:domain), usar Matrix
            destination.startsWith("@") && destination.contains(":") -> {
                log.d { "Routing to Matrix for: $destination" }
                CallType.MATRIX_INTERNAL
            }
            // Si es un room ID (!room:domain), usar Matrix
            destination.startsWith("!") && destination.contains(":") -> {
                log.d { "Routing to Matrix room for: $destination" }
                CallType.MATRIX_INTERNAL
            }
            // Todo lo demás va por SIP
            else -> {
                log.d { "Routing to SIP for: $destination" }
                CallType.SIP_EXTERNAL
            }
        }
    }

    /**
     * Realiza llamada usando el protocolo correcto
     */
    suspend fun makeCall(
        destination: String,
        isVideo: Boolean = false,
        accountInfo: AccountInfo? = null
    ): Result<UnifiedCallInfo> {
        val callType = determineCallType(destination)

        return when (callType) {
            CallType.MATRIX_INTERNAL -> {
                // Llamada Matrix
                if (isVideo) {
                    matrixManager.startVideoCall(destination).map { matrixCall ->
                        UnifiedCallInfo(
                            callId = matrixCall.callId,
                            type = CallType.MATRIX_INTERNAL,
                            phoneNumber = destination,
                            displayName = null,
                            roomId = matrixCall.roomId
                        )
                    }
                } else {
                    matrixManager.startVoiceCall(destination).map { matrixCall ->
                        UnifiedCallInfo(
                            callId = matrixCall.callId,
                            type = CallType.MATRIX_INTERNAL,
                            phoneNumber = destination,
                            displayName = null,
                            roomId = matrixCall.roomId
                        )
                    }
                }
            }
            CallType.SIP_EXTERNAL -> {
                // Llamada SIP
                try {
                    requireNotNull(accountInfo) { "SIP account info required for external calls" }
                    sipCallManager.makeCall(destination, accountInfo)
                    Result.success(
                        UnifiedCallInfo(
                            callId = accountInfo.currentCallData.value?.callId ?: "",
                            type = CallType.SIP_EXTERNAL,
                            phoneNumber = destination,
                            displayName = null
                        )
                    )
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }
    }
}
