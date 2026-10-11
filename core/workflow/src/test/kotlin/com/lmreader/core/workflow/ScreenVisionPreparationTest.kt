package com.lmreader.core.workflow

import com.lmreader.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ScreenVisionPreparationTest {
    @Test fun localWorkflowPreparesOnlyOriginalRegionRecognition() {
        assertEquals(listOf(ScreenOcrPreparation(false, "en")), screenVisionPreparation(WorkflowTemplates.localMachine(), "en", "zh-Hans"))
        assertEquals(emptyList<ScreenOcrPreparation>(), screenVisionPreparation(WorkflowTemplates.visionApi("fixture"), "en", "zh-Hans"))
    }
    @Test fun dataDependentLanguageStaysInWorkflowOrder() {
        val modified = WorkflowEditing.update(WorkflowTemplates.localMachine(), "ocr") { it.copy(inputs = it.inputs +
            ("language" to WorkflowExpression.Ref(WorkflowRef("api-result", listOf("language"))))) }
        assertNull(screenVisionPreparation(modified, "en", "zh-Hans"))
    }
    @Test fun conditionalRecognitionCannotBePerformedSpeculatively() {
        val modified = WorkflowEditing.update(WorkflowTemplates.localMachine(), "ocr") { it.copy(kind = WorkflowKind.IF,
            children = listOf(it.copy(id = "conditional-ocr"))) }
        assertNull(screenVisionPreparation(modified, "en", "zh-Hans"))
    }
}
