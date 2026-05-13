package com.eddyslarez.kmpsiprtc.services.matrix

import androidx.room.Room
import androidx.room.RoomDatabase
import com.eddyslarez.kmpsiprtc.platform.AndroidContext
import net.folivo.trixnity.client.store.repository.room.TrixnityRoomDatabase
import java.io.File

internal actual fun matrixRoomDatabaseBuilder(basePath: String): RoomDatabase.Builder<TrixnityRoomDatabase> {
    val ctx = AndroidContext.getApplication()
    val dbFile = File(basePath, "trixnity.db")
    return Room.databaseBuilder(
        ctx,
        TrixnityRoomDatabase::class.java,
        dbFile.absolutePath,
    )
}
