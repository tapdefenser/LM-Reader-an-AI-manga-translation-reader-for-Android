package com.lmreader.ui.video

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import com.lmreader.ui.reader.translation.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VideoDataIntegrationTest {
    private lateinit var root: File
    private lateinit var store: VideoLibraryStore
    private lateinit var project: VideoProject
    private val identity = "a".repeat(64)
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        root = File(context.cacheDir, "video-data-${UUID.randomUUID()}").apply { mkdirs() }
        store = VideoLibraryStore(root)
        project = store.add(VideoProject(UUID.randomUUID().toString(), "content://local/original", "fixture.mp4", "b".repeat(64), 100, 8000, 160, 120, 0))
    }
    @After fun cleanup() { root.deleteRecursively() }
    private fun bitmap(changed: Boolean = false) = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        val canvas = Canvas(this); val p = Paint().apply { color = Color.BLACK }
        for (n in 0..20) if (changed) canvas.drawRect(20f, 22f + n * 2, 135f, 23f + n * 2, p)
            else canvas.drawRect(20f + n * 5, 22f, 22f + n * 5, 65f, p)
    }
    private fun saved(bitmap: Bitmap, at: Long = 1000): ReaderPageTranslation = ReaderPageTranslation("video:${project.id}:$at:$identity", "r", File(root, "unused"),
        LocalTranslationLanguage.JAPANESE, LocalTranslationLanguage.CHINESE_SIMPLIFIED, VideoTranslationEngine.bitmapHash(bitmap), 160, 120,
        listOf(PageTranslatedRegion(PageTextRegion("video:${project.id}:$at:$identity:r", RegionKind.FREE_TEXT, PixelRect(16f, 20f, 140f, 68f), emptyList(), "source", emptyList()), "译文")),
        emptyList(), 10, BubbleRenderSettings(fillMode = BubbleFillMode.WHITE))
    @Test fun tinyStrokeProofSurvivesReopenAndScalingAndRejectsDifferentText() {
        val bitmap = bitmap(); val changed = bitmap(true); val data = saved(bitmap)
        val proof = VideoFrameProof.capture(bitmap, data)
        proof.save(store.proofs(project.id), data.pageId)
        val restored = VideoFrameProof.load(store.proofs(project.id), data.pageId)!!
        val scaled = Bitmap.createScaledBitmap(bitmap, 320, 240, false)
        try { assertTrue(restored.matches(bitmap, data)); assertTrue(restored.matches(scaled, data)); assertFalse(restored.matches(changed, data)) }
        finally { bitmap.recycle(); changed.recycle(); scaled.recycle() }
    }
    @Test fun frameAndHumanRevisionAreFormalDataAndNotRemovedByAnotherMachinePublish() {
        val bitmap = bitmap(); val data = saved(bitmap)
        try {
            VideoFrameProof.capture(bitmap, data).save(store.proofs(project.id), data.pageId)
            store.publishFrame(project.id, project.sha256, 1000, 1350, identity, data) {}
            val original = store.artifacts(project.id).current(data.pageId)!!
            val human = store.artifacts(project.id).saveEdits(original, original.regions.map { it.copy(translatedText = "human") })
            store.publishFrame(project.id, project.sha256, 1000, 1350, identity, data) {}
            val reopened = VideoLibraryStore(root)
            assertEquals(human.revision, reopened.artifacts(project.id).current(data.pageId)!!.revision)
            assertEquals("human", reopened.artifacts(project.id).current(data.pageId)!!.regions.single().translatedText)
            assertEquals(1, reopened.current(project.id).frames.size)
            reopened.validateResults()
        } finally { bitmap.recycle() }
    }
    @Test fun acknowledgedCancellationDoesNotPublishFrameOrProgress() {
        val bitmap = bitmap(); val data = saved(bitmap)
        try {
            try { store.publishFrame(project.id, project.sha256, 1000, 1350, identity, data) { throw kotlinx.coroutines.CancellationException() }; fail("Expected cancel") }
            catch (_: kotlinx.coroutines.CancellationException) {}
            assertTrue(store.current(project.id).frames.isEmpty()); assertNull(store.artifacts(project.id).current(data.pageId))
        } finally { bitmap.recycle() }
    }
    @Test fun stableVideoIdentityKeepsProgressWhenFileReferenceChanges() {
        store.change(project.id) { it.copy(positionMs = 3200) }
        val duplicate = store.add(project.copy(id = UUID.randomUUID().toString(), uri = "content://new/moved"))
        assertEquals(project.id, duplicate.id); assertEquals(3200L, duplicate.positionMs)
        assertEquals("content://new/moved", VideoLibraryStore(root).current(project.id).uri)
        assertEquals(1, store.projects.value.size)
    }
    @Test fun subtitleEditsPersistAndStaleEditorCannotOverwriteThem() {
        val cue = VideoSubtitleCue("one", 100, 800, "hello")
        store.change(project.id) { it.copy(cues = listOf(cue), offsetMs = -100) }
        store.editCue(project.id, cue.id, "人工", cue)
        try { store.editCue(project.id, cue.id, "stale", cue); fail("Expected conflict") } catch (_: IllegalStateException) {}
        val reopened = VideoLibraryStore(root).current(project.id)
        assertEquals(-100L, reopened.offsetMs); assertTrue(reopened.cues.single().manuallyEdited)
        assertEquals("人工", reopened.cues.single().translation)
        assertTrue(VideoSubtitles.encode(reopened.cues, VideoSubtitleDisplay.BILINGUAL, reopened.offsetMs).contains("hello\n人工"))
    }
    @Test fun snapshotContainsFormalDataAndNeverOriginalMedia() {
        val snapshot = File(root.parentFile, "video-snapshot-${UUID.randomUUID()}")
        try { store.snapshot(snapshot); val copied = VideoLibraryStore(snapshot).current(project.id)
            assertEquals(project.sha256, copied.sha256); assertEquals(1, snapshot.walkTopDown().count { it.isFile })
            store.remove(project.id); assertTrue(store.projects.value.isEmpty())
            assertEquals(project.id, VideoLibraryStore(snapshot).projects.value.single().id)
        } finally { snapshot.deleteRecursively() }
    }
}
