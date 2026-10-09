package com.lmreader.core.vision

import android.util.AtomicFile
import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Content-addressed SEG + text-line detection + region OCR. Never caches decoded bitmaps. */
class VisionPreprocessingCache(private val root: File, private val limitBytes: () -> Long) {
    private val stripes = List(32) { Mutex() }
    private val diskLock = Any()
    private val retainedPages = mutableMapOf<String, Int>()
    private val keyPages = mutableMapOf<String, String>()
    private val pendingPages = mutableSetOf<String>()
    private val mutableBytes = MutableStateFlow(0L)
    val bytes = mutableBytes.asStateFlow()
    private val mutableHits = MutableStateFlow(0)
    val hits = mutableHits.asStateFlow()
    init { root.mkdirs(); synchronized(diskLock) {
        runCatching {
            val saved = JSONObject(AtomicFile(File(root, "pending.json")).openRead().bufferedReader().use { it.readText() })
            saved.keys().forEach { key -> if(key.matches(Regex("[a-f0-9]{64}"))) {
                val page = saved.getString(key); keyPages[key] = page; pendingPages += page
            } }
        }
        prune()
    } }
    /** Durable pins survive a failed API or process restart; only publication clears them. */
    fun markPendingPage(pageId: String) = synchronized(diskLock) { pendingPages += pageId; savePending() }
    fun commitPage(pageId: String) = synchronized(diskLock) { pendingPages -= pageId; savePending(); prune() }
    private fun rememberKey(key: String, pageId: String) = synchronized(diskLock) {
        keyPages[key] = pageId
        if(pageId in pendingPages) savePending()
    }
    private fun savePending() {
        val json = JSONObject()
        keyPages.filterValues { it in pendingPages }.forEach { (key, page) -> json.put(key, page) }
        val atomic = AtomicFile(File(root, "pending.json"))
        runCatching {
            val output = atomic.startWrite()
            try { output.write(json.toString().toByteArray()); atomic.finishWrite(output) }
            catch(failure: Throwable) { atomic.failWrite(output); throw failure }
        }
    }
    fun retainPage(pageId: String) = synchronized(diskLock) { retainedPages[pageId] = (retainedPages[pageId] ?: 0) + 1 }
    fun releasePage(pageId: String) = synchronized(diskLock) {
        val count = (retainedPages[pageId] ?: 1) - 1
        if (count <= 0) retainedPages.remove(pageId) else retainedPages[pageId] = count
        prune()
    }

    suspend fun segment(imageId: String, sourceHash: String, width: Int, height: Int, threshold: Float,
        detectionThreshold: Float, compute: suspend () -> SegResult): SegResult {
        val key = key("seg", imageId, sourceHash, width, height, threshold, detectionThreshold)
        rememberKey(key, imageId)
        return stripes[(key.hashCode() and Int.MAX_VALUE) % stripes.size].withLock {
            val cached = read(key)?.let { json -> runCatching {
                require(json.getString("imageId") == imageId && json.getInt("width") == width && json.getInt("height") == height)
                SegResult(imageId, width, height, json.getJSONArray("regions").objects().map { item ->
                    SegRegion(item.getString("id"), RegionKind.valueOf(item.getString("kind")), rect(item.getJSONArray("bounds")),
                        item.getDouble("confidence").toFloat(), item.getJSONArray("contour").arrays().map { PixelPoint(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) },
                        item.optJSONArray("extraction")?.let(::rect))
                }, 0, json.getString("backend"), json.getJSONArray("lines").objects().map {
                    DetectedTextLine(rect(it.getJSONArray("bounds")), it.getDouble("confidence").toFloat())
                })
            }.getOrNull() }
            if (cached != null) { mutableHits.update { it + 1 }; return@withLock cached }
            val result = compute()
            currentCoroutineContext().ensureActive()
            write(key, JSONObject().put("imageId", imageId).put("width", width).put("height", height).put("backend", result.backend)
                .put("regions", JSONArray().apply { result.regions.forEach { r -> put(JSONObject().put("id", r.id).put("kind", r.kind.name)
                    .put("bounds", rect(r.bounds)).put("confidence", r.confidence.toDouble())
                    .put("contour", JSONArray().apply { r.contour.forEach { put(JSONArray(listOf(it.x, it.y))) } })
                    .put("extraction", r.extractionBounds?.let(::rect))) } })
                .put("lines", JSONArray().apply { result.textLines.forEach { put(JSONObject().put("bounds", rect(it.bounds)).put("confidence", it.confidence.toDouble())) } }))
            result
        }
    }

    suspend fun recognize(imageId: String, sourceHash: String, language: LocalOcrLanguage, region: SegRegion,
        pageRegions: List<SegRegion>, textLines: List<DetectedTextLine>?, compute: suspend () -> LocalOcrResult): LocalOcrResult {
        // Generated bubble IDs change on each SEG run; geometry and detector lines identify the crop.
        val geometry = listOf(region.kind, region.bounds, region.contour, region.extractionBounds,
            pageRegions.map { listOf(it.kind, it.bounds, it.contour) }, textLines)
        val key = key("ocr", imageId, sourceHash, language, geometry)
        rememberKey(key, imageId)
        return stripes[(key.hashCode() and Int.MAX_VALUE) % stripes.size].withLock {
            val cached = read(key)?.let { json -> runCatching {
                LocalOcrResult(imageId, json.getInt("width"), json.getInt("height"), language,
                    json.getJSONArray("lines").objects().map { OcrLine(it.getString("id"), rect(it.getJSONArray("bounds")),
                        it.getString("text"), it.getDouble("confidence").toFloat()) }, 0)
            }.getOrNull() }
            if (cached != null) { mutableHits.update { it + 1 }; return@withLock cached }
            val result = compute()
            currentCoroutineContext().ensureActive()
            write(key, JSONObject().put("width", result.width).put("height", result.height).put("lines", JSONArray().apply {
                result.lines.forEach { put(JSONObject().put("id", it.id).put("bounds", rect(it.bounds)).put("text", it.text).put("confidence", it.confidence.toDouble())) }
            }))
            result
        }
    }

    suspend fun trim() = withContext(Dispatchers.IO) { synchronized(diskLock) { prune() } }
    private fun key(vararg values: Any?) = hash((listOf(VERSION, VisionModels.hashes.toSortedMap()) + values).joinToString("|").toByteArray())
    private fun read(key: String): JSONObject? = synchronized(diskLock) {
        val file = File(root, "$key.json")
        runCatching {
            if (!file.isFile) return@synchronized null
            val envelope = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            val body = envelope.getString("body")
            require(envelope.getString("sha256") == hash(body.toByteArray()))
            file.setLastModified(System.currentTimeMillis())
            JSONObject(body)
        }.getOrElse { file.delete(); null }
    }
    private fun write(key: String, value: JSONObject) = synchronized(diskLock) {
        runCatching {
            val body = value.toString()
            val contents = JSONObject().put("sha256", hash(body.toByteArray())).put("body", body).toString().toByteArray()
            val atomic = AtomicFile(File(root, "$key.json"))
            val output = atomic.startWrite()
            try { output.write(contents); atomic.finishWrite(output) }
            catch (failure: Throwable) { atomic.failWrite(output); throw failure }
            prune()
        }
        Unit
    }
    private fun prune() {
        val files = root.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }.sortedBy { it.lastModified() }
        var used = files.sumOf { it.length() }
        for (file in files) {
            if (used <= limitBytes().coerceAtLeast(0)) break
            if (keyPages[file.nameWithoutExtension]?.let { retainedPages.containsKey(it) || it in pendingPages } == true) continue
            val size = file.length(); if (file.delete()) used -= size
        }
        val existing = root.listFiles().orEmpty().map { it.nameWithoutExtension }.toSet()
        // Another stripe can still be computing a registered key. Its pending
        // ownership must survive this writer's trim before that file exists.
        keyPages.entries.removeAll { (key, page) -> key !in existing && page !in pendingPages && page !in retainedPages }
        mutableBytes.value = used
    }
    private fun rect(value: PixelRect) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
    private fun rect(value: JSONArray) = PixelRect(value.getDouble(0).toFloat(), value.getDouble(1).toFloat(), value.getDouble(2).toFloat(), value.getDouble(3).toFloat())
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.arrays() = (0 until length()).map { getJSONArray(it) }
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    companion object { private const val VERSION = 1 }
}
