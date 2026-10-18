package com.lmreader.ui.translation

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.core.model.*
import com.lmreader.core.translation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Uses a public, hash-verified model ZIP pushed to the debug app's external fixtures folder. */
class NllbTranslationIntegrationTest {
    @Test fun multilingualDirectionsShareOneDecoderAndReleaseItAfterCancellation() = runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val pack=NllbModelCatalog.pack("https://huggingface.co")
        val installer=ModelPackageInstaller(File(context.cacheDir,"nllb-integration"))
        val fixture=File(context.getExternalFilesDir(null),"fixtures/nllb-200-int8.zip")
        if(!installer.isInstalled(pack)) {
            check(fixture.isFile) {"Push public NLLB fixture to ${fixture.absolutePath}"}
            fixture.inputStream().use {installer.importZip(pack,it)}
        }
        val engine=BergamotTextTranslator(TranslationModelCatalog.fromPacks(listOf(pack)),installer)
        val examples=listOf(
            Triple("en","zh-Hans","The library closes at six tonight."),
            Triple("ja","fr","図書館は今夜六時に閉まります。"),
            Triple("fr","ja","La bibliothèque ferme à six heures ce soir."),
            Triple("ar","de","أين محطة القطار؟"),
            Triple("ko","es","오늘은 날씨가 좋습니다."),
            Triple("zh-Hans","en","请在明天下午三点见面。"),
        )
        var loaded:Set<String>?=null
        try {
            for((from,to,text) in examples) {
                val result=withTimeout(120_000) {engine.translate(LocalTranslationLanguage.fromTag(from),LocalTranslationLanguage.fromTag(to),
                    listOf(LocalTranslationText("sample",text)))}
                val translated=result.items.single().translatedText
                Log.i("NllbIntegration","$from -> $to: $translated (${result.elapsedMillis} ms)")
                assertEquals(listOf(pack.id),result.modelPackIds)
                assertTrue(result.backend.contains("NLLB"));assertTrue(translated.isNotBlank());assertNotEquals(text,translated)
                when(to) {
                    "zh-Hans"->assertTrue(translated.any {it in '\u4e00'..'\u9fff'})
                    "ja"->assertTrue(translated.any {it in '\u3040'..'\u30ff'})
                    "de"->assertTrue(translated.lowercase().contains("bahnhof"))
                    "fr"->assertTrue(translated.lowercase().contains("biblioth"))
                    "es"->assertTrue(translated.lowercase().contains("tiempo"))
                    "en"->assertTrue(translated.lowercase().contains("tomorrow"))
                }
                val current=engine.loadedResources.value.filter {it.backend.contains("NLLB")}.map {it.id}.toSet()
                assertEquals(1,current.size)
                if(loaded==null) loaded=current else assertEquals(loaded,current)
                assertEquals(1,engine.parallelism(LocalTranslationLanguage.fromTag(from),LocalTranslationLanguage.fromTag(to)))
            }
            try {
                engine.translate(LocalTranslationLanguage.ENGLISH,LocalTranslationLanguage.CHINESE_SIMPLIFIED,
                    listOf(LocalTranslationText("one","Hello."),LocalTranslationText("two","Goodbye."))) {done,_ ->
                    if(done==1) throw CancellationException("stop between text items")
                }
                fail("Cancellation must stop the remaining items")
            } catch(_:CancellationException) { }
            assertTrue(engine.loadedResources.value.none {it.inUse})
            val owner="nllb-test"
            engine.pinModels(owner,setOf(LocalTranslationLanguage.JAPANESE to LocalTranslationLanguage.CHINESE_SIMPLIFIED))
            try {engine.retainModels(emptySet());assertEquals(loaded,engine.loadedResources.value.map {it.id}.toSet())}
            finally {engine.unpin(owner)}
        } finally {engine.releaseModels();installer.remove(pack)}
        assertTrue(engine.loadedResources.value.isEmpty())
    }
}
