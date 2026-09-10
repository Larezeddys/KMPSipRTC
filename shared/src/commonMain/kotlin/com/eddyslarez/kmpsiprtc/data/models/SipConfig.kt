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
     * Comportamiento en el Contact header del REGISTER:
     *   - false (Debug)  -> se envia ;pn-production=false para forzar sandbox
     *   - true (Release) -> NO se envia el parametro pn-production
     *
     * OpenSIPS asume produccion por defecto cuando no recibe pn-production.
     * Es ESTRICTO: si se envia pn-production=false, OpenSIPS enruta al sandbox
     * y las push no llegan en produccion. Por eso en release se OMITE.
     *
     * IMPORTANTE: En desarrollo siempre usar false, de lo contrario
     * no se recibiran notificaciones push en llamadas entrantes.
     *
     * Configurar segun el flavor en la app:
     *   Debug   -> pushProduction = false (envia pn-production=false)
     *   Release -> pushProduction = true  (no envia pn-production)
     */
    val pushProduction: Boolean = true,
    /**
     * Identificador APNs requerido por RFC 8599: TeamID.BundleID.voip.
     * Solo se agrega al Contact cuando el proveedor de la cuenta es "apns".
     */
    val apnsPushParam: String? = null,
    /**
     * Activa/desactiva la funcion "call waiting" (llamada en espera).
     *
     *   - true  (por defecto): comportamiento clasico. Una segunda llamada entrante
     *     mientras hay una llamada activa suena normalmente (multi-llamada).
     *   - false: una segunda llamada entrante mientras hay otra llamada activa se
     *     rechaza con 486 Busy Here. El llamante escucha tono de ocupado y no se
     *     reproduce ringtone que interfiera con la conversacion en curso.
     *
     * Es modificable en caliente vía KmpSipRtc.setCallWaitingEnabled(...) sin
     * reinicializar la libreria.
     */
    val callWaitingEnabled: Boolean = true,
    /**
     * Segundos que una llamada entrante puede sonar sin respuesta antes de que la
     * biblioteca la cierre con 480 Temporarily Unavailable y la registre como perdida.
     *
     * Existe como red de seguridad: si el llamante o el proxy nunca envian CANCEL
     * (red caida, servidor que se calla), sin esto el INVITE entrante se queda sonando
     * indefinidamente y bloquea llamadas posteriores. Equivale a `inc_timeout` de Linphone.
     *
     * `0` lo desactiva y deja que solo el CANCEL remoto cierre la llamada.
     */
    val incomingCallTimeoutSeconds: Int = 45
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

        if (incomingCallTimeoutSeconds < 0) {
            errors.add(
                SipError.Configuration(
                    "incomingCallTimeoutSeconds",
                    "Must be >= 0, 0 disables the timeout (got: $incomingCallTimeoutSeconds)"
                )
            )
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
