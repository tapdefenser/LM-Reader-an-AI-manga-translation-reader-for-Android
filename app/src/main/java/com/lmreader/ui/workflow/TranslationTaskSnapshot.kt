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
    val validation = WorkflowValidator.validate(workflow.program)
    require(validation.valid) { validation.issues.joinToString("；") { it.message } }
    if (workflow.program.usesApi()) require(apiProfiles.isNotEmpty()) { "工作流需要 API 配置" }
    workflow.program.allNodes().filter { it.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM) }.forEach { node ->
        val id = (node.inputs["profile"] as? WorkflowExpression.Text)?.value ?: error("API 配置必须明确选择")
        ApiProtocol.validate(apiProfiles.firstOrNull { it.id == id } ?: error("API 配置不存在，请重新选择"))
    }
    val render = settings.effectiveBubbleRender(legacyRender)
    return JSONObject()
        .put("schema", 2)
        .put("program", JSONObject(WorkflowProgramCodec.encode(workflow.program)))
        .put("apiProfiles", JSONObject(ApiProfileCodec.encode(apiProfiles.map { it.copy(apiKey = "") })))
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
