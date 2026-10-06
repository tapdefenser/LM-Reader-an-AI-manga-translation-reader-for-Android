package com.lmreader.ui.queue

import com.lmreader.ui.i18n.Icon

import com.lmreader.ui.i18n.Text

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.InferenceEngineKind
import com.lmreader.di.AppContainer
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationQueueScreen(container: AppContainer, onBack: () -> Unit) {
    val queue = container.translationQueue
    val rows by queue.items.collectAsStateWithLifecycle()
    val paused by queue.paused.collectAsStateWithLifecycle()
    val serviceFailure by container.taskService.failure.collectAsStateWithLifecycle()
    val priorityPage by queue.priorityPage.collectAsStateWithLifecycle()
    val orderRevision by queue.orderRevision.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var resourcesOpen by remember { mutableStateOf(false) }
    val resources by queue.loadedResources.collectAsStateWithLifecycle()
    val activeSeg by queue.activeSeg.collectAsStateWithLifecycle()
    val activeOcr by queue.activeOcr.collectAsStateWithLifecycle()
    val activeApi by queue.activeApi.collectAsStateWithLifecycle()
    if (resourcesOpen) AlertDialog(onDismissRequest = { resourcesOpen = false }, title = { Text("已加载资源") },
        text = { androidx.compose.foundation.lazy.LazyColumn {
            if (resources.isEmpty()) item { Text("当前没有已加载引擎") }
            items(resources, key = { it.id }) { resource ->
                ListItem(headlineContent = { Text(resource.name, localize = false) }, supportingContent = {
                    val kind = when (resource.kind) { InferenceEngineKind.SEG -> "SEG"; InferenceEngineKind.OCR -> "OCR"; InferenceEngineKind.TRANSLATION -> "机翻" }
                    Text("$kind · ${resource.backend} · " + if (resource.inUse) "使用中" else "已加载")
                })
            }
        } }, confirmButton = { TextButton(onClick = { queue.pause() }) { Text("全部暂停并卸载") } },
        dismissButton = { TextButton(onClick = { resourcesOpen = false }) { Text("关闭") } })
    var mangaNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var chapterDetails by remember { mutableStateOf<Map<String, ChapterRecord>>(emptyMap()) }
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(rows.map { it.mangaId }.distinct(), rows.map { it.chapterId to it.state }) {
        val ids = rows.map { it.mangaId }.distinct()
        mangaNames = container.mangaRepository.getCards(ids).associate { it.mangaId to it.displayName }
        chapterDetails = ids.flatMap { container.mangaRepository.getChaptersInDisplayOrder(it) }.associateBy { it.chapterId }
    }
    val ordered = remember(rows, orderRevision) { queue.ordered(rows) }
    val groups = ordered.groupBy { it.mangaId }
    Scaffold(topBar = { TopAppBar(title = { Text("翻译队列") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, actions = {
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "队列菜单") }
            DropdownMenu(menuOpen, { menuOpen = false }) {
                DropdownMenuItem(text = { Text("全部开始") }, onClick = { menuOpen = false; queue.startAll() })
                DropdownMenuItem(text = { Text("全部暂停") }, onClick = { menuOpen = false; queue.pause() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("已加载资源（${resources.size}）") }, onClick = { menuOpen = false; resourcesOpen = true })
                listOf("自然数", "修改时间", "首字母").forEach { mode ->
                    DropdownMenuItem(text = { Text("章节：$mode") }, onClick = {
                        groups.forEach { (id, chapters) ->
                            val sorted = when (mode) {
                                "自然数" -> chapters.sortedBy { chapterDetails[it.chapterId]?.sortKey.orEmpty() }
                                "修改时间" -> chapters.sortedByDescending { chapterDetails[it.chapterId]?.modifiedAt ?: 0L }
                                else -> chapters.sortedBy { chapterDetails[it.chapterId]?.title.orEmpty() }
                            }
                            queue.sortChapters(id, sorted.map { it.key() })
                        }; menuOpen = false
                    })
                }
            }
        }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        serviceFailure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("当前并行：SEG $activeSeg · OCR $activeOcr · API $activeApi", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        if (rows.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp)) {
            Text("队列为空。到漫画详情页选择章节翻译。")
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            priorityPage?.let { label -> item(key = "priority-page") { Text("插队重译：$label", modifier = Modifier.padding(8.dp)) } }
            groups.forEach { (mangaId, chapters) ->
                item(key = "manga:$mangaId") {
                    var expanded by remember { mutableStateOf(false) }
                    val active = chapters.filter { it.state in listOf("PENDING", "RUNNING", "PAUSED") }
                    val groupPaused = active.isNotEmpty() && active.all { it.state == "PAUSED" }
                    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().animateItem()) {
                        Row(Modifier.padding(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(mangaNames[mangaId] ?: mangaId, style = MaterialTheme.typography.titleMedium, localize = false)
                                Text("队列中 ${chapters.size} 章")
                                TextButton(onClick = { collapsed = if (mangaId in collapsed) collapsed - mangaId else collapsed + mangaId }) {
                                    Text(if (mangaId in collapsed) "展开章节" else "收起章节")
                                }
                            }
                            Box {
                                IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "漫画操作") }
                                DropdownMenu(expanded, { expanded = false }) {
                                    DropdownMenuItem(text = { Text(if (groupPaused) "继续" else "暂停") }, enabled = active.isNotEmpty(), onClick = {
                                        queue.setMangaPaused(mangaId, !groupPaused); expanded = false
                                    })
                                    DropdownMenuItem(text = { Text("上移") }, enabled = groups.keys.first() != mangaId, onClick = { queue.moveManga(mangaId, -1); expanded = false })
                                    DropdownMenuItem(text = { Text("下移") }, enabled = groups.keys.last() != mangaId, onClick = { queue.moveManga(mangaId, 1); expanded = false })
                                    DropdownMenuItem(text = { Text("置顶") }, enabled = groups.keys.first() != mangaId, onClick = { queue.topManga(mangaId); expanded = false })
                                    DropdownMenuItem(text = { Text("取消") }, onClick = { queue.cancelManga(mangaId); expanded = false })
                                }
                            }
                        }
                    }
                }
                if (mangaId !in collapsed) items(chapters, key = { "chapter:${it.key()}" }) { task ->
                    var expanded by remember { mutableStateOf(false) }
                    val move by rememberUpdatedState<(Int) -> Unit> { queue.moveChapter(mangaId, task.key(), it) }
                    Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().animateItem().padding(start = 18.dp).pointerInput(task.chapterId) {
                            var distance = 0f
                            detectDragGesturesAfterLongPress(onDragStart = { distance = 0f }, onDrag = { change, drag ->
                                change.consume(); distance += drag.y
                                if (abs(distance) >= 56.dp.toPx()) { move(if (distance > 0) 1 else -1); distance = 0f }
                            })
                        }) {
                        Row(Modifier.fillMaxWidth().padding(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("⋮⋮  " + (chapterDetails[task.chapterId]?.title ?: task.chapterId), localize = false)
                                Text("${stateLabel(task.state)} · ${task.translatedCount} / ${chapterDetails[task.chapterId]?.pageCount ?: "?"} 页",
                                    style = MaterialTheme.typography.bodySmall)
                                task.failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            }
                            Box {
                                IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "章节操作") }
                                DropdownMenu(expanded, { expanded = false }) {
                                    if (task.state in listOf("PENDING", "RUNNING", "PAUSED")) DropdownMenuItem(
                                        text = { Text(if (task.state == "PAUSED") "继续" else "暂停") }, onClick = {
                                            queue.setChapterPaused(task, task.state != "PAUSED"); expanded = false
                                        })
                                    if (task.state in listOf("FAILED", "INTERRUPTED")) DropdownMenuItem(text = { Text("重试") }, onClick = { queue.retry(task); expanded = false })
                                    DropdownMenuItem(text = { Text("取消") }, onClick = { queue.cancel(task); expanded = false })
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}
private fun stateLabel(state: String) = when (state) {
    "PENDING" -> "等待中"; "RUNNING" -> "翻译中"; "PAUSED" -> "已暂停"
    "DONE" -> "已完成"; "FAILED" -> "失败"; "INTERRUPTED" -> "中断"; else -> state
}
