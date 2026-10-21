package com.lmreader.ui.workflow

import com.lmreader.core.model.TranslationPageMode
import com.lmreader.core.model.TranslationWorkflow
import com.lmreader.core.model.WorkflowTemplates
import com.lmreader.core.workflow.WorkflowProgramCodec
import com.lmreader.core.workflow.WorkflowFileCodec
import com.lmreader.core.workflow.WorkflowModernization
import com.lmreader.core.model.WorkflowReferenceTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * The bundled workflow is code-owned. User copies live in a separate, atomically replaced file;
 * task snapshots never point at this mutable file.
 */
class TranslationWorkflowStore(private val root: File) {
    private val mutex = Mutex()
    private val document = File(root, "workflows.json")
    private val _workflows = MutableStateFlow(TranslationWorkflow.BUILT_INS + readUserWorkflows())
    val workflows = _workflows.asStateFlow()
    suspend fun reload() = mutex.withLock { _workflows.value = TranslationWorkflow.BUILT_INS + readUserWorkflows() }

    fun find(id: String?): TranslationWorkflow? =
        if (TranslationWorkflow.isDefaultSelection(id)) TranslationWorkflow.LOCAL_MACHINE
        else _workflows.value.firstOrNull { it.id == id }

    suspend fun create(): TranslationWorkflow = mutate { items ->
        val value = TranslationWorkflow(id = UUID.randomUUID().toString(), revision = 1,
            name = uniqueCopyName("新工作流", items), description = "", pageMode = TranslationPageMode.BUBBLE,
            parallelLimit = 8, retries = 1, program = WorkflowTemplates.blank())
        items + value to value
    }

    suspend fun copy(id: String): TranslationWorkflow = mutate { items ->
        val source = (TranslationWorkflow.BUILT_INS + items).firstOrNull { it.id == id }
            ?: error("Workflow no longer exists")
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            revision = 1,
            name = uniqueCopyName(source.name, items),
            builtIn = false,
        )
        items + copy to copy
    }

    suspend fun update(value: TranslationWorkflow): TranslationWorkflow = mutate { items ->
        require(!value.builtIn && TranslationWorkflow.BUILT_INS.none { it.id == value.id })
        val previous = items.firstOrNull { it.id == value.id } ?: error("Workflow no longer exists")
        require(previous.editable && value.editable == previous.editable) { "固定工作流不能编辑，标识只能在文件外修改" }
        require(value.revision == previous.revision) { "Workflow changed while editing" }
        val changed = value.copy(revision = previous.revision + 1)
        items.map { if (it.id == changed.id) changed else it } to changed
    }

    suspend fun delete(id: String) = mutate { items ->
        require(TranslationWorkflow.BUILT_INS.none { it.id == id })
        require(items.any { it.id == id }) { "Workflow no longer exists" }
        items.filterNot { it.id == id } to Unit
    }
    fun export(id: String): String = WorkflowFileCodec.encode(requireNotNull(find(id)))
    suspend fun import(value: com.lmreader.core.model.TranslationWorkflow): TranslationWorkflow = mutate { items ->
        val imported = value.copy(id = UUID.randomUUID().toString(), revision = 1, builtIn = false,
            name = if(items.none { it.name == value.name }) value.name else uniqueCopyName(value.name, items))
        items + imported to imported
    }
    suspend fun bindApi(id: String, profileId: String): TranslationWorkflow = mutate { items ->
        val previous = items.firstOrNull { it.id == id } ?: error("Workflow no longer exists")
        val next = previous.copy(revision = previous.revision + 1, program = WorkflowReferenceTemplates.bindApi(previous.program, profileId))
        items.map { if(it.id == id) next else it } to next
    }

    private suspend fun <T> mutate(block: (List<TranslationWorkflow>) -> Pair<List<TranslationWorkflow>, T>): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val old = _workflows.value.filterNot { it.builtIn }
                val (next, result) = block(old)
                require(next.size <= MAX_USER_WORKFLOWS && next.map { it.id }.distinct().size == next.size)
                write(next)
                _workflows.value = TranslationWorkflow.BUILT_INS + next
                result
            }
        }

    private fun readUserWorkflows(): List<TranslationWorkflow> {
        if (!document.exists()) return emptyList()
        require(document.length() <= MAX_BYTES) { "Workflow data is too large" }
        val json = JSONObject(document.readText(Charsets.UTF_8))
        require(json.getInt("schema") in 1..2)
        val array = json.getJSONArray("workflows")
        require(array.length() <= MAX_USER_WORKFLOWS)
        val result = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            TranslationWorkflow(
                id = item.getString("id"),
                revision = item.getInt("revision"),
                name = item.getString("name"),
                description = item.getString("description"),
                pageMode = TranslationPageMode.BUBBLE,
                parallelLimit = item.getInt("parallelLimit"),
                retries = item.getInt("retries"),
                program = if (item.has("program")) WorkflowModernization.apply(WorkflowProgramCodec.decode(item.getJSONObject("program").toString())) else WorkflowTemplates.localMachine(),
                editable = item.optBoolean("editable", true),
            ).also { value -> require(TranslationWorkflow.BUILT_INS.none { it.id == value.id }) }
        }
        require(result.map { it.id }.distinct().size == result.size)
        return result
    }

    private fun write(items: List<TranslationWorkflow>) {
        check(root.isDirectory || root.mkdirs())
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("revision", item.revision)
                .put("name", item.name)
                .put("description", item.description)
                .put("pageMode", item.pageMode.name)
                .put("parallelLimit", item.parallelLimit)
                .put("retries", item.retries)
                .put("editable", item.editable)
                .put("program", JSONObject(WorkflowProgramCodec.encode(item.program))))
        }
        val bytes = JSONObject().put("schema", 2).put("workflows", array).toString()
            .toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val temp = File(root, "workflows." + UUID.randomUUID() + ".part")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            check(temp.renameTo(document)) { "Cannot save workflows" }
        } finally {
            temp.delete()
        }
    }

    private fun uniqueCopyName(name: String, items: List<TranslationWorkflow>): String {
        val stem = name.take(66)
        var candidate = "$stem（副本）"
        var number = 2
        while (items.any { it.name == candidate }) candidate = "$stem（副本 " + number++ + "）"
        return candidate
    }

    companion object {
        private const val MAX_USER_WORKFLOWS = 500
        private const val MAX_BYTES = 1_000_000
    }
}
