package com.lmreader.core.storage.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lmreader.core.index.ChapterOrdering
import com.lmreader.core.model.BookshelfSort
import com.lmreader.core.model.BookshelfSortMode
import com.lmreader.core.model.LibraryDisplayMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 全局偏好（开发文档 14「多级设置」中属于 DataStore 的部分）。
 *
 * 为什么单独放 DataStore 而不是数据库：开发文档 15.3 明确「全局偏好和
 * onboarding 标记归 DataStore，漫画级阅读与章节视图偏好归数据库」。
 *
 * DataStore 实例的归属见 [preferenceStore] —— 这里**不能**再声明一个
 * `preferencesDataStore` 委托，否则同一文件会被打开两次并抛异常。
 */
class AppPreferences(private val context: Context) {
    val videoSubtitleStyle: Flow<String> = context.preferenceStore.data.map { it[stringPreferencesKey("video_subtitle_style")] ?: "{}" }
    suspend fun setVideoSubtitleStyle(style: String) {
        require(style.length <= 16_000)
        context.preferenceStore.edit { it[stringPreferencesKey("video_subtitle_style")] = style }
    }
    /** One selection shared by gallery and shelf; their list state remains separate. */
    val homeMediaMode: Flow<String> = context.preferenceStore.data.map { if(it[KEY_HOME_MEDIA_MODE] == "VIDEO") "VIDEO" else "IMAGE" }
    suspend fun setHomeMediaMode(mode: String) {
        require(mode in setOf("IMAGE", "VIDEO"))
        context.preferenceStore.edit { it[KEY_HOME_MEDIA_MODE] = mode }
    }
    private val KEY_HOME_MEDIA_MODE = stringPreferencesKey("home_media_mode")
    fun videoHomeState(shelf: Boolean): Flow<String> = context.preferenceStore.data.map {
        it[stringPreferencesKey(if(shelf) "video_shelf_state" else "video_library_state")] ?: "{}"
    }
    suspend fun setVideoHomeState(shelf: Boolean, state: String) {
        context.preferenceStore.edit { it[stringPreferencesKey(if(shelf) "video_shelf_state" else "video_library_state")] = state }
    }

    /**
     * 是否完成首次引导。默认 false（开发文档 3）。
     *
     * 语义要点：这个标记一旦置 true 就**不再清除**——后续用户删空所有路径或
     * 失去授权，冷启动仍进入书架并显示配置入口，而不是把用户重新推回引导页。
     */
    val onboardingCompleted: Flow<Boolean> = context.preferenceStore.data
        .map { it[KEY_ONBOARDING_COMPLETED] ?: false }

    suspend fun setOnboardingCompleted(value: Boolean) {
        context.preferenceStore.edit { it[KEY_ONBOARDING_COMPLETED] = value }
    }

    /** 图库展示方式，默认封面列表（开发文档 8.1「展示方式」）。 */
    val libraryDisplayMode: Flow<LibraryDisplayMode> = context.preferenceStore.data
        .map { prefs ->
            when (prefs[KEY_LIBRARY_DISPLAY_MODE]) {
                LibraryDisplayMode.GRID.name -> LibraryDisplayMode.GRID
                else -> LibraryDisplayMode.LIST
            }
        }

    suspend fun setLibraryDisplayMode(mode: LibraryDisplayMode) {
        context.preferenceStore.edit { it[KEY_LIBRARY_DISPLAY_MODE] = mode.name }
    }

    /**
     * 图库的图源筛选（用户勾选的来源 ID 集合）。
     *
     * 空集合表示"没有筛选"，与"用户取消勾选全部"在界面上是同一件事：
     * 两者都显示全部条目——与其给用户一个必然空白的图库，不如把空筛选当作
     * 未筛选，并在右滑栏里保留他的勾选状态。
     *
     * 存 DataStore 而不是数据库：它是界面偏好，且与"打开页面不扫描"配合时
     * 必须能立即读出来，不能等 Room 打开。
     */
    val librarySourceFilter: Flow<Set<String>> = context.preferenceStore.data
        .map { prefs ->
            prefs[KEY_LIBRARY_SOURCE_FILTER]
                ?.split(SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
        }

    suspend fun setLibrarySourceFilter(sourceIds: Set<String>) {
        context.preferenceStore.edit { prefs ->
            if (sourceIds.isEmpty()) {
                prefs.remove(KEY_LIBRARY_SOURCE_FILTER)
            } else {
                prefs[KEY_LIBRARY_SOURCE_FILTER] = sourceIds.joinToString(SEPARATOR)
            }
        }
    }

    /**
     * 章节列表的排序方式与方向（排序抽屉里用户勾选的那一项）。
     *
     * 它同时被两处读：详情页用它显示"当前是哪一种"；**数据库层**在扫描发现新章节时
     * 用它决定新章节插到哪里（见 `ChapterOrdering`）。因此这里存的是**规则**，
     * 而不是"列表当前顺序"——顺序本身存在 `chapters.position` 里。
     *
     * [ChapterOrdering.Setting.mode] 为 null = 用户从未选过：新章节追加到末尾。
     */
    val chapterOrder: Flow<ChapterOrdering.Setting> = context.preferenceStore.data
        .map { prefs ->
            ChapterOrdering.Setting(
                mode = prefs[KEY_CHAPTER_ORDER_MODE]?.let { stored ->
                    ChapterOrdering.Mode.entries.firstOrNull { it.name == stored }
                },
                descending = prefs[KEY_CHAPTER_ORDER_DESC] ?: false,
            )
        }

    suspend fun setChapterOrder(mode: ChapterOrdering.Mode, descending: Boolean) {
        context.preferenceStore.edit { prefs ->
            prefs[KEY_CHAPTER_ORDER_MODE] = mode.name
            prefs[KEY_CHAPTER_ORDER_DESC] = descending
            // 一旦用户主动选过排序方式，"当前是手动排的"这件事就不再成立。
            prefs[KEY_CHAPTER_ORDER_MANUAL] = false
        }
    }

    /**
     * 用户是否手动拖动过章节顺序。
     *
     * 只用于界面如实显示"当前：手动"——**不参与**新章节的插入规则
     * （用户口径：手动拖过之后，新章节依然按已保存的排序方式插入，不动已有顺序）。
     */
    val chapterOrderManual: Flow<Boolean> = context.preferenceStore.data
        .map { it[KEY_CHAPTER_ORDER_MANUAL] ?: false }

    suspend fun setChapterOrderManual(manual: Boolean) {
        context.preferenceStore.edit { it[KEY_CHAPTER_ORDER_MANUAL] = manual }
    }

    /**
     * 书架列表的排序（全局，作用于"分类之后"的漫画）。
     *
     * 与章节排序的区别：书架**不落库**——没有排序表、不支持拖动，读的时候现排
     * （用户口径："不需要拖动，不建每分类的排序方法，就是一个全局显示的排序"）。
     * 所以这里存的就是最终答案，没有第二份数据要与它保持同步。
     */
    val bookshelfSort: Flow<BookshelfSort> = context.preferenceStore.data
        .map { prefs ->
            val mode = prefs[KEY_BOOKSHELF_SORT_MODE]?.let { stored ->
                BookshelfSortMode.entries.firstOrNull { it.name == stored }
            } ?: BookshelfSortMode.NAME
            // 方向缺失（第一次用、或升级自旧版本）时取该方式的默认方向，
            // 而不是一律 false：否则"加入时间"会以最老在前开局。
            val descending = prefs[KEY_BOOKSHELF_SORT_DESC] ?: mode.startsDescending
            BookshelfSort(mode = mode, descending = descending)
        }

    suspend fun setBookshelfSort(sort: BookshelfSort) {
        context.preferenceStore.edit {
            it[KEY_BOOKSHELF_SORT_MODE] = sort.mode.name
            it[KEY_BOOKSHELF_SORT_DESC] = sort.descending
        }
    }

    // ---- 界面语言与全局文风 ----------

    /**
     * 应用**当前界面语言**的 BCP-47 标签。翻译目标语言已改为漫画级必填，
     * 这里仅供界面显示、语言名称本地化使用。
     *
     * 取 `resources.configuration.locales[0]` 而不是 `Locale.getDefault()`：前者是
     * **这个应用实际被配置成**的语言，后者是系统语言，
     * 两者的差别在"系统是英文但应用被设成中文"时会显出来。
     *
     * **原文语言没有对应的全局偏好**：它是每部作品的属性，必须逐部手动选（用户口径）。
     */
    val appLanguageTag: String
        get() = GeneralPreferences(context).effectiveLanguageTag

    /**
     * 全局默认文风（覆盖链的最后一层）。
     *
     * 默认值就是开发文档 TR06 的内置日漫文风。用户把它清空时回退到这个默认值：
     * 空文风会让模型收到一条空指令，比给一句通用文风更糟。
     */
    val translationGlobalStyle: Flow<String> = context.preferenceStore.data
        .map { it[KEY_TRANSLATION_GLOBAL_STYLE]?.takeIf { text -> text.isNotBlank() } ?: DEFAULT_STYLE }

    suspend fun setTranslationGlobalStyle(style: String) {
        context.preferenceStore.edit { it[KEY_TRANSLATION_GLOBAL_STYLE] = style }
    }

    private companion object {
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_LIBRARY_DISPLAY_MODE = stringPreferencesKey("library_display_mode")
        val KEY_LIBRARY_SOURCE_FILTER = stringPreferencesKey("library_source_filter")

        /**
         * 旧的"从新到旧"开关。
         *
         * v6 之后方向是**排序方式的一部分**（每种排序都能正向/逆向），这个键不再被读，
         * 但保留常量以免清理时漏掉历史值；新键见 [KEY_CHAPTER_ORDER_DESC]。
         */
        val KEY_CHAPTER_SORT_DESC = booleanPreferencesKey("chapter_sort_desc")

        val KEY_CHAPTER_ORDER_MODE = stringPreferencesKey("chapter_order_mode")
        val KEY_CHAPTER_ORDER_DESC = booleanPreferencesKey("chapter_order_desc")
        val KEY_CHAPTER_ORDER_MANUAL = booleanPreferencesKey("chapter_order_manual")

        val KEY_BOOKSHELF_SORT_MODE = stringPreferencesKey("bookshelf_sort_mode")
        val KEY_BOOKSHELF_SORT_DESC = booleanPreferencesKey("bookshelf_sort_desc")

        /**
         * 旧的全局源语言 / 自动识别 / 目标语言偏好。
         *
         * v8 之后不再被读写（用户口径：原文语言每部漫画手动选，没有全局默认；目标语言的
         * 全局默认就是应用当前语言）。保留常量以免以后清理时漏掉用户设备上的历史值，
         * 但**不要**再用它们做任何解析。
         */
        val KEY_TRANSLATION_SOURCE_LANGUAGE = stringPreferencesKey("translation_source_language")
        val KEY_TRANSLATION_AUTO_DETECT = booleanPreferencesKey("translation_auto_detect_source")
        val KEY_TRANSLATION_TARGET_LANGUAGE = stringPreferencesKey("translation_target_language")
        val KEY_TRANSLATION_GLOBAL_STYLE = stringPreferencesKey("translation_global_style")

        /**
         * 全局默认文风（覆盖链的最后一层）。
         *
         * 与原文语言不同，**这里给缺省值是刻意的**：文风留空本来就有明确的退化路径
         * （分类文风 → 这句默认），用户不填也不会出错；原文语言留空则无法开始翻译。
         */
        const val DEFAULT_STYLE =
            "忠实原意，译文自然流畅，保持人物语气、称谓和前后文一致，不添加解释。"

        /** 来源 ID 是 `s_` + 十六进制，不含逗号，因此逗号分隔是安全的。 */
        const val SEPARATOR = ","
    }
}
