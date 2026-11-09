package com.eddyslarez.kmpsiprtc.data.security

import com.eddyslarez.kmpsiprtc.platform.DesktopDataDirs
import com.eddyslarez.kmpsiprtc.platform.log
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM con una clave aleatoria por instalación guardada en `keys/sip-secrets.key` dentro
 * de la carpeta de datos, legible solo por el usuario (0600 en POSIX, ACL del propietario en
 * Windows). No usa el llavero del SO: protege la base de datos copiada o respaldada sin la
 * carpeta `keys`, no frente a un proceso con los permisos del usuario.
 */
internal actual object SecretProtector {
    private const val TAG = "SecretProtector"

    @Volatile
    private var cachedKey: SecretKey? = null

    actual fun protect(plain: String): String {
        if (plain.isEmpty() || SecretCipher.isEncrypted(plain)) return plain
        val key = key() ?: return plain
        return SecretCipher.encrypt(key, plain)
    }

    actual fun reveal(stored: String): String {
        if (!SecretCipher.isEncrypted(stored)) return stored
        val key = key() ?: return ""
        return try {
            SecretCipher.decrypt(key, stored)
        } catch (e: Exception) {
            log.w(tag = TAG) { "No se pudo descifrar un secreto guardado (${e::class.simpleName})" }
            ""
        }
    }

    actual fun isProtected(stored: String): Boolean = SecretCipher.isEncrypted(stored)

    private fun key(): SecretKey? {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey ?: runCatching { loadOrCreateKey(File(DesktopDataDirs.keysDir(), "sip-secrets.key")) }
                .onFailure { log.e(tag = TAG) { "Clave de cifrado no disponible: ${it.message}" } }
                .getOrNull()
                ?.also { cachedKey = it }
        }
    }

    internal fun loadOrCreateKey(file: File): SecretKey {
        if (file.exists()) {
            val bytes = Base64.getDecoder().decode(file.readText().trim())
            require(bytes.size == SecretCipher.KEY_BYTES) { "Clave con tamaño inesperado" }
            return SecretKeySpec(bytes, "AES")
        }
        val bytes = ByteArray(SecretCipher.KEY_BYTES).also { SecureRandom().nextBytes(it) }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.delete()
        createOwnerOnlyFile(temp.toPath())
        temp.writeText(Base64.getEncoder().encodeToString(bytes))
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return SecretKeySpec(bytes, "AES")
    }

    private fun createOwnerOnlyFile(path: Path) {
        val fileSystem = path.fileSystem
        if ("posix" in fileSystem.supportedFileAttributeViews()) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            return
        }
        Files.createFile(path)
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: return
        val owner = acl.owner
        val entry = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(
                AclEntryPermission.READ_DATA,
                AclEntryPermission.WRITE_DATA,
                AclEntryPermission.APPEND_DATA,
                AclEntryPermission.READ_NAMED_ATTRS,
                AclEntryPermission.WRITE_NAMED_ATTRS,
                AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.WRITE_ATTRIBUTES,
                AclEntryPermission.READ_ACL,
                AclEntryPermission.WRITE_ACL,
                AclEntryPermission.DELETE,
                AclEntryPermission.SYNCHRONIZE
            )
            .build()
        acl.acl = listOf(entry)
    }
}

internal object SecretCipher {
    const val PREFIX = "enc:v1:"
    const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(key: SecretKey, plain: String): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(iv + sealed)
    }

    fun decrypt(key: SecretKey, stored: String): String {
        val payload = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        require(payload.size > IV_BYTES) { "Secreto truncado" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES))
        val plain = cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES)
        return String(plain, Charsets.UTF_8)
    }
}
