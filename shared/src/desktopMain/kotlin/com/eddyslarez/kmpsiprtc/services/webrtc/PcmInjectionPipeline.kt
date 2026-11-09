package com.eddyslarez.kmpsiprtc.services.webrtc

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Convierte el PCM que entrega la app (entero little-endian, intercalado) a lo que acepta
 * `CustomAudioSource.pushAudio`: 16 bits en el orden de bytes de la plataforma, mono, 48 kHz y bloques
 * exactos de 10 ms. WebRTC descarta los bloques que no miden 10 ms, así que el resto se guarda para la
 * siguiente llamada.
 */
internal class PcmInjectionPipeline(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
) {
    private val resampler = LinearPcmResampler(sampleRate, TARGET_SAMPLE_RATE)
    private val pending = ShortArray(FRAME_SAMPLES)
    private var pendingCount = 0

    init {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(channels > 0) { "channels must be positive: $channels" }
        require(bitsPerSample in SUPPORTED_BITS) { "Unsupported bitsPerSample: $bitsPerSample" }
    }

    fun matches(sampleRate: Int, channels: Int, bitsPerSample: Int): Boolean =
        this.sampleRate == sampleRate && this.channels == channels && this.bitsPerSample == bitsPerSample

    fun process(pcm: ByteArray): List<ByteArray> {
        val samples = resampler.process(toMono16(pcm, channels, bitsPerSample))
        val frames = ArrayList<ByteArray>(samples.size / FRAME_SAMPLES + 1)
        var index = 0
        while (index < samples.size) {
            val take = minOf(FRAME_SAMPLES - pendingCount, samples.size - index)
            samples.copyInto(pending, pendingCount, index, index + take)
            pendingCount += take
            index += take
            if (pendingCount == FRAME_SAMPLES) {
                frames += encodeFrame(pending)
                pendingCount = 0
            }
        }
        return frames
    }

    /** El resto incompleto, completado con silencio; null si no queda nada. */
    fun drainPadded(): ByteArray? {
        if (pendingCount == 0) return null
        pending.fill(0, pendingCount, FRAME_SAMPLES)
        pendingCount = 0
        return encodeFrame(pending)
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = TARGET_SAMPLE_RATE / 100
        const val FRAME_BYTES = FRAME_SAMPLES * 2
        private val SUPPORTED_BITS = setOf(8, 16, 24, 32)

        fun silenceFrame(): ByteArray = ByteArray(FRAME_BYTES)

        fun encodeFrame(samples: ShortArray): ByteArray {
            val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
            buffer.asShortBuffer().put(samples)
            return buffer.array()
        }

        /** 8 bits sin signo; 16, 24 y 32 con signo. Los canales se promedian. */
        fun toMono16(pcm: ByteArray, channels: Int, bitsPerSample: Int): ShortArray {
            val bytesPerSample = bitsPerSample / 8
            val frameCount = pcm.size / (bytesPerSample * channels)
            val out = ShortArray(frameCount)
            var offset = 0
            for (frame in 0 until frameCount) {
                var sum = 0L
                repeat(channels) {
                    sum += readSample16(pcm, offset, bitsPerSample)
                    offset += bytesPerSample
                }
                out[frame] = (sum / channels).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            return out
        }

        private fun readSample16(pcm: ByteArray, offset: Int, bitsPerSample: Int): Int = when (bitsPerSample) {
            8 -> ((pcm[offset].toInt() and 0xFF) - 128) shl 8
            16 -> (pcm[offset + 1].toInt() shl 8) or (pcm[offset].toInt() and 0xFF)
            24 -> pcm[offset + 2].toInt() shl 8 or (pcm[offset + 1].toInt() and 0xFF)
            else -> pcm[offset + 3].toInt() shl 8 or (pcm[offset + 2].toInt() and 0xFF)
        }
    }
}

/**
 * Interpolación lineal que conserva la posición y la última muestra entre bloques: trocear la entrada
 * da el mismo resultado que procesarla de una vez. La posición va en enteros (1/toRate de muestra de
 * entrada) para que el redondeo no dependa del troceo.
 */
internal class LinearPcmResampler(private val fromRate: Int, private val toRate: Int) {
    private var position = 0L
    private var previous = 0

    fun process(input: ShortArray): ShortArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val last = input.size - 1
        val unit = toRate.toLong()
        val limit = last * unit
        val out = ShortArray(((limit - position) / fromRate).toInt().coerceAtLeast(0) + 2)
        var count = 0
        while (position < limit) {
            val base = Math.floorDiv(position, unit)
            val rem = position - base * unit
            val s0 = if (base < 0) previous else input[base.toInt()].toInt()
            val s1 = input[base.toInt() + 1].toInt()
            out[count++] = (s0 + (s1 - s0) * rem / unit).toInt().toShort()
            position += fromRate
        }
        position -= input.size * unit
        previous = input[last].toInt()
        return out.copyOf(count)
    }
}
