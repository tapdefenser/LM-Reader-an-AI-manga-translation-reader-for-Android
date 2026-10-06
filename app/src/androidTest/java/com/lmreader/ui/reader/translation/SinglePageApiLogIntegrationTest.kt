package com.lmreader.ui.reader.translation

import android.graphics.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.*
import androidx.test.core.app.ApplicationProvider
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.*
import com.lmreader.core.workflow.WorkflowEditing
import com.lmreader.di.AppContainer
import com.lmreader.ui.reader.ReaderItem
import com.lmreader.ui.reader.ViewerChapter
import com.lmreader.ui.workflow.WorkflowHostIntegrationTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import java.io.ByteArrayOutputStream
import java.util.UUID

/** The real reader action, with local HTTP fixtures; no user library or API is touched. */
class SinglePageApiLogIntegrationTest {
    @get:Rule val activity = createAndroidComposeRule<ComponentActivity>()
    @Test fun readerRetranslationLogsActualImagesAndOutputFailuresUnderTheManga() = runBlocking {
        val container=AppContainer.from(ApplicationProvider.getApplicationContext<android.content.Context>())
        container.startupReady.await()
        activity.runOnIdle {container.taskService.setVisible(true);container.taskService.allowRetry()}
        val queue=container.translationQueue
        val paused=queue.paused.value
        queue.pause();queue.awaitCurrentPage();queue.awaitResourceRelease()
        assumeTrue(container.database.translationDao().queueSnapshot().none {it.state in listOf("PENDING","RUNNING")})
        val previous=container.visionExecutionPreferences.settings.value
        container.visionExecutionPreferences.update {it.copy(segGpu=false,ocrBackend=OcrBackend.CPU,segConcurrency=1,ocrConcurrency=1)}
        val token=UUID.randomUUID().toString()
        val bitmap=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.LTGRAY)
            drawOval(80f,100f,720f,510f,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE})
            drawOval(80f,100f,720f,510f,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;style=Paint.Style.STROKE;strokeWidth=6f})
            drawText("GENERATED TEXT",170f,290f,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=46f;typeface=Typeface.DEFAULT_BOLD})
        }
        val bytes=ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        bitmap.recycle()
        try { for(failing in listOf(false,true)) {
            val mangaId="reader-api-$token-$failing";val sourceId="$mangaId-source";val chapterId="$mangaId-chapter"
            val profileId="$mangaId-api";val store=ViewModelStore()
            val server=WorkflowHostIntegrationTest.ImageApiFixture(if(failing) "Please upload an image. Example: {}" else "{\"source\":\"Generated text\",\"translation\":\"MASK\"}")
            var workflowId:String?=null
            val page=ReaderPage("$mangaId-page",0,"single-page-$failing.png","generated.png")
            val source=object:PageSource {
                override suspend fun pages()=listOf(page)
                override suspend fun open(page:ReaderPage)=bytes.inputStream()
                override suspend fun probe(page:ReaderPage)=PageGeometry(800,1000)
            }
            val sql=container.database.openHelper.writableDatabase
            try {
                container.apiProfiles.save(ApiProfile(profileId,ApiProfileKind.LLM,"Single-page loopback fixture","http://127.0.0.1:${server.port}/v1",model="fixture",retryCount=0))
                val created=container.translationWorkflows.create()
                workflowId=created.id
                var program=WorkflowTemplates.visionApi(profileId)
                program=WorkflowEditing.update(program,"bubbles") {it.copy(mode=WorkflowMode.SYNC)}
                program=WorkflowEditing.update(program,"api-vision") {it.copy(inputs=it.inputs-"images")}
                container.translationWorkflows.update(created.copy(program=program,retries=0))
                sql.execSQL("INSERT INTO library_sources (sourceId,kind,treeUri,displayPath,recursive,mode,orderIndex,permission,revision) VALUES (?,'IMAGE_DIRECTORY',?,'Reader fixture',1,'MULTI_CHAPTER',0,'OK',1)",arrayOf(sourceId,"content://fixture/$token"))
                sql.execSQL("INSERT INTO mangas (mangaId,anchorDocumentId,sourceId,sourceKind,layoutMode,displayName,sortKey,sourceOrderIndex,hasMetadata,chapterCountKnown,availability,discoveryGeneration,discoveredAt,updatedAt,translationAutoDetectSource,translationSourceLanguage,translationTargetLanguage,translationWorkflowId) VALUES (?,?,?,'IMAGE_DIRECTORY','MULTI_CHAPTER','Reader API fixture','fixture',0,0,1,'AVAILABLE',1,0,0,0,'en','zh-Hans',?)",arrayOf(mangaId,mangaId,sourceId,workflowId))
                sql.execSQL("INSERT INTO chapters (chapterId,mangaId,documentId,kind,title,sortKey,position,contentRevision,discoveredAt) VALUES (?,?,?,'IMAGE_DIRECTORY','Generated chapter','chapter',0,1,0)",arrayOf(chapterId,mangaId,chapterId))
                val chapter=ViewerChapter(ChapterRecord(chapterId,mangaId,chapterId,ChapterKind.IMAGE_DIRECTORY,"Generated chapter","chapter",pageCount=1,coverDocumentId=null,contentRevision=1,discoveredAt=0),listOf(page),source)
                val vm=withContext(Dispatchers.Main) {ViewModelProvider(store,object:ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=ReaderPageTranslationViewModel(container,mangaId) as T
                })[ReaderPageTranslationViewModel::class.java].also {it.translate(ReaderItem.PageItem(page,chapter),LocalTranslationLanguage.ENGLISH,LocalTranslationLanguage.CHINESE_SIMPLIFIED,BubbleRenderSettings())}}
                val finished=withTimeout(120_000) {vm.state.first {it.completedRegions!=null || it.failure!=null}}
                if(failing) assertTrue(finished.failure.orEmpty(),finished.failure.orEmpty().contains("已附带 1 张图片"))
                else assertNotNull(finished.failure,finished.completedRegions)
                val logs=container.apiLogs.records.value.filter {it.info.context.mangaId==mangaId}
                assertTrue(logs.isNotEmpty());assertEquals(server.requests.size,logs.size)
                logs.forEach {record ->
                    val detail=checkNotNull(container.apiLogs.detail(record.id))
                    assertTrue(detail.info.context.stepName.startsWith("单页重译"))
                    assertEquals(page.displayName,detail.info.context.pageName)
                    assertTrue(detail.info.request.contains("图片附件"))
                    assertEquals(200,detail.outcome.httpCode)
                    assertEquals(if(failing) "FAILED" else "SUCCESS",detail.outcome.status)
                    assertTrue(detail.outcome.response.isNotEmpty())
                    if(failing) assertTrue(detail.outcome.error.contains("输出校验失败"))
                }
            } finally {
                withContext(Dispatchers.Main) {store.clear()}
                queue.awaitResourceRelease();server.close()
                container.apiProfiles.delete(profileId)
                workflowId?.let {container.translationWorkflows.delete(it)}
                container.localPageTranslator.artifacts.delete(page.pageId)
                sql.execSQL("DELETE FROM mangas WHERE mangaId=?",arrayOf(mangaId))
                sql.execSQL("DELETE FROM library_sources WHERE sourceId=?",arrayOf(sourceId))
            }
        } } finally {
            container.localVision.releaseModels();container.visionExecutionPreferences.update {previous}
            if(paused) queue.pause() else queue.resume()
            activity.runOnIdle {container.taskService.setVisible(false)}
        }
    }
}
