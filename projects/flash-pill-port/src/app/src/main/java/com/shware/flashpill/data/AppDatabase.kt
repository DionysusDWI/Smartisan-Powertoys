package com.shware.flashpill.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Capsule::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun capsuleDao(): CapsuleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "flashpill.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}