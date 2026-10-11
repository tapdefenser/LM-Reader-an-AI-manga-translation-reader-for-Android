package com.lmreader.core.workflow

import com.lmreader.core.model.*

data class ScreenOcrPreparation(val wholePage: Boolean, val language: String)

/** Only pure recognition of immutable input images can move ahead of workflow execution.
 * Data-dependent languages/images and conditional OCR stay in their original order. */
fun screenVisionPreparation(program: WorkflowProgram, source: String, target: String): List<ScreenOcrPreparation>? {
    val nodes = program.allNodes()
    if (nodes.count { it.kind == WorkflowKind.SEG } != 1) return null
    if (nodes.any { it.kind == WorkflowKind.RETURN }) return null
    if (nodes.any { it.kind == WorkflowKind.IF && WorkflowProgram(rows = it.children + it.otherwise).uses(WorkflowKind.OCR) }) return null
    val bubbles = nodes.filter { it.kind == WorkflowKind.EACH &&
        it.inputs["items"] == WorkflowSystem.ref(WorkflowSystem.PAGE, "bubbles") }.mapNotNull { it.variable?.id }.toSet()
    return nodes.filter { it.kind == WorkflowKind.OCR }.map { node ->
        val image = (node.inputs["image"] as? WorkflowExpression.Ref)?.value ?: return null
        if (image.path != listOf("image") || image.variableId != WorkflowSystem.PAGE && image.variableId !in bubbles) return null
        val language = when (val expression = node.inputs["language"]) {
            is WorkflowExpression.Text -> expression.value
            WorkflowSystem.ref(WorkflowSystem.SOURCE) -> source
            WorkflowSystem.ref(WorkflowSystem.TARGET) -> target
            else -> return null
        }
        ScreenOcrPreparation(image.variableId == WorkflowSystem.PAGE, language)
    }.distinct()
}
