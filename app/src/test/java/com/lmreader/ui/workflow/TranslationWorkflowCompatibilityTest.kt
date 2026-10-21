package com.lmreader.ui.workflow

import com.lmreader.core.model.TranslationWorkflow
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranslationWorkflowCompatibilityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun removedOpusBuiltInResolvesToDefaultLocalWorkflow() {
        val store = TranslationWorkflowStore(temp.newFolder())
        assertSame(TranslationWorkflow.LOCAL_MACHINE, store.find(TranslationWorkflow.RETIRED_OPUS_MACHINE_ID))
        assertSame(TranslationWorkflow.LOCAL_MACHINE, store.find(null))
        assertSame(TranslationWorkflow.LOCAL_MACHINE, store.find(TranslationWorkflow.LOCAL_MACHINE_ID))
        assertNull(store.find("missing-workflow"))
    }
}
