package com.eddyslarez.kmpsiprtc.services.matrix

import com.eddyslarez.kmpsiprtc.platform.log
import net.folivo.trixnity.client.store.repository.createInMemoryRepositoriesModule
import net.folivo.trixnity.client.media.createInMemoryMediaStoreModule
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.core.module.Module

/**
 * Factory para crear los módulos requeridos por MatrixClient.
 *
 * Estado actual de persistencia:
 *  - **Media cache (Okio)** → ACTIVADO si el basePath es válido. Las imágenes
 *    y archivos descargados se persisten en disco — sobreviven a un restart.
 *  - **Repositorios (Room/Realm)** → todavía en memoria. La sesión Matrix
 *    no persiste entre restarts de la app. Pendiente de configurar Room KMP
 *    de Trixnity correctamente (requiere setup específico de databaseFactory
 *    KMP que validamos en una siguiente iteración con build real).
 *
 * Cuando se active Room repository:
 * ```
 * createRoomRepositoriesModule(databaseFactory = { Room.databaseBuilder(...) })
 * ```
 * El path de la DB sería `"$basePath/db/trixnity.db"`.
 */
object MatrixModuleFactory {
    private const val TAG = "MatrixModuleFactory"

    /**
     * Módulos in-memory (todo se pierde al cerrar la app).
     */
    fun createInMemoryModules(): Pair<Module, Module> {
        log.d(TAG) { "Creating in-memory Matrix modules" }
        return Pair(
            createInMemoryRepositoriesModule(),
            createInMemoryMediaStoreModule()
        )
    }

    /**
     * Módulos con media cache persistente. Repositorios siguen in-memory
     * (ver doc de clase).
     *
     * Si la creación del media store Okio falla por cualquier motivo
     * (path inválido, permisos, mismatch de API entre versiones de
     * Trixnity), hace fallback a in-memory y loguea el error.
     */
    fun createPersistentModules(basePath: String): Pair<Module, Module> {
        log.d(TAG) { "Creating persistent Matrix modules at: $basePath" }
        val repositories = createInMemoryRepositoriesModule()
        val media = try {
            val mediaPath = "$basePath/media".toPath()
            // Asegurar que el directorio existe
            FileSystem.SYSTEM.createDirectories(mediaPath)
            val mod = net.folivo.trixnity.client.media.createOkioMediaStoreModule(
                basePath = mediaPath,
                fileSystem = FileSystem.SYSTEM,
            )
            log.d(TAG) { "Okio media store enabled at: $mediaPath" }
            mod
        } catch (t: Throwable) {
            log.w(TAG) { "Okio media store init failed (${t.message}), falling back to in-memory" }
            createInMemoryMediaStoreModule()
        }
        return Pair(repositories, media)
    }
}
