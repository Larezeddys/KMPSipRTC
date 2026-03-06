package com.eddyslarez.kmpsiprtc.data.models

data class SipConfig(
    val defaultDomain: String = "",
    val webSocketUrl: String = "",
    val userAgent: String = "",
    val enableLogs: Boolean = true,
    val enableAutoReconnect: Boolean = true,
    val pingIntervalMs: Long = 30000L,
    val pushModeConfig: PushModeConfig = PushModeConfig(),
    val incomingRingtoneUri: String? = null,
    val outgoingRingtoneUri: String? = null,
    /**
     * Indica si el entorno de push es produccion (true) o desarrollo/debug (false).
     *
     * OpenSIPS requiere el parametro ;pn-production en el Contact header para
     * saber como enrutar el push al proveedor correcto:
     *   - false -> entorno de desarrollo (debug builds, APNs sandbox, RuStore staging)
     *   - true  -> entorno de produccion (Play Store/App Store releases)
     *
     * Para FCM este parametro es ignorado por el servidor (el entorno esta
     * embebido en el token), pero se envia igual para consistencia.
     *
     * IMPORTANTE: En desarrollo siempre usar false, de lo contrario
     * no se recibiran notificaciones push en llamadas entrantes.
     *
     * Configurar segun el flavor en la app:
     *   Debug   -> pushProduction = false
     *   Release -> pushProduction = true
     */
    val pushProduction: Boolean = false
) {
    /**
     * Valida la configuracion y retorna una lista de errores encontrados.
     * Lista vacia = configuracion valida.
     *
     * ```kotlin
     * val errors = config.validate()
     * if (errors.isNotEmpty()) {
     *     errors.forEach { println("Config error: ${it.message}") }
     *     return
     * }
     * ```
     */
    fun validate(): List<SipError.Configuration> {
        val errors = mutableListOf<SipError.Configuration>()

        if (webSocketUrl.isNotEmpty()) {
            if (!webSocketUrl.startsWith("ws://") && !webSocketUrl.startsWith("wss://")) {
                errors.add(
                    SipError.Configuration(
                        "webSocketUrl",
                        "Must start with ws:// or wss:// (got: '${webSocketUrl.take(10)}...')"
                    )
                )
            }
        }

        if (pingIntervalMs < 5000) {
            errors.add(
                SipError.Configuration(
                    "pingIntervalMs",
                    "Must be >= 5000ms (got: $pingIntervalMs)"
                )
            )
        }

        if (pushModeConfig.autoTransitionDelay < 1000) {
            errors.add(
                SipError.Configuration(
                    "pushModeConfig.autoTransitionDelay",
                    "Must be >= 1000ms (got: ${pushModeConfig.autoTransitionDelay})"
                )
            )
        }

        return errors
    }

    /**
     * Valida y lanza SipError.Configuration si hay errores.
     *
     * @throws IllegalArgumentException si la configuracion es invalida
     */
    fun validateOrThrow() {
        val errors = validate()
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(
                "Invalid SipConfig: ${errors.joinToString("; ") { it.message }}"
            )
        }
    }
}
