package com.eddyslarez.kmpsiprtc.services.calls

import com.eddyslarez.kmpsiprtc.core.SipCoreManager
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.pushMode.PushModeManager
import com.eddyslarez.kmpsiprtc.utils.ConcurrentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime

class CallLifecycleManager(
    private val sipCoreManager: SipCoreManager,
    private val pushModeManager: PushModeManager?
) {
    companion object {
        private const val TAG = "CallLifecycleManager"
        private const val RETURN_TO_PUSH_DELAY = 2000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val accountsInCall = ConcurrentMap<String, CallLifecycleState>()
    private val returnToPushJobs = ConcurrentMap<String, Job>()

    data class CallLifecycleState(
        val accountKey: String,
        val wasInPushBeforeCall: Boolean,
        val callStartTime: Long,
        var callEndTime: Long? = null
    )

    /**
     * Llamada cuando se recibe una llamada entrante
     */
    @OptIn(ExperimentalTime::class)
    suspend fun onIncomingCallReceived(accountKey: String) {
        log.d(tag = TAG) { "📞 Incoming call received for $accountKey" }

        val accountInfo = sipCoreManager.activeAccounts[accountKey] ?: return
        val wasInPush = accountInfo.userAgent.value?.contains("Push", ignoreCase = true)

        // Guardar estado
        accountsInCall.put(
            accountKey,
            CallLifecycleState(
                accountKey = accountKey,
                wasInPushBeforeCall = wasInPush == true,
                callStartTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
            )
        )

        log.d(tag = TAG) { "Saved state: wasInPush=$wasInPush for $accountKey" }

        // Si estaba en push, cambiar a foreground para la llamada
        if (wasInPush == true) {
            scope.launch {
                log.d(tag = TAG) { "Switching $accountKey to FOREGROUND for call" }
                sipCoreManager.switchToForegroundMode(
                    accountInfo.username,
                    accountInfo.domain
                )
            }
        }
    }


    /**
     * Llamada cuando termina una llamada
     */
    @OptIn(ExperimentalTime::class)
    suspend fun onCallEnded(accountKey: String) {
        log.d(tag = TAG) { "📴 Call ended for $accountKey" }

        val lifecycleState = accountsInCall.get(accountKey)

        if (lifecycleState == null) {
            log.w(tag = TAG) { "No lifecycle state found for $accountKey" }
            return
        }

        lifecycleState.callEndTime = kotlin.time.Clock.System.now().toEpochMilliseconds()

        // Si estaba en push antes de la llamada, programar retorno
        if (lifecycleState.wasInPushBeforeCall) {
            scheduleReturnToPush(accountKey, lifecycleState)
        } else {
            // Limpiar estado inmediatamente
            accountsInCall.remove(accountKey)
        }
    }

    private suspend fun scheduleReturnToPush(accountKey: String, lifecycleState: CallLifecycleState) {
        // Cancelar job anterior si existe
        returnToPushJobs.get(accountKey)?.cancel()

        val job = scope.launch {
            try {
                log.d(tag = TAG) { "⏳ Scheduling return to PUSH for $accountKey in ${RETURN_TO_PUSH_DELAY}ms" }
                delay(RETURN_TO_PUSH_DELAY)

                // Verificar que no hay nueva llamada
                val currentState = accountsInCall.get(accountKey)
                if (currentState?.callEndTime != null) {
                    log.d(tag = TAG) { "✅ Returning $accountKey to PUSH mode" }

                    val accountInfo = sipCoreManager.activeAccounts[accountKey]
                    if (accountInfo != null) {
                        sipCoreManager.switchToPushMode(
                            accountInfo.username,
                            accountInfo.domain
                        )

                        log.d(tag = TAG) { "✅ $accountKey returned to PUSH successfully" }
                    }

                    // Limpiar estado
                    accountsInCall.remove(accountKey)
                    returnToPushJobs.remove(accountKey)
                } else {
                    log.d(tag = TAG) { "⚠️ New call detected for $accountKey, cancelling return to PUSH" }
                }

            } catch (e: CancellationException) {
                log.d(tag = TAG) { "Return to PUSH cancelled for $accountKey" }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error returning $accountKey to PUSH: ${e.message}" }
            }
        }

        returnToPushJobs.put(accountKey, job)
    }

    /**
     * Cancela retorno a push para una cuenta específica
     */
    suspend fun cancelReturnToPush(accountKey: String) {
        returnToPushJobs.get(accountKey)?.cancel()
        returnToPushJobs.remove(accountKey)
        accountsInCall.remove(accountKey)
    }

    /**
     * Obtiene diagnóstico
     */
    suspend fun getDiagnostic(): String {
        return buildString {
            appendLine("=== CALL LIFECYCLE MANAGER ===")
            appendLine("Accounts in call: ${accountsInCall.size()}")
            appendLine("Pending returns to push: ${returnToPushJobs.size()}")

//            accountsInCall.forEach { (key, state) ->
//                appendLine("\n$key:")
//                appendLine("  Was in push: ${state.wasInPushBeforeCall}")
//                appendLine("  Call start: ${state.callStartTime}")
//                appendLine("  Call end: ${state.callEndTime ?: "ongoing"}")
//                appendLine("  Has return job: ${returnToPushJobs.containsKey(key)}")
//            } as suspend (String, CallLifecycleState) -> Unit
        }
    }

    suspend fun dispose() {
        returnToPushJobs.values().forEach { it.cancel() }
        returnToPushJobs.clear()
        accountsInCall.clear()
        scope.cancel()
    }
}
