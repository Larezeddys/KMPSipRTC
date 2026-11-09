package com.eddyslarez.kmpsiprtc.data.database

import androidx.room.Room
import androidx.room.RoomDatabase
import com.eddyslarez.kmpsiprtc.platform.DesktopDataDirs
import java.io.File

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
    val dbFile = DesktopDataDirs.databaseFile()
    return Room.databaseBuilder<SipDatabase>(
        name = dbFile.absolutePath
    )
}
