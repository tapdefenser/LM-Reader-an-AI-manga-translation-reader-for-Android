package com.lmreader.core.workflow

import com.lmreader.core.model.*

object WorkflowLabels {
    fun kind(kind: WorkflowKind) = when (kind) {
        WorkflowKind.MANGA -> "漫画"; WorkflowKind.CHAPTERS -> "每章节"; WorkflowKind.PAGES -> "每页"; WorkflowKind.PREPARE_MANGA -> "预处理整漫画"; WorkflowKind.EACH -> "每项"
        WorkflowKind.IF -> "如果"; WorkflowKind.DECLARE -> "新建变量"; WorkflowKind.SET -> "="; WorkflowKind.SEG -> "SEG"
        WorkflowKind.OCR -> "OCR"; WorkflowKind.TRANSLATE -> "本地机翻"; WorkflowKind.API -> "API 请求"; WorkflowKind.APPLY_TRANSLATIONS -> "按气泡 ID 回填"
        WorkflowKind.API_STREAM -> "API 请求－流式输出"; WorkflowKind.APPLY_ORDER -> "按气泡顺序回填"
        WorkflowKind.APPEND -> "+="; WorkflowKind.MERGE_LIST -> "合并列表"; WorkflowKind.REPLACE -> "字典匹配替换"
        WorkflowKind.MESSAGE -> "新增上下文"; WorkflowKind.MERGE_GLOSSARY -> "新增译名（保留已有）"; WorkflowKind.RETURN -> "旧版返回结果"
    }
    fun type(type: WorkflowType): String = "<" + when (type.kind) {
        WorkflowDataKind.TEXT -> "文本"; WorkflowDataKind.NUMBER -> "数字"; WorkflowDataKind.BOOLEAN -> "布尔"
        WorkflowDataKind.IMAGE -> "图片"; WorkflowDataKind.BUBBLE -> "气泡"; WorkflowDataKind.CONTEXT -> "上下文"
        WorkflowDataKind.LIST -> "列表${type.element?.let(::type) ?: "<?>"}"
        WorkflowDataKind.RECORD -> when(type) { WorkflowType.TRANSLATION -> "气泡对照"; WorkflowType.PAGE_RECORD -> "页翻译记录"; WorkflowType.GLOSSARY_ENTRY -> "译名条目"; else -> "自定义记录" }; WorkflowDataKind.DICTIONARY -> "字典"
    } + ">"
    fun field(key: String) = when (key) {
        "id" -> "ID"; "name" -> "名称"; "index" -> "序号"; "number" -> "页码"; "image" -> "图片"
        "source" -> "原文"; "translation" -> "译文"; "bubbles" -> "译文气泡列表"; "records" -> "翻译记录"
        "bubbleId" -> "气泡ID"; "pageId" -> "页ID"; "pageNumber" -> "页码"; "confidence" -> "置信度"
        "kind" -> "类别"; "key" -> "键"; "value" -> "值"; else -> key.toIntOrNull()?.let { "第${it + 1}项" } ?: key
    }
}
