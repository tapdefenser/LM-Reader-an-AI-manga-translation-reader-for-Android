package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Declared variables must stay visible and writable inside nested modules, and async rows must bound their own parallelism. */
class WorkflowScopeTest {
    private val images = WorkflowVariable("images", "图片列表", WorkflowType.list(WorkflowType.IMAGE))

    @Test fun `declared list is visible to the following loop and its nested rows`() {
        val captions = WorkflowVariable("captions", "说明", WorkflowType.TEXT)
        var program = WorkflowTemplates.localMachine()
        program = WorkflowEditing.insert(program, WorkflowPosition("pages", 0), WorkflowNode("declare-images", WorkflowKind.DECLARE, variable = images))
        program = WorkflowEditing.insert(program, WorkflowPosition("pages", 1), WorkflowNode("loop", WorkflowKind.EACH, variable = WorkflowVariable("item", "当前图片", WorkflowType.IMAGE),
            inputs = mapOf("items" to WorkflowExpression.Ref(WorkflowRef(images.id))), children = listOf(
                WorkflowNode("declare-caption", WorkflowKind.DECLARE, variable = captions),
                WorkflowNode("use", WorkflowKind.API, target = WorkflowRef(captions.id), resultType = WorkflowType.TEXT,
                    inputs = mapOf("profile" to WorkflowExpression.Text("p"), "prompt" to WorkflowExpression.Text("x"),
                        "images" to WorkflowExpression.Ref(WorkflowRef(images.id, listOf("0"))))))))
        val validation = WorkflowValidator.validate(program)
        assertTrue(validation.valid, validation.issues.toString())
        val loopScope = WorkflowEditing.scope(program, WorkflowPosition("pages", 1))
        assertTrue(loopScope.any { it.variable.id == images.id }, "loop cannot see declared list")
        val childScope = WorkflowEditing.scope(program, WorkflowPosition("loop", 0))
        assertTrue(childScope.any { it.variable.id == images.id }, "nested row cannot see declared list")
        assertTrue(WorkflowEditing.references(childScope).any { it.ref == WorkflowRef(images.id) && it.type == WorkflowType.list(WorkflowType.IMAGE) })
        assertTrue(WorkflowEditing.references(loopScope).any { it.ref == WorkflowRef(images.id) && it.type == WorkflowType.list(WorkflowType.IMAGE) })
    }

    @Test fun `a row output scope shows its own declared variable with type and name`() {
        var program = WorkflowTemplates.localMachine()
        program = WorkflowEditing.insert(program, WorkflowPosition("pages", 0), WorkflowNode("declare-images", WorkflowKind.DECLARE, variable = images))
        val validation = WorkflowValidator.validate(program)
        val out = WorkflowEditing.outputs(program, "declare-images", validation)
        assertTrue(out.any { it.variable.id == images.id && it.variable.name == "图片列表" && it.variable.type == WorkflowType.list(WorkflowType.IMAGE) }, out.map { it.variable.name }.toString())
        assertTrue(out.none { it.iterator }, "a declaration is not an iteration variable")
        val listed = WorkflowEditing.references(out).first { it.ref == WorkflowRef(images.id) }
        assertEquals("<列表<图片>> · 图片列表", "${WorkflowLabels.type(listed.type)} · ${listed.label}")
    }

    @Test fun `a loop iterator declares itself without being writable`() {
        var program = WorkflowTemplates.localMachine()
        program = WorkflowEditing.insert(program, WorkflowPosition("pages", 0), WorkflowNode("loop", WorkflowKind.EACH,
            variable = WorkflowVariable("item", "当前项", WorkflowType.BUBBLE), inputs = mapOf("items" to WorkflowSystem.ref(WorkflowSystem.PAGE, "bubbles"))))
        val validation = WorkflowValidator.validate(program)
        val declared = WorkflowEditing.outputs(program, "loop", validation).single { it.variable.id == "item" }
        assertTrue(declared.iterator, "the loop entry is an iteration variable, so it cannot be a write target")
    }

    @Test fun `nested rows keep reading and writing the outer list`() {
        val nested = WorkflowVariable("nested", "内层列表", WorkflowType.list(WorkflowType.TEXT))
        val inner = WorkflowNode("nested-loop", WorkflowKind.EACH, mode = WorkflowMode.SYNC,
            variable = WorkflowVariable("nested-item", "当前文本", WorkflowType.TEXT), inputs = mapOf("items" to WorkflowExpression.Empty(WorkflowType.list(WorkflowType.TEXT))),
            children = listOf(WorkflowNode("append-image", WorkflowKind.APPEND, target = WorkflowRef(images.id),
                inputs = mapOf("value" to WorkflowSystem.ref(WorkflowSystem.PAGE, "image")))))
        var program = WorkflowTemplates.localMachine()
        program = WorkflowEditing.update(program, "bubbles") { it.copy(mode = WorkflowMode.SYNC) }
        program = WorkflowEditing.insert(program, WorkflowPosition("pages", 0), WorkflowNode("declare-images", WorkflowKind.DECLARE, variable = images))
        program = WorkflowEditing.insert(program, WorkflowPosition("pages", 1), inner)
        program = WorkflowEditing.update(program, "bubbles") { loop -> loop.copy(children = loop.children + WorkflowNode("nested-declare", WorkflowKind.DECLARE, variable = nested)) }
        val validation = WorkflowValidator.validate(program)
        assertTrue(validation.valid, validation.issues.toString())
        assertTrue(WorkflowEditing.outputs(program, "bubbles", validation).any { it.variable.id == images.id }, "outer list must stay writable inside a nested loop")
        val appendScope = WorkflowEditing.scope(program, WorkflowPosition("nested-loop", 0))
        assertTrue(WorkflowEditing.references(appendScope).any { it.ref == WorkflowRef(images.id) && !it.readOnly }, "the declared list must be a writable reference two levels down")
    }

    @Test fun `a declaration cannot read itself but the next row can`() {
        var program = WorkflowTemplates.localMachine()
        program = WorkflowEditing.insert(program, WorkflowPosition("pages", 0), WorkflowNode("declare-images", WorkflowKind.DECLARE, variable = images,
            inputs = mapOf("value" to WorkflowExpression.Ref(WorkflowRef(images.id)))))
        val broken = WorkflowValidator.validate(program)
        assertEquals(listOf("declare-images"), broken.issues.map { it.nodeId }.distinct())
        program = WorkflowEditing.update(program, "declare-images") { it.copy(inputs = emptyMap()) }
        assertTrue(WorkflowValidator.validate(program).valid)
    }

    @Test fun `async rows bound their own parallelism and keep the codec round trip`() = runTest {
        assertFailsWith<IllegalArgumentException> { WorkflowNode("bad", WorkflowKind.PAGES, parallelLimit = 0) }
        assertFailsWith<IllegalArgumentException> { WorkflowNode("bad", WorkflowKind.PAGES, parallelLimit = WorkflowNode.MAX_PARALLEL + 1) }
        var program = WorkflowTemplates.localMachine()
        for(id in listOf("pages", "bubbles")) program = WorkflowEditing.update(program, id) { it.copy(mode = WorkflowMode.ASYNC, parallelLimit = 1) }
        assertEquals(program, WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(program)))
        assertTrue(WorkflowSource.render(program).contains("每页(异步，最多并行 1)"))
        val serial = Host(); WorkflowRuntime(4).execute(program, serial)
        assertEquals(1, serial.peak.get())
        assertTrue(serial.saved.all { it.size == 3 })
    }

    @Test fun `an async row without a limit still uses the engine pool`() = runTest {
        var program = WorkflowTemplates.localMachine()
        for(id in listOf("pages", "bubbles")) program = WorkflowEditing.update(program, id) { it.copy(mode = WorkflowMode.ASYNC) }
        val host = Host(); WorkflowRuntime(4).execute(program, host)
        assertTrue(host.peak.get() > 1, "an unset limit must not serialize the engine pool")
    }

    @Test fun `append rows reach an outer list inside an async loop while other rows keep it read-only`() {
        val list = WorkflowVariable("pictures", "图片列表", WorkflowType.list(WorkflowType.IMAGE))
        var program = WorkflowEditing.insert(WorkflowTemplates.localMachine(), WorkflowPosition("pages", 0), WorkflowNode("declare-pictures", WorkflowKind.DECLARE, variable = list))
        program = WorkflowEditing.insert(program, WorkflowPosition("bubbles", 0), WorkflowNode("append-picture", WorkflowKind.APPEND,
            target = WorkflowRef(list.id), inputs = mapOf("value" to WorkflowSystem.ref(WorkflowSystem.BUBBLE, "image"))))
        val validation = WorkflowValidator.validate(program)
        assertTrue(validation.valid, validation.issues.toString())
        val scope = WorkflowEditing.outputs(program, "append-picture", validation)
        assertTrue(scope.any { it.variable.id == list.id && it.readOnly }, "the outer list stays read-only inside the async loop")
        val targets = WorkflowEditing.writeTargets(scope, append = true)
        assertTrue(targets.any { it.ref == WorkflowRef(list.id) }, "the += picker must offer the outer list")
        assertTrue(WorkflowEditing.writeTargets(scope).none { it.ref == WorkflowRef(list.id) }, "a full replacement must not offer a read-only parent")
        assertTrue(targets.none { it.ref.variableId == WorkflowSystem.BUBBLE }, "the loop iterator is never an assignment target")
    }

    private class Host : WorkflowRuntimeHost {
        val saved = mutableListOf<List<String>>(); val active = AtomicInteger(); val peak = AtomicInteger()
        override val manga = WorkflowValue.Record(mapOf("id" to WorkflowValue.Text("m"), "name" to WorkflowValue.Text("n")))
        override val sourceLanguage = "en"; override val targetLanguage = "zh-Hans"; override val style = ""
        override suspend fun chapters() = listOf(WorkflowValue.Record(mapOf("id" to WorkflowValue.Text("7"), "name" to WorkflowValue.Text("7"),
            "index" to WorkflowValue.Number(1.0), "records" to WorkflowValue.ListValue(emptyList()))))
        override suspend fun pages(chapter: WorkflowValue.Record) = (1..3).map { i -> page("7:$i") }
        override suspend fun glossary() = emptyMap<String, String>()
        override suspend fun mergeGlossary(entries: Map<String, String>) = Unit
        override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue = when(kind) {
            WorkflowKind.SEG -> WorkflowValue.ListValue((1..3).map { bubble("${frame.identity(WorkflowSystem.PAGE)}:$it", it) })
            WorkflowKind.OCR -> { val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
                try { delay(20); WorkflowValue.Text("原文") } finally { active.decrementAndGet() } }
            WorkflowKind.TRANSLATE -> WorkflowValue.Text("译文")
            else -> error("unused")
        }
        override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue = error("unused")
        override suspend fun publishPage(frame: WorkflowFrame) {
            saved += (frame.read(WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles"))) as WorkflowValue.ListValue).items.map { "row" }
        }
        private fun page(id: String) = WorkflowValue.Record(mapOf("id" to WorkflowValue.Text(id), "name" to WorkflowValue.Text(id),
            "number" to WorkflowValue.Number(1.0), "image" to WorkflowValue.Image(id),
            "bubbles" to WorkflowValue.ListValue(emptyList()), "records" to WorkflowValue.ListValue(emptyList())))
        private fun bubble(id: String, index: Int) = WorkflowValue.Record(mapOf("id" to WorkflowValue.Text(id), "index" to WorkflowValue.Number(index.toDouble()),
            "image" to WorkflowValue.Image(id), "source" to WorkflowValue.Text(""), "translation" to WorkflowValue.Text(""),
            "confidence" to WorkflowValue.Number(1.0), "kind" to WorkflowValue.Text("BUBBLE")))
    }
}
