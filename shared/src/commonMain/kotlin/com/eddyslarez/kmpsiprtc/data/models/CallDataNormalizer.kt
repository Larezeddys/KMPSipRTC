package com.eddyslarez.kmpsiprtc.data.models

import com.eddyslarez.kmpsiprtc.platform.log

/**
 * Normaliza CallData para llamadas de tipo callback.
 *
 * Un callback ocurre cuando el servidor SIP llama al cliente WebSocket
 * tras una solicitud del usuario. El INVITE de callback tiene el header
 * To con dominio `.invalid` (identificador temporal de sesión WebSocket),
 * lo que puede corromper el historial si no se normaliza.
 *
 * Esta clase detecta callbacks y extrae el número real del llamante
 * desde P-Asserted-Identity o From, asegurando que el historial
 * muestre datos correctos.
 */
object CallDataNormalizer {

    private const val TAG = "CallDataNormalizer"

    /** Número de teléfono: 5+ dígitos, opcionalmente precedido por '+' */
    private val PHONE_REGEX = Regex("[+]?\\d{5,}")

    /** Extrae la parte 'user' de una SIP URI: sip:user@domain */
    private val SIP_USER_REGEX = Regex("sip:([^@;>\\s]+)@")

    /**
     * Normaliza [callData] para que callbacks tengan from/to correctos.
     *
     * - Si NO es callback: retorna [callData] sin modificar (comportamiento 100% preservado).
     * - Si ES callback: retorna una copia con from=número_real, isCallback=true.
     */
    fun normalize(callData: CallData): CallData {
        // Solo aplicar a llamadas entrantes
        if (callData.direction != CallDirections.INCOMING) return callData

        // Detectar callback por el rawToUri o el campo to actual
        val rawTo = callData.rawToUri ?: callData.to
        val isCallback = detectCallback(rawTo)

        if (!isCallback) return callData

        // Extraer número real en orden de prioridad:
        // 1. P-Asserted-Identity, 2. rawFromUri, 3. from directo
        val realNumber = extractPhoneNumber(callData.assertedIdentity)
            ?: extractPhoneNumber(callData.rawFromUri)
            ?: callData.from.takeIf { it.matches(PHONE_REGEX) }

        log.d(TAG) {
            "[NORMALIZE] isCallback=true | from=${realNumber ?: callData.from} | " +
            "to=${callData.to} | realNumber=$realNumber | rawTo=$rawTo"
        }

        if (realNumber == null) {
            log.w(TAG) {
                "[NORMALIZE] Callback detectado pero sin número real extraíble " +
                "(from=${callData.from}, assertedIdentity=${callData.assertedIdentity})"
            }
            // Marcar como callback aunque no tengamos número real
            return callData.copy(isCallback = true, rawToUri = rawTo)
        }

        return callData.copy(
            isCallback = true,
            from = realNumber,
            to = callData.to,    // accountInfo.username ya es el número local correcto
            rawToUri = rawTo     // preservar To URI original para debugging
        )
    }

    /**
     * Detecta si una URI de destino corresponde a una sesión callback WebSocket.
     * Indicador definitivo: dominio `.invalid` (RFC 2606 / RFC 7118) usado por
     * Asterisk para direcciones de sesión WebSocket transitorias.
     *
     * La condición `transport=ws` fue eliminada porque producía falsos positivos:
     * llamadas entrantes regulares de extensiones cortas (ej. "1001", 4 dígitos)
     * pasaban por WebSocket con `transport=ws` en la URI y no coincidían con
     * PHONE_REGEX (5+ dígitos), siendo incorrectamente clasificadas como callback.
     */
    private fun detectCallback(toUri: String): Boolean {
        return toUri.contains(".invalid", ignoreCase = true)
    }

    /**
     * Extrae un número de teléfono desde una SIP URI o string directo.
     * Retorna null si la URI es nula/vacía o no contiene un número válido.
     */
    private fun extractPhoneNumber(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        val user = SIP_USER_REGEX.find(uri)?.groupValues?.get(1) ?: uri.trim()
        return user.takeIf { it.matches(PHONE_REGEX) }
    }
}
