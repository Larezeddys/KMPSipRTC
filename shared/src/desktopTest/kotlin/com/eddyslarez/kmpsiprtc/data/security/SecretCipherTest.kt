package com.eddyslarez.kmpsiprtc.data.security

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecretCipherTest {

    private val key = SecretKeySpec(ByteArray(SecretCipher.KEY_BYTES) { it.toByte() }, "AES")

    @Test
    fun roundTrip() {
        val stored = SecretCipher.encrypt(key, "pässwörd-123")
        assertTrue(SecretCipher.isEncrypted(stored))
        assertFalse(stored.contains("pässwörd"))
        assertEquals("pässwörd-123", SecretCipher.decrypt(key, stored))
    }

    @Test
    fun eachEncryptionUsesFreshNonce() {
        assertNotEquals(SecretCipher.encrypt(key, "same"), SecretCipher.encrypt(key, "same"))
    }

    @Test
    fun plaintextIsDetectedForMigration() {
        assertFalse(SecretCipher.isEncrypted("legacy-plain-password"))
    }

    @Test
    fun tamperedValueIsRejected() {
        val stored = SecretCipher.encrypt(key, "secret")
        val payload = java.util.Base64.getDecoder().decode(stored.removePrefix(SecretCipher.PREFIX))
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 1).toByte()
        val tampered = SecretCipher.PREFIX + java.util.Base64.getEncoder().encodeToString(payload)
        assertFailsWith<Exception> { SecretCipher.decrypt(key, tampered) }
    }

    @Test
    fun wrongKeyIsRejected() {
        val other = SecretKeySpec(ByteArray(SecretCipher.KEY_BYTES) { 7 }, "AES")
        val stored = SecretCipher.encrypt(key, "secret")
        assertFailsWith<Exception> { SecretCipher.decrypt(other, stored) }
    }

    @Test
    fun keyFileIsCreatedOnceAndOwnerOnly() {
        val dir = Files.createTempDirectory("kmpsiprtc-key").toFile()
        try {
            val file = dir.resolve("keys/sip-secrets.key")
            val first = SecretProtector.loadOrCreateKey(file)
            val second = SecretProtector.loadOrCreateKey(file)
            assertContentEquals(first.encoded, second.encoded)
            assertEquals(SecretCipher.KEY_BYTES, first.encoded.size)
            if ("posix" in file.toPath().fileSystem.supportedFileAttributeViews()) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file.toPath())
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
