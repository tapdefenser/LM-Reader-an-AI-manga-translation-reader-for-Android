package com.lmreader.ui.translation

import com.lmreader.core.model.*
import com.lmreader.ui.workflow.withApiOverride
import org.junit.Assert.*
import org.junit.Test

class TranslationChoicesTest {
    @Test fun multilingualInstallExposesEverySupportedSourceAndTargetWithoutAnEnglishPivot() {
        val catalog=com.lmreader.core.translation.TranslationModelCatalog.fromPacks(listOf(
            com.lmreader.core.translation.NllbModelCatalog.pack("https://huggingface.co")))
        assertEquals(202,catalog.availableSources().size)
        assertEquals(201,catalog.availableTargets(LocalTranslationLanguage.JAPANESE).size)
        assertEquals(LocalTranslationLanguage.JAPANESE,matchEngineLanguage("ja",catalog.availableSources()))
        assertTrue(catalog.availableTargets(LocalTranslationLanguage.fromTag("fr")).contains(LocalTranslationLanguage.fromTag("ar")))
    }
    @Test fun platformLanguageChoicesIgnoreUndefinedTagsAndKeepValidLanguages() {
        val choices = translationLanguageChoices(listOf("und", "", "en", "zh-Hans", "bad/tag", "ja", "en", "fil"))
        assertEquals(listOf("en", "zh-Hans", "ja", "fil", "zh-Hant"), choices.map { it.tag })
    }

    @Test fun downloadedLanguagesAreStablyPinnedWithoutChangingSelection() {
        val all=listOf(LocalTranslationLanguage.KOREAN,LocalTranslationLanguage.JAPANESE,LocalTranslationLanguage.ENGLISH,
            LocalTranslationLanguage.CHINESE_SIMPLIFIED)
        assertEquals(listOf(LocalTranslationLanguage.JAPANESE,LocalTranslationLanguage.ENGLISH,
            LocalTranslationLanguage.CHINESE_SIMPLIFIED,LocalTranslationLanguage.KOREAN),
            prioritizeInstalledLanguages(all,listOf(LocalTranslationLanguage.JAPANESE,LocalTranslationLanguage.ENGLISH,LocalTranslationLanguage.CHINESE_SIMPLIFIED)))
        assertEquals(all,prioritizeInstalledLanguages(all,emptyList()))
    }
    @Test fun mangaApiOverrideCoversNestedStreamingAndOtherwiseStepsWithoutEditingTheWorkflow() {
        val original=WorkflowReferenceTemplates.vision("default-a")
        val requestKinds=setOf(WorkflowKind.API,WorkflowKind.API_STREAM)
        val nodes=original.allNodes().filter { it.kind in requestKinds }
        assertTrue(nodes.size>=2)
        val changed=original.withApiOverride("manga-b")
        assertTrue(changed.allNodes().filter { it.kind in requestKinds }.all { it.inputs["profile"]==WorkflowExpression.Text("manga-b") })
        assertTrue(nodes.all { it.inputs["profile"]==WorkflowExpression.Text("default-a") })
        assertSame(original,original.withApiOverride(null))
    }
}
