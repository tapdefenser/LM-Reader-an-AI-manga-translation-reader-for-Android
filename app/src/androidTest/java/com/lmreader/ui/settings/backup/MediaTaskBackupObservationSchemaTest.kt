package com.lmreader.ui.settings.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.database.media.MediaTaskDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Closed-schema checks for the schema-1 -> schema-2 backup boundary. These cover only the
 * structural normalization rules; semantic observation rows are validated by the normal
 * backup integration path with real video artifacts.
 */
@RunWith(AndroidJUnit4::class)
class MediaTaskBackupObservationSchemaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var root: File
    private val databases = mutableListOf<MediaTaskDatabase>()

    @Before
    fun setup() {
        root = File(context.cacheDir, "media-backup-schema-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun cleanup() {
        databases.forEach { it.close() }
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test
    fun schemaOneIsNormalizedWithOnlyAnEmptyObservationTable() {
        val db = database()
        val dir = archive(db, "legacy") { document ->
            document.put("schema", 1)
            document.getJSONObject("tables").remove(OBSERVATIONS)
            val columns = document.getJSONObject("tables").getJSONObject("video_regions").getJSONArray("columns")
            columns.remove(columns.length() - 1)
        }
        MediaTaskBackup.validate(db.openHelper.writableDatabase, dir, null, semantic = false)
        val before = File(dir, MediaTaskBackup.FILE).readText()
        MediaTaskBackup.restore(db.openHelper.writableDatabase, dir, false, File(root, "videos"))
        assertEquals("Restore must not rewrite the archive", before, File(dir, MediaTaskBackup.FILE).readText())
    }

    @Test
    fun schemaTwoWithoutObservationsIsRejected() {
        val db = database()
        val dir = archive(db, "missing") { document ->
            document.getJSONObject("tables").remove(OBSERVATIONS)
        }
        assertRejected(db, dir)
    }

    @Test
    fun schemaOneCarryingObservationsIsRejectedAsVersionMix() {
        val db = database()
        val dir = archive(db, "mixed") { document ->
            document.put("schema", 1)
        }
        assertRejected(db, dir)
    }

    @Test
    fun schemaOneMissingAnotherTableIsRejected() {
        val db = database()
        val dir = archive(db, "missing-other") { document ->
            document.put("schema", 1)
            document.getJSONObject("tables").apply {
                remove(OBSERVATIONS)
                remove("tasks")
            }
        }
        assertRejected(db, dir)
    }

    private fun assertRejected(db: MediaTaskDatabase, dir: File) {
        assertThrows(IllegalArgumentException::class.java) {
            MediaTaskBackup.validate(db.openHelper.writableDatabase, dir, null, semantic = false)
        }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(context, MediaTaskDatabase::class.java)
        .build().also { databases += it }

    private fun archive(db: MediaTaskDatabase, name: String, change: (JSONObject) -> Unit): File {
        val dir = File(root, name).apply { mkdirs() }
        MediaTaskBackup.snapshot(db.openHelper.writableDatabase, dir, true)
        val file = File(dir, MediaTaskBackup.FILE)
        val document = JSONObject(file.readText())
        change(document)
        file.writeText(document.toString())
        return dir
    }

    private companion object {
        const val OBSERVATIONS = "video_region_observations"
    }
}
