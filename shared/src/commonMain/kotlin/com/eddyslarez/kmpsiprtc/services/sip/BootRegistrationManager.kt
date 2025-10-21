package com.eddyslarez.kmpsiprtc.services.sip

import com.eddyslarez.kmpsiprtc.core.NetworkConnectivityListener
import com.eddyslarez.kmpsiprtc.core.SipCoreManager
import com.eddyslarez.kmpsiprtc.data.database.DatabaseManager
import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.data.models.RegistrationState
import com.eddyslarez.kmpsiprtc.platform.log
import io.ktor.http.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock

class BootRegistrationManager(
    private val sipCoreManager: SipCoreManager,
    private val databaseManager: DatabaseManager
) {
    private val TAG = "BootRegistrationManager"
    private val registrationQueue = mutableListOf<AccountInfo>()
    private val registrationResults = mutableMapOf<String, RegistrationState>()
    private var isProcessingRegistrations = false

    companion object {
        private const val MAX_REGISTRATION_RETRIES = 5
        private const val REGISTRATION_RETRY_DELAY = 3000L
        private const val BATCH_SIZE = 2 // Registrar máximo 2 cuentas simultáneamente
        private const val ACCOUNT_REGISTRATION_TIMEOUT = 15000L
    }

    /**
     * Inicia el proceso de recuperación de cuentas desde BD
     */
    suspend fun recoverAndRegisterAllAccounts(): RegistrationRecoveryResult {
        log.d(tag = TAG) { "=== STARTING BOOT REGISTRATION RECOVERY ===" }

        if (isProcessingRegistrations) {
            log.w(tag = TAG) { "Registration process already in progress" }
            return RegistrationRecoveryResult.InProgress
        }

        isProcessingRegistrations = true

        try {
            // 1. Recuperar cuentas desde BD
            val accountsFromDB = recoverAccountsFromDatabase()

            if (accountsFromDB.isEmpty()) {
                log.w(tag = TAG) { "No accounts found in database" }
                return RegistrationRecoveryResult.NoAccounts
            }

            log.d(tag = TAG) { "Found ${accountsFromDB.size} accounts in database" }

            // 2. Validar conectividad antes de registrar
            if (!validateNetworkConnectivity()) {
                log.e(tag = TAG) { "Network not available, cannot proceed with registration" }
                return RegistrationRecoveryResult.NetworkUnavailable
            }

            // 3. Procesar registros en lotes para evitar sobrecarga
            val results = processRegistrationBatches(accountsFromDB)

            // 4. Evaluar resultados
            return evaluateRegistrationResults(results)

        } catch (e: Exception) {
            log.e(tag = TAG) { "Critical error in boot registration recovery: ${e.message}" }
            return RegistrationRecoveryResult.CriticalError(e.message.toString())
        } finally {
            isProcessingRegistrations = false
        }
    }

    /**
     * Recupera cuentas desde base de datos con validación
     */
    private suspend fun recoverAccountsFromDatabase(): List<AccountInfo> {
        return try {
            val dbAccounts = databaseManager.getRegisteredSipAccounts().first()
            log.d(tag = TAG) { "Retrieved ${dbAccounts.size} accounts from database" }

            dbAccounts.mapNotNull { dbAccount ->
                try {
                    // Validar datos de cuenta
                    if (dbAccount.username.isBlank() || dbAccount.domain.isBlank()) {
                        log.w(tag = TAG) { "Skipping invalid account with empty credentials" }
                        return@mapNotNull null
                    }

                    val accountInfo = AccountInfo(
                        username = dbAccount.username,
                        password = dbAccount.password,
                        domain = dbAccount.domain
                    ).apply {
                        token.value = dbAccount.pushToken ?: ""
                        provider.value = dbAccount.pushProvider ?: "fcm"
                        userAgent.value = sipCoreManager.userAgent() + " Push"
                        isRegistered.value = false // Siempre empezar como no registrado
                    }

                    val accountKey = "${accountInfo.username}@${accountInfo.domain}"
                    log.d(tag = TAG) { "Recovered account: $accountKey" }

                    accountInfo

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error processing account ${dbAccount.id}: ${e.message}" }
                    null
                }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error recovering accounts from database: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Valida conectividad de red antes de registrar
     */
    private suspend fun validateNetworkConnectivity(): Boolean {
        return try {
            // Verificar conectividad básica
            if (!sipCoreManager.networkManager.isNetworkAvailable()) {
                log.e(tag = TAG) { "Basic network connectivity not available" }
                return false
            }

            // Esperar un poco para que la red se estabilice después del boot
            delay(2000)

            // Verificar conectividad a internet
            val hasInternet = sipCoreManager.networkManager.isNetworkAvailable()
            log.d(tag = TAG) { "Internet connectivity: $hasInternet" }

            hasInternet

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error validating network connectivity: ${e.message}" }
            false
        }
    }

    /**
     * Procesa registros en lotes para evitar sobrecarga del servidor
     */
    private suspend fun processRegistrationBatches(accounts: List<AccountInfo>): Map<String, RegistrationState> {
        val results = mutableMapOf<String, RegistrationState>()

        log.d(tag = TAG) { "Processing ${accounts.size} accounts in batches of $BATCH_SIZE" }

        accounts.chunked(BATCH_SIZE).forEachIndexed { batchIndex, batch ->
            log.d(tag = TAG) { "Processing batch ${batchIndex + 1} with ${batch.size} accounts" }

            // Procesar cuentas del lote actual
            val batchJobs = batch.map { accountInfo ->
                CoroutineScope(Dispatchers.IO).async {
                    registerAccountWithRetry(accountInfo)
                }
            }

            // Esperar a que termine el lote actual
            batchJobs.awaitAll().forEach { (accountKey, state) ->
                results[accountKey] = state
            }

            // Delay entre lotes para no sobrecargar
            if (batchIndex < accounts.chunked(BATCH_SIZE).size - 1) {
                log.d(tag = TAG) { "Waiting before next batch..." }
                delay(2000)
            }
        }

        return results
    }

    /**
     * Registra una cuenta con reintentos automáticos
     */
    private suspend fun registerAccountWithRetry(accountInfo: AccountInfo): Pair<String, RegistrationState> {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
        var lastError: String? = null

        repeat(MAX_REGISTRATION_RETRIES) { attempt ->
            try {
                log.d(tag = TAG) { "Registration attempt ${attempt + 1} for $accountKey" }

                // Actualizar estado en core manager
                sipCoreManager.activeAccounts[accountKey] = accountInfo
                sipCoreManager.updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)

                // Intentar registro con timeout
                val registrationSuccess = withTimeout(ACCOUNT_REGISTRATION_TIMEOUT) {
                    performSafeRegistration(accountInfo)
                }

                if (registrationSuccess) {
                    log.d(tag = TAG) { "Registration successful for $accountKey on attempt ${attempt + 1}" }
                    return accountKey to RegistrationState.OK
                } else {
                    lastError = "Registration returned false"
                    log.w(tag = TAG) { "Registration failed for $accountKey on attempt ${attempt + 1}: $lastError" }
                }

            } catch (e: TimeoutCancellationException) {
                lastError = "Registration timeout"
                log.w(tag = TAG) { "Registration timeout for $accountKey on attempt ${attempt + 1}" }
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                log.w(tag = TAG) { "Registration error for $accountKey on attempt ${attempt + 1}: $lastError" }
            }

            // Delay antes del siguiente intento
            if (attempt < MAX_REGISTRATION_RETRIES - 1) {
                delay(REGISTRATION_RETRY_DELAY)
            }
        }

        log.e(tag = TAG) { "Registration failed permanently for $accountKey after $MAX_REGISTRATION_RETRIES attempts. Last error: $lastError" }
        sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)

        return accountKey to RegistrationState.FAILED
    }

    /**
     * Realiza registro seguro con verificaciones adicionales
     */
    private suspend fun performSafeRegistration(accountInfo: AccountInfo): Boolean {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"

        try {
            // 1. Verificar conectividad WebSocket antes de registrar
            if (!sipCoreManager.ensureWebSocketConnectivity(accountInfo)) {
                log.e(tag = TAG) { "Cannot ensure WebSocket connectivity for $accountKey" }
                return false
            }

            // 2. Verificar que WebSocket está saludable
            if (!accountInfo.isWebSocketHealthy()) {
                log.e(tag = TAG) { "WebSocket not healthy for $accountKey" }
                return false
            }

            // 3. Enviar registro en modo push (siempre push al boot)
            sipCoreManager.messageHandler.sendRegister(accountInfo, true)

            // 4. Esperar confirmación de registro
            return waitForRegistrationConfirmation(accountKey)

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in safe registration for $accountKey: ${e.message}" }
            return false
        }
    }

    /**
     * Espera confirmación de registro con timeout
     */
    private suspend fun waitForRegistrationConfirmation(accountKey: String): Boolean {
        val startTime = Clock.System.now().toEpochMilliseconds()
        val maxWaitTime = 10000L // 10 segundos máximo

        while (Clock.System.now().toEpochMilliseconds() - startTime < maxWaitTime) {
            val currentState = sipCoreManager.getRegistrationState(accountKey)

            when (currentState) {
                RegistrationState.OK -> {
                    log.d(tag = TAG) { "Registration confirmed for $accountKey" }
                    return true
                }
                RegistrationState.FAILED -> {
                    log.w(tag = TAG) { "Registration failed for $accountKey" }
                    return false
                }
                else -> {
                    // Seguir esperando
                    delay(500)
                }
            }
        }

        log.w(tag = TAG) { "Registration confirmation timeout for $accountKey" }
        return false
    }

    /**
     * Evalúa los resultados de registro
     */
    private fun evaluateRegistrationResults(results: Map<String, RegistrationState>): RegistrationRecoveryResult {
        val successful = results.count { it.value == RegistrationState.OK }
        val failed = results.count { it.value == RegistrationState.FAILED }
        val total = results.size

        log.d(tag = TAG) { "Registration results: $successful successful, $failed failed out of $total total" }

        return when {
            successful == total -> RegistrationRecoveryResult.AllSuccessful(results)
            successful > 0 -> RegistrationRecoveryResult.PartialSuccess(results, successful, failed)
            else -> RegistrationRecoveryResult.AllFailed(results)
        }
    }
}

// 2. RESULTADO DE RECUPERACIÓN
sealed class RegistrationRecoveryResult {
    object InProgress : RegistrationRecoveryResult()
    object NoAccounts : RegistrationRecoveryResult()
    object NetworkUnavailable : RegistrationRecoveryResult()
    data class AllSuccessful(val results: Map<String, RegistrationState>) : RegistrationRecoveryResult()
    data class PartialSuccess(val results: Map<String, RegistrationState>, val successful: Int, val failed: Int) : RegistrationRecoveryResult()
    data class AllFailed(val results: Map<String, RegistrationState>) : RegistrationRecoveryResult()
    data class CriticalError(val error: String) : RegistrationRecoveryResult()
}

// 3. EXTENSIÓN PARA VERIFICAR SALUD DE WEBSOCKET
fun AccountInfo.isWebSocketHealthy(): Boolean {
    val webSocket = this.webSocketClient.value ?: return false
    return webSocket.isConnected()
}

// 5. MÉTODOS DE NOTIFICACIÓN Y REINTENTOS
private fun SipCoreManager.notifyBootRegistrationSuccess(results: Map<String, RegistrationState>) {
    sipCallbacks?.let { callbacks ->
        results.forEach { (accountKey, state) ->
            val parts = accountKey.split("@")
            if (parts.size == 2) {
                callbacks.onAccountRegistrationStateChanged(parts[0], parts[1], state)
            }
        }
    }
    lifecycleCallback?.invoke("BOOT_REGISTRATION_SUCCESS")
}

private fun SipCoreManager.notifyBootRegistrationPartial(results: Map<String, RegistrationState>, successful: Int, failed: Int) {
    results.forEach { (accountKey, state) ->
        val parts = accountKey.split("@")
        if (parts.size == 2) {
            sipCallbacks?.onAccountRegistrationStateChanged(parts[0], parts[1], state)
        }
    }
    lifecycleCallback?.invoke("BOOT_REGISTRATION_PARTIAL:$successful:$failed")
}

private fun SipCoreManager.notifyBootRegistrationFailure(results: Map<String, RegistrationState>) {
    results.forEach { (accountKey, state) ->
        val parts = accountKey.split("@")
        if (parts.size == 2) {
            sipCallbacks?.onAccountRegistrationStateChanged(parts[0], parts[1], state)
        }
    }
    lifecycleCallback?.invoke("BOOT_REGISTRATION_FAILED")
}

private fun SipCoreManager.scheduleFailedAccountRetries(failedAccounts: Set<String>) {
    CoroutineScope(Dispatchers.IO).launch {
        delay(15000L) // 15 segundos antes de reintentar

        log.d(tag = "SipCoreManager") { "Retrying registration for ${failedAccounts.size} failed accounts" }

        failedAccounts.forEach { accountKey ->
            val accountInfo = activeAccounts[accountKey]
            if (accountInfo != null) {
                try {
                    val success = safeRegister(accountInfo, true)
                    if (!success) {
                        log.w(tag = "SipCoreManager") { "Retry failed for $accountKey" }
                    }
                } catch (e: Exception) {
                    log.e(tag = "SipCoreManager") { "Error in retry for $accountKey: ${e.message}" }
                }
            }
        }
    }
}
//
//private fun SipCoreManager.scheduleCompleteRegistrationRetry(delayMs: Long) {
//    CoroutineScope(Dispatchers.IO).launch {
//        delay(delayMs)
//
//        log.d(tag = "SipCoreManager") { "Attempting complete registration retry" }
//
//        try {
//            initializeBootRegistration()
//        } catch (e: Exception) {
//            log.e(tag = "SipCoreManager") { "Error in complete registration retry: ${e.message}" }
//        }
//    }
//}
//
//private fun SipCoreManager.observeNetworkAndRetryRegistration() {
//    networkManager.setConnectivityListener(object : NetworkConnectivityListener {
//        override fun onNetworkRestored() {
//            log.d(tag = "SipCoreManager") { "Network restored, retrying boot registration" }
//
//            CoroutineScope(Dispatchers.IO).launch {
//                delay(3000L) // Esperar estabilización
//                initializeBootRegistration()
//            }
//        }
//
//        override fun onNetworkLost(): Job {
//            return Job()
//        }
//
//
//        override fun onReconnectionStarted() {}
//        override fun onReconnectionCompleted(successful: Boolean) {}
//    })
//}