package com.lmreader.ui.library

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.core.model.*
import com.lmreader.core.storage.scan.ScanReason
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Real SAF trees + scanner + Room + displayed library, including rescans of duplicate titles. */
class SameTitleLibraryIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = IsolatedApp(base)
    private val container = AppContainer(app)
    private val store = ViewModelStore()
    private val grants = mutableListOf<Uri>()
    private lateinit var root: Uri
    private lateinit var authority: String
    private lateinit var vm: LibraryViewModel

    @Before fun setUp() = runBlocking {
        container.startupReady.await()
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        authority = base.packageManager.getPackageInfo(testPackage, PackageManager.GET_PROVIDERS)
            .providers.orEmpty().single { it.name == "com.lmreader.reliability.FaultDocumentsProvider" }.authority
        root = grant("same-titles-${UUID.randomUUID()}")
        compose.runOnIdle { vm = ViewModelProvider(store, LibraryViewModel.factory(container))[LibraryViewModel::class.java] }
        compose.setContent { MaterialTheme { LibraryScreen(container, {}, {}, {}, viewModel = vm) } }
        compose.waitUntil(10000) { vm.state.value.exhausted && !vm.state.value.loading }
    }

    private fun grant(id: String): Uri {
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "am start -W -n $testPackage/com.lmreader.reliability.FaultGrantActivity -e authority $authority -e tree $id -e target ${base.packageName}"
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        return DocumentsContract.buildTreeDocumentUri(authority, id).also {
            base.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            grants += it
        }
    }

    private fun directory(parent: Uri, name: String) = checkNotNull(DocumentsContract.createDocument(
        base.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name))

    private fun addManga(parent: Uri, mode: LayoutMode) {
        val anchor = directory(parent, "abc")
        val chapter = if(mode == LayoutMode.MULTI_CHAPTER) directory(anchor, "chapter") else anchor
        val page = checkNotNull(DocumentsContract.createDocument(base.contentResolver, chapter, "image/png", "01.png"))
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        try { checkNotNull(base.contentResolver.openOutputStream(page)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    private suspend fun saveSource(uri: Uri, mode: LayoutMode, order: Int) = container.sourceRepository.saveSource(
        LibrarySource("same-$order-${UUID.randomUUID()}", SourceKind.IMAGE_DIRECTORY, uri.toString(), "Download",
            null, null, true, mode, order, SourcePermissionState.OK, 0, null, null, null))

    private fun verify(mode: LayoutMode, separate: Boolean) = runBlocking {
        val document = DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
        val first = directory(document, "mangalot")
        val second = directory(document, "kanmanha")
        addManga(first, mode)
        addManga(second, mode)
        if(separate) {
            saveSource(grant(DocumentsContract.getDocumentId(first)), mode, 0)
            saveSource(grant(DocumentsContract.getDocumentId(second)), mode, 1)
        } else saveSource(root, mode, 0)
        container.scanCoordinator.rescanAll(ScanReason.REFRESH).join()
        compose.waitUntil(15000) { !vm.state.value.scan.running && vm.state.value.items.size == 2 }
        assertNull(vm.state.value.scan.lastFailure)
        val cards = container.mangaRepository.pageLibrary(0, 30).items
        assertEquals(2, cards.size)
        assertEquals(listOf("abc", "abc"), cards.map { it.displayName })
        val ids = cards.map { it.mangaId }.toSet()
        assertEquals(2, ids.size)
        val anchors = container.database.openHelper.readableDatabase.query("SELECT anchorDocumentId FROM mangas").use { cursor ->
            buildSet { while(cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertEquals(2, anchors.size)
        compose.onAllNodesWithText("abc").assertCountEquals(2)
        cards.forEachIndexed { index, card ->
            container.shelfRepository.ensureOnShelf(card.mangaId)
            container.translationRepository.upsertGlossary(GlossaryEntry(card.mangaId, "Name", "Translation-$index", true, 123))
        }
        container.scanCoordinator.rescanAll(ScanReason.REFRESH).join()
        compose.waitUntil(15000) { !vm.state.value.scan.running && vm.state.value.items.size == 2 }
        assertEquals(ids, container.mangaRepository.pageLibrary(0, 30).items.map { it.mangaId }.toSet())
        cards.forEachIndexed { index, card ->
            assertNotNull(container.shelfRepository.categoryIdOf(card.mangaId))
            assertEquals("Translation-$index", container.translationRepository.glossary(card.mangaId).single().target)
        }
        compose.runOnIdle { vm.onQueryChange("abc") }
        compose.waitUntil(10000) { vm.state.value.appliedQuery == "abc" && vm.state.value.items.size == 2 }
        compose.onAllNodesWithText("abc").filter(hasClickAction()).assertCountEquals(2)
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        if(directory != null) {
            val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(directory).mkdirs()
            File(directory, "same-title-${mode.name}-$separate.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
    }

    @Test fun oneSourceSingleChapter() = verify(LayoutMode.SINGLE_CHAPTER, false)
    @Test fun oneSourceMultiChapter() = verify(LayoutMode.MULTI_CHAPTER, false)
    @Test fun separateSourcesSingleChapter() = verify(LayoutMode.SINGLE_CHAPTER, true)
    @Test fun separateSourcesMultiChapter() = verify(LayoutMode.MULTI_CHAPTER, true)

    @After fun clean() = runBlocking {
        compose.runOnIdle { store.clear() }
        container.scanCoordinator.cancelAll().join()
        closeContainer(container)
        if(::root.isInitialized) runCatching {
            DocumentsContract.deleteDocument(base.contentResolver, DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root)))
        }
        grants.forEach { uri -> runCatching { base.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        app.root.deleteRecursively()
        Unit
    }
}
