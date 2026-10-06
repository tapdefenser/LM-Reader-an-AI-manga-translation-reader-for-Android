package com.lmreader.ui.workflow

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.lmreader.core.model.*
import com.lmreader.core.workflow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkflowPrimitiveInputIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun assignmentAndAppendOfferNumbersBeforeSelectingAnOutputAndPreserveDecimalTyping() {
        val number = WorkflowVariable("number", "结果数字", WorkflowType.NUMBER)
        val list = WorkflowVariable("list", "结果列表", WorkflowType.list(WorkflowType.NUMBER))
        val scope = (0..20).map { WorkflowAvailableVariable(WorkflowVariable("v$it", "候选$it", WorkflowType.NUMBER)) } +
            listOf(WorkflowAvailableVariable(number), WorkflowAvailableVariable(list))
        var current by mutableStateOf(WorkflowNode("set", WorkflowKind.SET))
        var saved: WorkflowNode? = null
        compose.setContent { MaterialTheme { key(current.id) {
            WorkflowNodeEditor(current, scope, scope, emptyList(), dismiss = {}, save = { saved = it })
        } } }
        for (kind in listOf(WorkflowKind.SET, WorkflowKind.APPEND)) {
            compose.runOnIdle { current = WorkflowNode(kind.name, kind); saved = null }
            compose.onNodeWithText("<数字>值").performScrollTo().performClick()
            val label = if(kind == WorkflowKind.SET) "赋值" else "新增值"
            val field = compose.onNodeWithTag("workflow-literal:$label")
            field.performTextReplacement("-1")
            field.performTextInput(".")
            field.assertTextEquals("-1.")
            field.performTextInput("25")
            field.assertTextEquals("-1.25")
            val target = if(kind == WorkflowKind.SET) number else list
            compose.onNodeWithTag("workflow-search:输出到").performScrollTo().performTextInput(target.name)
            compose.onNode(hasText("${WorkflowLabels.type(target.type)} · ${target.name}") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithText("完成").performClick()
            compose.runOnIdle {
                assertEquals(WorkflowExpression.Number(-1.25), saved!!.inputs["value"])
                assertEquals(WorkflowRef(target.id), saved!!.target)
            }
        }
    }

    @Test fun assignmentAndAppendOfferLiteralTextWithManyVariableCandidates() {
        val text = WorkflowVariable("text", "结果文本", WorkflowType.TEXT)
        val scope = (0..20).map { WorkflowAvailableVariable(WorkflowVariable("v$it", "候选$it", WorkflowType.TEXT)) } + WorkflowAvailableVariable(text)
        var current by mutableStateOf(WorkflowNode("set", WorkflowKind.SET, target = WorkflowRef(text.id)))
        var saved: WorkflowNode? = null
        compose.setContent { MaterialTheme { key(current.id) {
            WorkflowNodeEditor(current, scope, scope, emptyList(), dismiss = {}, save = { saved = it })
        } } }
        for (kind in listOf(WorkflowKind.SET, WorkflowKind.APPEND)) {
            compose.runOnIdle { current = WorkflowNode(kind.name, kind, target = WorkflowRef(text.id)); saved = null }
            compose.onNodeWithText("<文本>值").assertIsDisplayed().performClick()
            val label = if(kind == WorkflowKind.SET) "赋值" else "新增值"
            compose.onNodeWithTag("workflow-literal:$label").performTextInput("猫爪 123\n\"字符串\"")
            compose.onNodeWithText("完成").performClick()
            compose.runOnIdle { assertEquals(WorkflowExpression.Text("猫爪 123\n\"字符串\""), saved!!.inputs["value"]) }
        }
    }

    @Test fun listCountIsSelectableAsANumberInputAndAbsentFromOutputChoices() {
        val list = WorkflowVariable("list", "图片列表", WorkflowType.list(WorkflowType.IMAGE))
        val number = WorkflowVariable("number", "数量", WorkflowType.NUMBER)
        val scope = listOf(WorkflowAvailableVariable(list), WorkflowAvailableVariable(number))
        var saved: WorkflowNode? = null
        compose.setContent { MaterialTheme {
            WorkflowNodeEditor(WorkflowNode("set", WorkflowKind.SET, target = WorkflowRef(number.id)), scope, scope, emptyList(),
                dismiss = {}, save = { saved = it })
        } }
        compose.onNodeWithTag("workflow-search:赋值").performTextInput("项数")
        compose.onNode(hasText("<数字> · 图片列表 · 项数") and hasClickAction()).performClick()
        compose.onNodeWithTag("workflow-search:输出到").performScrollTo().performTextInput("项数")
        compose.onAllNodes(hasText("<数字> · 图片列表 · 项数") and hasClickAction() and
            hasAnyAncestor(hasTestTag("workflow-reference:输出到"))).assertCountEquals(0)
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { assertEquals(WorkflowSystem.ref(list.id, "count"), saved!!.inputs["value"]) }
    }
}
