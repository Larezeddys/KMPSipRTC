package com.eddyslarez.kmpsiprtc.services.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioDeviceNameMatcherTest {

    private data class Dev(val id: String, val name: String)

    private val devices = listOf(
        Dev("{0.0.0.00000000}.{aaaa}", "Speakers (Realtek(R) Audio)"),
        Dev("{0.0.0.00000000}.{bbbb}", "Headphones (Jabra Evolve2 65)"),
        Dev("{0.0.0.00000000}.{cccc}", "LG HDR 4K (NVIDIA High Definition Audio)")
    )

    private fun resolve(query: String) = AudioDeviceNameMatcher.resolve(query, devices, { it.id }, { it.name })

    @Test
    fun resolvesByStableIdFirst() {
        assertEquals(devices[1], resolve("{0.0.0.00000000}.{bbbb}"))
    }

    @Test
    fun resolvesLegacySelectionSavedByName() {
        assertEquals(devices[0], resolve("Speakers (Realtek(R) Audio)"))
        assertEquals(devices[1], resolve("headphones (jabra evolve2 65)"))
    }

    @Test
    fun resolvesJavaSoundTruncatedName() {
        assertEquals(devices[0], resolve("Port Speakers (Realtek(R) Audi"))
    }

    @Test
    fun resolvesWebRtcRolePrefix() {
        assertEquals(devices[1], resolve("Default - Headphones (Jabra Evolve2 65)"))
    }

    @Test
    fun unknownDeviceIsNotGuessed() {
        assertNull(resolve("USB Microphone (Blue Yeti)"))
        assertNull(resolve(""))
    }

    @Test
    fun scoreRanksExactAboveContainmentAboveSharedWords() {
        val exact = AudioDeviceNameMatcher.score("MacBook Pro Speakers", "MacBook Pro Speakers")
        val contained = AudioDeviceNameMatcher.score("MacBook Pro Speakers", "MacBook Pro Speakers (Built-in)")
        val shared = AudioDeviceNameMatcher.score("MacBook Pro Speakers", "MacBook Air Speakers")
        assertEquals(100, exact)
        assertEquals(80, contained)
        assertTrue(shared in 1 until 80)
    }

    @Test
    fun alsaHardwareSuffixIsIgnored() {
        assertEquals(
            AudioDeviceNameMatcher.normalize("PCH"),
            AudioDeviceNameMatcher.normalize("PCH [plughw:0,0]")
        )
    }

    @Test
    fun bestMatchHonoursMinimumScore() {
        val names = listOf("Built-in Audio Analog Stereo", "HDMI Output")
        assertNull(AudioDeviceNameMatcher.bestMatch("PCH [plughw:0,0]", names, minScore = 50) { it })
        assertEquals("HDMI Output", AudioDeviceNameMatcher.bestMatch("HDMI Output Device", names, minScore = 50) { it })
    }
}
