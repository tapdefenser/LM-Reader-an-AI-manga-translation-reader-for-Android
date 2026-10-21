package com.lmreader.core.translation.asr

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
 * JNI surface implemented by the root task in `liblmreader_asr.so`. Input samples are always
 * mono 16 kHz floats. `transcribe` returns
 * `{"segments":[{"startMs":<long>,"endMs":<long>,"text":"..."}]}`.
 */
object WhisperNative {
    init {
        System.loadLibrary("lmreader_asr")
    }

    external fun create(modelPath: String): Long
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
    val inUse: Boolean = false,
)

/**
 * Retains one selected whisper.cpp session. The handle is created only by [transcribe], never
 * at construction or during ordinary video playback.
 *
 * Lock order is always `nativeMutex -> callLock`. `callLock` is a short lock around the handle
 * lifetime: abort and release both take it, so a freed handle is never aborted and a running
 * native call is never released. Cancellation uses a child watcher because
 * `invokeOnCompletion` runs only after the blocking native call returns.
 */
class LocalAsrEngine(private val manager: AsrModelManager) {
    private val nativeMutex = Mutex()
    private val callLock = Mutex()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "local-asr").apply { isDaemon = true }
    }
    private var session: Session? = null
    private var activeHandle: Long = 0L
    private var activeDone: CompletableDeferred<Unit>? = null
    private val mutableLoaded = MutableStateFlow<List<LoadedAsrResource>>(emptyList())
    val loadedResources: StateFlow<List<LoadedAsrResource>> = mutableLoaded.asStateFlow()

    private data class Session(
        val descriptor: AsrModelDescriptor,
        val handle: Long,
        val path: String,
        val threads: Int,
    )

    suspend fun transcribe(samples: FloatArray, language: String, modelId: String? = null): List<AsrSegment> {
        require(samples.isNotEmpty()) { "音频为空" }
        val normalizedLanguage = language.trim().lowercase(Locale.ROOT)
        require(normalizedLanguage == "auto" || normalizedLanguage.matches(Regex("[a-z]{2,3}"))) {
            "语言代码应为 ISO 639-1 二/三字母代码，或 auto"
        }
        val descriptor = (if(modelId != null) manager.installedModel(modelId) else manager.selectedModel())
            ?: error("请先在设置中选择并安装本地 ASR 模型")
        val threads = manager.settings.value.threads.coerceIn(1, manager.threadLimit)
        return nativeMutex.withLock {
            ResourceGovernor.shared.use(mapOf("asr" to 1L, "nmt" to 1L, "compute" to 2L)) {
                val handle = ensureSession(descriptor, threads)
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

    /** Aborts an in-flight native call, waits for it to return, then releases the session. */
    suspend fun stopAndAwait() = withContext(NonCancellable) {
        val done = callLock.withLock {
            if (activeHandle != 0L) runCatching { WhisperNative.abort(activeHandle) }
            activeDone
        }
        done?.await()
        nativeMutex.withLock {
            callLock.withLock { releaseLocked(0L) }
        }
    }

    suspend fun release() = stopAndAwait()

    private suspend fun ensureSession(model: AsrModelDescriptor, threads: Int): Long {
        val existing = session
        if (existing != null && existing.descriptor.id == model.id &&
            existing.descriptor.sha256 == model.sha256 && existing.descriptor.version == model.version &&
            existing.threads == threads && File(existing.path).isFile) {
            return existing.handle
        }
        if (existing != null) callLock.withLock { releaseLocked(0L) }
        // Re-hash the installed file on every load; a manifest is not proof that the bytes on
        // disk are still the ones the user installed.
        manager.verifyInstalled(model)
        currentCoroutineContext().ensureActive()
        val path = manager.modelFile(model).absolutePath
        val handle = try {
            WhisperNative.create(path)
        } catch (failure: LinkageError) {
            throw IllegalStateException("当前设备无法加载本地 ASR 运行库；需要 arm64-v8a 或 x86_64", failure)
        }
        require(handle != 0L) { "本地 ASR 运行库无法加载模型" }
        try {
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            callLock.withLock { runCatching { WhisperNative.release(handle) } }
            throw cancelled
        }
        session = Session(model, handle, path, threads)
        mutableLoaded.value = listOf(LoadedAsrResource(
            id = model.id,
            label = model.label,
            version = model.version,
            modelPath = path,
            threads = threads,
        ))
        return handle
    }

    private fun releaseLocked(onlyHandle: Long) {
        val current = session ?: return
        if (onlyHandle != 0L && current.handle != onlyHandle) return
        runCatching { WhisperNative.release(current.handle) }
        if (session === current) session = null
        mutableLoaded.value = emptyList()
    }

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
}
