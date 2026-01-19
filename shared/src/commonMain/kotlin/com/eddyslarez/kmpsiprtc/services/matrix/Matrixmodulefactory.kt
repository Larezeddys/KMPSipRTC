package com.eddyslarez.kmpsiprtc.services.matrix
//
//import net.folivo.trixnity.client.store.repository.realm.createRealmRepositoriesModule
//import net.folivo.trixnity.client.media.okio.createOkioMediaStoreModule
//import okio.FileSystem
//import okio.Path.Companion.toPath
//import okio.SYSTEM
//import org.koin.core.module.Module
//
///**
// * Factory para crear los módulos requeridos por MatrixClient
// *
// * IMPORTANTE: Debes ajustar las rutas según tu plataforma (Android, iOS, JVM, etc.)
// */
//object MatrixModuleFactory {
//
//    /**
//     * Crea el módulo de repositorios (base de datos)
//     *
//     * @param databasePath Ruta donde se guardará la base de datos
//     * @param encryptionKey Clave opcional para encriptar la base de datos (recomendado)
//     * @return Module de Koin con el repositorio configurado
//     */
//    fun createRepositoriesModule(
//        databasePath: String,
//        encryptionKey: ByteArray? = null
//    ): Module {
//        return createRealmRepositoriesModule {
//            directory = databasePath
//            this.encryptionKey = encryptionKey
//        }
//    }
//
//    /**
//     * Crea el módulo de almacenamiento de medios
//     *
//     * @param cachePath Ruta donde se guardará el caché de medios
//     * @param cacheSize Tamaño máximo del caché en bytes (default: 100 MB)
//     * @return Module de Koin con el media store configurado
//     */
//    fun createMediaStoreModule(
//        cachePath: String,
//        cacheSize: Long = 100 * 1024 * 1024 // 100 MB por defecto
//    ): Module {
//        return createOkioMediaStoreModule {
//            fileSystem = FileSystem.SYSTEM
//            this.cacheSize = cacheSize
//            directory = cachePath.toPath()
//        }
//    }
//
//    // ========== HELPERS ESPECÍFICOS POR PLATAFORMA ==========
//
//    /**
//     * Crea módulos para Android
//     *
//     * EJEMPLO DE USO:
//     * val modules = MatrixModuleFactory.createModulesForAndroid(context)
//     * matrixManager.repositoriesModule = modules.first
//     * matrixManager.mediaStoreModule = modules.second
//     */
//    /*
//    fun createModulesForAndroid(
//        context: android.content.Context,
//        encryptionKey: ByteArray? = null
//    ): Pair<Module, Module> {
//        val databasePath = context.filesDir.absolutePath + "/matrix"
//        val cachePath = context.cacheDir.absolutePath + "/matrix/media"
//
//        return Pair(
//            createRepositoriesModule(databasePath, encryptionKey),
//            createMediaStoreModule(cachePath)
//        )
//    }
//    */
//
//    /**
//     * Crea módulos para iOS
//     *
//     * EJEMPLO DE USO:
//     * val modules = MatrixModuleFactory.createModulesForIOS()
//     * matrixManager.repositoriesModule = modules.first
//     * matrixManager.mediaStoreModule = modules.second
//     */
//    /*
//    fun createModulesForIOS(
//        encryptionKey: ByteArray? = null
//    ): Pair<Module, Module> {
//        val documentsPath = NSSearchPathForDirectoriesInDomains(
//            NSDocumentDirectory,
//            NSUserDomainMask,
//            true
//        ).first() as String
//
//        val cachePath = NSSearchPathForDirectoriesInDomains(
//            NSCachesDirectory,
//            NSUserDomainMask,
//            true
//        ).first() as String
//
//        val databasePath = "$documentsPath/matrix"
//        val mediaPath = "$cachePath/matrix/media"
//
//        return Pair(
//            createRepositoriesModule(databasePath, encryptionKey),
//            createMediaStoreModule(mediaPath)
//        )
//    }
//    */
//
//    /**
//     * Crea módulos para JVM/Desktop
//     *
//     * EJEMPLO DE USO:
//     * val modules = MatrixModuleFactory.createModulesForJVM("/path/to/data")
//     * matrixManager.repositoriesModule = modules.first
//     * matrixManager.mediaStoreModule = modules.second
//     */
//    /*
//    fun createModulesForJVM(
//        basePath: String,
//        encryptionKey: ByteArray? = null
//    ): Pair<Module, Module> {
//        val databasePath = "$basePath/matrix"
//        val cachePath = "$basePath/matrix/media"
//
//        return Pair(
//            createRepositoriesModule(databasePath, encryptionKey),
//            createMediaStoreModule(cachePath)
//        )
//    }
//    */
//}