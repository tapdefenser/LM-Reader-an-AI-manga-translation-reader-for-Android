package com.lmreader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationEngineMigrationTest {
    @Test fun removedOpusPreferenceResolvesToBergamot() {
        assertNull(TranslationModelEngine.fromStoredValue("OPUS_MT"))
        assertEquals(TranslationModelEngine.BERGAMOT, TranslationModelEngine.fromStoredValueOrDefault("OPUS_MT"))
        assertEquals(TranslationModelEngine.BERGAMOT, TranslationModelEngine.fromStoredValueOrDefault(null))
        assertEquals(TranslationModelEngine.BERGAMOT, TranslationModelEngine.fromStoredValueOrDefault("unknown"))
        assertFalse(TranslationModelEngine.isSupported("OPUS_MT"))
        assertTrue(TranslationModelEngine.isSupported(null))
        assertTrue(TranslationModelEngine.isSupported("BERGAMOT"))
    }

    @Test fun unsupportedEngineHasActionableMessage() {
        val failure = assertThrows(UnsupportedTranslationEngineException::class.java) {
            TranslationModelEngine.requireSupported("OPUS_MT")
        }
        assertTrue(failure.message.orEmpty().contains("OPUS_MT"))
        assertTrue(failure.message.orEmpty().contains("Bergamot"))
    }

    @Test fun removedWorkflowIdUsesDefaultLocalResolution() {
        assertTrue(TranslationWorkflow.isDefaultSelection(null))
        assertTrue(TranslationWorkflow.isDefaultSelection(TranslationWorkflow.LOCAL_MACHINE_ID))
        assertTrue(TranslationWorkflow.isDefaultSelection(TranslationWorkflow.RETIRED_OPUS_MACHINE_ID))
        assertFalse(TranslationWorkflow.isDefaultSelection("custom-workflow"))
        assertFalse(TranslationWorkflow.BUILT_INS.any { it.id == TranslationWorkflow.RETIRED_OPUS_MACHINE_ID })
    }
}
