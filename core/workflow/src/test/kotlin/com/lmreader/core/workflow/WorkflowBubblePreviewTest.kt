package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WorkflowBubblePreviewTest {
    @Test fun iteratorTranslationIsPreviewedWhileAnotherBubbleIsBlockedWithoutWritingItsParent() = runTest {
        for (mode in listOf(WorkflowMode.SYNC, WorkflowMode.ASYNC)) {
            val host = Host()
            val program = WorkflowEditing.update(WorkflowTemplates.localMachine(), "bubbles") { it.copy(mode = mode) }
            val work = async { WorkflowRuntime().execute(program, host) }
            val first = host.first.await()
            assertEquals("Translated p:1", (first.fields.getValue("translation") as WorkflowValue.Text).value)
            assertEquals(emptyList(), host.saved)
            assertTrue(host.parentTranslations.all { it.isEmpty() }, "The preview hook must not mutate an async parent's page")
            host.release.complete(Unit); work.await()
            assertEquals(listOf("Translated p:1", "Translated p:2"), host.saved)
            assertEquals(2, host.previews.size)
        }
    }
    @Test fun renamedIteratorAndSetBackfillAlsoPreviewBeforePagePublication() = runTest {
        val host = Host(); host.release.complete(Unit)
        val program = WorkflowTemplates.visionApi("fixture")
        WorkflowRuntime().execute(program, host)
        assertEquals(listOf("Translated p:1", "Translated p:2"), host.saved)
        assertEquals(2, host.previews.size)
    }
    @Test fun supersedingOnePendingBubbleCancelsOnlyItsBranchAndStillPublishesOtherText() = runTest {
        val host = Host()
        val work = async { WorkflowRuntime().execute(WorkflowTemplates.localMachine(), host) }
        host.first.await(); host.secondEntered.await()
        host.supersede("p:2")
        work.await()
        assertEquals(listOf("Translated p:1"), host.saved)
        assertEquals(listOf("p:1"), host.previews.map { (it.fields.getValue("id") as WorkflowValue.Text).value })
        assertFalse(host.release.isCompleted, "The old translator need not finish before the page can complete")
    }
    private class Host : WorkflowRuntimeHost {
        override val manga = record("id" to text("m"), "name" to text("m"))
        override val sourceLanguage = "en"; override val targetLanguage = "zh-Hans"; override val style = ""
        val first = CompletableDeferred<WorkflowValue.Record>(); val release = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        private val obsolete = mutableSetOf<String>()
        private val tasks = mutableMapOf<String, Job>()
        fun supersede(id: String) { obsolete += id; tasks[id]?.cancel(WorkflowBubbleStopped()) }
        override suspend fun canProcessBubble(frame: WorkflowFrame) =
            (frame.currentBubble()?.fields?.get("id") as? WorkflowValue.Text)?.value !in obsolete
        override suspend fun runBubble(frame: WorkflowFrame, action: suspend () -> Boolean) = supervisorScope {
            val id = (frame.currentBubble()!!.fields.getValue("id") as WorkflowValue.Text).value
            val work = async(start = CoroutineStart.LAZY) { action() }
            tasks[id] = work; work.start()
            try { work.await() } finally { tasks.remove(id) }
        }
        val previews = mutableListOf<WorkflowValue.Record>(); var parentTranslations = emptyList<String>(); var saved = emptyList<String>()
        override suspend fun chapters() = listOf(record("id" to text("c"), "name" to text("c"),
            "index" to WorkflowValue.Number(1.0), "records" to WorkflowValue.ListValue(emptyList())))
        override suspend fun pages(chapter: WorkflowValue.Record) = listOf(record("id" to text("p"), "name" to text("p"),
            "number" to WorkflowValue.Number(1.0), "image" to WorkflowValue.Image("p"),
            "bubbles" to WorkflowValue.ListValue(emptyList()), "records" to WorkflowValue.ListValue(emptyList())))
        override suspend fun glossary() = emptyMap<String, String>()
        override suspend fun mergeGlossary(entries: Map<String, String>) = Unit
        override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue = when (kind) {
            WorkflowKind.SEG -> WorkflowValue.ListValue((1..2).map { n -> record("id" to text("p:$n"),
                "index" to WorkflowValue.Number(n.toDouble()), "image" to WorkflowValue.Image("p:$n"), "kind" to text("FREE_TEXT"),
                "confidence" to WorkflowValue.Number(1.0), "source" to text(""), "translation" to text("")) })
            WorkflowKind.OCR -> text((inputs.getValue("image") as WorkflowValue.Image).key)
            WorkflowKind.TRANSLATE -> {
                val source = (inputs.getValue("text") as WorkflowValue.Text).value
                if (source.endsWith(":2")) { secondEntered.complete(Unit); release.await() }
                text("Translated $source")
            }
            else -> error("unused")
        }
        override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue {
            val id = (frame.currentBubble()!!.fields.getValue("id") as WorkflowValue.Text).value
            return record("source" to text(id), "translation" to text("Translated $id"))
        }
        override suspend fun previewBubble(frame: WorkflowFrame, bubble: WorkflowValue.Record) {
            previews += bubble
            if (!first.isCompleted) {
                parentTranslations = translations(frame); first.complete(bubble)
            }
        }
        override suspend fun publishPage(frame: WorkflowFrame) { saved = translations(frame) }
        private suspend fun translations(frame: WorkflowFrame) =
            (frame.read(WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles"))) as WorkflowValue.ListValue).items
                .map { ((it as WorkflowValue.Record).fields.getValue("translation") as WorkflowValue.Text).value }
    }
}
private fun text(value: String) = WorkflowValue.Text(value)
private fun record(vararg values: Pair<String, WorkflowValue>) = WorkflowValue.Record(mapOf(*values))
