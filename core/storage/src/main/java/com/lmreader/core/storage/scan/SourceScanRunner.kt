package com.lmreader.core.storage.scan

import com.lmreader.core.index.ScanEvent
import com.lmreader.core.index.ScanRequest
import com.lmreader.core.index.StructureScanner
import com.lmreader.core.model.LibrarySource
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ScanRunStatus
import com.lmreader.core.model.SourcePermissionState
import com.lmreader.core.model.SourceRepository
import com.lmreader.core.storage.access.TreeAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * 单来源扫描作业（开发文档 6.3：每源最多一个结构扫描任务，重复请求合并）。
 *
 * 三个必须守住的规则：
 * 1. **每源单任务**：同一来源的重复请求合并到正在跑的任务上，不并发开两个扫描，
 *    否则两个 generation 会互相删对方的章节；
 * 2. **越早落库越好**：发现一部漫画就立刻 upsert 一次，界面才能边扫边出卡片
 *    （开发文档 6.1），而不是等整棵扫描完；
 * 3. **失败不删索引**：扫描中断或授权失效时只更新来源状态，绝不把漫画标成
 *    "已删除"（验收 A07）。
 */
class SourceScanRunner(
    private val treeAccess: TreeAccess,
    private val scanner: StructureScanner,
    private val mangaRepository: MangaRepository,
    private val sourceRepository: SourceRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Admission seam for the durable task store.
     *
     * The default runs [work] inline, preserving the original in-process semantics for callers
     * and tests. The app supplies a callback that submits a `SOURCE_SCAN` task and waits for its
     * executor, which rebuilds the scan through [runScanNow].
     */
    private val admission: suspend (LibrarySource, Boolean, suspend () -> Unit) -> Unit =
        { _, _, work -> work() },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val currentCommands = mutableMapOf<String, ScanCommand>()
    private val pendingCommands = mutableMapOf<String, ScanCommand>()
    private val activeRunJobs = mutableMapOf<String, Job>()
    private val lock = Mutex()
    private val generationCounter = AtomicLong(0)

    private val _states = MutableStateFlow<Map<String, ScanState>>(emptyMap())

    /** 各来源的实时扫描状态；界面只订阅它，不自己数目录。 */
    val states: StateFlow<Map<String, ScanState>> = _states.asStateFlow()

    /**
     * 本次运行中「路径 → 失败原因（异常类型 + 原始 message）」。
     *
     * 为什么单独记一份：扫描结束时只能拿到 `ScanSummary.failedPaths`（只有路径），
     * 真正的原因只出现在 `ScanEvent.Failed` 里。如果不在事件回调时留下原因，
     * 界面最终只会显示"某某目录失败"，用户无法判断是权限、IO 还是格式问题
     * （开发文档 3「错误显示可操作原因」）。真机（MIUI 吞掉应用 logcat）上这一点
     * 尤其关键：日志不可读时，界面是唯一的信息出口。
     *
     * 它是 [runScan] 的局部状态而不是字段：多来源并发扫描时共享一份会被互相清空。
     */

    /**
     * 请求扫描一个来源。
     *
     * @param force 强制重建：丢弃目录快照并全量重扫（开发文档 6.3「强制重新扫描索引」）。
     * @return 本次（或已在进行中的）扫描作业。重复请求合并到正在跑的任务上。
     */
    fun requestScan(source: LibrarySource, force: Boolean = false): Job = scope.launch {
        val command = ScanCommand(source, force)
        val loopJob = lock.withLock {
            val existing = jobs[source.sourceId]
            if (existing?.isActive == true) {
                val current = currentCommands.getValue(source.sourceId)
                val pending = pendingCommands[source.sourceId]
                val merged = mergeCommand(current, pending, command)
                if (merged != null) {
                    // 新 revision（类型/递归/目录变更）或更强的 force 请求不能被旧扫描
                    // 吞掉。保留最新命令，并尽快取消当前实际扫描；外层循环随后补跑。
                    pendingCommands[source.sourceId] = merged
                    activeRunJobs[source.sourceId]?.cancel()
                }
                existing
            } else {
                val created = scope.launch(start = CoroutineStart.LAZY) { runScanLoop(command) }
                jobs[source.sourceId] = created
                currentCommands[source.sourceId] = command
                created.start()
                created
            }
        }
        // 返回句柄等待当前扫描以及因更新配置而排入的补扫全部结束。
        loopJob.join()
    }

    /**
     * Executor entry point: run exactly one scan for [source], without scheduling, merging or
     * terminal-state bookkeeping (those stay in [requestScan]/[runScanLoop]).
     *
     * Only the app's `SOURCE_SCAN` executor calls this in production. It must not call
     * [requestScan], otherwise admission would recurse into another durable task.
     */
    suspend fun runScanNow(source: LibrarySource, force: Boolean) {
        runScan(source, force)
    }

    suspend fun cancel(sourceId: String) {
        val job = lock.withLock {
            pendingCommands.remove(sourceId)
            currentCommands.remove(sourceId)
            activeRunJobs.remove(sourceId)
            jobs.remove(sourceId)
        }
        job?.cancelAndJoin()
        updateState(sourceId) { it.copy(running = false, status = ScanRunStatus.CANCELLED) }
    }

    suspend fun cancelAll() {
        val running = lock.withLock {
            val snapshot = jobs.values.toList()
            jobs.clear()
            currentCommands.clear()
            pendingCommands.clear()
            activeRunJobs.clear()
            snapshot
        }
        running.forEach { it.cancel() }
        running.forEach { it.join() }
    }

    /** 同一来源的调度循环：任何时刻只有一个 [runScan]，但允许排一个“最新配置”补扫。 */
    private suspend fun runScanLoop(initial: ScanCommand) {
        val sourceId = initial.source.sourceId
        val self = currentCoroutineContext()[Job] ?: return
        var command = initial
        try {
            while (currentCoroutineContext().isActive) {
                lock.withLock { currentCommands[sourceId] = command }
                val actual = CoroutineScope(currentCoroutineContext()).launch {
                    // Admission owns the actual run in production (durable SOURCE_SCAN task);
                    // the default callback still runs the work inline for existing callers.
                    admission(command.source, command.force) {
                        runScan(command.source, command.force)
                    }
                }
                lock.withLock { activeRunJobs[sourceId] = actual }
                actual.join()

                val next = lock.withLock {
                    if (activeRunJobs[sourceId] === actual) activeRunJobs.remove(sourceId)
                    pendingCommands.remove(sourceId).also { queued ->
                        if (queued == null && jobs[sourceId] === self) {
                            jobs.remove(sourceId)
                            currentCommands.remove(sourceId)
                        }
                    }
                } ?: break
                command = next
            }
        } finally {
            lock.withLock {
                if (jobs[sourceId] === self) {
                    jobs.remove(sourceId)
                    currentCommands.remove(sourceId)
                    pendingCommands.remove(sourceId)
                    activeRunJobs.remove(sourceId)
                }
            }
        }
    }

    /** 返回 null 表示新请求与当前/已排队请求等价，只需合并等待。 */
    private fun mergeCommand(
        current: ScanCommand,
        pending: ScanCommand?,
        incoming: ScanCommand,
    ): ScanCommand? {
        val baseline = pending ?: current
        val latestSource = if (incoming.source.revision >= baseline.source.revision) {
            incoming.source
        } else {
            baseline.source
        }
        val merged = ScanCommand(
            source = latestSource,
            force = baseline.force || incoming.force,
        )
        return merged.takeIf {
            it.source.revision != current.source.revision || (it.force && !current.force)
        }
    }

    private suspend fun runScan(source: LibrarySource, force: Boolean) {
        // 全库扫描拿到的是来源快照；真正开跑前再次做 revision 门禁，避免保存配置后
        // 仍用旧的类型/递归设置启动一次扫描（验收 A09）。
        if (!isCurrentSource(source)) return

        val generation = generationCounter.incrementAndGet()
        android.util.Log.i(
            TAG,
            "开始扫描 ${source.displayPath}（访问方式=" +
                (if (treeAccess.usesDirectFileAccess()) "直接文件访问" else "SAF 授权") + "）",
        )
        // 「路径 → 失败原因」按次运行隔离：多来源并发扫描时共享一份会被互相清空。
        val failureReasons = mutableMapOf<String, String>()
        updateState(source.sourceId) {
            it.copy(
                running = true,
                discovered = 0,
                chapters = 0,
                visited = 0,
                lastError = null,
                status = ScanRunStatus.RUNNING,
                // 记录本次实际使用的访问方式：排障时"为什么读不到"的第一个问题
                // 就是"它到底走的是哪条路径"。
                accessMode = if (treeAccess.usesDirectFileAccess()) "直接文件访问" else "SAF 授权",
                // 口径按来源的模式决定：单章节路径只产出"单章节"，
                // 多章节路径只产出"漫画"，两者不相加（用户要求）。
                isSingleChapterSource = source.mode == LayoutMode.SINGLE_CHAPTER,
            )
        }
        sourceRepository.updateScanResult(source.sourceId, clock(), ScanRunStatus.RUNNING, null)

        // 冷启动/刷新时先校验授权：授权失效要立刻反映到路径状态列，而不是等
        // 扫描到一半抛 SecurityException（开发文档 4.1「路径状态」）。
        //
        // 校验由 TreeAccess 按当前访问方式完成：有「全部文件访问」时探测真实目录，
        // 单目录 SAF 授权时查持久授权记录并确认系统放行。原因直接来自那里，
        // 因此用户看到的是"目录不存在"或"请重新授权"这类可操作结论。
        treeAccess.checkReadable(source.treeUri)?.let { reason ->
            if (!isCurrentSource(source)) return
            sourceRepository.updatePermission(source.sourceId, SourcePermissionState.LOST)
            sourceRepository.updateScanResult(source.sourceId, clock(), ScanRunStatus.FAILED, reason)
            updateState(source.sourceId) {
                it.copy(running = false, lastError = reason, status = ScanRunStatus.FAILED, finishedAt = clock())
            }
            return
        }

        val root = treeAccess.open(source.treeUri, source.displayPath)
        if (root == null) {
            if (!isCurrentSource(source)) return
            val message = "无法打开该目录，可能已被移动或删除"
            sourceRepository.updateScanResult(source.sourceId, clock(), ScanRunStatus.FAILED, message)
            updateState(source.sourceId) {
                it.copy(running = false, lastError = message, status = ScanRunStatus.FAILED, finishedAt = clock())
            }
            return
        }

        // 强制重建时先丢弃目录快照：快照是"这个容器完整枚举过"的凭证，
        // 重建的定义就是不信任任何旧凭证（开发文档 6.3）。
        if (force) sourceRepository.updatePermission(source.sourceId, SourcePermissionState.CHECKING)

        val request = ScanRequest(
            sourceId = source.sourceId,
            sourceKind = source.kind,
            sourceRevision = source.revision,
            rootDocumentId = treeAccess.rootDocumentId(source.treeUri),
            layoutMode = source.mode,
            recursive = source.recursive,
            generation = generation,
            displayPath = source.displayPath,
        )

        val factory = treeAccess.treeFactory(source.treeUri)
        var persistenceFailure: String? = null
        val summary = try {
            // 关键：工厂必须**按本次扫描的授权树**传进去。
            // 扫描器实例是共享的（结构扫描本身无状态），它不能持有某个具体来源的
            // 工厂；早先的写法在容器里给扫描器塞了一个返回 null 的占位工厂、
            // 这里又忘了传真实工厂，结果是"根目录列得出来、每个子目录都打不开、
            // 却没有任何异常"——真机上排查了很久才定位到这个接线错误。
            scanner.scan(
                request,
                root,
                factory = factory,
                events = { event ->
                    onEvent(source.sourceId, event, failureReasons)?.let { message ->
                        persistenceFailure = persistenceFailure ?: message
                    }
                },
            )
        } catch (cancellation: CancellationException) {
            // [StructureScanner] 按契约把取消表达为 `completed = false` 而不是抛出，
            // 但调度层若在别处取消了这个协程（例如取消整个全库扫描），异常仍会到这里。
            withContext(NonCancellable) {
                val superseded = hasPending(source.sourceId) || !isCurrentSource(source)
                if (!superseded) {
                    sourceRepository.updateScanResult(source.sourceId, clock(), ScanRunStatus.CANCELLED, null)
                }
                updateState(source.sourceId) {
                    it.copy(running = false, status = ScanRunStatus.CANCELLED, finishedAt = clock())
                }
            }
            throw cancellation
        }

        // 用户在扫描期间保存了新配置：事件落库层已经按 revision 拒绝旧结果；这里还要
        // 禁止旧代次继续标记陈旧卡片或覆盖新来源的最终状态。
        if (!isCurrentSource(source)) {
            updateState(source.sourceId) {
                it.copy(
                    running = false,
                    currentPath = null,
                    status = ScanRunStatus.CANCELLED,
                    finishedAt = clock(),
                )
            }
            return
        }

        // 取消与失败必须区分：取消是用户意图，不该在路径状态里显示成红色错误。
        // 扫描器不抛异常地返回不完整结果，因此这里用当前协程是否仍活跃来判断，
        // 而不是把 `completed = false` 一律当成失败。
        val cancelled = !currentCoroutineContext().isActive
        val status = when {
            cancelled -> ScanRunStatus.CANCELLED
            persistenceFailure != null -> ScanRunStatus.FAILED
            !summary.completed -> ScanRunStatus.FAILED
            else -> ScanRunStatus.COMPLETED
        }

        // 本轮没有再发现的旧卡片要隐藏掉，否则切换解释方式（多章节 ↔ 单章节）、
        // 改动"子目录"勾选或把路径重新指向另一个目录之后，图库里会同时留着新旧两套
        // 卡片——用户看到的现象就是"单章节模式里出现了名字是带子文件夹的目录的卡片"。
        //
        // 只有 `COMPLETED` 才有资格做这个判定：取消、目录读取失败（completed=false）、
        // 授权失效时必须原样保留旧卡片，否则会把"这次没读到"误判成"已经不存在"
        // （验收 A07「失败不删索引」）。
        //
        // 判定依据是行上的 `discoveryGeneration`：发现阶段每次写入都带上本次代次，
        // 因此"本轮是否发现"不需要回传一份 ID 清单。标记只改 availability、不删行，
        // 章节、书架关系、阅读进度与译文全部保留，卡片被重新发现时自动回到可见。
        var staleFailure: String? = null
        val staleMarked = if (status == ScanRunStatus.COMPLETED) {
            runCatching { mangaRepository.markUndiscoveredAsStale(source.sourceId, generation) }
                .onFailure { failure ->
                    android.util.Log.e(TAG, "标记陈旧卡片失败 source=${source.sourceId}", failure)
                    staleFailure = failure.message ?: "更新陈旧卡片状态失败"
                }
                .getOrDefault(0)
        } else {
            0
        }
        val finalStatus = if (staleFailure != null) ScanRunStatus.FAILED else status
        // 诊断：把"打开子目录次数 / 枚举次数 / 命中叶子判定次数"打出来，
        // 用来验证"找到一个章节就跳过其余文件夹"是否真的生效（而不是靠感觉）。
        android.util.Log.i(
            TAG,
            "扫描结束 ${source.displayPath}：漫画=${summary.mangas} 遍历目录=${summary.directoriesVisited} " +
                "章节探测=${summary.leafChapterProbes}",
        )
        val error = when {
            cancelled -> null
            persistenceFailure != null -> persistenceFailure
            staleFailure != null -> staleFailure
            else -> {
                // 汇总里只有路径，原因在 failureReasons（事件回调时记下的）。
                // 拼成"路径：原因"才能在界面上回答"为什么失败"。
                summary.failedPaths.firstOrNull()?.let { path ->
                    failureReasons[path]?.let { "$path：$it" } ?: path
                }
            }
        }
        android.util.Log.i(
            TAG,
            "扫描结束 source=${source.sourceId} 漫画=${summary.mangas} 目录=${summary.directoriesVisited} " +
                "completed=${summary.completed} 失败路径=${summary.failedPaths.size} 陈旧卡片=$staleMarked",
        )
        sourceRepository.updatePermission(
            source.sourceId,
            if (summary.failedPaths.isEmpty()) SourcePermissionState.OK else SourcePermissionState.PARTIAL,
        )
        sourceRepository.updateScanResult(source.sourceId, clock(), finalStatus, error)
        updateState(source.sourceId) {
            it.copy(
                running = false,
                discovered = summary.mangas,
                chapters = summary.chapters,
                visited = summary.directoriesVisited,
                leafProbes = summary.leafChapterProbes,
                staleMarked = staleMarked,
                currentPath = null,
                lastError = error,
                status = finalStatus,
                finishedAt = clock(),
            )
        }
    }

    private suspend fun onEvent(
        sourceId: String,
        event: ScanEvent,
        failureReasons: MutableMap<String, String>,
    ): String? = when (event) {
        is ScanEvent.MangaDiscovered -> {
            // 边发现边落库：失败只影响这一部作品，其它卡片照常出现（开发文档 6.1）。
            val failure = runCatching { mangaRepository.upsertScanResult(event.result) }.exceptionOrNull()
            if (failure != null) {
                android.util.Log.e(TAG, "写入索引失败 manga=${event.result.manga.displayName}", failure)
                updateState(sourceId) { it.copy(lastError = failure.message ?: "写入索引失败") }
            }
            updateState(sourceId) { it.copy(discovered = event.totalDiscovered) }
            failure?.message ?: failure?.let { "写入索引失败" }
        }

        is ScanEvent.Progress -> {
            updateState(sourceId) {
                it.copy(
                    visited = event.directoriesVisited,
                    chapters = event.chaptersDiscovered,
                    currentPath = event.currentPath ?: it.currentPath,
                )
            }
            null
        }

        is ScanEvent.Diagnostic -> {
            // 诊断不算失败：混放目录只是提示，不该把路径状态标红（开发文档 5.1）。
            // 保留原文供"扫描诊断"查看，P1 再做逐条展示页。
            appendDiagnostic(sourceId, "诊断 ${event.path}：${event.message}")
            null
        }

        is ScanEvent.Failed -> {
            val detail = "${event.path}：${event.message}"
            // 完整失败原因（路径 + 异常类型 + message）必须能被用户看到：
            // 真机（MIUI）会吞掉应用 logcat，只藏在日志里等于没有错误提示。
            failureReasons[event.path] = event.message
            appendDiagnostic(sourceId, detail)
            updateState(sourceId) { it.copy(lastError = detail) }
            null
        }
    }

    private suspend fun isCurrentSource(source: LibrarySource): Boolean =
        sourceRepository.getSource(source.sourceId)?.revision == source.revision

    private suspend fun hasPending(sourceId: String): Boolean =
        lock.withLock { pendingCommands.containsKey(sourceId) }

    /** 诊断列表有上限：扫描万级目录时不能无限增长（UI 只展示最近若干条）。 */
    private fun appendDiagnostic(sourceId: String, line: String) {
        updateState(sourceId) { state ->
            val next = (state.diagnostics + line).takeLast(MAX_DIAGNOSTICS)
            state.copy(diagnostics = next)
        }
    }

    private fun updateState(sourceId: String, transform: (ScanState) -> ScanState) {
        _states.update { current ->
            val previous = current[sourceId] ?: ScanState(sourceId = sourceId)
            current + (sourceId to transform(previous))
        }
    }

    private data class ScanCommand(
        val source: LibrarySource,
        val force: Boolean,
    )

    private companion object {
        const val TAG = "SourceScanRunner"

        /** 界面只展示最近若干条诊断；扫描万级目录时不能无限增长。 */
        const val MAX_DIAGNOSTICS = 40
    }
}
