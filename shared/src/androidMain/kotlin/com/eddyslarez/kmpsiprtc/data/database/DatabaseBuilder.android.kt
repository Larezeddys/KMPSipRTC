package com.eddyslarez.kmpsiprtc.data.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase


actual fun getDatabaseBuilder(context: Any?): RoomDatabase.Builder<SipDatabase> {
    require(context is Context) { "Expected Android Context" }
    val dbFile = context.getDatabasePath("sip.db")
    return Room.databaseBuilder<SipDatabase>(
        context = context.applicationContext,
        name = dbFile.absolutePath
    )
}
