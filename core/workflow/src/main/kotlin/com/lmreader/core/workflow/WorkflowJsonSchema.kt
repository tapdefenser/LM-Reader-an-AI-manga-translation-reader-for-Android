package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.serialization.json.*

/** The selected output type also constrains sampling on compatible Chat servers. */
object WorkflowJsonSchema {
    fun responseFormat(type: WorkflowType): String? {
        if(type.kind !in setOf(WorkflowDataKind.RECORD, WorkflowDataKind.LIST)) return null
        fun schema(t: WorkflowType): JsonObject = buildJsonObject {
            when(t.kind) {
                WorkflowDataKind.TEXT -> put("type", "string")
                WorkflowDataKind.NUMBER -> put("type", "number")
                WorkflowDataKind.BOOLEAN -> put("type", "boolean")
                WorkflowDataKind.RECORD -> {
                    put("type", "object")
                    put("properties", buildJsonObject { t.fields.forEach { (key, field) -> put(key, schema(field)) } })
                    put("required", buildJsonArray { t.fields.keys.forEach { add(it) } })
                    put("additionalProperties", false)
                }
                WorkflowDataKind.LIST -> { put("type", "array"); put("items", schema(requireNotNull(t.element))) }
                WorkflowDataKind.DICTIONARY -> { put("type", "object"); put("additionalProperties", buildJsonObject { put("type", "string") }) }
                else -> error("API 不能构造图片或气泡几何")
            }
        }
        return buildJsonObject {
            put("type", "json_schema")
            put("json_schema", buildJsonObject {
                put("name", "lmreader_workflow_output"); put("strict", true); put("schema", schema(type))
            })
        }.toString()
    }
}
