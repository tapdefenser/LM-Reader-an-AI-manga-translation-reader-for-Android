package com.lmreader.core.translation

import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors

internal object BergamotNative {
    init { System.loadLibrary("lmreader_translation") }
    external fun load(key: ByteArray, config: ByteArray)
    external fun translate(key: ByteArray, text: ByteArray): ByteArray
    external fun unload(key: ByteArray)
    external fun release()
    external fun maximumConcurrency(reset: Boolean = false): Int
}

/** A process-wide lock also prevents removal of files while native inference uses them. */
internal object TranslationRuntime {
    val mutex = Mutex()
    val dispatcher = Executors.newFixedThreadPool(8) { task -> Thread(task, "local-translation") }.asCoroutineDispatcher()
    val resources = MutableStateFlow<List<LoadedEngineResource>>(emptyList())
    val pool = PrioritySessionPool()
    class Slot { val mutex = Mutex(); val keys = mutableSetOf<String>() }
    val slots = List(8) { Slot() }
    val pins = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    val costs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val allocation = Mutex()
}

class BergamotTextTranslator(private val catalog: () -> TranslationModelCatalog, private val installer: ModelPackageInstaller,
    private val resources: () -> MachineTranslationResources = { MachineTranslationResources() },
    private val availableMemory: () -> Long = { Runtime.getRuntime().maxMemory() }): LocalTextTranslator {
    val loadedResources = TranslationRuntime.resources.asStateFlow()
    val activeTasks = TranslationRuntime.pool.active
    constructor(catalog: TranslationModelCatalog, installer: ModelPackageInstaller): this({catalog},installer)
    private fun signature(pack: TranslationModelPack) = "${pack.id}-${pack.version}:${installer.installedDirectory(pack).absolutePath}"
    private fun unload(slot: TranslationRuntime.Slot, key: String) {
        BergamotNative.unload(key.toByteArray(Charsets.UTF_8))
        slot.keys.remove(key)
        TranslationRuntime.resources.update { it.filterNot { resource -> resource.id == key } }
    }
    fun parallelism(source: LocalTranslationLanguage, target: LocalTranslationLanguage,
        engine: TranslationModelEngine = TranslationModelEngine.BERGAMOT): Int = capacity(catalog().route(source, target, engine))
    private fun capacity(route: List<TranslationModelPack>): Int {
        val routeBytes = route.sumOf { it.installedBytes + 160L * 1_048_576 }.coerceAtLeast(1)
        val settings = resources()
        val automatic = machineTranslationParallelism(settings, Runtime.getRuntime().availableProcessors(), availableMemory(), routeBytes)
        val signatures = route.map(::signature).toSet()
        val pinnedExtra = TranslationRuntime.pins.values.flatten().toSet().filterNot { it in signatures }.sumOf { TranslationRuntime.costs[it] ?: 0 }
        val budget = minOf(settings.memoryMegabytes.toLong() * 1_048_576, availableMemory() / 2)
        return minOf(automatic, ((budget - pinnedExtra).coerceAtLeast(0) / routeBytes).coerceIn(1, 8).toInt())
    }
    private suspend fun <T> session(route: List<TranslationModelPack>, limit: Int = capacity(route), action: suspend (List<String>) -> T): T =
        ResourceGovernor.shared.use(mapOf("nmt" to 1L, "compute" to 1L)) { TranslationRuntime.pool.use(limit, TranslationRuntime.slots.indices.sortedBy { index ->
            route.count { pack -> TranslationRuntime.resources.value.none { it.id == "${signature(pack)}:session:$index" } }
        }, liveLimit = { capacity(route) }) { index ->
            withContext(TranslationRuntime.dispatcher) { TranslationRuntime.slots[index].mutex.withLock {
                val slot = TranslationRuntime.slots[index]
                val required = route.map(::signature).toSet()
                val pinned = TranslationRuntime.pins.values.flatten().toSet()
                slot.keys.filterNot { it.substringBeforeLast(":session:") in required + pinned }.toList().forEach { key ->
                    unload(slot, key)
                }
                val keys = TranslationRuntime.allocation.withLock {
                val budget = resources().memoryMegabytes.toLong() * 1_048_576
                val missing = route.filter { "${signature(it)}:session:$index" !in slot.keys }.sumOf { it.installedBytes + 160L * 1_048_576 }
                fun retainedBytes() = TranslationRuntime.resources.value.sumOf { TranslationRuntime.costs[it.id.substringBeforeLast(":session:")] ?: 0 }
                if (retainedBytes() + missing > budget) TranslationRuntime.slots.forEachIndexed { otherIndex, other ->
                    if (otherIndex != index && other.mutex.tryLock()) try {
                        other.keys.toList().forEach { key ->
                            val base = key.substringBeforeLast(":session:")
                            val duplicate = slot.keys.any { it.substringBeforeLast(":session:") == base } || base in required
                            if (retainedBytes() + missing > budget && (base !in pinned || duplicate)) {
                                unload(other, key)
                            }
                        }
                    } finally { other.mutex.unlock() }
                }
                route.map { pack ->
                    val key = "${signature(pack)}:session:$index"
                    if (key !in slot.keys) {
                        installer.verifyInstalled(pack)
                        ensureActive()
                        try {
                            BergamotNative.load(key.toByteArray(Charsets.UTF_8), configuration(pack, installer.installedDirectory(pack)).toByteArray(Charsets.UTF_8))
                        }
                        catch (failure: LinkageError) { throw IllegalStateException("当前设备无法加载机翻运行库；需要 arm64-v8a 或 x86_64", failure) }
                        slot.keys += key
                        TranslationRuntime.costs[signature(pack)] = pack.installedBytes + 160L * 1_048_576
                        TranslationRuntime.resources.update { it + LoadedEngineResource(key,
                            InferenceEngineKind.TRANSLATION, "${pack.label} · ${pack.version}", "Bergamot CPU") }
                    }
                    key
                }
                }
                TranslationRuntime.resources.update { list -> list.map { if (it.id in keys) it.copy(inUse = true) else it } }
                try { action(keys) } finally {
                    TranslationRuntime.resources.update { list -> list.map { if (it.id in keys) it.copy(inUse = false) else it } }
                }
            } }
        } }
    /** Pin a prepared screen session so queue completion cannot unload its engines. */
    suspend fun pinModels(owner: String, routes: Set<Pair<LocalTranslationLanguage, LocalTranslationLanguage>>) =
        pinEngineModels(owner, routes.map { LocalTranslationRoute(it.first, it.second) }.toSet())
    suspend fun pinEngineModels(owner: String, routes: Set<LocalTranslationRoute>) {
        val packs = routes.flatMap { catalog().route(it.source, it.target, it.engine) }.distinctBy(::signature)
        packs.forEach { TranslationRuntime.costs[signature(it)] = it.installedBytes + 160L * 1_048_576 }
        TranslationRuntime.pins[owner] = packs.map(::signature).toSet()
    }
    suspend fun prepare(owner: String, routes: Set<Pair<LocalTranslationLanguage, LocalTranslationLanguage>>) =
        prepareEngineModels(owner, routes.map { LocalTranslationRoute(it.first, it.second) }.toSet())
    suspend fun prepareEngineModels(owner: String, routes: Set<LocalTranslationRoute>) {
        pinEngineModels(owner, routes)
        val packs = routes.flatMap { catalog().route(it.source, it.target, it.engine) }.distinctBy(::signature)
        if (packs.isEmpty()) return
        try {
            // Warm one clone. Cross-owner barriers must not retain global admission
            // while waiting for more clones; the remaining sessions load on demand.
            session(packs) { }
        } catch (failure: Throwable) { TranslationRuntime.pins.remove(owner); throw failure }
    }
    fun unpin(owner: String) { TranslationRuntime.pins.remove(owner) }
    override suspend fun translate(source: LocalTranslationLanguage, target: LocalTranslationLanguage,
        items: List<LocalTranslationText>, onProgress: (Int, Int) -> Unit): LocalTranslationResult =
        translate(TranslationModelEngine.BERGAMOT, source, target, items, onProgress)
    suspend fun translate(engine: TranslationModelEngine, source: LocalTranslationLanguage, target: LocalTranslationLanguage,
        items: List<LocalTranslationText>, onProgress: (Int, Int) -> Unit = { _, _ -> }): LocalTranslationResult {
        TranslationModelEngine.requireSupported(engine.name)
        require(items.size <= 256) { "一次最多翻译 256 段文本" }
        require(items.all { it.id.isNotBlank() && it.sourceText.length <= 4096 && '\u0000' !in it.sourceText }) { "文本 ID 不能为空；单段最多 4096 字符且不可含 NUL" }
        require(items.map { it.id }.distinct().size == items.size) { "文本 ID 重复" }
        val route = catalog().route(source, target, engine)
        val started = System.nanoTime()
        if (items.all { it.sourceText.isBlank() } || route.isEmpty()) return LocalTranslationResult(
            items.map { LocalTranslatedText(it.id, it.sourceText, it.sourceText) }, emptyList(), 0, "Bergamot · CPU")
        val result = items.mapIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            val text = if (item.sourceText.isBlank()) item.sourceText else session(route) { keys ->
                var text = item.sourceText
                keys.forEach { key ->
                    currentCoroutineContext().ensureActive()
                    try {
                        text = BergamotNative.translate(key.toByteArray(Charsets.UTF_8), text.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8)
                    } catch (failure: Throwable) {
                        // An aborted native request may leave decoder state behind.
                        // Retry recreates only this session, while other slots keep running.
                        unload(TranslationRuntime.slots[key.substringAfterLast(":session:").toInt()], key)
                        throw failure
                    }
                    currentCoroutineContext().ensureActive()
                    require(text.isNotBlank()) { "模型返回空译文：${item.id}" }
                }
                text
            }
            // Return the permit at text boundaries; a 256-item batch cannot hold
            // the only decoder while another interactive request is waiting.
            onProgress(index + 1, items.size)
            LocalTranslatedText(item.id, item.sourceText, text)
        }
        return LocalTranslationResult(result, route.map { it.id }, (System.nanoTime() - started) / 1_000_000, "Bergamot · CPU")
    }

    override suspend fun releaseModels() = retainModels(emptySet())

    /** Reconcile resident packs without waiting for or unloading occupied shared slots. */
    suspend fun retainModels(routes: Set<Pair<LocalTranslationLanguage, LocalTranslationLanguage>>) =
        retainEngineModels(routes.map { LocalTranslationRoute(it.first, it.second) }.toSet())
    suspend fun retainEngineModels(routes: Set<LocalTranslationRoute>) = TranslationRuntime.mutex.withLock {
        withContext(TranslationRuntime.dispatcher) {
            val required = routes.flatMap { catalog().route(it.source, it.target, it.engine) }.map(::signature).toSet() + TranslationRuntime.pins.values.flatten()
            TranslationRuntime.slots.forEach { slot ->
                if (!slot.mutex.tryLock()) return@forEach
                try {
                    slot.keys.filterNot { it.substringBeforeLast(":session:") in required }.toList().forEach { key ->
                        unload(slot, key)
                    }
                } finally { slot.mutex.unlock() }
            }
        }
    }

    suspend fun removePack(pack: TranslationModelPack) = TranslationRuntime.mutex.withLock {
        withContext(TranslationRuntime.dispatcher) {
            val acquired = mutableListOf<TranslationRuntime.Slot>()
            try {
            TranslationRuntime.slots.forEach { slot -> slot.mutex.lock(); acquired += slot }
            check(TranslationRuntime.resources.value.none { it.id.startsWith("${pack.id}-${pack.version}:") }) {
                "此语言包仍已加载，请先在翻译队列中全部暂停并卸载引擎，再删除语言包"
            }
            installer.remove(pack)
            } finally { acquired.asReversed().forEach { it.mutex.unlock() } }
        }
    }

    companion object {
        internal fun configuration(pack: TranslationModelPack, directory: File): String {
            fun path(role: String): String = "'" + File(directory, pack.files.single { it.role == role }.name).absolutePath.replace("'", "''") + "'"
            val vocab = if (pack.files.any { it.role == "vocab" }) listOf(path("vocab"), path("vocab")) else listOf(path("srcVocab"), path("trgVocab"))
            return """
                models: [${path("model")}]
                vocabs: [${vocab.joinToString(", ")}]
                shortlist: [${path("lexicalShortlist")}, false]
                beam-size: 1
                normalize: 1.0
                word-penalty: 0
                max-length-break: 256
                max-length-factor: 3.0
                mini-batch-words: 1024
                workspace: 128
                cpu-threads: 1
                gemm-precision: int8shiftAlphaAll
                skip-cost: true
                quiet: true
                quiet-translation: true
                ssplit-mode: paragraph
            """.trimIndent()
        }
    }
}
