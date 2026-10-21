package com.lmreader.core.translation.asr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.lmreader.core.model.ResourceGovernor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional adapter for the app's unified task scheduler. Until the root container wires the
 * adapter, [Direct] executes inside the manager's own [CoroutineScope]. The root can pass a
 * scheduler that adds a task row/priority and still call the public suspend operations
 * ([downloadNow], [importNow], [deleteNow]) directly.
 */
interface AsrWorkScheduler {
    suspend fun <T> schedule(reason: String, block: suspend () -> T): T

    /**
     * Optional identity-aware variant. The default ignores [expectedIdentity] so simple test
     * schedulers keep the original contract; durable installers persist it to reject a catalog
     * that changed between submission and execution.
     */
    suspend fun <T> schedule(
        reason: String,
        expectedIdentity: String?,
        block: suspend () -> T,
    ): T = schedule(reason, block)

    /** Pauses the durable task behind [reason], if any, and waits for file/native release. */
    suspend fun pause(reason: String): Boolean = false

    /** Cancels the durable task behind [reason], if any, and waits for file/native release. */
    suspend fun cancel(reason: String): Boolean = false

    object Direct : AsrWorkScheduler {
        override suspend fun <T> schedule(reason: String, block: suspend () -> T): T = block()
    }
}

/**
 * Owns ASR model metadata, selection, downloads, imports and deletions.
 *
 * Storage is `noBackupFilesDir/asr-models`, so weights and their manifest are never part of
 * Android backup exports. Only verified files enter `installed`; a `.part` prefix is visible
 * as [AsrModelStatus.PAUSED] and is resumed only when the server returns the exact requested
 * range. Every file operation in the suspend paths runs under
 * [ResourceGovernor]'s shared `io` budget.
 *
 * Native inference is intentionally absent here. [LocalAsrEngine] loads a model only when a
 * transcript is requested, so ordinary video playback never opens an ASR session.
 */
class AsrModelManager(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val scheduler: AsrWorkScheduler = AsrWorkScheduler.Direct,
    root: File = File(context.applicationContext.noBackupFilesDir, "asr-models"),
    client: OkHttpClient = defaultAsrHttpClient(),
) {
    private val appContext = context.applicationContext
    val threadLimit: Int = asrThreadLimit()

    private val installer = AsrModelInstaller(root, client)
    private val catalogStore = AsrCatalogStore(File(root, "catalog.json"))
    private val initialModels = catalogStore.load()
    private val preferences = appContext.getSharedPreferences(
        AsrSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val settingsStore = AsrSettingsStore(preferences, threadLimit)
    private val initialSettings = settingsStore.load()
    private val initialInstalled = discoverInstalled(initialModels)
    private val initialSelected = initialSettings.selectedId?.takeIf { initialInstalled.containsKey(it) }
        ?: initialInstalled.keys.singleOrNull()
    private val initialStates = initialModels.associate { model ->
        model.id to stateFor(model, initialInstalled)
    }

    private val mutableModels = MutableStateFlow(initialModels)
    val models: StateFlow<List<AsrModelDescriptor>> = mutableModels.asStateFlow()

    private val mutableStates = MutableStateFlow(initialStates)
    val states: StateFlow<Map<String, AsrModelState>> = mutableStates.asStateFlow()

    private val mutableInstalled = MutableStateFlow(initialInstalled)
    /** Only models with a complete manifest and exact byte count; hashes are rechecked on load. */
    val installed: StateFlow<Map<String, AsrModelDescriptor>> = mutableInstalled.asStateFlow()

    private val mutableSelectedId = MutableStateFlow(initialSelected)
    val selectedId: StateFlow<String?> = mutableSelectedId.asStateFlow()

    private val mutableSettings = MutableStateFlow(initialSettings.copy(selectedId = initialSelected))
    val settings: StateFlow<AsrSettings> = mutableSettings.asStateFlow()

    private val serial = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()

    init {
        if (initialSettings.selectedId != initialSelected) settingsStore.saveSelected(initialSelected)
    }

    fun model(id: String): AsrModelDescriptor? = mutableModels.value.firstOrNull { it.id == id }

    fun installedModel(id: String): AsrModelDescriptor? = mutableInstalled.value[id]

    fun selectedModel(): AsrModelDescriptor? =
        mutableSelectedId.value?.let { mutableInstalled.value[it] }

    /** Selects only a model that already passed installation verification. */
    fun select(id: String) {
        val model = requireModel(id)
        require(mutableInstalled.value.containsKey(model.id)) { "请先下载并校验该 ASR 模型" }
        updateSelection(model.id)
    }

    fun setWifiOnly(value: Boolean) {
        settingsStore.saveWifiOnly(value)
        mutableSettings.update { it.copy(wifiOnly = value) }
    }

    fun setThreads(value: Int) {
        val clamped = value.coerceIn(1, threadLimit)
        settingsStore.saveThreads(clamped)
        mutableSettings.update { it.copy(threads = clamped) }
    }

    /**
     * Persists the requested acceleration backend. Availability is checked by
     * [LocalAsrEngine.selectBackend] before this is called, so an unavailable explicit backend
     * cannot be silently stored as if it were usable.
     */
    fun setBackend(value: AsrBackend) {
        settingsStore.saveBackend(value)
        mutableSettings.update { it.copy(backend = value) }
    }

    /**
     * Re-reads the ASR preferences from disk (backup restore replaces the XML file behind the
     * already created SharedPreferences instance). A restored selection is accepted only when
     * that model is installed on this device; the stored thread count is clamped by the store.
     */
    fun reloadSettings() {
        val loaded = settingsStore.load()
        val selected = loaded.selectedId?.takeIf { mutableInstalled.value.containsKey(it) }
            ?: mutableInstalled.value.keys.singleOrNull()
        if (loaded.selectedId != selected) settingsStore.saveSelected(selected)
        mutableSelectedId.value = selected
        mutableSettings.value = loaded.copy(selectedId = selected)
    }

    /** Starts a resumable download on the manager scope; [downloadNow] is the suspend core. */
    fun download(id: String) {
        val model = requireModel(id)
        if (mutableInstalled.value.containsKey(id) || jobs[id]?.isActive == true) return
        setState(model, AsrModelState(
            status = AsrModelStatus.DOWNLOADING,
            completedBytes = installer.partialBytes(model).coerceAtMost(model.bytes),
            totalBytes = model.bytes,
            message = "等待下载",
        ))
        launchTracked(id) {
            scheduler.schedule("asr-download:$id", model.identity) { downloadNow(id) }
        }
    }

    /**
     * Downloads into `.staging/<id>/model.bin.part`, verifies the exact byte count and
     * SHA-256, then atomically publishes the directory. Cancellation leaves the verified
     * prefix in place for a later Range resume.
     */
    suspend fun downloadNow(id: String) {
        val model = requireModel(id)
        if (mutableInstalled.value.containsKey(id)) return
        try {
            setState(model, AsrModelState(
                status = AsrModelStatus.DOWNLOADING,
                completedBytes = installer.partialBytes(model).coerceAtMost(model.bytes),
                totalBytes = model.bytes,
                message = "等待其他模型操作",
            ))
            serial.withLock {
                if (mutableInstalled.value.containsKey(id)) return@withLock
                checkDownloadNetwork()
                setState(model, AsrModelState(
                    status = AsrModelStatus.DOWNLOADING,
                    completedBytes = installer.partialBytes(model).coerceAtMost(model.bytes),
                    totalBytes = model.bytes,
                    message = "下载中",
                ))
                ResourceGovernor.shared.use("io") {
                    withContext(Dispatchers.IO) {
                        installer.download(model,
                            onProgress = { written ->
                                setState(model, AsrModelState(
                                    status = AsrModelStatus.DOWNLOADING,
                                    completedBytes = written.coerceAtMost(model.bytes),
                                    totalBytes = model.bytes,
                                    message = "下载中",
                                ))
                            },
                            onVerifying = {
                                setState(model, AsrModelState(
                                    status = AsrModelStatus.VERIFYING,
                                    completedBytes = model.bytes,
                                    totalBytes = model.bytes,
                                    message = "校验大小与 SHA-256",
                                ))
                            })
                    }
                }
                markInstalled(model)
                setState(model, AsrModelState(
                    status = AsrModelStatus.READY,
                    completedBytes = model.bytes,
                    totalBytes = model.bytes,
                ))
            }
        } catch (cancelled: CancellationException) {
            setState(model, pausedOrIdle(model))
            throw cancelled
        } catch (failure: Exception) {
            setState(model, AsrModelState(
                status = AsrModelStatus.FAILED,
                completedBytes = installer.partialBytes(model).coerceAtMost(model.bytes),
                totalBytes = model.bytes,
                message = failure.message ?: "ASR 模型下载失败",
            ))
            throw failure
        }
    }

    /**
     * Imports a local file selected by the user. The input must match the chosen descriptor
     * exactly; the manager closes [input] in all paths. There is no trust in a filename,
     * MIME type or container metadata.
     */
    fun import(id: String, input: InputStream) {
        val model = requireModel(id)
        if (mutableInstalled.value.containsKey(id) || jobs[id]?.isActive == true) {
            runCatching { input.close() }
            return
        }
        setState(model, AsrModelState(
            status = AsrModelStatus.VERIFYING,
            completedBytes = 0L,
            totalBytes = model.bytes,
            message = "等待导入",
        ))
        launchTracked(id) {
            try {
                scheduler.schedule("asr-import:$id", model.identity) { importNow(id, input) }
            } finally {
                // The executor owns the stream while the task runs. Closing here also covers
                // the case where the task was paused before its action was ever claimed.
                runCatching { input.close() }
            }
        }
    }

    suspend fun importNow(id: String, input: InputStream) {
        val model = requireModel(id)
        if (mutableInstalled.value.containsKey(id)) {
            runCatching { input.close() }
            return
        }
        try {
            setState(model, AsrModelState(
                status = AsrModelStatus.VERIFYING,
                completedBytes = 0L,
                totalBytes = model.bytes,
                message = "等待其他模型操作",
            ))
            serial.withLock {
                if (mutableInstalled.value.containsKey(id)) {
                    runCatching { input.close() }
                    return@withLock
                }
                setState(model, AsrModelState(
                    status = AsrModelStatus.VERIFYING,
                    completedBytes = 0L,
                    totalBytes = model.bytes,
                    message = "导入并校验",
                ))
                ResourceGovernor.shared.use("io") {
                    withContext(Dispatchers.IO) {
                        input.use { source ->
                            installer.import(model, source,
                                onProgress = { written ->
                                    setState(model, AsrModelState(
                                        status = AsrModelStatus.VERIFYING,
                                        completedBytes = written.coerceAtMost(model.bytes),
                                        totalBytes = model.bytes,
                                        message = "导入中",
                                    ))
                                },
                                onVerifying = {
                                    setState(model, AsrModelState(
                                        status = AsrModelStatus.VERIFYING,
                                        completedBytes = model.bytes,
                                        totalBytes = model.bytes,
                                        message = "校验大小与 SHA-256",
                                    ))
                                })
                        }
                    }
                }
                markInstalled(model)
                setState(model, AsrModelState(
                    status = AsrModelStatus.READY,
                    completedBytes = model.bytes,
                    totalBytes = model.bytes,
                ))
            }
        } catch (cancelled: CancellationException) {
            setState(model, pausedOrIdle(model))
            throw cancelled
        } catch (failure: Exception) {
            setState(model, AsrModelState(
                status = AsrModelStatus.FAILED,
                completedBytes = 0L,
                totalBytes = model.bytes,
                message = failure.message ?: "ASR 模型导入失败",
            ))
            throw failure
        }
    }

    /**
     * Cancels the manager waiter and pauses the durable task behind it. The task scheduler
     * call and the wait for the executor to release the file/native work are NonCancellable,
     * so a caller that is itself cancelled cannot return while the download still writes.
     */
    fun pause(id: String) {
        val model = requireModel(id)
        val previous = jobs[id]
        previous?.cancel()
        val pauseJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(NonCancellable) {
                    scheduler.pause("asr-download:$id")
                    scheduler.pause("asr-import:$id")
                    previous?.join()
                }
                setState(model, pausedOrIdle(model))
            } finally {
                removeJob(id)
            }
        }
        jobs[id] = pauseJob
        pauseJob.start()
    }

    /** Discards only the resumable staging directory, never an installed model. */
    fun discard(id: String) {
        val model = requireModel(id)
        val previous = jobs[id]
        previous?.cancel()
        val discardJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(NonCancellable) {
                    // Let the cancelled waiter settle its durable task first; issuing a cancel
                    // concurrently with that pause could race the final task state.
                    previous?.join()
                    scheduler.cancel("asr-download:$id")
                    scheduler.cancel("asr-import:$id")
                }
                ResourceGovernor.shared.use("io") {
                    withContext(Dispatchers.IO) { installer.discardPartial(model) }
                }
                setState(model, idleState(model))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                setState(model, AsrModelState(
                    status = AsrModelStatus.FAILED,
                    completedBytes = installer.partialBytes(model),
                    totalBytes = model.bytes,
                    message = failure.message ?: "无法丢弃暂存文件",
                ))
            } finally {
                removeJob(id)
            }
        }
        jobs[id] = discardJob
        discardJob.start()
    }

    /**
     * Deletes the installed file and staging prefix after stopping the injected native
     * session. Callers must pass the engine's `stopAndAwait` (or their own equivalent) so a
     * native transcript can never keep reading a deleted model.
     */
    fun delete(id: String, stopAndAwait: suspend () -> Unit = {}) {
        scope.launch { deleteNow(id, stopAndAwait) }
    }

    suspend fun deleteNow(id: String, stopAndAwait: suspend () -> Unit = {}) {
        val model = requireModel(id)
        try {
            setState(model, AsrModelState(
                status = AsrModelStatus.VERIFYING,
                completedBytes = 0L,
                totalBytes = model.bytes,
                message = "停止并卸载模型",
            ))
            jobs[id]?.cancelAndJoin()
            withContext(NonCancellable) {
                scheduler.cancel("asr-download:$id")
                scheduler.cancel("asr-import:$id")
            }
            stopAndAwait()
            ResourceGovernor.shared.use("io") {
                withContext(Dispatchers.IO) { installer.delete(model) }
            }
            mutableInstalled.update { it - id }
            if (mutableSelectedId.value == id) updateSelection(null)
            setState(model, AsrModelState(totalBytes = model.bytes))
        } catch (cancelled: CancellationException) {
            setState(model, idleState(model))
            throw cancelled
        } catch (failure: Exception) {
            setState(model, AsrModelState(
                status = AsrModelStatus.FAILED,
                completedBytes = installer.partialBytes(model),
                totalBytes = model.bytes,
                message = failure.message ?: "删除 ASR 模型失败",
            ))
            throw failure
        }
    }

    /** Full byte-count and SHA-256 verification, used by [LocalAsrEngine] before `create`. */
    suspend fun verifyInstalled(model: AsrModelDescriptor) {
        withContext(Dispatchers.IO) {
            ResourceGovernor.shared.use("io") { installer.verifyInstalled(model) }
        }
    }

    fun modelFile(model: AsrModelDescriptor): File = installer.modelFile(model)

    private fun launchTracked(id: String, block: suspend () -> Unit) {
        jobs[id] = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                // A TaskScreen pause/cancel or a data barrier stops the durable task; reflect
                // that in the manager without turning it into a failure.
                model(id)?.let { setState(it, pausedOrIdle(it)) }
                throw cancelled
            } catch (failure: Exception) {
                // The suspend core already records FAILED for normal operation failures.
                // This also covers a scheduler that rejects the work before it starts.
                val model = model(id) ?: return@launch
                if (mutableStates.value[id]?.status != AsrModelStatus.FAILED) {
                    setState(model, AsrModelState(
                        status = AsrModelStatus.FAILED,
                        completedBytes = installer.partialBytes(model).coerceAtMost(model.bytes),
                        totalBytes = model.bytes,
                        message = failure.message ?: "ASR 模型操作失败",
                    ))
                }
            } finally {
                removeJob(id)
            }
        }
    }

    private suspend fun removeJob(id: String) {
        val current = kotlinx.coroutines.currentCoroutineContext()[Job]
        if (current != null) jobs.remove(id, current)
    }

    private fun requireModel(id: String): AsrModelDescriptor =
        model(id) ?: error("未知 ASR 模型：$id")

    private fun updateSelection(id: String?) {
        settingsStore.saveSelected(id)
        mutableSelectedId.value = id
        mutableSettings.update { it.copy(selectedId = id) }
    }

    private fun markInstalled(model: AsrModelDescriptor) {
        mutableInstalled.update { it + (model.id to model) }
        if (mutableSelectedId.value == null) updateSelection(model.id)
    }

    private fun setState(model: AsrModelDescriptor, state: AsrModelState) {
        mutableStates.update { it + (model.id to state.copy(totalBytes = model.bytes)) }
    }

    private fun idleState(model: AsrModelDescriptor): AsrModelState =
        stateFor(model, mutableInstalled.value)

    private fun pausedOrIdle(model: AsrModelDescriptor): AsrModelState {
        if (mutableInstalled.value.containsKey(model.id)) return idleState(model)
        val partial = installer.partialBytes(model).coerceAtMost(model.bytes)
        return if (partial > 0L) AsrModelState(
            status = AsrModelStatus.PAUSED,
            completedBytes = partial,
            totalBytes = model.bytes,
            message = "已暂停，可继续下载",
        ) else AsrModelState(totalBytes = model.bytes)
    }

    private fun discoverInstalled(models: List<AsrModelDescriptor>): Map<String, AsrModelDescriptor> {
        val byId = models.associateBy { it.id }
        return installer.installedDescriptors().mapNotNull { installed ->
            val known = byId[installed.id] ?: return@mapNotNull null
            if (known.version == installed.version && known.sha256 == installed.sha256 &&
                known.bytes == installed.bytes) known else null
        }.associateBy { it.id }
    }

    private fun stateFor(
        model: AsrModelDescriptor,
        installed: Map<String, AsrModelDescriptor>,
    ): AsrModelState {
        if (installed.containsKey(model.id)) return AsrModelState(
            status = AsrModelStatus.READY,
            completedBytes = model.bytes,
            totalBytes = model.bytes,
        )
        val partial = installer.partialBytes(model).coerceAtMost(model.bytes)
        return if (partial > 0L) AsrModelState(
            status = AsrModelStatus.PAUSED,
            completedBytes = partial,
            totalBytes = model.bytes,
        ) else AsrModelState(totalBytes = model.bytes)
    }

    private fun checkDownloadNetwork() {
        if (!mutableSettings.value.wifiOnly) return
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
            ?: error("无法读取网络状态")
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?: error("当前没有可用网络")
        require(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            "当前不是 Wi-Fi；可在本页关闭“仅 Wi-Fi 下载”"
        }
    }
}
