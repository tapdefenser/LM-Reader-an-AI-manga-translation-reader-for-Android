package com.lmreader.ui.translation

import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.core.model.*
import com.lmreader.core.translation.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real pinned models supplied externally by the acceptance runner, never bundled in the APK. */
class OpusMtIntegrationTest {
    @Test fun directPivotCancellationAndPinnedResources() = runBlocking {
        withTimeout(180_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(context.getExternalFilesDir(null), "opus-acceptance")
            check(File(root, "catalog.json").isFile) { "Supply the verified OPUS acceptance fixtures first" }
            val catalog = TranslationModelCatalog(File(root, "catalog.json").readText())
            val installer = ModelPackageInstaller(root)
            val translator = BergamotTextTranslator({ catalog }, installer,
                { MachineTranslationResources(concurrency = 1) })
            val expected = Json.parseToJsonElement(File(root, "reference.json").readText()).jsonArray
            fun language(tag: String) = LocalTranslationLanguage.fromTag(tag)
            val engine = TranslationModelEngine.OPUS_MT
            val direct = LocalTranslationRoute(language("fr"), language("de"), engine)
            val pivot = LocalTranslationRoute(language("ja"), language("zh-Hans"), engine)
            suspend fun translate(from: String, to: String, text: String) = translator.translate(engine,
                language(from), language(to), listOf(LocalTranslationText("acceptance", text)))
            val evidence = mutableListOf<JsonObject>()
            try {
                translator.releaseModels()
                catalog.packs.forEach { installer.verifyInstalled(it) }
                val french = expected.first().jsonObject
                val first = translate("fr", "de", french.getValue("source").jsonPrimitive.content)
                assertEquals(listOf("opus-fr-de"), first.modelPackIds)
                assertEquals(french.getValue("translation").jsonPrimitive.content, first.items.single().translatedText)
                assertEquals("OPUS-MT · ONNX CPU", first.backend)
                evidence += buildJsonObject { put("direction", "fr-de"); put("text", first.items.single().translatedText); put("ms", first.elapsedMillis) }

                translator.prepareEngineModels("opus-acceptance", setOf(pivot))
                translator.retainEngineModels(setOf(direct))
                assertTrue(translator.loadedResources.value.any { "opus-ja-en" in it.id })
                assertTrue(translator.loadedResources.value.any { "opus-en-zh-Hans" in it.id })
                val japanese = expected[1].jsonObject.getValue("source").jsonPrimitive.content
                val second = translate("ja", "zh-Hans", japanese)
                assertEquals(listOf("opus-ja-en", "opus-en-zh-Hans"), second.modelPackIds)
                assertEquals(expected[2].jsonObject.getValue("translation").jsonPrimitive.content,
                    second.items.single().translatedText)
                evidence += buildJsonObject { put("direction", "ja-en-zh-Hans"); put("text", second.items.single().translatedText); put("ms", second.elapsedMillis) }
                translator.unpin("opus-acceptance")
                translator.releaseModels()
                assertTrue(translator.loadedResources.value.isEmpty())

                // Cancel an occupied decoder, then require a fresh request to work.
                val job = launch(Dispatchers.Default) { translate("fr", "de", "Bonjour, comment allez-vous ? ".repeat(100)) }
                withTimeout(30_000) { while (translator.loadedResources.value.none { it.inUse }) delay(10) }
                delay(100)
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                val recovered = translate("fr", "de", french.getValue("source").jsonPrimitive.content)
                assertEquals(first.items.single().translatedText, recovered.items.single().translatedText)
                assertEquals(0, translator.activeTasks.value)
                translator.releaseModels()
                assertTrue(translator.loadedResources.value.isEmpty())
                File(root, "acceptance.json").writeText(buildJsonObject {
                    put("status", "PASS"); put("inference", JsonArray(evidence))
                    put("cancellationRecovery", true); put("pinnedResources", true)
                }.toString())
            } finally {
                translator.unpin("opus-acceptance")
                translator.releaseModels()
            }
        }
    }
}
