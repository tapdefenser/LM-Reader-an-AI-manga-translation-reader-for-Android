package com.lmreader.ui.workflow

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.lmreader.ui.i18n.Text
import com.lmreader.ui.i18n.UiTextTranslations
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.lmreader.core.model.*
import com.lmreader.core.workflow.*

internal fun typedLabel(choice: WorkflowReferenceChoice) = "${WorkflowLabels.type(choice.type)} · ${choice.label}"

/** One summary line entry for a variable the row declares or writes, deduplicated by type and name. */
internal data class TypedChoice(val name: String, val type: WorkflowType) {
    constructor(variable: WorkflowVariable) : this(variable.name, variable.type)
    constructor(choice: WorkflowReferenceChoice) : this(choice.label, choice.type)
}

@Composable
internal fun WorkflowOptionSearch(label: String, query: String, changed: (String) -> Unit) {
    OutlinedTextField(query, changed, label = { Text("搜索变量、字段或类型") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("workflow-search:$label"))
}

@Composable
internal fun workflowOptionMatcher(query: String): (String) -> Boolean {
    val context = LocalContext.current
    val keyword = query.trim()
    return { label -> keyword.isEmpty() || label.contains(keyword, true) || UiTextTranslations.translate(context, label).contains(keyword, true) }
}

@Composable
internal fun variableColor(type: WorkflowType): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return when(type.kind) {
        WorkflowDataKind.TEXT -> if(dark) Color(0xFF92C5FF) else Color(0xFF165CA8)
        WorkflowDataKind.NUMBER -> if(dark) Color(0xFFFFBF82) else Color(0xFF934700)
        WorkflowDataKind.BOOLEAN -> if(dark) Color(0xFFFF99AC) else Color(0xFFA32B48)
        WorkflowDataKind.IMAGE -> if(dark) Color(0xFFB9AAFF) else Color(0xFF6742A8)
        WorkflowDataKind.BUBBLE -> if(dark) Color(0xFFF0A5FA) else Color(0xFF8C368D)
        WorkflowDataKind.CONTEXT -> if(dark) Color(0xFF9ADDD4) else Color(0xFF006C61)
        WorkflowDataKind.LIST -> if(dark) Color(0xFFA6D98F) else Color(0xFF396B22)
        WorkflowDataKind.RECORD -> if(dark) Color(0xFFE4CE89) else Color(0xFF79600E)
        WorkflowDataKind.DICTIONARY -> if(dark) Color(0xFF8DDBEA) else Color(0xFF006A7C)
    }
}

@Composable
internal fun TypedVariable(choice: WorkflowReferenceChoice) {
    Text(typedLabel(choice), color = variableColor(choice.type), style = MaterialTheme.typography.labelSmall)
}

internal fun expressionLabel(expression: WorkflowExpression, refs: List<WorkflowReferenceChoice>): String = when(expression) {
    is WorkflowExpression.Ref -> refs.firstOrNull { it.ref == expression.value }?.let(::typedLabel) ?: "引用 · 不可用变量"
    is WorkflowExpression.Text -> "${WorkflowLabels.type(WorkflowType.TEXT)} · ${expression.value.take(40).ifBlank { "空文本" }}"
    is WorkflowExpression.Template -> "${WorkflowLabels.type(WorkflowType.TEXT)} · 提示词模板"
    is WorkflowExpression.Number -> "${WorkflowLabels.type(WorkflowType.NUMBER)} · ${expression.value}"
    is WorkflowExpression.Boolean -> "${WorkflowLabels.type(WorkflowType.BOOLEAN)} · ${expression.value}"
    is WorkflowExpression.Empty -> "${WorkflowLabels.type(expression.type)} · 初始值"
    is WorkflowExpression.Record -> "${WorkflowLabels.type(WorkflowType(WorkflowDataKind.RECORD))} · ${expression.fields.keys.joinToString()}"
}
