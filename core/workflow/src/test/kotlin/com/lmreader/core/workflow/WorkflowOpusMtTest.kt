package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlin.test.*
import org.junit.Test

class WorkflowOpusMtTest {
    @Test fun opusBindingSurvivesSerializationAndLegacyProgramsKeepBergamot() {
        val original = TranslationWorkflow.OPUS_MACHINE.program
        val restored = WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(original))
        assertTrue(WorkflowValidator.validate(restored).valid)
        assertEquals(setOf(TranslationModelEngine.OPUS_MT), restored.translationEngines())
        assertEquals(setOf(TranslationModelEngine.BERGAMOT), WorkflowTemplates.localMachine().translationEngines())
        val from = LocalTranslationLanguage.fromTag("fr"); val to = LocalTranslationLanguage.fromTag("es")
        assertEquals(setOf(LocalTranslationRoute(from, to, TranslationModelEngine.OPUS_MT)), restored.localTranslationRoutes(from, to))
    }
}
