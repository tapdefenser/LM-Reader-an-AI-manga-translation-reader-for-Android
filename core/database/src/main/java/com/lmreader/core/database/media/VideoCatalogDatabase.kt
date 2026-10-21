package com.lmreader.core.database.media

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Independent catalog database. Keeping it out of `LmReaderDatabase` means a catalog
 * scan can never migrate, reset or destructive-fallback the reader library.
 *
 * There is intentionally no `fallbackToDestructiveMigration`: version 1 is the only
 * version, and any future schema change must ship an explicit migration.
 */
@Database(
    entities = [VideoCatalogEntry::class, VideoMediaWatermark::class],
    version = 1,
    exportSchema = true,
)
abstract class VideoCatalogDatabase : RoomDatabase() {

    abstract fun videoCatalogDao(): VideoCatalogDao

    companion object {
        private const val DATABASE_NAME = "video-catalog.db"

        fun build(context: Context): VideoCatalogDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                VideoCatalogDatabase::class.java,
                DATABASE_NAME,
            ).build()
    }
}
