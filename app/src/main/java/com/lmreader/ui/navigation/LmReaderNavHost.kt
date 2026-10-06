package com.lmreader.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lmreader.core.storage.settings.AppPreferences
import com.lmreader.core.model.ApiProfileKind
import com.lmreader.di.AppContainer
import com.lmreader.ui.bookshelf.BookshelfScreen
import com.lmreader.ui.detail.MangaDetailScreen
import com.lmreader.ui.library.LibraryScreen
import com.lmreader.ui.reader.ReaderScreen
import com.lmreader.ui.reader.ReaderViewModel
import com.lmreader.ui.settings.SettingsHomeScreen
import com.lmreader.ui.settings.api.*
import com.lmreader.ui.settings.reader.ReaderSettingsScreen
import com.lmreader.ui.settings.paths.GalleryPathsScreen
import com.lmreader.ui.settings.paths.GalleryPathsSettingsScreen
import com.lmreader.ui.translation.GlossaryScreen
import com.lmreader.ui.translation.TranslationOptionsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 路由常量集中一处，避免字符串散落在各个页面里。 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val LIBRARY = "library"
    const val BOOKSHELF = "bookshelf"
    const val SETTINGS = "settings"
    const val SETTINGS_GENERAL = "settings/general"
    const val SETTINGS_ABOUT = "settings/about"
    const val SETTINGS_PATHS = "settings/paths"
    const val SETTINGS_READER = "settings/reader"
    const val SETTINGS_EXPORT = "settings/export"
    const val SETTINGS_BACKUP = "settings/backup"
    const val SETTINGS_TASKS = "settings/tasks"
    const val SETTINGS_API = "settings/api"
    const val SETTINGS_API_LLM = "settings/api/llm"
    const val SETTINGS_API_OCR = "settings/api/ocr"
    const val SETTINGS_API_SEG = "settings/api/seg"
    const val SETTINGS_API_OCR_LOCAL = "settings/api/ocr/local"
    const val SETTINGS_API_LOCAL = "settings/api/local"
    const val SETTINGS_LOCAL_VISION = "settings/api/ocr/local-test"
    const val SETTINGS_API_EDITOR = "settings/api/editor/{kind}/{profileId}"
    fun apiEditor(kind: ApiProfileKind, id: String?) = "settings/api/editor/${kind.name}/${id ?: "new"}"
    const val MANGA_DETAIL = "manga/{mangaId}"
    const val READER = "reader/{mangaId}/{chapterId}?page={page}"
    const val TRANSLATION_QUEUE = "queue/translation"
    const val TRANSLATION_WORKFLOWS = "workflows/translation"
    const val API_LOGS = "logs/api"
    const val EXPORT_QUEUE = "queue/export"

    // 翻译相关的页面全部挂在详情页下（"跟着每部漫画走"，用户口径）。
    //
    // 注意名字：这里是**「翻译选项」**（漫画级：这部作品怎么翻），与应用级的「翻译设置」
    // （设置里：主 AI、OCR、模式、全局文风）是两回事，用户要求两者分开叫。
    const val TRANSLATION_OPTIONS = "manga/{mangaId}/translation?prompt={prompt}"
    const val TRANSLATION_GLOSSARY = "manga/{mangaId}/translation/glossary"

    fun mangaDetail(mangaId: String) = "manga/$mangaId"

    /**
     * @param prompt 是否由"用户想翻译但原文语言还没选"这一路径进来的。
     *   是的话页面会弹一句"请完成翻译选项"。**提示必须由该页自己弹**：
     *   详情页那句 snackbar 会随着导航把详情页移出组合而立刻消失，用户根本看不到。
     *   用查询参数而不是新路由，是因为这仍**是同一个页面**，只是进来的原因不同。
     */
    fun translationOptions(mangaId: String, prompt: Boolean = false) =
        "manga/$mangaId/translation?prompt=$prompt"

    fun translationGlossary(mangaId: String) = "manga/$mangaId/translation/glossary"

    /**
     * 阅读器路由。
     *
     * @param chapterId 具体章节 ID；null 表示"继续阅读"（跟随进度里的章节）
     * @param page 从**哪一页**开始（0 基）。只在从章节列表点进"已读过的那一章"时才给，
     *   于是用户点那一行会回到上次停下的页，而不是从第 1 页重来。
     *   用查询参数而不是路径段：它是可选的，而路径段难以区分"不带页码"与"页码为 0"。
     */
    fun reader(mangaId: String, chapterId: String?, page: Int? = null): String {
        val base = "reader/$mangaId/${chapterId ?: "resume"}"
        return if (page == null) base else "$base?page=$page"
    }
}

/**
 * 应用导航宿主。
 *
 * 启动决策（开发文档 1.3「首启与后续启动」、第 3 节）：
 * - 尚未完成路径配置 → 直接进入设置里的图库路径列表（引导态）；
 * - 已完成 → 冷启动进入书架。
 *
 * `onboardingCompleted` 一旦置 true 就不再清除，因此删空所有路径或失去授权之后
 * 仍然进书架（书架里会显示来源失效状态与入口），不会把用户重新推回引导页。
 */
@Composable
fun LmReaderNavHost(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
    requestedQueue: String? = null,
    onQueueOpened: () -> Unit = {},
) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    // 首次组合时同步读一次决策值：DataStore 是异步的，用 null 表示"还没读到"，
    // 在读到之前不渲染任何页面，避免先闪一下书架再跳到引导页。
    var startDestination by remember { mutableStateOf<String?>(null) }
    var startupFailure by remember { mutableStateOf<String?>(null) }
    val recoveryRequired by container.backups.recoveryRequired.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        try {
            container.startupReady.await()
            val completed = preferences.onboardingCompleted.first()
            startDestination = if (completed) Routes.BOOKSHELF else Routes.ONBOARDING
        } catch (error: Exception) { startupFailure = "数据恢复未完成，请保留应用数据并重新打开：${error.message}" }
    }

    // 主菜单抽屉用框架的 ModalNavigationDrawer：
    // - 它吸附在起始侧，正是主菜单需要的方向；
    // - 它自带"从屏幕左边缘向右滑打开"的手势（用户要求），而且**关闭时不会吃掉
    //   顶栏按钮的点击**。自实现版本在这一点上踩过坑：24dp 的边缘手势区正好盖住
    //   了距边缘 20dp 的汉堡按钮，导致"菜单点不开、左滑也失灵"。
    //
    // 右侧的图源筛选栏用自实现的 EndSideDrawer：框架组件没有选择吸附侧别的参数，
    // 而"把子树设成 RTL"会把面板内容整体镜像（真机上标题与数量文案都会反过来）。
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openMenu: () -> Unit = { scope.launch { drawerState.open() } }

    if (recoveryRequired) {
        com.lmreader.ui.i18n.Text("数据恢复未完成，请保留应用数据并重新打开应用。")
        return
    }
    val destination = startDestination ?: run {
        startupFailure?.let { com.lmreader.ui.i18n.Text(it) }
        return
    }
    LaunchedEffect(requestedQueue, destination) {
        if (requestedQueue != null) {
            navController.navigate(if (requestedQueue == "export") Routes.EXPORT_QUEUE else Routes.TRANSLATION_QUEUE) { launchSingleTop = true }
            onQueueOpened()
        }
    }

    // 侧栏「翻译队列」的角标接真实计数：入队真的会写待翻译记录，数字必须跟着动
    // （开发文档 8.1「队列入口显示活动任务数」；写死 0 就是在骗用户）。
    val pendingTranslations by container.translationRepository
        .observePendingCount()
        .collectAsStateWithLifecycle(initialValue = 0)
    val exportTasks by container.exportQueue.tasks.collectAsStateWithLifecycle()

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            MainMenuSheet(
                activeTranslationTasks = pendingTranslations,
                activeExportTasks = exportTasks.count { it.state == "PENDING" || it.state == "RUNNING" || it.state == "PAUSED" },
                onNavigate = { target ->
                    when (target) {
                        MainDestination.LIBRARY -> navController.navigateMainDestination(Routes.LIBRARY)
                        MainDestination.BOOKSHELF -> navController.navigateMainDestination(Routes.BOOKSHELF)
                        MainDestination.TRANSLATION_QUEUE ->
                            navController.navigateMainDestination(Routes.TRANSLATION_QUEUE)
                        MainDestination.TRANSLATION_WORKFLOWS ->
                            navController.navigateMainDestination(Routes.TRANSLATION_WORKFLOWS)
                        MainDestination.API_LOGS -> navController.navigateMainDestination(Routes.API_LOGS)

                        MainDestination.EXPORT_QUEUE ->
                            navController.navigateMainDestination(Routes.EXPORT_QUEUE)

                        MainDestination.SETTINGS -> navController.navigateMainDestination(Routes.SETTINGS)
                        MainDestination.MENU -> Unit
                    }
                },
                onDismiss = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            NavHost(navController = navController, startDestination = destination) {
                composable(Routes.API_LOGS) { ApiLogScreen(container, onBack = { navController.popBackStack() }) }
                composable(
                    route = Routes.READER,
                    arguments = listOf(
                        // 必须显式声明为 Int：查询参数默认按 String 解析，
                        // `getInt("page")` 会拿到 null，起始页就静默失效。
                        //
                        // `readOnly` + 在路由里给默认值，是为了不重复声明默认值——
                        // 路由模板一处、构建 URL 一处，两处不一致时最难查。
                        navArgument("page") {
                            type = NavType.IntType
                            defaultValue = ReaderViewModel.NO_START_PAGE
                        },
                    ),
                ) { entry ->
                    ReaderScreen(
                        container = container,
                        mangaId = entry.arguments?.getString("mangaId").orEmpty(),
                        chapterId = entry.arguments?.getString("chapterId").orEmpty(),
                        startPage = entry.arguments?.getInt("page") ?: ReaderViewModel.NO_START_PAGE,
                        onBack = { navController.popBackStack() },
                        onOpenTranslationOptions = {
                            navController.navigate(Routes.translationOptions(entry.arguments?.getString("mangaId").orEmpty()))
                        },
                    )
                }

                composable(Routes.ONBOARDING) {
                    GalleryPathsScreen(
                        container = container,
                        onNavigateToLibrary = {
                            navController.navigate(Routes.LIBRARY) {
                                // 引导完成后清掉引导页，返回键不会回到"设置路径"。
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                        onBack = null,
                    )
                }

                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        container = container,
                        onOpenMenu = openMenu,
                        onOpenManga = { mangaId -> navController.navigate(Routes.mangaDetail(mangaId)) },
                        onOpenSettings = { navController.navigateSingleTop(Routes.SETTINGS_PATHS) },
                    )
                }

                composable(Routes.BOOKSHELF) {
                    BookshelfScreen(
                        container = container,
                        onOpenMenu = openMenu,
                        onOpenManga = { mangaId -> navController.navigate(Routes.mangaDetail(mangaId)) },
                        onOpenLibrary = { navController.navigateMainDestination(Routes.LIBRARY) },
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsHomeScreen(
                        onOpenPaths = { navController.navigateSingleTop(Routes.SETTINGS_PATHS) },
                        onOpenReader = { navController.navigateSingleTop(Routes.SETTINGS_READER) },
                        onOpenApi = { navController.navigate(Routes.SETTINGS_API) { launchSingleTop = true } },
                        onOpenGeneral = { navController.navigate(Routes.SETTINGS_GENERAL) { launchSingleTop = true } },
                        onOpenExport = { navController.navigateSingleTop(Routes.SETTINGS_EXPORT) },
                        onOpenBackup = { navController.navigate(Routes.SETTINGS_BACKUP) },
                        onOpenTasks = { navController.navigate(Routes.SETTINGS_TASKS) },
                        onOpenAbout = { navController.navigate(Routes.SETTINGS_ABOUT) },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.SETTINGS_GENERAL) {
                    com.lmreader.ui.settings.GeneralSettingsScreen(container) { navController.popBackStack() }
                }
                composable(Routes.SETTINGS_ABOUT) {
                    com.lmreader.ui.settings.AboutScreen { navController.popBackStack() }
                }
                composable(Routes.SETTINGS_EXPORT) {
                    com.lmreader.ui.settings.ExportSettingsScreen(container) { navController.popBackStack() }
                }
                composable(Routes.SETTINGS_BACKUP) {
                    com.lmreader.ui.settings.BackupSettingsScreen(container, onBack = { navController.popBackStack() },
                        onPaths = { navController.navigate(Routes.SETTINGS_PATHS) })
                }
                composable(Routes.SETTINGS_TASKS) {
                    com.lmreader.ui.settings.TaskSettingsScreen(container) { navController.popBackStack() }
                }

                composable(Routes.SETTINGS_PATHS) {
                    GalleryPathsSettingsScreen(
                        container = container,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.SETTINGS_READER) {
                    ReaderSettingsScreen(
                        container = container,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.SETTINGS_API) {
                    ApiConfigurationHomeScreen(
                        onLlm = { navController.navigate(Routes.SETTINGS_API_LLM) { launchSingleTop = true } },
                        onLocal = { navController.navigate(Routes.SETTINGS_API_LOCAL) { launchSingleTop = true } },
                        onOcr = { navController.navigate(Routes.SETTINGS_API_OCR) { launchSingleTop = true } },
                        onSeg = { navController.navigateSingleTop(Routes.SETTINGS_API_SEG) },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.SETTINGS_API_LOCAL) {
                    com.lmreader.ui.settings.translation.LocalTranslationScreen(container) { navController.popBackStack() }
                }
                listOf(Routes.SETTINGS_API_LLM to ApiProfileKind.LLM, Routes.SETTINGS_API_OCR to ApiProfileKind.OCR).forEach { (route, kind) ->
                    composable(route) {
                        ApiProfilesScreen(container, kind, onEdit = { id -> navController.navigate(Routes.apiEditor(kind, id)) },
                            onBack = { navController.popBackStack() },
                            onLocalOcrTest = { navController.navigate(Routes.SETTINGS_LOCAL_VISION) { launchSingleTop = true } },
                            onLocalOcrConfig = { navController.navigateSingleTop(Routes.SETTINGS_API_OCR_LOCAL) })
                    }
                }
                composable(Routes.SETTINGS_LOCAL_VISION) {
                    com.lmreader.ui.settings.vision.LocalVisionScreen(container,onBack = { navController.popBackStack() })
                }
                listOf(Routes.SETTINGS_API_SEG to true, Routes.SETTINGS_API_OCR_LOCAL to false).forEach { (route, seg) ->
                    composable(route) {
                        LocalVisionConfigurationScreen(container, seg,
                            onTest = { navController.navigateSingleTop(Routes.SETTINGS_LOCAL_VISION) },
                            onBack = { navController.popBackStack() })
                    }
                }
                composable(Routes.SETTINGS_API_EDITOR) { entry ->
                    ApiProfileEditorScreen(
                        container, ApiProfileKind.valueOf(entry.arguments?.getString("kind").orEmpty()),
                        entry.arguments?.getString("profileId")?.takeUnless { it == "new" },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.MANGA_DETAIL) { entry ->
                    val mangaId = entry.arguments?.getString("mangaId").orEmpty()
                    MangaDetailScreen(
                        container = container,
                        mangaId = mangaId,
                        onBack = { navController.popBackStack() },
                        onReadChapter = { chapterId, startPage ->
                            navController.navigate(Routes.reader(mangaId, chapterId, startPage))
                        },
                        onOpenTranslationOptions = { prompt ->
                            navController.navigate(Routes.translationOptions(mangaId, prompt))
                        },
                        onOpenGlossary = {
                            navController.navigate(Routes.translationGlossary(mangaId))
                        },
                    )
                }

                composable(
                    route = Routes.TRANSLATION_OPTIONS,
                    arguments = listOf(
                        // 与 READER 的 page 同理：查询参数必须显式声明类型与默认值，
                        // 否则解析出来的类型不确定、"从 ⋮ 进来"与"被带进来"就分不开。
                        navArgument("prompt") {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
                ) { entry ->
                    val mangaId = entry.arguments?.getString("mangaId").orEmpty()
                    TranslationOptionsScreen(
                        container = container,
                        mangaId = mangaId,
                        showSetupPrompt = entry.arguments?.getBoolean("prompt") == true,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.TRANSLATION_GLOSSARY) { entry ->
                    GlossaryScreen(
                        container = container,
                        mangaId = entry.arguments?.getString("mangaId").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }


                composable(Routes.TRANSLATION_QUEUE) {
                    com.lmreader.ui.queue.TranslationQueueScreen(container) { navController.popBackStack() }
                }

                composable(Routes.TRANSLATION_WORKFLOWS) {
                    com.lmreader.ui.workflow.TranslationWorkflowScreen(container) { navController.popBackStack() }
                }

                composable(Routes.EXPORT_QUEUE) {
                    com.lmreader.ui.queue.ExportQueueScreen(container,
                        onBack = { navController.popBackStack() },
                        onSettings = { navController.navigateSingleTop(Routes.SETTINGS_EXPORT) })
                }
            }
        }
    }
}

/** 同一个目的地重复点选时不叠加返回栈（主菜单可能被连续点击）。 */
private fun NavHostController.navigateMainDestination(route: String) {
    navigate(route) {
        launchSingleTop = true
        popUpTo(graph.startDestinationId) { inclusive = false }
    }
}

/** Child pages retain their caller so both toolbar and system Back return there. */
private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) { launchSingleTop = true }
}
