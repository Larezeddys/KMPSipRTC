package com.eddyslarez.kmpsiprtc.services.conference

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El formato del marcador de plataforma es un contrato ya desplegado (app Android
 * nativa + cliente web), asi que estas pruebas fijan los bytes exactos: cualquier
 * cambio de formato rompe la interoperabilidad.
 */
class ConferencePlatformDataMessageTest {

    private val canonicalMarkers = listOf(
        PLATFORM_MARKER_ANDROID,
        PLATFORM_MARKER_IOS,
        PLATFORM_MARKER_WEB,
        PLATFORM_MARKER_WINDOWS,
        PLATFORM_MARKER_MAC,
        PLATFORM_MARKER_LINUX,
    )

    @Test
    fun payload_es_byte_compatible_con_el_contrato() {
        assertEquals("""{"type":"platform/ios"}""", buildPlatformDataMessagePayload("ios"))
        assertEquals("""{"type":"platform/windows"}""", buildPlatformDataMessagePayload("windows"))
        assertContentEquals(
            """{"type":"platform/android"}""".encodeToByteArray(),
            buildPlatformDataMessageBytes("android"),
        )
    }

    @Test
    fun ida_y_vuelta_de_todos_los_marcadores_canonicos() {
        canonicalMarkers.forEach { marker ->
            assertEquals(marker, buildPlatformDataMessageBytes(marker).parsePlatformDataMessage())
        }
    }

    @Test
    fun otros_mensajes_del_data_channel_no_se_confunden() {
        assertNull(ByteArray(0).parsePlatformDataMessage())
        assertNull("""{"type":"hand/raise","at":1710000000000}""".encodeToByteArray().parsePlatformDataMessage())
        assertNull("""{"type":"hand/sync/state","raised":true}""".encodeToByteArray().parsePlatformDataMessage())
        assertNull("""{"author":"Alex","message":"hola"}""".encodeToByteArray().parsePlatformDataMessage())
        assertNull("""{}""".encodeToByteArray().parsePlatformDataMessage())
        assertNull("no json".encodeToByteArray().parsePlatformDataMessage())
    }

    @Test
    fun plataforma_desconocida_se_devuelve_tal_cual_para_que_la_ui_decida() {
        assertEquals("tizen", """{"type":"platform/tizen"}""".encodeToByteArray().parsePlatformDataMessage())
        // Sin plataforma no hay nada que anunciar.
        assertNull("""{"type":"platform/"}""".encodeToByteArray().parsePlatformDataMessage())
    }

    @Test
    fun la_plataforma_actual_es_uno_de_los_valores_canonicos() {
        assertTrue(currentPlatformMarker() in canonicalMarkers, "marcador inesperado: ${currentPlatformMarker()}")
    }
}
