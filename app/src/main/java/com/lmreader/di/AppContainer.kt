package com.lmreader.di

import android.app.Application
import android.content.Context
import com.lmreader.core.database.DatabaseProvider
import com.lmreader.core.index.StructureScanner
import com.lmreader.core.index.ChapterResolver
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ShelfRepository
import com.lmreader.core.model.SourceRepository
import com.lmreader.core.storage.access.StorageAccessCoordinator
import com.lmreader.core.storage.access.TreeAccess
import com.lmreader.core.storage.cover.CoverMetadataWriter
import com.lmreader.core.storage.cover.CoverResolver
import com.lmreader.core.storage.saf.SafTreeAccess
import com.lmreader.core.storage.scan.LibraryScanCoordinator
import com.lmreader.core.storage.scan.ChapterCounter
import com.lmreader.core.storage.scan.MangaChapterSyncer
import com.lmreader.core.storage.scan.MetadataBackfillWorker
import com.lmreader.core.storage.scan.SourceScanRunner
import com.lmreader.core.storage.reader.PageSourceFactory
import com.lmreader.core.storage.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 依赖装配（开发文档 15.2：`app / navigation` 负责"导航、依赖装配、主题"）。
 *
 * 为什么用手写容器而不是引入 DI 框架：本应用的对象图很小且没有变体（debug/release
 * 共用一套），引入 Hilt/Koin 只会增加注解处理与构建时间。等 feature 拆成独立
 * Gradle 模块、构造链变长之后再换框架也不影响调用方（都从这里取）。
 *
 * 线程约定：容器构建本身不做 IO；数据库与 DataStore 都是惰性打开。
 */
class AppContainer(val application: Application) {
    val applicationContext: Context get() = application.applicationContext

    private val databaseComponents by lazy {
        DatabaseProvider.create(
            context = application,
            // 章节排序方式由用户偏好决定；数据库层靠它给新探到的章节定位
            // （见 ChapterOrdering：新章节按已保存的排序方式插入，不动已有顺序）。
            chapterOrder = { preferences.chapterOrder.first() },
        )
    }

    /**
     * 启动时的一次性索引维护（不参与依赖图，失败不影响使用）。
     *
     * 目前只做一件事：把"来源行已经不存在的"孤儿卡片标成陈旧（只改可用性、不删行）。
     * `mangas` 没有指向 `library_sources` 的外键，早期版本删掉一条路径之后卡片会永远
     * 留在图库里——真机实测 4749 张卡片里有 4595 张是这种孤儿，图库界面因此完全没法看。
     *
     * 容器构建本身不做 IO（见类注释），所以放在独立作用域里跑；结果只记日志，
     * 因为它是维护而不是启动前提。
     */
    /** All application-owned queue/maintenance jobs share one lifetime. */
    internal val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val startupReady = kotlinx.coroutines.CompletableDeferred<Unit>()

    val database get() = databaseComponents.database
    val sourceRepository: SourceRepository get() = databaseComponents.sources
    val mangaRepository: MangaRepository get() = databaseComponents.mangas
    /** 待翻译队列与漫画译名字典（阶段 2；翻译引擎在 P3）。 */
    val translationRepository: com.lmreader.core.model.TranslationRepository
        get() = databaseComponents.translations
    val shelfRepository: ShelfRepository get() = databaseComponents.shelf
    val readingProgressRepository get() = databaseComponents.readingProgress

    val preferences by lazy { AppPreferences(application) }
    val generalPreferences by lazy { com.lmreader.core.storage.settings.GeneralPreferences(application) }

    /** 独立 API 配置列表与可取消的流式客户端，供设置和未来工作流共用。 */
    val apiProfiles by lazy { com.lmreader.core.storage.settings.ApiProfileStore(application) }
    val apiLogs by lazy { com.lmreader.ui.settings.api.ApiLogStore(java.io.File(applicationContext.filesDir, "api-request-logs")) }
    val apiClient by lazy { com.lmreader.core.api.ApiClient(journal = apiLogs) }
    val visionExecutionPreferences by lazy { com.lmreader.core.storage.settings.VisionExecutionPreferences(application) }
    val localVision by lazy { com.lmreader.core.vision.LocalVisionEngine(application) { visionExecutionPreferences.settings.value } }
    val translationSources by lazy { com.lmreader.core.translation.TranslationSourceStore(application) }
    val translationCatalog by lazy { com.lmreader.core.translation.TranslationModelCatalog(
        application.assets.open("translation/catalog.json").bufferedReader().use { it.readText() }) }
    val translationInstaller by lazy { com.lmreader.core.translation.ModelPackageInstaller(
        java.io.File(application.noBackupFilesDir, "translation-models")) }
    val translationCatalogs by lazy { com.lmreader.core.translation.TranslationCatalogStore(
        translationCatalog, java.io.File(application.noBackupFilesDir,"translation-catalogs")) }
    val translationModels by lazy { com.lmreader.core.translation.TranslationModelManager(
        application, translationCatalogs, translationInstaller, translationSources, translationCatalog) }
    val localTranslator by lazy { com.lmreader.core.translation.BergamotTextTranslator({translationModels.installedCatalog()}, translationInstaller) }
    val loadedTranslationResources by lazy {
        kotlinx.coroutines.flow.combine(localVision.loadedResources, localTranslator.loadedResources) { vision, translation -> vision + translation }
            .stateIn(backgroundScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
    }
    val bubbleRenderPreferences by lazy { com.lmreader.core.storage.settings.BubbleRenderPreferences(application) }
    val translationWorkflows by lazy { com.lmreader.ui.workflow.TranslationWorkflowStore(
        java.io.File(application.filesDir, "translation-workflows")) }
    val queueOrder by lazy { com.lmreader.ui.queue.QueueOrderStore(
        java.io.File(application.filesDir, "translation-queue-order.json")) }
    val translationCachePreferences by lazy { com.lmreader.core.storage.settings.TranslationCachePreferences(application) }
    val translationQueue by lazy { com.lmreader.ui.queue.TranslationQueueCoordinator(this) }
    val exportSettings by lazy { com.lmreader.ui.queue.ExportSettingsStore(application) }
    val exportQueue by lazy { com.lmreader.ui.queue.ExportQueueCoordinator(this) }
    val taskService by lazy { com.lmreader.tasks.TaskServiceController(this) }
    val taskNotifications by lazy { com.lmreader.tasks.TaskNotifications(this) }
    val appUpdates by lazy { com.lmreader.updates.AppUpdates(application, backgroundScope) }
    val backups by lazy { com.lmreader.ui.settings.backup.AppBackupManager(this) }
    val localPageTranslator by lazy { com.lmreader.ui.reader.translation.LocalPageTranslator(application,localVision,localTranslator,
        com.lmreader.ui.reader.translation.ReaderPageArtifactStore(java.io.File(application.filesDir,"reader-page-translations"),
            java.io.File(application.cacheDir,"reader-page-translations")),
        {translationModels.installedPacks.value.values.toList()}) }

    /**
     * 阅读器偏好（开发文档 12、14）。
     *
     * 与 [preferences] 分开放：两者共用同一个 DataStore 文件，但一个是"界面与引导"、
     * 另一个是"阅读器几十项设置"，合并成一个类只会让它变成杂物间。
     */
    val readerPreferences by lazy {
        com.lmreader.core.storage.settings.ReaderPreferences(application)
    }


    /** 底层 SAF 授权管理（持久授权、URI 构造）。 */
    val safAccess by lazy { SafTreeAccess(application) }

    /**
     * 打开内容树的统一入口：有「全部文件访问」时走直接文件访问，
     * 否则走单目录 SAF 授权。扫描、补全与封面都只通过它取树。
     */
    val treeAccess by lazy { TreeAccess(application, safAccess) }

    /**
     * 每次启动的授权检测（用户要求）。
     *
     * 放在容器里而不是某个页面里：书架、图库、路径配置三处都要用同一个结论，
     * 各自检测会出现"这个页面说失效、那个页面说正常"的矛盾。
     */
    val accessCoordinator by lazy {
        StorageAccessCoordinator(
            context = application,
            treeAccess = treeAccess,
            sourceRepository = sourceRepository,
        )
    }

    val backfillWorker by lazy {
        MetadataBackfillWorker(
            treeAccess = treeAccess,
            mangaRepository = mangaRepository,
        )
    }

    /**
     * 封面解析（图库/书架滚动时的懒加载，以及详情页「更新章节」的强制重取）。
     *
     * 放在容器里而不是各个 ViewModel 里：图库、书架、详情页三处要用**同一套**规则
     * （自然序第一章的第一页、单章节用锚点目录本身、最多往下试 3 章），各写一份
     * 迟早会出现"图库有封面、详情页没有"这类分叉。
     */
    val coverResolver by lazy { CoverResolver(treeAccess) }

    /**
     * 写封面探测结果时**顺手更新简介**（用户要求：在所有更新封面的时候也要更新简介）。
     *
     * 图库、书架、详情页三处都通过它落库：封面与简介读的是同一批目录，绑在一起才不会
     * 出现"卡片有封面、简介一直是空的"——简介原本只由扫描期补全负责，每来源每轮 120 条，
     * 几千部作品要刷十几次才轮得到。
     */
    val coverMetadataWriter by lazy {
        CoverMetadataWriter(
            mangaRepository = mangaRepository,
            metadataBackfill = backfillWorker,
        )
    }

    /**
     * 滚动/搜索时的章节计数（只数数量，不落章节清单）。
     *
     * 与封面懒加载同一套形状与触发时机（用户口径："滚动+搜索结果时加载，不要做成扫描时
     * 加载"）。它写的是 `countedChapterCount` 那一对列，**不碰** `chapterCountKnown`
     * ——那一位是同步逻辑删除多余章节行的闸门（见 `MangaEntity.countedChapterCount`）。
     */
    val chapterCounter by lazy { ChapterCounter(treeAccess) }

    val mangaChapterSyncer by lazy {
        MangaChapterSyncer(
            treeAccess = treeAccess,
            resolver = ChapterResolver(),
            mangaRepository = mangaRepository,
            sourceRepository = sourceRepository,
        )
    }

    val pageSourceFactory by lazy { PageSourceFactory(treeAccess, java.io.File(application.cacheDir, "chapter-archives")) }

    /**
     * 页面字节的磁盘预取缓存（见 [com.lmreader.ui.reader.PagePrefetcher]）。
     *
     * 放在容器里而不是阅读器 ViewModel 里：缓存的价值在于**跨会话存活**，
     * 读者退出再进来时那几页应该还在。缓存目录由系统管理，随时可被清理，
     * 因此不需要应用自己关心它的生命周期。
     */
    val pagePrefetcher by lazy {
        com.lmreader.ui.reader.PagePrefetcher(application)
    }

    /**
     * 结构扫描器是**纯算法**，不认识任何具体来源：它只通过 [TreeFactory] 读目录。
     * 真实工厂由 [SourceScanRunner] 在每次扫描时按该来源的树 URI 构造（开发文档
     * 15.2「core:index 禁止依赖业务 UI」，反过来也不该持有来源状态）。
     */
    private val structureScanner by lazy { StructureScanner(FailFastTreeFactory) }

    val scanRunner by lazy {
        SourceScanRunner(
            treeAccess = treeAccess,
            scanner = structureScanner,
            mangaRepository = mangaRepository,
            sourceRepository = sourceRepository,
        )
    }

    val scanCoordinator by lazy {
        LibraryScanCoordinator(
            runner = scanRunner,
            sourceRepository = sourceRepository,
            mangaRepository = mangaRepository,
            backfillWorker = backfillWorker,
        )
    }

    init {
        // 这里曾经调用 `SubsamplingScaleImageView.setPreferredBitmapConfig(RGB_565)` 以期
        // 把每页的内存减半。反编译该 fork 的解码器后确认**它无效**：位图格式在
        // `decoder.Decoder.init` 里硬编码为 ARGB_8888，静态配置根本不参与。
        // 真正的对策见 ReaderImageView（控制送进去的像素量）与 PagePrefetcher
        // （字节只落磁盘、不压堆）。留着那行只会让人以为内存已经被限制住了。

        // Launch only after every lazy delegate has been initialized.
        backgroundScope.launch { translationQueue }
        backgroundScope.launch { exportQueue }
        backgroundScope.launch {
            try { backups.recoverPendingRestore(); startupReady.complete(Unit) }
            catch (error: Exception) { startupReady.completeExceptionally(error) }
        }
        backgroundScope.launch {
            try { startupReady.await() } catch (_: Exception) { return@launch }
            runCatching { mangaRepository.markOrphanedAsStale() }
                .onSuccess { hidden ->
                    if (hidden > 0) {
                        android.util.Log.i(TAG, "启动维护：隐藏孤儿卡片 $hidden 张（来源行已删除，只隐藏不删除）")
                    }
                }
                .onFailure { error -> android.util.Log.e(TAG, "启动维护：孤儿卡片清扫失败", error) }
        }

        // 上次进程被系统/用户杀掉时，来源行会停在 RUNNING（真机快照里就有两个）。
        // 不收敛的话路径表永远显示"正在扫描"，用户分不清"真的在扫"与"上次没扫完"。
        backgroundScope.launch {
            try { startupReady.await() } catch (_: Exception) { return@launch }
            runCatching { sourceRepository.clearInterruptedScans("上次扫描被中断，请重新扫描") }
                .onSuccess { cleared ->
                    if (cleared > 0) {
                        android.util.Log.i(TAG, "启动维护：收敛 $cleared 个停留在「扫描中」的来源")
                    }
                }
                .onFailure { error -> android.util.Log.e(TAG, "启动维护：扫描状态收敛失败", error) }
        }
    }

    companion object {
        private const val TAG = "AppContainer"

        /**
         * 失败即报错的占位工厂。
         *
         * 它存在的唯一理由是让"忘了传真实工厂"立刻可见：早先这里是 `TreeFactory { null }`，
         * 于是每个子目录都被当成"打不开"，既不抛异常也没有日志，真机上表现成
         * "0 部漫画、65 个失败路径"，排查成本极高。现在它直接抛异常。
         */
        private val FailFastTreeFactory = com.lmreader.core.index.TreeFactory { child ->
            throw IllegalStateException(
                "扫描器没有拿到本次扫描的 TreeFactory（child=${child.name}）；" +
                    "SourceScanRunner 必须把按来源授权树构造的工厂传进 scan(factory = …)",
            )
        }

        fun from(context: Context): AppContainer {
            val app = context.applicationContext as Application
            return (app as LmReaderApplicationHolder).container
        }
    }
}

/** 由 Application 实现，避免在 :app 里到处做类型转换。 */
interface LmReaderApplicationHolder {
    val container: AppContainer
}
