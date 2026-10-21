package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

class SubtitleWorkflowTest {
    @Test fun textPresetHasNoImagesAndSurvivesCodecAndFile() {
        val p = SubtitleWorkflowTemplates.program()
        assertTrue(WorkflowValidator.validate(p).valid, WorkflowValidator.validate(p).issues.toString())
        assertFalse(p.allNodes().any { it.kind in setOf(WorkflowKind.SEG, WorkflowKind.OCR, WorkflowKind.MANGA, WorkflowKind.PAGES) })
        assertEquals(p, WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(p)))
        val file = WorkflowFileCodec.decode(WorkflowFileCodec.encode(TranslationWorkflow.SUBTITLE_LOCAL))
        assertEquals(p, file.program)
        assertEquals(WorkflowInputScenario.IMAGE, WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(WorkflowTemplates.blank())).inputScenario)
    }
    @Test fun parallelCollectionRetainsIdsTimesAndInputOrder() = runTest {
        val host = Host()
        val result = WorkflowRuntime().execute(SubtitleWorkflowTemplates.program(), host) as WorkflowValue.ListValue
        assertEquals(listOf("id1", "id2"), result.items.map { ((it as WorkflowValue.Record).fields.getValue("id") as WorkflowValue.Text).value })
        assertEquals(listOf("translated:1", "translated:2"), result.items.map { ((it as WorkflowValue.Record).fields.getValue("translation") as WorkflowValue.Text).value })
        assertEquals(listOf(1000.0, 2000.0), result.items.map { ((it as WorkflowValue.Record).fields.getValue("startMs") as WorkflowValue.Number).value })
        assertEquals(listOf(WorkflowKind.TRANSLATE, WorkflowKind.TRANSLATE), host.requests)
    }
    @Test fun apiTextPresetUsesBoundProfileWithoutImageAttachment() = runTest {
        val host = Host()
        WorkflowRuntime().execute(SubtitleWorkflowTemplates.program("chosen-api"), host)
        assertEquals(2, host.apiCalls.size)
        assertTrue(host.apiCalls.all { it.profileId == "chosen-api" && it.images.isEmpty() })
        assertTrue(host.requests.isEmpty())
    }
    @Test fun textInputRejectsComicContainersAndMissingResult() {
        val text = SubtitleWorkflowTemplates.program()
        assertFalse(WorkflowValidator.validate(WorkflowTemplates.localMachine().copy(inputScenario = WorkflowInputScenario.TIMED_TEXT)).valid)
        assertFalse(WorkflowValidator.validate(text.copy(rows = text.rows.dropLast(1))).valid)
        assertFalse(WorkflowValidator.validate(text.copy(inputScenario = WorkflowInputScenario.IMAGE)).valid)
    }
    private class Host : WorkflowRuntimeHost {
        val requests = mutableListOf<WorkflowKind>(); val apiCalls = mutableListOf<WorkflowApiCall>()
        override val manga = WorkflowValue.Record(emptyMap())
        override val sourceLanguage = "en"; override val targetLanguage = "zh-Hans"; override val style = ""
        override suspend fun textEntries() = (1..2).map { n -> WorkflowValue.Record(mapOf("id" to WorkflowValue.Text("id$n"),
            "source" to WorkflowValue.Text("$n"), "translation" to WorkflowValue.Text(""), "startMs" to WorkflowValue.Number(n * 1000.0), "endMs" to WorkflowValue.Number(n * 1000.0 + 1500))) }
        override suspend fun chapters(): List<WorkflowValue.Record> = error("No comics")
        override suspend fun pages(chapter: WorkflowValue.Record): List<WorkflowValue.Record> = error("No pages")
        override suspend fun glossary() = emptyMap<String, String>()
        override suspend fun mergeGlossary(entries: Map<String, String>) {}
        override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue {
            requests += kind; val value = (inputs.getValue("text") as WorkflowValue.Text).value
            if (value == "1") delay(50)
            return WorkflowValue.Text("translated:$value")
        }
        override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue { apiCalls += call; return WorkflowValue.Text("API") }
    }
}
