package com.lmreader.core.translation

import com.lmreader.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NllbModelCatalogTest {
    private val pack = NllbModelCatalog.pack("https://huggingface.co")
    @Test fun oneVerifiedPackageProvidesEverySupportedDirectPair() {
        val catalog = TranslationModelCatalog.fromPacks(listOf(pack))
        assertEquals(202, catalog.languages.size)
        assertEquals(899478308L, pack.installedBytes)
        assertEquals(setOf("encoder", "decoder", "tokenizer"), pack.files.map { it.role }.toSet())
        for (source in catalog.languages) for (target in catalog.languages) {
            assertEquals(if (source == target) emptyList<TranslationModelPack>() else listOf(pack), catalog.route(source, target))
        }
        assertTrue(pack.files.all { it.url!!.contains("/resolve/${NllbModelCatalog.REVISION}/") && it.sha256.length == 64 })
    }
    @Test fun engineAndLanguagesSurviveCacheAndInstalledDescriptorSerialization() {
        val restored = TranslationModelCatalog(TranslationModelCatalog.fromPacks(listOf(pack)).toJson())
        assertEquals(TranslationModelEngine.NLLB, restored.packs.single().engine)
        assertTrue(pack.sameContents(restored.packs.single()))
        assertEquals(listOf(pack), restored.route(LocalTranslationLanguage.JAPANESE, LocalTranslationLanguage.fromTag("fr")))
        assertThrows(IllegalArgumentException::class.java) { restored.route(LocalTranslationLanguage.fromTag("qaa"), LocalTranslationLanguage.ENGLISH) }
        assertThrows(IllegalArgumentException::class.java) { TranslationModelCatalog.fromPacks(listOf(pack.copy(files = pack.files.dropLast(1)))) }
    }
    @Test fun multilingualModelIsPreferredAndCannotMasqueradeAsAnEnglishPivotForUnsupportedLanguages() {
        val legacy = TranslationModelCatalog(File("src/main/assets/translation/catalog.json").readText())
        val ancient = legacy.pack("ja-en").copy(id="lzh-en", source=LocalTranslationLanguage.fromTag("lzh"))
        val mixed = TranslationModelCatalog.fromPacks(legacy.packs + pack + ancient)
        assertEquals(listOf(pack), mixed.route(LocalTranslationLanguage.JAPANESE, LocalTranslationLanguage.CHINESE_SIMPLIFIED))
        assertEquals(listOf(ancient,legacy.pack("en-zh-Hans")),mixed.route(ancient.source,LocalTranslationLanguage.CHINESE_SIMPLIFIED))
    }
    @Test fun officialAndMirrorCatalogsUseTheSamePinnedWeightsOnTheChosenHost() = runBlocking<Unit> {
        val loader = RemoteTranslationCatalog()
        val official = loader.fetch(TranslationSourceSettings(TranslationDownloadSource.NLLB_HUGGING_FACE))
        val mirror = loader.fetch(TranslationSourceSettings(TranslationDownloadSource.NLLB_HF_MIRROR))
        assertTrue(official.packs.single().sameContents(mirror.packs.single()))
        assertTrue(mirror.packs.single().files.all { it.url!!.startsWith("https://hf-mirror.com/") })
        assertTrue(official.packs.single().files.all { it.url!!.startsWith("https://huggingface.co/") })
        assertNotEquals(official.sourceKey, mirror.sourceKey)
        assertThrows(IllegalArgumentException::class.java) { official.downloadUrl(pack,pack.files.first(),TranslationSourceSettings(TranslationDownloadSource.NLLB_HF_MIRROR)) }
    }
}
