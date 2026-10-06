package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.serialization.json.*

object WorkflowValueCodec {
    fun matches(value: WorkflowValue, type: WorkflowType, depth: Int = 0): Boolean {
        if(depth > 8) return false
        return when(type.kind) {
            WorkflowDataKind.TEXT -> value is WorkflowValue.Text
            WorkflowDataKind.NUMBER -> value is WorkflowValue.Number && value.value.isFinite()
            WorkflowDataKind.BOOLEAN -> value is WorkflowValue.Boolean
            WorkflowDataKind.IMAGE -> value is WorkflowValue.Image
            WorkflowDataKind.CONTEXT -> value is WorkflowValue.Context
            WorkflowDataKind.DICTIONARY -> value is WorkflowValue.Dictionary
            WorkflowDataKind.LIST -> value is WorkflowValue.ListValue && value.items.size <= 10000 && type.element?.let { item -> value.items.all { matches(it, item, depth + 1) } } == true
            WorkflowDataKind.RECORD, WorkflowDataKind.BUBBLE -> value is WorkflowValue.Record && value.fields.keys == type.fields.keys && type.fields.all { (key,t) -> matches(value.fields.getValue(key), t, depth + 1) }
        }
    }
    fun json(value: WorkflowValue): JsonElement = when (value) {
        is WorkflowValue.Text -> JsonPrimitive(value.value)
        is WorkflowValue.Number -> JsonPrimitive(value.value)
        is WorkflowValue.Boolean -> JsonPrimitive(value.value)
        is WorkflowValue.ListValue -> JsonArray(value.items.map(::json))
        is WorkflowValue.Record -> JsonObject(value.fields.mapValues { json(it.value) })
        is WorkflowValue.Dictionary -> JsonObject(value.entries.mapValues { JsonPrimitive(it.value) })
        is WorkflowValue.Image -> error("图片应作为真实附件发送")
        is WorkflowValue.Context -> error("上下文应作为有角色的消息发送")
    }
    fun display(value: WorkflowValue): String = if (value is WorkflowValue.Text) value.value else json(value).toString()
    fun parseResponse(raw: String, type: WorkflowType, expectedBubbleId: String? = null, expectedItems: WorkflowValue.ListValue? = null): WorkflowValue {
        require(raw.toByteArray().size <= 4_000_000) { "结构化结果过大" }
        if (type == WorkflowType.TEXT) return WorkflowValue.Text(raw)
        val trimmed = raw.trim()
        val cleaned = if (trimmed.startsWith("```")) {
            val match = Regex("^```(?:json)?\\s*\\n([\\s\\S]*?)\\n```$", RegexOption.IGNORE_CASE).matchEntire(trimmed)
                ?: error("JSON 代码围栏格式错误")
            match.groupValues[1]
        } else trimmed
        fun decode(v: JsonElement, t: WorkflowType, depth: Int): WorkflowValue {
            require(depth <= 8)
            return when (t.kind) {
                WorkflowDataKind.TEXT -> { require(v is JsonPrimitive && v.isString && v.content.length <= 16384); WorkflowValue.Text(v.content) }
                WorkflowDataKind.NUMBER -> { require(v is JsonPrimitive && !v.isString); val number = v.doubleOrNull ?: error("需要数字"); require(number.isFinite()); WorkflowValue.Number(number) }
                WorkflowDataKind.BOOLEAN -> { require(v is JsonPrimitive && !v.isString); WorkflowValue.Boolean(v.booleanOrNull ?: error("需要布尔值")) }
                WorkflowDataKind.LIST -> {
                    require(v is JsonArray) { "输出类型要求 JSON 列表（[...]），实际返回了非列表值" }
                    require(v.size <= 10000) { "JSON 列表最多 10000 项" }
                    WorkflowValue.ListValue(v.map { decode(it, t.element!!, depth + 1) })
                }
                WorkflowDataKind.RECORD -> {
                    require(v is JsonObject) {
                        if(v is JsonArray) "输出类型要求单个 JSON 对象（{...}），实际返回了 JSON 列表（${v.size} 项）；请让提示词与输出类型一致"
                        else "输出类型要求单个 JSON 对象（{...}），实际返回了非对象值"
                    }
                    require(v.keys == t.fields.keys) {
                        "JSON 字段与输出结构不一致：" + listOfNotNull(
                            (t.fields.keys - v.keys).takeIf { it.isNotEmpty() }?.let { "缺少字段 ${it.joinToString()}" },
                            (v.keys - t.fields.keys).takeIf { it.isNotEmpty() }?.let { "多余字段 ${it.joinToString()}" }).joinToString("；")
                    }
                    WorkflowValue.Record(t.fields.mapValues { decode(v.getValue(it.key), it.value, depth + 1) })
                }
                WorkflowDataKind.DICTIONARY -> { require(v is JsonObject && v.size <= 10000 && v.values.all { it is JsonPrimitive && it.isString }); WorkflowValue.Dictionary(v.mapValues { it.value.jsonPrimitive.content }) }
                else -> error("此类型不能由 API 构造")
            }
        }
        val parsed = try { Json.parseToJsonElement(cleaned) } catch (failure: kotlinx.serialization.SerializationException) {
            throw IllegalArgumentException("API 返回的正文不是有效 JSON，请查看 API 日志中的模型回复", failure)
        }
        val result = decode(parsed, type, 0)
        if (type == WorkflowType.list(WorkflowType.TRANSLATION) && expectedBubbleId != null) {
            val entries = (result as WorkflowValue.ListValue).items
            require(entries.size == 1 && ((entries.single() as WorkflowValue.Record).fields["bubbleId"] as WorkflowValue.Text).value == expectedBubbleId) {
                "API 气泡 ID 必须与请求完全一致；期望 $expectedBubbleId，返回 " + entries.take(3).joinToString { ((it as WorkflowValue.Record).fields["bubbleId"] as WorkflowValue.Text).value.take(100) }
            }
        }
        expectedItems?.let { requireMatchingTranslations(result, it) }
        return result
    }
    fun requireItemCount(result: WorkflowValue, expected: Int) {
        val actual = (result as? WorkflowValue.ListValue)?.items?.size ?: error("项数校验需要列表输出")
        require(actual == expected) { "API 输出项数不匹配：期望 $expected 项，实际 $actual 项" }
    }
    fun requireMatchingTranslations(result: WorkflowValue, expected: WorkflowValue.ListValue) {
        val rows = (result as? WorkflowValue.ListValue)?.items?.map { it as? WorkflowValue.Record ?: error("气泡输出必须是记录列表") } ?: error("气泡输出必须是列表")
        val originals = expected.items.map { it as? WorkflowValue.Record ?: error("期望气泡必须是记录列表") }
        fun id(record: WorkflowValue.Record) = ((record.fields["bubbleId"] ?: record.fields["id"]) as? WorkflowValue.Text)?.value ?: error("缺少气泡 ID")
        val keys = rows.map(::id); val expectedKeys = originals.map(::id)
        require(keys.size == keys.toSet().size && expectedKeys.size == expectedKeys.toSet().size && keys.toSet() == expectedKeys.toSet()) { "API 气泡 ID 必须完整对应，不能缺失、重复或额外返回" }
        val byId = rows.associateBy(::id)
        originals.forEach { original ->
            val translated = byId.getValue(id(original))
            if(!original.fields.containsKey("image") || (original.fields["source"] as? WorkflowValue.Text)?.value?.isNotEmpty() == true)
                require(translated.fields["source"] == original.fields["source"]) { "API 不得修改 OCR 原文" }
            for(key in listOf("pageId", "pageNumber")) if(key in original.fields)
                require(translated.fields[key] == original.fields[key]) { "API 不得修改页身份或页码" }
        }
    }
}
