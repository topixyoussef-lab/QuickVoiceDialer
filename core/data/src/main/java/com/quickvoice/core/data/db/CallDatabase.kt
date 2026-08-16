package com.quickvoice.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RecentCallEntity::class, ContactVoipLinkEntity::class, SavedNumberEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class CallDatabase : RoomDatabase() {
    abstract fun callDao(): CallDao
    abstract fun contactVoipLinkDao(): ContactVoipLinkDao
    abstract fun savedNumberDao(): SavedNumberDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_numbers` (" +
                        "`number` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "PRIMARY KEY(`number`))"
                )
            }
        }
    }
}
