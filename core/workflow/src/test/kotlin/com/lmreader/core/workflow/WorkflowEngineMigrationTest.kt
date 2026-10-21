package com.lmreader.core.workflow

import com.lmreader.core.model.LocalTranslationLanguage
import com.lmreader.core.model.TranslationModelEngine
import com.lmreader.core.model.UnsupportedTranslationEngineException
import com.lmreader.core.model.WorkflowExpression
import com.lmreader.core.model.WorkflowProgram
import com.lmreader.core.model.WorkflowTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowEngineMigrationTest {
    private fun legacyOpusProgram(): WorkflowProgram = WorkflowEditing.update(WorkflowTemplates.localMachine(), "translate") { node ->
        node.copy(inputs = node.inputs + ("engine" to WorkflowExpression.Text("OPUS_MT")))
    }

    @Test fun removedOpusBindingIsReportedAndNeverTreatedAsBergamot() {
        val program = legacyOpusProgram()
        val validation = WorkflowValidator.validate(program)
        val issue = validation.issues.single { it.nodeId == "translate" }
        assertFalse(validation.valid)
        assertTrue(issue.message.contains("OPUS_MT"))
        assertTrue(issue.message.contains("Bergamot"))
        assertEquals(emptySet<TranslationModelEngine>(), program.translationEngines())
        assertThrows(UnsupportedTranslationEngineException::class.java) {
            program.localTranslationRoutes(LocalTranslationLanguage.JAPANESE, LocalTranslationLanguage.CHINESE_SIMPLIFIED)
        }
    }

    @Test fun removedOpusQueueSnapshotDecodesAndStillFailsValidation() {
        val restored = WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(legacyOpusProgram()))
        assertTrue(WorkflowValidator.validate(restored).issues.any { it.nodeId == "translate" && it.message.contains("OPUS_MT") })
        assertEquals(emptySet<TranslationModelEngine>(), restored.translationEngines())
    }

    @Test fun defaultLocalWorkflowUsesBergamot() {
        val program = WorkflowTemplates.localMachine()
        assertEquals(setOf(TranslationModelEngine.BERGAMOT), program.translationEngines())
        assertTrue(WorkflowValidator.validate(program).valid)
    }
}
