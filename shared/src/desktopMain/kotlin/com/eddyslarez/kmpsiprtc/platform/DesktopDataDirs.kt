package com.eddyslarez.kmpsiprtc.platform

import com.eddyslarez.kmpsiprtc.data.database.DatabaseConfig
import java.io.File
import java.nio.file.Files

/**
 * Carpetas de datos de la librería en escritorio, dentro del directorio estándar del SO:
 * Windows `%APPDATA%\<App>`, macOS `~/Library/Application Support/<App>`,
 * Linux `$XDG_DATA_HOME/<app>` o `~/.local/share/<app>`.
 *
 * Las rutas antiguas (`~/.kmpsiprtc<sufijo>`, `~/.mcn-softphone/matrix`) se mueven una sola vez;
 * si el movimiento falla se sigue usando la ruta antigua. Las grabaciones son archivos del usuario y
 * se quedan en `~/CallRecordings`.
 */
object DesktopDataDirs {
    const val DATABASE_FILE_NAME = "sip_database.db"

    private val databaseCompanions = listOf("", "-wal", "-shm", "-journal")

    fun appDataName(): String = DatabaseConfig.desktopAppDataName?.takeIf { it.isNotBlank() }
        ?: "KMPSipRTC${DatabaseConfig.brandSuffix}"

    fun baseDir(): File = resolveBaseDir(
        osName = System.getProperty("os.name").orEmpty(),
        env = System.getenv(),
        userHome = userHome(),
        appName = appDataName()
    ).also { it.mkdirs() }

    fun databaseFile(): File {
        val target = File(baseDir(), DATABASE_FILE_NAME)
        val legacy = File(File(userHome(), ".kmpsiprtc${DatabaseConfig.brandSuffix}"), DATABASE_FILE_NAME)
        return migrateFileGroup(legacy, target, databaseCompanions)
    }

    fun matrixDir(): File {
        val dir = migrateDirectory(File(userHome(), ".mcn-softphone/matrix"), File(baseDir(), "matrix"))
        dir.mkdirs()
        return dir
    }

    fun recordingsDir(): File {
        val dir = File(userHome(), "CallRecordings")
        dir.mkdirs()
        return dir
    }

    fun keysDir(): File = File(baseDir(), "keys").also { it.mkdirs() }

    internal fun resolveBaseDir(osName: String, env: Map<String, String>, userHome: String, appName: String): File {
        val os = osName.lowercase()
        return when {
            os.contains("win") -> {
                val appData = env["APPDATA"]?.takeIf { it.isNotBlank() }
                    ?: File(File(userHome, "AppData"), "Roaming").path
                File(appData, appName)
            }
            os.contains("mac") || os.contains("darwin") ->
                File(File(File(userHome, "Library"), "Application Support"), appName)
            else -> {
                val dataHome = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() && (it.startsWith("/") || File(it).isAbsolute) }
                    ?: File(File(userHome, ".local"), "share").path
                File(dataHome, linuxDirName(appName))
            }
        }
    }

    internal fun linuxDirName(appName: String): String =
        appName.trim().lowercase().replace(Regex("\\s+"), "-")

    /**
     * Mueve `legacy` (y sus ficheros compañeros, p. ej. `-wal`) a `target`. Si `target` ya existe
     * o no hay nada que mover, devuelve `target`. Si algo falla deshace lo movido y devuelve `legacy`.
     */
    internal fun migrateFileGroup(legacy: File, target: File, companions: List<String>): File {
        if (target.exists() || !legacy.exists()) return target
        target.parentFile?.mkdirs()
        val moved = mutableListOf<Pair<File, File>>()
        return try {
            for (suffix in companions) {
                val source = File(legacy.path + suffix)
                if (!source.exists()) continue
                val destination = File(target.path + suffix)
                Files.move(source.toPath(), destination.toPath())
                moved += source to destination
            }
            target
        } catch (e: Exception) {
            moved.asReversed().forEach { (source, destination) ->
                runCatching { Files.move(destination.toPath(), source.toPath()) }
            }
            log.w(tag = "DesktopDataDirs") { "No se pudo mover ${legacy.name}; se mantiene la ruta antigua: ${e.message}" }
            legacy
        }
    }

    internal fun migrateDirectory(legacy: File, target: File): File {
        if (target.exists() || !legacy.isDirectory) return target
        target.parentFile?.mkdirs()
        return try {
            Files.move(legacy.toPath(), target.toPath())
            target
        } catch (e: Exception) {
            log.w(tag = "DesktopDataDirs") { "No se pudo mover ${legacy.name}; se mantiene la ruta antigua: ${e.message}" }
            legacy
        }
    }

    private fun userHome(): String =
        System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir") ?: "."
}
