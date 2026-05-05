package com.eddyslarez.kmpsiprtc.data.models

import kotlin.time.ExperimentalTime

/**
 * Snapshot completo del estado del sistema para debugging.
 * Captura toda la información relevante en un momento dado.
 *
 * ```kotlin
 * val snapshot = sdk.getDiagnosticSnapshot()
 * println(snapshot.toFormattedString())
 * // O enviar a un servicio de analytics:
 * analytics.track("sip_diagnostic", snapshot.toMap())
 * ```
 */
data class DiagnosticSnapshot @OptIn(ExperimentalTime::class) constructor(
    val timestamp: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
    val health: HealthReport,
    val activeCalls: Int,
    val registeredAccounts: List<String>,
    val webSocketState: String,
    val callStateHistory: List<CallStateInfo>,
    val reconnectAttempts: Int,
    val uptimeMs: Long,
    val libraryVersion: String = "2.0.0"
) {
    fun toFormattedString(): String = buildString {
        appendLine("╔══════════════════════════════════════╗")
        appendLine("║      KMPSipRTC DIAGNOSTIC SNAPSHOT   ║")
        appendLine("╠══════════════════════════════════════╣")
        appendLine("║ Timestamp: $timestamp")
        appendLine("║ Uptime: ${uptimeMs / 1000}s")
        appendLine("║ Version: $libraryVersion")
        appendLine("╠══════════════════════════════════════╣")
        appendLine("║ HEALTH: ${if (health.isHealthy) "HEALTHY" else "UNHEALTHY"}")
        appendLine("║   SIP: ${health.sipRegistration.status}")
        appendLine("║   WebSocket: ${health.webSocket.status}")
        appendLine("║   Network: ${health.network.status}")
        appendLine("║   Database: ${health.database.status}")
        appendLine("╠══════════════════════════════════════╣")
        appendLine("║ Active calls: $activeCalls")
        appendLine("║ Registered: ${registeredAccounts.joinToString()}")
        appendLine("║ WebSocket: $webSocketState")
        appendLine("║ Reconnect attempts: $reconnectAttempts")
        appendLine("╠══════════════════════════════════════╣")
        appendLine("║ Recent state changes (last ${callStateHistory.size}):")
        callStateHistory.takeLast(5).forEach { state ->
            appendLine("║   ${state.previousState} → ${state.state} (${state.callId})")
        }
        appendLine("╚══════════════════════════════════════╝")
    }

    fun toMap(): Map<String, Any> = mapOf(
        "timestamp" to timestamp,
        "isHealthy" to health.isHealthy,
        "activeCalls" to activeCalls,
        "registeredAccounts" to registeredAccounts,
        "webSocketState" to webSocketState,
        "reconnectAttempts" to reconnectAttempts,
        "uptimeMs" to uptimeMs,
        "version" to libraryVersion
    )
}
