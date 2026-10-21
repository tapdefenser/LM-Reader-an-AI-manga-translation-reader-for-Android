package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.serialization.json.*
import java.security.MessageDigest

/**
 * Stable identity for one rendered page result.
 *
 * Labels and variable display names are excluded. Every semantic program field, both language tags,
 * the style and the caller-provided allowlisted engine values are included. The canonical form is never
 * exposed: only its SHA-256 digest leaves this object.
 */
object WorkflowResultIdentity {
    private const val DOMAIN = "lmreader.page-result.v1"

    fun compute(
        program: WorkflowProgram,
        source: String,
        target: String,
        style: String,
        engineConfiguration: Map<String, String>,
    ): String {
        val canonicalProgram = withoutDisplayMetadata(program)
        val payload = buildJsonObject {
            put("domain", DOMAIN)
            put("source", source)
            put("target", target)
            put("style", style)
            put("program", WorkflowProgramCodec.json(canonicalProgram))
            put("engineConfiguration", JsonObject(engineConfiguration.mapValues { JsonPrimitive(it.value) }))
        }
        val canonical = canonical(payload).toString().toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it) }
    }

    private fun withoutDisplayMetadata(program: WorkflowProgram): WorkflowProgram =
        program.copy(rows = program.rows.map { withoutDisplayMetadata(it) })

    private fun withoutDisplayMetadata(node: WorkflowNode): WorkflowNode = node.copy(
        label = "",
        variable = node.variable?.copy(name = ""),
        children = node.children.map { withoutDisplayMetadata(it) },
        otherwise = node.otherwise.map { withoutDisplayMetadata(it) },
    )

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(LinkedHashMap<String, JsonElement>().apply {
            value.entries.sortedBy { it.key }.forEach { (key, child) -> put(key, canonical(child)) }
        })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
}
