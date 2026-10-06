package com.lmreader.core.workflow

import com.lmreader.core.model.*

/** A readable view of the actual editable AST, never a separately maintained recipe. */
object WorkflowSource {
    fun render(program: WorkflowProgram): String {
        val validation = WorkflowValidator.validate(program)
        val out = StringBuilder()
        fun visit(nodes: List<WorkflowNode>, depth: Int) {
            val indent = "    ".repeat(depth)
            nodes.forEach { n ->
                val refs = WorkflowEditing.references(validation.scopes[n.id].orEmpty()) +
                    if(n.kind == WorkflowKind.EACH) WorkflowEditing.references(n.children.lastOrNull()?.let { validation.scopes[it.id + ":end"] }.orEmpty()) else emptyList()
                fun ref(r: WorkflowRef): String = refs.firstOrNull { it.ref == r }?.let {
                    "${it.label.replace(" · ", "-")}${WorkflowLabels.type(it.type)}"
                } ?: r.variableId
                fun expr(e: WorkflowExpression): String = when(e) {
                    is WorkflowExpression.Ref -> ref(e.value)
                    is WorkflowExpression.Text -> quote(e.value)
                    is WorkflowExpression.Template -> quote(e.text) + " [" + e.bindings.entries.joinToString { (k, v) -> "\${$k}=${ref(v)}" } + "]"
                    is WorkflowExpression.Number -> e.value.toString()
                    is WorkflowExpression.Boolean -> e.value.toString()
                    is WorkflowExpression.Empty -> "空${WorkflowLabels.type(e.type)}"
                    is WorkflowExpression.Record -> e.fields.entries.joinToString(", ", "{", "}") { (k, v) -> "$k: ${expr(v)}" }
                }
                fun input(key: String) = n.inputs[key]?.let(::expr) ?: "未填写"
                fun target() = n.target?.let(::ref) ?: "未填写"
                fun imageArgument() = n.inputs["images"]?.let { "图片=${expr(it)}, " }
                    ?: WorkflowEditing.automaticImage(n, validation.scopes[n.id].orEmpty())?.let { "图片=${ref(it)}（自动）, " }
                    ?: "图片=无, "
                val mode = (if(n.mode == WorkflowMode.ASYNC) "异步" else "同步") + (n.parallelLimit?.let { "，最多并行 $it" } ?: "")
                val header = when(n.kind) {
                    WorkflowKind.MANGA -> "漫画"
                    WorkflowKind.CHAPTERS -> "每章节($mode)"
                    WorkflowKind.PAGES -> "每页($mode)"
                    WorkflowKind.PREPARE_MANGA -> "预处理整漫画(逐章节/逐页，$mode，收集到 ${target()})"
                    WorkflowKind.EACH -> "每(${n.variable?.name}${n.variable?.type?.let(WorkflowLabels::type)}) 在(${input("items")})($mode)" + (n.collectTo?.let { (if(n.inputs["flatten"] == WorkflowExpression.Boolean(true)) " 合并收集 " else " 收集 ") + "${input("collectValue")} 到 ${ref(it)}" } ?: "") + (if(n.inputs["publish"] == WorkflowExpression.Boolean(false)) "（本阶段不保存译文）" else "")
                    WorkflowKind.IF -> "如果(${input("condition")})"
                    WorkflowKind.DECLARE -> "新建：${n.variable?.name}${n.variable?.type?.let(WorkflowLabels::type)}" + (n.inputs["value"]?.let { " = ${expr(it)}" } ?: "")
                    WorkflowKind.SEG -> "SEG：${target()} = SEG(${input("image")})"
                    WorkflowKind.OCR -> "请求：${target()} = OCR(${input("image")}, ${input("language")})"
                    WorkflowKind.TRANSLATE -> "请求：${target()} = 本地机翻(${input("text")}, ${input("source")}, ${input("target")})"
                    WorkflowKind.API -> "请求：${target()} = API(配置=${input("profile")}, " +
                        (n.inputs["context"]?.let { "上下文=${expr(it)}, " } ?: "") + "提示词=${input("prompt")}, " +
                        imageArgument() + "输出=${WorkflowLabels.type(n.resultType)}" +
                        (n.inputs["expectedBubbles"]?.let { ", 校验=${expr(it)}" } ?: "") +
                        (n.inputs["expectedCount"]?.let { ", 校验项数=${expr(it)}" } ?: "") +
                        (if(n.inputs["wholeManga"] == WorkflowExpression.Boolean(true)) ", 整漫画一次请求" else "") + ")"
                    WorkflowKind.API_STREAM -> "每(${n.variable?.name}${n.variable?.type?.let(WorkflowLabels::type)}) 在 API请求－流式输出(配置=${input("profile")}, " +
                        (n.inputs["context"]?.let { "上下文=${expr(it)}, " } ?: "") + "提示词=${input("prompt")}, " +
                        imageArgument() + "输出=${WorkflowLabels.type(n.resultType)}" +
                        (n.inputs["expectedBubbles"]?.let { ", 校验=${expr(it)}" } ?: "") +
                        (n.inputs["expectedCount"]?.let { ", 校验项数=${expr(it)}" } ?: "") +
                        (if(n.inputs["wholeManga"] == WorkflowExpression.Boolean(true)) ", 整漫画一次请求" else "") + ") 累积到 ${target()}"
                    WorkflowKind.APPLY_TRANSLATIONS -> "按本页和气泡 ID 回填(${input("items")})"
                    WorkflowKind.APPLY_ORDER -> "按气泡顺序回填(${input("items")}, 起始序号=${input("index")})"
                    WorkflowKind.SET -> "${target()} = ${input("value")}"
                    WorkflowKind.APPEND -> "${target()} += ${input("value")}"
                    WorkflowKind.MERGE_LIST -> "${target()} 合并 ${input("value")}"
                    WorkflowKind.REPLACE -> "${target()} = ${input("text")} 匹配替换 ${input("dictionary")}"
                    WorkflowKind.MESSAGE -> "${target()} 新增上下文(用户 ${input("user")}" + (n.inputs["assistant"]?.let { ", 助手 ${expr(it)}" } ?: "") + ")"
                    WorkflowKind.MERGE_GLOSSARY -> "漫画译名字典 新增未收录译名(${input("items")})"
                    WorkflowKind.RETURN -> "返回：${input("value")}"
                }
                if(n.label.isNotBlank()) out.appendLine("$indent# ${n.label}")
                val container = n.kind in setOf(WorkflowKind.MANGA, WorkflowKind.CHAPTERS, WorkflowKind.PAGES, WorkflowKind.PREPARE_MANGA, WorkflowKind.EACH, WorkflowKind.IF, WorkflowKind.API_STREAM)
                out.appendLine(indent + header + if(container) " {" else "")
                if(container) {
                    visit(n.children, depth + 1)
                    if(n.kind == WorkflowKind.IF && n.otherwise.isNotEmpty()) { out.appendLine("$indent} 否则 {"); visit(n.otherwise, depth + 1) }
                    out.appendLine("$indent}")
                }
            }
        }
        visit(program.rows, 0)
        return out.toString()
    }
    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
}
