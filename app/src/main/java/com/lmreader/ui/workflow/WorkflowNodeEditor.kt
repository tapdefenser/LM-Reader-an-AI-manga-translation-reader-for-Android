package com.lmreader.ui.workflow

import com.lmreader.ui.i18n.Text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.lmreader.core.model.*
import com.lmreader.core.workflow.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkflowNodeEditor(value: WorkflowNode, scope: List<WorkflowAvailableVariable>, outScope: List<WorkflowAvailableVariable>, profiles: List<ApiProfile>,
    dismiss: () -> Unit, readOnly: Boolean = false, collectScope: List<WorkflowAvailableVariable> = emptyList(), save: (WorkflowNode) -> Unit) {
    var node by remember(value.id) { mutableStateOf(value) }
    var leaving by remember { mutableStateOf(false) }
    val dirty = !readOnly && node != value
    val latestDirty by rememberUpdatedState(dirty)
    fun leave() { if(dirty) leaving = true else dismiss() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = {
        if(it == SheetValue.Hidden && latestDirty) { leaving = true; false } else true
    })
    val refs = WorkflowEditing.references(scope)
    val outRefs = WorkflowEditing.references(outScope)
    val enabled = !readOnly
    fun parameter(key: String, expression: WorkflowExpression?) {
        node = node.copy(inputs = if(expression == null) node.inputs - key else node.inputs + (key to expression))
    }
    fun targetType() = outRefs.firstOrNull { it.ref == node.target }?.type
    @Composable fun input(key: String, label: String, type: WorkflowType?, optional: Boolean = false) {
        ExpressionEditor(label, type, node.inputs[key], refs, optional = optional, enabled = enabled) { parameter(key, it) }
    }
    ModalBottomSheet(onDismissRequest = { leave() }, sheetState = sheet) {
        BackHandler { leave() }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal = 16.dp).imePadding()) {
            Row {
                Text(WorkflowLabels.kind(node.kind), Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { leave() }) { Text(if(readOnly) "关闭" else "取消") }
                if(enabled) TextButton(onClick = { save(node) }) { Text("完成") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section("输入")
                when(node.kind) {
                    WorkflowKind.DECLARE -> input("value", "初始值", node.variable?.type, optional = true)
                    WorkflowKind.EACH -> ExpressionEditor("遍历列表／字典", null, node.inputs["items"], refs.filter { it.type.kind in setOf(WorkflowDataKind.LIST, WorkflowDataKind.DICTIONARY) }, variableOnly = true, enabled = enabled) { e ->
                        parameter("items", e)
                        val collection = (e as? WorkflowExpression.Ref)?.let { refs.firstOrNull { r -> r.ref == it.value }?.type }
                        val element = if(collection?.kind == WorkflowDataKind.DICTIONARY) WorkflowType(WorkflowDataKind.RECORD, fields = mapOf("key" to WorkflowType.TEXT, "value" to WorkflowType.TEXT)) else collection?.element ?: WorkflowType.TEXT
                        node = node.copy(variable = node.variable?.let { variable -> variable.copy(id = if(element == WorkflowSystem.pageType) WorkflowSystem.PAGE else if(variable.id == WorkflowSystem.PAGE) java.util.UUID.randomUUID().toString() else variable.id, name = if(element == WorkflowSystem.pageType) "本页" else variable.name, type = element) })
                    }
                    WorkflowKind.SEG -> {
                        input("image", "<图片> · 输入图片", WorkflowType.IMAGE)
                        Text("气泡和游离文字统一输出为 <列表<气泡>>，每项都包含自己的裁图。每次成功执行都会替换本页旧气泡与翻译记录；输出到自定义变量也会同步本页。文字检测属于 SEG；OCR 步骤只负责转文字。", style = MaterialTheme.typography.bodySmall)
                    }
                    WorkflowKind.OCR -> { input("image", "<图片> · 气泡图片", WorkflowType.IMAGE); input("language", "<文本> · 原文语言", WorkflowType.TEXT) }
                    WorkflowKind.TRANSLATE -> { input("text", "<文本> · 原文", WorkflowType.TEXT); input("source", "<文本> · 源语言", WorkflowType.TEXT); input("target", "<文本> · 目标语言", WorkflowType.TEXT) }
                    WorkflowKind.API, WorkflowKind.API_STREAM -> {
                        input("context", "<上下文> · 请求上下文", WorkflowType.CONTEXT, optional = true)
                        TemplateEditor("<文本> · 请求提示词", node.inputs["prompt"], refs, enabled) { parameter("prompt", it) }
                        val currentBubble = WorkflowEditing.currentBubble(scope)
                        val automatic = WorkflowEditing.automaticImage(node, scope)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (currentBubble != null) FilterChip(automatic != null, {
                                parameter("images", null); parameter("attachCurrentImage", WorkflowExpression.Boolean(true))
                            }, enabled = enabled, label = { Text("自动附带当前气泡图片") }, modifier = Modifier.testTag("workflow-image-auto"))
                            FilterChip(node.inputs["images"] == null && automatic == null, {
                                parameter("images", null); parameter("attachCurrentImage", WorkflowExpression.Boolean(false))
                            }, enabled = enabled, label = { Text("不附图") }, modifier = Modifier.testTag("workflow-image-none"))
                        }
                        automatic?.let { ref -> refs.firstOrNull { it.ref == ref }?.let { TypedVariable(it) } }
                        ExpressionEditor("<图片>／<列表<图片>> · 附件", null, node.inputs["images"], refs.filter { it.type == WorkflowType.IMAGE || it.type == WorkflowType.list(WorkflowType.IMAGE) }, variableOnly = true, enabled = enabled) {
                            parameter("images", it); parameter("attachCurrentImage", null)
                        }
                        Text("可选择单张图片或图片列表；列表按原顺序作为同一次请求的附件发送，张数不限，只受单次请求总大小限制。新建变量时可直接选择 <列表<图片>>，再用新增项或循环收集图片。", style = MaterialTheme.typography.bodySmall)
                        if (automatic != null) Text("每次请求附带当前这一项的裁图；游离文字也按气泡项处理。", style = MaterialTheme.typography.bodySmall)
                        ExpressionEditor("<列表> · 校验输入气泡（可选）", null, node.inputs["expectedBubbles"], refs.filter { it.type in setOf(WorkflowType.list(WorkflowType.BUBBLE), WorkflowType.list(WorkflowType.TRANSLATION), WorkflowType.list(WorkflowType.PAGE_RECORD)) }, optional = true, variableOnly = true, enabled = enabled) { parameter("expectedBubbles", it) }
                        input("expectedCount", "<数字> · 期望输出项数（可选）", WorkflowType.NUMBER, optional = true)
                    }
                    WorkflowKind.APPLY_TRANSLATIONS, WorkflowKind.APPLY_ORDER -> {
                        val choices = refs.filter { r ->
                            val item = if(r.type.kind == WorkflowDataKind.LIST) r.type.element else r.type
                            if(node.kind == WorkflowKind.APPLY_ORDER) item?.kind == WorkflowDataKind.RECORD && item.fields["translation"] == WorkflowType.TEXT
                            else item in setOf(WorkflowType.TRANSLATION, WorkflowType.PAGE_RECORD)
                        }
                        ExpressionEditor("<记录>／<列表> · 译文", null, node.inputs["items"], choices, variableOnly = true, enabled = enabled) { parameter("items", it) }
                        if(node.kind == WorkflowKind.APPLY_ORDER) input("index", "<数字> · 起始气泡序号（从 1 开始）", WorkflowType.NUMBER)
                    }
                    WorkflowKind.SET -> input("value", "赋值", targetType())
                    WorkflowKind.APPEND -> input("value", "新增值", targetType()?.let { if(it.kind == WorkflowDataKind.LIST) it.element else it })
                    WorkflowKind.MERGE_LIST -> input("value", "合并列表", targetType())
                    WorkflowKind.REPLACE -> { input("text", "<文本> · 匹配文本", WorkflowType.TEXT); input("dictionary", "<字典> · 译名字典", WorkflowType.DICTIONARY) }
                    WorkflowKind.MESSAGE -> {
                        TemplateEditor("<文本> · 用户上下文", node.inputs["user"], refs, enabled) { parameter("user", it) }
                        TemplateEditor("<文本> · 助手示例（可选）", node.inputs["assistant"], refs, enabled, optional = true) { parameter("assistant", it) }
                    }
                    WorkflowKind.MERGE_GLOSSARY -> input("items", "<列表> · 原词／译名", WorkflowType.list(WorkflowType.GLOSSARY_ENTRY))
                    WorkflowKind.IF -> input("condition", "<布尔> · 条件", WorkflowType.BOOLEAN)
                    WorkflowKind.RETURN -> input("value", "旧版返回值", node.resultType)
                    else -> Text("由漫画与当前作用域提供。", style = MaterialTheme.typography.bodySmall)
                }
                Section("输出")
                val filter: (WorkflowReferenceChoice) -> Boolean = when(node.kind) {
                    WorkflowKind.SEG -> { r -> r.type == WorkflowType.list(WorkflowType.BUBBLE) }
                    WorkflowKind.OCR, WorkflowKind.TRANSLATE, WorkflowKind.REPLACE -> { r -> r.type == WorkflowType.TEXT }
                    WorkflowKind.MESSAGE -> { r -> r.type == WorkflowType.CONTEXT }
                    WorkflowKind.APPEND -> { r -> r.type.kind in setOf(WorkflowDataKind.TEXT, WorkflowDataKind.LIST) }
                    WorkflowKind.MERGE_LIST -> { r -> r.type.kind == WorkflowDataKind.LIST }
                    WorkflowKind.API -> { r -> apiOutput(r.type) }
                    WorkflowKind.API_STREAM -> { r -> r.type.kind == WorkflowDataKind.LIST && r.type.element?.kind in setOf(WorkflowDataKind.RECORD, WorkflowDataKind.DICTIONARY) && apiOutput(r.type) }
                    else -> { _ -> true }
                }
                if(node.kind in setOf(WorkflowKind.SEG, WorkflowKind.OCR, WorkflowKind.TRANSLATE, WorkflowKind.API, WorkflowKind.API_STREAM, WorkflowKind.SET, WorkflowKind.REPLACE, WorkflowKind.APPEND, WorkflowKind.MERGE_LIST, WorkflowKind.MESSAGE)) {
                    // Appends are commutative, so an outer list or text stays selectable from a nested async branch.
                    val append = node.kind in setOf(WorkflowKind.APPEND, WorkflowKind.MERGE_LIST)
                    val writable = WorkflowEditing.writeTargets(outScope, append).filter(filter)
                    ReferencePicker("输出到", node.target, writable, enabled = enabled) { ref ->
                        val type = outRefs.firstOrNull { it.ref == ref }?.type ?: node.resultType
                        node = node.copy(target = ref, resultType = type, variable = if(node.kind == WorkflowKind.API_STREAM) node.variable?.copy(type = type.element ?: WorkflowType.TRANSLATION) else node.variable)
                    }
                    if(append) Text("可追加到外层声明的列表或文本。并行分支同时追加不保证顺序，但不会丢项。", style = MaterialTheme.typography.bodySmall)
                }
                if(node.variable != null) {
                    val variable = requireNotNull(node.variable)
                    OutlinedTextField(variable.name, { if(it.length <= 80) node = node.copy(variable = variable.copy(name = it)) }, enabled = enabled && variable.id != WorkflowSystem.PAGE, label = { Text("变量名称") }, modifier = Modifier.fillMaxWidth())
                    if(node.kind == WorkflowKind.DECLARE) TypeEditor("变量类型", variable.type, enabled = enabled) { node = node.copy(variable = variable.copy(type = it), inputs = emptyMap()) }
                    else TypedVariable(WorkflowReferenceChoice(WorkflowRef(variable.id), variable.name, variable.type, false))
                }
                if(node.kind == WorkflowKind.EACH) {
                    ReferencePicker("收集到列表（可选）", node.collectTo, outRefs.filter { !it.readOnly && it.type.kind == WorkflowDataKind.LIST }, optional = true, enabled = enabled) {
                        node = node.copy(collectTo = it, inputs = if(it == null) node.inputs - "collectValue" - "flatten" else node.inputs)
                    }
                    node.collectTo?.let { target ->
                        val type = outRefs.firstOrNull { it.ref == target }?.type ?: refs.firstOrNull { it.ref == target }?.type
                        val flatten = node.inputs["flatten"] == WorkflowExpression.Boolean(true)
                        Row { Text("合并每项列表", Modifier.weight(1f)); Switch(flatten, { parameter("flatten", WorkflowExpression.Boolean(it)); parameter("collectValue", null) }, enabled = enabled) }
                        val childScope = collectScope.filterNot { it.variable.id == node.variable?.id } + listOfNotNull(node.variable?.let { WorkflowAvailableVariable(it) })
                        ExpressionEditor("每项完成时收集值", if(flatten) type else type?.element, node.inputs["collectValue"], WorkflowEditing.references(childScope), enabled = enabled) { parameter("collectValue", it) }
                    }
                    if(node.variable?.type == WorkflowSystem.pageType) Row {
                        Text("循环结束保存本页译文", Modifier.weight(1f))
                        Switch(node.inputs["publish"] != WorkflowExpression.Boolean(false), { parameter("publish", WorkflowExpression.Boolean(it)) }, enabled = enabled)
                    }
                }
                if(node.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM)) {
                    Text("输出：${WorkflowLabels.type(node.resultType)}", color = variableColor(node.resultType))
                    if(apiOutput(node.resultType)) Text("JSON 示例：${AndroidWorkflowHost.schemaExample(node.resultType)}", style = MaterialTheme.typography.bodySmall)
                    if(node.kind == WorkflowKind.API_STREAM) Text("每收到一个完整 JSON 条目，按顺序执行内部行。当前条目序号从 1 开始；后续行等待 API 完整结束。", style = MaterialTheme.typography.bodySmall)
                }
                if(node.kind in setOf(WorkflowKind.APPLY_TRANSLATIONS, WorkflowKind.APPLY_ORDER)) Text("本页译文气泡列表。单条记录可在流式循环中回填；列表可以一次回填。", style = MaterialTheme.typography.bodySmall)
                Section("参数")
                OutlinedTextField(node.label, { if(it.length <= 80) node = node.copy(label = it) }, enabled = enabled, label = { Text("步骤名称（可选）") }, modifier = Modifier.fillMaxWidth())
                if(node.kind in setOf(WorkflowKind.CHAPTERS, WorkflowKind.PAGES, WorkflowKind.EACH)) Row {
                    Text("异步", Modifier.weight(1f)); Switch(node.mode == WorkflowMode.ASYNC, { node = node.copy(mode = if(it) WorkflowMode.ASYNC else WorkflowMode.SYNC) }, enabled = enabled)
                }
                // The limit only applies while the branches run in parallel; null follows the engine setting.
                if(node.mode == WorkflowMode.ASYNC && node.kind in setOf(WorkflowKind.CHAPTERS, WorkflowKind.PAGES, WorkflowKind.EACH)) {
                    val limit = node.parallelLimit
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("最大并行数", Modifier.weight(1f))
                        TextButton(enabled = enabled && limit != null, onClick = { node = node.copy(parallelLimit = null) }, modifier = Modifier.testTag("workflow-parallel-auto")) { Text("自动") }
                        TextButton(enabled = enabled && limit != null && limit > 1, onClick = { node = node.copy(parallelLimit = limit?.minus(1)) }) { Text("－") }
                        Text(limit?.toString() ?: "自动", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("workflow-parallel-value"))
                        TextButton(enabled = enabled && limit != WorkflowNode.MAX_PARALLEL, onClick = { node = node.copy(parallelLimit = limit?.plus(1) ?: 2) }) { Text("＋") }
                    }
                    Text(if(limit == null) "自动结合本模块的 SEG、OCR、API 与机翻额度及缓存预算安排并行；各步骤共享对应引擎额度。" else "本模块同时最多处理 $limit 项，最多 ${WorkflowNode.MAX_PARALLEL} 项；各步骤仍共享对应引擎额度。", style = MaterialTheme.typography.bodySmall)
                }
                if(node.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM)) {
                    var choosing by remember { mutableStateOf(false) }
                    val id = (node.inputs["profile"] as? WorkflowExpression.Text)?.value
                    OutlinedButton(onClick = { choosing = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("API 配置：${profiles.firstOrNull { it.id == id }?.name ?: "未绑定"}") }
                    if(choosing) ChoiceDialog("选择 API 配置", profiles.filter { it.kind == ApiProfileKind.LLM }.map { it.id to "${it.name} · ${it.model} · 并行 ${it.parallelLimit}" }, { choosing = false }) { parameter("profile", WorkflowExpression.Text(it)); choosing = false }
                    if(scope.none { it.variable.id in setOf(WorkflowSystem.CHAPTER, WorkflowSystem.PAGE) }) Row {
                        Text("整漫画单请求", Modifier.weight(1f)); Switch(node.inputs["wholeManga"] == WorkflowExpression.Boolean(true), { parameter("wholeManga", WorkflowExpression.Boolean(it)) }, enabled = enabled)
                    }
                }
                if(node.kind == WorkflowKind.MERGE_GLOSSARY) Text("已有原词保持原译名；后来条目不能覆盖。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if(leaving) AlertDialog(onDismissRequest = { leaving = false }, title = { Text("保存步骤修改？") }, text = { Text("当前步骤有未保存的修改。") },
        confirmButton = { TextButton(onClick = { save(node) }) { Text("保存") } }, dismissButton = { Row {
            TextButton(onClick = dismiss) { Text("放弃") }; TextButton(onClick = { leaving = false }) { Text("继续编辑") }
        } })
}

@Composable private fun Section(label: String) { HorizontalDivider(); Text(label, style = MaterialTheme.typography.titleSmall) }
private fun apiOutput(type: WorkflowType): Boolean = when(type.kind) {
    WorkflowDataKind.TEXT, WorkflowDataKind.NUMBER, WorkflowDataKind.BOOLEAN, WorkflowDataKind.DICTIONARY -> true
    WorkflowDataKind.RECORD -> type.fields.isNotEmpty() && type.fields.values.all(::apiOutput)
    WorkflowDataKind.LIST -> type.element?.let(::apiOutput) == true
    else -> false
}

@Composable
private fun ReferencePicker(label: String, value: WorkflowRef?, choices: List<WorkflowReferenceChoice>, optional: Boolean = false,
    enabled: Boolean = true, changed: (WorkflowRef?) -> Unit) {
    var query by remember(label) { mutableStateOf("") }
    val matches = workflowOptionMatcher(query)
    val visible = choices.filter { matches(typedLabel(it)) }.sortedBy { it.ref != value }
    Column(Modifier.testTag("workflow-reference:$label")) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        WorkflowOptionSearch(label, query) { query = it }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if(optional && matches("不使用")) item { FilterChip(value == null, { changed(null) }, label = { Text("不使用") }, enabled = enabled) }
            items(visible) { choice -> FilterChip(choice.ref == value, { changed(choice.ref) }, label = { TypedVariable(choice) }, enabled = enabled) }
        }
        if(value != null && choices.none { it.ref == value }) Text("引用 · 此处变量不可用", color = MaterialTheme.colorScheme.error)
        if(visible.isEmpty()) Text("无匹配变量", style = MaterialTheme.typography.bodySmall)
        if(query.isNotBlank()) choices.firstOrNull { it.ref == value }?.let { TypedVariable(it) }
    }
}

@Composable
internal fun ExpressionEditor(label: String, type: WorkflowType?, value: WorkflowExpression?, references: List<WorkflowReferenceChoice>,
    optional: Boolean = false, variableOnly: Boolean = false, depth: Int = 0, enabled: Boolean = true, changed: (WorkflowExpression?) -> Unit) {
    val refs = references.filter { type == null || it.type == type }.sortedBy { it.ref != (value as? WorkflowExpression.Ref)?.value }
    var query by remember(label) { mutableStateOf("") }
    val matches = workflowOptionMatcher(query)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        WorkflowOptionSearch(label, query) { query = it }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if(optional && matches("不使用")) item { FilterChip(value == null, { changed(null) }, label = { Text("不使用") }, enabled = enabled) }
            if(!variableOnly) {
                if(type == null || type == WorkflowType.TEXT) {
                    if(matches("<文本>值")) item { FilterChip(value is WorkflowExpression.Text, { changed(WorkflowExpression.Text("")) }, label = { Text("<文本>值", color = variableColor(WorkflowType.TEXT)) }, enabled = enabled) }
                    if(matches("<文本> · 提示词模板")) item { FilterChip(value is WorkflowExpression.Template, { changed(WorkflowExpression.Template((value as? WorkflowExpression.Text)?.value.orEmpty())) }, label = { Text("提示词模板") }, enabled = enabled) }
                }
                if((type == null || type == WorkflowType.NUMBER) && matches("<数字>值")) item { FilterChip(value is WorkflowExpression.Number, { changed(WorkflowExpression.Number(0.0)) }, label = { Text("<数字>值", color = variableColor(WorkflowType.NUMBER)) }, enabled = enabled) }
                if((type == null || type == WorkflowType.BOOLEAN) && matches("<布尔>值")) item { FilterChip(value is WorkflowExpression.Boolean, { changed(WorkflowExpression.Boolean(false)) }, label = { Text("<布尔>值", color = variableColor(WorkflowType.BOOLEAN)) }, enabled = enabled) }
                if(type?.kind == WorkflowDataKind.RECORD && matches("${WorkflowLabels.type(type)} · 构造记录")) item { FilterChip(value is WorkflowExpression.Record, { changed(WorkflowExpression.Record(type.fields.mapValues { WorkflowExpression.Empty(it.value) })) }, label = { Text("${WorkflowLabels.type(type)} · 构造记录", color = variableColor(type)) }, enabled = enabled) }
                if(type?.kind in setOf(WorkflowDataKind.LIST, WorkflowDataKind.DICTIONARY, WorkflowDataKind.CONTEXT) && matches("空${WorkflowLabels.type(type!!)}")) item { FilterChip(value is WorkflowExpression.Empty, { changed(WorkflowExpression.Empty(type!!)) }, label = { Text("空${WorkflowLabels.type(type!!)}", color = variableColor(type)) }, enabled = enabled) }
            }
            items(refs.filter { matches(typedLabel(it)) }) { choice -> FilterChip((value as? WorkflowExpression.Ref)?.value == choice.ref, { changed(WorkflowExpression.Ref(choice.ref)) }, label = { TypedVariable(choice) }, enabled = enabled) }
        }
        if(query.isNotBlank() && refs.none { matches(typedLabel(it)) }) Text("无匹配变量", style = MaterialTheme.typography.bodySmall)
        when(value) {
            is WorkflowExpression.Ref -> refs.firstOrNull { it.ref == value.value }?.let { TypedVariable(it) } ?: Text("引用 · 不可用变量")
            is WorkflowExpression.Template -> TemplateEditor("<文本> · 模板", value, references, enabled, changed = changed)
            is WorkflowExpression.Text -> OutlinedTextField(value.value, { if(it.length <= 100_000) changed(WorkflowExpression.Text(it)) }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("workflow-literal:$label"))
            is WorkflowExpression.Number -> {
                var raw by remember { mutableStateOf(value.value.toString()) }
                var previous by remember { mutableStateOf(value.value) }
                if(previous != value.value) {
                    if(raw.toDoubleOrNull() != value.value) raw = value.value.toString()
                    previous = value.value
                }
                OutlinedTextField(raw, { raw = it; it.toDoubleOrNull()?.takeIf { n -> n.isFinite() }?.let { n -> changed(WorkflowExpression.Number(n)) } }, enabled = enabled, isError = raw.toDoubleOrNull()?.isFinite() != true, modifier = Modifier.fillMaxWidth().testTag("workflow-literal:$label"))
            }
            is WorkflowExpression.Boolean -> Switch(value.value, { changed(WorkflowExpression.Boolean(it)) }, enabled = enabled)
            is WorkflowExpression.Record -> if(depth < 8) type?.fields?.forEach { (key, fieldType) ->
                ExpressionEditor("${WorkflowLabels.type(fieldType)} · ${WorkflowLabels.field(key)}", fieldType, value.fields[key], references, depth = depth + 1, enabled = enabled) { e ->
                    changed(value.copy(fields = if(e == null) value.fields - key else value.fields + (key to e)))
                }
            }
            is WorkflowExpression.Empty -> Text("空${WorkflowLabels.type(value.type)}", style = MaterialTheme.typography.bodySmall)
            null -> Unit
        }
    }
}

@Composable
private fun TemplateEditor(label: String, expression: WorkflowExpression?, refs: List<WorkflowReferenceChoice>,
    enabled: Boolean, optional: Boolean = false, changed: (WorkflowExpression?) -> Unit) {
    val value = expression as? WorkflowExpression.Template ?: WorkflowExpression.Template((expression as? WorkflowExpression.Text)?.value.orEmpty())
    var field by remember { mutableStateOf(TextFieldValue(value.text, TextRange(value.text.length))) }
    if(field.text != value.text) field = field.copy(text = value.text, selection = TextRange(value.text.length))
    var query by remember(label) { mutableStateOf("") }
    val matches = workflowOptionMatcher(query)
    val visible = refs.filter { serializable(it.type) && matches(typedLabel(it)) }
    Column {
        Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if(optional) TextButton(onClick = { changed(null) }, enabled = enabled && expression != null) { Text("不使用") }
        }
        WorkflowOptionSearch(label, query) { query = it }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(visible) { choice -> AssistChip(onClick = {
                var key = choice.label; var index = 2
                while(value.bindings[key]?.let { it != choice.ref } == true) key = choice.label + index++
                val token = "$" + "{" + key + "}"
                val start = field.selection.min; val end = field.selection.max
                val text = field.text.replaceRange(start, end, token)
                if(text.length <= 100_000) {
                    field = TextFieldValue(text, TextRange(start + token.length))
                    changed(value.copy(text = text, bindings = value.bindings + (key to choice.ref)))
                }
            }, enabled = enabled, label = { TypedVariable(choice) }) }
        }
        if(visible.isEmpty()) Text("无匹配变量", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(field, { next -> if(next.text.length <= 100_000) { field = next; if(next.text != value.text) changed(value.copy(text = next.text)) } },
            enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("提示词模板") })
        val selected = (expression as? WorkflowExpression.Ref)?.let { e -> refs.firstOrNull { it.ref == e.value } }
        if(selected != null) {
            TypedVariable(selected)
            Text("当前为变量引用。输入模板会替换该引用。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
private fun serializable(type: WorkflowType): Boolean = when(type.kind) {
    WorkflowDataKind.IMAGE, WorkflowDataKind.CONTEXT -> false
    WorkflowDataKind.LIST -> type.element?.let(::serializable) == true
    else -> type.fields.values.all(::serializable)
}

@Composable
internal fun TypeEditor(label: String, value: WorkflowType, depth: Int = 0, enabled: Boolean = true, changed: (WorkflowType) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { choosing = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(WorkflowLabels.type(value), color = variableColor(value)) }
        if(value.kind == WorkflowDataKind.LIST && depth < 4) TypeEditor("列表条目类型", value.element!!, depth + 1, enabled) { changed(value.copy(element = it)) }
        if(value.kind == WorkflowDataKind.RECORD && depth < 4) {
            value.fields.entries.forEachIndexed { index, (key, type) ->
                var fieldName by remember(value, index) { mutableStateOf(key) }
                OutlinedTextField(fieldName, { text ->
                    fieldName = text
                    if(text.isNotBlank() && text.length <= 80 && (text == key || text !in value.fields)) changed(value.copy(fields = value.fields.entries.associate { (k, v) -> (if(k == key) text else k) to v }))
                }, enabled = enabled, label = { Text("字段名称") }, isError = fieldName.isBlank() || (fieldName != key && fieldName in value.fields))
                TypeEditor("字段类型", type, depth + 1, enabled) { changed(value.copy(fields = value.fields + (key to it))) }
                if(enabled) TextButton(onClick = { changed(value.copy(fields = value.fields - key)) }) { Text("删除字段") }
            }
            if(enabled) TextButton(enabled = value.fields.size < 64, onClick = {
                var key = "字段${value.fields.size + 1}"; while(key in value.fields) key += "新"
                changed(value.copy(fields = value.fields + (key to WorkflowType.TEXT)))
            }) { Text("＋ 新增字段") }
        }
    }
    if(choosing) ChoiceDialog(label, buildList {
        listOf(WorkflowType.TEXT, WorkflowType.NUMBER, WorkflowType.BOOLEAN, WorkflowType.IMAGE).forEach { add(it to WorkflowLabels.type(it)) }
        add(WorkflowType.BUBBLE to "${WorkflowLabels.type(WorkflowType.BUBBLE)}（来自 SEG）")
        listOf(WorkflowType.CONTEXT, WorkflowType.DICTIONARY).forEach { add(it to WorkflowLabels.type(it)) }
        if(depth < 4) {
            add(WorkflowType.list(WorkflowType.IMAGE) to WorkflowLabels.type(WorkflowType.list(WorkflowType.IMAGE)))
            val list = WorkflowType.list(WorkflowType.TEXT)
            val record = WorkflowType(WorkflowDataKind.RECORD, fields = mapOf("字段1" to WorkflowType.TEXT))
            add(list to "${WorkflowLabels.type(list)}（自定义列表）"); add(record to WorkflowLabels.type(record))
        }
        add(WorkflowType.TRANSLATION to "${WorkflowLabels.type(WorkflowType.TRANSLATION)}：bubbleId／source／translation")
        add(WorkflowType.GLOSSARY_ENTRY to "${WorkflowLabels.type(WorkflowType.GLOSSARY_ENTRY)}：source／translation")
        add(WorkflowType.PAGE_RECORD to "${WorkflowLabels.type(WorkflowType.PAGE_RECORD)}：页ID／页码／气泡ID／原文／译文")
    }, { choosing = false }, searchable = true) { changed(it); choosing = false }
}
