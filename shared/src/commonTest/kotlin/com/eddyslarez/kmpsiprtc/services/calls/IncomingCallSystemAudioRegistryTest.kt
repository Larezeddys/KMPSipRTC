package com.eddyslarez.kmpsiprtc.services.calls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncomingCallSystemAudioRegistryTest {
    @Test
    fun primeraPushActivaNoSilenciaSiguienteSocket() {
        val registry = IncomingCallSystemAudioRegistry { 1_000L }
        registry.set("push-1", true, "+79001234567", "105")
        assertTrue(registry.bindIncomingCall("sip-1", "79001234567", "105@mcn.ru"))
        assertTrue(registry.isManaged("sip-1"))
        assertFalse(registry.bindIncomingCall("sip-2", "79007654321", "105@mcn.ru"))
        assertFalse(registry.isManaged("sip-2"))
        assertTrue(registry.isManaged("sip-1"))
    }

    @Test
    fun segundaPushTieneAudioSoloParaSuCallId() {
        val registry = IncomingCallSystemAudioRegistry { 1_000L }
        registry.set("push-1", true, "79001234567", "105")
        registry.bindIncomingCall("sip-1", "79001234567", "105@mcn.ru")
        registry.set("push-2", true, "79007654321", "105")
        assertFalse(registry.isManaged("sip-2"))
        assertTrue(registry.bindIncomingCall("sip-2", "79007654321", "105@mcn.ru"))
        assertTrue(registry.isManaged("sip-2"))
        assertEquals("push-2", registry.getOwner("sip-2"))
        assertFalse(registry.isManaged("sip-3"))
    }

    @Test
    fun coincidenciaDeNumeroYCuentaConsumeSoloUnaVez() {
        val registry = IncomingCallSystemAudioRegistry { 1_000L }
        registry.set("push-1", true, "+7 900 123 45 67", "105@mcn.ru")
        assertFalse(registry.bindIncomingCall("otra-cuenta", "79001234567", "105@otro.ru"))
        assertTrue(registry.bindIncomingCall("sip-1", "79001234567", "105@mcn.ru"))
        assertTrue(registry.bindIncomingCall("sip-1", "79001234567", "105@mcn.ru"))
        assertFalse(registry.bindIncomingCall("sip-2", "79001234567", "105@mcn.ru"))
    }

    @Test
    fun callIdExactoTienePrioridadAunqueElNumeroDelPushSeaDiferente() {
        val registry = IncomingCallSystemAudioRegistry { 1_000L }
        registry.set("sip-1", true, "105", "105")
        assertTrue(registry.bindIncomingCall("sip-1", "79001234567", "105@mcn.ru"))
        assertTrue(registry.isManaged("sip-1"))
        assertFalse(registry.bindIncomingCall("sip-2", "105", "105@mcn.ru"))
    }

    @Test
    fun pushDuplicadoNoRearmaPendienteConsumido() {
        val registry = IncomingCallSystemAudioRegistry { 1_000L }
        registry.set("push-1", true, "79001234567", "105")
        registry.bindIncomingCall("sip-1", "79001234567", "105@mcn.ru")
        registry.set("push-1", true, "79001234567", "105")
        assertTrue(registry.isManaged("sip-1"))
        assertFalse(registry.bindIncomingCall("sip-2", "79001234567", "105@mcn.ru"))
    }

    @Test
    fun pendientesCaducanYLasLlamadasAsociadasConservanAudioNativo() {
        var now = 0L
        val registry = IncomingCallSystemAudioRegistry { now }
        registry.set("push-activo", true, "79001234567", "105")
        registry.bindIncomingCall("sip-activo", "79001234567", "105@mcn.ru")
        registry.set("push-sin-invite", true, "79007654321", "105")
        now = 60_000L
        assertFalse(registry.bindIncomingCall("sip-tardio", "79007654321", "105@mcn.ru"))
        assertTrue(registry.isManaged("sip-activo"))
    }

    @Test
    fun limpiarPorReporteOriginalOPorSipIdNoAfectaOtraLlamada() {
        val registry = IncomingCallSystemAudioRegistry { 1_000L }
        registry.set("push-1", true, "79001234567", "105")
        registry.bindIncomingCall("sip-1", "79001234567", "105@mcn.ru")
        registry.set("sip-2", true)
        registry.set("push-1", false)
        assertFalse(registry.isManaged("sip-1"))
        assertTrue(registry.isManaged("sip-2"))
        registry.set("sip-2", false)
        assertFalse(registry.isManaged("sip-2"))
        registry.set("sip-3", true)
        registry.clear()
        assertFalse(registry.isManaged("sip-3"))
    }

    @Test
    fun fallbackSinIdentidadSoloSePermiteEnArranqueConUnaSolaPendiente() {
        val registry = IncomingCallSystemAudioRegistry { 1_000L }
        registry.set("push-1", true, "Unknown", "105")
        assertFalse(registry.bindIncomingCall("sip-1", "79001234567", "105@mcn.ru"))
        assertTrue(registry.bindIncomingCall("sip-1", "79001234567", "105@mcn.ru", true))
        assertEquals("push-1", registry.getOwner("sip-1"))
    }

    @Test
    fun fallbackNuncaCruzaCuentasNiEligeEntreVariasPendientes() {
        val registry = IncomingCallSystemAudioRegistry { 1_000L }
        registry.set("push-1", true, "Unknown", "105@mcn.ru")
        assertFalse(registry.bindIncomingCall("sip-otro", "79001234567", "105@otro.ru", true))
        registry.set("push-2", true, "Unknown", "105@mcn.ru")
        assertFalse(registry.bindIncomingCall("sip-1", "79001234567", "105@mcn.ru", true))
    }

    @Test
    fun pushTardioAsociaInviteExistentePorNumeroYCuenta() {
        val registry = IncomingCallSystemAudioRegistry { 1_000L }
        assertFalse(registry.bindIncomingCall("sip-1", "79001234567", "105@mcn.ru"))
        registry.set("push-tardio", true, "+7 900 123 45 67", "105@mcn.ru")
        val resolved = registry.bindPendingToExisting("push-tardio", listOf(
            IncomingCallSystemAudioRegistry.IncomingCall("sip-1", "79001234567", "105@mcn.ru"),
            IncomingCallSystemAudioRegistry.IncomingCall("sip-otra-cuenta", "79001234567", "105@otro.ru"),
        ))
        assertEquals("sip-1", resolved)
        assertEquals("push-tardio", registry.getOwner("sip-1"))
        assertFalse(registry.isManaged("sip-otra-cuenta"))
    }

    @Test
    fun pushTardioAmbiguoNoRobaLlamadaPeroCallIdExactoDesambigua() {
        val registry = IncomingCallSystemAudioRegistry { 1_000L }
        val incoming = listOf(
            IncomingCallSystemAudioRegistry.IncomingCall("sip-1", "105", "200@mcn.ru"),
            IncomingCallSystemAudioRegistry.IncomingCall("sip-2", "105", "200@mcn.ru"),
        )
        registry.set("push-ambiguo", true, "105", "200")
        assertNull(registry.bindPendingToExisting("push-ambiguo", incoming))
        registry.set("sip-2", true, "105", "200")
        assertEquals("sip-2", registry.bindPendingToExisting("sip-2", incoming))
        assertTrue(registry.isManaged("sip-2"))
        assertFalse(registry.isManaged("sip-1"))
    }
}
