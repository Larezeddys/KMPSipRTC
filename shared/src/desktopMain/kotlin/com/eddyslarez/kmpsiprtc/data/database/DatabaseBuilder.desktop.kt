package com.eddyslarez.kmpsiprtc.data.database

import androidx.room.RoomDatabase
import androidx.room.Room
import java.io.File


/**
 * Obtiene la ubicación del archivo de base de datos
 * En producción, usar el directorio de la aplicación
 */
private fun getDatabaseFile(): File {
    // Obtener directorio home del usuario
    val userHome = System.getProperty("user.home")

    // Crear directorio de la aplicación si no existe
    val appDir = File(userHome, ".kmpsiprtc")
    if (!appDir.exists()) {
        appDir.mkdirs()
    }

    // Retornar archivo de base de datos
    return File(appDir, "sip_database.db")
}

/**
 * Helper para obtener database en ubicación temporal (útil para testing)
 */
fun getTemporaryDatabaseBuilder(): RoomDatabase.Builder<SipDatabase> {
    val dbFile = File(System.getProperty("java.io.tmpdir"), "sip_database_test.db")
    return Room.databaseBuilder<SipDatabase>(
        name = dbFile.absolutePath
    )
}

actual fun getDatabaseBuilder(): RoomDatabase.Builder<SipDatabase> {
    val dbFile = getDatabaseFile()
    return Room.databaseBuilder<SipDatabase>(
        name = dbFile.absolutePath
    )}