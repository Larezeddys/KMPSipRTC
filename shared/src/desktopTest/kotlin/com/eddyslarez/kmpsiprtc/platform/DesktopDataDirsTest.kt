package com.eddyslarez.kmpsiprtc.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDataDirsTest {

    @Test
    fun windowsUsesAppData() {
        val dir = DesktopDataDirs.resolveBaseDir(
            osName = "Windows 11",
            env = mapOf("APPDATA" to "C:\\Users\\ana\\AppData\\Roaming"),
            userHome = "C:\\Users\\ana",
            appName = "KMPSipRTC-mcn"
        )
        assertEquals(File("C:\\Users\\ana\\AppData\\Roaming", "KMPSipRTC-mcn"), dir)
    }

    @Test
    fun windowsWithoutAppDataFallsBackToRoaming() {
        val dir = DesktopDataDirs.resolveBaseDir("Windows 10", emptyMap(), "/home/ana", "App")
        assertEquals(File(File(File("/home/ana", "AppData"), "Roaming"), "App"), dir)
    }

    @Test
    fun macUsesApplicationSupport() {
        val dir = DesktopDataDirs.resolveBaseDir("Mac OS X", emptyMap(), "/Users/ana", "KMPSipRTC-kompaas")
        assertEquals(File("/Users/ana/Library/Application Support/KMPSipRTC-kompaas"), dir)
    }

    @Test
    fun linuxHonoursXdgDataHome() {
        val dir = DesktopDataDirs.resolveBaseDir("Linux", mapOf("XDG_DATA_HOME" to "/data/ana"), "/home/ana", "MCN SuperApp")
        assertEquals(File("/data/ana/mcn-superapp"), dir)
    }

    @Test
    fun linuxIgnoresRelativeXdgAndUsesLocalShare() {
        val dir = DesktopDataDirs.resolveBaseDir("Linux", mapOf("XDG_DATA_HOME" to "rel"), "/home/ana", "KMPSipRTC-mcn")
        assertEquals(File("/home/ana/.local/share/kmpsiprtc-mcn"), dir)
    }

    @Test
    fun databaseGroupIsMovedOnce() = withTempDir { root ->
        val legacy = File(root, "legacy/sip_database.db").apply { parentFile.mkdirs(); writeText("db") }
        File(legacy.path + "-wal").writeText("wal")
        val target = File(root, "new/sip_database.db")

        val result = DesktopDataDirs.migrateFileGroup(legacy, target, listOf("", "-wal", "-shm"))

        assertEquals(target, result)
        assertEquals("db", target.readText())
        assertEquals("wal", File(target.path + "-wal").readText())
        assertFalse(legacy.exists())
        assertEquals(target, DesktopDataDirs.migrateFileGroup(legacy, target, listOf("")))
    }

    @Test
    fun existingTargetWinsAndLegacyIsKept() = withTempDir { root ->
        val legacy = File(root, "legacy/sip_database.db").apply { parentFile.mkdirs(); writeText("old") }
        val target = File(root, "new/sip_database.db").apply { parentFile.mkdirs(); writeText("new") }

        assertEquals(target, DesktopDataDirs.migrateFileGroup(legacy, target, listOf("")))
        assertTrue(legacy.exists())
        assertEquals("new", target.readText())
    }

    @Test
    fun directoryIsMoved() = withTempDir { root ->
        val legacy = File(root, "CallRecordings").apply { mkdirs() }
        File(legacy, "a.wav").writeText("x")
        val target = File(root, "app/recordings")

        assertEquals(target, DesktopDataDirs.migrateDirectory(legacy, target))
        assertTrue(File(target, "a.wav").exists())
        assertFalse(legacy.exists())
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("kmpsiprtc-dirs").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
