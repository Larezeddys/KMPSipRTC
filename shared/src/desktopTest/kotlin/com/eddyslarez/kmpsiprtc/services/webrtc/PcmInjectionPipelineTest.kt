package com.eddyslarez.kmpsiprtc.services.webrtc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PcmInjectionPipelineTest {

    private fun le16(vararg samples: Int): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }

    private fun decodeFrame(frame: ByteArray): ShortArray {
        val out = ShortArray(frame.size / 2)
        ByteBuffer.wrap(frame).order(ByteOrder.nativeOrder()).asShortBuffer().get(out)
        return out
    }

    @Test
    fun estereoSePromediaAMono() {
        val stereo = le16(1000, 3000, -2000, -4000)
        assertContentEquals(shortArrayOf(2000, -3000), PcmInjectionPipeline.toMono16(stereo, 2, 16))
    }

    @Test
    fun otrosAnchosDeMuestraSeLlevanA16Bits() {
        assertContentEquals(shortArrayOf(0, -32768), PcmInjectionPipeline.toMono16(byteArrayOf(0x80.toByte(), 0), 1, 8))
        val s24 = byteArrayOf(0x00, 0x34, 0x12)
        assertContentEquals(shortArrayOf(0x1234), PcmInjectionPipeline.toMono16(s24, 1, 24))
        val s32 = byteArrayOf(0, 0, 0xCC.toByte(), 0xFF.toByte())
        assertContentEquals(shortArrayOf(-52), PcmInjectionPipeline.toMono16(s32, 1, 32))
    }

    @Test
    fun formatoNoSoportadoSeRechaza() {
        assertFailsWith<IllegalArgumentException> { PcmInjectionPipeline(24_000, 1, 12) }
        assertFailsWith<IllegalArgumentException> { PcmInjectionPipeline(0, 1, 16) }
    }

    @Test
    fun de24kA48kDuplicaLasMuestrasInterpolando() {
        val out = LinearPcmResampler(24_000, 48_000).process(shortArrayOf(0, 100, 200))
        assertContentEquals(shortArrayOf(0, 50, 100, 150), out)
    }

    @Test
    fun trocearLaEntradaDaLoMismoQueProcesarlaEntera() {
        val input = ShortArray(4_800) { (it * 7 % 2_000 - 1_000).toShort() }
        for ((from, to) in listOf(24_000 to 48_000, 44_100 to 48_000, 16_000 to 48_000, 96_000 to 48_000)) {
            val whole = LinearPcmResampler(from, to).process(input)
            val chunked = LinearPcmResampler(from, to).let { resampler ->
                listOf(0..999, 1_000..1_000, 1_001..3_332, 3_333..4_799)
                    .map { range -> resampler.process(input.sliceArray(range)) }
                    .reduce { acc, part -> acc + part }
            }
            assertContentEquals(whole, chunked, "$from -> $to")
        }
    }

    @Test
    fun cienMilisegundosA24kSonDiezBloquesDe10ms() {
        val pipeline = PcmInjectionPipeline(24_000, 1, 16)
        val first = pipeline.process(le16(*IntArray(2_400) { 1_000 }))
        val second = pipeline.process(le16(*IntArray(2_400) { 1_000 }))
        val frames = first + second
        assertTrue(frames.all { it.size == PcmInjectionPipeline.FRAME_BYTES })
        assertEquals(19, frames.size)
        assertTrue(frames.flatMap { decodeFrame(it).toList() }.all { it.toInt() == 1_000 })
    }

    @Test
    fun elRestoSeCompletaConSilencio() {
        val pipeline = PcmInjectionPipeline(48_000, 1, 16)
        assertEquals(0, pipeline.process(le16(*IntArray(100) { 500 })).size)
        val padded = decodeFrame(pipeline.drainPadded()!!)
        assertEquals(PcmInjectionPipeline.FRAME_SAMPLES, padded.size)
        assertTrue(padded.take(100).all { it.toInt() == 500 })
        assertTrue(padded.drop(100).all { it.toInt() == 0 })
        assertNull(pipeline.drainPadded())
    }

    @Test
    fun cambioDeFormatoSeDetecta() {
        val pipeline = PcmInjectionPipeline(24_000, 1, 16)
        assertTrue(pipeline.matches(24_000, 1, 16))
        assertTrue(!pipeline.matches(48_000, 1, 16))
    }
}
