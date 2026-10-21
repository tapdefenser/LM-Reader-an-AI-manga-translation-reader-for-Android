package com.lmreader.core.model

/** Timed text is real text input: no synthetic images, SEG or comic containers. */
object SubtitleWorkflowTemplates {
    fun program(profileId: String? = null): WorkflowProgram {
        val cue = WorkflowVariable("subtitle-cue", "当前字幕", WorkflowSystem.subtitleType)
        val output = WorkflowVariable("subtitle-results", "字幕成果", WorkflowType.list(WorkflowSystem.subtitleType))
        val translate = if (profileId == null) WorkflowNode("subtitle-translate", WorkflowKind.TRANSLATE,
            target = WorkflowRef(cue.id, listOf("translation")), inputs = mapOf("text" to WorkflowSystem.ref(cue.id, "source"),
                "source" to WorkflowSystem.ref(WorkflowSystem.SOURCE), "target" to WorkflowSystem.ref(WorkflowSystem.TARGET)))
        else WorkflowNode("subtitle-api", WorkflowKind.API, target = WorkflowRef(cue.id, listOf("translation")),
            inputs = mapOf("profile" to WorkflowExpression.Text(profileId), "prompt" to WorkflowExpression.Template(
                "将字幕从\${原文语言}翻译为\${目标语言}。只返回译文，保留换行。文风：\${文风}\n\${原文}", mapOf(
                    "原文语言" to WorkflowRef(WorkflowSystem.SOURCE), "目标语言" to WorkflowRef(WorkflowSystem.TARGET),
                    "文风" to WorkflowRef(WorkflowSystem.STYLE), "原文" to WorkflowRef(cue.id, listOf("source"))))))
        return WorkflowProgram(inputScenario = WorkflowInputScenario.TIMED_TEXT, rows = listOf(
            WorkflowNode("subtitle-declare", WorkflowKind.DECLARE, variable = output),
            WorkflowNode("subtitle-each", WorkflowKind.EACH, variable = cue, mode = WorkflowMode.ASYNC, parallelLimit = 2,
                inputs = mapOf("items" to WorkflowSystem.ref(WorkflowSystem.TEXT_ENTRIES), "collectValue" to WorkflowSystem.ref(cue.id)),
                collectTo = WorkflowRef(output.id), children = listOf(translate)),
            WorkflowNode("subtitle-return", WorkflowKind.RETURN, resultType = output.type, inputs = mapOf("value" to WorkflowSystem.ref(output.id)))))
    }
}
