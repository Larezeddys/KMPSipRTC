package com.eddyslarez.kmpsiprtc.data.database

import com.eddyslarez.kmpsiprtc.data.database.converters.DatabaseConverters
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.ConstructedBy
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import com.eddyslarez.kmpsiprtc.data.database.dao.*
import com.eddyslarez.kmpsiprtc.data.database.entities.*

@Database(
    entities = [
        SipAccountEntity::class,
        CallLogEntity::class,
        CallDataEntity::class,
        ContactEntity::class,
        CallStateHistoryEntity::class,
        AppConfigEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
@ConstructedBy(SipDatabaseConstructor::class)
abstract class SipDatabase : RoomDatabase() {
    abstract fun sipAccountDao(): SipAccountDao
    abstract fun callLogDao(): CallLogDao
    abstract fun callDataDao(): CallDataDao
    abstract fun contactDao(): ContactDao
    abstract fun callStateDao(): CallHistoryDao
    abstract fun appConfigDao(): AppConfigDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object SipDatabaseConstructor : RoomDatabaseConstructor<SipDatabase> {
    override fun initialize(): SipDatabase
}