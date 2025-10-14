package com.eddyslarez.kmpsiprtc.data.database


import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * Construye la instancia de SipDatabase con la configuración común
 */
fun buildSipDatabase(
    builder: RoomDatabase.Builder<SipDatabase>
): SipDatabase {
    return builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        // Agregar callback si es necesario
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(connection: androidx.sqlite.SQLiteConnection) {
                super.onCreate(connection)
                // La configuración por defecto se creará desde el Repository
            }
        })
        .build()
}

/**
 * Función expect para obtener el builder específico de cada plataforma
 */
expect fun getDatabaseBuilder(context: Any? = null): RoomDatabase.Builder<SipDatabase>
