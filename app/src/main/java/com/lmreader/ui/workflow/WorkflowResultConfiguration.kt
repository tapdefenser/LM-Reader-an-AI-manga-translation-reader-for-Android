package com.lmreader.ui.workflow

import com.lmreader.core.api.ApiProfileCodec
import com.lmreader.core.model.*
import com.lmreader.core.translation.TranslationModelCatalog
import com.lmreader.core.workflow.*
import kotlinx.serialization.json.*

/** Only semantic settings enter the digest. Render settings, credentials and scheduling limits do not. */
internal fun workflowResultIdentity(program: WorkflowProgram, settings: WorkflowRunSettings,
    packs: List<TranslationModelPack>, visionModels: Map<String, String>, vision: VisionExecutionSettings,
    pipelineVersion: String = "workflow-page-v1"): String {
    fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
    val configuration = sortedMapOf<String, String>()
    // Bump the relevant pipeline identity when execution semantics change without a program/model change.
    configuration["execution.pipeline"] = pipelineVersion
    if (program.uses(WorkflowKind.SEG) || program.uses(WorkflowKind.OCR)) {
        configuration["vision.pipeline"] = "page-analysis-v1"
        configuration["vision.segGpu"] = vision.segGpu.toString()
        configuration["vision.ocrBackend"] = vision.ocrBackend.name
        visionModels.forEach { (name, hash) -> configuration["vision.model.$name"] = hash }
    }
    if (program.uses(WorkflowKind.SEG)) {
        configuration["seg.threshold"] = settings.segThreshold.toString()
        configuration["seg.textDetectionThreshold"] = settings.textDetectionThreshold.toString()
        configuration["seg.textScope"] = settings.segTextScope.name
        configuration["seg.freeTextMergeGapRatio"] = settings.freeTextMergeGapRatio.toString()
    }
    val routes = program.localTranslationRoutes(settings.source, settings.target)
    if (routes.isNotEmpty()) {
        val catalog = TranslationModelCatalog.fromPacks(packs)
        routes.forEach { route -> catalog.route(route.source, route.target, route.engine).forEachIndexed { index, pack ->
            val files = pack.files.sortedBy { it.role }.joinToString(";") { "${it.role}:${it.name}:${it.size}:${it.gitBlobSha1 ?: it.sha256}" }
            configuration["local.${route.engine}.${route.source.tag}.${route.target.tag}.$index"] = "${pack.identity}:$files"
        } }
    }
    val apiNodes = program.allNodes().filter { it.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM) }
    val ids = apiNodes.mapNotNull { (it.inputs["profile"] as? WorkflowExpression.Text)?.value }.toSet() +
        if (apiNodes.any { it.inputs["profile"] !is WorkflowExpression.Text }) settings.apiProfiles.map { it.id }.toSet() else emptySet()
    for (id in ids) {
        val profile = settings.apiProfiles.firstOrNull { it.id == id }
        if (profile == null) { configuration["api.$id"] = "unavailable-profile"; continue }
        val json = Json.parseToJsonElement(ApiProfileCodec.encode(listOf(profile.copy(apiKey = ""))))
            .jsonObject.getValue("profiles").jsonArray.single().jsonObject
        val semantic = json.filterKeys { it !in setOf("id", "name", "apiKey", "timeoutSeconds", "retryCount", "parallelLimit") }.toMutableMap()
        semantic["customParameters"] = Json.parseToJsonElement(profile.customParameters)
        configuration["api.$id"] = canonical(JsonObject(semantic)).toString()
    }
    return WorkflowResultIdentity.compute(program, settings.source.tag, settings.target.tag, settings.style, configuration)
}
