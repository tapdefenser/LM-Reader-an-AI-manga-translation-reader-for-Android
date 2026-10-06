package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

class WorkflowListCountTest {
    private val host = object : WorkflowRuntimeHost {
        override val manga = WorkflowValue.Record(mapOf("id" to WorkflowValue.Text("manga"), "name" to WorkflowValue.Text("漫画")))
        override val sourceLanguage = "ja"
        override val targetLanguage = "zh"
        override val style = ""
        override suspend fun chapters() = emptyList<WorkflowValue.Record>()
        override suspend fun pages(chapter: WorkflowValue.Record) = emptyList<WorkflowValue.Record>()
        override suspend fun glossary() = emptyMap<String, String>()
        override suspend fun mergeGlossary(entries: Map<String, String>) = Unit
        override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue = error("unused")
        override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue = error("unused")
    }

    @Test fun `all list types expose a read only count including nested and system lists`() {
        val lists = listOf(WorkflowType.TEXT, WorkflowType.NUMBER, WorkflowType.IMAGE, WorkflowType.BUBBLE,
            WorkflowType.list(WorkflowType.TEXT)).mapIndexed { index, element ->
            WorkflowAvailableVariable(WorkflowVariable("list-$index", "列表$index", WorkflowType.list(element)))
        }
        val record = WorkflowAvailableVariable(WorkflowVariable("record", "记录", WorkflowType(WorkflowDataKind.RECORD,
            fields = mapOf("items" to WorkflowType.list(WorkflowType.NUMBER), "count" to WorkflowType.NUMBER))))
        val scope = lists + record + WorkflowAvailableVariable(WorkflowVariable(WorkflowSystem.PAGE, "本页", WorkflowSystem.pageType))
        val refs = WorkflowEditing.references(scope)
        val expected = lists.map { WorkflowRef(it.variable.id, listOf("count")) } + listOf(
            WorkflowRef("list-4", listOf("0", "count")), WorkflowRef("record", listOf("items", "count")),
            WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles", "count")), WorkflowRef(WorkflowSystem.PAGE, listOf("records", "count")))
        expected.forEach { ref ->
            val choice = refs.single { it.ref == ref }
            assertEquals(WorkflowType.NUMBER, choice.type)
            assertTrue(choice.readOnly)
            assertTrue(choice.label.endsWith(" · 项数"))
        }
        for (append in listOf(false, true)) {
            val targets = WorkflowEditing.writeTargets(scope, append)
            assertTrue(targets.none { it.ref in expected })
            assertTrue(targets.any { it.ref == WorkflowRef("record", listOf("count")) }, "ordinary record fields remain writable")
        }
    }

    @Test fun `count reads empty and current lists and nested lists without requiring a first item`() = runTest {
        val frame = WorkflowFrame(host)
        val type = WorkflowType.list(WorkflowType.TEXT)
        frame.define("items", WorkflowFrame.empty(type), type)
        val count = WorkflowRef("items", listOf("count"))
        assertEquals(WorkflowValue.Number(0.0), frame.read(count))
        frame.append(WorkflowRef("items"), WorkflowValue.Text("猫爪"), merge = false)
        assertEquals(WorkflowValue.Number(1.0), frame.read(count))
        frame.append(WorkflowRef("items"), WorkflowValue.ListValue(listOf(WorkflowValue.Text("第二项"))), merge = true)
        assertEquals(WorkflowValue.Number(2.0), frame.read(count))
        assertEquals(WorkflowValue.Text("项数=2.0"), frame.evaluate(WorkflowExpression.Template("项数=\${数量}", mapOf("数量" to count))))
        frame.define("nested", WorkflowValue.ListValue(listOf(WorkflowValue.ListValue(emptyList()))), WorkflowType.list(type))
        assertEquals(WorkflowValue.Number(0.0), frame.read(WorkflowRef("nested", listOf("0", "count"))))
        frame.define("record", WorkflowValue.Record(mapOf("count" to WorkflowValue.Number(3.0))))
        frame.write(WorkflowRef("record", listOf("count")), WorkflowValue.Number(4.0))
        assertEquals(WorkflowValue.Number(4.0), frame.read(WorkflowRef("record", listOf("count"))))
        assertContains(assertFailsWith<IllegalArgumentException> { frame.write(count, WorkflowValue.Number(9.0)) }.message.orEmpty(), "只读")
    }

    @Test fun `count references and primitive assignment and append survive export and execute`() = runTest {
        val numbers = WorkflowVariable("numbers", "数字列表", WorkflowType.list(WorkflowType.NUMBER))
        val texts = WorkflowVariable("texts", "文本列表", WorkflowType.list(WorkflowType.TEXT))
        val size = WorkflowVariable("size", "数量", WorkflowType.NUMBER)
        val text = WorkflowVariable("text", "说明", WorkflowType.TEXT)
        val count = WorkflowRef(numbers.id, listOf("count"))
        fun declaration(variable: WorkflowVariable) = WorkflowNode("new-${variable.id}", WorkflowKind.DECLARE, variable = variable)
        fun row(id: String, kind: WorkflowKind, target: WorkflowRef, value: WorkflowExpression) =
            WorkflowNode(id, kind, target = target, inputs = mapOf("value" to value))
        val rows = listOf(declaration(numbers), declaration(texts), declaration(size), declaration(text),
            row("set-number", WorkflowKind.SET, WorkflowRef(size.id), WorkflowExpression.Number(-1.25)),
            row("set-text", WorkflowKind.SET, WorkflowRef(text.id), WorkflowExpression.Text("猫爪\n\"字符串\"")),
            row("append-number", WorkflowKind.APPEND, WorkflowRef(numbers.id), WorkflowExpression.Number(-2.5)),
            row("append-text", WorkflowKind.APPEND, WorkflowRef(texts.id), WorkflowExpression.Text("文字")),
            row("append-to-text", WorkflowKind.APPEND, WorkflowRef(text.id), WorkflowExpression.Text("后缀")),
            row("set-count", WorkflowKind.SET, WorkflowRef(size.id), WorkflowExpression.Ref(count)))
        val program = WorkflowEditing.update(WorkflowTemplates.blank(), "manga") { it.copy(children = rows + it.children) }
        val restored = WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(program))
        assertEquals(program, restored)
        assertTrue(WorkflowValidator.validate(restored).valid)
        assertContains(WorkflowSource.render(restored), "数字列表-项数<数字>")
        var completedFrame: WorkflowFrame? = null
        val observingHost = object : WorkflowRuntimeHost by host {
            override fun step(node: WorkflowNode, frame: WorkflowFrame) { if(node.kind == WorkflowKind.CHAPTERS) completedFrame = frame }
        }
        WorkflowRuntime().execute(restored, observingHost)
        val frame = assertNotNull(completedFrame)
        assertEquals(WorkflowValue.Number(1.0), frame.read(WorkflowRef(size.id)))
        assertEquals(WorkflowValue.ListValue(listOf(WorkflowValue.Number(-2.5))), frame.read(WorkflowRef(numbers.id)))
        assertEquals(WorkflowValue.ListValue(listOf(WorkflowValue.Text("文字"))), frame.read(WorkflowRef(texts.id)))
        assertEquals(WorkflowValue.Text("猫爪\n\"字符串\"后缀"), frame.read(WorkflowRef(text.id)))
        for (kind in listOf(WorkflowKind.SET, WorkflowKind.APPEND)) {
            val invalid = WorkflowEditing.update(restored, "set-count") { it.copy(kind = kind, target = count, inputs = mapOf("value" to WorkflowExpression.Number(5.0))) }
            assertTrue(WorkflowValidator.validate(invalid).issues.any { it.nodeId == "set-count" && it.message == "列表项数只读" })
        }
    }
}
