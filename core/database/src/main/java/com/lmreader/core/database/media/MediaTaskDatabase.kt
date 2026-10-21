package com.lmreader.core.database.media

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import com.lmreader.core.database.tasks.TaskDao
import com.lmreader.core.database.tasks.TaskRecord
import com.lmreader.core.database.tasks.TaskRequestRecord

/** One transaction covers catalog membership, configuration, tasks and timeline indexes. */
@Database(entities = [TaskRecord::class, TaskRequestRecord::class, VideoCatalogEntry::class, VideoMediaWatermark::class,
    VideoFrameIndex::class, VideoRegionInterval::class, VideoRegionObservation::class, VideoCueRecord::class,
    VideoSettingsRecord::class],
    version = 2, exportSchema = true)
abstract class MediaTaskDatabase : VideoResultDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun videoCatalogDao(): VideoCatalogDao
    companion object { fun build(context: Context) = Room.databaseBuilder(context.applicationContext,
        MediaTaskDatabase::class.java, "media-work.db")
        .addMigrations(VideoResultMigrations.MIGRATION_1_2).build() }
}
