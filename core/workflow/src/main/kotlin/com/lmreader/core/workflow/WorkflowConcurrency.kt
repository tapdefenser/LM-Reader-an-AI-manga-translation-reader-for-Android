package com.lmreader.core.workflow

import com.lmreader.core.model.*

/** Pipeline lanes sum stage capacities; the engines still own their individual permits. */
data class WorkflowResourceCapacities(val seg: Int, val ocr: Int, val localTranslation: Int,
    val api: Map<String, Int>, val pageSlots: Int) {
    fun parallelism(node: WorkflowNode): Int {
        fun descendants(rows: List<WorkflowNode>): List<WorkflowNode> = rows.flatMap { listOf(it) + descendants(it.children) + descendants(it.otherwise) }
        val rows = descendants(listOf(node))
        var lanes = 0
        if (rows.any { it.kind == WorkflowKind.SEG }) lanes += seg.coerceAtLeast(1)
        if (rows.any { it.kind in setOf(WorkflowKind.SEG, WorkflowKind.OCR) }) lanes += ocr.coerceAtLeast(1)
        if (rows.any { it.kind == WorkflowKind.TRANSLATE }) lanes += localTranslation.coerceAtLeast(1)
        rows.filter { it.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM) }
            .map { (it.inputs["profile"] as? WorkflowExpression.Text)?.value }.distinct().forEach { lanes += api[it]?.coerceAtLeast(1) ?: 1 }
        if (lanes == 0) lanes = WorkflowNode.MAX_PARALLEL
        val pageLoop = node.kind in setOf(WorkflowKind.PAGES, WorkflowKind.PREPARE_MANGA) ||
            node.kind == WorkflowKind.EACH && node.variable?.type == WorkflowSystem.pageType
        return minOf(lanes, if (pageLoop) pageSlots.coerceAtLeast(1) else WorkflowNode.MAX_PARALLEL, WorkflowNode.MAX_PARALLEL).coerceAtLeast(1)
    }
}
