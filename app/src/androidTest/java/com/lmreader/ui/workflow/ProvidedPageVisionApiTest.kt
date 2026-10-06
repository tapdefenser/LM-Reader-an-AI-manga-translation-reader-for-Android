package com.lmreader.ui.workflow

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.*
import com.lmreader.core.workflow.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.queue.TranslationCacheBudget
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.util.UUID

/** Live inference is opt-in via explicit arguments; private files never become test assets. */
class ProvidedPageVisionApiTest {
    @Test fun actualVisionApiFiltersEmptyCropsAndTranslatesTheLargeTextRegion() = runBlocking {
        val arguments=InstrumentationRegistry.getArguments()
        val directory=arguments.getString("providedPages")
        val url=arguments.getString("providedApiUrl")
        val model=arguments.getString("providedApiModel")
        assumeNotNull(directory,url,model)
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val container=AppContainer.from(context)
        val token=UUID.randomUUID().toString();val mangaId="provided-live-$token";val profileId="$mangaId-api"
        val previous=container.visionExecutionPreferences.settings.value
        try {
            container.localVision.releaseModels()
            container.visionExecutionPreferences.update {it.copy(segConcurrency=1,ocrConcurrency=1)}
            container.apiProfiles.save(ApiProfile(profileId,ApiProfileKind.LLM,"Authorized private-page vision test",url!!,
                model=model!!,timeoutSeconds=180,retryCount=0,parallelLimit=1,parameters=AiParameters(temperature=.1,maxTokens=3072)))
            for(number in 1..2) {
                val file=File(directory!!,"page-$number.webp")
                val page=ReaderPage("$mangaId-$number",0,"private-$number.webp","private-$number.webp")
                val source=object:PageSource {
                    override suspend fun pages()=listOf(page)
                    override suspend fun open(page:ReaderPage)=file.inputStream()
                    override suspend fun probe(page:ReaderPage)=PageGeometry(1280,957)
                }
                val host=AndroidWorkflowHost(context,container,mangaId,"Private fixture",
                    listOf(WorkflowChapterInput("fixture","Fixture",source,listOf(page))),
                    WorkflowRunSettings(LocalTranslationLanguage.JAPANESE,LocalTranslationLanguage.CHINESE_SIMPLIFIED,"",
                        BubbleRenderSettings(),.35f,segTextScope=SegTextScope.ALL),TranslationCacheBudget(32L*1_048_576),
                    reusePages=false,requestOrigin="单页重译测试")
                // A saved copy can retain the old bubble-only wording and omit attachment selection.
                val program=WorkflowEditing.update(WorkflowTemplates.visionApi(profileId),"api-vision") { it.copy(inputs=(it.inputs-"images")+
                    ("prompt" to WorkflowExpression.Text("附件是当前单个气泡的裁图。将所有文字从ja翻译为zh-Hans。只返回一个 JSON 对象，字段 source、translation；同一气泡的多行／多块文字合并。没有文字时两个字段均为空字符串，不要编造文字。"))) }
                try {
                    withTimeout(240_000) {WorkflowRuntime().execute(program,host)}
                    val saved=checkNotNull(host.published[page.pageId])
                    val characters=saved.regions.sumOf {it.region.sourceText.length}
                    assertTrue("Full detected text must reach the saved API result",characters>=if(number==1) 30 else 200)
                    if(number==1) assertEquals(2,saved.regions.count {it.region.kind==RegionKind.BUBBLE})
                    val records=container.apiLogs.records.value.filter {it.info.context.mangaId==mangaId &&it.info.context.pageName==page.displayName}
                    assertEquals(saved.regions.size,records.size)
                    records.forEach {record ->
                        val detail=checkNotNull(container.apiLogs.detail(record.id))
                        assertEquals("SUCCESS",detail.outcome.status)
                        assertTrue(detail.info.request.contains("图片附件"))
                        assertTrue(detail.info.request.contains("response_format"))
                        assertTrue(detail.info.context.stepName.startsWith("单页重译"))
                    }
                    InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
                        putString("stream","\nlive page=$number regions=${saved.regions.size} nonempty=${saved.regions.count {it.region.sourceText.isNotBlank()}} sourceCharacters=$characters requests=${records.size}\n")
                    })
                } finally {host.close();container.localPageTranslator.artifacts.delete(page.pageId)}
            }
        } finally {
            container.apiProfiles.delete(profileId);container.localVision.releaseModels()
            container.visionExecutionPreferences.update {previous}
        }
    }
}
