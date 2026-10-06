package com.lmreader.ui.reader

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.core.model.ReaderOrientation
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ReadingMode
import com.lmreader.di.AppContainer
import com.lmreader.ui.library.LibraryScreen
import com.lmreader.ui.library.LibraryViewModel
import java.util.UUID
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Generated private manga; run with an isolated application ID to protect installed reader data. */
class LibraryAndGlobalReaderIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val container = AppContainer.from(context)
    private val token = UUID.randomUUID().toString()
    private val sourceId = "shelf-source-$token"
    private val mangaIds = listOf("shelf-a-$token", "shelf-b-$token")
    private val chapterIds = mangaIds.map { "$it-chapter" }
    private val names = listOf("Shelf fixture A $token", "Shelf fixture B $token")
    private val categoryName = "Shelf category ${token.take(8)}"
    private val rootId = "source-reader-shelf-$token"
    private lateinit var treeUri: Uri
    private val store = ViewModelStore()
    private var categoryId: Long? = null
    private lateinit var previousSettings: ReaderSettings

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
    }

    private fun captureEvidence(name: String) {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory).mkdirs()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Before fun seed() = runBlocking {
        container.startupReady.await()
        previousSettings = container.readerPreferences.settings.first()
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val authority = context.packageManager.getPackageInfo(testPackage, PackageManager.GET_PROVIDERS)
            .providers.orEmpty().single { it.name == "com.lmreader.reliability.FaultDocumentsProvider" }.authority
        treeUri = DocumentsContract.buildTreeDocumentUri(authority, rootId)
        shell("am start -W -n $testPackage/com.lmreader.reliability.FaultGrantActivity -e authority $authority -e tree $rootId -e target ${context.packageName}")
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val sql = container.database.openHelper.writableDatabase
        sql.execSQL("INSERT INTO library_sources (sourceId, kind, treeUri, displayPath, recursive, mode, orderIndex, permission, revision) VALUES (?, 'IMAGE_DIRECTORY', ?, 'Shelf fixture', 1, 'MULTI_CHAPTER', 0, 'OK', 1)", arrayOf(sourceId, treeUri.toString()))
        mangaIds.forEachIndexed { index, id ->
            val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
            val anchor = checkNotNull(DocumentsContract.createDocument(context.contentResolver, root, DocumentsContract.Document.MIME_TYPE_DIR, "manga-$index"))
            val chapter = checkNotNull(DocumentsContract.createDocument(context.contentResolver, anchor, DocumentsContract.Document.MIME_TYPE_DIR, "chapter"))
            val page = checkNotNull(DocumentsContract.createDocument(context.contentResolver, chapter, "image/png", "01.png"))
            val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
            checkNotNull(context.contentResolver.openOutputStream(page)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            sql.execSQL("INSERT INTO mangas (mangaId, anchorDocumentId, sourceId, sourceKind, layoutMode, displayName, sortKey, sourceOrderIndex, hasMetadata, chapterCountKnown, availability, discoveryGeneration, discoveredAt, updatedAt, translationAutoDetectSource) VALUES (?, ?, ?, 'IMAGE_DIRECTORY', 'MULTI_CHAPTER', ?, ?, 0, 0, 1, 'AVAILABLE', 1, 0, 0, 0)", arrayOf(id, DocumentsContract.getDocumentId(anchor), sourceId, names[index], names[index]))
            sql.execSQL("INSERT INTO chapters (chapterId, mangaId, documentId, kind, title, sortKey, position, contentRevision, discoveredAt) VALUES (?, ?, ?, 'IMAGE_DIRECTORY', 'Chapter', 'chapter', 0, 1, 0)", arrayOf(chapterIds[index], id, DocumentsContract.getDocumentId(chapter)))
        }
        categoryId = container.shelfRepository.createCategory(categoryName).categoryId
    }

    @After fun clean() = runBlocking {
        compose.runOnIdle { store.clear() }
        container.readerPreferences.update { previousSettings }
        val sql = container.database.openHelper.writableDatabase
        mangaIds.forEach { sql.execSQL("DELETE FROM mangas WHERE mangaId = ?", arrayOf(it)) }
        sql.execSQL("DELETE FROM library_sources WHERE sourceId = ?", arrayOf(sourceId))
        categoryId?.let { container.shelfRepository.deleteCategory(it) }
        DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId))
        // 删除文档时系统可能已撤销该树的授权。
        if (context.contentResolver.persistedUriPermissions.any { it.uri == treeUri }) {
            context.contentResolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    @Test fun libraryLongPressAsksCategoryAndCancelDoesNotAddAnything() {
        lateinit var vm: LibraryViewModel
        compose.runOnIdle {
            vm = ViewModelProvider(store, LibraryViewModel.factory(container))["library", LibraryViewModel::class.java]
            vm.onQueryChange(token)
        }
        compose.setContent { MaterialTheme { LibraryScreen(container, {}, {}, {}, viewModel = vm) } }
        compose.waitUntil(10000) { vm.state.value.items.size == 2 && vm.state.value.appliedQuery == token }
        compose.onNodeWithText(names[0]).performTouchInput { longClick() }
        compose.onNodeWithText(names[1]).performClick()
        compose.onNodeWithText("已选 2 项").assertIsDisplayed()
        compose.onNodeWithContentDescription("批量操作").performClick()
        compose.onNodeWithText("移出书架").assertDoesNotExist()
        captureEvidence("library-selection-menu")
        compose.onNodeWithText("加入书架").performClick()
        compose.onNodeWithText("选择书架分类").assertIsDisplayed()
        captureEvidence("library-category-picker")
        mangaIds.forEach { assertNull(runBlocking { container.shelfRepository.categoryIdOf(it) }) }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("已选 2 项").assertIsDisplayed()
        mangaIds.forEach { assertNull(runBlocking { container.shelfRepository.categoryIdOf(it) }) }
        compose.onNodeWithContentDescription("批量操作").performClick()
        compose.onNodeWithText("加入书架").performClick()
        compose.onNodeWithText(categoryName).performClick()
        compose.waitUntil(5000) { mangaIds.all { runBlocking { container.shelfRepository.categoryIdOf(it) == categoryId } } }
        compose.waitUntil(5000) { !vm.state.value.selectionMode }
    }

    @Test fun readerChangesAreGlobalPersistAndIgnoreLegacyMangaOverrides() {
        runBlocking {
            container.readerPreferences.update { it.copy(readingMode = ReadingMode.RIGHT_TO_LEFT, orientation = null) }
            container.mangaRepository.updateReaderOverrides(mangaIds[0], ReadingMode.LEFT_TO_RIGHT, ReaderOrientation.LOCKED_LANDSCAPE)
            container.mangaRepository.updateReaderOverrides(mangaIds[1], ReadingMode.CONTINUOUS_VERTICAL, ReaderOrientation.REVERSE_PORTRAIT)
        }
        fun open(index: Int, key: String): ReaderViewModel =
            ViewModelProvider(store, ReaderViewModel.factory(container, mangaIds[index], chapterIds[index]))[key, ReaderViewModel::class.java]
        lateinit var first: ReaderViewModel
        lateinit var second: ReaderViewModel
        compose.runOnIdle { first = open(0, "first"); second = open(1, "second") }
        compose.waitUntil(10000) { !first.state.value.loading && !second.state.value.loading && first.state.value.items.isNotEmpty() && second.state.value.items.isNotEmpty() }
        assertNull(first.state.value.error)
        assertNull(second.state.value.error)
        assertEquals(ReadingMode.RIGHT_TO_LEFT, first.state.value.readingMode)
        assertEquals(ReadingMode.RIGHT_TO_LEFT, second.state.value.readingMode)
        assertNull(second.state.value.settings.orientation)
        val active = mutableStateOf(first)
        compose.setContent {
            val vm = active.value
            val state by vm.state.collectAsState()
            MaterialTheme { ReaderSettingsDialog(state, {}, vm::setReadingMode, vm::setOrientation, vm::updateGlobalSettings) }
        }
        compose.onNodeWithText("全局设置，应用于所有漫画").assertIsDisplayed()
        compose.onNodeWithText("这部漫画").assertDoesNotExist()
        compose.onNodeWithText("跟随默认").assertDoesNotExist()
        captureEvidence("global-reader-settings")
        compose.onNode(hasText("从左到右翻页") and isSelectable()).performScrollTo().performClick()
        compose.waitUntil(5000) { second.state.value.readingMode == ReadingMode.LEFT_TO_RIGHT }
        assertEquals(ReadingMode.LEFT_TO_RIGHT, runBlocking { container.readerPreferences.settings.first().readingMode })
        compose.onNode(hasText("条漫") and isSelectable()).performScrollTo().performClick()
        compose.onNode(hasText("锁定竖屏") and isSelectable()).performScrollTo().performClick()
        compose.waitUntil(5000) { second.state.value.readingMode == ReadingMode.WEBTOON && second.state.value.settings.orientation == ReaderOrientation.LOCKED_PORTRAIT }
        assertEquals(ReadingMode.WEBTOON, runBlocking { container.readerPreferences.settings.first().readingMode })
        compose.runOnIdle { active.value = second }
        compose.onNode(hasText("条漫") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("锁定竖屏") and isSelectable()).assertIsSelected()
        compose.onNodeWithText("通用").performClick()
        compose.onNode(hasText("白色") and isSelectable()).performClick()
        compose.waitUntil(5000) { first.state.value.settings.theme == com.lmreader.core.model.ReaderTheme.WHITE }
        lateinit var reopened: ReaderViewModel
        compose.runOnIdle { reopened = open(0, "reopened") }
        compose.waitUntil(10000) { !reopened.state.value.loading && reopened.state.value.items.isNotEmpty() }
        assertEquals(second.state.value.settings, reopened.state.value.settings)
        assertNull(reopened.state.value.error)
    }
}
