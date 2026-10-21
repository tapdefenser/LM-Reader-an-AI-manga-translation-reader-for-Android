package com.lmreader.core.database.media

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Focused 1 -> 2 check: old video rows survive and the observation table is usable. */
@RunWith(AndroidJUnit4::class)
class VideoResultMigrationsTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MediaTaskDatabase::class.java,
    )

    @Test
    fun migrationKeepsExistingFramesRegionsAndSettings() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO video_settings (videoId, json, style, revision, migrated) " +
                    "VALUES ('video-1', '{\"resultEpoch\":\"r\"}', '{}', 'rev-1', 1)",
            )
            db.execSQL(
                "INSERT INTO video_frames (videoId, sourceHash, identity, sampleIndex, targetUs, ptsUs, untilUs, " +
                    "pageId, resultRevision, noText, producerTaskId) VALUES " +
                    "('video-1', '$HASH', '$IDENTITY', 3, 1000, 900, 1400, 'video:video-1:$HASH:manual:$IDENTITY', " +
                    "'artifact-rev', 0, 'task-1')",
            )
            db.execSQL(
                "INSERT INTO video_regions (intervalId, videoId, sourceHash, pageId, regionId, startMs, endMs, " +
                    "observedStartMs, observedEndMs, manualTiming, deleted, revision, trackId) VALUES " +
                    "('interval-1', 'video-1', '$HASH', 'video:video-1:$HASH:manual:$IDENTITY', 'bubble-1', " +
                    "900, 1400, 900, 1400, 1, 0, 'region-rev', 'track-1')",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, VideoResultMigrations.MIGRATION_1_2).use { db ->
            db.query("SELECT json, revision, migrated FROM video_settings WHERE videoId='video-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("{\"resultEpoch\":\"r\"}", cursor.getString(0))
                assertEquals("rev-1", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
            }
            db.query("SELECT targetUs, ptsUs, untilUs, resultRevision FROM video_frames WHERE videoId='video-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1000L, cursor.getLong(0))
                assertEquals(900L, cursor.getLong(1))
                assertEquals(1400L, cursor.getLong(2))
                assertEquals("artifact-rev", cursor.getString(3))
            }
            db.query("SELECT trackId, endMs, manualTiming FROM video_regions WHERE intervalId='interval-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("track-1", cursor.getString(0))
                assertEquals(1400L, cursor.getLong(1))
                assertEquals(1, cursor.getInt(2))
            }

            db.execSQL(
                "INSERT INTO video_region_observations (videoId, sourceHash, identity, sampleIndex, trackId, " +
                    "targetUs, ptsUs, untilUs, width, height, kind, left, top, right, bottom, contour, appearance) " +
                    "VALUES ('video-1', '$HASH', '$IDENTITY', 4, 'track-1', 1600, 1500, 1900, 1920, 1080, " +
                    "'BUBBLE', 10.5, 20.5, 110.5, 80.5, '[]', NULL)",
            )
            db.query(
                "SELECT trackId, width, height, left FROM video_region_observations " +
                    "WHERE videoId='video-1' AND sampleIndex=4",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("track-1", cursor.getString(0))
                assertEquals(1920, cursor.getInt(1))
                assertEquals(1080, cursor.getInt(2))
                assertEquals(10.5f, cursor.getFloat(3), 0.0001f)
            }
        }
    }

    private companion object {
        const val TEST_DB = "video-result-migration-test.db"
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val IDENTITY = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
    }
}
