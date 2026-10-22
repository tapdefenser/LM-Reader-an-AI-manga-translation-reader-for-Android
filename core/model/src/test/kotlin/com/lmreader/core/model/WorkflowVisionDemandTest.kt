package com.lmreader.core.model

import org.junit.Test
import kotlin.test.*

/** Pure demand calculation; no vision/OCR/API implementation is started. */
class WorkflowVisionDemandTest {
    private val en = LocalTranslationLanguage.ENGLISH
    private val ja = LocalTranslationLanguage.JAPANESE

    private fun ocrFrom(program: WorkflowProgram, id: String, language: WorkflowExpression) =
        program.allNodes().first { it.kind == WorkflowKind.OCR }.copy(id = id, inputs = mapOf("language" to language))

    private fun withoutOcr(nodes: List<WorkflowNode>): List<WorkflowNode> = nodes.filter { it.kind != WorkflowKind.OCR }
        .map { it.copy(children = withoutOcr(it.children), otherwise = withoutOcr(it.otherwise)) }

    @Test fun `three scopes map to bubble seg, text detection or both`() {
        assertEquals(VisionModelDemand(bubbleSeg = true), VisionModelDemand.blocks(SegTextScope.BUBBLES))
        assertEquals(VisionModelDemand(textDetection = true), VisionModelDemand.blocks(SegTextScope.FREE_TEXT))
        assertEquals(VisionModelDemand(bubbleSeg = true, textDetection = true), VisionModelDemand.blocks(SegTextScope.ALL))
    }

    @Test fun `local machine SEG alone covers all three scopes`() {
        val base = WorkflowTemplates.localMachine()
        val program = base.copy(rows = withoutOcr(base.rows))
        assertEquals(VisionModelDemand(bubbleSeg = true),
            program.visionModelDemand(SegTextScope.BUBBLES, en, en))
        assertEquals(VisionModelDemand(textDetection = true),
            program.visionModelDemand(SegTextScope.FREE_TEXT, en, en))
        assertEquals(VisionModelDemand(bubbleSeg = true, textDetection = true),
            program.visionModelDemand(SegTextScope.ALL, en, en))
    }

    @Test fun `local machine OCR adds DET and source language to every scope`() {
        val program = WorkflowTemplates.localMachine()
        for (scope in SegTextScope.entries) {
            val demand = program.visionModelDemand(scope, en, en)
            assertTrue(demand.textDetection)
            assertEquals(setOf(LocalOcrLanguage.ENGLISH), demand.recognitionLanguages, "$scope")
        }
        assertEquals(VisionModelDemand(bubbleSeg = true, textDetection = true,
            recognitionLanguages = setOf(LocalOcrLanguage.ENGLISH)), program.visionModelDemand(SegTextScope.BUBBLES, en, en))
    }

    @Test fun `vision API all scope adds both block models but no recognizer`() {
        val demand = WorkflowTemplates.visionApi("fixture").visionModelDemand(SegTextScope.ALL, en, en)
        assertEquals(VisionModelDemand(bubbleSeg = true, textDetection = true), demand)
    }

    @Test fun `API node adds no local OCR demand`() {
        val base = WorkflowTemplates.visionApi("fixture")
        val apiOnly = base.copy(rows = withoutOcr(base.rows))
        assertEquals(VisionModelDemand(bubbleSeg = true, textDetection = true), apiOnly.visionModelDemand(SegTextScope.ALL, en, en))
        assertEquals(VisionModelDemand(bubbleSeg = true), apiOnly.visionModelDemand(SegTextScope.BUBBLES, en, en))
    }

    @Test fun `OCR source binding follows the runtime source language`() {
        val ocr = ocrFrom(WorkflowTemplates.localMachine(), "ocr-source", WorkflowSystem.ref(WorkflowSystem.SOURCE))
        val demand = WorkflowProgram(rows = listOf(ocr)).visionModelDemand(SegTextScope.ALL, ja, en)
        assertEquals(setOf(LocalOcrLanguage.JAPANESE), demand.recognitionLanguages)
        assertTrue(demand.textDetection)
        assertFalse(demand.bubbleSeg, "pure OCR has no SEG")
    }

    @Test fun `OCR target binding follows the runtime target language`() {
        val ocr = ocrFrom(WorkflowTemplates.localMachine(), "ocr-target", WorkflowSystem.ref(WorkflowSystem.TARGET))
        val demand = WorkflowProgram(rows = listOf(ocr)).visionModelDemand(SegTextScope.ALL, en, ja)
        assertEquals(setOf(LocalOcrLanguage.JAPANESE), demand.recognitionLanguages)
    }

    @Test fun `literal OCR languages are bound without source or target`() {
        val base = WorkflowTemplates.localMachine()
        val program = WorkflowProgram(rows = listOf(
            ocrFrom(base, "ocr-ja", WorkflowExpression.Text("ja")),
            ocrFrom(base, "ocr-hant", WorkflowExpression.Text("zh-Hant"))))
        val demand = program.visionModelDemand(SegTextScope.BUBBLES, en, en)
        assertEquals(setOf(LocalOcrLanguage.JAPANESE, LocalOcrLanguage.CHINESE_TRADITIONAL), demand.recognitionLanguages)
        assertTrue(demand.textDetection)
        assertFalse(demand.bubbleSeg)
    }

    @Test fun `no OCR node never loads a recognition model`() {
        val program = WorkflowTemplates.localMachine().copy(rows = withoutOcr(WorkflowTemplates.localMachine().rows))
        val demand = program.visionModelDemand(SegTextScope.ALL, ja, ja)
        assertTrue(demand.textDetection)
        assertTrue(demand.recognitionLanguages.isEmpty())
    }

    @Test fun `pure OCR without SEG still needs DET and REC`() {
        val ocr = ocrFrom(WorkflowTemplates.localMachine(), "ocr-only", WorkflowExpression.Text("ko"))
        val demand = WorkflowProgram(rows = listOf(ocr)).visionModelDemand(SegTextScope.BUBBLES, en, en)
        assertFalse(demand.bubbleSeg)
        assertTrue(demand.textDetection)
        assertEquals(setOf(LocalOcrLanguage.KOREAN), demand.recognitionLanguages)
    }

    @Test fun `TIMED_TEXT never demands vision models`() {
        val timed = WorkflowTemplates.visionApi("fixture").copy(inputScenario = WorkflowInputScenario.TIMED_TEXT)
        assertEquals(VisionModelDemand(), timed.visionModelDemand(SegTextScope.ALL, en, en))
    }

    @Test fun `demand union keeps booleans and languages from both tasks`() {
        val seg = VisionModelDemand.blocks(SegTextScope.BUBBLES)
        val ocr = VisionModelDemand(textDetection = true, recognitionLanguages = setOf(LocalOcrLanguage.JAPANESE))
        assertEquals(VisionModelDemand(bubbleSeg = true, textDetection = true,
            recognitionLanguages = setOf(LocalOcrLanguage.JAPANESE)), seg + ocr)
        val both = ocr + VisionModelDemand(bubbleSeg = true, recognitionLanguages = setOf(LocalOcrLanguage.KOREAN))
        assertTrue(both.bubbleSeg)
        assertTrue(both.textDetection)
        assertEquals(setOf(LocalOcrLanguage.JAPANESE, LocalOcrLanguage.KOREAN), both.recognitionLanguages)
    }
}
