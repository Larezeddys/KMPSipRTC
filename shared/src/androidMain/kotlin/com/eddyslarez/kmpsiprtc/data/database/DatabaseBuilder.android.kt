package com.eddyslarez.kmpsiprtc.data.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.eddyslarez.kmpsiprtc.platform.AndroidContext


actual fun getDatabaseBuilder(): RoomDatabase.Builder<SipDatabase> {
     val context: Context = AndroidContext.get()
    val dbFile = context.getDatabasePath("sip.db")
    return Room.databaseBuilder<SipDatabase>(
        context = context.applicationContext,
        name = dbFile.absolutePath
    )
}
