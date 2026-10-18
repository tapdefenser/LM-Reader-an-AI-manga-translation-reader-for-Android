package com.lmreader.core.translation

import com.lmreader.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

class TranslationModelCatalogTest {
    private val json get() = File("src/main/assets/translation/catalog.json").readText()
    private val catalog get() = TranslationModelCatalog(json)
    @Test fun removedDownloadSourcesFallBackToMozilla() {
        assertEquals(TranslationDownloadSource.MOZILLA, TranslationDownloadSource.fromStoredValue("OBSOLETE_SOURCE"))
        assertEquals(TranslationDownloadSource.MOZILLA, TranslationDownloadSource.fromStoredValue(null))
        TranslationDownloadSource.entries.forEach {
            assertEquals(it, TranslationDownloadSource.fromStoredValue(it.name))
        }
    }
    @Test fun previousDirectionalSnapshotsAndDescriptorsRemainUsable() {
        val pack = catalog.pack("ja-en")
        val descriptor = JsonObject(TranslationModelCatalog.packJson(pack) + ("engine" to JsonPrimitive("BERGAMOT")))
        assertEquals(pack, TranslationModelCatalog.parsePack(descriptor))
        val previous = buildJsonObject {
            put("schema", 3); put("engine", "local-nmt-v1")
            putJsonArray("packages") { add(descriptor) }
        }
        assertEquals(listOf(pack), TranslationModelCatalog(previous.toString()).route(pack.source, pack.target))
        val unsupported = JsonObject(descriptor + ("engine" to JsonPrimitive("OBSOLETE_BACKEND")))
        assertThrows(IllegalArgumentException::class.java) { TranslationModelCatalog.parsePack(unsupported) }
        val invalid = JsonObject(previous + ("packages" to JsonArray(listOf(unsupported))))
        assertThrows(IllegalArgumentException::class.java) { TranslationModelCatalog(invalid.toString()) }
    }
    @Test fun allSupportedRoutesHaveCorrectDirectionAndSharedEnglishPivot() {
        val c = catalog
        c.languages.forEach { source -> c.languages.forEach { target ->
            val route = c.route(source,target)
            if(source==target) assertTrue(route.isEmpty()) else {
                assertEquals(source,route.first().source); assertEquals(target,route.last().target)
                assertTrue(route.size in 1..2)
                if(route.size==2) assertEquals(LocalTranslationLanguage.ENGLISH,route.first().target)
            }
        } }
        assertEquals(listOf("ja-en","en-zh-Hans"),c.route(LocalTranslationLanguage.JAPANESE,LocalTranslationLanguage.CHINESE_SIMPLIFIED).map {it.id})
    }
    @Test fun mirrorsUsePinnedRevisionAndOfficialUsesFixedTrainingPath() {
        val c=catalog; val p=c.pack("ja-en"); val f=p.files.first()
        val hf=c.downloadUrl(p,f,TranslationSourceSettings(TranslationDownloadSource.HUGGING_FACE))
        assertTrue(hf.contains("/resolve/${c.mirrorRevision}/base-memory/jaen/"))
        assertEquals(hf.replace("huggingface.co","hf-mirror.com"),c.downloadUrl(p,f,TranslationSourceSettings(TranslationDownloadSource.HF_MIRROR)))
        assertEquals(c.officialBaseUrl+"/"+f.officialPath,c.downloadUrl(p,f,TranslationSourceSettings()))
    }
    @Test fun customDirectoryPreservesBasePath() {
        val c=catalog;val p=c.pack("en-zh-Hans");val f=p.files.first()
        assertEquals("http://127.0.0.1:8080/models/en-zh-Hans/${f.name}.gz",c.downloadUrl(p,f,
            TranslationSourceSettings(TranslationDownloadSource.CUSTOM,"http://127.0.0.1:8080/models/")))
    }
    @Test fun customSourceRejectsCredentialsQueryFragmentAndOtherProtocols() {
        listOf("https://user:pass@example.com","https://example.com/?token=x","https://example.com/#x","ftp://example.com","").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { TranslationModelCatalog.validateCustomBase(url) }
        }
    }
    @Test fun rejectsTraversalMalformedHashAndRole() {
        val original=catalog.pack("ja-en").files.first()
        listOf(json.replace(original.name,"../escape.bin"),json.replace(original.sha256,"invalid"),json.replace("\"role\": \"model\"","\"role\": \"script\""),
            json.replace(original.officialPath,"../escape.gz")).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { TranslationModelCatalog(bad) }
        }
    }
    @Test fun configQuotesAbsolutePathsAndSupportsSplitVocab() {
        val p=catalog.pack("en-zh-Hans")
        val cfg=BergamotTextTranslator.configuration(p,File("models/o'hare"))
        assertTrue(cfg.contains("o''hare"));assertTrue(cfg.contains("srcvocab"));assertTrue(cfg.contains("trgvocab"))
        assertTrue(cfg.contains("gemm-precision: int8shiftAlphaAll"))
        fun number(name: String)=Regex("(?m)^$name: ([0-9.]+)$").find(cfg)!!.groupValues[1].toDouble()
        assertTrue(number("mini-batch-words") >= number("max-length-break")*number("max-length-factor"))
    }
    @Test fun languageIdentityDoesNotDependOnDisplayLocaleOrPresetDictionary() {
        val german=LocalTranslationLanguage.fromTag("DE")
        assertEquals("de",german.tag);assertEquals("Deutsch",german.nativeName)
        assertEquals("German",german.localizedName(Locale.ENGLISH));assertEquals("德语",german.localizedName(Locale.SIMPLIFIED_CHINESE))
        listOf("id","lzh","qaa","sr-Latn").forEach {tag ->assertEquals(tag,LocalTranslationLanguage.fromTag(tag).tag)}
        assertEquals("zh-Hant",LocalTranslationLanguage.fromTag("zh_hant").tag)
        assertThrows(IllegalArgumentException::class.java) {LocalTranslationLanguage.fromTag("德语")}
        assertThrows(IllegalArgumentException::class.java) {LocalTranslationLanguage.fromTag("../de")}
    }
}
