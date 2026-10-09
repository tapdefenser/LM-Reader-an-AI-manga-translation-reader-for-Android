package com.lmreader.ui.queue

import com.lmreader.core.api.ApiProfileCodec
import com.lmreader.core.api.ApiProtocol
import com.lmreader.core.model.*
import com.lmreader.core.workflow.WorkflowProgramCodec
import org.json.JSONObject

enum class TranslationSchedulingPriority { RESOURCES, ORDER }

/** Only long-lived bottlenecks reserve a manga lane. SEG and OCR are shared at each step. */
data class TranslationResourcePlan(val bottlenecks: Set<String>, val apiResources: Map<String, Set<String>> = emptyMap()) {
    fun conflicts(other: TranslationResourcePlan) = bottlenecks.any { it in other.bottlenecks }
    fun waitingForApi(profiles: Set<String>) = if(profiles.isEmpty()) this else
        TranslationResourcePlan(profiles.flatMap { apiResources[it] ?: setOf("api:$it") }.toSet())
}

fun translationResourcePlan(snapshot: String?): TranslationResourcePlan = runCatching {
    val json = JSONObject(requireNotNull(snapshot))
    if (json.getInt("schema") == 1) return@runCatching TranslationResourcePlan(setOf("local-translation"))
    val program = WorkflowProgramCodec.decode(json.getJSONObject("program").toString())
    val profiles = json.optJSONObject("apiProfiles")?.let { ApiProfileCodec.decode(it.toString()) }.orEmpty().associateBy { it.id }
    val apiResources = mutableMapOf<String, Set<String>>()
    val resources = buildSet {
        if (program.uses(WorkflowKind.TRANSLATE)) add("local-translation")
        program.allNodes().filter { it.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM) }.forEach { node ->
            val id = (node.inputs["profile"] as? WorkflowExpression.Text)?.value
            val keys = buildSet {
                add("api:$id")
                profiles[id]?.let { profile ->
                    val endpoint = ApiProtocol.endpoint(profile)
                    add("server:${endpoint.scheme}://${endpoint.host}:${endpoint.port}")
                }
            }
            if (id != null) apiResources[id] = keys
            addAll(keys)
        }
    }
    TranslationResourcePlan(resources, apiResources)
}.getOrDefault(TranslationResourcePlan(setOf("invalid-workflow")))

/** Stable queue order wins ties; a busy provider never blocks an independent provider. */
fun <T> admitTranslationLanes(candidates: List<Pair<T, TranslationResourcePlan>>, active: List<TranslationResourcePlan>,
    priority: TranslationSchedulingPriority, available: Int): List<T> {
    if (available <= 0 || priority == TranslationSchedulingPriority.ORDER && active.isNotEmpty()) return emptyList()
    val reserved = active.toMutableList()
    val admitted = mutableListOf<T>()
    for ((value, plan) in candidates) {
        if (reserved.none(plan::conflicts)) {
            admitted += value; reserved += plan
            if (admitted.size >= available || priority == TranslationSchedulingPriority.ORDER) break
        }
    }
    return admitted
}
