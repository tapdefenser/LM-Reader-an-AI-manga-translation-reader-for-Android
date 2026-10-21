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

class WorkflowContextOutputIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val contextLabel = "写入上下文 · <上下文>"

    @Test fun createContextSearchAndReopenKeepsSelectedWritableOutput() {
        var program by mutableStateOf(WorkflowEditing.insert(WorkflowTemplates.blank(), WorkflowPosition("pages", 1), message()))
        var open by mutableStateOf(true)
        var saves = 0
        compose.setContent { MaterialTheme {
            if (open) editor(program, "message", create = { program = it }, save = { node ->
                program = WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(WorkflowEditing.update(program, node.id) { node }))
                saves++; open = false
            })
        } }
        compose.onNodeWithText("没有可写上下文。请在此步骤之前创建，或使用下方创建按钮。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("workflow-create-target").performScrollTo().performClick()
        val target = program.allNodes().single { it.id == "message" }.target!!
        compose.runOnIdle { assertTrue(WorkflowValidator.validate(program).valid) }
        compose.onNodeWithTag("workflow-search:$contextLabel").performScrollTo().performTextInput("不存在的搜索")
        compose.onNodeWithText("搜索无匹配变量；请清空或修改搜索。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("workflow-search:$contextLabel").performTextClearance()
        outputChoice("<上下文> · 上下文", contextLabel).performScrollTo().assertIsSelected().performClick()
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { assertEquals(1, saves); open = true }
        outputChoice("<上下文> · 上下文", contextLabel).performScrollTo().assertIsSelected()
        compose.runOnIdle { assertEquals(target, program.allNodes().single { it.id == "message" }.target) }
    }

    @Test fun asyncOuterContextExplainsRestrictionAndCreatesLocalContextWithoutChangingMode() {
        val outer = WorkflowVariable("outer", "外层上下文", WorkflowType.CONTEXT)
        var initial = WorkflowEditing.insert(WorkflowTemplates.localMachine(), WorkflowPosition("pages", 0), WorkflowNode("declare-outer", WorkflowKind.DECLARE, variable = outer))
        initial = WorkflowEditing.insert(initial, WorkflowPosition("bubbles", 0), message().copy(target = WorkflowRef(outer.id)))
        var program by mutableStateOf(initial)
        compose.setContent { MaterialTheme { editor(program, "message", create = { program = it }, save = {}) } }
        compose.onAllNodesWithText("异步分支的外层上下文只读", substring = true).onFirst().performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasText("<上下文> · 外层上下文") and hasClickAction() and
            hasAnyAncestor(hasTestTag("workflow-reference:$contextLabel"))).assertCountEquals(0)
        compose.onNodeWithTag("workflow-create-target").performScrollTo().performClick()
        outputChoice("<上下文> · 上下文", contextLabel).performScrollTo().assertIsSelected()
        compose.runOnIdle {
            val loop = program.allNodes().single { it.id == "bubbles" }
            assertEquals(WorkflowMode.ASYNC, loop.mode)
            assertEquals(WorkflowKind.DECLARE, loop.children[0].kind)
            assertEquals(loop.children[0].variable!!.id, loop.children[1].target!!.variableId)
            assertTrue(WorkflowValidator.validate(program).valid)
        }
    }

    @Test fun mergeWithNoListOffersCreationAndKeepsListTypeAfterReopen() {
        var program by mutableStateOf(WorkflowEditing.insert(WorkflowTemplates.blank(), WorkflowPosition("manga", 0),
            newNode(WorkflowKind.MERGE_LIST, emptyList(), emptyList()).copy(id = "merge")))
        var saved: WorkflowProgram? = null
        compose.setContent { MaterialTheme { editor(program, "merge", create = { program = it }, save = { node ->
            saved = WorkflowFileCodec.decode(WorkflowFileCodec.encode(TranslationWorkflow.LOCAL_MACHINE.copy(
                program = WorkflowEditing.update(program, node.id) { node }))).program
        }) } }
        compose.onNodeWithText("没有可合并的目标列表。请先创建列表变量。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("workflow-create-target").performScrollTo().performClick()
        outputChoice("<列表<文本>> · 合并结果", "输出到").performScrollTo().assertIsSelected()
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle {
            val node = saved!!.allNodes().single { it.id == "merge" }
            assertEquals(WorkflowType.list(WorkflowType.TEXT), node.resultType)
            assertEquals(WorkflowExpression.Empty(node.resultType), node.inputs["value"])
            assertTrue(WorkflowValidator.validate(saved!!).valid)
        }
    }

    @Composable private fun editor(program: WorkflowProgram, id: String, create: (WorkflowProgram) -> Unit, save: (WorkflowNode) -> Unit) {
        val node = program.allNodes().single { it.id == id }
        val validation = WorkflowValidator.validate(program)
        WorkflowNodeEditor(node, validation.scopes[id].orEmpty(), WorkflowEditing.outputs(program, id, validation), emptyList(), dismiss = {},
            blockedContexts = if (node.kind == WorkflowKind.MESSAGE) WorkflowEditing.blockedContextTargets(program, id) else emptyList(),
            createTarget = { edited, type -> create(WorkflowEditing.createTargetBefore(program, edited, type)) }, save = save)
    }

    private fun outputChoice(label: String, section: String) = compose.onNode(hasText(label) and hasClickAction() and
        hasAnyAncestor(hasTestTag("workflow-reference:$section")))
    private fun message() = WorkflowNode("message", WorkflowKind.MESSAGE, inputs = mapOf("user" to WorkflowExpression.Text("remember")))
}
