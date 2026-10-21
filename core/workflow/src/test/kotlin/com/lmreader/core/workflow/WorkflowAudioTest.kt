package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

class WorkflowAudioTest {
    private val audio = WorkflowValue.Audio("sentence-7.wav")

    private fun timedTextProgram(): WorkflowProgram {
        val audioVariable = WorkflowVariable("sentence-audio", "本句录音", WorkflowType.AUDIO)
        val output = WorkflowVariable("answer", "回答", WorkflowType.TEXT)
        return WorkflowProgram(rows = listOf(
            WorkflowNode("declare-audio", WorkflowKind.DECLARE, variable = audioVariable,
                inputs = mapOf("value" to WorkflowSystem.ref(WorkflowSystem.CURRENT_AUDIO))),
            WorkflowNode("declare-answer", WorkflowKind.DECLARE, variable = output),
            WorkflowNode("api-audio", WorkflowKind.API, target = WorkflowRef(output.id), resultType = WorkflowType.TEXT,
                inputs = mapOf("profile" to WorkflowExpression.Text("p"), "prompt" to WorkflowExpression.Text("x"),
                    "audios" to WorkflowSystem.ref(audioVariable.id), "images" to WorkflowSystem.ref(WorkflowSystem.CURRENT_IMAGE))),
            WorkflowNode("return", WorkflowKind.RETURN, resultType = WorkflowType.list(WorkflowSystem.subtitleType),
                inputs = mapOf("value" to WorkflowSystem.ref(WorkflowSystem.TEXT_ENTRIES))),
        ), inputScenario = WorkflowInputScenario.TIMED_TEXT)
    }

    private fun textOnlyProgram(): WorkflowProgram {
        val output = WorkflowVariable("answer", "回答", WorkflowType.TEXT)
        return WorkflowProgram(rows = listOf(
            WorkflowNode("declare-answer", WorkflowKind.DECLARE, variable = output),
            WorkflowNode("api-text", WorkflowKind.API, target = WorkflowRef(output.id), resultType = WorkflowType.TEXT,
                inputs = mapOf("profile" to WorkflowExpression.Text("p"), "prompt" to WorkflowExpression.Text("x"))),
            WorkflowNode("return", WorkflowKind.RETURN, resultType = WorkflowType.list(WorkflowSystem.subtitleType),
                inputs = mapOf("value" to WorkflowSystem.ref(WorkflowSystem.TEXT_ENTRIES))),
        ), inputScenario = WorkflowInputScenario.TIMED_TEXT)
    }

    private class Host(private val current: WorkflowValue.Audio) : WorkflowRuntimeHost {
        var call: WorkflowApiCall? = null
        override val manga = WorkflowValue.Record(emptyMap())
        override val sourceLanguage = "ja"
        override val targetLanguage = "zh"
        override val style = ""
        override suspend fun currentAudio(): WorkflowValue.Audio = current
        override suspend fun currentImage() = WorkflowValue.Image("sentence-midpoint-7")
        override suspend fun chapters(): List<WorkflowValue.Record> = emptyList()
        override suspend fun pages(chapter: WorkflowValue.Record): List<WorkflowValue.Record> = emptyList()
        override suspend fun glossary(): Map<String, String> = emptyMap()
        override suspend fun mergeGlossary(entries: Map<String, String>) {}
        override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue =
            error("引擎不可用")
        override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue {
            this.call = call
            return WorkflowValue.Text("ok")
        }
    }

    @Test fun `timed text scope exposes read only current sentence audio`() {
        val validation = WorkflowValidator.validate(timedTextProgram())
        assertTrue(validation.valid, validation.issues.toString())
        val current = validation.scopes.getValue("api-audio").single { it.variable.id == WorkflowSystem.CURRENT_AUDIO }
        assertEquals("当前句子录音", current.variable.name)
        assertEquals(WorkflowType.AUDIO, current.variable.type)
        assertTrue(current.readOnly)
        val image = validation.scopes.getValue("api-audio").single { it.variable.id == WorkflowSystem.CURRENT_IMAGE }
        assertEquals(WorkflowType.IMAGE, image.variable.type)
        assertTrue(image.readOnly)
    }

    @Test fun `audio cannot be fabricated by empty values or api output`() {
        assertTrue(WorkflowValueCodec.matches(WorkflowValue.Audio("clip"), WorkflowType.AUDIO))
        assertFalse(WorkflowValueCodec.matches(WorkflowValue.Text("clip"), WorkflowType.AUDIO))
        assertFailsWith<IllegalStateException> { WorkflowFrame.empty(WorkflowType.AUDIO) }
        assertFailsWith<IllegalStateException> { WorkflowValueCodec.json(WorkflowValue.Audio("clip")) }
        assertFailsWith<IllegalStateException> { WorkflowValueCodec.display(WorkflowValue.Audio("clip")) }
        val empty = WorkflowProgram(rows = listOf(WorkflowNode("bad", WorkflowKind.DECLARE,
            variable = WorkflowVariable("empty-audio", "空音频", WorkflowType.AUDIO))), inputScenario = WorkflowInputScenario.TIMED_TEXT)
        val validation = WorkflowValidator.validate(empty)
        assertTrue(validation.issues.any { it.message.contains("初始引用") }, validation.issues.toString())
    }

    @Test fun `codec round trips audio type and audios ref`() {
        val program = timedTextProgram()
        val decoded = WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(program))
        assertEquals(program.rows, decoded.rows)
    }

    @Test fun `runtime passes the same opaque audio reference to the api call`() = runTest {
        val host = Host(audio)
        WorkflowRuntime(2).execute(timedTextProgram(), host)
        val call = assertNotNull(host.call)
        assertSame(audio, call.audios.single())
        assertEquals("sentence-midpoint-7", call.images.single().key)
    }

    @Test fun `api without audios keeps the old text flow with an empty audio list`() = runTest {
        val host = Host(audio)
        WorkflowRuntime(2).execute(textOnlyProgram(), host)
        assertTrue(assertNotNull(host.call).audios.isEmpty())
    }
}
