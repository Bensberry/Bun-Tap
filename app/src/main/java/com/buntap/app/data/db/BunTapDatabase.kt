package com.buntap.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Local Room database. Version 1 is the first schema; future schema changes must add a
 * Migration so users keep their routines and targets.
 */
@Database(
    entities = [
        TargetEntity::class,
        RoutineEntity::class,
        ClickerProfileEntity::class,
        SessionEntity::class,
        SessionEventEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class BunTapDatabase : RoomDatabase() {
    abstract fun targetDao(): TargetDao
    abstract fun routineDao(): RoutineDao
    abstract fun clickerDao(): ClickerDao
    abstract fun historyDao(): HistoryDao

    companion object {
        fun build(context: Context): BunTapDatabase =
            Room.databaseBuilder(context, BunTapDatabase::class.java, "buntap.db").build()

        /** In-memory database for tests. */
        fun inMemory(context: Context): BunTapDatabase =
            Room.inMemoryDatabaseBuilder(context, BunTapDatabase::class.java).build()
    }
}
