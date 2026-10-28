package com.lmreader.ui.video.catalog

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.core.database.media.VideoCatalogEntry
import com.lmreader.core.database.tasks.TaskRecord
import com.lmreader.core.index.NaturalOrder
import com.lmreader.core.model.InferencePriority
import com.lmreader.core.model.ResourceGovernor
import com.lmreader.di.AppContainer
import com.lmreader.tasks.taskStateLabel
import com.lmreader.ui.i18n.Text
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

@Composable
fun VideoCatalogScreen(container: AppContainer, shelf: Boolean, onOpenMenu: () -> Unit,
    onPlay: (String, List<String>) -> Unit, onAction: (String, String) -> Unit) {
    val persisted by produceState<String?>(null, shelf) { value = container.preferences.videoHomeState(shelf).first() }
    val saved = persisted ?: return
    key(shelf) { CatalogContent(container, shelf, JSONObject(saved), onOpenMenu, onPlay, onAction) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogContent(container: AppContainer, shelf: Boolean, saved: JSONObject, onOpenMenu: () -> Unit,
    onPlay: (String, List<String>) -> Unit, onAction: (String, String) -> Unit) {
    val context = LocalContext.current
    val catalog = container.videoCatalog
    val rows by catalog.entries.collectAsStateWithLifecycle(emptyList())
    val tasks by container.taskScheduler.tasks.collectAsStateWithLifecycle()
    val projects by container.videoLibrary.projects.collectAsStateWithLifecycle()
    val scanError by catalog.error.collectAsStateWithLifecycle()
    val scanning by catalog.scanning.collectAsStateWithLifecycle()
    val categories by container.shelfRepository.observeCategories().collectAsStateWithLifecycle(emptyList())
    var search by rememberSaveable { mutableStateOf(saved.optString("search")) }
    var folder by rememberSaveable { mutableStateOf(saved.optString("folder")) }
    var category by rememberSaveable { mutableStateOf(saved.optLong("category", -1)) }
    var sort by rememberSaveable { mutableStateOf(saved.optString("sort", "ADDED")) }
    var descending by rememberSaveable { mutableStateOf(saved.optBoolean("descending", true)) }
    val list = rememberLazyListState(saved.optInt("index"), saved.optInt("offset"))
    var filterMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    var classification by remember { mutableStateOf<VideoCatalogEntry?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if(event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { permissionRevision++; container.videoMaintenance.refresh() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val scope = rememberCoroutineScope()
    val permission = remember(permissionRevision) { catalog.permissionState() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRevision++; container.videoMaintenance.refresh(full = true)
    }
    fun requestPermission() {
        permissionLauncher.launch(when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        })
    }
    LaunchedEffect(Unit) {
        val settings = context.getSharedPreferences("video-permission", 0)
        if(permission == "NONE" && !settings.getBoolean("requested", false)) {
            settings.edit().putBoolean("requested", true).apply(); requestPermission()
        } else container.videoMaintenance.refresh()
    }
    LaunchedEffect(search, folder, category, sort, descending, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) {
        delay(400)
        container.preferences.setVideoHomeState(shelf, JSONObject().put("search", search).put("folder", folder)
            .put("category", category).put("sort", sort).put("descending", descending)
            .put("index", list.firstVisibleItemIndex).put("offset", list.firstVisibleItemScrollOffset).toString())
    }
    val visible by produceState<List<VideoCatalogEntry>>(emptyList(), rows, shelf, search, folder, category, sort, descending, permission) {
        value = withContext(Dispatchers.Default) {
        val filteringContext = currentCoroutineContext()
        var comparisons = 0
        rows.filter { row -> (if(shelf) row.onShelf else row.accessible && (permission != "NONE" || row.generation == 0L)) && row.name.contains(search, ignoreCase = true)
            && (folder.isEmpty() || folder == "${row.volume}|${row.parentPath}") && (!shelf || category < 0 || row.categoryId == category) }
            .sortedWith { a, b ->
                if(++comparisons % 256 == 0) filteringContext.ensureActive()
                // Unknown sizes/durations stay last in both directions.
                val unknown = when(sort) { "SIZE" -> (a.bytes <= 0).compareTo(b.bytes <= 0); "DURATION" -> (a.durationMs <= 0).compareTo(b.durationMs <= 0); else -> 0 }
                if(unknown != 0) unknown else {
                    val primary = when(sort) {
                        "SIZE" -> a.bytes.compareTo(b.bytes); "DURATION" -> a.durationMs.compareTo(b.durationMs)
                        "NAME" -> a.name.compareTo(b.name, ignoreCase = true); "NATURAL" -> NaturalOrder.compare(a.name, b.name)
                        "PLAYS" -> a.playCount.compareTo(b.playCount); else -> a.dateAdded.compareTo(b.dateAdded)
                    }
                    (if(descending) -primary else primary).takeIf { it != 0 } ?: a.name.compareTo(b.name).takeIf { it != 0 } ?: a.videoId.compareTo(b.videoId)
                }
            }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("视频") }, navigationIcon = {
        IconButton(onClick = onOpenMenu) { Icon(Icons.Default.Menu, "菜单") }
    }, actions = {
        IconButton(onClick = { container.videoMaintenance.refresh(full = true) }) { Icon(Icons.Default.Refresh, "刷新视频库") }
        Box {
            IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, "排序") }
            DropdownMenu(sortMenu, { sortMenu = false }) {
                listOf("ADDED" to "添加时间", "SIZE" to "文件大小", "DURATION" to "时长", "NAME" to "名称", "NATURAL" to "自然数", "PLAYS" to "最多播放").forEach { (key, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        if(sort == key) descending = !descending else { sort = key; descending = key !in setOf("NAME", "NATURAL") }; sortMenu = false
                    })
                }
                DropdownMenuItem(text = { Text(if(descending) "降序 ↓" else "升序 ↑") }, onClick = { descending = !descending; sortMenu = false })
            }
        }
    }) }) { padding -> Column(Modifier.padding(padding).fillMaxSize()) {
        OutlinedTextField(search, { search = it }, label = { Text("搜索视频") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true)
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box { TextButton(onClick = { filterMenu = true }) { Text(if(folder.isEmpty()) "全部文件夹" else folder.substringAfter('|').trimEnd('/').substringAfterLast('/')) }
                DropdownMenu(filterMenu, { filterMenu = false }) {
                    DropdownMenuItem(text = { Text("全部文件夹") }, onClick = { folder = ""; filterMenu = false })
                    rows.filter { !shelf || it.onShelf }.groupBy { "${it.volume}|${it.parentPath}" }.toSortedMap().forEach { (key, entries) ->
                        DropdownMenuItem(text = { Text("${entries.first().parentPath.ifEmpty { "根目录" }} · ${entries.first().volume} (${entries.size})") }, onClick = { folder = key; filterMenu = false })
                    }
                }
            }
            if(shelf) Box { TextButton(onClick = { categoryMenu = true }) { Text(categories.firstOrNull { it.categoryId == category }?.name ?: "全部分类") }
                DropdownMenu(categoryMenu, { categoryMenu = false }) {
                    DropdownMenuItem(text = { Text("全部分类") }, onClick = { category = -1; categoryMenu = false })
                    categories.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { category = c.categoryId; categoryMenu = false }) }
                }
            }
        }
        if(permission != "FULL") TextButton(onClick = ::requestPermission) { Text(if(permission == "PARTIAL") "仅可访问部分视频 · 更改授权" else "授予视频读取权限") }
        if(scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        (error ?: scanError)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
        LazyColumn(state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if(visible.isEmpty()) item { Text(if(shelf) "书架还没有视频；可收藏视频，或设置翻译后自动加入。" else "当前授权范围内没有视频。", modifier = Modifier.padding(24.dp)) }
            items(visible, key = { it.videoId }) { video ->
                val taskState = videoTaskState(tasks, video.videoId)
                Row(Modifier.fillMaxWidth().clickable {
                    if(video.accessible) onPlay(video.videoId, visible.map { it.videoId }) else error = video.accessReason ?: "原视频暂不可访问，请恢复授权或重新定位"
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    VideoThumbnail(video, Modifier.width(120.dp).aspectRatio(16f / 9f))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(video.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text("${resolution(video)} | ${formatVideoBytes(video.bytes)} | ${videoTranslationStatus(taskState, projects.any { it.id == video.videoId })}",
                            maxLines = 2, style = MaterialTheme.typography.bodySmall)
                        if(!video.accessible) Text(video.accessReason ?: "不可访问", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    Box { IconButton(onClick = { menu = video.videoId }) { Icon(Icons.Default.MoreVert, "视频菜单") }
                        DropdownMenu(menu == video.videoId, { menu = null }) {
                            val actions = listOf("分享", if(video.onShelf) "已加入书架/管理分类" else "添加到书架", if(taskState == "RUNNING") "暂停翻译" else "▷ 翻译", "翻译选项", "编辑译文", "清除译文", "导出", "信息")
                            actions.forEachIndexed { index, label -> DropdownMenuItem(text = { Text(label) }, onClick = {
                                menu = null
                                when(index) {
                                    0 -> try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, Uri.parse(video.uri))
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = android.content.ClipData.newRawUri(video.name, Uri.parse(video.uri)) }, video.name)) } catch(e: Exception) { error = e.message }
                                    1 -> { classification = video; if(!video.onShelf) scope.launch { catalog.ensureOnShelf(video.videoId) } }
                                    else -> onAction(video.videoId, listOf("", "", "TRANSLATE", "OPTIONS", "EDIT", "CLEAR", "EXPORT", "INFO")[index])
                                }
                            }) }
                        }
                    }
                }
            }
        }
    } }
    classification?.let { video -> AlertDialog(onDismissRequest = { classification = null }, title = { Text("管理视频分类") },
        text = { Column { categories.forEach { c -> TextButton(onClick = { scope.launch { catalog.setCategory(video.videoId, c.categoryId) }; classification = null }) { Text(c.name) } } } },
        confirmButton = { TextButton(onClick = { classification = null }) { Text("关闭") } },
        dismissButton = { if(shelf) TextButton(onClick = { scope.launch { catalog.removeFromShelf(video.videoId) }; classification = null }) { Text("移出书架") } }) }
}

private val thumbnails = android.util.LruCache<String, Bitmap>(8 * 1024 * 1024).let { _ -> object : android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
} }
@Composable
internal fun VideoThumbnail(
    video: VideoCatalogEntry,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    VideoThumbnail(
        uri = video.uri,
        cacheKey = "${video.uri}:${video.sourceRevision}",
        durationMs = video.durationMs,
        accessible = video.accessible,
        modifier = modifier,
        contentScale = contentScale,
    )
}

/** Shared player/gallery thumbnail: decodes only the rows that are actually composed. */
@Composable
internal fun VideoThumbnail(
    uri: String,
    cacheKey: String,
    durationMs: Long,
    accessible: Boolean,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    val key = cacheKey
    val bitmap by produceState<Bitmap?>(thumbnails.get(key), key, accessible) {
        if(!accessible) { value = null; return@produceState }
        if(value != null) return@produceState
        value = withContext(Dispatchers.IO + InferencePriority(ResourceGovernor.MAINTENANCE)) {
            try { ResourceGovernor.shared.use("decode") {
                if(Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(Uri.parse(uri), Size(320, 180), null)
                else MediaMetadataRetriever().let { r -> try {
                    r.setDataSource(context, Uri.parse(uri))
                    val original = if(Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180) else r.getFrameAtTime(0)
                    original?.let { if(it.width > 320 || it.height > 180) Bitmap.createScaledBitmap(it, 320, 180, true).also { _ -> it.recycle() } else it }
                } finally { r.release() } }
            }?.also { thumbnails.put(key, it) } } catch(e: CancellationException) { throw e } catch(_: Exception) { null }
        }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } ?: Icon(Icons.Default.PlayCircle, null)
        Surface(color = MaterialTheme.colorScheme.scrim.copy(alpha = .6f), modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp)) {
            androidx.compose.material3.Text(formatVideoTime(durationMs), color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(2.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}
fun formatVideoTime(ms: Long): String = if(ms >= 3_600_000) "%d:%02d:%02d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60) else "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
fun formatVideoBytes(bytes: Long): String = when { bytes <= 0 -> "未知大小"; bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0); else -> "%.1f MB".format(bytes / 1_048_576.0) }

/** Status text shared by the gallery row and the player's playlist drawer. */
internal fun videoTaskState(tasks: List<TaskRecord>, videoId: String): String? =
    tasks.firstOrNull {
        it.ownerKey.startsWith("video:$videoId") &&
            it.state != "SUCCEEDED" && it.state != "CANCELLED"
    }?.state

internal fun videoTranslationStatus(taskState: String?, hasProject: Boolean): String =
    taskState?.let(::taskStateLabel) ?: if(hasProject) "已有成果/配置" else "未翻译"

internal fun videoResolution(width: Int, height: Int): String =
    if(width <= 0 || height <= 0) "未知清晰度" else "${width}×${height} · ${minOf(width, height)}p"

private fun resolution(v: VideoCatalogEntry): String = videoResolution(v.width, v.height)
