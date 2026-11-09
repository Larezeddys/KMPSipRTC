package com.eddyslarez.kmpsiprtc.services.conference

import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.audio.JavaSoundDevices
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSink
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine

/**
 * Volumen local de una pista de audio remota.
 *
 * webrtc-java no expone la ganancia de salida por pista: el mezclador nativo solo admite
 * habilitarla o no. Al 100 % suena por el mezclador nativo como siempre y al 0 % se
 * deshabilita. Cualquier otro valor deshabilita la salida nativa y reproduce la pista con
 * ganancia propia: el sink recibe el audio decodificado antes de la ganancia de salida, así
 * que sigue llegando con la pista deshabilitada.
 *
 * La línea de JavaSound se abre en el hilo que fija el volumen, antes de deshabilitar la salida
 * nativa: si no se puede abrir, la pista sigue sonando al 100 %. Si la línea falla después, el
 * hilo de audio solo lo señala y otro hilo quita el sink y devuelve la pista al mezclador nativo.
 *
 * Lo que suena por aquí no pasa por la referencia del cancelador de eco de WebRTC; con
 * altavoces, solo los participantes con volumen ajustado quedan fuera de esa referencia.
 */
internal class DesktopRemoteAudioGain internal constructor(
    private val track: RemoteAudioPort,
    private val openLine: () -> GainLine,
    private val recoveryExecutor: Executor,
) {
    constructor(track: AudioTrack) : this(NativeAudioPort(track), { openJavaSoundLine() }, recoveryThread)

    private val stateLock = Any()
    private var player: GainPlayer? = null

    fun setPercent(percent: Int) {
        synchronized(stateLock) {
            when {
                percent == UNITY_PERCENT -> {
                    detach()
                    setNativeOutput(true)
                }
                percent <= 0 -> {
                    detach()
                    setNativeOutput(false)
                }
                else -> {
                    val gain = percent / 100.0
                    val current = player?.takeIf { it.ready }
                    if (current != null) {
                        current.gain = gain
                    } else {
                        detach()
                        player = attach(gain)
                    }
                    setNativeOutput(player?.ready != true)
                }
            }
        }
    }

    fun release() {
        synchronized(stateLock) {
            detach()
            setNativeOutput(true)
        }
    }

    private fun attach(gain: Double): GainPlayer? {
        val line = try {
            openLine()
        } catch (e: Throwable) {
            log.w(tag = TAG) { "No se pudo abrir la salida para el volumen ajustado: ${e.message}" }
            return null
        }
        val created = GainPlayer(line, gain, ::onPlayerFailed)
        return try {
            track.addSink(created)
            created
        } catch (e: Throwable) {
            log.w(tag = TAG) { "addSink falló: ${e.message}" }
            created.close()
            null
        }
    }

    private fun onPlayerFailed(failed: GainPlayer) {
        try {
            recoveryExecutor.execute { recover(failed) }
        } catch (e: Throwable) {
            log.w(tag = TAG) { "No se pudo programar la vuelta a la salida nativa: ${e.message}" }
        }
    }

    private fun recover(failed: GainPlayer) {
        synchronized(stateLock) {
            if (player === failed) {
                detach()
                setNativeOutput(true)
            }
        }
    }

    private fun detach() {
        val current = player ?: return
        player = null
        runCatching { track.removeSink(current) }
        current.close()
    }

    private fun setNativeOutput(enabled: Boolean) {
        runCatching { track.setEnabled(enabled) }
    }

    private class GainPlayer(
        private val line: GainLine,
        @Volatile var gain: Double,
        private val onFailure: (GainPlayer) -> Unit,
    ) : AudioTrackSink {
        private val lock = ReentrantLock()
        private val failed = AtomicBoolean(false)
        @Volatile private var closed = false

        val ready: Boolean get() = !closed && !failed.get()

        override fun onData(data: ByteArray, bitsPerSample: Int, sampleRate: Int, channels: Int, frames: Int) {
            if (!ready || bitsPerSample != 16 || sampleRate <= 0 || channels <= 0) return
            // El hilo es el de reproducción nativa: nunca espera a la línea ni al cierre.
            if (!lock.tryLock()) return
            try {
                if (closed) return
                if (!line.isOpen) {
                    fail("la línea se cerró")
                    return
                }
                val pcm = RemotePcmGain.convert(data, sampleRate, channels, line.sampleRate, line.channels, gain)
                if (pcm.isNotEmpty() && line.available() >= pcm.size) line.write(pcm)
            } catch (e: Throwable) {
                fail(e.message)
            } finally {
                lock.unlock()
            }
        }

        fun close() {
            closed = true
            lock.lock()
            try {
                line.close()
            } finally {
                lock.unlock()
            }
        }

        private fun fail(reason: String?) {
            if (!failed.compareAndSet(false, true)) return
            log.w(tag = TAG) { "Falló la salida del volumen ajustado, vuelve la salida nativa: $reason" }
            onFailure(this)
        }
    }

    private companion object {
        const val TAG = "RemoteAudioGain"
        const val UNITY_PERCENT = 100
        const val BUFFER_DIVISOR = 5
        val LINE_FORMATS = listOf(48_000 to 2, 48_000 to 1, 44_100 to 2)

        val recoveryThread: Executor by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "remote-audio-gain").apply { isDaemon = true }
            }
        }

        fun openJavaSoundLine(): GainLine {
            var lastError: Throwable? = null
            for ((sampleRate, channels) in LINE_FORMATS) {
                var line: SourceDataLine? = null
                try {
                    val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
                    val opened = JavaSoundDevices.openSourceLine(format)
                    line = opened
                    opened.open(format, sampleRate * channels * 2 / BUFFER_DIVISOR)
                    opened.start()
                    return JavaSoundGainLine(opened, sampleRate, channels)
                } catch (e: Throwable) {
                    line?.let { runCatching { it.close() } }
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("Sin formato de salida")
        }
    }
}

internal interface RemoteAudioPort {
    fun addSink(sink: AudioTrackSink)
    fun removeSink(sink: AudioTrackSink)
    fun setEnabled(enabled: Boolean)
}

/** Línea de salida ya abierta y en marcha, en PCM de 16 bits little-endian. */
internal interface GainLine {
    val sampleRate: Int
    val channels: Int
    val isOpen: Boolean
    fun available(): Int
    fun write(pcm: ByteArray)
    fun close()
}

private class NativeAudioPort(private val track: AudioTrack) : RemoteAudioPort {
    override fun addSink(sink: AudioTrackSink) {
        track.addSink(sink)
    }

    override fun removeSink(sink: AudioTrackSink) {
        track.removeSink(sink)
    }

    override fun setEnabled(enabled: Boolean) {
        track.setEnabled(enabled)
    }
}

private class JavaSoundGainLine(
    private val line: SourceDataLine,
    override val sampleRate: Int,
    override val channels: Int,
) : GainLine {
    override val isOpen: Boolean get() = line.isOpen

    override fun available(): Int = line.available()

    override fun write(pcm: ByteArray) {
        line.write(pcm, 0, pcm.size)
    }

    override fun close() {
        runCatching { line.stop() }
        runCatching { line.flush() }
        runCatching { line.close() }
    }
}

/**
 * Lleva el PCM de 16 bits del sink al formato de la línea: la línea se abre antes de conocer el
 * formato del audio decodificado, que además puede cambiar durante la llamada.
 */
internal object RemotePcmGain {
    fun convert(
        data: ByteArray,
        sampleRate: Int,
        channels: Int,
        targetRate: Int,
        targetChannels: Int,
        gain: Double,
    ): ByteArray {
        if (sampleRate <= 0 || channels <= 0 || targetRate <= 0 || targetChannels <= 0) return ByteArray(0)
        val inFrames = data.size / 2 / channels
        if (inFrames == 0) return ByteArray(0)
        val sameRate = sampleRate == targetRate
        val outFrames = if (sameRate) inFrames else (inFrames.toLong() * targetRate / sampleRate).toInt()
        val out = ByteArray(outFrames * targetChannels * 2)
        for (frame in 0 until outFrames) {
            val position = if (sameRate) frame.toDouble() else frame.toDouble() * sampleRate / targetRate
            val first = position.toInt().coerceAtMost(inFrames - 1)
            val next = (first + 1).coerceAtMost(inFrames - 1)
            val fraction = position - first
            for (channel in 0 until targetChannels) {
                val a = mixed(data, first, channels, channel, targetChannels)
                val b = if (next == first || fraction == 0.0) a else mixed(data, next, channels, channel, targetChannels)
                val value = ((a + (b - a) * fraction) * gain).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                val index = (frame * targetChannels + channel) * 2
                out[index] = value.toByte()
                out[index + 1] = (value shr 8).toByte()
            }
        }
        return out
    }

    private fun mixed(data: ByteArray, frame: Int, channels: Int, channel: Int, targetChannels: Int): Double {
        val base = frame * channels
        if (channels == targetChannels) return sample(data, base + channel).toDouble()
        if (targetChannels == 1) {
            var sum = 0.0
            for (source in 0 until channels) sum += sample(data, base + source)
            return sum / channels
        }
        return sample(data, base + minOf(channel, channels - 1)).toDouble()
    }

    private fun sample(data: ByteArray, index: Int): Int =
        (data[index * 2].toInt() and 0xFF) or (data[index * 2 + 1].toInt() shl 8)
}
