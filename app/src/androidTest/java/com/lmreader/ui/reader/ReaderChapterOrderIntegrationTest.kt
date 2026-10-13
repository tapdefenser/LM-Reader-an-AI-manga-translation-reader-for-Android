package com.lmreader.ui.reader

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.core.index.ChapterOrdering
import com.lmreader.core.index.NaturalOrder
import com.lmreader.core.model.ReaderSettings
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import com.lmreader.reliability.seedRow
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real repository and SAF page sources; every test owns its database, preferences and documents. */
@RunWith(AndroidJUnit4::class)
class ReaderChapterOrderIntegrationTest {
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    private lateinit var tree: Uri
    private val root = "reader-order-${UUID.randomUUID()}"
    private val store = ViewModelStore()
    private val readerJobs = mutableListOf<Job>()
    private var readerNumber = 0

    @Before fun seed() = runBlocking {
        app = IsolatedApp(ApplicationProvider.getApplicationContext())
        container = AppContainer(app)
        container.startupReady.await()
        container.readerPreferences.update { ReaderSettings(preloadPages = 9, showChapterTransitions = true) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testPackage = instrumentation.context.packageName
        val authority = app.packageManager.getPackageInfo(testPackage, PackageManager.GET_PROVIDERS)
            .providers.orEmpty().single { it.name == "com.lmreader.reliability.FaultDocumentsProvider" }.authority
        tree = DocumentsContract.buildTreeDocumentUri(authority, root)
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n $testPackage/com.lmreader.reliability.FaultGrantActivity -e authority $authority -e tree $root -e target ${app.packageName}",
        ).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        seedRow(container, "library_sources", mapOf("sourceId" to "s", "kind" to "IMAGE_DIRECTORY", "treeUri" to tree.toString(), "mode" to "MULTI_CHAPTER", "permission" to "OK"))
        seedRow(container, "mangas", mapOf("mangaId" to "m", "sourceId" to "s", "anchorDocumentId" to root, "sourceKind" to "IMAGE_DIRECTORY", "layoutMode" to "MULTI_CHAPTER", "availability" to "AVAILABLE", "chapterCountKnown" to 1))
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, root)
        listOf(1, 10, 2, 20, 3, 30, 4, 40).forEachIndexed { index, number ->
            val title = "Chapter $number"
            val folder = checkNotNull(DocumentsContract.createDocument(app.contentResolver, document, DocumentsContract.Document.MIME_TYPE_DIR, title))
            repeat(2) { page ->
                val uri = checkNotNull(DocumentsContract.createDocument(app.contentResolver, folder, "image/png", "$page.png"))
                val image = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
                checkNotNull(app.contentResolver.openOutputStream(uri)).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            seedRow(container, "chapters", mapOf(
                "chapterId" to "c$number", "mangaId" to "m", "documentId" to DocumentsContract.getDocumentId(folder),
                "kind" to "IMAGE_DIRECTORY", "title" to title, "sortKey" to NaturalOrder.sortKey(title),
                "position" to index, "modifiedAt" to ((index * 3) % 8).toLong(), "contentRevision" to 1,
            ))
        }
    }

    @After fun clean() = runBlocking {
        clearReaders()
        if (::container.isInitialized) closeContainer(container)
        if (::tree.isInitialized) {
            DocumentsContract.deleteDocument(app.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, root))
            if (app.contentResolver.persistedUriPermissions.any { it.uri == tree }) {
                app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }

    private suspend fun open(chapterId: String, startPage: Int = ReaderViewModel.NO_START_PAGE): ReaderViewModel =
        withContext(Dispatchers.Main) {
            ViewModelProvider(store, ReaderViewModel.factory(container, "m", chapterId, startPage))[
                "reader-${readerNumber++}", ReaderViewModel::class.java,
            ].also { readerJobs += it.viewModelScope.coroutineContext.job }
        }.also { reader ->
            withTimeout(10000) { reader.state.first { !it.loading && it.items.isNotEmpty() } }
            assertNull(reader.state.value.error)
        }

    private suspend fun clearReaders() {
        withContext(Dispatchers.Main) { store.clear() }
        // Clearing cancels the scope; its pending Room operations must also finish before closing the DB.
        readerJobs.forEach { it.join() }
        readerJobs.clear()
    }

    private suspend fun loaded(reader: ReaderViewModel, vararg ids: String) {
        withTimeout(10000) {
            reader.state.first { state -> ids.all { id -> state.chapters?.window.orEmpty().any { it.chapterId == id && it.isUsable } } }
        }
    }

    private fun assertPosition(reader: ReaderViewModel, chapterId: String, page: Int) {
        val state = reader.state.value
        val item = state.items[state.currentPageIndex] as ReaderItem.PageItem
        assertEquals(chapterId, state.chapters?.currentChapterId)
        assertEquals(chapterId, item.chapterId)
        assertEquals(page, item.page.ordinal)
        assertEquals(item.key, state.positionKey)
    }

    @Test fun allSortModesAndDirectionsDriveNeighborsWithoutMovingTheRequestedPage() = runBlocking {
        val records = container.mangaRepository.getChapters("m")
        for (mode in ChapterOrdering.Mode.entries) for (descending in listOf(false, true)) {
            val expected = ChapterOrdering.resort(records, ChapterOrdering.Setting(mode, descending)).map { it.chapterId }
            container.mangaRepository.setChapterOrder("m", expected)
            val reader = open(expected[3], 1)
            loaded(reader, expected[2], expected[4])
            assertEquals(expected, reader.state.value.chapterList.map { it.chapterId })
            assertPosition(reader, expected[3], 1)
            val window = reader.state.value.chapters!!.window.map { it.chapterId }
            assertEquals(expected.filter { it in window }, window)
            withContext(Dispatchers.Main) { reader.jumpToAdjacentChapter(true) }
            withTimeout(10000) { reader.state.first { !it.loading && it.chapters?.currentChapterId == expected[4] } }
            assertPosition(reader, expected[4], 0)
            withContext(Dispatchers.Main) { reader.jumpToAdjacentChapter(false) }
            withTimeout(10000) { reader.state.first { !it.loading && it.chapters?.currentChapterId == expected[3] } }
            assertPosition(reader, expected[3], 0)
            clearReaders()
        }
        // The metadata/cover path must keep its original natural order.
        assertEquals(records.map { it.chapterId }, container.mangaRepository.getBackfillTarget("m")!!.chapters.map { it.chapterId })
    }

    @Test fun manualOrderKeepsExtendingPreloadAcrossEveryChapterWithTransitions() = runBlocking {
        readManualOrder(showTransitions = true)
    }

    @Test fun manualOrderKeepsExtendingPreloadAcrossEveryChapterWithoutTransitions() = runBlocking {
        readManualOrder(showTransitions = false)
    }

    private suspend fun readManualOrder(showTransitions: Boolean) {
        val expected = listOf("c20", "c1", "c40", "c3", "c10", "c4", "c30", "c2")
        container.mangaRepository.setChapterOrder("m", expected)
        container.readerPreferences.update { it.copy(showChapterTransitions = showTransitions) }
        // Page counts start unknown, so loading must discover them and extend the frontier.
        assertTrue(container.mangaRepository.getChapters("m").all { it.pageCount == null })
        val reader = open(ReaderViewModel.RESUME_CHAPTER)
        assertPosition(reader, expected.first(), 0)
        assertEquals(expected, reader.state.value.chapterList.map { it.chapterId })
        for ((index, id) in expected.withIndex()) {
            loaded(reader, id)
            assertPosition(reader, id, 0)
            withContext(Dispatchers.Main) { reader.move(1) }
            assertPosition(reader, id, 1)
            val next = expected.getOrNull(index + 1) ?: break
            loaded(reader, next)
            // Page-byte caching is best effort; the required chapter pages are already loaded here.
            withContext(Dispatchers.Main) { reader.move(1) }
            if (showTransitions) {
                val transition = reader.state.value.items[reader.state.value.currentPageIndex] as ReaderItem.Transition
                assertEquals(id, transition.from?.chapterId)
                assertEquals(next, transition.to?.chapterId)
                assertEquals(id, reader.state.value.chapters?.currentChapterId)
                withContext(Dispatchers.Main) { reader.move(1) }
            }
            assertPosition(reader, next, 0)
        }
        val last = expected.last()
        if (showTransitions) {
            withContext(Dispatchers.Main) { reader.move(1) }
            assertTrue((reader.state.value.items[reader.state.value.currentPageIndex] as ReaderItem.Transition).isEnd)
            withContext(Dispatchers.Main) { reader.move(-1) }
        }
        assertPosition(reader, last, 1)
        for (id in expected.asReversed()) {
            loaded(reader, id)
            assertPosition(reader, id, 1)
            withContext(Dispatchers.Main) { reader.move(-1) }
            assertPosition(reader, id, 0)
            if (id == expected.first()) break
            val previous = expected[expected.indexOf(id) - 1]
            loaded(reader, previous)
            withContext(Dispatchers.Main) { reader.move(if (showTransitions) -2 else -1) }
            assertPosition(reader, previous, 1)
        }
    }

    @Test fun resumeKeepsChapterAndPageAfterReordering() = runBlocking {
        val original = container.mangaRepository.getChapters("m").map { it.chapterId }
        open(original[3], 1)
        withTimeout(10000) {
            while (container.readingProgressRepository.get("m")?.pageOrdinal != 1) kotlinx.coroutines.delay(20)
        }
        clearReaders()
        val expected = original.reversed()
        container.mangaRepository.setChapterOrder("m", expected)
        val resumed = open(ReaderViewModel.RESUME_CHAPTER)
        val index = expected.indexOf(original[3])
        loaded(resumed, expected[index - 1], expected[index + 1])
        assertEquals(expected, resumed.state.value.chapterList.map { it.chapterId })
        assertPosition(resumed, original[3], 1)
    }
}
