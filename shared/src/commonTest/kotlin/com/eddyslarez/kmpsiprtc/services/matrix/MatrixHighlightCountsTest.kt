package com.eddyslarez.kmpsiprtc.services.matrix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MatrixHighlightCountsTest {

    @Test
    fun syncActualizaSoloLasSalasQueTraenContador() {
        val current = mapOf("!a" to 2, "!b" to 1)
        val next = MatrixHighlightCounts.merge(
            current = current,
            joined = mapOf("!a" to 0L, "!b" to null, "!c" to 3L),
            left = emptyList(),
        )
        assertEquals(mapOf("!a" to 0, "!b" to 1, "!c" to 3), next)
    }

    @Test
    fun salaAbandonadaSeOlvida() {
        val next = MatrixHighlightCounts.merge(mapOf("!a" to 2, "!b" to 1), emptyMap(), listOf("!a"))
        assertEquals(mapOf("!b" to 1), next)
    }

    @Test
    fun sinCambiosDevuelveElMismoMapa() {
        val current = mapOf("!a" to 2)
        assertSame(current, MatrixHighlightCounts.merge(current, mapOf("!a" to 2L), listOf("!z")))
    }

    @Test
    fun contadorFueraDeRangoSeAcota() {
        val next = MatrixHighlightCounts.merge(emptyMap(), mapOf("!a" to -1L, "!b" to Long.MAX_VALUE), emptyList())
        assertEquals(mapOf("!a" to 0, "!b" to Int.MAX_VALUE), next)
    }

    @Test
    fun persistenciaIdaYVueltaYTextoDanado() {
        val counts = mapOf("!a:mcn.ru" to 4, "!b:mcn.ru" to 0)
        assertEquals(counts, MatrixHighlightCounts.decode(MatrixHighlightCounts.encode(counts)))
        assertEquals(emptyMap<String, Int>(),MatrixHighlightCounts.decode("{roto"))
    }
}
