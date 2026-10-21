package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WorkflowPageFailureIsolationTest {
    @Test fun handledFailureSkipsOnlyThePageInSerialAndParallelWorkflows() = runTest {
        for (mode in listOf(WorkflowMode.SYNC, WorkflowMode.ASYNC)) {
            val host = Host()
            val program = WorkflowEditing.update(WorkflowTemplates.localMachine(), "pages") { it.copy(mode = mode) }
            WorkflowRuntime().execute(program, host)
            assertEquals(listOf("p2"), host.failed.toList())
            assertEquals(setOf("p1", "p3"), host.published.toSet())
            assertEquals(setOf("p1", "p2", "p3"), host.closed.toSet())
            assertEquals(listOf(true), host.finished)
        }
    }

    @Test fun failedPageDoesNotAbortCollectedFullMangaApiOrReenterPublication() = runTest {
        for (prepareKind in listOf(WorkflowKind.EACH, WorkflowKind.PREPARE_MANGA)) {
            val host = Host()
            val program = WorkflowEditing.update(WorkflowReferenceTemplates.fullManga("fixture"), "prepare-manga") {
                if (prepareKind == WorkflowKind.EACH) it else it.copy(kind = prepareKind, variable = null,
                    inputs = emptyMap(), collectTo = null, target = WorkflowRef("manga-originals"))
            }
            WorkflowRuntime().execute(program, host)
            assertEquals(listOf("p2"), host.failed.toList())
            assertEquals(listOf("p1", "p3"), host.apiPages)
            assertEquals(setOf("p1", "p3"), host.published.toSet())
            assertEquals(listOf(true), host.finished, "Only finish the chapter after its remaining pages")
        }
    }

    @Test fun unhandledPageFailureStillFailsTheWorkflow() = runTest {
        val host = Host(handleFailure = false)
        assertFailsWith<WorkflowExecutionFailure> { WorkflowRuntime().execute(WorkflowTemplates.localMachine(), host) }
        assertTrue("p2" in host.closed)
    }

    @Test fun restartEscapesSerialAndParallelPageAndCollectedPageWorkers() = runTest {
        for(mode in WorkflowMode.entries) for(base in listOf(WorkflowTemplates.localMachine(), WorkflowReferenceTemplates.fullManga("fixture"))) {
            fun update(node: WorkflowNode): WorkflowNode = node.copy(mode = mode,
                children = node.children.map(::update), otherwise = node.otherwise.map(::update))
            val host = Host(restartOnPublish = true)
            assertFailsWith<WorkflowRestartRequested> { WorkflowRuntime().execute(base.copy(rows = base.rows.map(::update)), host) }
            assertTrue("p2" in host.closed)
            assertTrue(host.failed.isEmpty(), "A restart is a control command, not a failed page")
        }
    }

    @Test fun restartEscapesLegacyPreparationWorkers() = runTest {
        for(mode in WorkflowMode.entries) {
            val program = WorkflowEditing.update(WorkflowReferenceTemplates.fullManga("fixture"), "prepare-manga") {
                it.copy(kind = WorkflowKind.PREPARE_MANGA, mode = mode, variable = null,
                    inputs = emptyMap(), collectTo = null, target = WorkflowRef("manga-originals"))
            }
            val host = Host(restartOnAdmission = true)
            assertFailsWith<WorkflowRestartRequested> { WorkflowRuntime().execute(program, host) }
            assertTrue("p2" in host.closed)
            assertTrue(host.failed.isEmpty(), "Preparation must not consume a restart as a page failure")
        }
    }

    private class Host(private val handleFailure: Boolean = true, private val restartOnPublish: Boolean = false,
        private val restartOnAdmission: Boolean = false) : WorkflowRuntimeHost {
        override val manga = record("id" to text("m"), "name" to text("m"))
        override val sourceLanguage = "en"
        override val targetLanguage = "zh-Hans"
        override val style = ""
        val failed = linkedSetOf<String>()
        val published = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val finished = mutableListOf<Boolean>()
        var apiPages = emptyList<String>()
        private val prepared = mutableMapOf<String, WorkflowValue.Record>()
        override suspend fun chapters() = listOf(record("id" to text("c"), "name" to text("c"),
            "index" to number(1), "records" to list(emptyList())))
        override suspend fun pages(chapter: WorkflowValue.Record) = (1..3).map { i ->
            record("id" to text("p$i"), "name" to text("p$i"), "number" to number(i),
                "image" to WorkflowValue.Image("p$i"), "bubbles" to list(emptyList()), "records" to list(emptyList()))
        }.filterNot { (it.fields.getValue("id") as WorkflowValue.Text).value in failed }
        override suspend fun glossary() = emptyMap<String, String>()
        override suspend fun mergeGlossary(entries: Map<String, String>) = Unit
        override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue {
            val id = frame.identity(WorkflowSystem.PAGE)!!
            return when (kind) {
                WorkflowKind.SEG -> list(listOf(record("id" to text("$id:1"), "index" to number(1),
                    "image" to WorkflowValue.Image("$id:1"), "source" to text(""), "translation" to text(""),
                    "confidence" to number(1), "kind" to text("FREE_TEXT"))))
                WorkflowKind.OCR -> { if (id == "p2" && !restartOnPublish) error("Damaged page fixture"); text("Original $id") }
                WorkflowKind.TRANSLATE -> text("Translated $id")
                else -> error("unused $kind")
            }
        }
        override suspend fun pageFailed(frame: WorkflowFrame, failure: Throwable) {
            if (!handleFailure) throw failure
            failed += frame.identity(WorkflowSystem.PAGE)!!
        }
        override suspend fun completePreparationPage(frame: WorkflowFrame): WorkflowValue.ListValue {
            val page = frame.read(WorkflowRef(WorkflowSystem.PAGE)) as WorkflowValue.Record
            val bubbles = page.fields.getValue("bubbles") as WorkflowValue.ListValue
            val records = list(bubbles.items.map { value ->
                val bubble = value as WorkflowValue.Record
                record("pageId" to page.fields.getValue("id"), "pageNumber" to page.fields.getValue("number"),
                    "bubbleId" to bubble.fields.getValue("id"), "source" to bubble.fields.getValue("source"), "translation" to text(""))
            })
            val ready = page.copy(fields = page.fields + ("records" to records))
            prepared[frame.identity(WorkflowSystem.PAGE)!!] = ready
            frame.define(WorkflowSystem.PAGE, ready, WorkflowSystem.pageType)
            return records
        }
        override suspend fun admitPage(frame: WorkflowFrame, job: Job): WorkflowPageAdmission {
            if(restartOnAdmission && frame.identity(WorkflowSystem.PAGE) == "p2") throw WorkflowRestartRequested()
            prepared[frame.identity(WorkflowSystem.PAGE)]?.let { frame.define(WorkflowSystem.PAGE, it, WorkflowSystem.pageType) }
            return WorkflowPageAdmission.RUN
        }
        override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue {
            val items = requireNotNull(call.expectedItems).items
            apiPages = items.map { ((it as WorkflowValue.Record).fields.getValue("pageId") as WorkflowValue.Text).value }
            return list(items.map { (it as WorkflowValue.Record).copy(fields = it.fields + ("translation" to text("Translated"))) })
        }
        override suspend fun publishPage(frame: WorkflowFrame) {
            val id = frame.identity(WorkflowSystem.PAGE)!!
            if(id == "p2" && restartOnPublish) throw WorkflowRestartRequested()
            published += id
        }
        override suspend fun closePage(frame: WorkflowFrame) { closed += frame.identity(WorkflowSystem.PAGE)!! }
        override suspend fun chapterFinished(frame: WorkflowFrame, complete: Boolean) {
            finished += complete
        }
    }
    companion object {
        private fun text(value: String) = WorkflowValue.Text(value)
        private fun number(value: Int) = WorkflowValue.Number(value.toDouble())
        private fun list(values: List<WorkflowValue>) = WorkflowValue.ListValue(values)
        private fun record(vararg fields: Pair<String, WorkflowValue>) = WorkflowValue.Record(fields.toMap())
    }
}
