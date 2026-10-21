package com.lmreader.ui.workflow

import com.lmreader.core.model.*
import com.lmreader.core.workflow.*
import org.junit.Assert.*
import org.junit.Test

class WorkflowNodeDefaultsTest {
    @Test fun mergeSelectsCompatibleListRatherThanText() {
        val type = WorkflowType.list(WorkflowType.IMAGE)
        val text = WorkflowReferenceChoice(WorkflowRef("text"), "文本", WorkflowType.TEXT, false)
        val list = WorkflowReferenceChoice(WorkflowRef("list"), "图片", type, false)
        val source = list.copy(ref = WorkflowRef("source"))
        val node = newNode(WorkflowKind.MERGE_LIST, listOf(text, list, source), emptyList())
        assertEquals(list.ref, node.target)
        assertEquals(type, node.resultType)
        assertEquals(WorkflowExpression.Ref(source.ref), node.inputs["value"])
    }

    @Test fun mergeWithoutListHasNoMisleadingTextTarget() {
        val text = WorkflowReferenceChoice(WorkflowRef("text"), "文本", WorkflowType.TEXT, false)
        val node = newNode(WorkflowKind.MERGE_LIST, listOf(text), emptyList())
        assertNull(node.target)
        assertEquals(WorkflowType.list(WorkflowType.TEXT), node.resultType)
        assertEquals(WorkflowExpression.Empty(node.resultType), node.inputs["value"])
    }

    @Test fun asyncParentListUsesExplicitAppendTargets() {
        val scope = listOf(WorkflowAvailableVariable(WorkflowVariable("outer", "外层列表", WorkflowType.list(WorkflowType.TEXT)), readOnly = true))
        val node = newNode(WorkflowKind.MERGE_LIST, WorkflowEditing.references(scope), emptyList(), WorkflowEditing.writeTargets(scope, append = true))
        assertEquals(WorkflowRef("outer"), node.target)
        assertEquals(WorkflowExpression.Empty(WorkflowType.list(WorkflowType.TEXT)), node.inputs["value"])
    }

    @Test fun systemPageListCannotBecomeDefaultMergeDestination() {
        val scope = WorkflowEditing.scope(WorkflowTemplates.blank(), WorkflowPosition("manga", 0))
        val node = newNode(WorkflowKind.MERGE_LIST, WorkflowEditing.references(scope), emptyList(), WorkflowEditing.writeTargets(scope, append = true))
        assertNull(node.target)
        assertEquals(WorkflowType.list(WorkflowType.TEXT), node.resultType)
    }
}
