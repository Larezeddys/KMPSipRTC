package com.eddyslarez.kmpsiprtc.services.matrix

import androidx.room.Room
import androidx.room.RoomDatabase
import net.folivo.trixnity.client.store.repository.room.TrixnityRoomDatabase
import java.io.File

internal actual fun matrixRoomDatabaseBuilder(basePath: String): RoomDatabase.Builder<TrixnityRoomDatabase> {
    val dbFile = File(basePath, "trixnity.db")
    return Room.databaseBuilder<TrixnityRoomDatabase>(name = dbFile.absolutePath)
}
