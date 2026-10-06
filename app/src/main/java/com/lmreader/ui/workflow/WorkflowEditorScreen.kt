package com.lmreader.ui.workflow

import com.lmreader.ui.i18n.Icon

import com.lmreader.ui.i18n.Text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lmreader.core.model.*
import com.lmreader.core.workflow.*
import com.lmreader.di.AppContainer
import java.util.UUID

internal val workflowContainers = setOf(WorkflowKind.MANGA, WorkflowKind.CHAPTERS, WorkflowKind.PAGES, WorkflowKind.EACH, WorkflowKind.IF, WorkflowKind.API_STREAM)
private data class EditorScope(val id: String, val depth: Int)
private data class EditorEntry(val key: String, val depth: Int, val position: WorkflowPosition,
    val scopes: List<EditorScope>, val node: WorkflowNode? = null, val caption: String? = null)
private fun entries(program: WorkflowProgram): List<EditorEntry> = buildList {
    fun visit(rows: List<WorkflowNode>, parent: String?, depth: Int, scopes: List<EditorScope>, otherwise: Boolean = false) {
        rows.forEachIndexed { index, node ->
            val position = WorkflowPosition(parent, index, otherwise)
            val nested = if(node.kind in workflowContainers) scopes + EditorScope(node.id, depth) else scopes
            add(EditorEntry(node.id, depth, position, nested, node))
            if(node.kind in workflowContainers) {
                visit(node.children, node.id, depth + 1, nested)
                if(node.kind == WorkflowKind.IF) {
                    add(EditorEntry("else:" + node.id, depth, position, nested, caption = "否则"))
                    visit(node.otherwise, node.id, depth + 1, nested, true)
                }
                add(EditorEntry("close:" + node.id, depth, position, nested, caption = "结束 " + WorkflowLabels.kind(node.kind)))
            }
        }
        add(EditorEntry("slot:$parent:$otherwise:" + rows.size, depth, WorkflowPosition(parent, rows.size, otherwise), scopes))
    }
    visit(program.rows, null, 0, emptyList())
}

// Each lazy row paints its portion of the same scope block, rounding only its first/last row.
private fun Modifier.scopeBackground(entry: EditorEntry, surface: Color, primary: Color) = drawBehind {
    val indent = 16.dp.toPx()
    val gap = 4.dp.toPx()
    val radius = CornerRadius(14.dp.toPx())
    entry.scopes.forEach { scope ->
        val first = entry.key == scope.id
        val last = entry.key == "close:" + scope.id
        val shade = lerp(surface, primary, (.06f + scope.depth * .055f).coerceAtMost(.48f))
        val path = Path().apply {
            addRoundRect(RoundRect(Rect(scope.depth * indent, if(first) gap else 0f, size.width, size.height - if(last) gap else 0f),
                topLeft = if(first) radius else CornerRadius.Zero,
                topRight = if(first) radius else CornerRadius.Zero,
                bottomRight = if(last) radius else CornerRadius.Zero,
                bottomLeft = if(last) radius else CornerRadius.Zero))
        }
        drawPath(path, shade)
    }
}

private data class SummaryValue(val label: String, val type: WorkflowType? = null)

private fun inputSummary(node: WorkflowNode, refs: List<WorkflowReferenceChoice>): List<SummaryValue> {
    if(node.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM)) return listOf(SummaryValue("提示词模板", WorkflowType.TEXT))
    return node.inputs.filterKeys { it !in setOf("profile", "publish", "flatten", "wholeManga") }.values.map { expression ->
        val choice = (expression as? WorkflowExpression.Ref)?.let { e -> refs.firstOrNull { it.ref == e.value } }
        if(choice != null) SummaryValue(typedLabel(choice), choice.type)
        else SummaryValue(expressionLabel(expression, refs), when(expression) {
            is WorkflowExpression.Text, is WorkflowExpression.Template -> WorkflowType.TEXT
            is WorkflowExpression.Number -> WorkflowType.NUMBER
            is WorkflowExpression.Boolean -> WorkflowType.BOOLEAN
            is WorkflowExpression.Empty -> expression.type
            is WorkflowExpression.Record -> WorkflowType(WorkflowDataKind.RECORD)
            else -> null
        })
    }
}

@Composable
private fun SummaryLine(prefix: String, values: List<SummaryValue>) {
    val colors = values.map { it.type?.let { type -> variableColor(type) } ?: MaterialTheme.colorScheme.onSurfaceVariant }
    val text = buildAnnotatedString {
        append("$prefix: ")
        if(values.isEmpty()) append("无")
        values.forEachIndexed { index, value ->
            if(index > 0) append("；")
            withStyle(SpanStyle(color = colors[index])) { append(value.label.replace('\n', ' ').replace('\r', ' ')) }
        }
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowEditorScreen(container: AppContainer, value: TranslationWorkflow, readOnly: Boolean = false,
    onDismiss: () -> Unit, onSave: (TranslationWorkflow) -> Unit, onBindApi: (String) -> Unit = {}) {
    var program by remember(value.id, value.revision) { mutableStateOf(value.program) }
    var name by remember(value.id, value.revision) { mutableStateOf(value.name) }
    var description by remember(value.id, value.revision) { mutableStateOf(value.description) }
    var retries by remember(value.id, value.revision) { mutableIntStateOf(value.retries) }
    val locked = readOnly || !value.editable || value.builtIn
    val history = remember { mutableStateListOf<WorkflowProgram>() }
    var selected by remember { mutableStateOf<WorkflowNode?>(null) }
    var adding by remember { mutableStateOf<WorkflowPosition?>(null) }
    var inspecting by remember { mutableStateOf<WorkflowPosition?>(null) }
    var moving by remember { mutableStateOf<WorkflowNode?>(null) }
    var metadata by remember { mutableStateOf(false) }
    var template by remember { mutableStateOf(false) }
    var api by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val profiles by container.apiProfiles.profiles.collectAsState(initial = emptyList())
    val rows = remember(program) { entries(program) }
    val validation = remember(program) { WorkflowValidator.validate(program) }
    fun change(next: WorkflowProgram) {
        if(program != next) { history.add(program); if(history.size > 50) history.removeAt(0); program = next }
    }
    fun save() = onSave(value.copy(name = name.trim().ifBlank { value.name }, description = description.trim(), retries = retries, program = program))
    fun leave() {
        if(!locked && (program != value.program || name != value.name || description != value.description || retries != value.retries)) leaving = true else onDismiss()
    }
    fun move(node: WorkflowNode, position: WorkflowPosition) {
        runCatching { change(WorkflowEditing.move(program, node.id, position)) }.onFailure { error = it.message }
    }
    BackHandler(enabled = selected == null) { leave() }
    Scaffold(topBar = { TopAppBar(title = { Text(name, maxLines = 1) }, navigationIcon = {
        IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, actions = {
        if(!locked) {
            IconButton(enabled = history.isNotEmpty(), onClick = { program = history.removeAt(history.lastIndex) }) { Icon(Icons.Default.Undo, "撤销") }
            TextButton(onClick = { save() }) { Text("保存") }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "工作流菜单") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("查看猫爪文本") }, onClick = { menu = false; source = true })
                if(!locked) {
                    DropdownMenuItem(text = { Text("名称与重试") }, onClick = { menu = false; metadata = true })
                    DropdownMenuItem(text = { Text("填入模板") }, onClick = { menu = false; template = true })
                }
                if(program.usesApi() && !value.builtIn && (!locked || !value.editable))
                    DropdownMenuItem(text = { Text("为所有请求选择 API") }, onClick = { menu = false; api = true })
                DropdownMenuItem(text = { Text("使用说明") }, onClick = { menu = false; help = true })
            }
        }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp).testTag("workflow-rows")) {
            item("summary") { Text((if(value.builtIn) "参考模板 · " else if(!value.editable) "固定工作流 · " else "") +
                if(validation.valid) "${program.allNodes().size} 行 · 配置完整" else "草稿 · ${validation.issues.size} 处待填写",
                Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelLarge) }
            items(rows, key = { it.key }) { entry ->
                val node = entry.node
                Column(Modifier.fillMaxWidth().scopeBackground(entry, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary)
                    .padding(start = (entry.depth * 16 + 8).dp, end = 8.dp,
                        top = if(node?.kind in workflowContainers) 8.dp else 0.dp,
                        bottom = if(entry.key.startsWith("close:")) 8.dp else 0.dp)) {
                    if(node == null) {
                        if(entry.caption != null) Text(entry.caption, Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
                        else if(!locked) TextButton(onClick = { adding = entry.position }, modifier = Modifier.fillMaxWidth().testTag("workflow-slot:" + entry.key)) { Text("＋ 新增行") }
                    } else {
                        val fixed = node.kind in WorkflowValidator.fixedKinds
                        var rowMenu by remember(node.id) { mutableStateOf(false) }
                        val issue = validation.issues.firstOrNull { it.nodeId == node.id }
                        val refs = WorkflowEditing.references(validation.scopes[node.id].orEmpty())
                        val after = WorkflowEditing.references(WorkflowEditing.outputs(program, node.id, validation))
                        Card(onClick = { selected = node }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("workflow-row:" + node.id).animateItem(),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if(issue != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Row {
                                    Text(node.label.ifBlank { WorkflowLabels.kind(node.kind) }, Modifier.weight(1f).padding(top = 10.dp), style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if(node.kind in setOf(WorkflowKind.CHAPTERS, WorkflowKind.PAGES, WorkflowKind.EACH))
                                        TextButton(enabled = !locked, modifier = Modifier.testTag("workflow-mode:" + node.id), onClick = {
                                            change(WorkflowEditing.update(program, node.id) { it.copy(mode = if(it.mode == WorkflowMode.ASYNC) WorkflowMode.SYNC else WorkflowMode.ASYNC) })
                                        }) { Text(if(node.mode == WorkflowMode.ASYNC) "异步" else "同步") }
                                    IconButton(onClick = { inspecting = if(node.kind in workflowContainers) WorkflowPosition(node.id, 0) else entry.position.copy(index = entry.position.index + 1) }) {
                                        Icon(Icons.Default.DataObject, "此处可用变量", Modifier.size(20.dp))
                                    }
                                    if(!locked && !fixed) Box {
                                        IconButton(onClick = { rowMenu = true }) { Icon(Icons.Default.MoreVert, "步骤菜单") }
                                        DropdownMenu(rowMenu, { rowMenu = false }) {
                                            DropdownMenuItem(text = { Text("编辑参数") }, onClick = { rowMenu = false; selected = node })
                                            DropdownMenuItem(text = { Text("上移") }, enabled = entry.position.index > 0, onClick = { rowMenu = false; move(node, entry.position.copy(index = entry.position.index - 1)) })
                                            DropdownMenuItem(text = { Text("下移") }, enabled = entry.position.index + 1 < WorkflowEditing.rows(program, entry.position).size, onClick = { rowMenu = false; move(node, entry.position.copy(index = entry.position.index + 2)) })
                                            DropdownMenuItem(text = { Text("移到外层") }, enabled = entry.position.parentId != null, onClick = {
                                                rowMenu = false
                                                WorkflowEditing.flatten(program).firstOrNull { it.node.id == entry.position.parentId }?.let { move(node, it.position.copy(index = it.position.index + 1)) }
                                            })
                                            DropdownMenuItem(text = { Text("移动到…") }, onClick = { rowMenu = false; moving = node })
                                            DropdownMenuItem(text = { Text("复制") }, onClick = { rowMenu = false; change(WorkflowEditing.insert(program, entry.position.copy(index = entry.position.index + 1), WorkflowEditing.copyNode(node))) })
                                            DropdownMenuItem(text = { Text("删除") }, onClick = { rowMenu = false; runCatching { change(WorkflowEditing.remove(program, node.id)) }.onFailure { error = it.message } })
                                        }
                                    }
                                }
                                SummaryLine("In", inputSummary(node, refs))
                                val scopeOutput = when(node.kind) {
                                    WorkflowKind.MANGA -> WorkflowReferenceChoice(WorkflowRef(WorkflowSystem.MANGA), "漫画", WorkflowSystem.mangaType, true)
                                    WorkflowKind.CHAPTERS -> WorkflowReferenceChoice(WorkflowRef(WorkflowSystem.CHAPTER), "本章", WorkflowSystem.chapterType, true)
                                    WorkflowKind.PAGES -> WorkflowReferenceChoice(WorkflowRef(WorkflowSystem.PAGE), "本页", WorkflowSystem.pageType, false)
                                    else -> null
                                }
                                val declared = listOfNotNull(node.variable).map { TypedChoice(it) }
                                val written = listOfNotNull(node.target, node.collectTo).mapNotNull { r -> (refs + after).firstOrNull { it.ref == r } }.map { TypedChoice(it) }
                                val destinations = (declared + written + listOfNotNull(scopeOutput?.let { TypedChoice(it) })).distinct()
                                SummaryLine("Out", buildList {
                                    destinations.forEach { add(SummaryValue("${WorkflowLabels.type(it.type)} · ${it.name}", it.type)) }
                                    if(node.kind in setOf(WorkflowKind.APPLY_TRANSLATIONS, WorkflowKind.APPLY_ORDER)) add(SummaryValue("<文本> · 本页各气泡译文", WorkflowType.TEXT))
                                    if(node.mode == WorkflowMode.ASYNC) node.parallelLimit?.let { add(SummaryValue("并行上限 $it")) }
                                })
                                issue?.let { Text(it.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
    adding?.let { position -> AddStepDialog({ adding = null }) { kind ->
        val node = newNode(kind, WorkflowEditing.references(WorkflowEditing.scope(program, position)), profiles)
        change(WorkflowEditing.insert(program, position, node)); adding = null; selected = node
    } }
    selected?.let { node ->
        val collectScope = if(node.children.isNotEmpty()) validation.scopes[node.children.last().id + ":end"].orEmpty() else validation.scopes[node.id + ":children"].orEmpty()
        WorkflowNodeEditor(node, validation.scopes[node.id].orEmpty(), WorkflowEditing.outputs(program, node.id, validation), profiles, { selected = null }, readOnly = locked, collectScope = collectScope) { changed ->
            change(WorkflowEditing.update(program, node.id) { changed }); selected = null
        }
    }
    inspecting?.let { VariableDialog(WorkflowEditing.references(WorkflowEditing.scope(program, it)), { inspecting = null }) }
    moving?.let { node ->
        val positions = buildList {
            WorkflowEditing.flatten(program).forEach { row ->
                add(row.position to ("　".repeat(row.depth) + row.node.label.ifBlank { WorkflowLabels.kind(row.node.kind) } + " 之前"))
                if(row.node.kind in workflowContainers) {
                    add(WorkflowPosition(row.node.id, row.node.children.size) to ("　".repeat(row.depth + 1) + row.node.label.ifBlank { WorkflowLabels.kind(row.node.kind) } + " · 末尾"))
                    if(row.node.kind == WorkflowKind.IF) add(WorkflowPosition(row.node.id, row.node.otherwise.size, true) to "否则 · 末尾")
                }
            }
        }.filter { (position, _) -> runCatching { WorkflowEditing.move(program, node.id, position) }.isSuccess }
        ChoiceDialog("移动到", positions, { moving = null }) { move(node, it); moving = null }
    }
    if(metadata) AlertDialog(onDismissRequest = { metadata = false }, title = { Text("名称与重试") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { if(it.length <= 80) name = it }, label = { Text("名称") })
            OutlinedTextField(description, { if(it.length <= 500) description = it }, label = { Text("说明") })
            Row { Text("失败额外重试 $retries 次"); TextButton(enabled = retries > 0, onClick = { retries-- }) { Text("－") }; TextButton(enabled = retries < 5, onClick = { retries++ }) { Text("＋") } }
        }
    }, confirmButton = { TextButton(onClick = { metadata = false }) { Text("完成") } })
    if(template) ChoiceDialog("填入模板（可以撤销）", listOf("blank" to "空白结构", "local" to "SEG → OCR → 本地机翻", "standard" to "标准翻译（每页文本 API）", "vl" to "VL 直接翻译（双语＋章末译名）", "vl-page" to "VL 整页直接翻译（多图一次 API）"), { template = false }) {
        val id = profiles.firstOrNull { it.kind == ApiProfileKind.LLM }?.id.orEmpty()
        change(when(it) { "blank" -> WorkflowTemplates.blank(); "local" -> WorkflowTemplates.localMachine(); "standard" -> WorkflowReferenceTemplates.standard(id); "vl-page" -> WorkflowReferenceTemplates.visionPage(id); else -> WorkflowReferenceTemplates.vision(id) }); template = false
    }
    if(api) ChoiceDialog("为所有请求选择 API", profiles.filter { it.kind == ApiProfileKind.LLM }.map { it.id to ("${it.name} · ${it.model}") }, { api = false }) {
        if(!value.editable) onBindApi(it) else change(WorkflowReferenceTemplates.bindApi(program, it))
        api = false
    }
    if(source) Dialog(onDismissRequest = { source = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(16.dp)) {
            Row { Text("猫爪文本", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); TextButton(onClick = { source = false }) { Text("关闭") } }
            androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(WorkflowSource.render(program), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        } }
    }
    if(help) AlertDialog(onDismissRequest = { help = false }, title = { Text("猫爪工作流") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("漫画、每章节、每页为固定结构。步骤菜单可上下移动或移入其他作用域；需要的变量必须在目的位置可用。各作用域末尾新增行。")
            Text("同步依次处理；异步受引擎并行数与缓存限制。异步模块可单独设置最大并行数，设为自动时跟随引擎上限。循环后的行等待所有分支完成。每项循环在参数中选择收集值，按原顺序写入外层列表。")
            Text("变量以“<类型> · 名称”显示，颜色代表类型。{} 查看当前作用域的变量及字段。提示词变量条可左右滑动，点击插入光标处。")
            Text("漫画译名字典实时读取，后来同名译名不能覆盖已有内容。固定工作流可以统一绑定 API；固定标识只能由外部工具修改导出文件。")
        }
    }, confirmButton = { TextButton(onClick = { help = false }) { Text("关闭") } })
    if(leaving) AlertDialog(onDismissRequest = { leaving = false }, title = { Text("保存工作流？") }, text = { Text("工作流有未保存的修改。") },
        confirmButton = { TextButton(onClick = { save() }) { Text("保存") } }, dismissButton = { Row { TextButton(onClick = onDismiss) { Text("放弃") }; TextButton(onClick = { leaving = false }) { Text("继续编辑") } } })
    error?.let { AlertDialog(onDismissRequest = { error = null }, title = { Text("无法完成操作") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { error = null }) { Text("关闭") } }) }
}

@Composable
internal fun <T> ChoiceDialog(title: String, choices: List<Pair<T, String>>, dismiss: () -> Unit, searchable: Boolean = false, choose: (T) -> Unit) {
    var query by remember(title) { mutableStateOf("") }
    val matches = workflowOptionMatcher(query)
    val visible = choices.filter { matches(it.second) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 500.dp)) {
            if(searchable) WorkflowOptionSearch(title, query) { query = it }
            LazyColumn(Modifier.weight(1f, false)) { if(visible.isEmpty()) item { Text(if(query.isBlank()) "没有匹配选项，请先新建变量或配置引擎" else "无匹配选项") }
                items(visible.size) { i -> TextButton(onClick = { choose(visible[i].first) }, modifier = Modifier.fillMaxWidth()) { Text(visible[i].second) } }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}

@Composable
private fun AddStepDialog(dismiss: () -> Unit, choose: (WorkflowKind) -> Unit) {
    val groups = linkedMapOf("识别与翻译" to listOf(WorkflowKind.SEG, WorkflowKind.OCR, WorkflowKind.TRANSLATE, WorkflowKind.API, WorkflowKind.API_STREAM, WorkflowKind.APPLY_TRANSLATIONS, WorkflowKind.APPLY_ORDER),
        "变量与列表" to listOf(WorkflowKind.DECLARE, WorkflowKind.SET, WorkflowKind.APPEND, WorkflowKind.MERGE_LIST),
        "流程" to listOf(WorkflowKind.EACH, WorkflowKind.IF), "上下文与译名" to listOf(WorkflowKind.MESSAGE, WorkflowKind.REPLACE, WorkflowKind.MERGE_GLOSSARY))
    AlertDialog(onDismissRequest = dismiss, title = { Text("新增步骤") }, text = { LazyColumn {
        groups.forEach { (name, kinds) ->
            item { Text(name, style = MaterialTheme.typography.labelLarge); HorizontalDivider() }
            items(kinds) { kind -> TextButton(onClick = { choose(kind) }, modifier = Modifier.fillMaxWidth()) { Text(WorkflowLabels.kind(kind)) } }
        }
    } }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}

@Composable
private fun VariableDialog(refs: List<WorkflowReferenceChoice>, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = refs.filter { query.isBlank() || typedLabel(it).contains(query.trim(), true) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("此处可用变量") }, text = { Column(Modifier.heightIn(max = 500.dp)) {
        OutlinedTextField(query, { query = it }, label = { Text("搜索变量或字段") }, singleLine = true)
        LazyColumn(Modifier.weight(1f, false)) { items(matches) { Column(Modifier.padding(vertical = 6.dp)) {
            TypedVariable(it); Text(if(it.readOnly) "只读" else "可写", style = MaterialTheme.typography.labelSmall)
        } } }
    } }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}

internal fun newNode(kind: WorkflowKind, refs: List<WorkflowReferenceChoice>, profiles: List<ApiProfile>): WorkflowNode {
    fun reference(type: WorkflowType) = refs.firstOrNull { it.type == type }?.let { WorkflowExpression.Ref(it.ref) } ?: WorkflowExpression.Empty(type)
    fun target(type: WorkflowType) = refs.firstOrNull { !it.readOnly && it.type == type }?.ref
    val items = refs.firstOrNull { it.ref == WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles")) } ?: refs.firstOrNull { it.type.kind == WorkflowDataKind.LIST }
    val element = items?.type?.element ?: WorkflowType.TEXT
    val variable = WorkflowVariable(if(kind == WorkflowKind.EACH && element == WorkflowSystem.pageType) WorkflowSystem.PAGE else UUID.randomUUID().toString(),
        if(kind in setOf(WorkflowKind.EACH, WorkflowKind.API_STREAM)) (if(element == WorkflowSystem.pageType && kind == WorkflowKind.EACH) "本页" else "当前条目") else "变量", if(kind == WorkflowKind.EACH) element else if(kind == WorkflowKind.API_STREAM) WorkflowType.TRANSLATION else WorkflowType.TEXT)
    return WorkflowNode(UUID.randomUUID().toString(), kind, variable = if(kind in setOf(WorkflowKind.DECLARE, WorkflowKind.EACH, WorkflowKind.API_STREAM)) variable else null,
        resultType = if(kind == WorkflowKind.API_STREAM) WorkflowType.list(WorkflowType.TRANSLATION) else WorkflowType.TEXT,
        target = when(kind) { WorkflowKind.SEG -> target(WorkflowType.list(WorkflowType.BUBBLE)); WorkflowKind.MESSAGE -> target(WorkflowType.CONTEXT); WorkflowKind.APPLY_TRANSLATIONS, WorkflowKind.EACH -> null; else -> target(WorkflowType.TEXT) },
        inputs = when(kind) {
            WorkflowKind.SEG -> mapOf("image" to reference(WorkflowType.IMAGE))
            WorkflowKind.APPLY_TRANSLATIONS -> mapOf("items" to (refs.firstOrNull { it.type == WorkflowType.TRANSLATION }?.let { WorkflowExpression.Ref(it.ref) } ?: reference(WorkflowType.list(WorkflowType.PAGE_RECORD))))
            WorkflowKind.APPLY_ORDER -> mapOf("items" to (refs.firstOrNull { it.type.kind == WorkflowDataKind.RECORD && it.type.fields["translation"] == WorkflowType.TEXT }?.let { WorkflowExpression.Ref(it.ref) } ?: reference(WorkflowType.list(WorkflowType.TRANSLATION))), "index" to (refs.firstOrNull { it.ref.variableId == WorkflowSystem.STREAM_INDEX }?.let { WorkflowExpression.Ref(it.ref) } ?: WorkflowExpression.Number(1.0)))
            WorkflowKind.OCR -> mapOf("image" to reference(WorkflowType.IMAGE), "language" to WorkflowSystem.ref(WorkflowSystem.SOURCE))
            WorkflowKind.TRANSLATE -> mapOf("text" to reference(WorkflowType.TEXT), "source" to WorkflowSystem.ref(WorkflowSystem.SOURCE), "target" to WorkflowSystem.ref(WorkflowSystem.TARGET))
            WorkflowKind.EACH -> mapOf("items" to (items?.let { WorkflowExpression.Ref(it.ref) } ?: WorkflowExpression.Empty(WorkflowType.list(WorkflowType.TEXT))))
            WorkflowKind.API, WorkflowKind.API_STREAM -> mapOf("profile" to WorkflowExpression.Text(profiles.firstOrNull { it.kind == ApiProfileKind.LLM }?.id.orEmpty()), "prompt" to WorkflowExpression.Template(""))
            WorkflowKind.MESSAGE -> mapOf("user" to WorkflowExpression.Template(""))
            WorkflowKind.MERGE_GLOSSARY -> mapOf("items" to reference(WorkflowType.list(WorkflowType.GLOSSARY_ENTRY)))
            WorkflowKind.REPLACE -> mapOf("text" to reference(WorkflowType.TEXT), "dictionary" to WorkflowSystem.ref(WorkflowSystem.GLOSSARY))
            WorkflowKind.IF -> mapOf("condition" to WorkflowExpression.Boolean(true))
            WorkflowKind.DECLARE -> emptyMap()
            else -> mapOf("value" to reference(WorkflowType.TEXT))
        })
}
