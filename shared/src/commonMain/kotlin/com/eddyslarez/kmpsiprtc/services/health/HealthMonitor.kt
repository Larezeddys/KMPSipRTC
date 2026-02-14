package com.eddyslarez.kmpsiprtc.services.health

import com.eddyslarez.kmpsiprtc.data.models.HealthReport
import com.eddyslarez.kmpsiprtc.platform.log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitor de salud continuo del sistema SIP.
 * Ejecuta chequeos periódicos y emite reportes via StateFlow.
 *
 * ```kotlin
 * healthMonitor.healthFlow.collect { report ->
 *     if (!report.isHealthy) notifyAdmin(report.unhealthyComponents)
 * }
 * ```
 */
internal class HealthMonitor(
    private val healthCheckProvider: suspend () -> HealthReport,
    private val intervalMs: Long = DEFAULT_CHECK_INTERVAL_MS
) {
    companion object {
        private const val TAG = "HealthMonitor"
        private const val DEFAULT_CHECK_INTERVAL_MS = 60_000L  // 1 minuto
        private const val MIN_CHECK_INTERVAL_MS = 10_000L       // 10 segundos mínimo
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null

    private val _healthFlow = MutableStateFlow<HealthReport?>(null)
    val healthFlow: StateFlow<HealthReport?> = _healthFlow.asStateFlow()

    @Volatile
    private var isRunning = false

    /**
     * Inicia el monitoreo periódico
     */
    fun start() {
        if (isRunning) return
        isRunning = true

        val checkInterval = intervalMs.coerceAtLeast(MIN_CHECK_INTERVAL_MS)

        monitorJob = scope.launch {
            log.d(TAG) { "Health monitor started (interval: ${checkInterval}ms)" }

            while (isActive && isRunning) {
                try {
                    val report = healthCheckProvider()
                    _healthFlow.value = report

                    if (!report.isHealthy) {
                        log.w(TAG) {
                            "Health check UNHEALTHY: ${report.unhealthyComponents.keys}"
                        }
                    }
                } catch (e: Exception) {
                    log.e(TAG) { "Health check failed: ${e.message}" }
                }

                delay(checkInterval)
            }
        }
    }

    /**
     * Detiene el monitoreo
     */
    fun stop() {
        isRunning = false
        monitorJob?.cancel()
        monitorJob = null
        log.d(TAG) { "Health monitor stopped" }
    }

    /**
     * Fuerza un chequeo inmediato
     */
    suspend fun checkNow(): HealthReport {
        val report = healthCheckProvider()
        _healthFlow.value = report
        return report
    }

    fun dispose() {
        stop()
        scope.cancel()
    }
}
