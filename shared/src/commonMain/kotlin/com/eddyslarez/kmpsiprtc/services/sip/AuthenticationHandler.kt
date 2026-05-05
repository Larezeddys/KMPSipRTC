package com.eddyslarez.kmpsiprtc.services.sip

import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.utils.generateId
import com.eddyslarez.kmpsiprtc.utils.md5

object AuthenticationHandler {
    private const val TAG = "AuthenticationHandler"

    /**
     * Tipo de challenge: determina si usar Authorization o Proxy-Authorization
     * RFC 3261: 401 -> WWW-Authenticate -> Authorization
     *           407 -> Proxy-Authenticate -> Proxy-Authorization
     */
    enum class ChallengeType {
        WWW_AUTHENTICATE,     // 401 -> responder con Authorization
        PROXY_AUTHENTICATE    // 407 -> responder con Proxy-Authorization
    }

    /**
     * Datos de autenticacion extraidos del challenge
     */
    data class AuthData(
        val realm: String,
        val nonce: String,
        val algorithm: String,
        val callId: String,
        val fromTag: String,
        val challengeType: ChallengeType = ChallengeType.WWW_AUTHENTICATE,
        val qop: String? = null,       // qop directive del servidor (ej: "auth")
        val opaque: String? = null,     // opaque del servidor (debe reenviarse)
        val stale: Boolean = false      // si el nonce esta expirado
    )

    /**
     * Extrae datos de autenticacion del mensaje SIP.
     * Detecta automaticamente si es WWW-Authenticate (401) o Proxy-Authenticate (407)
     * para usar el header de respuesta correcto (RFC 3261 Sections 22.1-22.3)
     */
    fun extractAuthenticationData(lines: List<String>): AuthData? {
        // Determinar tipo de challenge segun el header presente
        val challengeType: ChallengeType
        val authHeader: String

        val wwwAuth = lines.find { it.startsWith("WWW-Authenticate:", ignoreCase = true) }
        val proxyAuth = lines.find { it.startsWith("Proxy-Authenticate:", ignoreCase = true) }

        when {
            proxyAuth != null -> {
                challengeType = ChallengeType.PROXY_AUTHENTICATE
                authHeader = proxyAuth
                log.d(tag = TAG) { "Challenge type: Proxy-Authenticate (407)" }
            }
            wwwAuth != null -> {
                challengeType = ChallengeType.WWW_AUTHENTICATE
                authHeader = wwwAuth
                log.d(tag = TAG) { "Challenge type: WWW-Authenticate (401)" }
            }
            else -> {
                log.d(tag = TAG) { "ERROR: No authentication header found (neither WWW-Authenticate nor Proxy-Authenticate)" }
                return null
            }
        }

        val realmMatch = Regex("""realm\s*=\s*"([^"]+)""").find(authHeader)
        val nonceMatch = Regex("""nonce\s*=\s*"([^"]+)""").find(authHeader)
        val algorithmMatch = Regex("""algorithm\s*=\s*([^,\s"]+)""").find(authHeader)
        val qopMatch = Regex("""qop\s*=\s*"?([^",]+)"?""").find(authHeader)
        val opaqueMatch = Regex("""opaque\s*=\s*"([^"]+)""").find(authHeader)
        val staleMatch = Regex("""stale\s*=\s*"?(true|false)"?""", RegexOption.IGNORE_CASE).find(authHeader)

        val realm = realmMatch?.groupValues?.get(1) ?: run {
            log.d(tag = TAG) { "ERROR: Realm not found in challenge" }
            return null
        }

        val nonce = nonceMatch?.groupValues?.get(1) ?: run {
            log.d(tag = TAG) { "ERROR: Nonce not found in challenge" }
            return null
        }

        val algorithm = algorithmMatch?.groupValues?.get(1)?.trim('"') ?: "MD5"

        if (algorithm != "MD5") {
            log.d(tag = TAG) { "ERROR: Unsupported algorithm: $algorithm" }
            return null
        }

        val qop = qopMatch?.groupValues?.get(1)?.trim()
        val opaque = opaqueMatch?.groupValues?.get(1)
        val stale = staleMatch?.groupValues?.get(1)?.equals("true", ignoreCase = true) == true

        val callId = lines.find { it.startsWith("Call-ID:", ignoreCase = true) }
            ?.substring("Call-ID:".length)?.trim() ?: generateId()

        val fromLine = lines.find { it.startsWith("From:", ignoreCase = true) }
        val fromTag = fromLine?.let {
            val tagMatch = Regex("""tag=([^;]+)""").find(it)
            tagMatch?.groupValues?.get(1)
        } ?: generateId()

        return AuthData(
            realm = realm,
            nonce = nonce,
            algorithm = algorithm,
            callId = callId,
            fromTag = fromTag,
            challengeType = challengeType,
            qop = qop,
            opaque = opaque,
            stale = stale
        )
    }

    /**
     * Resultado de autenticacion: response hash + datos de qop para consistencia
     */
    data class AuthResult(
        val response: String,
        val cnonce: String? = null,
        val nc: String? = null
    )

    /**
     * Calcula la respuesta de autenticacion Digest.
     * Soporta tanto modo sin qop como qop=auth (RFC 2617 / RFC 7616).
     * Devuelve AuthResult con cnonce/nc para consistencia con updateAccountAuthInfo.
     */
    fun calculateAuthResponseWithDetails(
        accountInfo: AccountInfo,
        authData: AuthData,
        method: String
    ): AuthResult {
        val username = accountInfo.username
        val password = accountInfo.password
        val realm = authData.realm
        val nonce = authData.nonce

        // HA1 = MD5(username:realm:password)
        val ha1Input = "$username:$realm:$password"
        val ha1 = md5(ha1Input)

        val uri = getUriForMethod(accountInfo, method)
        // HA2 = MD5(method:uri)
        val ha2Input = "$method:$uri"
        val ha2 = md5(ha2Input)

        return if (authData.qop == "auth") {
            // qop=auth: response = MD5(HA1:nonce:nc:cnonce:qop:HA2)
            val cnonce = generateId().take(16)
            val nc = "00000001"
            val responseInput = "$ha1:$nonce:$nc:$cnonce:auth:$ha2"
            AuthResult(
                response = md5(responseInput),
                cnonce = cnonce,
                nc = nc
            )
        } else {
            // Sin qop: response = MD5(HA1:nonce:HA2)
            val responseInput = "$ha1:$nonce:$ha2"
            AuthResult(response = md5(responseInput))
        }
    }

    /**
     * Wrapper de compatibilidad que solo devuelve el hash
     */
    fun calculateAuthResponse(
        accountInfo: AccountInfo,
        authData: AuthData,
        method: String
    ): String = calculateAuthResponseWithDetails(accountInfo, authData, method).response

    /**
     * Actualiza la informacion de autenticacion en la cuenta.
     * Genera el header correcto segun el tipo de challenge:
     * - 401 (WWW-Authenticate) -> Authorization
     * - 407 (Proxy-Authenticate) -> Proxy-Authorization
     * Usa el mismo cnonce/nc que calculateAuthResponseWithDetails para consistencia.
     */
    fun updateAccountAuthInfo(
        accountInfo: AccountInfo,
        authData: AuthData,
        authResult: AuthResult,
        method: String
    ) {
        val username = accountInfo.username
        val realm = authData.realm
        val nonce = authData.nonce
        val algorithm = authData.algorithm
        val uri = getUriForMethod(accountInfo, method)

        // Construir el valor del header Digest
        val authHeaderValue = buildString {
            append("Digest ")
            append("username=\"$username\", ")
            append("realm=\"$realm\", ")
            append("nonce=\"$nonce\", ")
            append("uri=\"$uri\", ")
            append("response=\"${authResult.response}\", ")
            append("algorithm=$algorithm")

            // Incluir qop, cnonce y nc con los MISMOS valores usados en calculateAuthResponse
            if (authData.qop == "auth" && authResult.cnonce != null && authResult.nc != null) {
                append(", qop=auth")
                append(", cnonce=\"${authResult.cnonce}\"")
                append(", nc=${authResult.nc}")
            }

            // Incluir opaque si fue proporcionado por el servidor
            authData.opaque?.let { append(", opaque=\"$it\"") }
        }

        // Actualizar la cuenta con los datos del challenge
        accountInfo.apply {
            this.callId.value = authData.callId
            this.fromTag.value = authData.fromTag
            this.challengeNonce.value = nonce
            this.realm.value = realm
            this.authorizationHeader.value = authHeaderValue
        }

        log.d(tag = TAG) {
            "Auth info updated: challengeType=${authData.challengeType}, " +
            "qop=${authData.qop ?: "none"}, method=$method"
        }
    }

    /**
     * Overload de compatibilidad: acepta response como String (sin qop)
     */
    fun updateAccountAuthInfo(
        accountInfo: AccountInfo,
        authData: AuthData,
        response: String,
        method: String
    ) = updateAccountAuthInfo(accountInfo, authData, AuthResult(response), method)

    /**
     * Devuelve el nombre del header SIP de autorizacion segun el tipo de challenge.
     * RFC 3261:
     *   - 401 -> responder con "Authorization"
     *   - 407 -> responder con "Proxy-Authorization"
     */
    fun getAuthHeaderName(challengeType: ChallengeType): String {
        return when (challengeType) {
            ChallengeType.WWW_AUTHENTICATE -> "Authorization"
            ChallengeType.PROXY_AUTHENTICATE -> "Proxy-Authorization"
        }
    }

    /**
     * Determina la URI a usar basandose en el metodo SIP
     */
    private fun getUriForMethod(accountInfo: AccountInfo, method: String): String {
        return if (method == "INVITE") {
            val callData = accountInfo.currentCallData.value
            if (callData != null && callData.direction == CallDirections.OUTGOING) {
                "sip:${callData.to}@${accountInfo.domain}"
            } else {
                "sip:${accountInfo.domain}"
            }
        } else {
            // Para REGISTER, usar la URI del dominio
            "sip:${accountInfo.domain}"
        }
    }

}