package com.lmreader.screen

import android.util.AtomicFile
import com.lmreader.core.model.PixelRect
import com.lmreader.core.model.ScreenTranslationSettings
import com.lmreader.core.storage.settings.ScreenTranslationPreferences
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

internal data class PendingScreenFrame(val pageId: String, val image: File, val bounds: PixelRect,
    val settings: ScreenTranslationSettings, val snapshot: String)

/** Failed/cancelled admitted inputs survive restart, together with the pinned SEG/OCR. */
internal class ScreenPendingFrames(private val root: File) {
    val count = MutableStateFlow(0)
    init {
        root.mkdirs()
        root.listFiles().orEmpty().filter { it.name.endsWith(".json.bak") }.forEach { backup ->
            runCatching { AtomicFile(File(root, backup.name.removeSuffix(".bak"))).openRead().close() }
        }
        root.listFiles().orEmpty().filter { it.name.endsWith(".png.new") }.forEach { unfinished ->
            val id = unfinished.name.removeSuffix(".png.new")
            if (listOf(".json", ".json.bak").none { File(root, id + it).exists() }) unfinished.delete()
        }
        // Only files that never acquired an admission journal can be removed.
        // AtomicFile may still need its .new/.bak journal after a process crash.
        root.listFiles().orEmpty().filter { it.extension == "png" }.forEach { image ->
            if (listOf(".json", ".json.bak").none { File(root, image.nameWithoutExtension + it).exists() }) {
                image.delete(); File(root, image.nameWithoutExtension + ".json.new").delete()
            }
        }
        refresh()
    }
    fun file(pageId: String): File { val id = pageId.removePrefix("screen:"); require(id.matches(Regex("[a-f0-9-]{36}"))); return File(root, "$id.png") }
    fun bytes() = root.listFiles().orEmpty().sumOf { it.length() }
    fun writeImage(pageId: String, write: (java.io.OutputStream) -> Unit) {
        val atomic = AtomicFile(file(pageId))
        val stream = atomic.startWrite()
        try { write(stream); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }
    fun admit(frame: PendingScreenFrame) {
        val json = JSONObject().put("id", frame.pageId).put("bounds", org.json.JSONArray(listOf(frame.bounds.left, frame.bounds.top, frame.bounds.right, frame.bounds.bottom)))
            .put("settings", JSONObject(ScreenTranslationPreferences.encode(frame.settings))).put("snapshot", JSONObject(frame.snapshot))
        val atomic = AtomicFile(File(root, frame.image.nameWithoutExtension + ".json"))
        val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
        refresh()
    }
    fun complete(pageId: String) {
        val image = file(pageId); File(root, image.nameWithoutExtension + ".json").delete(); image.delete(); refresh()
    }
    fun latest(): PendingScreenFrame? = root.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }.firstNotNullOfOrNull { metadata ->
        runCatching {
            require(metadata.length() <= 1_000_000)
            val j = JSONObject(AtomicFile(metadata).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }); val id = j.getString("id"); val image = file(id)
            require(image.isFile)
            val b = j.getJSONArray("bounds")
            PendingScreenFrame(id, image, PixelRect(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat()),
                ScreenTranslationPreferences.decode(j.getJSONObject("settings").toString()), j.getJSONObject("snapshot").toString())
        }.getOrNull()
    }
    private fun refresh() { count.value = root.listFiles().orEmpty().count { it.extension == "json" } }
}
