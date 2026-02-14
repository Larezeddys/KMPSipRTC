package com.eddyslarez.kmpsiprtc.data.models

import kotlin.time.ExperimentalTime

/**
 * Reporte de salud tipado del sistema.
 *
 * Reemplaza los multiples metodos diagnosticos que retornan String
 * con un modelo estructurado que el consumidor puede inspeccionar programaticamente.
 *
 * ```kotlin
 * val health = KmpSipRtc.getInstance().getHealthReport()
 * if (!health.isHealthy) {
 *     health.unhealthyComponents.forEach { (name, component) ->
 *         log("$name: ${component.status} - ${component.details}")
 *     }
 * }
 * ```
 */
data class HealthReport @OptIn(ExperimentalTime::class) constructor(
    val sipRegistration: ComponentHealth,
    val webSocket: ComponentHealth,
    val network: ComponentHealth,
    val database: ComponentHealth,
    val timestamp: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
) {
    /** true si todos los componentes estan HEALTHY. */
    val isHealthy: Boolean
        get() = sipRegistration.isHealthy && webSocket.isHealthy &&
                network.isHealthy && database.isHealthy

    /** Componentes que no estan healthy, mapeados por nombre. */
    val unhealthyComponents: Map<String, ComponentHealth>
        get() = buildMap {
            if (!sipRegistration.isHealthy) put("sipRegistration", sipRegistration)
            if (!webSocket.isHealthy) put("webSocket", webSocket)
            if (!network.isHealthy) put("network", network)
            if (!database.isHealthy) put("database", database)
        }

    /** Resumen legible para logs/debugging. */
    fun summary(): String = buildString {
        appendLine("=== HEALTH REPORT ===")
        appendLine("Overall: ${if (isHealthy) "HEALTHY" else "UNHEALTHY"}")
        appendLine("SIP Registration: ${sipRegistration.status} - ${sipRegistration.details}")
        appendLine("WebSocket: ${webSocket.status} - ${webSocket.details}")
        appendLine("Network: ${network.status} - ${network.details}")
        appendLine("Database: ${database.status} - ${database.details}")
        if (sipRegistration.registeredAccounts.isNotEmpty()) {
            appendLine("Registered accounts: ${sipRegistration.registeredAccounts.joinToString()}")
        }
    }
}

/**
 * Estado de salud de un componente individual.
 */
data class ComponentHealth @OptIn(ExperimentalTime::class) constructor(
    val status: HealthStatus,
    val details: String = "",
    val lastChecked: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
    val registeredAccounts: List<String> = emptyList()
) {
    val isHealthy: Boolean get() = status == HealthStatus.HEALTHY

    companion object {
        fun healthy(details: String = "OK") = ComponentHealth(HealthStatus.HEALTHY, details)
        fun degraded(details: String) = ComponentHealth(HealthStatus.DEGRADED, details)
        fun unhealthy(details: String) = ComponentHealth(HealthStatus.UNHEALTHY, details)
        fun unknown(details: String = "Not checked") = ComponentHealth(HealthStatus.UNKNOWN, details)
    }
}

/**
 * Estado de salud posible.
 */
enum class HealthStatus {
    /** Funcionando correctamente. */
    HEALTHY,
    /** Funcionando con problemas menores. */
    DEGRADED,
    /** No funciona. */
    UNHEALTHY,
    /** No se ha verificado aun. */
    UNKNOWN
}
