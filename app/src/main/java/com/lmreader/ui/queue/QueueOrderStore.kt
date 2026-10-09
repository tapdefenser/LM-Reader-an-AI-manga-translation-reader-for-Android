package com.lmreader.ui.queue

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Independent positions for manga groups and chapter rows; a sort writes one order then drag can refine it. */
class QueueOrderStore(private val file: File) {
    private val atomic = AtomicFile(file)
    private var manga = mutableListOf<String>()
    private var chapters = mutableMapOf<String, MutableList<String>>()
    private var paused = false
    private var scheduling = TranslationSchedulingPriority.RESOURCES
    private data class ExcludedPages(val queuedAt: Long, val pages: MutableSet<String>)
    private val excluded = mutableMapOf<String, ExcludedPages>()

    init {
        reload()
    }
    @Synchronized fun reload() {
        manga.clear(); chapters.clear(); excluded.clear(); paused = false; scheduling = TranslationSchedulingPriority.RESOURCES
        runCatching {
            val root = JSONObject(atomic.openRead().bufferedReader().use { it.readText() })
            paused = root.optBoolean("paused", false)
            scheduling = TranslationSchedulingPriority.entries.firstOrNull { it.name == root.optString("schedulingPriority") }
                ?: TranslationSchedulingPriority.RESOURCES
            manga = root.getJSONArray("manga").strings().toMutableList()
            val groups = root.getJSONObject("chapters")
            groups.keys().forEach { id -> chapters[id] = groups.getJSONArray(id).strings().toMutableList() }
            root.optJSONObject("excludedPages")?.let { saved -> saved.keys().forEach { id ->
                val item = saved.getJSONObject(id)
                excluded[id] = ExcludedPages(item.getLong("queuedAt"), item.getJSONArray("pages").strings().toMutableSet())
            } }
        }
    }

    @Synchronized fun mangaOrder(ids: List<String>): List<String> = order(manga, ids)
    @Synchronized fun chapterOrder(mangaId: String, ids: List<String>): List<String> =
        order(chapters.getOrPut(mangaId) { mutableListOf() }, ids)

    @Synchronized fun moveManga(id: String, delta: Int, visible: List<String>) {
        manga = move(mangaOrder(visible), id, delta).toMutableList()
        save()
    }

    @Synchronized fun moveChapter(mangaId: String, id: String, delta: Int, visible: List<String>) {
        chapters[mangaId] = move(chapterOrder(mangaId, visible), id, delta).toMutableList()
        save()
    }

    @Synchronized fun sortManga(ids: List<String>) { manga = ids.toMutableList(); save() }
    @Synchronized fun sortChapters(mangaId: String, ids: List<String>) {
        chapters[mangaId] = ids.toMutableList(); save()
    }
    @Synchronized fun isPaused(): Boolean = paused
    @Synchronized fun setPaused(value: Boolean) { paused = value; save() }
    @Synchronized fun schedulingPriority() = scheduling
    @Synchronized fun setSchedulingPriority(value: TranslationSchedulingPriority) { scheduling = value; save() }
    @Synchronized fun excludePage(chapterId: String, queuedAt: Long, pageId: String) {
        val entry = excluded[chapterId]?.takeIf { it.queuedAt == queuedAt }
            ?: ExcludedPages(queuedAt, mutableSetOf()).also { excluded[chapterId] = it }
        entry.pages += pageId; save()
    }
    @Synchronized fun isPageExcluded(chapterId: String, queuedAt: Long, pageId: String): Boolean =
        excluded[chapterId]?.takeIf { it.queuedAt == queuedAt }?.pages?.contains(pageId) == true
    @Synchronized fun includePage(chapterId: String, pageId: String) {
        if (excluded[chapterId]?.pages?.remove(pageId) == true) save()
    }

    private fun order(saved: List<String>, ids: List<String>): List<String> =
        saved.mapNotNull { old -> old.takeIf { it in ids } ?: old.substringBefore('@').takeIf { it in ids } }
            .distinct().let { retained -> retained + ids.filterNot { it in retained } }

    private fun move(ids: List<String>, id: String, delta: Int): List<String> {
        val index = ids.indexOf(id)
        if (index < 0 || index + delta !in ids.indices) return ids
        return ids.toMutableList().apply { add(index + delta, removeAt(index)) }
    }

    private fun save() {
        val groupJson = JSONObject()
        chapters.forEach { (id, entries) -> groupJson.put(id, JSONArray(entries)) }
        val excludedJson = JSONObject()
        excluded.forEach { (id, entry) -> excludedJson.put(id, JSONObject().put("queuedAt", entry.queuedAt).put("pages", JSONArray(entry.pages.toList()))) }
        val bytes = JSONObject().put("manga", JSONArray(manga)).put("chapters", groupJson).put("paused", paused)
            .put("schedulingPriority", scheduling.name)
            .put("excludedPages", excludedJson)
            .toString().toByteArray(Charsets.UTF_8)
        ExportFiles.commit(file) { it.write(bytes) }
    }
}

private fun JSONArray.strings() = (0 until length()).map { getString(it) }
