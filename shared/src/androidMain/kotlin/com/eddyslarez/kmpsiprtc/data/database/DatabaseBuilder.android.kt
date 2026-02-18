package com.eddyslarez.kmpsiprtc.data.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.eddyslarez.kmpsiprtc.platform.AndroidContext


actual fun getDatabaseBuilder(): RoomDatabase.Builder<SipDatabase> {
    val context: Context = AndroidContext.get()
    val suffix = DatabaseConfig.brandSuffix
    val dbName = "sip$suffix.db"

    return Room.databaseBuilder(
        context.applicationContext,
        SipDatabase::class.java,
        dbName
    )
}

