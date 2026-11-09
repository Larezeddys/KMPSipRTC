package com.eddyslarez.kmpsiprtc.services.conference

import dev.onvoid.webrtc.media.audio.AudioTrackSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopRemoteAudioGainTest {

    private class FakePort : RemoteAudioPort {
        val events = mutableListOf<String>()
        var sink: AudioTrackSink? = null
        var failAddSink = false

        override fun addSink(sink: AudioTrackSink) {
            if (failAddSink) throw IllegalStateException("addSink")
            this.sink = sink
            events += "add"
        }

        override fun removeSink(sink: AudioTrackSink) {
            if (this.sink === sink) this.sink = null
            events += "remove"
        }

        override fun setEnabled(enabled: Boolean) {
            events += "enabled=$enabled"
        }
    }

    private class FakeLine(
        override val sampleRate: Int = 48_000,
        override val channels: Int = 2,
    ) : GainLine {
        var open = true
        var failWrite = false
        var closed = false
        val written = mutableListOf<ByteArray>()

        override val isOpen: Boolean get() = open

        override fun available(): Int = Int.MAX_VALUE

        override fun write(pcm: ByteArray) {
            if (failWrite) throw IllegalStateException("write")
            written += pcm
        }

        override fun close() {
            closed = true
            open = false
        }
    }

    private val port = FakePort()
    private val lines = mutableListOf<FakeLine>()
    private val pending = mutableListOf<Runnable>()
    private var lineError: Throwable? = null

    private val gain = DesktopRemoteAudioGain(
        track = port,
        openLine = { lineError?.let { throw it } ?: FakeLine().also { lines += it } },
        recoveryExecutor = Executor { pending += it },
    )

    private fun le16(vararg samples: Int): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }

    private fun runPending() {
        val tasks = pending.toList()
        pending.clear()
        tasks.forEach { it.run() }
    }

    @Test
    fun volumenAjustadoQuitaLaSalidaNativaSoloConLineaYSink() {
        gain.setPercent(50)
        assertEquals(listOf("add", "enabled=false"), port.events)
    }

    @Test
    fun sinLineaLaPistaSigueEnElMezcladorNativo() {
        lineError = javax.sound.sampled.LineUnavailableException("ocupada")
        gain.setPercent(50)
        assertEquals(listOf("enabled=true"), port.events)
    }

    @Test
    fun addSinkFallidoCierraLaLineaYMantieneLaSalidaNativa() {
        port.failAddSink = true
        gain.setPercent(50)
        assertEquals(listOf("enabled=true"), port.events)
        assertTrue(lines.single().closed)
    }

    @Test
    fun elSinkEscribeConLaGananciaFijada() {
        gain.setPercent(50)
        assertNotNull(port.sink).onData(le16(1000, -2000), 16, 48_000, 2, 1)
        assertContentEquals(le16(500, -1000), lines.single().written.single())
    }

    @Test
    fun fallaDeEscrituraDevuelveLaSalidaNativaFueraDelHiloDeAudio() {
        gain.setPercent(50)
        val sink = assertNotNull(port.sink)
        lines.single().failWrite = true
        sink.onData(le16(1000, 1000), 16, 48_000, 2, 1)
        assertEquals(listOf("add", "enabled=false"), port.events)

        runPending()
        assertEquals(listOf("add", "enabled=false", "remove", "enabled=true"), port.events)
        assertTrue(lines.single().closed)
    }

    @Test
    fun lineaCerradaPorElSistemaDevuelveLaSalidaNativa() {
        gain.setPercent(50)
        val sink = assertNotNull(port.sink)
        lines.single().open = false
        sink.onData(le16(1000, 1000), 16, 48_000, 2, 1)
        sink.onData(le16(1000, 1000), 16, 48_000, 2, 1)
        assertEquals(1, pending.size)

        runPending()
        assertEquals("enabled=true", port.events.last())
    }

    @Test
    fun nuevoVolumenTrasLaFallaReabreLaLineaYLaRecuperacionPendienteNoLaToca() {
        gain.setPercent(50)
        val sink = assertNotNull(port.sink)
        lines.single().failWrite = true
        sink.onData(le16(1000, 1000), 16, 48_000, 2, 1)

        gain.setPercent(70)
        runPending()
        assertEquals(listOf("add", "enabled=false", "remove", "add", "enabled=false"), port.events)
        assertEquals(2, lines.size)
        assertTrue(lines[0].closed)
        assertTrue(!lines[1].closed)
    }

    @Test
    fun cienPorCientoVuelveAlMezcladorNativoYCeroLoSilencia() {
        gain.setPercent(50)
        gain.setPercent(100)
        assertEquals(listOf("add", "enabled=false", "remove", "enabled=true"), port.events)
        assertTrue(lines.single().closed)

        gain.setPercent(0)
        assertEquals("enabled=false", port.events.last())
    }

    @Test
    fun releaseDevuelveLaSalidaNativa() {
        gain.setPercent(50)
        gain.release()
        assertEquals(listOf("add", "enabled=false", "remove", "enabled=true"), port.events)
    }

    @Test
    fun mismoFormatoSoloAplicaGananciaConSaturacion() {
        val out = RemotePcmGain.convert(le16(1000, -2000, 30000, -30000), 48_000, 2, 48_000, 2, 1.5)
        assertContentEquals(le16(1500, -3000, 32767, -32768), out)
    }

    @Test
    fun monoSeDuplicaEnEstereo() {
        val out = RemotePcmGain.convert(le16(100, 200), 48_000, 1, 48_000, 2, 1.0)
        assertContentEquals(le16(100, 100, 200, 200), out)
    }

    @Test
    fun estereoSePromediaEnMono() {
        val out = RemotePcmGain.convert(le16(1000, 3000, -2000, -4000), 48_000, 2, 48_000, 1, 1.0)
        assertContentEquals(le16(2000, -3000), out)
    }

    @Test
    fun cambioDeFrecuenciaInterpolaLinealmente() {
        val out = RemotePcmGain.convert(le16(0, 300), 24_000, 1, 48_000, 1, 1.0)
        assertContentEquals(le16(0, 150, 300, 300), out)
    }

    @Test
    fun diezMilisegundosA16kHzDanDiezMilisegundosA48kHz() {
        val out = RemotePcmGain.convert(ByteArray(160 * 2), 16_000, 1, 48_000, 2, 1.0)
        assertEquals(480 * 2 * 2, out.size)
    }

    @Test
    fun entradaVaciaOFormatoInvalidoNoProduceAudio() {
        assertEquals(0, RemotePcmGain.convert(ByteArray(0), 48_000, 2, 48_000, 2, 1.0).size)
        assertEquals(0, RemotePcmGain.convert(le16(1, 2), 0, 2, 48_000, 2, 1.0).size)
    }
}
