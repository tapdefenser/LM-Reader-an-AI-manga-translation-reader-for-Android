package com.lmreader.reliability

import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.database.tasks.TaskRecord
import com.lmreader.di.AppContainer
import com.lmreader.tasks.TaskScheduler
import com.lmreader.ui.queue.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class ExportIntegrationTest {
    @Test fun enqueuedConfigurationSurvivesStyleChangesRetryAndProcessRecovery() = runBlocking {
        seedRow(container, "library_sources", mapOf("sourceId" to "s", "kind" to "IMAGE_DIRECTORY", "treeUri" to source.toString(),
            "displayPath" to "export fixture", "recursive" to 1, "mode" to "MULTI_CHAPTER", "permission" to "OK", "revision" to 1))
        val placeholder = task("ok", ExportFormat.CBZ).copy(chapterId = "paused-placeholder", state = "PAUSED")
        val queue = queue(placeholder); queue.pauseAndAwait()
        val destination = tree("ok-${UUID.randomUUID()}")
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.takePersistableUriPermission(destination, flags)
        try {
        container.exportSettings.setDestination(com.lmreader.core.model.LayoutMode.MULTI_CHAPTER, destination)
        val options = container.mangaRepository.translationSettings("m").copy(bubbleFontScalePercent = 140, bubbleBold = true, freeTextMaskExpansionPercent = 12)
        container.mangaRepository.updateTranslationSettings("m", options)
        assertEquals(1, queue.enqueue("m", listOf("c")))
        val created = queue.tasks.value.single { it.chapterId == "c" }
        val captured = ExportConfiguration.decode(created.configurationSnapshot!!)
        assertEquals(140, captured.render.fontScalePercent); assertTrue(captured.render.bold)
        container.mangaRepository.updateTranslationSettings("m", options.copy(bubbleFontScalePercent = 80, bubbleBold = false))
        val restored = restart()
        val reloaded = restored.tasks.value.single { it.id == created.id }
        assertEquals(created.configurationSnapshot, reloaded.configurationSnapshot)
        val recovering = restart(write = JSONArray().put(saved(reloaded.copy(state = "FAILED"))
            .put("configurationSnapshot", reloaded.configurationSnapshot)))
        recovering.retry(created.id)
        assertEquals(created.configurationSnapshot, recovering.tasks.value.single().configurationSnapshot)
        } finally { app.contentResolver.releasePersistableUriPermission(destination, flags) }
    }

    @Test fun renderedExportUsesCapturedOpacityInsteadOfSavedOrCurrentStyle() = runBlocking(Dispatchers.IO) {
        val chapter = container.mangaRepository.getChapters("m").single()
        val opened = container.pageSourceFactory.open(source.toString(), chapter) as com.lmreader.core.storage.reader.PageSourceOpenResult.Ready
        val page = opened.source.pages().first()
        val original = opened.source.open(page).use { it.readBytes() }
        val region = com.lmreader.core.model.PageTextRegion("${page.pageId}:fixture", com.lmreader.core.model.RegionKind.BUBBLE,
            com.lmreader.core.model.PixelRect(4f, 4f, 36f, 56f), listOf(
                com.lmreader.core.model.PixelPoint(4f, 4f), com.lmreader.core.model.PixelPoint(36f, 4f),
                com.lmreader.core.model.PixelPoint(36f, 56f), com.lmreader.core.model.PixelPoint(4f, 56f)), "source", emptyList())
        container.localPageTranslator.artifacts.save(page.pageId, com.lmreader.ui.reader.translation.ReaderPageArtifactStore.sha256(original),
            com.lmreader.core.model.LocalTranslationLanguage.ENGLISH, com.lmreader.core.model.LocalTranslationLanguage.ENGLISH,
            40, 60, listOf(com.lmreader.core.model.PageTranslatedRegion(region, "X")), emptyList(), 0,
            com.lmreader.core.model.BubbleRenderSettings(opacityPercent = 0))
        val captured = ExportConfiguration(com.lmreader.core.model.BubbleRenderSettings(com.lmreader.core.model.BubbleFillMode.WHITE, 100), "en", "en")
        val task = task("ok", ExportFormat.CBZ).copy(configurationSnapshot = captured.encode())
        container.mangaRepository.updateTranslationSettings("m", container.mangaRepository.translationSettings("m").copy(bubbleOpacityPercent = 0))
        val queue = queue(task, saved(task).put("configurationSnapshot", task.configurationSnapshot))
        executeThroughScheduler(queue, task)
        assertCompleted(queue, task)
        val output = DocumentFile.fromTreeUri(app, Uri.parse(task.destinationTreeUri))!!.listFiles().single()
        val png = app.contentResolver.openInputStream(output.uri)!!.use { ZipInputStream(it).use { zip ->
            assertEquals("00001.png", zip.nextEntry.name); zip.readBytes()
        } }
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        try { assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(8, 8)) } finally { bitmap.recycle() }
        assertArrayEquals(original, opened.source.open(page).use { it.readBytes() })
    }
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    private lateinit var sourceId: String
    private lateinit var source: Uri
    @Volatile private var runEnabled = false
    @Before fun setup() {
        runBlocking {
            runEnabled = false
            app = IsolatedApp(ApplicationProvider.getApplicationContext())
            container = newContainer()
            container.startupReady.await()
            sourceId = "source-${UUID.randomUUID()}"; source = tree(sourceId)
            seedRow(container, "mangas", mapOf("mangaId" to "m", "anchorDocumentId" to sourceId, "sourceId" to "s", "sourceKind" to "IMAGE_DIRECTORY", "layoutMode" to "MULTI_CHAPTER", "availability" to "AVAILABLE"))
            seedRow(container, "chapters", mapOf("chapterId" to "c", "mangaId" to "m", "documentId" to sourceId, "kind" to "IMAGE_DIRECTORY"))
        }
    }
    @After fun cleanup() {
        runBlocking { closeContainer(container) }
    }
    private fun newContainer(): AppContainer = AppContainer(app, schedulerFactory = { c ->
        TaskScheduler(c, canStart = { runEnabled }, acquire = { _: Boolean -> null }, release = {})
    })
    /** Real process restart: tear the old container/DB scope down before a new singleton queue is created. */
    private suspend fun restart(write: JSONArray? = null): ExportQueueCoordinator {
        closeContainer(container)
        write?.let { File(app.filesDir, "export-queue.json").writeText(it.toString()) }
        container = newContainer()
        container.startupReady.await()
        return container.exportQueue.also { it.pauseAndAwait() }
    }
    private fun tree(id: String): Uri {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val testPackage = instrumentation.context.packageName
        val targetPackage = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.executeShellCommand("am start -W -n $testPackage/com.lmreader.reliability.FaultGrantActivity -e tree $id -e target $targetPackage").use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
        return DocumentsContract.buildTreeDocumentUri(FaultDocumentsProvider.AUTHORITY, id)
    }
    private fun task(mode: String, format: ExportFormat) = ExportTask(UUID.randomUUID().toString(), "m", "c", "漫画", "一章", source.toString(), tree("$mode-${UUID.randomUUID()}").toString(), false, format)
    private fun saved(task: ExportTask): JSONObject = JSONObject().put("id", task.id).put("mangaId", task.mangaId).put("chapterId", task.chapterId)
        .put("mangaTitle", task.mangaTitle).put("chapterTitle", task.chapterTitle).put("sourceTreeUri", task.sourceTreeUri)
        .put("destinationTreeUri", task.destinationTreeUri).put("singleChapter", task.singleChapter).put("format", task.format.name)
        .put("state", task.state).put("completedPages", task.completedPages).put("totalPages", task.totalPages)
    private suspend fun queue(task: ExportTask, json: JSONObject = saved(task)): ExportQueueCoordinator {
        closeContainer(container)
        File(app.filesDir, "export-queue.json").writeText(JSONArray().put(json).toString())
        container = newContainer()
        container.startupReady.await()
        return container.exportQueue.also {
            it.pauseAndAwait()
            if (task.state == "PENDING") assertEquals("EXPORT_QUEUE", canonicalRow(task.id)?.pauseReason)
        }
    }
    private fun canonicalLegacyId(row: TaskRecord): String? = runCatching {
        JSONObject(row.payload).optString("legacyId")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private suspend fun canonicalRow(id: String): TaskRecord? {
        container.taskScheduler.ready.await()
        return container.taskScheduler.database.tasks().all().firstOrNull { it.type == "EXPORT" && canonicalLegacyId(it) == id }
    }

    private suspend fun awaitCanonical(id: String, aboveGeneration: Long, states: Set<String>): TaskRecord =
        withTimeout(30_000) {
            container.taskScheduler.tasks.first { rows -> rows.any { row ->
                row.type == "EXPORT" && canonicalLegacyId(row) == id &&
                    row.generation > aboveGeneration && row.state in states
            } }.first { it.type == "EXPORT" && canonicalLegacyId(it) == id }
        }

    private suspend fun awaitLegacy(queue: ExportQueueCoordinator, id: String, vararg states: String): ExportTask =
        withTimeout(30_000) { queue.tasks.first { rows -> rows.any { it.id == id && it.state in states } }.first { it.id == id } }

    private fun assertCanonical(id: String, state: String) {
        val row = runBlocking { canonicalRow(id) }
        assertNotNull("canonical EXPORT row missing for $id", row)
        assertEquals(state, row!!.state)
    }

    /** Drives the real dispatcher: capture claim, open admission, issue the legacy command, wait for a newer claim. */
    private suspend fun executeThroughScheduler(queue: ExportQueueCoordinator, task: ExportTask, retry: Boolean = false): TaskRecord {
        if (canonicalRow(task.id) == null) queue.pauseAndAwait()
        val previous = requireNotNull(canonicalRow(task.id)) { "legacy task was not adopted by the canonical scheduler" }
        runEnabled = true
        try {
            if (retry) queue.retry(task.id) else queue.resumeAll()
            container.taskScheduler.poke()
            return awaitCanonical(task.id, previous.generation, TaskScheduler.TERMINAL + "WAITING")
        } finally {
            runEnabled = false
        }
    }

    @Test fun actualSafPngJpegAndCbzOutputsReopenInOrder() = runBlocking(Dispatchers.IO) {
        for (format in ExportFormat.entries) {
            val task = task("ok", format); val queue = queue(task)
            executeThroughScheduler(queue, task)
            assertCompleted(queue, task)
            val uri = DocumentFile.fromTreeUri(app, Uri.parse(task.destinationTreeUri))!!.listFiles().single().uri
            if (format == ExportFormat.CBZ) {
                val names = mutableListOf<String>()
                app.contentResolver.openInputStream(uri)!!.use { ZipInputStream(it).use { zip -> while (true) { val entry = zip.nextEntry ?: break; names += entry.name; assertTrue(zip.readBytes().isNotEmpty()) } } }
                assertEquals(listOf("00001.png", "00002.png"), names)
            } else assertEquals(listOf("00001.${format.extension}", "00002.${format.extension}"), DocumentFile.fromTreeUri(app, Uri.parse(task.destinationTreeUri))!!.findFile("漫画 - 一章")!!.listFiles().map { it.name }.sortedBy { it })
            assertEquals(2, DocumentFile.fromTreeUri(app, source)!!.listFiles().size)
            assertFalse(ExportFiles.ownedRoot(File(app.noBackupFilesDir, "export-staging"), task.id).exists())
        }
    }
    @Test fun spaceFailureAndUnsupportedRenameNeverPublishSuccess() = runBlocking(Dispatchers.IO) {
        for (mode in listOf("fail-write", "no-rename", "revoked")) {
            val task = task(mode, ExportFormat.CBZ); val queue = queue(task)
            val terminal = executeThroughScheduler(queue, task)
            val expected = if(mode == "revoked") "WAITING" else "FAILED"
            assertEquals(expected, terminal.state)
            if(mode == "revoked") assertEquals("ACCESS", terminal.waitingReason)
            val failed = awaitLegacy(queue, task.id, if(mode == "revoked") "INTERRUPTED" else "FAILED"); assertNull(failed.outputUri)
            assertCanonical(task.id, expected)
            if(mode == "revoked") {
                // A waiting task still accepts a durable category pause and resumes the same intent.
                queue.pauseAndAwait()
                assertCanonical(task.id, "PAUSED")
                val again = executeThroughScheduler(queue, task)
                assertEquals(terminal.taskId, again.taskId)
                assertEquals("WAITING", again.state)
                assertEquals("ACCESS", again.waitingReason)
            }
            assertTrue(DocumentFile.fromTreeUri(app, Uri.parse(task.destinationTreeUri))!!.listFiles().isEmpty())
            assertTrue(ExportFiles.ownedRoot(File(app.noBackupFilesDir, "export-staging"), task.id).exists())
        }
    }
    @Test fun startupCleansRemoteFileCreatedBeforeUriWasRecorded() = runBlocking(Dispatchers.IO) {
        val task = task("ok", ExportFormat.CBZ).copy(state = "RUNNING")
        val tree = DocumentFile.fromTreeUri(app, Uri.parse(task.destinationTreeUri))!!
        tree.createFile("application/octet-stream", ".lmreader-${task.id}.part")!!
        val queue = queue(task, saved(task).put("publicationPhase", "COPYING").put("publicationName", "漫画 - 一章.cbz"))
        assertTrue(tree.listFiles().isEmpty()); assertEquals("PAUSED", queue.tasks.value.single().state)
        assertNull(queue.tasks.value.single().publicationPhase)
    }
    @Test fun startupRecoversRenameBeforeDoneReceiptWithoutDuplicateOutput() = runBlocking(Dispatchers.IO) {
        val task = task("ok", ExportFormat.CBZ).copy(state = "RUNNING")
        val root = ExportFiles.ownedRoot(File(app.noBackupFilesDir, "export-staging").apply { mkdirs() }, task.id).apply { mkdirs() }
        File(root, "chapter.cbz").writeText("complete fixture")
        val tree = DocumentFile.fromTreeUri(app, Uri.parse(task.destinationTreeUri))!!
        val doc = tree.createFile("application/octet-stream", ".lmreader-${task.id}.part")!!
        val oldUri = doc.uri.toString(); app.contentResolver.openOutputStream(doc.uri)!!.use { it.write("complete fixture".toByteArray()) }
        assertTrue(doc.renameTo("漫画 - 一章.cbz"))
        val queue = queue(task, saved(task).put("publicationPhase", "READY").put("publicationName", "漫画 - 一章.cbz").put("temporaryUri", oldUri))
        // Recovery already found the renamed output: adopt success without running again.
        assertCompleted(queue, task); assertEquals(1, tree.listFiles().size)
        app.contentResolver.openInputStream(tree.listFiles().single().uri)!!.use {
            assertEquals("complete fixture", it.readBytes().toString(Charsets.UTF_8))
        }
    }
    @Test fun retryKeepsFrozenPageBytesAfterSourceChanges() = runBlocking(Dispatchers.IO) {
        val task = task("no-rename", ExportFormat.CBZ); val queue = queue(task)
        val first = executeThroughScheduler(queue, task)
        assertEquals("FAILED", first.state)
        awaitLegacy(queue, task.id, "FAILED")
        val sourcePage = DocumentFile.fromTreeUri(app, source)!!.findFile("1.png")!!
        val before = app.contentResolver.openInputStream(sourcePage.uri)!!.use { it.readBytes() }
        app.contentResolver.openOutputStream(sourcePage.uri, "wt")!!.use { it.write("damaged new source".toByteArray()) }
        val retryDestination = tree("ok-${UUID.randomUUID()}")
        container.exportSettings.setDestination(com.lmreader.core.model.LayoutMode.MULTI_CHAPTER, retryDestination)
        val retried = executeThroughScheduler(queue, task, retry = true)
        assertEquals("SUCCEEDED", retried.state)
        assertCompleted(queue, task)
        val output = DocumentFile.fromTreeUri(app, retryDestination)!!.listFiles().single()
        app.contentResolver.openInputStream(output.uri)!!.use { ZipInputStream(it).use { zip ->
            assertEquals("00001.png", zip.nextEntry.name); assertArrayEquals(before, zip.readBytes())
        } }
    }
    private suspend fun assertCompleted(queue: ExportQueueCoordinator, task: ExportTask) {
        withTimeout(30_000) {
            while (queue.tasks.value.isNotEmpty() || JSONArray(File(app.filesDir, "export-queue.json").readText()).length() != 0) delay(50)
        }
        assertTrue(queue.tasks.value.toString(), queue.tasks.value.isEmpty())
        assertEquals(0, JSONArray(File(app.filesDir, "export-queue.json").readText()).length())
        assertEquals("SUCCEEDED", canonicalRow(task.id)?.state)
        assertFalse(ExportFiles.ownedRoot(File(app.noBackupFilesDir, "export-staging"), task.id).exists())
    }
    @Test fun startupDequeuesOldCompletionAndKeepsUnfinishedTasks() = runBlocking(Dispatchers.IO) {
        val completed = task("ok", ExportFormat.CBZ).copy(state = "DONE")
        val failed = completed.copy(id = UUID.randomUUID().toString(), state = "FAILED")
        val paused = completed.copy(id = UUID.randomUUID().toString(), state = "PAUSED")
        val root = ExportFiles.ownedRoot(File(app.noBackupFilesDir, "export-staging").apply { mkdirs() }, completed.id).apply { mkdirs() }
        File(root, "snapshot.json").writeText("old snapshot")
        val tree = DocumentFile.fromTreeUri(app, Uri.parse(completed.destinationTreeUri))!!
        val output = tree.createFile("application/zip", "completed.cbz")!!
        app.contentResolver.openOutputStream(output.uri)!!.use { it.write("published output".toByteArray()) }
        val queue = restart(write = JSONArray().put(saved(completed).put("outputUri", output.uri.toString()))
            .put(saved(failed)).put(saved(paused)))
        assertEquals(setOf(failed.id, paused.id), queue.tasks.value.map { it.id }.toSet())
        assertEquals(2, JSONArray(File(app.filesDir, "export-queue.json").readText()).length())
        assertFalse(root.exists())
        app.contentResolver.openInputStream(output.uri)!!.use { assertEquals("published output", it.readBytes().toString(Charsets.UTF_8)) }
        assertCanonical(failed.id, "FAILED")
        assertCanonical(paused.id, "PAUSED")
        val restarted = restart()
        assertEquals(setOf(failed.id, paused.id), restarted.tasks.value.map { it.id }.toSet())
        assertCanonical(failed.id, "FAILED")
        assertCanonical(paused.id, "PAUSED")
    }
    @Test fun failedTemporaryDeletionRemainsRecoverableInJournal() = runBlocking(Dispatchers.IO) {
        val task = task("no-delete-no-rename", ExportFormat.CBZ); val queue = queue(task)
        val terminal = executeThroughScheduler(queue, task)
        assertEquals("FAILED", terminal.state)
        val failed = awaitLegacy(queue, task.id, "FAILED"); assertEquals("FAILED", failed.state)
        assertNotNull(failed.temporaryUri); assertEquals("READY", failed.publicationPhase)
        assertCanonical(task.id, "FAILED")
        val restarted = restart()
        val recovered = awaitLegacy(restarted, task.id, "FAILED")
        assertEquals("FAILED", recovered.state)
        assertNotNull(recovered.temporaryUri)
        assertEquals("FAILED", canonicalRow(task.id)?.state)
        assertEquals(1, DocumentFile.fromTreeUri(app, Uri.parse(task.destinationTreeUri))!!.listFiles().size)
    }
    @Test fun documentIdsRetainSlashesPlusAndPercentSigns() {
        for (id in listOf("root/child with space.png", "root/a+b.png", "root/a%20b.png", "root/中文.png")) {
            val uri = container.safAccess.documentUri(source.toString(), id)!!
            assertEquals(id, DocumentsContract.getDocumentId(uri))
        }
    }
    @Test fun queueWriteFailurePausesWithoutDroppingExistingTasks() = runBlocking(Dispatchers.IO) {
        val task = task("ok", ExportFormat.CBZ)
        val queue = queue(task)
        val state = File(app.filesDir, "export-queue.json")
        assertTrue(state.delete()); assertTrue(state.mkdir())
        // A file cannot atomically replace a directory. Exercise the actual commit failure.
        queue.pauseAll()
        assertNotNull(queue.storageFailure.value)
        assertTrue(queue.paused.value)
        assertEquals(task.id, queue.tasks.value.single().id)
        queue.resumeAll(); queue.retry(task.id); queue.resume(task.id)
        assertTrue(queue.paused.value)
        assertNotNull(queue.storageFailure.value)
        assertTrue(DocumentFile.fromTreeUri(app, Uri.parse(task.destinationTreeUri))!!.listFiles().isEmpty())
    }
}
