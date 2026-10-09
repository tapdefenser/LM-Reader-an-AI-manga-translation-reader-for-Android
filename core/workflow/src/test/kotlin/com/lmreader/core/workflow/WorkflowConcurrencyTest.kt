package com.lmreader.core.workflow

import com.lmreader.core.model.*
import org.junit.Test
import kotlin.test.assertEquals

class WorkflowConcurrencyTest {
    private val capacity = WorkflowResourceCapacities(2, 3, 1, mapOf("a" to 2, "b" to 1), 4)
    @Test fun pagePipelinesFillStagesButRespectCacheAdmission() {
        val pages = WorkflowTemplates.localMachine().allNodes().first { it.kind == WorkflowKind.PAGES }
        assertEquals(4, capacity.parallelism(pages))
        assertEquals(1, capacity.copy(pageSlots=1).parallelism(pages))
    }
    @Test fun bubbleLoopsUseTheSumOfTheirActualResources() {
        fun api(id: String) = WorkflowNode(id,WorkflowKind.API,inputs=mapOf("profile" to WorkflowExpression.Text(id)))
        val loop = WorkflowNode("each",WorkflowKind.EACH,children=listOf(api("a"),api("b"),api("a")))
        assertEquals(3,capacity.parallelism(loop))
        assertEquals(3,capacity.parallelism(loop.copy(children=listOf(WorkflowNode("ocr",WorkflowKind.OCR)))))
    }
}
