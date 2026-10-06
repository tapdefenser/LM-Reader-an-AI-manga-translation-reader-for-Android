package com.lmreader.reliability

import android.net.Uri
import android.provider.DocumentsContract
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.queue.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class EmptyQueueRestartTest {
    @get:Rule val activity=androidx.test.ext.junit.rules.ActivityScenarioRule(androidx.activity.ComponentActivity::class.java)
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    private val root = "source-empty-queue-${UUID.randomUUID()}"
    private lateinit var tree: Uri
    @Before fun setup() = runBlocking {
        app = IsolatedApp(ApplicationProvider.getApplicationContext())
        container = AppContainer(app)
        container.startupReady.await()
        container.taskService.setVisible(true)
        // The Android service belongs to the test application, while these queues own
        // a private container. Deliver its ready callback to that private controller.
        container.backgroundScope.launch(start=CoroutineStart.UNDISPATCHED) {
            container.taskService.leases.collect { if(it>0) container.taskService.serviceReady() }
        }
        container.translationQueue.pauseAndAwait()
        container.exportQueue.pauseAndAwait()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testPackage = instrumentation.context.packageName
        val authority = app.packageManager.getPackageInfo(testPackage, PackageManager.GET_PROVIDERS)
            .providers.orEmpty().single { it.name==FaultDocumentsProvider::class.java.name }.authority
        tree = DocumentsContract.buildTreeDocumentUri(authority, root)
        instrumentation.uiAutomation.executeShellCommand("am start -W -n $testPackage/com.lmreader.reliability.FaultGrantActivity -e authority $authority -e tree $root -e target ${app.packageName}").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        seedRow(container,"library_sources",mapOf("sourceId" to "s", "kind" to "IMAGE_DIRECTORY", "treeUri" to tree.toString(),"mode" to "MULTI_CHAPTER","permission" to "OK"))
        seedRow(container,"mangas",mapOf("mangaId" to "m","sourceId" to "s","anchorDocumentId" to root,"sourceKind" to "IMAGE_DIRECTORY","layoutMode" to "MULTI_CHAPTER","availability" to "AVAILABLE","displayName" to "Queue fixture"))
        for (id in listOf("a","b")) {
            val folder=checkNotNull(DocumentsContract.createDocument(app.contentResolver,DocumentsContract.buildDocumentUriUsingTree(tree,root),DocumentsContract.Document.MIME_TYPE_DIR,id))
            val page=checkNotNull(DocumentsContract.createDocument(app.contentResolver,folder,"image/png","01.png"))
            val image=android.graphics.Bitmap.createBitmap(40,60,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.WHITE)}
            checkNotNull(app.contentResolver.openOutputStream(page)).use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            image.recycle()
            seedRow(container,"chapters",mapOf("chapterId" to id,"mangaId" to "m","documentId" to "$root/$id","kind" to "IMAGE_DIRECTORY","title" to id))
        }
        container.exportSettings.setDestination(LayoutMode.MULTI_CHAPTER,tree)
    }
    @After fun cleanup() = runBlocking {
        closeContainer(container)
        DocumentsContract.deleteDocument(app.contentResolver,DocumentsContract.buildDocumentUriUsingTree(tree,root))
        if(app.contentResolver.persistedUriPermissions.any {it.uri==tree}) app.contentResolver.releasePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    // Invalid workflow intentionally fails immediately: its state proves that admission ran.
    private fun request() = TranslationRequest("zh-Hans",sourceLanguage="en",autoDetectSource=false,configSnapshot="{}",at=System.currentTimeMillis())
    @Test fun translationStartsNewEmptyBatchAfterManualPauseAndCompletion() = runBlocking {
        assertTrue(container.translationQueue.paused.value)
        assertEquals(1,container.translationQueue.enqueue("m",listOf("a"),request()))
        assertFalse(container.translationQueue.paused.value)
        val dao=container.database.translationDao()
        withTimeout(10000) { dao.observeQueue().first { rows -> rows.any { it.chapterId=="a" && it.state=="FAILED" } } }
        dao.cancelQueueItems(listOf("a"),System.currentTimeMillis())
        container.translationQueue.pauseAndAwait()
        assertTrue(dao.queueSnapshot().isEmpty())
        assertEquals(1,container.translationQueue.enqueue("m",listOf("b"),request()))
        withTimeout(10000) { dao.observeQueue().first { rows -> rows.any { it.chapterId=="b" && it.state=="FAILED" } } }
        assertFalse(container.translationQueue.paused.value)
        assertFalse(container.queueOrder.isPaused())
    }
    @Test fun addingToExistingTranslationBatchKeepsItsManualPause() = runBlocking {
        container.translationQueue.pauseForPriority()
        try {
            assertEquals(1,container.translationQueue.enqueue("m",listOf("a"),request()))
            container.translationQueue.pause()
            assertEquals(1,container.translationQueue.enqueue("m",listOf("b"),request()))
            assertTrue(container.translationQueue.paused.value)
            assertEquals(0,container.translationQueue.enqueue("m",listOf("b"),request()))
            assertTrue(container.translationQueue.paused.value)
        } finally { container.translationQueue.resumeAfterPriority() }
    }
    @Test fun exportStartsNewEmptyBatchAfterManualPause() = runBlocking {
        assertTrue(container.exportQueue.paused.value)
        assertTrue(container.exportQueue.tasks.value.isEmpty())
        assertEquals(1,container.exportQueue.enqueue("m",listOf("a")))
        assertFalse(container.exportQueue.paused.value)
        withTimeout(15000) { container.exportQueue.tasks.first { rows -> rows.isEmpty() || rows.any { it.state in listOf("FAILED","DONE") } } }
        assertFalse(app.getSharedPreferences("export-settings",0).getBoolean("queue-paused",true))
    }
    @Test fun addingToExistingExportBatchKeepsItsManualPause() = runBlocking {
        // A failed older batch still occupies the queue until the user clears it.
        val json=org.json.JSONObject().put("id",UUID.randomUUID().toString()).put("mangaId","m").put("chapterId","a")
            .put("mangaTitle","Fixture").put("chapterTitle","a").put("sourceTreeUri",tree.toString())
            .put("destinationTreeUri",tree.toString()).put("singleChapter",false).put("state","FAILED").put("completedPages",0).put("totalPages",0)
        java.io.File(app.filesDir,"export-queue.json").writeText(org.json.JSONArray().put(json).toString())
        val queue=ExportQueueCoordinator(container)
        queue.pauseAndAwait()
        assertEquals(1,queue.enqueue("m",listOf("b")))
        assertTrue(queue.paused.value)
        assertEquals("PAUSED",queue.tasks.value.single { it.chapterId=="b" }.state)
    }
}
