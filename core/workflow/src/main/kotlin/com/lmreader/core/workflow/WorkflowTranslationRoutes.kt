package com.lmreader.core.workflow

import com.lmreader.core.model.*

/** A literal engine binding is serialized with the workflow and queued snapshot. */
fun WorkflowNode.translationEngine(): TranslationModelEngine = when (val value = inputs["engine"]) {
    null -> TranslationModelEngine.BERGAMOT
    is WorkflowExpression.Text -> TranslationModelEngine.requireSupported(value.value)
    else -> throw IllegalArgumentException("机翻引擎必须明确选择")
}

/** Supported engines only, so old OPUS snapshots cannot crash settings or reader composition. */
fun WorkflowProgram.translationEngines(): Set<TranslationModelEngine> =
    allNodes().filter { it.kind == WorkflowKind.TRANSLATE }.mapNotNull { node ->
        runCatching { node.translationEngine() }.getOrNull()
    }.toSet()

fun WorkflowProgram.localTranslationRoutes(source: LocalTranslationLanguage, target: LocalTranslationLanguage): Set<LocalTranslationRoute> {
    fun language(value: WorkflowExpression?) = when (value) {
        is WorkflowExpression.Text -> LocalTranslationLanguage.fromTag(value.value)
        WorkflowSystem.ref(WorkflowSystem.SOURCE) -> source
        WorkflowSystem.ref(WorkflowSystem.TARGET) -> target
        else -> null
    }
    return allNodes().filter { it.kind == WorkflowKind.TRANSLATE }.mapNotNull { node ->
        val from = language(node.inputs["source"]); val to = language(node.inputs["target"])
        if (from != null && to != null) LocalTranslationRoute(from, to, node.translationEngine()) else null
    }.toSet()
}
