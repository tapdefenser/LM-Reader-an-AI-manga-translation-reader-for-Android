package com.lmreader.core.translation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.lmreader.core.model.*
import com.lmreader.core.translation.asr.AsrWorkScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.InputStream

/** Downloads belong to the process; installed descriptors remain usable across catalog refreshes. */
class TranslationModelManager(private val context: Context, val catalogs: TranslationCatalogStore,
    val installer: ModelPackageInstaller, val sources: TranslationSourceStore,
    private val fallback: TranslationModelCatalog,
    private val scheduler: AsrWorkScheduler = AsrWorkScheduler.Direct) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val jobs=mutableMapOf<String,Job>()
    private val serial=Semaphore(1)
    @Volatile private var known=installer.knownPackages(fallback.packs)
    private val retainedMutable=MutableStateFlow(known)
    val retainedPacks=retainedMutable.asStateFlow()
    private val installedMutable=MutableStateFlow(installed())
    val installedPacks=installedMutable.asStateFlow()
    private val mutable=MutableStateFlow(fallback.packs.associate {it.id to idleState(it)})
    val states=mutable.asStateFlow()
    private val catalogsReady=CompletableDeferred<Unit>()
    init {
        scope.launch {sources.settings.collectLatest {
            try {catalogs.select(it)} finally {catalogsReady.complete(Unit)}
        }}
        scope.launch {catalogs.state.map {it.catalog}.distinctUntilChanged().collect {refreshStates(it)}}
    }
    /** A task executor can start before the asynchronous source/catalog selection finishes. */
    suspend fun awaitCatalogReady() {catalogsReady.await()}
    private fun installed()=known.filter(installer::isInstalled).associateBy {it.id}
    fun installedCatalog()=TranslationModelCatalog.fromPacks(installedPacks.value.values.toList())
    /**
     * Resolves the descriptor the UI asked for. Retained (installed/partial) descriptors win,
     * then the active catalog. [expectedIdentity] narrows the match so a durable task cannot
     * silently switch to a different version after the catalog changes.
     */
    fun resolvePack(id:String, expectedIdentity:String?=null):TranslationModelPack? {
        val candidates=known.filter {it.id==id}+catalogs.state.value.catalog.packs.filter {it.id==id}
        return if(expectedIdentity==null) candidates.firstOrNull()
        else candidates.firstOrNull {it.identity==expectedIdentity}
    }
    private fun partial(id:String)=known.firstOrNull {it.id==id && installer.hasPartial(it)}
    private fun idleState(pack:TranslationModelPack)=when {
        installedMutable.value.containsKey(pack.id) -> TranslationPackState(TranslationPackStatus.READY)
        partial(pack.id)!=null || installer.hasPartial(pack) -> TranslationPackState(TranslationPackStatus.PAUSED)
        else -> TranslationPackState()
    }
    private fun refreshStates(catalog:TranslationModelCatalog=catalogs.state.value.catalog) {
        known=installer.knownPackages(fallback.packs+catalog.packs)
        retainedMutable.value=known
        installedMutable.value=installed()
        val packs=(catalog.packs+known).distinctBy {it.id}
        mutable.update {previous -> packs.associate {pack ->pack.id to
            (previous[pack.id]?.takeIf {jobs[pack.id]?.isCompleted==false || it.status==TranslationPackStatus.FAILED} ?: idleState(pack))}}
    }
    private fun state(pack:TranslationModelPack,value:TranslationPackState) {mutable.update {it+(pack.id to value)}}
    /**
     * Publishes a committed package immediately. Recovery runs without the manager's `start`
     * coroutine, so it cannot rely on the post-schedule refresh to expose the installed pack.
     */
    private fun markInstalled(pack:TranslationModelPack) {
        known=(known+pack).distinctBy {it.identity}
        retainedMutable.value=known
        installedMutable.update {it+(pack.id to pack)}
        state(pack,TranslationPackState(TranslationPackStatus.READY))
    }
    fun download(pack:TranslationModelPack)=start(pack, "nmt-download:${pack.id}") {downloadNow(pack)}
    fun importZip(pack:TranslationModelPack,open:()->InputStream)=start(pack, "nmt-import:${pack.id}") {importNow(pack,open)}

    /** Suspend core used by the unified task executor after reconstructing the request by id. */
    suspend fun downloadNow(id:String) {downloadNow(resolvePack(id) ?: error("未知语言包：$id"))}

    /**
     * Recovery entry point. The exact identity persisted with the task must still resolve, so a
     * refresh cannot turn an old goal into a download of a different descriptor.
     */
    suspend fun downloadNow(id:String, expectedIdentity:String) {
        downloadNow(resolvePack(id, expectedIdentity) ?: error("语言包目录已改变，请重新选择"))
    }

    private suspend fun downloadNow(pack:TranslationModelPack) {
        val catalog=catalogs.state.value.catalog
        require(catalog.packs.any {it==pack}) {"目录已改变，请重新选择语言包"}
        val settings=sources.settings.first()
        val previous=partial(pack.id)
        require(previous==null || previous.sameContents(pack)) {"未完成的包版本为 ${previous?.version}，请先取消旧下载再下载新版本"}
        serial.withPermit {
            if(settings.wifiOnly) {
                val cm=context.getSystemService(ConnectivityManager::class.java)
                require(cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true) {"当前不是 Wi-Fi；可在下载源设置中允许其他网络"}
            }
            ResourceGovernor.shared.use("io") {
                installer.download(pack,{catalog.downloadUrl(pack,it,settings)},{state(pack,it)})
            }
            markInstalled(pack)
        }
    }

    private suspend fun importNow(pack:TranslationModelPack,open:()->InputStream) {
        serial.withPermit {
            state(pack,TranslationPackState(TranslationPackStatus.VERIFYING,message="校验导入包"))
            ResourceGovernor.shared.use("io") {
                withContext(Dispatchers.IO) {open().use {installer.importZip(pack,it)}}
            }
            markInstalled(pack)
        }
    }

    private fun start(pack:TranslationModelPack,reason:String,action:suspend()->Unit) {
        if(jobs[pack.id]?.isCompleted==false || installedMutable.value.containsKey(pack.id)) return
        state(pack,TranslationPackState(TranslationPackStatus.DOWNLOADING,message="等待下载 / 导入"))
        jobs[pack.id]=scope.launch {
            try {
                scheduler.schedule(reason, pack.identity) {action()}
                ensureActive();refreshStates();state(pack,idleState(pack))
            }
            catch(cancelled:CancellationException) {refreshStates();state(pack,idleState(pack));throw cancelled}
            catch(failure:Exception) {refreshStates();state(pack,TranslationPackState(TranslationPackStatus.FAILED,message=failure.message ?: "语言包安装失败"))}
        }
    }

    /**
     * Pauses the durable task, then waits (NonCancellable) until the executor released the
     * file/network work before publishing the paused manager state.
     */
    fun pause(pack:TranslationModelPack) {
        val previous=jobs[pack.id];previous?.cancel()
        val pauseJob=scope.launch(start=CoroutineStart.LAZY) {
            try {
                withContext(NonCancellable) {
                    scheduler.pause("nmt-download:${pack.id}")
                    scheduler.pause("nmt-import:${pack.id}")
                    previous?.join()
                }
                refreshStates();state(pack,idleState(pack))
            } finally {
                val current=currentCoroutineContext()[Job]
                if(current!=null && jobs[pack.id]===current) jobs.remove(pack.id)
            }
        }
        jobs[pack.id]=pauseJob
        pauseJob.start()
    }

    fun discard(pack:TranslationModelPack) {
        val previous=jobs[pack.id];previous?.cancel()
        jobs[pack.id]=scope.launch {
            try {
                withContext(NonCancellable) {
                    // Wait for the cancelled waiter's pause to settle before turning the row
                    // terminal, so a concurrent pause/cancel cannot race the final state.
                    previous?.join()
                    scheduler.cancel("nmt-download:${pack.id}")
                    scheduler.cancel("nmt-import:${pack.id}")
                }
                ResourceGovernor.shared.use("io") {
                    withContext(Dispatchers.IO) {
                        (known.filter {it.id==pack.id}+pack).distinctBy {it.identity}.forEach(installer::discardPartial)
                    }
                }
                refreshStates();state(pack,idleState(pack))
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(failure:Exception) {state(pack,TranslationPackState(TranslationPackStatus.FAILED,message=failure.message ?: "无法丢弃暂存文件"))}
        }
    }

    suspend fun remove(pack:TranslationModelPack,translator:BergamotTextTranslator) {
        jobs[pack.id]?.cancelAndJoin()
        withContext(NonCancellable) {
            scheduler.cancel("nmt-download:${pack.id}")
            scheduler.cancel("nmt-import:${pack.id}")
        }
        val actual=installedMutable.value[pack.id] ?: return
        translator.removePack(actual);refreshStates();state(pack,idleState(catalogs.state.value.catalog.packs.firstOrNull {it.id==pack.id} ?: pack))
    }
}
