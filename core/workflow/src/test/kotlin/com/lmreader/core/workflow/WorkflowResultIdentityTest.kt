package com.lmreader.core.workflow

import com.lmreader.core.model.*
import org.junit.Test
import kotlin.test.*

class WorkflowResultIdentityTest {
    private val engineConfiguration = linkedMapOf("temperature" to "0.2", "maxTokens" to "512")

    @Test fun mapInsertionOrderDoesNotChangeIdentity() {
        val first = program(reversedMaps = false)
        val second = program(reversedMaps = true)
        assertNotEquals(
            first.allNodes().single { it.id == "translate" }.inputs.keys.toList(),
            second.allNodes().single { it.id == "translate" }.inputs.keys.toList(),
        )
        assertEquals(
            WorkflowResultIdentity.compute(first, "en", "zh-Hans", "口语",
                linkedMapOf("temperature" to "0.2", "maxTokens" to "512")),
            WorkflowResultIdentity.compute(second, "en", "zh-Hans", "口语",
                linkedMapOf("maxTokens" to "512", "temperature" to "0.2")),
        )
    }

    @Test fun displayMetadataChangesDoNotChangeIdentityOrMutateProgram() {
        val original = program(display = "original")
        val snapshot = original.copy()
        val renamed = program(display = "renamed")
        val identity = WorkflowResultIdentity.compute(original, "en", "zh-Hans", "口语", engineConfiguration)
        assertEquals(identity,
            WorkflowResultIdentity.compute(renamed, "en", "zh-Hans", "口语", engineConfiguration))
        assertEquals(snapshot, original)
        val nodes = original.allNodes().associateBy { it.id }
        assertEquals("翻译-original", nodes.getValue("translate").label)
        assertEquals("译文-original", nodes.getValue("translate").variable!!.name)
        assertEquals("逐气泡-original", nodes.getValue("bubbles").label)
        assertEquals("气泡-original", nodes.getValue("bubbles").variable!!.name)
        assertEquals("校对-original", nodes.getValue("review").label)
        assertEquals("失败提示-original", nodes.getValue("fallback").label)
        assertEquals("翻译-renamed", renamed.allNodes().single { it.id == "translate" }.label)
    }

    @Test fun sourceTargetStylePromptAndEngineConfigurationChangesChangeIdentity() {
        val base = program()
        fun identity(program: WorkflowProgram = base, source: String = "en", target: String = "zh-Hans",
            style: String = "口语", configuration: Map<String, String> = engineConfiguration) =
            WorkflowResultIdentity.compute(program, source, target, style, configuration)
        val baseline = identity()
        assertNotEquals(baseline, identity(source = "ja"))
        assertNotEquals(baseline, identity(target = "zh-Hant"))
        assertNotEquals(baseline, identity(style = "书面"))
        assertNotEquals(baseline, identity(configuration = engineConfiguration + ("temperature" to "0.3")))
        assertNotEquals(baseline, identity(configuration = engineConfiguration + ("topP" to "0.9")))
        val promptChanged = update(base, "translate") { node ->
            node.copy(inputs = node.inputs + ("prompt" to WorkflowExpression.Text("a different prompt")))
        }
        assertNotEquals(baseline, identity(program = promptChanged))
    }

    @Test fun childOrderAndModeChangesChangeIdentity() {
        val base = program()
        val baseline = WorkflowResultIdentity.compute(base, "en", "zh-Hans", "口语", engineConfiguration)
        val reordered = update(base, "bubbles") { it.copy(children = it.children.reversed()) }
        assertNotEquals(baseline,
            WorkflowResultIdentity.compute(reordered, "en", "zh-Hans", "口语", engineConfiguration))
        val async = update(base, "translate") { it.copy(mode = WorkflowMode.ASYNC) }
        assertNotEquals(baseline,
            WorkflowResultIdentity.compute(async, "en", "zh-Hans", "口语", engineConfiguration))
    }

    @Test fun identityIsStableLowercaseSha256() {
        val first = WorkflowResultIdentity.compute(program(), "en", "zh-Hans", "口语", engineConfiguration)
        val second = WorkflowResultIdentity.compute(program(), "en", "zh-Hans", "口语", engineConfiguration)
        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

    private fun program(display: String = "a", reversedMaps: Boolean = false): WorkflowProgram {
        val translated = WorkflowVariable("translated", "译文-$display", WorkflowType.TEXT)
        val prompt = WorkflowExpression.Template(
            text = "将 \${text} 翻译为 \${target}",
            bindings = if (reversedMaps) linkedMapOf(
                "target" to WorkflowRef(WorkflowSystem.TARGET),
                "text" to WorkflowRef(translated.id),
            ) else linkedMapOf(
                "text" to WorkflowRef(translated.id),
                "target" to WorkflowRef(WorkflowSystem.TARGET),
            ),
        )
        val translate = WorkflowNode("translate", WorkflowKind.TRANSLATE, label = "翻译-$display",
            mode = WorkflowMode.SYNC, variable = translated, target = WorkflowRef(translated.id),
            inputs = if (reversedMaps) linkedMapOf(
                "prompt" to prompt,
                "language" to WorkflowSystem.ref(WorkflowSystem.SOURCE),
            ) else linkedMapOf(
                "language" to WorkflowSystem.ref(WorkflowSystem.SOURCE),
                "prompt" to prompt,
            ))
        val review = WorkflowNode("review", WorkflowKind.SET, label = "校对-$display",
            inputs = mapOf("value" to WorkflowExpression.Text("fixed")))
        val fallback = WorkflowNode("fallback", WorkflowKind.MESSAGE, label = "失败提示-$display",
            inputs = mapOf("user" to WorkflowExpression.Text("failed")))
        val loop = WorkflowNode("bubbles", WorkflowKind.EACH, label = "逐气泡-$display",
            variable = WorkflowVariable("bubble", "气泡-$display", WorkflowType.BUBBLE),
            inputs = mapOf("items" to WorkflowSystem.ref(WorkflowSystem.PAGE, "bubbles")),
            children = listOf(translate, review), otherwise = listOf(fallback))
        return WorkflowProgram(rows = listOf(loop))
    }

    private fun update(program: WorkflowProgram, id: String, change: (WorkflowNode) -> WorkflowNode) =
        program.copy(rows = program.rows.map { update(it, id, change) })

    private fun update(node: WorkflowNode, id: String, change: (WorkflowNode) -> WorkflowNode): WorkflowNode {
        val next = if (node.id == id) change(node) else node
        return next.copy(children = next.children.map { update(it, id, change) },
            otherwise = next.otherwise.map { update(it, id, change) })
    }
}
