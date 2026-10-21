package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

class WorkflowBatchOneTest {
    private val context = WorkflowVariable("context", "上下文", WorkflowType.CONTEXT)
    private fun message(target: WorkflowRef = WorkflowRef(context.id)) = WorkflowNode("message", WorkflowKind.MESSAGE,
        target = target, inputs = mapOf("user" to WorkflowExpression.Text("remember")))
    private fun declare(variable: WorkflowVariable) = WorkflowNode("declare-${variable.id}", WorkflowKind.DECLARE, variable = variable)
    private fun withContext(mode: WorkflowMode): WorkflowProgram {
        var p = WorkflowEditing.insert(WorkflowTemplates.localMachine(), WorkflowPosition("pages", 0), declare(context))
        return WorkflowEditing.update(p, "bubbles") { it.copy(mode = mode, children = listOf(message()) + it.children) }
    }

    @Test fun createContextBeforeStepKeepsEditsAndSurvivesReopen() = runTest {
        val step = message().copy(target = null)
        val initial = WorkflowEditing.insert(WorkflowTemplates.localMachine(), WorkflowPosition("pages", 0), step)
        assertTrue(WorkflowValidator.validate(initial).issues.any { it.nodeId == step.id && it.message.contains("创建") })
        val created = WorkflowEditing.createTargetBefore(initial, step.copy(label = "edited"), WorkflowType.CONTEXT)
        val restored = WorkflowFileCodec.decode(WorkflowFileCodec.encode(TranslationWorkflow.LOCAL_MACHINE.copy(program = created))).program
        assertEquals(created, restored)
        val rows = WorkflowEditing.rows(restored, WorkflowPosition("pages", 0))
        assertEquals(WorkflowKind.DECLARE, rows[0].kind)
        assertEquals(rows[0].variable!!.id, rows[1].target!!.variableId)
        assertEquals("edited", rows[1].label)
        val host = Host(rows[0].variable!!.id)
        WorkflowRuntime().execute(restored, host)
        assertEquals(1, host.contextSize)
    }

    @Test fun syncOuterContextIsSelectableAndAccumulatesInInputOrder() = runTest {
        val program = withContext(WorkflowMode.SYNC)
        val scope = WorkflowValidator.validate(program).scopes.getValue("message")
        assertNull(WorkflowEditing.contextWriteIssue(WorkflowRef(context.id), scope))
        val host = Host(context.id)
        WorkflowRuntime().execute(WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(program)), host)
        assertEquals(2, host.contextSize)
    }

    @Test fun asyncOuterContextExplainsRestrictionAndCannotRun() = runTest {
        val program = withContext(WorkflowMode.ASYNC)
        val reason = WorkflowEditing.blockedContextTargets(program, "message").single().reason
        assertTrue(reason.contains("异步")); assertTrue(reason.contains("同步"))
        assertTrue(WorkflowValidator.validate(program).issues.any { it.nodeId == "message" && it.message == reason })
        assertFailsWith<IllegalArgumentException> { WorkflowRuntime().execute(program, Host()) }
    }

    @Test fun branchContextCreationDoesNotChangeAsyncModeOrOtherBranches() {
        val original = withContext(WorkflowMode.ASYNC)
        val changed = WorkflowEditing.createTargetBefore(original, message(), WorkflowType.CONTEXT)
        val loop = changed.allNodes().single { it.id == "bubbles" }
        assertEquals(WorkflowMode.ASYNC, loop.mode)
        assertEquals(WorkflowKind.DECLARE, loop.children[0].kind)
        assertEquals(loop.children[0].variable!!.id, loop.children[1].target!!.variableId)
        assertTrue(WorkflowValidator.validate(changed).valid)
        assertEquals("上下文2", loop.children[0].variable!!.name)
    }

    @Test fun laterDeclarationAndOtherBranchHaveDistinctReasons() {
        var program = WorkflowEditing.insert(WorkflowTemplates.localMachine(), WorkflowPosition("pages", 0), message())
        program = WorkflowEditing.insert(program, WorkflowPosition("pages", 1), declare(context))
        assertTrue(WorkflowEditing.blockedContextTargets(program, "message").single().reason.contains("之后"))
        program = WorkflowEditing.remove(program, "declare-context")
        program = WorkflowEditing.insert(program, WorkflowPosition("bubbles", 0), declare(context))
        assertTrue(WorkflowEditing.blockedContextTargets(program, "message").single().reason.contains("其他作用域"))
    }

    @Test fun contextIteratorIsRejectedByPickerValidatorAndRuntime() = runTest {
        val item = context.copy(id = "context-item")
        val loop = WorkflowNode("contexts", WorkflowKind.EACH, mode = WorkflowMode.SYNC, variable = item,
            inputs = mapOf("items" to WorkflowExpression.Empty(WorkflowType.list(WorkflowType.CONTEXT))),
            children = listOf(message(WorkflowRef(item.id))))
        val program = WorkflowEditing.insert(WorkflowTemplates.localMachine(), WorkflowPosition("pages", 0), loop)
        assertTrue(WorkflowEditing.blockedContextTargets(program, "message").any { it.reason.contains("循环条目") })
        assertTrue(WorkflowValidator.validate(program).issues.any { it.nodeId == "message" && it.message.contains("循环条目") })
        assertFailsWith<IllegalArgumentException> { WorkflowRuntime().execute(program, Host()) }
    }

    @Test fun sameTypeListMergeReachesAsyncParentWithoutLosingItems() = runTest {
        val list = WorkflowVariable("merged", "合并结果", WorkflowType.list(WorkflowType.BUBBLE))
        var program = WorkflowEditing.insert(WorkflowTemplates.localMachine(), WorkflowPosition("pages", 0), declare(list))
        program = WorkflowEditing.insert(program, WorkflowPosition("bubbles", 0), WorkflowNode("merge", WorkflowKind.MERGE_LIST,
            target = WorkflowRef(list.id), inputs = mapOf("value" to WorkflowSystem.ref(WorkflowSystem.PAGE, "bubbles"))))
        val restored = WorkflowFileCodec.decode(WorkflowFileCodec.encode(TranslationWorkflow.LOCAL_MACHINE.copy(program = program))).program
        val scope = WorkflowValidator.validate(restored).scopes.getValue("merge")
        assertTrue(WorkflowEditing.writeTargets(scope, append = true).any { it.ref == WorkflowRef(list.id) })
        val host = Host(listId = list.id)
        WorkflowRuntime().execute(restored, host)
        assertEquals(4, host.listSize)
        val wrong = WorkflowEditing.update(restored, "merge") { it.copy(inputs = mapOf("value" to WorkflowExpression.Empty(WorkflowType.list(WorkflowType.TEXT)))) }
        assertFalse(WorkflowValidator.validate(wrong).valid)
        assertFailsWith<IllegalArgumentException> { WorkflowRuntime().execute(wrong, Host()) }
    }

    @Test fun appendDoesNotMakeSystemListsOrNumericItemTargetsWritable() {
        val program = WorkflowTemplates.blank()
        val scope = WorkflowEditing.scope(program, WorkflowPosition("manga", 0))
        assertTrue(WorkflowEditing.references(scope).any { it.ref.variableId == WorkflowSystem.ALL_PAGES })
        assertTrue(WorkflowEditing.writeTargets(scope, append = true).none { it.ref.variableId == WorkflowSystem.ALL_PAGES })
        val nested = WorkflowAvailableVariable(WorkflowVariable("nested", "嵌套列表", WorkflowType.list(WorkflowType.list(WorkflowType.TEXT))), readOnly = true)
        val targets = WorkflowEditing.writeTargets(listOf(nested), append = true)
        assertTrue(targets.any { it.ref == WorkflowRef("nested") })
        assertTrue(targets.none { it.ref.path.any { key -> key.toIntOrNull() != null } })
    }

    @Test fun oldTextMergeMigratesForFilesAndQueueSnapshotsWithSameResult() = runTest {
        val text = WorkflowVariable("joined", "文本", WorkflowType.TEXT)
        var legacy = WorkflowEditing.insert(WorkflowTemplates.localMachine(), WorkflowPosition("pages", 0), declare(text))
        legacy = WorkflowEditing.insert(legacy, WorkflowPosition("pages", 1), WorkflowNode("merge", WorkflowKind.MERGE_LIST,
            target = WorkflowRef(text.id), inputs = mapOf("value" to WorkflowExpression.Text("preserved"))))
        assertFalse(WorkflowValidator.validate(legacy).valid)
        val snapshot = WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(legacy))
        val file = WorkflowFileCodec.decode(WorkflowFileCodec.encode(TranslationWorkflow.LOCAL_MACHINE.copy(program = legacy))).program
        for (restored in listOf(snapshot, file)) {
            assertEquals(WorkflowKind.APPEND, restored.allNodes().single { it.id == "merge" }.kind)
            val host = Host(textId = text.id)
            WorkflowRuntime().execute(restored, host)
            assertEquals("preserved", host.joined)
        }
    }

    @Test fun invalidLiteralOrderIsRejectedBeforeAnyRequest() = runTest {
        for (index in listOf(0.0, -1.0, 1.5, Double.POSITIVE_INFINITY, Int.MAX_VALUE.toDouble() + 1)) {
            val node = WorkflowNode("order", WorkflowKind.APPLY_ORDER, inputs = mapOf(
                "items" to WorkflowExpression.Empty(WorkflowType.list(WorkflowType.TRANSLATION)), "index" to WorkflowExpression.Number(index)))
            val program = WorkflowEditing.insert(WorkflowTemplates.localMachine(), WorkflowPosition("pages", 0), node)
            assertTrue(WorkflowValidator.validate(program).issues.any { it.nodeId == "order" && it.message.contains("整数") })
            assertFailsWith<IllegalArgumentException> { WorkflowRuntime().execute(program, Host()) }
        }
    }

    private class Host(val contextId: String? = null, val listId: String? = null, val textId: String? = null) : WorkflowRuntimeHost {
        override val manga = record("id" to WorkflowValue.Text("m"), "name" to WorkflowValue.Text("fixture"))
        override val sourceLanguage = "en"; override val targetLanguage = "zh-Hans"; override val style = ""
        var contextSize = 0; var listSize = 0; var joined = ""
        override suspend fun chapters() = listOf(record("id" to WorkflowValue.Text("c"), "name" to WorkflowValue.Text("c"),
            "index" to WorkflowValue.Number(1.0), "records" to WorkflowValue.ListValue(emptyList())))
        override suspend fun pages(chapter: WorkflowValue.Record) = listOf(record("id" to WorkflowValue.Text("p"), "name" to WorkflowValue.Text("p"),
            "number" to WorkflowValue.Number(1.0), "image" to WorkflowValue.Image("p"),
            "bubbles" to WorkflowValue.ListValue(emptyList()), "records" to WorkflowValue.ListValue(emptyList())))
        override suspend fun glossary() = emptyMap<String, String>()
        override suspend fun mergeGlossary(entries: Map<String, String>) = Unit
        override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue = when (kind) {
            WorkflowKind.SEG -> WorkflowValue.ListValue((1..2).map { index -> record("id" to WorkflowValue.Text("b$index"), "index" to WorkflowValue.Number(index.toDouble()),
                "source" to WorkflowValue.Text(""), "translation" to WorkflowValue.Text(""), "image" to WorkflowValue.Image("b$index"),
                "confidence" to WorkflowValue.Number(1.0), "kind" to WorkflowValue.Text("BUBBLE")) })
            WorkflowKind.OCR, WorkflowKind.TRANSLATE -> WorkflowValue.Text("text")
            else -> error("Unexpected request: $kind")
        }
        override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue = error("No paid requests")
        override suspend fun publishPage(frame: WorkflowFrame) {
            contextId?.let { contextSize = (frame.read(WorkflowRef(it)) as WorkflowValue.Context).messages.size }
            listId?.let { listSize = (frame.read(WorkflowRef(it)) as WorkflowValue.ListValue).items.size }
            textId?.let { joined = (frame.read(WorkflowRef(it)) as WorkflowValue.Text).value }
        }
    }
    companion object { private fun record(vararg fields: Pair<String, WorkflowValue>) = WorkflowValue.Record(fields.toMap()) }
}
