package com.eddyslarez.kmpsiprtc.services.matrix

import com.eddyslarez.kmpsiprtc.platform.log
import net.folivo.trixnity.client.store.repository.createInMemoryRepositoriesModule
import net.folivo.trixnity.client.media.createInMemoryMediaStoreModule
import org.koin.core.module.Module

/**
 * Factory para crear los modulos requeridos por MatrixClient.
 *
 * Actualmente usa almacenamiento en memoria.
 * Para persistencia, reemplazar con createRealmRepositoriesModule / createOkioMediaStoreModule
 * cuando se configure correctamente la ruta de cada plataforma.
 */
object MatrixModuleFactory {
    private const val TAG = "MatrixModuleFactory"

    /**
     * Crea modulos con almacenamiento en memoria (sesion se pierde al reiniciar)
     */
    fun createInMemoryModules(): Pair<Module, Module> {
        log.d(TAG) { "Creating in-memory Matrix modules" }
        return Pair(
            createInMemoryRepositoriesModule(),
            createInMemoryMediaStoreModule()
        )
    }

    /**
     * Crea modulos con almacenamiento persistente.
     * Requiere que se provea la ruta base por plataforma:
     * - Android: context.filesDir.absolutePath
     * - iOS: NSDocumentDirectory path
     * - Desktop: System.getProperty("user.home") + "/.mcnsoftphone"
     *
     * NOTA: Por ahora delega a in-memory. Descomentar cuando las dependencias
     * de Realm/Okio esten correctamente configuradas.
     */
    fun createPersistentModules(basePath: String): Pair<Module, Module> {
        log.d(TAG) { "Creating persistent Matrix modules at: $basePath" }

        // TODO: Descomentar cuando Realm y Okio esten disponibles:
        // val databasePath = "$basePath/matrix/db"
        // val mediaPath = "$basePath/matrix/media"
        // return Pair(
        //     createRealmRepositoriesModule { directory = databasePath },
        //     createOkioMediaStoreModule {
        //         fileSystem = FileSystem.SYSTEM
        //         cacheSize = 100 * 1024 * 1024
        //         directory = mediaPath.toPath()
        //     }
        // )

        // Fallback a in-memory hasta que Realm este configurado
        log.w(TAG) { "Persistent storage not yet configured, using in-memory modules" }
        return createInMemoryModules()
    }

}
