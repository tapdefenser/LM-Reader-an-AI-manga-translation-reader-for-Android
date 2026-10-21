package com.lmreader.core.translation.asr

import android.util.Log
import com.lmreader.core.model.ResourceGovernor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * JNI surface implemented by `liblmreader_asr.so`. Input samples are always mono 16 kHz floats.
 * `transcribe` returns `{"segments":[{"startMs":<long>,"endMs":<long>,"text":"..."}]}`.
 *
 * [capabilities] reports what this build and device can actually do. [create] returns a handle
 * whose [describe] payload contains the backend that was actually used, not merely requested.
 */
object WhisperNative {
    init {
        System.loadLibrary("lmreader_asr")
    }

    external fun capabilities(): String
    external fun create(modelPath: String, backend: String): Long
    external fun describe(handle: Long): String
    external fun transcribe(handle: Long, samples: FloatArray, language: String, threads: Int): String
    external fun abort(handle: Long)
    external fun release(handle: Long)
}

data class AsrSegment(val startMs: Long, val endMs: Long, val text: String)

data class LoadedAsrResource(
    val id: String,
    val label: String,
    val version: String,
    val modelPath: String,
    val threads: Int,
    val backend: AsrBackend,
    val device: String?,
    val deviceDescription: String?,
    val fallbackReason: String?,
    val sessionIdentity: String,
    val inUse: Boolean = false,
)

/**
 * Retains one selected whisper.cpp session. The handle is created only by [transcribe], never
 * at construction or during ordinary video playback.
 *
 * Lock order is always `ResourceGovernor("asr") -> nativeMutex -> callLock`. `callLock` is a
 * short lock around the handle lifetime: abort and release both take it, so a freed handle is
 * never aborted and a running native call is never released. Cancellation uses a child watcher
 * because `invokeOnCompletion` runs only after the blocking native call returns.
 */
class LocalAsrEngine(private val manager: AsrModelManager) {
    private val nativeMutex = Mutex()
    private val callLock = Mutex()
    private val capabilitiesMutex = Mutex()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "local-asr").apply { isDaemon = true }
    }
    private var session: Session? = null
    private var activeHandle: Long = 0L
    private var activeDone: CompletableDeferred<Unit>? = null
    private val mutableLoaded = MutableStateFlow<List<LoadedAsrResource>>(emptyList())
    val loadedResources: StateFlow<List<LoadedAsrResource>> = mutableLoaded.asStateFlow()

    private val mutableCapabilities = MutableStateFlow<AsrRuntimeCapabilities?>(null)
    val capabilities: StateFlow<AsrRuntimeCapabilities?> = mutableCapabilities.asStateFlow()

    private data class Session(
        val key: AsrSessionKey,
        val descriptor: AsrModelDescriptor,
        val handle: Long,
        val path: String,
        val threads: Int,
        val info: AsrLoadedSessionInfo,
    )

    suspend fun transcribe(
        samples: FloatArray,
        language: String,
        modelId: String? = null,
    ): List<AsrSegment> {
        require(samples.isNotEmpty()) { "音频为空" }
        val normalizedLanguage = language.trim().lowercase(Locale.ROOT)
        require(normalizedLanguage == "auto" || normalizedLanguage.matches(Regex("[a-z]{2,3}"))) {
            "语言代码应为 ISO 639-1 二/三字母代码，或 auto"
        }
        val descriptor = (if(modelId != null) manager.installedModel(modelId) else manager.selectedModel())
            ?: error("请先在设置中选择并安装本地 ASR 模型")
        val settings = manager.settings.value
        val threads = settings.threads.coerceIn(1, manager.threadLimit)
        val backend = settings.backend
        // 自动后端固定走 CPU，只有显式选择 GPU 后端时才占用视觉资源。
        val usesGpu = backend == AsrBackend.VULKAN || backend == AsrBackend.OPENCL
        val resources = if(usesGpu) mapOf("asr" to 1L, "nmt" to 1L, "vision" to 1L, "compute" to 1L)
            else mapOf("asr" to 1L, "nmt" to 1L, "compute" to 2L)
        return ResourceGovernor.shared.use(resources) {
            nativeMutex.withLock {
                val handle = ensureSession(descriptor, backend, threads)
                val done = CompletableDeferred<Unit>()
                callLock.withLock {
                    activeHandle = handle
                    activeDone = done
                    markLoaded(descriptor, inUse = true)
                }
                var failure: Throwable? = null
                try {
                    val json = runNative(handle, samples, normalizedLanguage, threads)
                    val segments = parseSegments(json)
                    currentCoroutineContext().ensureActive()
                    segments
                } catch (throwable: Throwable) {
                    failure = throwable
                    throw throwable
                } finally {
                    withContext(NonCancellable) {
                        callLock.withLock {
                            activeHandle = 0L
                            activeDone = null
                            if (failure != null) releaseLocked(handle)
                            markLoaded(descriptor, inUse = false)
                            done.complete(Unit)
                        }
                    }
                }
            }
        }
    }

    /**
     * Aborts an in-flight native call without waiting for the ASR governor: the abort must be
     * able to interrupt the call that currently holds the permit. The release that follows is
     * governed again, so it cannot overlap another ASR session.
     */
    suspend fun stopAndAwait() = withContext(NonCancellable) {
        val done = callLock.withLock {
            if (activeHandle != 0L) runCatching { WhisperNative.abort(activeHandle) }
            activeDone
        }
        done?.await()
        ResourceGovernor.shared.use("asr") {
            nativeMutex.withLock {
                callLock.withLock { releaseLocked(0L) }
            }
        }
    }

    /** A completed source must not abort another video's newly admitted ASR call. */
    suspend fun release() = withContext(NonCancellable) {
        ResourceGovernor.shared.use("asr") {
            nativeMutex.withLock { callLock.withLock { releaseLocked(0L) } }
        }
    }

    /** Probes native capabilities once (and caches them) without loading a model. */
    suspend fun refreshCapabilities(): AsrRuntimeCapabilities = withContext(Dispatchers.IO) {
        capabilitiesMutex.withLock {
            val parsed = ResourceGovernor.shared.use(mapOf("asr" to 1L, "vision" to 1L, "compute" to 1L)) {
                val json = try {
                    WhisperNative.capabilities()
                } catch (failure: LinkageError) {
                    throw IllegalStateException(
                        "当前设备无法加载本地 ASR 运行库；需要 arm64-v8a 或 x86_64",
                        failure,
                    )
                }
                AsrRuntimeCapabilities.parse(json)
            }
            mutableCapabilities.value = parsed
            parsed
        }
    }

    /**
     * Closes the retained session before persisting a new backend. Explicit GPU selection is
     * rejected here when the native report says the backend is not actually available, so the
     * UI never stores a GPU label for a CPU session.
     */
    suspend fun selectBackend(backend: AsrBackend): AsrRuntimeCapabilities {
        stopAndAwait()
        val current = capabilities.value ?: refreshCapabilities()
        if (backend == AsrBackend.VULKAN || backend == AsrBackend.OPENCL) {
            val capability = current[backend]
            require(capability != null) { "${backend.label} 后端不可用：当前构建未提供该后端" }
            require(capability.compiled) {
                "${backend.label} 后端不可用：${capability.reason ?: "当前构建未编译该后端"}"
            }
            require(capability.available) {
                "${backend.label} 后端不可用：${capability.reason ?: "未检测到可用设备"}"
            }
        }
        manager.setBackend(backend)
        Log.i(TAG, "ASR backend selected=${backend.wireId}")
        return current
    }

    suspend fun selectModel(id: String) {
        stopAndAwait()
        manager.select(id)
    }

    suspend fun selectThreads(count: Int) {
        stopAndAwait()
        manager.setThreads(count)
    }

    private suspend fun ensureSession(
        model: AsrModelDescriptor,
        backend: AsrBackend,
        threads: Int,
    ): Long {
        val key = AsrSessionKey(
            modelId = model.id,
            version = model.version,
            sha256 = model.sha256,
            backend = backend,
            threads = threads,
        )
        val existing = session
        if (existing != null && existing.key == key && File(existing.path).isFile) {
            return existing.handle
        }
        if (existing != null) callLock.withLock { releaseLocked(0L) }
        // Re-hash the installed file on every load; a manifest is not proof that the bytes on
        // disk are still the ones the user installed.
        manager.verifyInstalled(model)
        currentCoroutineContext().ensureActive()
        val path = manager.modelFile(model).absolutePath
        val handle = try {
            WhisperNative.create(path, backend.wireId)
        } catch (failure: LinkageError) {
            throw IllegalStateException("当前设备无法加载本地 ASR 运行库；需要 arm64-v8a 或 x86_64", failure)
        }
        require(handle != 0L) { "本地 ASR 运行库无法加载模型" }
        val info = try {
            AsrLoadedSessionInfo.parse(WhisperNative.describe(handle))
        } catch (failure: Throwable) {
            callLock.withLock { runCatching { WhisperNative.release(handle) } }
            throw failure
        }
        if (backend != AsrBackend.AUTO && info.actualBackend != backend) {
            callLock.withLock { runCatching { WhisperNative.release(handle) } }
            throw IllegalStateException(
                "请求的 ${backend.label} 后端未实际启用（实际为 ${info.actualBackend.label}）；" +
                    "不会把 CPU 会话标记为 GPU",
            )
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            callLock.withLock { runCatching { WhisperNative.release(handle) } }
            throw cancelled
        }
        val loaded = Session(key, model, handle, path, threads, info)
        session = loaded
        mutableLoaded.value = listOf(loaded.toResource(inUse = false))
        Log.i(
            TAG,
            "ASR session loaded model=${model.id} backend=${info.actualBackend.wireId} " +
                "device=${info.device ?: "-"} fallback=${info.fallbackReason ?: "-"}",
        )
        return handle
    }

    private fun releaseLocked(onlyHandle: Long) {
        val current = session ?: return
        if (onlyHandle != 0L && current.handle != onlyHandle) return
        runCatching { WhisperNative.release(current.handle) }
        if (session === current) session = null
        mutableLoaded.value = emptyList()
        Log.i(TAG, "ASR session released model=${current.descriptor.id}")
    }

    private fun Session.toResource(inUse: Boolean): LoadedAsrResource = LoadedAsrResource(
        id = descriptor.id,
        label = descriptor.label,
        version = descriptor.version,
        modelPath = path,
        threads = threads,
        backend = info.actualBackend,
        device = info.device,
        deviceDescription = info.deviceDescription,
        fallbackReason = info.fallbackReason,
        sessionIdentity = key.identity,
        inUse = inUse,
    )

    private fun markLoaded(model: AsrModelDescriptor, inUse: Boolean) {
        mutableLoaded.update { resources ->
            resources.map {
                if (it.id == model.id) it.copy(inUse = inUse) else it
            }
        }
    }

    /**
     * Runs the blocking native call on one dedicated thread. A child watcher is cancelled
     * with the caller and calls abort immediately; the finally block then waits for the native
     * future to finish before the mutex/state can be released.
     */
    private suspend fun runNative(
        handle: Long,
        samples: FloatArray,
        language: String,
        threads: Int,
    ): String {
        val future = CompletableFuture<String>()
        val completed = AtomicBoolean(false)
        executor.execute {
            try {
                val value = WhisperNative.transcribe(handle, samples, language, threads)
                completed.set(true)
                future.complete(value)
            } catch (throwable: Throwable) {
                completed.set(true)
                future.completeExceptionally(throwable)
            }
        }
        val watcher = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    if (!completed.get()) {
                        callLock.withLock {
                            if (activeHandle == handle) {
                                runCatching { WhisperNative.abort(handle) }
                            }
                        }
                    }
                }
            }
        }
        try {
            return awaitNative(future)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (future.isDone) watcher.cancelAndJoin() else watcher.join()
                // Never release or reuse the handle while the native call is still running.
                runCatching { future.join() }
            }
        }
    }

    private suspend fun awaitNative(future: CompletableFuture<String>): String =
        suspendCancellableCoroutine { continuation ->
            future.whenComplete { value, error ->
                if (!continuation.isActive) return@whenComplete
                if (error != null) {
                    continuation.resumeWithException(unwrap(error))
                } else {
                    continuation.resume(value)
                }
            }
        }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while (current is ExecutionException && current.cause != null) {
            current = current.cause!!
        }
        return current
    }

    private fun parseSegments(json: String): List<AsrSegment> {
        require(json.length <= 8_388_608) { "ASR 返回结果过大" }
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }
            .getOrElse { throw IllegalStateException("ASR 返回了无效 JSON", it) }
        val segments = root["segments"]?.jsonArray ?: error("ASR 返回缺少 segments")
        require(segments.size <= 100_000) { "ASR 返回的片段过多" }
        var characters = 0L
        return segments.map { element ->
            val item = element.jsonObject
            val startMs = item["startMs"]?.jsonPrimitive?.longOrNull
                ?: error("ASR 片段缺少 startMs")
            val endMs = item["endMs"]?.jsonPrimitive?.longOrNull
                ?: error("ASR 片段缺少 endMs")
            require(startMs >= 0L && endMs >= startMs) { "ASR 片段的时间范围无效" }
            val text = (item["text"]?.jsonPrimitive?.contentOrNull ?: "")
                .replace("\u0000", "")
                .trim()
            require(text.length <= 100_000) { "ASR 片段文本过长" }
            characters += text.length
            require(characters <= 4_000_000) { "ASR 返回文本总量过大" }
            AsrSegment(startMs, endMs, text)
        }
    }

    private companion object {
        const val TAG = "LMReaderAsr"
    }
}
