package com.lmreader.core.model

/** Stable identities, lexical scopes and typed data: persisted programs contain no executable source. */
enum class WorkflowKind { MANGA, CHAPTERS, PAGES, PREPARE_MANGA, EACH, IF, DECLARE, SET, SEG, OCR, TRANSLATE, API, API_STREAM, APPLY_TRANSLATIONS, APPLY_ORDER, APPEND, MERGE_LIST, REPLACE, MESSAGE, MERGE_GLOSSARY, RETURN }
enum class WorkflowMode { SYNC, ASYNC }
enum class WorkflowDataKind { TEXT, NUMBER, BOOLEAN, IMAGE, BUBBLE, CONTEXT, LIST, RECORD, DICTIONARY }

data class WorkflowType(val kind: WorkflowDataKind, val element: WorkflowType? = null,
    val fields: Map<String, WorkflowType> = emptyMap()) {
    companion object {
        val TEXT = WorkflowType(WorkflowDataKind.TEXT)
        val NUMBER = WorkflowType(WorkflowDataKind.NUMBER)
        val BOOLEAN = WorkflowType(WorkflowDataKind.BOOLEAN)
        val IMAGE = WorkflowType(WorkflowDataKind.IMAGE)
        val CONTEXT = WorkflowType(WorkflowDataKind.CONTEXT)
        val DICTIONARY = WorkflowType(WorkflowDataKind.DICTIONARY)
        val BUBBLE = WorkflowType(WorkflowDataKind.BUBBLE, fields = linkedMapOf(
            "id" to TEXT, "index" to NUMBER, "image" to IMAGE, "source" to TEXT, "translation" to TEXT,
            "confidence" to NUMBER, "kind" to TEXT))
        val TRANSLATION = WorkflowType(WorkflowDataKind.RECORD, fields = linkedMapOf(
            "bubbleId" to TEXT, "source" to TEXT, "translation" to TEXT))
        val GLOSSARY_ENTRY = WorkflowType(WorkflowDataKind.RECORD, fields = linkedMapOf("source" to TEXT, "translation" to TEXT))
        val PAGE_RECORD = WorkflowType(WorkflowDataKind.RECORD, fields = TRANSLATION.fields + mapOf("pageId" to TEXT, "pageNumber" to NUMBER))
        fun list(element: WorkflowType) = WorkflowType(WorkflowDataKind.LIST, element)
    }
}

data class WorkflowRef(val variableId: String, val path: List<String> = emptyList())
sealed interface WorkflowExpression {
    data class Text(val value: String) : WorkflowExpression
    data class Number(val value: Double) : WorkflowExpression
    data class Boolean(val value: kotlin.Boolean) : WorkflowExpression
    data class Ref(val value: WorkflowRef) : WorkflowExpression
    data class Empty(val type: WorkflowType) : WorkflowExpression
    data class Template(val text: String, val bindings: Map<String, WorkflowRef> = emptyMap()) : WorkflowExpression
    data class Record(val fields: Map<String, WorkflowExpression>) : WorkflowExpression
}

data class WorkflowVariable(val id: String, val name: String, val type: WorkflowType)
data class WorkflowNode(
    val id: String, val kind: WorkflowKind, val label: String = "",
    val mode: WorkflowMode = WorkflowMode.ASYNC,
    val variable: WorkflowVariable? = null,
    val target: WorkflowRef? = null,
    val inputs: Map<String, WorkflowExpression> = emptyMap(),
    val resultType: WorkflowType = WorkflowType.TEXT,
    val children: List<WorkflowNode> = emptyList(),
    val otherwise: List<WorkflowNode> = emptyList(),
    val collectTo: WorkflowRef? = null,
    /** Async branches of this row: null follows the engine limit, otherwise 1..[MAX_PARALLEL]. */
    val parallelLimit: Int? = null,
) {
    init {
        require(parallelLimit == null || parallelLimit in 1..MAX_PARALLEL) { "最大并行数应为 1～$MAX_PARALLEL" }
    }
    companion object { const val MAX_PARALLEL = 8 }
}
data class WorkflowProgram(val version: Int = 1, val rows: List<WorkflowNode>) {
    fun allNodes(): List<WorkflowNode> = rows.flatMap { node -> listOf(node) + WorkflowProgram(rows = node.children).allNodes() + WorkflowProgram(rows = node.otherwise).allNodes() }
    fun uses(kind: WorkflowKind) = allNodes().any { it.kind == kind }
    fun usesApi() = uses(WorkflowKind.API) || uses(WorkflowKind.API_STREAM)
}

object WorkflowSystem {
    const val SOURCE = "builtin.sourceLanguage"
    const val TARGET = "builtin.targetLanguage"
    const val STYLE = "builtin.style"
    const val GLOSSARY = "builtin.glossary"
    const val MANGA = "builtin.manga"
    const val CHAPTER = "builtin.chapter"
    const val PAGE = "builtin.page"
    const val BUBBLE = "builtin.bubble"
    const val ALL_PAGES = "builtin.pages"
    const val STREAM_INDEX = "builtin.streamIndex"
    val mangaType = WorkflowType(WorkflowDataKind.RECORD, fields = mapOf("id" to WorkflowType.TEXT, "name" to WorkflowType.TEXT))
    val chapterType = WorkflowType(WorkflowDataKind.RECORD, fields = mapOf("id" to WorkflowType.TEXT, "name" to WorkflowType.TEXT,
        "index" to WorkflowType.NUMBER, "records" to WorkflowType.list(WorkflowType.PAGE_RECORD)))
    val pageType = WorkflowType(WorkflowDataKind.RECORD, fields = mapOf("id" to WorkflowType.TEXT, "name" to WorkflowType.TEXT,
        "number" to WorkflowType.NUMBER, "image" to WorkflowType.IMAGE, "bubbles" to WorkflowType.list(WorkflowType.BUBBLE),
        "records" to WorkflowType.list(WorkflowType.PAGE_RECORD)))
    fun ref(id: String, vararg path: String) = WorkflowExpression.Ref(WorkflowRef(id, path.toList()))
}

object WorkflowTemplates {
    fun blank(): WorkflowProgram {
        val seg = WorkflowNode("seg", WorkflowKind.SEG,
            target = WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles")),
            inputs = mapOf("image" to WorkflowSystem.ref(WorkflowSystem.PAGE, "image")))
        val page = WorkflowNode("pages", WorkflowKind.PAGES, children = listOf(seg))
        val chapter = WorkflowNode("chapters", WorkflowKind.CHAPTERS, children = listOf(page))
        return WorkflowProgram(rows = listOf(WorkflowNode("manga", WorkflowKind.MANGA, children = listOf(chapter))))
    }
    fun localMachine(): WorkflowProgram {
        val bubble = WorkflowVariable(WorkflowSystem.BUBBLE, "气泡", WorkflowType.BUBBLE)
        val steps = listOf(
            WorkflowNode("seg", WorkflowKind.SEG, target = WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles")),
                inputs = mapOf("image" to WorkflowSystem.ref(WorkflowSystem.PAGE, "image"))),
            WorkflowNode("bubbles", WorkflowKind.EACH, variable = bubble,
                inputs = mapOf("items" to WorkflowSystem.ref(WorkflowSystem.PAGE, "bubbles")), children = listOf(
                    WorkflowNode("ocr", WorkflowKind.OCR, target = WorkflowRef(bubble.id, listOf("source")),
                        inputs = mapOf("image" to WorkflowSystem.ref(bubble.id, "image"), "language" to WorkflowSystem.ref(WorkflowSystem.SOURCE))),
                    WorkflowNode("translate", WorkflowKind.TRANSLATE, target = WorkflowRef(bubble.id, listOf("translation")), inputs = mapOf(
                        "text" to WorkflowSystem.ref(bubble.id, "source"), "source" to WorkflowSystem.ref(WorkflowSystem.SOURCE),
                        "target" to WorkflowSystem.ref(WorkflowSystem.TARGET))))))
        return blank().copy(rows = listOf(blank().rows.single().copy(children = listOf(
            blank().rows.single().children.single().copy(children = listOf(WorkflowNode("pages", WorkflowKind.PAGES, children = steps)))))))
    }
    fun visionApi(profileId: String = ""): WorkflowProgram {
        val base = localMachine()
        val bubble = WorkflowSystem.BUBBLE
        val output = WorkflowVariable("vision-result", "气泡对照", WorkflowType.GLOSSARY_ENTRY)
        val prompt = WorkflowExpression.Template("附件是当前单个文字区域（气泡或游离文字）的裁图。将其中所有文字从\${源语言}翻译为\${目标语言}，遵守文风：\${文风}。已有译名字典：\${译名字典}。只返回一个 JSON 对象，字段 source、translation，不要返回列表或 bubbleId。source 是该区域中的完整原文，translation 是完整译文；同一区域的多行／多块文字按阅读顺序合并到这两个字段，不要拆成多个对象。没有文字时两个字段均为空字符串，不要编造文字。",
            mapOf("源语言" to WorkflowRef(WorkflowSystem.SOURCE), "目标语言" to WorkflowRef(WorkflowSystem.TARGET),
                "文风" to WorkflowRef(WorkflowSystem.STYLE), "译名字典" to WorkflowRef(WorkflowSystem.GLOSSARY)))
        val steps = listOf(WorkflowNode("declare-vision", WorkflowKind.DECLARE, variable = output),
            WorkflowNode("api-vision", WorkflowKind.API, target = WorkflowRef(output.id), resultType = output.type,
                inputs = mapOf("profile" to WorkflowExpression.Text(profileId), "prompt" to prompt, "images" to WorkflowSystem.ref(bubble, "image"))),
            WorkflowNode("vision-source", WorkflowKind.SET, target = WorkflowRef(bubble, listOf("source")),
                inputs = mapOf("value" to WorkflowSystem.ref(output.id, "source"))),
            WorkflowNode("vision-target", WorkflowKind.SET, target = WorkflowRef(bubble, listOf("translation")),
                inputs = mapOf("value" to WorkflowSystem.ref(output.id, "translation"))))
        val manga = base.rows.single(); val chapter = manga.children.single(); val page = chapter.children.single()
        return base.copy(rows = listOf(manga.copy(children = listOf(chapter.copy(children = listOf(page.copy(
            children = listOf(page.children.first(), page.children.last().copy(children = steps)))))))))
    }
    fun visionWithGlossary(profileId: String = ""): WorkflowProgram {
        val base = visionApi(profileId)
        val context = WorkflowVariable("glossary-context", "译名提取上下文", WorkflowType.CONTEXT)
        val extracted = WorkflowVariable("new-glossary", "新增译名", WorkflowType.list(WorkflowType.GLOSSARY_ENTRY))
        val afterPages = listOf(
            WorkflowNode("new-glossary-context", WorkflowKind.DECLARE, variable = context),
            WorkflowNode("glossary-fewshot", WorkflowKind.MESSAGE, target = WorkflowRef(context.id), inputs = mapOf(
                "user" to WorkflowExpression.Text("从原文及译文成对提取人物、地名和组织名。输入：[{\"source\":\"Akira arrived at Moon Academy.\",\"translation\":\"阿基拉抵达月亮学院。\"}]；已知译名字典：{}"),
                "assistant" to WorkflowExpression.Text("[{\"source\":\"Akira\",\"translation\":\"阿基拉\"},{\"source\":\"Moon Academy\",\"translation\":\"月亮学院\"}]"))),
            WorkflowNode("new-glossary-list", WorkflowKind.DECLARE, variable = extracted),
            WorkflowNode("extract-glossary", WorkflowKind.API, target = WorkflowRef(extracted.id), resultType = extracted.type, inputs = mapOf(
                "profile" to WorkflowExpression.Text(profileId), "context" to WorkflowSystem.ref(context.id),
                "prompt" to WorkflowExpression.Template("从本章原文／译文对照中提取尚未收录的人物、地名、组织名。只提取有证据且原文与译文一一对应的专名，不提取普通词，不改写已有译名；没有新增项返回 []。已有字典：\${译名字典}。本章对照：\${本章对照}。",
                    mapOf("译名字典" to WorkflowRef(WorkflowSystem.GLOSSARY), "本章对照" to WorkflowRef(WorkflowSystem.CHAPTER, listOf("records")))))),
            WorkflowNode("merge-glossary", WorkflowKind.MERGE_GLOSSARY, inputs = mapOf("items" to WorkflowSystem.ref(extracted.id))))
        val manga = base.rows.single(); val chapter = manga.children.single()
        return base.copy(rows = listOf(manga.copy(children = listOf(chapter.copy(children = chapter.children + afterPages)))))
    }
}
