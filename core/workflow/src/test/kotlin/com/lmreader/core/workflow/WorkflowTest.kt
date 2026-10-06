package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WorkflowTest {
    @Test fun `repeated seg replaces page bubbles and records even with a custom output and empty detection`() = runTest {
        val custom = WorkflowVariable("seg-result", "新气泡", WorkflowType.list(WorkflowType.BUBBLE))
        val oldRecord = WorkflowExpression.Record(mapOf("bubbleId" to WorkflowExpression.Text("old"), "source" to WorkflowExpression.Text("旧原文"),
            "translation" to WorkflowExpression.Text("旧译文"), "pageId" to WorkflowExpression.Text("old-page"), "pageNumber" to WorkflowExpression.Number(1.0)))
        val program = WorkflowEditing.update(WorkflowTemplates.blank(), "pages") { page -> page.copy(children = listOf(
            WorkflowNode("declare-seg", WorkflowKind.DECLARE, variable = custom),
            page.children.single().copy(target = WorkflowRef(custom.id)),
            WorkflowNode("old-translation", WorkflowKind.SET, target = WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles", "0", "translation")), inputs = mapOf("value" to WorkflowExpression.Text("旧译文"))),
            WorkflowNode("old-record", WorkflowKind.APPEND, target = WorkflowRef(WorkflowSystem.PAGE, listOf("records")), inputs = mapOf("value" to oldRecord)),
            page.children.single().copy(id = "seg-again", target = WorkflowRef(custom.id)))) }
        val calls = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val snapshots = mutableListOf<Pair<String, WorkflowValue.ListValue>>()
        val host = object : Host(chapterIds = listOf("7", "39"), pageCount = 1) {
            override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue {
                if(kind != WorkflowKind.SEG) return super.request(kind, inputs, frame)
                val page = frame.identity(WorkflowSystem.PAGE)!!
                val pass = calls.merge(page, 1, Int::plus)!!
                if(pass == 2) {
                    assertEquals(1, (frame.read(WorkflowRef(WorkflowSystem.PAGE, listOf("records"))) as WorkflowValue.ListValue).items.size)
                    assertEquals(text("旧译文"), frame.read(WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles", "0", "translation"))))
                }
                val count = if(pass == 1) 2 else if(page.startsWith("7:")) 0 else 1
                return WorkflowValue.ListValue((1..count).map { i -> record("id" to text("$page:seg:$pass:$i"), "index" to WorkflowValue.Number(i.toDouble()),
                    "image" to WorkflowValue.Image("$page:seg:$pass:$i"), "source" to text(""), "translation" to text(""), "confidence" to WorkflowValue.Number(1.0), "kind" to text("BUBBLE")) })
            }
            override suspend fun segmentedPage(frame: WorkflowFrame) {
                val bubbles = frame.read(WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles"))) as WorkflowValue.ListValue
                assertEquals(bubbles, frame.read(WorkflowRef(custom.id)))
                assertTrue((frame.read(WorkflowRef(WorkflowSystem.PAGE, listOf("records"))) as WorkflowValue.ListValue).items.isEmpty())
                assertTrue(bubbles.items.all { (it as WorkflowValue.Record).fields["translation"] == text("") })
                snapshots += frame.identity(WorkflowSystem.PAGE)!! to bubbles
            }
            override suspend fun publishPage(frame: WorkflowFrame) {
                val page = frame.identity(WorkflowSystem.PAGE)!!
                val count = (frame.read(WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles"))) as WorkflowValue.ListValue).items.size
                assertEquals(if(page.startsWith("7:")) 0 else 1, count)
                super.publishPage(frame)
            }
        }
        WorkflowRuntime().execute(WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(program)), host)
        assertEquals(mapOf("7:1" to 2, "39:1" to 2), calls)
        assertEquals(4, snapshots.size)
        assertEquals(2, host.saved.size)
    }

    @Test fun `seg rejects rebuilding a page from an asynchronous bubble iterator`() {
        val custom = WorkflowVariable("nested-seg", "重复气泡", WorkflowType.list(WorkflowType.BUBBLE))
        val program = WorkflowEditing.update(WorkflowTemplates.localMachine(), "bubbles") { loop -> loop.copy(children = listOf(
            WorkflowNode("nested-declare", WorkflowKind.DECLARE, variable = custom),
            WorkflowNode("nested-seg-row", WorkflowKind.SEG, target = WorkflowRef(custom.id), inputs = mapOf("image" to WorkflowSystem.ref(WorkflowSystem.PAGE, "image"))))) }
        assertTrue(WorkflowValidator.validate(program).issues.any { it.nodeId == "nested-seg-row" && it.message.contains("气泡循环之外") })
    }

    @Test fun `api and streaming api receive the collected image list in order`() = runTest {
        for (kind in listOf(WorkflowKind.API, WorkflowKind.API_STREAM)) {
            val pictures = WorkflowVariable("pictures", "图片列表", WorkflowType.list(WorkflowType.IMAGE))
            val result = WorkflowVariable("result", "对照列表", WorkflowType.list(WorkflowType.GLOSSARY_ENTRY))
            val iterator = WorkflowVariable("picture-bubble", "气泡", WorkflowType.BUBBLE)
            val api = WorkflowNode("multi-api", kind, target = WorkflowRef(result.id), resultType = result.type,
                variable = if (kind == WorkflowKind.API_STREAM) WorkflowVariable("entry", "当前条目", WorkflowType.GLOSSARY_ENTRY) else null,
                inputs = mapOf("profile" to WorkflowExpression.Text("fixture"), "prompt" to WorkflowExpression.Text("Read all images"), "images" to WorkflowSystem.ref(pictures.id)))
            val program = WorkflowEditing.update(WorkflowTemplates.blank(), "pages") { page -> page.copy(children = page.children + listOf(
                WorkflowNode("pictures-declare", WorkflowKind.DECLARE, variable = pictures),
                WorkflowNode("collect-pictures", WorkflowKind.EACH, variable = iterator, collectTo = WorkflowRef(pictures.id),
                    inputs = mapOf("items" to WorkflowSystem.ref(WorkflowSystem.PAGE, "bubbles"), "collectValue" to WorkflowSystem.ref(iterator.id, "image"))),
                WorkflowNode("result-declare", WorkflowKind.DECLARE, variable = result), api)) }
            val restored = WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(program))
            assertTrue(WorkflowValidator.validate(restored).valid)
            val calls = mutableListOf<WorkflowApiCall>()
            val host = object : Host(chapterIds = listOf("7", "39"), pageCount = 1) {
                override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue {
                    calls += call
                    val page = frame.identity(WorkflowSystem.PAGE)!!
                    assertEquals((1..3).map { WorkflowValue.Image("$page:$it") }, call.images)
                    return WorkflowValue.ListValue(listOf(WorkflowValue.Record(mapOf("source" to text("original"), "translation" to text("译文")))))
                }
            }
            WorkflowRuntime().execute(restored, host)
            assertEquals(2, calls.size)
            assertEquals(2, host.saved.size)
            assertTrue(WorkflowSource.render(restored).contains("图片=图片列表<列表<图片>>"))
            val invalid = WorkflowEditing.update(restored, api.id) { it.copy(inputs = it.inputs + ("images" to WorkflowExpression.Empty(WorkflowType.list(WorkflowType.TEXT)))) }
            assertTrue(WorkflowValidator.validate(invalid).issues.any { it.nodeId == api.id && it.message.contains("附件") })
        }
    }

    @Test fun `typed destinations reject an engine returning the wrong value`() = runTest {
        val host = object : Host(pageCount = 1) {
            override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue =
                if(kind == WorkflowKind.OCR) WorkflowValue.Number(1.0) else super.request(kind, inputs, frame)
        }
        val failure = assertFailsWith<WorkflowExecutionFailure> { WorkflowRuntime().execute(WorkflowTemplates.localMachine(), host) }
        assertEquals("ocr", failure.rowId)
        assertTrue(host.saved.isEmpty())
    }
    @Test fun `context keeps departed loop values while glossary remains live`() = runTest {
        val context = WorkflowVariable("context", "上下文", WorkflowType.CONTEXT)
        var program = WorkflowTemplates.localMachine()
        program = WorkflowEditing.insert(program, WorkflowPosition("pages", 0), WorkflowNode("new-context", WorkflowKind.DECLARE, variable = context))
        program = WorkflowEditing.update(program, "bubbles") { it.copy(mode = WorkflowMode.SYNC, children = listOf(
            WorkflowNode("example-message", WorkflowKind.MESSAGE, target = WorkflowRef(context.id), inputs = mapOf("user" to WorkflowExpression.Template("\${ID}：\${字典}",
                mapOf("ID" to WorkflowRef(WorkflowSystem.BUBBLE, listOf("id")), "字典" to WorkflowRef(WorkflowSystem.GLOSSARY))))))) }
        val variable = WorkflowVariable("api-result", "结果", WorkflowType.TEXT)
        program = WorkflowEditing.insert(program, WorkflowPosition("pages", 3), WorkflowNode("new-result", WorkflowKind.DECLARE, variable = variable))
        program = WorkflowEditing.insert(program, WorkflowPosition("pages", 4), WorkflowNode("request", WorkflowKind.API, target = WorkflowRef(variable.id), inputs = mapOf(
            "profile" to WorkflowExpression.Text("fixture"), "context" to WorkflowSystem.ref(context.id), "prompt" to WorkflowExpression.Text("summary"))))
        val calls = mutableListOf<WorkflowApiCall>()
        val host = object : Host(pageCount = 1) {
            override fun step(node: WorkflowNode, frame: WorkflowFrame) { if(node.id == "new-result") dictionary["Akira"] = "阿基拉" }
            override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue { calls += call; return text("ok") }
        }
        WorkflowRuntime().execute(program, host)
        assertEquals(4, calls.single().messages.size)
        assertTrue(calls.single().messages.take(3).all { it.content.contains("阿基拉") })
        assertTrue(calls.single().messages[0].content.startsWith("7:1:1"))
    }
    @Test fun `priority admission waits before consuming the shared page permit`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val host = object : Host(listOf("7", "39"), 2) {
            override suspend fun beforePage(frame: WorkflowFrame) {
                if(frame.identity(WorkflowSystem.PAGE) == "7:1") delay(100) else ready.await()
            }
            override suspend fun closePage(frame: WorkflowFrame) { if(frame.identity(WorkflowSystem.PAGE) == "7:1") ready.complete(Unit) }
        }
        WorkflowRuntime(2).execute(WorkflowTemplates.localMachine(), host)
        assertEquals(4, host.saved.size)
        assertEquals("7:1", host.saved.first())
    }
    private fun text(value: String) = WorkflowValue.Text(value)
    private fun record(vararg fields: Pair<String, WorkflowValue>) = WorkflowValue.Record(fields.toMap())
    private open inner class Host(private val chapterIds: List<String> = listOf("7"), private val pageCount: Int = 3) : WorkflowRuntimeHost {
        override val manga = record("id" to text("manga"), "name" to text("fixture"))
        override val sourceLanguage = "en"; override val targetLanguage = "zh-Hans"; override val style = ""
        val dictionary = linkedMapOf<String, String>(); val dictionaryLock = Mutex()
        val requests = mutableListOf<Pair<String, String>>()
        val active = AtomicInteger(); val peak = AtomicInteger(); val saved = mutableListOf<String>()
        override suspend fun chapters() = chapterIds.map { record("id" to text(it), "name" to text(it), "index" to WorkflowValue.Number(it.toDouble()), "records" to WorkflowValue.ListValue(emptyList())) }
        override suspend fun pages(chapter: WorkflowValue.Record) = (1..pageCount).map { index ->
            val id = (chapter.fields.getValue("id") as WorkflowValue.Text).value + ":" + index
            record("id" to text(id), "name" to text(id), "number" to WorkflowValue.Number(index.toDouble()),
                "image" to WorkflowValue.Image(id), "bubbles" to WorkflowValue.ListValue(emptyList()), "records" to WorkflowValue.ListValue(emptyList()))
        }
        override suspend fun glossary() = dictionaryLock.withLock { dictionary.toMap() }
        override suspend fun mergeGlossary(entries: Map<String, String>) { dictionaryLock.withLock { entries.forEach { (k,v) -> dictionary.putIfAbsent(k,v) } } }
        override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue {
            val page = frame.identity(WorkflowSystem.PAGE)!!
            return when(kind) {
                WorkflowKind.SEG -> WorkflowValue.ListValue((1..3).map { index -> record("id" to text("$page:$index"), "index" to WorkflowValue.Number(index.toDouble()),
                    "image" to WorkflowValue.Image("$page:$index"), "source" to text("source-$index"), "translation" to text(""), "confidence" to WorkflowValue.Number(1.0), "kind" to text("BUBBLE")) })
                WorkflowKind.OCR -> {
                    val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
                    try { delay(if(frame.identity(WorkflowSystem.CHAPTER) == "7") 50 else 1); text("Akira") }
                    finally { active.decrementAndGet() }
                }
                WorkflowKind.TRANSLATE -> { val input = (inputs.getValue("text") as WorkflowValue.Text).value; requests += frame.identity(WorkflowSystem.CHAPTER)!! to input; text("译文") }
                else -> error("unused")
            }
        }
        override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue = error("unused")
        override suspend fun publishPage(frame: WorkflowFrame) { saved += frame.identity(WorkflowSystem.PAGE)!! }
    }
    @Test fun `templates and codec retain types scopes prompts and stable identities`() {
        for(program in listOf(WorkflowTemplates.blank(), WorkflowTemplates.localMachine(), WorkflowTemplates.visionApi("fixture"), WorkflowTemplates.visionWithGlossary("fixture"))) {
            assertEquals(program, WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(program)))
            if(program.uses(WorkflowKind.SEG)) assertEquals(emptyList(), WorkflowValidator.validate(program).issues)
        }
        assertFalse(WorkflowValidator.validate(WorkflowTemplates.visionApi()).valid)
    }
    @Test fun `dragging engine outside its page is rejected and legal outer moves preserve references`() {
        val local = WorkflowTemplates.localMachine()
        assertFailsWith<IllegalArgumentException> { WorkflowEditing.move(local, "seg", WorkflowPosition("chapters", 0)) }
        val variable = WorkflowVariable("v", "文本", WorkflowType.TEXT)
        val declared = WorkflowNode("declare", WorkflowKind.DECLARE, variable = variable)
        val withVariable = WorkflowEditing.insert(local, WorkflowPosition("pages", 0), declared)
        val moved = WorkflowEditing.move(withVariable, "declare", WorkflowPosition("chapters", 0))
        assertTrue(WorkflowValidator.validate(moved).valid)
        assertEquals(variable.id, moved.allNodes().first { it.id == declared.id }.variable?.id)
        assertFailsWith<IllegalArgumentException> { WorkflowEditing.move(local, "pages", WorkflowPosition("manga", 0)) }
    }
    @Test fun `async branches append to an outer variable while parent replacement stays rejected`() {
        val variable = WorkflowVariable("shared", "共享文本", WorkflowType.TEXT)
        var program = WorkflowEditing.insert(WorkflowTemplates.localMachine(), WorkflowPosition("pages", 0), WorkflowNode("shared-declare", WorkflowKind.DECLARE, variable = variable))
        program = WorkflowEditing.insert(program, WorkflowPosition("bubbles", 0), WorkflowNode("append-text", WorkflowKind.APPEND, target = WorkflowRef(variable.id), inputs = mapOf("value" to WorkflowExpression.Text("x"))))
        val appended = WorkflowValidator.validate(program)
        assertTrue(appended.valid, appended.issues.toString())
        val replaced = WorkflowEditing.insert(program, WorkflowPosition("bubbles", 1), WorkflowNode("replace-parent", WorkflowKind.SET, target = WorkflowRef(variable.id), inputs = mapOf("value" to WorkflowExpression.Text("y"))))
        assertTrue(WorkflowValidator.validate(replaced).issues.any { it.nodeId == "replace-parent" && it.message.contains("父层") })
        assertTrue(WorkflowValidator.validate(WorkflowTemplates.localMachine()).valid)
    }
    @Test fun `parallel branches append to an outer list without losing items`() = runTest {
        val pictures = WorkflowVariable("pictures", "图片列表", WorkflowType.list(WorkflowType.IMAGE))
        val iterator = WorkflowVariable("bubble", "气泡", WorkflowType.BUBBLE)
        val collect = WorkflowNode("collect-pictures", WorkflowKind.EACH, variable = iterator,
            inputs = mapOf("items" to WorkflowSystem.ref(WorkflowSystem.PAGE, "bubbles")),
            children = listOf(WorkflowNode("append-picture", WorkflowKind.APPEND, target = WorkflowRef(pictures.id),
                inputs = mapOf("value" to WorkflowSystem.ref(iterator.id, "image")))))
        val program = WorkflowEditing.update(WorkflowTemplates.blank(), "pages") { page ->
            page.copy(children = page.children + listOf(WorkflowNode("declare-pictures", WorkflowKind.DECLARE, variable = pictures), collect))
        }
        assertTrue(WorkflowValidator.validate(program).valid, WorkflowValidator.validate(program).issues.toString())
        val collected = mutableListOf<WorkflowValue>()
        val host = object : Host(chapterIds = listOf("7"), pageCount = 1) {
            override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue =
                if(kind != WorkflowKind.SEG) super.request(kind, inputs, frame) else WorkflowValue.ListValue((1..200).map { index ->
                    record("id" to text("7:1:$index"), "index" to WorkflowValue.Number(index.toDouble()),
                        "image" to WorkflowValue.Image("7:1:$index"), "source" to text("source-$index"), "translation" to text(""),
                        "confidence" to WorkflowValue.Number(1.0), "kind" to text("BUBBLE")) })
            override suspend fun publishPage(frame: WorkflowFrame) {
                collected += (frame.read(WorkflowRef(pictures.id)) as WorkflowValue.ListValue).items
            }
        }
        withContext(Dispatchers.Default) { WorkflowRuntime(8).execute(program, host) }
        assertEquals(200, collected.size)
        assertEquals((1..200).map { "7:1:$it" }.toSet(), collected.map { (it as WorkflowValue.Image).key }.toSet())
    }
    @Test fun `sync loop is sequential and async pages are bounded`() = runTest {
        var sync = WorkflowTemplates.localMachine()
        for(id in listOf("chapters", "pages", "bubbles")) sync = WorkflowEditing.update(sync, id) { it.copy(mode = WorkflowMode.SYNC) }
        val serial = Host(); WorkflowRuntime(2).execute(sync, serial)
        assertEquals(1, serial.peak.get()); assertEquals(listOf("7:1", "7:2", "7:3"), serial.saved)
        val concurrent = Host(); val async = WorkflowEditing.update(sync, "pages") { it.copy(mode = WorkflowMode.ASYNC) }
        WorkflowRuntime(2).execute(async, concurrent)
        assertEquals(2, concurrent.peak.get()); assertEquals(3, concurrent.saved.size)
    }
    @Test fun `chapter 39 addition is immediately visible to chapter 7 and later additions never replace it`() = runTest {
        var program = WorkflowTemplates.localMachine()
        program = WorkflowEditing.update(program, "translate") { it.copy(inputs = it.inputs + ("text" to WorkflowExpression.Template("\${字典}", mapOf("字典" to WorkflowRef(WorkflowSystem.GLOSSARY))))) }
        program = WorkflowEditing.insert(program, WorkflowPosition("chapters", 1), WorkflowNode("add-name", WorkflowKind.MERGE_GLOSSARY,
            inputs = mapOf("items" to WorkflowExpression.Ref(WorkflowRef("names")))))
        program = WorkflowEditing.insert(program, WorkflowPosition("chapters", 1), WorkflowNode("declare-names", WorkflowKind.DECLARE,
            variable = WorkflowVariable("names", "提取结果", WorkflowType.list(WorkflowType.GLOSSARY_ENTRY))))
        val host = object : Host(listOf("7", "39"), 1) {
            override suspend fun chapterFinished(frame: WorkflowFrame, complete: Boolean) { mergeGlossary(mapOf("Akira" to if(frame.identity(WorkflowSystem.CHAPTER) == "39") "阿基拉" else "后来的译名")) }
        }
        WorkflowRuntime(8).execute(program, host)
        assertEquals("阿基拉", host.dictionary["Akira"])
        assertTrue(host.requests.filter { it.first == "7" }.all { it.second.contains("阿基拉") })
        assertTrue(host.requests.filter { it.first == "39" }.all { it.second == "{}" })
    }
    @Test fun `collecting async returns preserves input order without shared append`() = runTest {
        val output = WorkflowVariable("results", "结果", WorkflowType.list(WorkflowType.TEXT))
        var program = WorkflowTemplates.localMachine()
        program = WorkflowEditing.insert(program, WorkflowPosition("pages", 1), WorkflowNode("new-results", WorkflowKind.DECLARE, variable = output))
        program = WorkflowEditing.update(program, "bubbles") { it.copy(collectTo = WorkflowRef(output.id), children = listOf(
            WorkflowNode("return", WorkflowKind.RETURN, inputs = mapOf("value" to WorkflowSystem.ref(WorkflowSystem.BUBBLE, "source"))))) }
        val seen = mutableListOf<List<String>>()
        val host = object : Host(pageCount = 1) {
            override fun step(node: WorkflowNode, frame: WorkflowFrame) { }
            override suspend fun publishPage(frame: WorkflowFrame) { seen += (frame.read(WorkflowRef(output.id)) as WorkflowValue.ListValue).items.map { (it as WorkflowValue.Text).value } }
        }
        WorkflowRuntime(3).execute(program, host)
        assertEquals(listOf(listOf("source-1", "source-2", "source-3")), seen)
    }
    @Test fun `structured API data requires exact fields and bubble identity`() {
        val type = WorkflowType.list(WorkflowType.TRANSLATION)
        val valid = "[{\"bubbleId\":\"b\",\"source\":\"Akira\",\"translation\":\"阿基拉\"}]"
        assertEquals(WorkflowValueCodec.parseResponse(valid, type, "b"), WorkflowValueCodec.parseResponse("```json\n$valid\n```", type, "b"))
        for(raw in listOf(valid.replace("\"b\"", "\"other\""), "[]", valid.replace("\"source\":\"Akira\",", ""), valid.replace("\"source\":", "\"extra\":true,\"source\":"), valid.dropLast(1))) {
            assertFails { WorkflowValueCodec.parseResponse(raw, type, "b") }
        }
        assertFails { WorkflowValueCodec.display(WorkflowValue.Image("image")) }
    }
    @Test fun `single bubble records reject lists and describe missing or extra fields`() {
        val pair = """{"source":"一","translation":"one"}"""
        assertEquals(record("source" to text("一"), "translation" to text("one")),
            WorkflowValueCodec.parseResponse(pair, WorkflowType.GLOSSARY_ENTRY))
        val array = assertFailsWith<IllegalArgumentException> {
            WorkflowValueCodec.parseResponse("[$pair,$pair]", WorkflowType.GLOSSARY_ENTRY)
        }
        assertContains(array.message.orEmpty(), "单个 JSON 对象")
        assertContains(array.message.orEmpty(), "2 项")
        val fields = assertFailsWith<IllegalArgumentException> {
            WorkflowValueCodec.parseResponse("""{"source":"一","bubbleId":"b"}""", WorkflowType.GLOSSARY_ENTRY)
        }
        assertContains(fields.message.orEmpty(), "缺少字段 translation")
        assertContains(fields.message.orEmpty(), "多余字段 bubbleId")
    }
    @Test fun `vision iterations keep their image and local result across concurrent calls and copied variables`() = runTest {
        for(mode in listOf(WorkflowMode.SYNC, WorkflowMode.ASYNC)) for(automatic in listOf(false, true)) {
            var program = WorkflowTemplates.visionApi("fixture")
            val loop = WorkflowEditing.copyNode(program.allNodes().single { it.id == "bubbles" }).copy(mode = mode)
            val iterator = requireNotNull(loop.variable).id
            val localResult = requireNotNull(loop.children.first().variable).id
            program = WorkflowEditing.update(program, "bubbles") { loop }
            if(automatic) program = WorkflowEditing.update(program, loop.children[1].id) { it.copy(inputs = it.inputs - "images") }
            for(id in listOf("chapters", "pages")) program = WorkflowEditing.update(program, id) { it.copy(mode = mode) }
            val calls = mutableListOf<String>()
            val published = mutableMapOf<String, List<Pair<String, String>>>()
            val host = object : Host(listOf("7", "39"), 2) {
                override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue {
                    val id = frame.text(iterator, "id")
                    assertEquals(listOf(WorkflowValue.Image(id)), call.images)
                    assertEquals(id, call.expectedBubbleId)
                    assertEquals(WorkflowType.GLOSSARY_ENTRY, call.resultType)
                    assertTrue(call.messages.last().content.contains("只返回一个 JSON 对象"))
                    assertEquals("", frame.text(localResult, "source"))
                    calls += id
                    val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
                    try {
                        delay((4 - id.substringAfterLast(':').toInt()) * 10L)
                        assertEquals(id, frame.text(iterator, "id"))
                        assertEquals(WorkflowValue.Image(id), frame.read(WorkflowRef(iterator, listOf("image"))))
                        assertEquals("", frame.text(localResult, "source"))
                        return WorkflowValueCodec.parseResponse("""{"source":"source-$id","translation":"translated-$id"}""", call.resultType)
                    } finally { active.decrementAndGet() }
                }
                override suspend fun publishPage(frame: WorkflowFrame) {
                    val page = requireNotNull(frame.identity(WorkflowSystem.PAGE))
                    val bubbles = frame.read(WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles"))) as WorkflowValue.ListValue
                    published[page] = bubbles.items.map { value ->
                        val fields = (value as WorkflowValue.Record).fields
                        (fields.getValue("source") as WorkflowValue.Text).value to (fields.getValue("translation") as WorkflowValue.Text).value
                    }
                }
            }
            WorkflowRuntime(2).execute(program, host)
            assertEquals(12, calls.size); assertEquals(12, calls.toSet().size); assertEquals(4, published.size)
            published.forEach { (page, results) ->
                assertEquals((1..3).map { "source-$page:$it" to "translated-$page:$it" }, results)
            }
            if(mode == WorkflowMode.SYNC) assertEquals(1, host.peak.get()) else assertTrue(host.peak.get() > 1)
        }
    }
    @Test fun `explicit no image and empty image list disable automatic attachment`() = runTest {
        for(imageInput in listOf<Map<String, WorkflowExpression>>(
            mapOf("attachCurrentImage" to WorkflowExpression.Boolean(false)),
            mapOf("images" to WorkflowExpression.Empty(WorkflowType.list(WorkflowType.IMAGE))))) {
            var program=WorkflowTemplates.visionApi("fixture")
            program=WorkflowEditing.update(program,"api-vision") {it.copy(inputs=(it.inputs-"images")+imageInput)}
            program=WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(program))
            val calls=mutableListOf<WorkflowApiCall>()
            val host=object:Host(pageCount=1) {
                override suspend fun api(call:WorkflowApiCall,frame:WorkflowFrame):WorkflowValue {
                    calls+=call;assertTrue(call.images.isEmpty())
                    return record("source" to text("source"),"translation" to text("translated"))
                }
            }
            WorkflowRuntime().execute(program,host)
            assertEquals(3,calls.size)
            if("attachCurrentImage" in imageInput) assertContains(WorkflowSource.render(program),"图片=无")
        }
    }
    @Test fun `automatic attachment source shows the actual iterator and never leaks after loop`() = runTest {
        var program=WorkflowTemplates.visionApi("fixture")
        program=WorkflowEditing.update(program,"api-vision") {it.copy(inputs=it.inputs-"images")}
        assertContains(WorkflowSource.render(program),"图片=气泡-图片<图片>（自动）")
        val output=WorkflowVariable("summary","Summary",WorkflowType.TEXT)
        program=WorkflowEditing.insert(program,WorkflowPosition("pages",2),WorkflowNode("summary-new",WorkflowKind.DECLARE,variable=output))
        program=WorkflowEditing.insert(program,WorkflowPosition("pages",3),WorkflowNode("summary-api",WorkflowKind.API,target=WorkflowRef(output.id),
            inputs=mapOf("profile" to WorkflowExpression.Text("fixture"),"prompt" to WorkflowExpression.Text("Summary"))))
        val calls=mutableListOf<WorkflowApiCall>()
        val host=object:Host(pageCount=1) {
            override suspend fun api(call:WorkflowApiCall,frame:WorkflowFrame):WorkflowValue {
                calls+=call
                return if(call.resultType==WorkflowType.TEXT) text("Summary") else record("source" to text("source"),"translation" to text("translated"))
            }
        }
        WorkflowRuntime().execute(program,host)
        assertEquals(4,calls.size);assertTrue(calls.last().images.isEmpty());assertNull(calls.last().expectedBubbleId)
        val invalid=WorkflowEditing.update(program,"summary-api") {it.copy(inputs=it.inputs+("attachCurrentImage" to WorkflowExpression.Boolean(true)))}
        assertTrue(WorkflowValidator.validate(invalid).issues.any {it.message.contains("气泡循环")})
    }
    @Test fun `non JSON model replies give a readable error instead of decoder internals`() {
        val failure=assertFailsWith<IllegalArgumentException> {WorkflowValueCodec.parseResponse("Please upload an image. Example: {}",WorkflowType.GLOSSARY_ENTRY)}
        assertContains(failure.message.orEmpty(),"不是有效 JSON")
        assertContains(failure.message.orEmpty(),"API 日志")
        assertFalse(failure.message.orEmpty().contains("offset"))
    }
    @Test fun `copy remaps local declarations and all their references`() {
        val variable = WorkflowVariable("v", "文本", WorkflowType.TEXT)
        val node = WorkflowNode("each", WorkflowKind.EACH, variable = WorkflowVariable("item", "项", WorkflowType.TEXT),
            children = listOf(WorkflowNode("new", WorkflowKind.DECLARE, variable = variable), WorkflowNode("set", WorkflowKind.SET, target = WorkflowRef(variable.id), inputs = mapOf("value" to WorkflowSystem.ref(variable.id)))))
        val copy = WorkflowEditing.copyNode(node)
        val id = copy.children.first().variable!!.id
        assertNotEquals(variable.id, id); assertEquals(id, copy.children.last().target!!.variableId)
        assertEquals(id, (copy.children.last().inputs.getValue("value") as WorkflowExpression.Ref).value.variableId)
    }
}
