package com.lmreader.ui.workflow

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.*
import com.lmreader.core.workflow.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.queue.TranslationCacheBudget
import com.lmreader.core.vision.BubbleMaskRenderer
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Reproduce private pages through the real workflow without calling a translation service. */
class ProvidedPageWorkflowTest {
    @Test fun providedOriginalsSurviveLocalOcrAndVisionApiWorkflow() = runBlocking {
        val directory=InstrumentationRegistry.getArguments().getString("providedPages")
        assumeNotNull(directory)
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val container=AppContainer.from(context)
        val previous=container.visionExecutionPreferences.settings.value
        val token=UUID.randomUUID().toString()
        val server=WorkflowHostIntegrationTest.ImageApiFixture()
        val profileId="provided-api-$token"
        try {
            container.apiProfiles.save(ApiProfile(profileId,ApiProfileKind.LLM,"Private loopback image fixture",
                "http://127.0.0.1:${server.port}/v1",model="fixture",retryCount=0))
            container.localVision.releaseModels()
            container.visionExecutionPreferences.update {it.copy(segConcurrency=1,ocrConcurrency=1,segGpu=false,ocrBackend=OcrBackend.CPU)}
            for(number in 1..2) for(visionOnly in listOf(false,true)) {
                val file=File(directory!!,"page-$number.webp")
                val page=ReaderPage("provided-$token-$number-$visionOnly",0,"private-page.webp","private-page.webp")
                val source=object:PageSource {
                    override suspend fun pages()=listOf(page)
                    override suspend fun open(page:ReaderPage)=file.inputStream()
                    override suspend fun probe(page:ReaderPage)=PageGeometry(1280,957)
                }
                val ocrCharacters=AtomicInteger()
                val apiCalls=AtomicInteger()
                val host=object:AndroidWorkflowHost(context,container,"provided-$token","Private fixture",
                    listOf(WorkflowChapterInput("fixture","Fixture",source,listOf(page))),
                    WorkflowRunSettings(LocalTranslationLanguage.JAPANESE,LocalTranslationLanguage.CHINESE_SIMPLIFIED,
                        "",BubbleRenderSettings(),.35f,segTextScope=SegTextScope.ALL),TranslationCacheBudget(32L*1_048_576),reusePages=false) {
                    override suspend fun request(kind:WorkflowKind,inputs:Map<String,WorkflowValue>,frame:WorkflowFrame):WorkflowValue {
                        if(kind==WorkflowKind.TRANSLATE) return WorkflowValue.Text(if((inputs.getValue("text") as WorkflowValue.Text).value.isBlank()) "" else "MASK")
                        return super.request(kind,inputs,frame).also {
                            if(kind==WorkflowKind.OCR) ocrCharacters.addAndGet((it as WorkflowValue.Text).value.length)
                        }
                    }
                    override suspend fun api(call:WorkflowApiCall,frame:WorkflowFrame):WorkflowValue {
                        assertEquals(1,call.images.size)
                        assertEquals(WorkflowType.GLOSSARY_ENTRY,call.resultType)
                        apiCalls.incrementAndGet()
                        return super.api(call,frame)
                    }
                }
                try {
                    server.requests.clear()
                    WorkflowRuntime(8,0).execute(if(visionOnly) WorkflowTemplates.visionApi(profileId) else WorkflowTemplates.localMachine(),host)
                    val saved=checkNotNull(host.published[page.pageId])
                    if(number==1) assertEquals(2,saved.regions.count {it.region.kind==RegionKind.BUBBLE})
                    else assertTrue(saved.regions.count {it.region.kind==RegionKind.FREE_TEXT}>=4)
                    if(visionOnly) {
                        assertEquals(saved.regions.size,apiCalls.get())
                        assertEquals(saved.regions.size,server.requests.size)
                        val destination=InstrumentationRegistry.getArguments().getString("captureImages")?.let(::File)
                        destination?.mkdirs()
                        server.requests.forEachIndexed { index, request ->
                            val content=request.getJSONArray("messages").let { it.getJSONObject(it.length()-1) }.getJSONArray("content")
                            val url=(0 until content.length()).map { content.getJSONObject(it) }
                                .single { it.optString("type")=="image_url" }.getJSONObject("image_url").getString("url")
                            val png=android.util.Base64.decode(url.substringAfter(','),android.util.Base64.DEFAULT)
                            val crop=android.graphics.BitmapFactory.decodeByteArray(png,0,png.size)
                            assertNotNull("Image bytes must reach the HTTP server",crop)
                            try {
                                assertTrue(crop.width>10 && crop.height>10)
                                val pixels=IntArray(crop.width*crop.height);crop.getPixels(pixels,0,crop.width,0,0,crop.width,crop.height)
                                assertTrue("The transmitted crop must contain original image pixels",pixels.count { it != android.graphics.Color.WHITE }>100)
                                destination?.let { File(it,"page-$number-crop-$index.png").writeBytes(png) }
                                InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
                                    putString("stream","\nwire page=$number crop=$index width=${crop.width} height=${crop.height} bytes=${png.size}\n")
                                })
                            } finally {crop.recycle()}
                        }
                        val logs=container.apiLogs.records.value.filter { it.info.context.mangaId=="provided-$token" && it.info.context.pageName==page.displayName }
                        assertTrue(logs.size>=saved.regions.size)
                        logs.take(saved.regions.size).forEach { log ->
                            val detail=checkNotNull(container.apiLogs.detail(log.id))
                            assertTrue(detail.info.request.contains("图片附件"))
                            assertEquals("SUCCESS",detail.outcome.status)
                        }
                    }
                    else assertTrue("Detected text must reach the workflow's OCR outputs",ocrCharacters.get()>=if(number==1) 30 else 100)
                    val analysis=com.lmreader.ui.reader.translation.decodePageAnalysisImage(context,file)
                    try {
                        val overlay=BubbleMaskRenderer().prepare(analysis,saved.regions,BubbleRenderSettings())
                        assertTrue(saved.regions.any { it.translatedText.isNotBlank() && overlay.hitTest(
                            (it.region.bounds.left+it.region.bounds.right)/2,(it.region.bounds.top+it.region.bounds.bottom)/2)!=null })
                    } finally {analysis.recycle()}
                    InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
                        putString("stream","\npage=$number visionOnly=$visionOnly regions=${saved.regions.size} ocrCharacters=${ocrCharacters.get()} apiCalls=${apiCalls.get()}\n")
                    })
                } finally {host.close();container.localPageTranslator.artifacts.delete(page.pageId)}
            }
        } finally {
            server.close();container.apiProfiles.delete(profileId)
            container.localVision.releaseModels();container.visionExecutionPreferences.update {previous}
        }
    }
}
