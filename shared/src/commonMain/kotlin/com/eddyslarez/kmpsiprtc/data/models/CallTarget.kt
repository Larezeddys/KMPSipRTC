package com.eddyslarez.kmpsiprtc.data.models

/**
 * Destino de llamada type-safe.
 * Reemplaza el uso de String ambiguo para representar el destino de una llamada.
 *
 * Uso:
 * ```kotlin
 * // Llamada SIP a número de teléfono
 * sdk.makeCall(CallTarget.Phone("123456789"))
 *
 * // Llamada SIP a dirección completa
 * sdk.makeCall(CallTarget.SipAddress("user@domain.com"))
 *
 * // Llamada Matrix a room
 * sdk.makeCall(CallTarget.MatrixRoom("!roomId:server"))
 * ```
 */
sealed interface CallTarget {
    /** Número de teléfono (se le agrega el dominio SIP automáticamente) */
    data class Phone(val number: String) : CallTarget

    /** Dirección SIP completa (user@domain) */
    data class SipAddress(val uri: String) : CallTarget

    /** Room ID de Matrix para llamada interna */
    data class MatrixRoom(val roomId: String) : CallTarget
}
