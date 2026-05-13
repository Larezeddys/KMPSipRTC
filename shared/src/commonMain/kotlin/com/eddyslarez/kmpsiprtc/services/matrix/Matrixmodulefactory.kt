package com.eddyslarez.kmpsiprtc.services.matrix

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eddyslarez.kmpsiprtc.platform.log
import net.folivo.trixnity.client.media.createInMemoryMediaStoreModule
import net.folivo.trixnity.client.media.okio.createOkioMediaStoreModule
import net.folivo.trixnity.client.store.repository.createInMemoryRepositoriesModule
import net.folivo.trixnity.client.store.repository.room.createRoomRepositoriesModule
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import org.koin.core.module.Module

/**
 * Factory para crear los módulos requeridos por MatrixClient.
 *
 * Estado actual de persistencia:
 *  - **Media cache (Okio)** → ACTIVADO si el basePath es válido. Las imágenes
 *    y archivos descargados se persisten en disco — sobreviven a un restart.
 *  - **Repositorios (Room KMP)** → ACTIVADO. La sesión Matrix sobrevive a
 *    un restart de la app: `next_batch` del sync, account, rooms, eventos,
 *    timeline, etc. quedan en `$basePath/trixnity.db`.
 *
 * Ambos módulos hacen fallback gracioso a in-memory si su inicialización
 * falla (path inválido, permisos, mismatch de API), de forma que la app
 * sigue siendo usable aunque la persistencia tenga algún problema.
 */
object MatrixModuleFactory {
    private const val TAG = "MatrixModuleFactory"

    /**
     * Módulos in-memory (todo se pierde al cerrar la app).
     * Útil para tests o cuando no se quiere ensuciar disco.
     */
    fun createInMemoryModules(): Pair<Module, Module> {
        log.d(TAG) { "Creating in-memory Matrix modules" }
        return Pair(
            createInMemoryRepositoriesModule(),
            createInMemoryMediaStoreModule()
        )
    }

    /**
     * Módulos persistentes (Room KMP para state + Okio para media).
     *
     * Cada módulo tiene su propio try/catch con fallback a in-memory
     * — si Room falla pero Okio anda, la media persiste igual; y viceversa.
     */
    fun createPersistentModules(basePath: String): Pair<Module, Module> {
        log.d(TAG) { "Creating persistent Matrix modules at: $basePath" }

        val repositories = try {
            val builder = matrixRoomDatabaseBuilder(basePath)
                .setDriver(BundledSQLiteDriver())
            val mod = createRoomRepositoriesModule(databaseBuilder = builder)
            log.d(TAG) { "Trixnity Room repositories enabled at: $basePath/trixnity.db" }
            mod
        } catch (t: Throwable) {
            log.w(TAG) { "Room repositories init failed (${t.message}), falling back to in-memory" }
            createInMemoryRepositoriesModule()
        }

        val media = try {
            val mediaPath = "$basePath/media".toPath()
            FileSystem.SYSTEM.createDirectories(mediaPath)
            val mod = createOkioMediaStoreModule(
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
