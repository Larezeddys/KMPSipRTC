package com.eddyslarez.kmpsiprtc.services.matrix

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * `highlight_count` por sala. Trixnity solo guarda `notification_count` (`Room.unreadMessageCount`), así que
 * el valor se toma de cada respuesta de `/sync` y se persiste: una sincronización incremental solo trae las
 * salas que cambiaron.
 */
internal object MatrixHighlightCounts {
    private val serializer = MapSerializer(String.serializer(), Int.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    fun merge(
        current: Map<String, Int>,
        joined: Map<String, Long?>,
        left: Collection<String>,
    ): Map<String, Int> {
        val next = current.toMutableMap()
        joined.forEach { (roomId, count) ->
            if (count != null) next[roomId] = count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
        left.forEach { next.remove(it) }
        return if (next == current) current else next
    }

    fun encode(counts: Map<String, Int>): String = json.encodeToString(serializer, counts)

    fun decode(text: String): Map<String, Int> =
        runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyMap())
}
