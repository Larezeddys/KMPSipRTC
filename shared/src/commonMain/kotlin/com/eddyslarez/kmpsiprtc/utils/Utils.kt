package com.eddyslarez.kmpsiprtc.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.encodeUtf8
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun generateId(): String {
    return "${kotlin.time.Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(100000)}"
}

@OptIn(ExperimentalTime::class)
fun generateSipTag(): String {
    return kotlin.time.Clock.System.now().toEpochMilliseconds().toString() + "-" + (1000..9999).random()
}

fun formatTime(minutes: Long, seconds: Long): String {
    val formattedMinutes = minutes.twoDigits()
    val formattedSeconds = seconds.twoDigits()
    return "$formattedMinutes:$formattedSeconds"
}

fun formatTimeWithHours(hours: Long, minutes: Long, seconds: Long): String {
    val formattedHours = hours.twoDigits()
    val formattedMinutes = minutes.twoDigits()
    val formattedSeconds = seconds.twoDigits()
    return "$formattedHours:$formattedMinutes:$formattedSeconds"
}

fun formatDuration(duration: Int): String {
    val hours = duration / 3600
    val minutes = (duration % 3600) / 60
    val seconds = duration % 60

    return if (hours > 0) {
        "${hours.twoDigits()}:${minutes.twoDigits()}:${seconds.twoDigits()}"
    } else {
        "${minutes.twoDigits()}:${seconds.twoDigits()}"
    }
}
/**
 * Computes MD5 hash of a string input
 */
fun md5(input: String): String {
    // Using placeholder for actual MD5 implementation
    // Real implementation would use platform-specific crypto libraries
    return input.encodeUtf8().md5().hex()
}
private fun Long.twoDigits(): String = if (this < 10) "0$this" else "$this"
private fun Int.twoDigits(): String = if (this < 10) "0$this" else "$this"
class ConcurrentMap<K, V> {
    private val mutex = Mutex()
    private val map = mutableMapOf<K, V>()

    suspend fun put(key: K, value: V) = mutex.withLock {
        map[key] = value
    }

    suspend fun get(key: K): V? = mutex.withLock {
        map[key]
    }

    suspend fun remove(key: K): V? = mutex.withLock {
        map.remove(key)
    }

    suspend fun clear() = mutex.withLock {
        map.clear()
    }

    suspend fun values(): List<V> = mutex.withLock {
        map.values.toList()
    }

    suspend fun keys(): List<K> = mutex.withLock {
        map.keys.toList()
    }

    suspend fun size(): Int = mutex.withLock {
        map.size
    }

    suspend fun containsKey(key: K): Boolean = mutex.withLock {
        map.containsKey(key)
    }

    suspend fun forEach(action: suspend (K, V) -> Unit) = mutex.withLock {
        for ((k, v) in map) action(k, v)
    }

    // Método seguro para obtener snapshot
    suspend fun snapshot(): Map<K, V> = mutex.withLock {
        map.toMap()
    }
}

// =================== AccountRecoveryCounter ===================
class AccountRecoveryCounter {
    private val mutex = Mutex()
    private var attempts = 0

    suspend fun increment(): Int = mutex.withLock {
        attempts += 1
        attempts
    }

    suspend fun reset() = mutex.withLock {
        attempts = 0
    }

    suspend fun get(): Int = mutex.withLock { attempts }
}
/**
 * Genera un nuevo Call-ID único (KMP compatible - sin UUID API)
 */
@OptIn(ExperimentalTime::class)
 fun generateNewCallId(): String {
    val timestamp = Clock.System.now().toEpochMilliseconds()
    val random1 = kotlin.random.Random.nextInt(100000, 999999)
    val random2 = kotlin.random.Random.nextInt(100000, 999999)
    val deviceId = getDeviceIdentifier()
    return "$timestamp-$random1-$random2@$deviceId"
}


/**
 * Genera un nuevo From-Tag único (KMP compatible - sin UUID API)
 */
@OptIn(ExperimentalTime::class)
 fun generateNewFromTag(): String {
    val timestamp = Clock.System.now().toEpochMilliseconds()
    val random = kotlin.random.Random.nextInt(10000000, 99999999)
    return "${timestamp.toString().takeLast(10)}${random}".take(16)
}

/**
 * Obtiene un identificador del dispositivo (KMP compatible)
 */
@OptIn(ExperimentalTime::class)
 fun getDeviceIdentifier(): String {
    val timestamp = Clock.System.now().toEpochMilliseconds()
    val random = kotlin.random.Random.nextInt(10000, 99999)
    return "kmp-${timestamp.hashCode().toString().takeLast(8)}-$random"
}