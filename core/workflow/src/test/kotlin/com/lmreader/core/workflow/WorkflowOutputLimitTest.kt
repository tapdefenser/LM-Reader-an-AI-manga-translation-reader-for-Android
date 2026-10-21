package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

class WorkflowOutputLimitTest {
    @Test fun `both API steps persist render and forward their output limit`() = runTest {
        for(kind in listOf(WorkflowKind.API, WorkflowKind.API_STREAM)) {
            val program = program(kind, WorkflowExpression.Number(12.0))
            val restored = WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(program))
            assertEquals(program, restored)
            assertTrue(WorkflowValidator.validate(restored).valid)
            assertContains(WorkflowSource.render(restored), "最大输出长度=12.0 字符")
            val host = Host()
            WorkflowRuntime().execute(restored, host)
            assertEquals(12, host.calls.single().maxOutputLength)
        }
    }

    @Test fun `old workflows keep an unset limit`() = runTest {
        for(kind in listOf(WorkflowKind.API, WorkflowKind.API_STREAM)) {
            val program = WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(program(kind, null)))
            assertTrue(WorkflowValidator.validate(program).valid)
            assertFalse(WorkflowSource.render(program).contains("最大输出长度"))
            val host = Host()
            WorkflowRuntime().execute(program, host)
            assertNull(host.calls.single().maxOutputLength)
        }
    }

    @Test fun `nonpositive fractional infinite and nonnumeric limits are rejected before execution`() = runTest {
        val invalid = listOf(WorkflowExpression.Number(0.0), WorkflowExpression.Number(-1.0), WorkflowExpression.Number(1.5),
            WorkflowExpression.Number(Double.NaN), WorkflowExpression.Number(Double.POSITIVE_INFINITY),
            WorkflowExpression.Number(Int.MAX_VALUE.toDouble() + 1), WorkflowExpression.Text("12"), WorkflowExpression.Boolean(true))
        for(kind in listOf(WorkflowKind.API, WorkflowKind.API_STREAM)) {
            for(limit in invalid) {
                val program = program(kind, limit)
                assertTrue(WorkflowValidator.validate(program).issues.any { it.nodeId == "api" && it.message == "最大输出长度必须是正整数" })
                val host = Host()
                assertFailsWith<IllegalArgumentException> { WorkflowRuntime().execute(program, host) }
                assertTrue(host.calls.isEmpty())
            }
            for(limit in listOf(1, Int.MAX_VALUE)) {
                assertTrue(WorkflowValidator.validate(program(kind, WorkflowExpression.Number(limit.toDouble()))).valid)
            }
        }
    }

    private fun program(kind: WorkflowKind, limit: WorkflowExpression?): WorkflowProgram {
        val output = WorkflowVariable("output", "结果", WorkflowType.list(WorkflowType.GLOSSARY_ENTRY))
        val api = WorkflowNode("api", kind, target = WorkflowRef(output.id), resultType = output.type,
            variable = if(kind == WorkflowKind.API_STREAM) WorkflowVariable("item", "条目", WorkflowType.GLOSSARY_ENTRY) else null,
            inputs = mapOf("profile" to WorkflowExpression.Text("test"), "prompt" to WorkflowExpression.Text("translate")) +
                if(limit == null) emptyMap() else mapOf("maxOutputLength" to limit))
        return WorkflowEditing.update(WorkflowTemplates.blank(), "manga") {
            it.copy(children = listOf(WorkflowNode("declare", WorkflowKind.DECLARE, variable = output), api) + it.children)
        }
    }

    private class Host : WorkflowRuntimeHost {
        val calls = mutableListOf<WorkflowApiCall>()
        override val manga = WorkflowValue.Record(mapOf("id" to WorkflowValue.Text("manga"), "name" to WorkflowValue.Text("test")))
        override val sourceLanguage = "ja"
        override val targetLanguage = "zh"
        override val style = ""
        override suspend fun chapters() = emptyList<WorkflowValue.Record>()
        override suspend fun pages(chapter: WorkflowValue.Record) = emptyList<WorkflowValue.Record>()
        override suspend fun glossary() = emptyMap<String, String>()
        override suspend fun mergeGlossary(entries: Map<String, String>) = Unit
        override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue = error("unused")
        override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue {
            calls += call
            return WorkflowValue.ListValue(emptyList())
        }
    }
}
