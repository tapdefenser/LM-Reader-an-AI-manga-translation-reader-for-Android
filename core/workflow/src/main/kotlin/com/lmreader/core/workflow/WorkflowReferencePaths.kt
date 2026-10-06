package com.lmreader.core.workflow

import com.lmreader.core.model.*

/** List counts are computed fields; a record field named count remains an ordinary field. */
internal object WorkflowReferencePaths {
    const val ITEM_COUNT = "count"
    data class Resolved(val type: WorkflowType, val readOnly: Boolean)

    fun resolve(root: WorkflowType, path: List<String>): Resolved? {
        var type = root
        var readOnly = false
        for (field in path) {
            type = if (type.kind == WorkflowDataKind.LIST) when {
                field == ITEM_COUNT -> { readOnly = true; WorkflowType.NUMBER }
                field.toIntOrNull()?.let { it >= 0 } == true -> type.element ?: return null
                else -> return null
            } else type.fields[field] ?: return null
        }
        return Resolved(type, readOnly)
    }
}
