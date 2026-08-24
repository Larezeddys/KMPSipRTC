package com.eddyslarez.kmpsiprtc.services.conference

/**
 * Marcador "este participante esta conectado desde <plataforma>", que se difunde
 * por el data-channel de LiveKit (publishData RELIABLE), igual que el chat y el
 * "levantar la mano".
 *
 * Por que por data-channel y no por nombre/metadata/atributos: el token que emite
 * el backend NO concede `canUpdateOwnMetadata`, asi que `updateName`,
 * `updateMetadata` y `updateAttributes` no se aplican en el servidor. El unico
 * mecanismo cliente -> todos que funciona es `publishData`.
 *
 * El formato es un contrato ya desplegado (app Android nativa + cliente web), asi
 * que NO se puede cambiar: JSON UTF-8 exacto, sin espacios ni campos extra:
 *
 *     {"type":"platform/<plataforma>"}
 *
 * Valores canonicos de `<plataforma>`: android, ios, web, windows, mac, linux.
 * Una plataforma desconocida simplemente no pinta icono en el receptor.
 */
const val PLATFORM_DATA_MESSAGE_TYPE_PREFIX = "platform/"

/** Campo JSON que transporta el tipo de mensaje (compartido con los hand/... y el chat). */
private const val PLATFORM_DATA_MESSAGE_TYPE_FIELD = "type"

const val PLATFORM_MARKER_ANDROID = "android"
const val PLATFORM_MARKER_IOS = "ios"
const val PLATFORM_MARKER_WEB = "web"
const val PLATFORM_MARKER_WINDOWS = "windows"
const val PLATFORM_MARKER_MAC = "mac"
const val PLATFORM_MARKER_LINUX = "linux"

/**
 * Marcador de la plataforma en la que corre este cliente.
 * - Android -> [PLATFORM_MARKER_ANDROID]
 * - iOS -> [PLATFORM_MARKER_IOS]
 * - Desktop/JVM -> windows / mac / linux segun `os.name`
 */
expect fun currentPlatformMarker(): String

/** Payload JSON del anuncio propio, byte-compatible con Android nativo y web. */
fun buildPlatformDataMessagePayload(marker: String = currentPlatformMarker()): String =
    """{"$PLATFORM_DATA_MESSAGE_TYPE_FIELD":"$PLATFORM_DATA_MESSAGE_TYPE_PREFIX$marker"}"""

/** El mismo payload en bytes UTF-8, listo para `publishData`. */
fun buildPlatformDataMessageBytes(marker: String = currentPlatformMarker()): ByteArray =
    buildPlatformDataMessagePayload(marker).encodeToByteArray()

// Parser minimo de campos JSON string: los payloads del data-channel son planos y
// llegan de clientes heterogeneos (algunos malformados). Un regex no lanza si el
// texto no es JSON valido, a diferencia de Json.parseToJsonElement.
private val JSON_STRING_FIELD = """"([^"]+)"\s*:\s*"([^"]*)"""".toRegex()

/**
 * Devuelve la plataforma anunciada si [payload] es un marcador de plataforma,
 * o null si es cualquier otro mensaje (chat, hand/..., basura).
 */
fun parsePlatformDataMessage(payload: String): String? {
    val type = JSON_STRING_FIELD.findAll(payload)
        .firstOrNull { it.groupValues[1] == PLATFORM_DATA_MESSAGE_TYPE_FIELD }
        ?.groupValues
        ?.get(2)
        ?: return null
    if (!type.startsWith(PLATFORM_DATA_MESSAGE_TYPE_PREFIX)) return null
    return type.removePrefix(PLATFORM_DATA_MESSAGE_TYPE_PREFIX)
        .trim()
        .lowercase()
        .takeIf { it.isNotEmpty() }
}

/** Variante para los bytes crudos que entrega el data-channel. */
fun ByteArray.parsePlatformDataMessage(): String? =
    if (isEmpty()) null else parsePlatformDataMessage(decodeToString())
