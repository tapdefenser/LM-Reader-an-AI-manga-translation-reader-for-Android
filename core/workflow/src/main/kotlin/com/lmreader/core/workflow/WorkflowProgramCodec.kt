package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.serialization.json.*

object WorkflowProgramCodec {
    fun encode(program: WorkflowProgram): String = json(program).toString()
    fun json(program: WorkflowProgram): JsonObject = buildJsonObject {
        put("version", program.version); put("rows", JsonArray(program.rows.map(::node)))
    }
    fun decode(text: String): WorkflowProgram {
        require(text.toByteArray().size <= 1_000_000) { "工作流超过大小上限" }
        val root = Json.parseToJsonElement(text).jsonObject
        require(root["version"]?.jsonPrimitive?.int == 1) { "无法读取此版本的工作流" }
        var count = 0
        fun readNode(value: JsonElement, depth: Int): WorkflowNode {
            require(depth <= 16 && ++count <= 500) { "工作流最多 500 行、16 层" }
            val j = value.jsonObject
            require(j["parallelLimit"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int?.let { it in 1..WorkflowNode.MAX_PARALLEL } != false) { "最大并行数应为 1～${WorkflowNode.MAX_PARALLEL}" }
            return WorkflowNode(j.string("id"), WorkflowKind.valueOf(j.string("kind")), j.string("label", ""),
                WorkflowMode.valueOf(j.string("mode", "ASYNC")),
                j["variable"]?.takeUnless { it is JsonNull }?.let { v -> v.jsonObject.let {
                    WorkflowVariable(it.string("id"), it.string("name"), readType(it.getValue("type")))
                } },
                j["target"]?.takeUnless { it is JsonNull }?.let(::readRef),
                j["inputs"]?.jsonObject?.mapValues { readExpression(it.value) }.orEmpty(),
                j["resultType"]?.let(::readType) ?: WorkflowType.TEXT,
                j["children"]?.jsonArray?.map { readNode(it, depth + 1) }.orEmpty(),
                j["otherwise"]?.jsonArray?.map { readNode(it, depth + 1) }.orEmpty(),
                j["collectTo"]?.takeUnless { it is JsonNull }?.let(::readRef),
                j["parallelLimit"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int)
        }
        return WorkflowProgram(rows = root.getValue("rows").jsonArray.map { readNode(it, 0) })
    }
    fun type(value: WorkflowType): JsonObject = buildJsonObject {
        put("kind", value.kind.name); value.element?.let { put("element", type(it)) }
        if (value.fields.isNotEmpty()) put("fields", JsonObject(value.fields.mapValues { type(it.value) }))
    }
    fun readType(value: JsonElement, depth: Int = 0): WorkflowType {
        require(depth <= 8) { "数据结构最多嵌套 8 层" }
        val j = value.jsonObject
        val fields = j["fields"]?.jsonObject.orEmpty()
        require(fields.size <= 64 && fields.keys.all { it.isNotBlank() && it.length <= 80 }) { "字段名称或数量无效" }
        return WorkflowType(WorkflowDataKind.valueOf(j.string("kind")), j["element"]?.let { readType(it, depth + 1) },
            fields.mapValues { readType(it.value, depth + 1) }).also {
            require(it.kind != WorkflowDataKind.LIST || it.element != null) { "请选择列表条目的类型" }
        }
    }
    private fun ref(value: WorkflowRef) = buildJsonObject {
        put("variableId", value.variableId); put("path", JsonArray(value.path.map(::JsonPrimitive)))
    }
    private fun readRef(value: JsonElement): WorkflowRef = value.jsonObject.let {
        WorkflowRef(it.string("variableId"), it["path"]?.jsonArray?.map { p -> p.jsonPrimitive.content }.orEmpty())
    }
    private fun variable(value: WorkflowVariable) = buildJsonObject {
        put("id", value.id); put("name", value.name); put("type", type(value.type))
    }
    fun expression(value: WorkflowExpression): JsonObject = buildJsonObject {
        when (value) {
            is WorkflowExpression.Text -> { put("kind", "text"); put("value", value.value) }
            is WorkflowExpression.Number -> { put("kind", "number"); put("value", value.value) }
            is WorkflowExpression.Boolean -> { put("kind", "boolean"); put("value", value.value) }
            is WorkflowExpression.Ref -> { put("kind", "ref"); put("value", ref(value.value)) }
            is WorkflowExpression.Empty -> { put("kind", "empty"); put("type", type(value.type)) }
            is WorkflowExpression.Template -> {
                put("kind", "template"); put("text", value.text); put("bindings", JsonObject(value.bindings.mapValues { ref(it.value) }))
            }
            is WorkflowExpression.Record -> { put("kind", "record"); put("fields", JsonObject(value.fields.mapValues { expression(it.value) })) }
        }
    }
    private fun readExpression(value: JsonElement, depth: Int = 0): WorkflowExpression {
        require(depth <= 8) { "表达式嵌套过深" }
        val j = value.jsonObject
        return when (j.string("kind")) {
            "text" -> WorkflowExpression.Text(j.string("value"))
            "number" -> WorkflowExpression.Number(j.getValue("value").jsonPrimitive.double)
            "boolean" -> WorkflowExpression.Boolean(j.getValue("value").jsonPrimitive.boolean)
            "ref" -> WorkflowExpression.Ref(readRef(j.getValue("value")))
            "empty" -> WorkflowExpression.Empty(readType(j.getValue("type")))
            "template" -> WorkflowExpression.Template(j.string("text"), j["bindings"]?.jsonObject?.mapValues { readRef(it.value) }.orEmpty())
            "record" -> WorkflowExpression.Record(j.getValue("fields").jsonObject.mapValues { readExpression(it.value, depth + 1) })
            else -> error("未知表达式类型")
        }
    }
    private fun node(value: WorkflowNode): JsonObject = buildJsonObject {
        put("id", value.id); put("kind", value.kind.name); put("label", value.label); put("mode", value.mode.name)
        value.variable?.let { put("variable", variable(it)) }; value.target?.let { put("target", ref(it)) }
        put("inputs", JsonObject(value.inputs.mapValues { expression(it.value) })); put("resultType", type(value.resultType))
        put("children", JsonArray(value.children.map(::node))); put("otherwise", JsonArray(value.otherwise.map(::node)))
        value.collectTo?.let { put("collectTo", ref(it)) }
        value.parallelLimit?.let { put("parallelLimit", it) }
    }
    private fun JsonObject.string(key: String, fallback: String? = null): String = this[key]?.jsonPrimitive?.content ?: fallback ?: error("缺少字段：$key")
}
