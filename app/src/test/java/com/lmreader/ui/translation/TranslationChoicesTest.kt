package com.lmreader.ui.translation

import com.lmreader.core.model.*
import com.lmreader.ui.workflow.withApiOverride
import org.junit.Assert.*
import org.junit.Test

class TranslationChoicesTest {
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
