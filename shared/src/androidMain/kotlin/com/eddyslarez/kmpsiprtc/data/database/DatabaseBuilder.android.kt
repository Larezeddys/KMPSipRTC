package com.eddyslarez.kmpsiprtc.data.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.eddyslarez.kmpsiprtc.platform.AndroidContext


actual fun getDatabaseBuilder(): RoomDatabase.Builder<SipDatabase> {
    val appContext: Context = AndroidContext.get()
    val dbFile = appContext.getDatabasePath(SipDatabase.DATABASE_NAME)
    return Room.databaseBuilder(
        context = appContext,
        name = dbFile.absolutePath

    )
}
