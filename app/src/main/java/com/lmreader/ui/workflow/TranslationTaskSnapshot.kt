package com.lmreader.ui.workflow

import com.lmreader.core.model.BubbleRenderSettings
import com.lmreader.core.model.MangaTranslationSettings
import com.lmreader.core.model.TranslationWorkflow
import com.lmreader.core.model.effectiveBubbleRender
import com.lmreader.core.model.effectiveSegThreshold
import com.lmreader.core.model.effectiveTextDetectionThreshold
import com.lmreader.core.model.effectiveFreeTextMergeGapRatio
import org.json.JSONObject
import com.lmreader.core.workflow.WorkflowProgramCodec
import com.lmreader.core.workflow.WorkflowValidator
import com.lmreader.core.model.ApiProfile
import com.lmreader.core.model.WorkflowKind
import com.lmreader.core.api.ApiProfileCodec
import com.lmreader.core.api.ApiProtocol
import com.lmreader.core.model.WorkflowExpression
import com.lmreader.core.model.WorkflowProgram
import com.lmreader.core.model.WorkflowNode

/** Apply manga choices to an execution copy; editing or sharing the workflow keeps its defaults. */
fun WorkflowProgram.withApiOverride(profileId: String?): WorkflowProgram {
    if (profileId == null) return this
    fun override(node: WorkflowNode): WorkflowNode = node.copy(
        inputs = if (node.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM))
            node.inputs + ("profile" to WorkflowExpression.Text(profileId)) else node.inputs,
        children = node.children.map(::override), otherwise = node.otherwise.map(::override))
    return copy(rows = rows.map(::override))
}

/** Fixed at enqueue time so editing a workflow cannot silently change an in-flight chapter. */
fun translationTaskSnapshot(
    workflow: TranslationWorkflow,
    settings: MangaTranslationSettings,
    source: String,
    target: String,
    style: String,
    legacyRender: BubbleRenderSettings,
    apiProfiles: List<ApiProfile> = emptyList(),
): String {
    require(source.isNotBlank() && target.isNotBlank() && !settings.autoDetectSource)
    val program = workflow.program.withApiOverride(settings.apiProfileId)
    val validation = WorkflowValidator.validate(program)
    require(validation.valid) { validation.issues.joinToString("；") { it.message } }
    if (program.usesApi()) require(apiProfiles.isNotEmpty()) { "工作流需要 API 配置" }
    val usedIds = program.allNodes().filter { it.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM) }.map { node ->
        val id = (node.inputs["profile"] as? WorkflowExpression.Text)?.value ?: error("API 配置必须明确选择")
        ApiProtocol.validate(apiProfiles.firstOrNull { it.id == id } ?: error("API 配置不存在，请重新选择"))
        id
    }.toSet()
    val render = settings.effectiveBubbleRender(legacyRender)
    return JSONObject()
        .put("schema", 2)
        .put("program", JSONObject(WorkflowProgramCodec.encode(program)))
        .put("apiProfiles", JSONObject(ApiProfileCodec.encode(apiProfiles.filter { it.id in usedIds }.map { it.copy(apiKey = "") })))
        .put("workflowId", workflow.id)
        .put("workflowRevision", workflow.revision)
        .put("pageMode", "BUBBLE")
        .put("parallelLimit", workflow.parallelLimit)
        .put("retries", workflow.retries)
        .put("sourceLanguage", source)
        .put("targetLanguage", target)
        .put("style", style)
        .put("segThreshold", settings.effectiveSegThreshold().toDouble())
        .put("textDetectionThreshold", settings.effectiveTextDetectionThreshold().toDouble())
        .put("freeTextMergeGapRatio", settings.effectiveFreeTextMergeGapRatio().toDouble())
        .put("segTextScope", settings.segTextScope.name)
        .put("fillMode", render.fillMode.name)
        .put("opacity", render.opacityPercent)
        .put("padding", render.textPaddingPercent)
        .put("font", render.font.name)
        .put("fontScale", render.fontScalePercent)
        .put("bold", render.bold)
        .put("freeTextMaskExpansion", render.freeTextMaskExpansionPercent)
        .toString()
}
