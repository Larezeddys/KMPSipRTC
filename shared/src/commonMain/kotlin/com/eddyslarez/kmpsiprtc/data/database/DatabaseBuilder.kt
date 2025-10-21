package com.eddyslarez.kmpsiprtc.data.database


import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.eddyslarez.kmpsiprtc.platform.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO


fun buildSipDatabase(
    builder: RoomDatabase.Builder<SipDatabase>
): SipDatabase {
    return builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(connection: androidx.sqlite.SQLiteConnection) {
                super.onCreate(connection)
                log.d(tag = "DatabaseIntegrityCheck") {"Database created successfully" }
            }

            override fun onDestructiveMigration(connection: SQLiteConnection) {
                super.onDestructiveMigration(connection)
                log.d(tag = "DatabaseIntegrityCheck"){ "Database destroyed successfully" }

            }
            override fun onOpen(connection: androidx.sqlite.SQLiteConnection) {
                super.onOpen(connection)
                log.d(tag = "DatabaseIntegrityCheck"){ "Database opened successfully" }

                // Verificar integridad
                try {
                    connection.prepare("PRAGMA integrity_check").use { statement ->
                        if (statement.step()) {
                            val result = statement.getText(0)
                            log.d(tag = "DatabaseIntegrityCheck"){ "Database integrity check: $result" }
                        }
                    }
                } catch (e: Exception) {
                    log.e(tag = "DatabaseIntegrityCheck") { "Database integrity check failed ${e.message}" }
                }
            }
        })
        .build()
}
// shared/src/commonMain/kotlin/Database.kt

//fun getRoomDatabase(
//    builder: RoomDatabase.Builder<SipDatabase>
//): SipDatabase {
//    return builder
//        .setDriver(BundledSQLiteDriver())
//        .setQueryCoroutineContext(Dispatchers.IO)
//        .build()
//}
/**
 * Función expect para obtener el builder específico de cada plataforma
 */
expect fun getDatabaseBuilder(): RoomDatabase.Builder<SipDatabase>
