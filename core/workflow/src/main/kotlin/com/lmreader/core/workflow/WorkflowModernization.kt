package com.lmreader.core.workflow

import com.lmreader.core.model.*

/** Saved queue snapshots remain executable; editable files use the smaller current vocabulary. */
object WorkflowModernization {
    /** Old MERGE_LIST text concatenation is exactly APPEND, including saved queue snapshots. */
    fun migrateTextMerges(program: WorkflowProgram): WorkflowProgram {
        if (!program.uses(WorkflowKind.MERGE_LIST)) return program
        val scopes = WorkflowValidator.validate(program).scopes
        fun row(node: WorkflowNode): WorkflowNode {
            val target = node.target
            val type = target?.let { ref -> scopes[node.id].orEmpty().lastOrNull { it.variable.id == ref.variableId }
                ?.variable?.type?.let { WorkflowReferencePaths.resolve(it, ref.path)?.type } }
            return node.copy(kind = if (node.kind == WorkflowKind.MERGE_LIST && type == WorkflowType.TEXT) WorkflowKind.APPEND else node.kind,
                children = node.children.map(::row), otherwise = node.otherwise.map(::row))
        }
        return program.copy(rows = program.rows.map(::row))
    }

    fun apply(program: WorkflowProgram): WorkflowProgram {
        fun row(n: WorkflowNode): WorkflowNode {
            var changed = n.copy(children = n.children.map(::row), otherwise = n.otherwise.map(::row))
            if(n.kind == WorkflowKind.PREPARE_MANGA) changed = changed.copy(kind = WorkflowKind.EACH,
                label = "识别漫画各页", variable = WorkflowVariable(WorkflowSystem.PAGE, "本页", WorkflowSystem.pageType), target = null,
                collectTo = n.target, inputs = mapOf("items" to WorkflowSystem.ref(WorkflowSystem.ALL_PAGES),
                    "publish" to WorkflowExpression.Boolean(false), "flatten" to WorkflowExpression.Boolean(true), "collectValue" to WorkflowSystem.ref(WorkflowSystem.PAGE, "records")))
            if(changed.kind == WorkflowKind.EACH && changed.children.lastOrNull()?.kind == WorkflowKind.RETURN) {
                val value = changed.children.last().inputs.getValue("value")
                changed = changed.copy(children = changed.children.dropLast(1), inputs = changed.inputs + ("collectValue" to value))
            }
            return changed
        }
        return migrateTextMerges(program.copy(rows = program.rows.map(::row)))
    }
}
