package com.lmreader.ui.video

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.database.media.*
import com.lmreader.core.database.tasks.TaskRecord
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import com.lmreader.tasks.TaskScheduler
import com.lmreader.ui.reader.translation.ReaderPageArtifactStore
import com.lmreader.ui.reader.translation.ReaderPageTranslation
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Real Room/files/export/import code; a fake task host avoids the foreground service. No network. */
@RunWith(AndroidJUnit4::class)
class VideoResultPackageTest {
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    private lateinit var sourceFile: File
    private lateinit var source: VideoProject
    private lateinit var target: VideoProject
    private lateinit var sourcePage: String
    private lateinit var sourceArtifact: ReaderPageTranslation
    private lateinit var regionIds: List<String>
    private lateinit var cueIds: List<String>

    @Before fun setup() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<android.content.Context>()
        app = IsolatedApp(base)
        container = AppContainer(app, videoResultUri = { Uri.fromFile(it) }) { TaskScheduler(it, canStart = { true }, acquire = { null }, release = {}) }
        container.startupReady.await()
        container.taskScheduler.ready.await()
        sourceFile = File(app.cacheDir, "source-${UUID.randomUUID()}.mp4")
        withContext(Dispatchers.IO) { VideoFixture.create(sourceFile) }
        source = install(sourceFile)
        val targetFile = File(app.cacheDir, "target-${UUID.randomUUID()}.mp4")
        sourceFile.copyTo(targetFile)
        target = install(targetFile)
        assertEquals("target must expose the same verified content", source.sha256, target.sha256)
        seedFormal(source)
    }

    @After fun cleanup() = runBlocking {
        if (::container.isInitialized) {
            runCatching { withTimeout(5_000) { container.taskScheduler.interrupt("TEST_CLEANUP") } }
            runCatching { closeContainer(container) }
            runCatching { container.workDatabase.close() }
        }
        if (::app.isInitialized) app.root.deleteRecursively()
    }

    private suspend fun install(file: File): VideoProject {
        val inspected = VideoMediaSource.inspect(app, Uri.fromFile(file), persist = false)
        container.videoCatalog.importLegacy(inspected.id, inspected.uri, inspected.name, inspected.sha256,
            inspected.bytes, inspected.durationMs, inspected.width, inspected.height, inspected.rotation, 0)
        return container.videoTasks.ensureProject(inspected.id)
    }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private suspend fun seedFormal(project: VideoProject) {
        val identity = sha("frame-${project.id}")
        sourcePage = "video:${project.id}:1000:$identity"
        val regions = listOf("a", "b").map { suffix ->
            PageTranslatedRegion(PageTextRegion("$sourcePage:$suffix", RegionKind.FREE_TEXT,
                PixelRect(10f, 10f, 80f, 40f), emptyList(), "原文$suffix", emptyList()), "译文$suffix")
        }
        regionIds = regions.map { it.region.id }
        sourceArtifact = container.videoLibrary.artifacts(project.id).save(sourcePage, sha("pixels"),
            LocalTranslationLanguage.JAPANESE, LocalTranslationLanguage.CHINESE_SIMPLIFIED, 160, 120, regions,
            listOf("pack"), 5, BubbleRenderSettings(), resultIdentity = identity, producerTaskId = UUID.randomUUID().toString())
        val dao = container.videoResults.results()
        dao.putFrame(VideoFrameIndex(project.id, project.sha256, identity, 0, 1_000_000, 1_000_000, 1_400_000,
            sourcePage, sourceArtifact.revision, false, UUID.randomUUID().toString()))
        dao.addRegion(VideoRegionInterval(UUID.randomUUID().toString(), project.id, project.sha256, sourcePage,
            regionIds[0], 1000, 1400, revision = "r-a"))
        dao.addRegion(VideoRegionInterval(UUID.randomUUID().toString(), project.id, project.sha256, sourcePage,
            regionIds[1], 2000, 2500, 1900, 2600, manualTiming = true, revision = "r-b", trackId = "track-b"))
        dao.addRegion(VideoRegionInterval(UUID.randomUUID().toString(), project.id, project.sha256, sourcePage,
            "$sourcePage:tombstone", 3000, 3500, 3000, 3500, deleted = true, revision = "r-deleted"))
        cueIds = listOf("cue-manual", "cue-deleted")
        dao.addCue(VideoCueRecord(project.id, cueIds[0], project.sha256, "external", 0, 800, "hello", "人工",
            manuallyEdited = true, revision = "c-manual"))
        dao.addCue(VideoCueRecord(project.id, cueIds[1], project.sha256, "external", 800, 1600, "old", "",
            deleted = true, revision = "c-deleted"))
        val settings = dao.settings(project.id) ?: VideoSettingsRecord(project.id)
        dao.putSettings(settings.copy(style = JSONObject().put("fontScale", 140).put("overrideStyle", true).toString(), migrated = true))
    }

    @Test fun bundleRoundTripRetargetsPagesPreservesLocalEditsAndRejectsBadPackages() = runBlocking {
        val exportId = container.videoExports.submit(source.id, "BUNDLE")
        val exported = await(exportId)
        val exportLabel = JSONObject(exported.checkpoint).getString("outputUri")
        assertTrue(exportLabel, exportLabel.isNotBlank())
        // IsolatedApp FileProvider roots can differ; the task-owned cache file is the stable contract.
        val bundle = File(app.cacheDir, "video-exports/$exportId.lmvideo.zip")
        assertTrue("missing owned export archive", bundle.isFile)
        val entries = unzip(bundle)
        val manifestText = entries.getValue("manifest.json").toString(Charsets.UTF_8)
        val manifest = JSONObject(manifestText)
        assertEquals("LMReaderVideoResults", manifest.getString("format"))
        assertTrue("unexpected export member: ${entries.keys}", entries.keys.all {
            it == "manifest.json" || Regex("(pages|proofs)/[a-f0-9]{64}\\.json").matches(it)
        })
        assertFalse("manifest must not leak credentials", manifestText.contains("apiKey") || manifestText.contains("audioApiEndpoint"))
        assertFalse("no original media/model files", entries.keys.any { it.contains(".mp4", true) || it.contains("model", true) || it.contains("credential", true) })

        val imported = await(container.videoResultImports.submit(target.id, Uri.fromFile(bundle)))
        assertTrue(JSONObject(imported.checkpoint).getBoolean("imported"))
        val newPage = sourcePage.replace("video:${source.id}:", "video:${target.id}:")
        val restored = requireNotNull(container.videoLibrary.artifacts(target.id).current(newPage))
        assertEquals(newPage, restored.pageId)
        assertEquals(sourceArtifact.source, restored.source)
        assertEquals(sourceArtifact.target, restored.target)
        assertEquals(sourceArtifact.resultIdentity, restored.resultIdentity)
        assertEquals(sourceArtifact.regions.map { it.translatedText }, restored.regions.map { it.translatedText })
        val dao = container.videoResults.results()
        val frame = dao.frames(target.id, target.sha256).single()
        assertEquals(newPage, frame.pageId)
        assertEquals(1_000_000L, frame.ptsUs)
        assertEquals(1_400_000L, frame.untilUs)
        assertEquals(1_000_000L, frame.targetUs)
        assertEquals(restored.revision, frame.resultRevision)
        val regions = dao.regions(target.id, target.sha256)
        assertEquals(3, regions.size)
        fun targetRegionId(old: String) = old.replace(sourcePage, newPage)
        assertEquals(regions.filterNot { it.deleted }.map { it.regionId }.toSet(),
            restored.regions.map { it.region.id }.toSet())
        val plain = regions.single { it.regionId == targetRegionId(regionIds[0]) }
        assertEquals(newPage, plain.pageId)
        assertEquals(1000L, plain.startMs)
        assertEquals(1400L, plain.endMs)
        val timed = regions.single { it.regionId == targetRegionId(regionIds[1]) }
        assertEquals(newPage, timed.pageId)
        assertTrue(timed.manualTiming)
        assertEquals(1900L, timed.observedStartMs)
        assertEquals(2600L, timed.observedEndMs)
        assertEquals("r-b", timed.revision)
        val tombstone = regions.single { it.regionId == "$newPage:tombstone" }
        assertTrue(tombstone.deleted)
        assertEquals("r-deleted", tombstone.revision)
        val cues = dao.cues(target.id)
        assertTrue(cues.single { it.cueId == cueIds[0] }.manuallyEdited)
        assertTrue(cues.single { it.cueId == cueIds[1] }.deleted)
        assertEquals(140, JSONObject(dao.settings(target.id)!!.style).optInt("fontScale"))

        // A local human edit and cue must survive a second merge even with the same package content.
        val localCue = cues.single { it.cueId == cueIds[0] }.copy(translation = "本地人工", revision = UUID.randomUUID().toString())
        dao.saveCue(localCue, cues.single { it.cueId == cueIds[0] }.revision)
        val edited = container.videoLibrary.artifacts(target.id).saveEdits(restored,
            restored.regions.map { it.copy(translatedText = it.translatedText + "·本地") })
        val second = File(app.cacheDir, "second-${UUID.randomUUID()}.lmvideo.zip")
        bundle.copyTo(second)
        await(container.videoResultImports.submit(target.id, Uri.fromFile(second)))
        val merged = requireNotNull(container.videoLibrary.artifacts(target.id).current(newPage))
        assertTrue(merged.manuallyEdited)
        assertEquals(edited.revision, merged.revision)
        assertEquals(edited.regions.map { it.translatedText }, merged.regions.map { it.translatedText })
        assertEquals("本地人工", dao.cue(target.id, cueIds[0])!!.translation)
        assertTrue(dao.cue(target.id, cueIds[0])!!.manuallyEdited)

        // Malicious packages fail before publication and never touch the source or existing rows.
        val sourceHash = ReaderPageArtifactStore.hashFile(sourceFile)
        val sourceFrames = dao.frames(source.id, source.sha256)
        val sourceCues = dao.cues(source.id)
        val targetFrames = dao.frames(target.id, target.sha256)
        val targetCues = dao.cues(target.id)
        val traversal = File(app.cacheDir, "traversal-${UUID.randomUUID()}.zip").also { file ->
            zip(file, listOf("manifest.json" to entries.getValue("manifest.json"), "../escape.json" to "{}".toByteArray()))
        }
        val mismatch = JSONObject(manifestText).put("sourceSha256", "0".repeat(64))
        val mismatchEntries = entries.mapValues { (name, bytes) -> if (name == "manifest.json") mismatch.toString().toByteArray() else bytes }
        val mismatchZip = File(app.cacheDir, "mismatch-${UUID.randomUUID()}.zip").also { zip(it, mismatchEntries.toList()) }
        val duplicate = File(app.cacheDir, "duplicate-${UUID.randomUUID()}.zip")
        duplicateZip(duplicate, entries.getValue("manifest.json"))
        val bad = listOf(
            "成果包包含无效或重复文件" to traversal,
            "成果包与原视频内容不匹配" to mismatchZip,
            "成果包包含无效或重复文件" to duplicate,
        )
        for ((message, file) in bad) {
            val task = await(container.videoResultImports.submit(target.id, Uri.fromFile(file)), "FAILED")
            // Android 14+ may reject traversal in ZipInputStream before our allowlist sees it.
            val platformRejectedTraversal = file == traversal &&
                task.failure.orEmpty().contains("Invalid zip entry path: ../escape.json")
            assertTrue("expected $message, got ${task.failure}",
                task.failure.orEmpty().contains(message) || platformRejectedTraversal)
            assertEquals(sourceHash, ReaderPageArtifactStore.hashFile(sourceFile))
            assertEquals(sourceFrames, dao.frames(source.id, source.sha256))
            assertEquals(sourceCues, dao.cues(source.id))
            assertEquals(targetFrames, dao.frames(target.id, target.sha256))
            assertEquals(targetCues, dao.cues(target.id))
        }
    }

    private suspend fun await(id: String, state: String = "SUCCEEDED"): TaskRecord = withTimeout(20_000) {
        while (true) {
            val row = container.workDatabase.tasks().get(id) ?: error("task $id disappeared")
            if (row.state == state) return@withTimeout row
            if (row.state in TaskScheduler.TERMINAL) error("task $id ended ${row.state}: ${row.failure}")
            delay(25)
        }
        error("unreachable")
    }

    private fun unzip(file: File): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    private fun zip(file: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }

    /** ZipOutputStream rejects duplicates; ZipInputStream accepts two local headers with one name. */
    private fun duplicateZip(file: File, manifest: ByteArray) {
        file.outputStream().use { out ->
            fun u16(value: Int) { out.write(value and 255); out.write((value ushr 8) and 255) }
            fun u32(value: Long) { for (i in 0..3) out.write(((value ushr (8 * i)) and 255).toInt()) }
            repeat(2) {
                val name = "manifest.json".toByteArray()
                val crc = CRC32().apply { update(manifest) }.value
                u32(0x04034b50); u16(20); u16(0); u16(0); u16(0); u16(0)
                u32(crc); u32(manifest.size.toLong()); u32(manifest.size.toLong())
                u16(name.size); u16(0); out.write(name); out.write(manifest)
            }
            u32(0x06054b50); u16(0); u16(0); u16(0); u16(0); u32(0); u32(0); u16(0)
        }
    }
}
