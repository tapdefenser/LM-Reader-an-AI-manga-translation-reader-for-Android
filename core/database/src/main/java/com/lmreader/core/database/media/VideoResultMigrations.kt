package com.lmreader.core.database.media

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migrations shared by both Room databases that own the video result schema
 * (`video-results.db` through [VideoResultDatabase] and `media-work.db` through
 * [MediaTaskDatabase]).
 *
 * There is intentionally no destructive fallback: version 1 rows are preserved and only
 * the observation table plus its three indices are added.
 */
object VideoResultMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `video_regions` ADD COLUMN `anchorUs` INTEGER")
            // Keep this CREATE statement byte-for-byte aligned with Room's generated
            // version 2 schema: column order, NOT NULL flags, primary-key order and names.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `video_region_observations` (" +
                    "`videoId` TEXT NOT NULL, `sourceHash` TEXT NOT NULL, `identity` TEXT NOT NULL, " +
                    "`sampleIndex` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `targetUs` INTEGER NOT NULL, " +
                    "`ptsUs` INTEGER NOT NULL, `untilUs` INTEGER NOT NULL, `width` INTEGER NOT NULL, " +
                    "`height` INTEGER NOT NULL, `kind` TEXT NOT NULL, `left` REAL NOT NULL, " +
                    "`top` REAL NOT NULL, `right` REAL NOT NULL, `bottom` REAL NOT NULL, " +
                    "`contour` TEXT NOT NULL, `appearance` TEXT, " +
                    "PRIMARY KEY(`videoId`, `sourceHash`, `identity`, `sampleIndex`, `trackId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_video_region_observations_videoId_sourceHash_identity_sampleIndex` " +
                    "ON `video_region_observations` (`videoId`, `sourceHash`, `identity`, `sampleIndex`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_video_region_observations_videoId_trackId_ptsUs` " +
                    "ON `video_region_observations` (`videoId`, `trackId`, `ptsUs`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_video_region_observations_videoId_sourceHash_identity_ptsUs` " +
                    "ON `video_region_observations` (`videoId`, `sourceHash`, `identity`, `ptsUs`)",
            )
        }
    }
}
