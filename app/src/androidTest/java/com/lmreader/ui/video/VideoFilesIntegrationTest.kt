package com.lmreader.ui.video

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import com.lmreader.reliability.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VideoFilesIntegrationTest {
    private lateinit var app: IsolatedApp; private lateinit var container: AppContainer
    private lateinit var treeId: String; private lateinit var tree: Uri
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testPackage get() = instrumentation.context.packageName
    private val base get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    @Before fun setup() = runBlocking {
        app = IsolatedApp(base); container = AppContainer(app); container.startupReady.await()
        treeId = "video-files-${UUID.randomUUID()}"
        tree = DocumentsContract.buildTreeDocumentUri(FaultDocumentsProvider.AUTHORITY, treeId)
        grant(tree)
    }
    private fun grant(uri: Uri) {
        instrumentation.uiAutomation.executeShellCommand("am start -W -n $testPackage/com.lmreader.reliability.FaultGrantActivity -e uri $uri -e target ${base.packageName}").use {
            FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
    }
    @After fun cleanup() = runBlocking {
        runCatching { DocumentsContract.deleteDocument(app.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, treeId)) }
        closeContainer(container); app.root.deleteRecursively(); Unit
    }
    @Test fun persistentSafVideoRelocationAndFullBackupRestoreKeepIdentityEditsAndOriginalBytes() = runBlocking {
        val movie = File(app.cacheDir, "original.mp4"); VideoFixture.create(movie)
        val root = DocumentFile.fromTreeUri(app, tree)!!
        val uri = root.createFile("video/mp4", "original.mp4")!!.uri
        app.contentResolver.openOutputStream(uri, "wt")!!.use { output -> movie.inputStream().use { it.copyTo(output) } }
        grant(uri)
        val p = container.videoLibrary.add(VideoMediaSource.inspect(app, uri))
        assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
        val moved = DocumentsContract.renameDocument(app.contentResolver, uri, "moved.mp4")!!
        grant(moved)
        val relocated = container.videoLibrary.add(VideoMediaSource.inspect(app, moved))
        assertEquals(p.id, relocated.id)
        val cue = VideoSubtitleCue("cue", 100, 1500, "Hello", "人工", true)
        container.videoLibrary.change(p.id) { it.copy(positionMs = 2500, cues = listOf(cue), offsetMs = 200) }
        val backup = root.createFile("application/zip", "backup.zip")!!.uri
        container.backups.create(backup)
        container.videoLibrary.change(p.id) { it.copy(positionMs = 0, cues = emptyList()) }
        container.backups.restore(backup)
        val restored = container.videoLibrary.current(p.id)
        assertEquals(2500L, restored.positionMs); assertEquals(listOf(cue), restored.cues); assertEquals(200L, restored.offsetMs)
        assertEquals(p.sha256, VideoMediaSource.inspect(app, moved, false).sha256)
        assertArrayEquals(movie.readBytes(), app.contentResolver.openInputStream(moved)!!.use { it.readBytes() })
        for (u in listOf(uri, moved)) runCatching { app.contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    @Test fun savedSrtReopensAndSeparateUidCanReadSharedFileWithGrantedPermission() = runBlocking {
        val cue = VideoSubtitleCue("cue", 1000, 2000, "Hello", "你好", true)
        val contents = VideoSubtitles.encode(listOf(cue), VideoSubtitleDisplay.BILINGUAL, 250)
        val output = DocumentFile.fromTreeUri(app, tree)!!.createFile("application/x-subrip", "translated.srt")!!.uri
        VideoSubtitleFiles.save(app, output, contents)
        val roundtrip = VideoSubtitles.parse(app.contentResolver.openInputStream(output)!!.use { it.readBytes() }).single()
        assertEquals(1250L, roundtrip.startMs); assertEquals("Hello\n你好", roundtrip.source)
        val send = VideoSubtitleFiles.shareIntent(base, contents)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val resultId = "$treeId/result.json"
        send.setClassName(testPackage, SubtitleReceiverActivity::class.java.name).putExtra("result", resultId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        withContext(Dispatchers.Main) { base.startActivity(send) }
        val resultUri = DocumentsContract.buildDocumentUriUsingTree(tree, resultId)
        val json = withTimeout(10000) {
            while (true) {
                val text = runCatching { app.contentResolver.openInputStream(resultUri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                if (!text.isNullOrBlank()) return@withTimeout JSONObject(text)
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") JSONObject()
        }
        assertTrue(json.toString(), json.getBoolean("ok")); assertEquals(contents, json.getString("contents"))
        @Suppress("DEPRECATION") val shared = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        base.contentResolver.delete(shared, null, null)
        Unit
    }
}
