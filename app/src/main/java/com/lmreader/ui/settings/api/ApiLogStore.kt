package com.lmreader.ui.settings.api

import com.lmreader.core.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ApiLogRecord(val id: String, val started: Long, val ended: Long?, val info: ApiRequestInfo, val outcome: ApiRequestOutcome,
    val outputError: String = "")

/** App-private bounded journal. Summary flow carries no request/response bodies. */
class ApiLogStore(private val root: File) : ApiRequestJournal {
    private val mutex = Mutex()
    private val entries = MutableStateFlow(readExisting())
    val records = entries.asStateFlow()
    override suspend fun begin(info: ApiRequestInfo): String = withContext(Dispatchers.IO) { mutex.withLock {
        val record = ApiLogRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), null, info, ApiRequestOutcome("REQUESTING"))
        write(record); entries.value = listOf(summary(record)) + entries.value; prune(); record.id
    } }
    override suspend fun finish(id: String, outcome: ApiRequestOutcome) = withContext(Dispatchers.IO) { mutex.withLock {
        val old = read(id) ?: return@withLock
        val record = old.copy(ended = System.currentTimeMillis(), outcome = if(old.outputError.isEmpty()) outcome
            else outcome.copy(status = "FAILED", error = "输出校验失败：${old.outputError}"))
        write(record); entries.value = entries.value.map { if(it.id == id) summary(record) else it }; prune()
    } }
    suspend fun recordOutputFailure(id: String, error: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val old = read(id) ?: return@withLock
        val detail = error.take(4096)
        val record = old.copy(ended = old.ended ?: System.currentTimeMillis(), outputError = detail,
            outcome = old.outcome.copy(status = "FAILED", error = "输出校验失败：$detail"))
        write(record); entries.value = entries.value.map { if(it.id == id) summary(record) else it }
    } }
    suspend fun detail(id: String): ApiLogRecord? = withContext(Dispatchers.IO) { mutex.withLock { read(id) } }
    private fun summary(r: ApiLogRecord) = r.copy(info = r.info.copy(request = ""), outcome = r.outcome.copy(response = "", thinking = ""))
    private fun file(id: String): File { require(id.matches(Regex("[a-f0-9-]{36}"))); return File(root, "$id.json") }
    private fun read(id: String): ApiLogRecord? = runCatching {
        val f = file(id); if(!f.isFile || f.length() > 16_000_000) return@runCatching null
        val j = JSONObject(f.readText()); val c = j.getJSONObject("context")
        ApiLogRecord(id, j.getLong("started"), if(j.isNull("ended")) null else j.getLong("ended"), ApiRequestInfo(
            ApiTraceContext(c.getString("mangaId"), c.getString("mangaName"), c.getString("chapterName"), c.getString("pageName"), c.getString("stepName")),
            j.getString("profileName"), j.getString("model"), j.getString("url"), j.getString("method"), j.getString("format"), j.getInt("attempt"), j.getString("request")),
            ApiRequestOutcome(j.getString("status"), j.getString("response"), j.getString("thinking"), j.getString("error"), if(j.isNull("httpCode")) null else j.getInt("httpCode")), j.optString("outputError"))
    }.getOrNull()
    private fun write(r: ApiLogRecord) {
        check(root.isDirectory || root.mkdirs())
        val c = r.info.context; val o = r.outcome
        val j = JSONObject().put("id", r.id).put("started", r.started).put("ended", r.ended ?: JSONObject.NULL)
            .put("context", JSONObject().put("mangaId", c.mangaId).put("mangaName", c.mangaName).put("chapterName", c.chapterName).put("pageName", c.pageName).put("stepName", c.stepName))
            .put("profileName", r.info.profileName).put("model", r.info.model).put("url", r.info.url).put("method", r.info.method).put("format", r.info.format)
            .put("attempt", r.info.attempt).put("request", r.info.request.take(1_000_000)).put("status", o.status)
            .put("response", o.response.take(4_000_000)).put("thinking", o.thinking.take(1_000_000)).put("error", o.error.take(4096)).put("httpCode", o.httpCode ?: JSONObject.NULL)
            .put("outputError", r.outputError.take(4096))
        val temp = File(root, r.id + ".part")
        try { FileOutputStream(temp).use { it.write(j.toString().toByteArray()); it.fd.sync() }; check(temp.renameTo(file(r.id))) }
        finally { temp.delete() }
    }
    private fun readExisting(): List<ApiLogRecord> {
        val read = root.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9-]{36}\\.json")) }.mapNotNull { f ->
            read(f.nameWithoutExtension)?.let { record ->
                if(record.outcome.status == "REQUESTING") record.copy(ended = System.currentTimeMillis(), outcome = record.outcome.copy(status = "INTERRUPTED")).also { write(it) } else record
            }?.let(::summary)
        }.sortedByDescending { it.started }
        return read
    }
    private fun prune() {
        var size = entries.value.sumOf { file(it.id).length() }
        val kept = entries.value.toMutableList()
        for(record in entries.value.asReversed()) {
            if(kept.size <= 500 && size <= 64_000_000) break
            if(record.outcome.status == "REQUESTING") continue
            val f = file(record.id); val bytes = f.length()
            if(f.delete()) { size -= bytes; kept.remove(record) }
        }
        entries.value = kept
    }
}
