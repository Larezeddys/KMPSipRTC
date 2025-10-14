package com.eddyslarez.kmpsiprtc.services.audio

data class VibrationPattern(
    val pattern: LongArray,
    val amplitudes: IntArray,
    val repeat: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as VibrationPattern
        if (!pattern.contentEquals(other.pattern)) return false
        if (!amplitudes.contentEquals(other.amplitudes)) return false
        if (repeat != other.repeat) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pattern.contentHashCode()
        result = 31 * result + amplitudes.contentHashCode()
        result = 31 * result + repeat
        return result
    }
}

// Patrones de vibración compartidos
object VibrationPatterns {
    val patterns = mapOf(
        "default" to VibrationPattern(
            pattern = longArrayOf(0, 500, 200, 500, 200),
            amplitudes = intArrayOf(0, 255, 0, 255, 0),
            repeat = 1
        ),
        "gentle" to VibrationPattern(
            pattern = longArrayOf(0, 300, 300, 300, 300),
            amplitudes = intArrayOf(0, 150, 0, 150, 0),
            repeat = 1
        ),
        "strong" to VibrationPattern(
            pattern = longArrayOf(0, 800, 400, 400, 400),
            amplitudes = intArrayOf(0, 255, 0, 200, 0),
            repeat = 1
        ),
        "heartbeat" to VibrationPattern(
            pattern = longArrayOf(0, 100, 100, 200, 600),
            amplitudes = intArrayOf(0, 255, 0, 255, 0),
            repeat = 1
        )
    )
}