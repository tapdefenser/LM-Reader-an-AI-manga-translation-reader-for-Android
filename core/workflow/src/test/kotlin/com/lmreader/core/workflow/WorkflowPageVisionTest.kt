package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.*

class WorkflowPageVisionTest {
    @Test fun `page vision reference replaces full manga in built ins and exports without a machine binding`() {
        assertFalse(TranslationWorkflow.BUILT_INS.any { it.id == TranslationWorkflow.FULL_MANGA_API.id })
        val workflow = TranslationWorkflow.VISION_PAGE_API
        val bound = WorkflowReferenceTemplates.bindApi(workflow.program, "fixture")
        assertTrue(WorkflowValidator.validate(bound).valid, WorkflowValidator.validate(bound).issues.toString())
        assertFalse(bound.uses(WorkflowKind.OCR)); assertFalse(bound.uses(WorkflowKind.TRANSLATE))
        assertEquals(1, bound.allNodes().count { it.kind == WorkflowKind.API_STREAM })
        assertEquals(workflow.program, WorkflowFileCodec.decode(WorkflowFileCodec.encode(workflow)).program)
        assertEquals(WorkflowExpression.Text(""), workflow.program.allNodes().single { it.kind == WorkflowKind.API_STREAM }.inputs["profile"])
        val exported = File("build/fixtures/vision-page.lmworkflow.json")
        exported.parentFile.mkdirs()
        exported.writeText(WorkflowFileCodec.encode(workflow.copy(builtIn = false)))
    }

    @Test fun `page vision streams ordered pairs from all crops and skips empty pages`() = runTest {
        val host = Host()
        WorkflowRuntime().execute(WorkflowReferenceTemplates.visionPage("fixture"), host)
        assertEquals(1, host.calls.size)
        val call = host.calls.single()
        assertEquals(listOf("p1:1", "p1:2", "p1:3"), call.images.map { it.key })
        assertEquals(3, call.expectedCount)
        assertTrue(call.messages.last().content.contains("3.0 张"))
        assertEquals(listOf("原文1", "", "原文3"), host.saved.getValue("p1").items.map { ((it as WorkflowValue.Record).fields.getValue("source") as WorkflowValue.Text).value })
        assertEquals(listOf("译文1", "", "译文3"), host.saved.getValue("p1").items.map { ((it as WorkflowValue.Record).fields.getValue("translation") as WorkflowValue.Text).value })
        assertTrue(host.saved.getValue("p2").items.isEmpty())
        assertEquals(3, host.previews)
    }

    @Test fun `page vision fails incomplete or excess replies before publishing a page`() = runTest {
        for (count in listOf(2, 4)) {
            val host = Host(count)
            assertFailsWith<WorkflowExecutionFailure> { WorkflowRuntime().execute(WorkflowReferenceTemplates.visionPage("fixture"), host) }
            assertFalse(host.saved.containsKey("p1"))
        }
        assertFailsWith<IllegalArgumentException> { WorkflowValueCodec.requireItemCount(WorkflowValue.ListValue(emptyList()), 3) }
    }

    private class Host(private val replyCount: Int = 3) : WorkflowRuntimeHost {
        override val manga = record("id" to text("m"), "name" to text("漫画"))
        override val sourceLanguage = "ja"; override val targetLanguage = "zh"; override val style = "自然"
        val calls = mutableListOf<WorkflowApiCall>()
        val saved = mutableMapOf<String, WorkflowValue.ListValue>()
        var previews = 0
        override suspend fun chapters() = listOf(record("id" to text("c"), "name" to text("章"), "index" to number(1), "records" to WorkflowValue.ListValue(emptyList())))
        override suspend fun pages(chapter: WorkflowValue.Record) = (1..2).map { i -> record("id" to text("p$i"), "name" to text("页$i"),
            "number" to number(i), "image" to WorkflowValue.Image("p$i"), "bubbles" to WorkflowValue.ListValue(emptyList()), "records" to WorkflowValue.ListValue(emptyList())) }
        override suspend fun glossary() = emptyMap<String, String>()
        override suspend fun mergeGlossary(entries: Map<String, String>) = error("unused")
        override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue {
            assertEquals(WorkflowKind.SEG, kind)
            val id = frame.identity(WorkflowSystem.PAGE)!!
            return WorkflowValue.ListValue(if(id == "p2") emptyList() else (1..3).map { i -> record("id" to text("$id:$i"), "index" to number(i),
                "image" to WorkflowValue.Image("$id:$i"), "source" to text(""), "translation" to text(""), "confidence" to number(1), "kind" to text("BUBBLE")) })
        }
        override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue {
            calls += call
            return WorkflowValue.ListValue((1..replyCount).map { i -> record("source" to text(if(i == 2) "" else "原文$i"), "translation" to text(if(i == 2) "" else "译文$i")) })
        }
        override suspend fun previewPage(frame: WorkflowFrame) { previews++ }
        override suspend fun publishPage(frame: WorkflowFrame) { saved[frame.identity(WorkflowSystem.PAGE)!!] = frame.read(WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles"))) as WorkflowValue.ListValue }
    }
    companion object {
        private fun text(value: String) = WorkflowValue.Text(value)
        private fun number(value: Int) = WorkflowValue.Number(value.toDouble())
        private fun record(vararg fields: Pair<String, WorkflowValue>) = WorkflowValue.Record(fields.toMap())
    }
}
