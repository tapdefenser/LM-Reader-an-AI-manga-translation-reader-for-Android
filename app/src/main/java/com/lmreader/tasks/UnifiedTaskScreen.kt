package com.lmreader.tasks

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.database.tasks.TaskRecord
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.InferenceEngineKind
import com.lmreader.di.AppContainer
import com.lmreader.ui.i18n.Text
import com.lmreader.ui.queue.TranslationSchedulingPriority
import com.lmreader.ui.queue.key
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedTaskScreen(container: AppContainer,
    onResolve: ((TaskRecord) -> Unit)? = null, onBack: () -> Unit) {
    val scheduler = container.taskScheduler
    val queue = container.translationQueue
    val controller = remember(container) { TranslationQueueController(container) }
    val rows by scheduler.tasks.collectAsStateWithLifecycle()
    val chapters by queue.items.collectAsStateWithLifecycle()
    val serviceFailure by container.taskService.failure.collectAsStateWithLifecycle()
    val initializationFailure by scheduler.initializationFailure.collectAsStateWithLifecycle()
    val orderRevision by queue.orderRevision.collectAsStateWithLifecycle()
    val resources by queue.loadedResources.collectAsStateWithLifecycle()
    val activeSeg by queue.activeSeg.collectAsStateWithLifecycle()
    val activeOcr by queue.activeOcr.collectAsStateWithLifecycle()
    val activeApi by queue.activeApi.collectAsStateWithLifecycle()
    val scheduling by queue.schedulingPriority.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    var uncertain by remember { mutableStateOf<TaskRecord?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var resourcesOpen by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf<String?>(null) }
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val runAction: (suspend () -> Unit) -> Unit = { action ->
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error.message ?: error.javaClass.simpleName }
        }
    }
    val groups = remember(rows, chapters, orderRevision) {
        translationQueueGroups(rows, queue.ordered(chapters))
    }
    val mangaIds = remember(groups) { groups.mapNotNull { it.mangaId }.distinct() }
    var mangaNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var chapterDetails by remember { mutableStateOf<Map<String, ChapterRecord>>(emptyMap()) }
    LaunchedEffect(mangaIds) {
        if (mangaIds.isEmpty()) {
            mangaNames = emptyMap(); chapterDetails = emptyMap(); return@LaunchedEffect
        }
        try {
            val cards = container.mangaRepository.getCards(mangaIds)
            mangaNames = cards.associate { it.mangaId to it.displayName }
            chapterDetails = mangaIds.flatMap { container.mangaRepository.getChaptersInDisplayOrder(it) }
                .associateBy { it.chapterId }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = "读取漫画与章节信息失败：${error.message ?: error.javaClass.simpleName}" }
    }
    if (resourcesOpen) AlertDialog(onDismissRequest = { resourcesOpen = false }, title = { Text("已加载资源") },
        text = { LazyColumn {
            if (resources.isEmpty()) item { Text("当前没有已加载引擎") }
            items(resources, key = { it.id }) { resource ->
                val kind = when (resource.kind) {
                    InferenceEngineKind.SEG -> "SEG"; InferenceEngineKind.OCR -> "OCR"
                    InferenceEngineKind.TRANSLATION -> "机翻"
                }
                ListItem(headlineContent = { Text(resource.name, localize = false) },
                    supportingContent = { Text("$kind · ${resource.backend} · " + if (resource.inUse) "使用中" else "已加载") })
            }
        } }, confirmButton = { TextButton(onClick = { resourcesOpen = false }) { Text("关闭") } })
    Scaffold(topBar = { TopAppBar(title = { Text("翻译队列") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, actions = {
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "队列菜单") }
            DropdownMenu(menuOpen, { menuOpen = false }) {
                DropdownMenuItem(text = { Text("全部开始") }, onClick = { menuOpen = false; runAction { controller.continueAll() } })
                DropdownMenuItem(text = { Text("全部暂停") }, onClick = { menuOpen = false; runAction { controller.pauseAll() } })
                DropdownMenuItem(text = { Text("全部取消") }, onClick = { menuOpen = false; runAction { controller.cancelAll() } })
                HorizontalDivider()
                TranslationSchedulingPriority.entries.forEach { priority ->
                    DropdownMenuItem(
                        text = { Text(if (priority == TranslationSchedulingPriority.RESOURCES) "资源利用优先" else "队列顺序优先") },
                        modifier = Modifier.testTag("translation-priority:${priority.name}").semantics { selected = scheduling == priority },
                        trailingIcon = { if (scheduling == priority) Text("✓") },
                        onClick = { menuOpen = false; queue.setSchedulingPriority(priority) })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("已加载资源（${resources.size}）") },
                    onClick = { menuOpen = false; resourcesOpen = true })
                HorizontalDivider()
                listOf("自然数", "修改时间", "首字母").forEach { mode ->
                    Box {
                        DropdownMenuItem(text = { Text(mode) }, trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowRight, null) },
                            onClick = { sortMenu = mode })
                        DropdownMenu(expanded = sortMenu == mode, onDismissRequest = { sortMenu = null }) {
                            listOf(true to "升序", false to "降序").forEach { (ascending, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    sortMenu = null; menuOpen = false
                                    runAction { controller.sort(mode, ascending) }
                                })
                            }
                        }
                    }
                }
            }
        }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            serviceFailure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            initializationFailure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            Text("当前并行：SEG $activeSeg · OCR $activeOcr · API $activeApi",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("translation-queue-list"), contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (groups.isEmpty()) item(key = "empty-queue") {
                    Text("队列为空。可从漫画或视频的翻译选项加入队列。", modifier = Modifier.padding(12.dp))
                }
                groups.forEachIndexed { index, group ->
                    val mangaId = group.mangaId
                    val readerPages = group.tasks.filter { it.type == "READER_PAGE" }
                    val videoChildren = group.tasks.filter { it.type == "VIDEO_FRAMES" || it.type == "VIDEO_SUBTITLES" }
                    val isVideo = group.tasks.any { it.type in setOf("VIDEO", "VIDEO_FRAMES", "VIDEO_SUBTITLES") }
                    val title = if (mangaId != null) mangaNames[mangaId] ?: group.title else group.title
                    val subtitle = when {
                        mangaId != null -> "队列中 ${group.chapters.size} 章" +
                            if (readerPages.isNotEmpty()) " · 本页 ${readerPages.size}" else ""
                        isVideo -> "视频任务 ${group.tasks.size} 项"
                        else -> taskTypeLabel(group.tasks.firstOrNull()?.type.orEmpty())
                    }
                    val activeRoots = group.tasks.filter { it.state !in TaskScheduler.TERMINAL }
                    val chapterPaused = group.chapters.isNotEmpty() && group.chapters.all { it.state == "PAUSED" }
                    val groupPaused = chapterPaused || activeRoots.isNotEmpty() && activeRoots.all { it.state in setOf("PAUSED", "WAITING", "INTERRUPTED") }
                    item(key = "group:${group.key}") {
                        TranslationGroupCard(group = group, title = title, subtitle = subtitle, paused = groupPaused,
                            collapsed = group.key in collapsed, canMoveUp = index > 0, canMoveDown = index < groups.lastIndex,
                            onToggleCollapse = { collapsed = if (group.key in collapsed) collapsed - group.key else collapsed + group.key },
                            onStart = { runAction { controller.startGroup(group) } },
                            onPause = { runAction { controller.pauseGroup(group) } },
                            onRetry = { runAction { controller.retryGroup(group) } },
                            onMoveUp = { runAction { controller.moveGroup(group.key, -1) } },
                            onMoveDown = { runAction { controller.moveGroup(group.key, 1) } },
                            onCancel = { runAction { controller.cancelGroup(group) } },
                            onResolve = onResolve, onUnknown = { uncertain = it })
                    }
                    if (group.key in collapsed) return@forEachIndexed
                    if (mangaId != null) {
                        itemsIndexed(group.chapters,
                            key = { _, chapter -> "chapter:${group.key}:${chapter.key()}" }) { chapterIndex, chapter ->
                            val detail = chapterDetails[chapter.chapterId]
                            ChapterCard(item = chapter, title = detail?.title ?: chapter.chapterId, pageCount = detail?.pageCount,
                                canMoveUp = chapterIndex > 0, canMoveDown = chapterIndex < group.chapters.lastIndex,
                                onMove = { delta -> queue.moveChapter(mangaId, chapter.key(), delta) },
                                onPause = { runAction { queue.setChapterPaused(chapter, true).join() } },
                                onStart = { runAction { controller.startChapter(chapter) } },
                                onRetry = { runAction { controller.retryChapter(chapter) } },
                                onCancel = { runAction { queue.cancel(chapter).join() } })
                        }
                        items(readerPages, key = { "page:${it.taskId}" }) { task ->
                            TaskCard(task = task, indented = true, scheduler = scheduler, controller = controller,
                                onResolve = onResolve, onUnknown = { uncertain = it }, command = runAction)
                        }
                    } else if (isVideo) {
                        items(videoChildren, key = { "video-child:${it.taskId}" }) { task ->
                            TaskCard(task = task, indented = true, scheduler = scheduler, controller = controller,
                                onResolve = onResolve, onUnknown = { uncertain = it }, command = runAction)
                        }
                    } else {
                        items(group.tasks, key = { "task-card:${it.taskId}" }) { task ->
                            TaskCard(task = task, indented = false, scheduler = scheduler, controller = controller,
                                onResolve = onResolve, onUnknown = { uncertain = it }, command = runAction)
                        }
                    }
                }
            }
        }
    }
    uncertain?.let { row -> AlertDialog(onDismissRequest = { uncertain = null }, title = { Text("核对远端请求") },
        text = { Text("请求可能已被提供方处理并计费，但本机没有完整发布回执。请先核对提供方记录。选择重试可能产生重复费用；已保存的成果仍会优先复用。") },
        confirmButton = { TextButton(onClick = { uncertain = null; runAction { scheduler.retryUnknown(row.taskId) } }) { Text("已核对，明确重试") } },
        dismissButton = { TextButton(onClick = { uncertain = null }) { Text("暂不重试") } }) }
}

@Composable
private fun TranslationGroupCard(group: TranslationQueueGroup, title: String, subtitle: String, paused: Boolean,
    collapsed: Boolean, canMoveUp: Boolean, canMoveDown: Boolean, onToggleCollapse: () -> Unit,
    onStart: () -> Unit, onPause: () -> Unit, onRetry: () -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit, onCancel: () -> Unit,
    onResolve: ((TaskRecord) -> Unit)?, onUnknown: (TaskRecord) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val root = group.roots.firstOrNull()
    val work = if(root?.type == "VIDEO") listOf(root) else group.tasks
    val total = work.sumOf { it.total }
    val completed = work.sumOf { it.completed }
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag("translation-group:${group.key}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, localize = false)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                    root?.let { Text(taskStateLabel(it.state), style = MaterialTheme.typography.bodySmall) }
                    if (group.priority > 0) Text("优先级 ${group.priority}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                    if (total > 0) {
                        LinearProgressIndicator(progress = { (completed.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        Text("$completed / $total", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "分组操作") }
                    DropdownMenu(menu, { menu = false }) {
                        if(group.tasks.any { it.state == "FAILED" } || group.chapters.any { it.state == "FAILED" })
                            DropdownMenuItem(text = { Text("重试失败") }, onClick = { menu = false; onRetry() })
                        if(group.tasks.any { it.state !in TaskScheduler.TERMINAL } || group.chapters.any { it.state != "FAILED" })
                            DropdownMenuItem(text = { Text(if (paused) "开始" else "暂停") }, onClick = {
                                menu = false; if (paused) onStart() else onPause()
                            })
                        DropdownMenuItem(text = { Text("上移") }, enabled = canMoveUp, onClick = { menu = false; onMoveUp() })
                        DropdownMenuItem(text = { Text("下移") }, enabled = canMoveDown, onClick = { menu = false; onMoveDown() })
                        DropdownMenuItem(text = { Text("取消") }, onClick = { menu = false; onCancel() })
                    }
                }
            }
            TextButton(onClick = onToggleCollapse) { Text(if (collapsed) "展开" else "收起") }
            root?.failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            root?.waitingReason?.let { Text(waitingReasonLabel(it), style = MaterialTheme.typography.bodySmall) }
            root?.stage?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (root != null && onResolve != null && root.state == "WAITING" && root.needsResolve()) {
                TextButton(onClick = { onResolve?.invoke(root) }) {
                    Text(if (root.waitingReason in setOf("CAPABILITY", "API_ACCESS")) "补齐翻译能力" else "查看来源与选项")
                }
            }
            if (root != null && root.remoteOutcomeUnknown) TextButton(onClick = { onUnknown(root) }) { Text("核对请求") }
        }
    }
}

@Composable
private fun ChapterCard(item: ChapterTranslationEntity, title: String, pageCount: Int?,
    canMoveUp: Boolean, canMoveDown: Boolean, onMove: (Int) -> Unit, onPause: () -> Unit, onStart: () -> Unit,
    onRetry: () -> Unit, onCancel: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val chapterKey = item.key()
    Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp).testTag("translation-chapter:${item.chapterId}").pointerInput(chapterKey) {
            var distance = 0f
            detectDragGesturesAfterLongPress(onDragStart = { distance = 0f }, onDrag = { change, drag ->
                change.consume(); distance += drag.y
                if (abs(distance) >= 56.dp.toPx()) { onMove(if (distance > 0) 1 else -1); distance = 0f }
            })
        }) {
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("⋮⋮  $title", localize = false)
                Text("${chapterStateLabel(item.state)} · ${item.translatedCount} / ${pageCount ?: "?"} 页",
                    style = MaterialTheme.typography.bodySmall)
                item.failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "章节操作") }
                DropdownMenu(menu, { menu = false }) {
                    if (item.state == "FAILED") DropdownMenuItem(text = { Text("重试失败") }, onClick = { menu = false; onRetry() })
                    else if (item.state == "PAUSED" || item.state == "INTERRUPTED")
                        DropdownMenuItem(text = { Text("开始") }, onClick = { menu = false; onStart() })
                    if (item.state == "PENDING" || item.state == "RUNNING")
                        DropdownMenuItem(text = { Text("暂停") }, onClick = { menu = false; onPause() })
                    DropdownMenuItem(text = { Text("上移") }, enabled = canMoveUp, onClick = { menu = false; onMove(-1) })
                    DropdownMenuItem(text = { Text("下移") }, enabled = canMoveDown, onClick = { menu = false; onMove(1) })
                    if (item.state !in setOf("DONE", "CANCELLED")) DropdownMenuItem(text = { Text("取消") },
                        onClick = { menu = false; onCancel() })
                }
            }
        }
    }
}

@Composable
private fun TaskCard(task: TaskRecord, indented: Boolean, scheduler: TaskScheduler,
    controller: TranslationQueueController, onResolve: ((TaskRecord) -> Unit)?, onUnknown: (TaskRecord) -> Unit,
    command: (suspend () -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().then(if (indented) Modifier.padding(start = 18.dp) else Modifier)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(task.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), localize = false)
                if (task.priority > 0) Text("优先 ${task.priority}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "任务操作") }
                    DropdownMenu(menu, { menu = false }) {
                        if(task.state in setOf("QUEUED", "RUNNING")) DropdownMenuItem(text = { Text("暂停") },
                            onClick = { menu = false; command { scheduler.pause(task.taskId) } })
                        if(task.remoteOutcomeUnknown) DropdownMenuItem(text = { Text("核对请求") }, onClick = { menu = false; onUnknown(task) })
                        else if(task.state in setOf("PAUSED", "WAITING", "INTERRUPTED")) DropdownMenuItem(text = { Text("开始") },
                            onClick = { menu = false; command { controller.startTask(task.taskId) } })
                        if(task.state == "FAILED" && !task.remoteOutcomeUnknown) DropdownMenuItem(text = { Text("重试失败") },
                            onClick = { menu = false; command { controller.retryTask(task.taskId) } })
                        DropdownMenuItem(text = { Text("上移") }, onClick = { menu = false; command { controller.moveTask(task, -1) } })
                        DropdownMenuItem(text = { Text("下移") }, onClick = { menu = false; command { controller.moveTask(task, 1) } })
                        DropdownMenuItem(text = { Text("取消") }, onClick = { menu = false; command { scheduler.pause(task.taskId, cancel = true) } })
                    }
                }
            }
            Text("${taskTypeLabel(task.type)} · ${taskStateLabel(task.state)}", style = MaterialTheme.typography.bodySmall)
            if (task.stage.isNotBlank()) Text(task.stage, style = MaterialTheme.typography.bodySmall)
            if (task.total > 0) {
                LinearProgressIndicator(progress = { (task.completed.toFloat() / task.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
                Text("${task.completed}/${task.total}", style = MaterialTheme.typography.bodySmall)
            } else if (task.completed > 0) Text("已完成 ${task.completed}", style = MaterialTheme.typography.bodySmall)
            task.waitingReason?.let { Text(waitingReasonLabel(it), style = MaterialTheme.typography.bodySmall) }
            task.failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (onResolve != null && task.state == "WAITING" && task.needsResolve()) {
                TextButton(onClick = { onResolve?.invoke(task) }) {
                    Text(if (task.waitingReason in setOf("CAPABILITY", "API_ACCESS")) "补齐翻译能力" else "查看来源与选项")
                }
            }

        }
    }
}

private fun TaskRecord.needsResolve() = waitingReason in setOf(
    "CAPABILITY", "API_ACCESS", "ACCESS", "SOURCE_CHANGED", "CONFIG_CHANGED", "CHAPTER_PAUSED")

private fun chapterStateLabel(state: String) = when (state) {
    "PENDING" -> "等待中"; "RUNNING" -> "翻译中"; "PAUSED" -> "已暂停"
    "DONE" -> "已完成"; "FAILED" -> "失败"; "INTERRUPTED" -> "中断"; else -> state
}

fun taskTypeLabel(value: String) = when(value) {
    "VIDEO" -> "视频翻译"; "VIDEO_FRAMES" -> "视频画面"; "VIDEO_SUBTITLES" -> "视频字幕"
    "VIDEO_EXPORT", "EXPORT" -> "导出"; "CHAPTER" -> "章节翻译"; "READER_PAGE" -> "本页翻译"
    "MEDIA_SCAN" -> "视频索引"; "SOURCE_SCAN" -> "来源扫描"; "METADATA_FILL" -> "元数据补全"
    "VIDEO_VERIFY" -> "核对视频来源"; "SCREEN" -> "屏幕翻译"; "MODEL_INSTALL" -> "模型安装"
    "VIDEO_IMPORT" -> "成果包导入"; else -> value
}
fun taskStateLabel(value: String) = when(value) {
    "QUEUED" -> "排队"; "RUNNING" -> "处理中"; "PAUSING" -> "正在暂停"; "PAUSED" -> "已暂停"
    "CANCELLING" -> "正在取消"; "CANCELLED" -> "已取消"; "SUCCEEDED" -> "已完成"; "FAILED" -> "失败"
    "WAITING" -> "等待条件"; "INTERRUPTED" -> "系统中断"; else -> value
}
fun waitingReasonLabel(value: String) = when {
    value.startsWith("RESOURCE:") -> "等待资源：${value.substringAfter(':')}"
    value.startsWith("API:") -> "等待 API 并发许可"
    value.startsWith("VERIFY:") -> "正在校验原视频字节：${value.substringAfter(':')}"
    value == "CAPABILITY" -> "等待模型或 API 配置，请在设置补齐后继续"
    value == "ACCESS" -> "等待文件读取授权或重新定位"
    value == "SYSTEM_INTERRUPTED" -> "系统中断，已保留成果和恢复点"
    value == "SYSTEM_LIMIT" -> "等待系统允许后台运行，请打开应用后继续"
    value == "CHAPTER_PAUSED" -> "部分章节已单独暂停"
    value == "CHAPTER_QUEUE" -> "章节队列已暂停"
    value == "REMOTE_UNKNOWN" -> "远端请求结果未知，请核对后明确重试"
    value == "SOURCE_CHANGED" -> "原视频版本变化，请重新定位或更新任务"
    value == "CONFIG_CHANGED" -> "成果或配置变化，请更新选项并重新排队"
    else -> value
}
