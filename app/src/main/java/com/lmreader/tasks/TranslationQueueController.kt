package com.lmreader.tasks

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.database.tasks.TaskRecord
import com.lmreader.core.index.NaturalOrder
import com.lmreader.core.model.ResourceGovernor
import com.lmreader.di.AppContainer
import com.lmreader.ui.queue.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

val TRANSLATION_TASK_TYPES = setOf("CHAPTER", "READER_PAGE", "SCREEN", "VIDEO", "VIDEO_FRAMES", "VIDEO_SUBTITLES")
val REMOVED_FROM_TRANSLATION_QUEUE = setOf("SUCCEEDED", "CANCELLED")
fun TaskRecord.isTranslation() = type in TRANSLATION_TASK_TYPES
fun TaskRecord.inTranslationQueue() = isTranslation() && state !in REMOVED_FROM_TRANSLATION_QUEUE
internal fun TaskRecord.translationGroupKey(): String {
    val json = runCatching { JSONObject(payload) }.getOrNull()
    return when (type) {
        "CHAPTER", "READER_PAGE" -> json?.optString("mangaId")?.takeIf { it.isNotBlank() }?.let { "manga:$it" }
        "VIDEO", "VIDEO_FRAMES", "VIDEO_SUBTITLES" -> json?.optString("videoId")?.takeIf { it.isNotBlank() }?.let { "video:$it" }
        else -> null
    } ?: "task:${parentTaskId ?: taskId}"
}

data class TranslationQueueGroup(val key: String, val title: String, val mangaId: String?,
    val tasks: List<TaskRecord>, val chapters: List<ChapterTranslationEntity>) {
    val roots get() = tasks.filter { it.parentTaskId == null }
    val priority get() = tasks.maxOfOrNull { it.priority } ?: 0
}

/** One projection for the page and badge. Terminal history remains in the durable ledger. */
fun translationQueueGroups(rows: List<TaskRecord>, chapters: List<ChapterTranslationEntity>): List<TranslationQueueGroup> {
    val active = rows.filter { it.inTranslationQueue() }
    val grouped = active.groupBy { it.translationGroupKey() }.toMutableMap()
    val pendingChapters = chapters.filter { it.state !in setOf("DONE", "CANCELLED") }.groupBy { "manga:${it.mangaId}" }
    pendingChapters.keys.forEach { grouped.putIfAbsent(it, emptyList()) }
    return grouped.map { (key, tasks) ->
        val mangaId = key.removePrefix("manga:").takeIf { key.startsWith("manga:") }
        TranslationQueueGroup(key, tasks.firstOrNull { it.type in setOf("CHAPTER", "VIDEO") }?.title
            ?: tasks.firstOrNull()?.title ?: mangaId.orEmpty(), mangaId, tasks, pendingChapters[key].orEmpty())
    }.sortedWith(compareByDescending<TranslationQueueGroup> { it.priority }
        .thenBy { it.roots.minOfOrNull { row -> row.orderIndex } ?: Long.MAX_VALUE }.thenBy { it.key })
}

/** Commands retain chapter publication guards and use the same scheduler for every translation type. */
class TranslationQueueController(private val container: AppContainer) {
    private val scheduler get() = container.taskScheduler
    private val chapters get() = container.translationQueue
    // Navigation may cancel its UI scope after the command has already persisted an intent.
    private suspend fun command(action: suspend () -> Unit) = withContext(container.backgroundScope.coroutineContext) {
        scheduler.translationCommands.withLock { action() }
    }
    private suspend fun orderedGroups(): List<TranslationQueueGroup> {
        chapters.awaitReady()
        return translationQueueGroups(scheduler.dao.all(), chapters.ordered(container.database.translationDao().queueSnapshot()))
    }
    suspend fun pauseAll() = command { scheduler.withTranslationAdmissionHeld {
        enableTargetedStart()
        for(group in orderedGroups()) pauseGroupNow(group)
    } }
    suspend fun continueAll() = command {
        enableTargetedStart()
        for(group in orderedGroups()) startGroupNow(group)
    }
    suspend fun cancelAll() = command { scheduler.withTranslationAdmissionHeld {
        chapters.awaitReady()
        scheduler.markCancelling(scheduler.dao.all().filter { it.inTranslationQueue() }.map { it.taskId })
        container.database.translationDao().queueSnapshot().map { it.mangaId }.distinct().forEach { chapters.cancelManga(it).join() }
        scheduler.dao.all().filter { it.inTranslationQueue() && it.parentTaskId == null }
            .forEach { scheduler.pause(it.taskId, cancel = true) }
    } }
    suspend fun pauseGroup(group: TranslationQueueGroup) = command { enableTargetedStart(); pauseGroupNow(group) }
    private suspend fun pauseGroupNow(group: TranslationQueueGroup) {
        group.mangaId?.let { chapters.setMangaPaused(it, true).join() }
        group.roots.filter { it.type != "CHAPTER" }.forEach { scheduler.pause(it.taskId) }
    }
    suspend fun startGroup(group: TranslationQueueGroup) = command { enableTargetedStart(); startGroupNow(group) }
    private suspend fun startGroupNow(group: TranslationQueueGroup) {
        group.mangaId?.let { chapters.setMangaPaused(it, false).join() }
        group.tasks.filter { it.state != "FAILED" && !it.remoteOutcomeUnknown }.forEach { scheduler.resume(it.taskId) }
    }
    suspend fun cancelGroup(group: TranslationQueueGroup) = command {
        group.mangaId?.let { chapters.cancelManga(it).join() }
        group.roots.forEach { scheduler.pause(it.taskId, cancel = true) }
    }
    private suspend fun enableTargetedStart() {
        chapters.awaitReady()
        chapters.clearQueuePauseIntent()
    }
    suspend fun startChapter(item: ChapterTranslationEntity) = command {
        enableTargetedStart()
        if(item.state == "INTERRUPTED") chapters.retry(item).join() else chapters.setChapterPaused(item, false).join()
    }
    suspend fun retryChapter(item: ChapterTranslationEntity) = command { enableTargetedStart(); chapters.retry(item).join() }
    suspend fun startTask(id: String) = command { enableTargetedStart(); scheduler.resume(id) }
    suspend fun retryTask(id: String) = command { enableTargetedStart(); scheduler.resume(id, retryFailed = true) }
    suspend fun retryGroup(group: TranslationQueueGroup) = command {
        enableTargetedStart()
        group.chapters.filter { it.state == "FAILED" }.forEach { chapters.retry(it).join() }
        group.roots.filter { it.state == "FAILED" && !it.remoteOutcomeUnknown }.forEach { scheduler.resume(it.taskId, retryFailed = true) }
    }
    suspend fun moveTask(row: TaskRecord, delta: Int) {
        if(row.parentTaskId == null) moveGroup(row.translationGroupKey(), delta) else scheduler.move(row.taskId, delta)
    }
    suspend fun moveGroup(key: String, delta: Int) {
        val keys = container.queueOrder.translationGroupOrder(scheduler.dao.all()
            .filter { it.inTranslationQueue() && it.parentTaskId == null }.map { it.translationGroupKey() }.distinct()).toMutableList()
        val from = keys.indexOf(key); val to = from + delta
        if (from < 0 || to !in keys.indices) return
        keys.add(to, keys.removeAt(from)); scheduler.orderTranslationGroups(keys)
    }
    suspend fun sort(mode: String, ascending: Boolean = mode != "修改时间") = withContext(Dispatchers.IO) {
        chapters.awaitReady()
        require(mode in setOf("自然数", "修改时间", "首字母"))
        val groups = translationQueueGroups(scheduler.dao.all(), chapters.items.value)
        val names = container.mangaRepository.getCards(groups.mapNotNull { it.mangaId }).associate { it.mangaId to it.displayName }
        val details = groups.mapNotNull { it.mangaId }.associateWith { container.mangaRepository.getChaptersInDisplayOrder(it) }
        val titles = groups.associate { it.key to (names[it.mangaId] ?: it.title) }
        val modified = if(mode == "修改时间") groups.associate { group -> group.key to
            (group.mangaId?.let { id -> details[id].orEmpty().filter { chapter -> group.chapters.any { it.chapterId == chapter.chapterId } }
                .mapNotNull { it.modifiedAt }.maxOrNull() } ?: group.tasks.firstOrNull()?.let { sourceModified(it) }) } else emptyMap()
        fun compare(a: String, b: String, aTime: Long?, bTime: Long?): Int {
            // Unknown timestamps stay last in both directions.
            if(mode == "修改时间" && (aTime == null || bTime == null)) return when {
                aTime == bTime -> 0; aTime == null -> 1; else -> -1
            }
            val value = when(mode) {
                "自然数" -> NaturalOrder.compare(a, b)
                "修改时间" -> compareValues(aTime, bTime)
                else -> a.compareTo(b, ignoreCase = true)
            }
            return if(ascending) value else -value
        }
        val ordered = groups.sortedWith { a,b -> compare(titles[a.key].orEmpty(), titles[b.key].orEmpty(), modified[a.key], modified[b.key])
            .takeIf { it != 0 } ?: a.key.compareTo(b.key) }
        for(group in groups) group.mangaId?.let { id ->
            val byId = details[id].orEmpty().associateBy { it.chapterId }
            val sorted = group.chapters.sortedWith { a,b ->
                val x=byId[a.chapterId]; val y=byId[b.chapterId]
                compare(x?.title ?: a.chapterId, y?.title ?: b.chapterId, x?.modifiedAt, y?.modifiedAt)
                    .takeIf { it != 0 } ?: a.key().compareTo(b.key())
            }
            chapters.sortChapters(id, sorted.map { it.key() })
        }
        scheduler.orderTranslationGroups(ordered.map { it.key })
    }
    private suspend fun sourceModified(row: TaskRecord): Long? = ResourceGovernor.shared.use("io") {
        val uri = runCatching { Uri.parse(JSONObject(row.payload).getString("uri")) }.getOrNull() ?: return@use null
        if(uri.scheme == "file") return@use uri.path?.let { java.io.File(it).lastModified().takeIf { value -> value > 0 } }
        val resolver = container.applicationContext.contentResolver
        val media = uri.authority == MediaStore.AUTHORITY
        val column = if(media) MediaStore.MediaColumns.DATE_MODIFIED else DocumentsContract.Document.COLUMN_LAST_MODIFIED
        runCatching { resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if(cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it > 0 }?.let { if(media) it * 1000 else it } else null
        } }.getOrNull()
    }
}
