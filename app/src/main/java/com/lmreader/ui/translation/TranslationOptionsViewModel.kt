package com.lmreader.ui.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.MangaTranslationSettings
import com.lmreader.core.model.BubbleRenderSettings
import com.lmreader.core.model.BubbleFillMode
import com.lmreader.core.model.TranslationPageMode
import com.lmreader.core.model.ShelfRepository
import com.lmreader.core.model.StyleMode
import com.lmreader.core.model.resolveSourceLanguage
import com.lmreader.core.model.resolveTargetLanguage
import com.lmreader.core.model.resolveTranslationStyle
import com.lmreader.core.model.translationSetupComplete
import com.lmreader.core.storage.settings.AppPreferences
import com.lmreader.core.storage.settings.BubbleRenderPreferences
import com.lmreader.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **翻译选项**（漫画级：一个页面装下语言、文风与译名入口；用户口径）。
 *
 * 名字里刻意不带"设置"：应用级的那一套（主 AI、OCR、模式、全局文风）在**设置**里，
 * 用户要求两者分开叫，免得"翻译设置"到底指哪一层要靠上下文猜。
 *
 * ## 为什么合成一页
 *
 * 语言、文风、译名原来各占一个入口（⋮ 里三行），但它们回答的是同一件事：
 * "这部作品要怎么翻"。合成一页之后，用户从"翻译所选"被拦进来时能一次把三样都填完，
 * 而不是在三个页面之间来回找。
 *
 * ## 两维语言的规则不同
 *
 * - **原文语言必填、且没有全局默认**（用户口径："每个新的漫画都必须得手动选"）。
 *   因此 [TranslationOptionsUiState.sourceConfigured] 为 false 时详情页会拦住翻译。
 * - **目标语言必填**：与源语言一样，必须为每部漫画明确填写。
 */
class TranslationOptionsViewModel(
    private val mangaId: String,
    private val mangaRepository: MangaRepository,
    private val shelfRepository: ShelfRepository,
    private val preferences: AppPreferences,
    private val legacyBubble: BubbleRenderPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(TranslationOptionsUiState())
    val state: StateFlow<TranslationOptionsUiState> = _state.asStateFlow()
    private val saveMutex = Mutex()

    init {
        viewModelScope.launch { reload() }
    }

    /**
     * 重新读一遍设置。
     *
     * 每次回到这一页都要重读：用户可以改分类文风或漫画级遮盖设置，
     * 那时这张页面上显示的生效值会变，而漫画设置本身没变——只观察
     * [MangaTranslationSettings] 是看不出来的。
     */
    fun reload() {
        viewModelScope.launch {
            var settings = mangaRepository.translationSettings(mangaId)
            val globalStyle = preferences.translationGlobalStyle.first()
            val legacyRender = legacyBubble.settings.first()
            val migrated = settings.copy(
                segThreshold = settings.segThreshold ?: .35f,
                bubbleFillMode = settings.bubbleFillMode ?: legacyRender.fillMode,
                bubbleOpacityPercent = settings.bubbleOpacityPercent ?: legacyRender.opacityPercent,
                bubbleTextPaddingPercent = settings.bubbleTextPaddingPercent ?: legacyRender.textPaddingPercent,
            )
            if (migrated != settings) {
                mangaRepository.updateTranslationSettings(mangaId, migrated)
                settings = migrated
            }
            val categoryId = shelfRepository.categoryIdOf(mangaId)
            val category = categoryId?.let { id ->
                shelfRepository.observeCategories().first().firstOrNull { it.categoryId == id }
            }
            _state.update {
                it.copy(
                    loading = false,
                    settings = settings,
                    legacyRender = legacyRender,
                    globalStyle = globalStyle,
                    categoryName = category?.name,
                    categoryStyle = category?.customStyle?.takeIf { text -> text.isNotBlank() },
                )
            }
        }
    }

    /**
     * 原文语言。
     *
     * 空串 = 清除（回到"还没选"，而不是回退到某个全局默认——没有全局默认这回事）。
     */
    fun setSourceLanguage(language: String) {
        val trimmed = language.trim()
        update { current ->
            current.copy(
                sourceLanguage = trimmed.takeIf { it.isNotEmpty() },
                // 手工选了语言就同时关掉自动识别：两个都开着时"哪个生效"要靠优先级解释，
                // 不如让选择本身互斥（与界面上的单选一致）。
                autoDetectSource = false,
            )
        }
    }

    fun setAutoDetectSource(enabled: Boolean) {
        update { it.copy(autoDetectSource = enabled) }
    }

    /** 目标语言；空串 = 未选，禁止入队。 */
    fun setTargetLanguage(language: String) {
        val trimmed = language.trim()
        update { it.copy(targetLanguage = trimmed.takeIf { it.isNotEmpty() }) }
    }

    /**
     * 文风：一个文本框，**留空 = 自动往下退化**（用户口径："如果留空就是自动应用分类"）。
     *
     * 因此这里不再有"用自定义 / 跟随分类"的单选，也总是写 `StyleMode.CUSTOM`——
     * 解析只看文本是否为空（见 `resolveTranslationStyle`），模式列只为兼容旧行保留。
     */
    fun setStyle(text: String) {
        update { it.copy(styleMode = StyleMode.CUSTOM, customStyle = text) }
    }

    fun setWorkflow(id: String?) = update { it.copy(workflowId = id) }
    fun setApiProfile(id: String?) = update { it.copy(apiProfileId = id) }
    fun setPageMode(mode: TranslationPageMode?) = update { it.copy(pageMode = mode) }
    fun setSegThreshold(value: Float) = update { it.copy(segThreshold = value.coerceIn(0f, 1f)) }
    fun setTextDetectionThreshold(value: Float) = update { it.copy(textDetectionThreshold = value.coerceIn(0f, 1f)) }
    fun setFreeTextMaskExpansion(value: Int) = update { it.copy(freeTextMaskExpansionPercent = value.coerceIn(0, 20)) }
    fun setFreeTextMergeGap(value: Float) = update { it.copy(freeTextMergeGapRatio = value.coerceIn(0f, 2f)) }
    fun setSegTextScope(value: com.lmreader.core.model.SegTextScope) = update { it.copy(segTextScope = value) }
    fun setBubbleFill(mode: BubbleFillMode) = update { it.copy(bubbleFillMode = mode) }
    fun setBubbleOpacity(value: Int) = update { it.copy(bubbleOpacityPercent = value.coerceIn(0, 100)) }
    fun setBubblePadding(value: Int) = update { it.copy(bubbleTextPaddingPercent = value.coerceIn(0, 20)) }
    fun setBubbleFont(value: com.lmreader.core.model.BubbleFont) = update { it.copy(bubbleFont = value) }
    fun setBubbleFontScale(value: Int) = update { it.copy(bubbleFontScalePercent = value.coerceIn(50, 150)) }
    fun setBubbleBold(value: Boolean) = update { it.copy(bubbleBold = value) }

    private fun update(transform: (MangaTranslationSettings) -> MangaTranslationSettings) {
        val next = transform(_state.value.settings)
        _state.update { it.copy(settings = next) }
        viewModelScope.launch {
            runCatching { saveMutex.withLock {
                mangaRepository.updateTranslationSettings(mangaId, _state.value.settings)
            } }
                .onFailure { error ->
                    _state.update { it.copy(message = error.message ?: "保存失败") }
                }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        fun factory(container: AppContainer, mangaId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    TranslationOptionsViewModel(
                        mangaId = mangaId,
                        mangaRepository = container.mangaRepository,
                        shelfRepository = container.shelfRepository,
                        preferences = container.preferences,
                        legacyBubble = container.bubbleRenderPreferences,
                    )
                }
            }
    }
}

data class TranslationOptionsUiState(
    val loading: Boolean = true,
    val settings: MangaTranslationSettings = MangaTranslationSettings(),
    val globalStyle: String = "",
    val legacyRender: BubbleRenderSettings = BubbleRenderSettings(),
    /** 这部作品所在分类的名字；null = 不在书架，没有分类这一层。 */
    val categoryName: String? = null,
    /** 分类那一层的文风；null = 分类没设，继续回退全局。 */
    val categoryStyle: String? = null,
    val message: String? = null,
) {
    /** 生效的原文语言：null = 还没选（且没开自动识别）。 */
    val effectiveSource: String?
        get() = resolveSourceLanguage(settings).let { (language, auto) ->
            if (auto) AUTO_DETECT_LABEL else language
        }

    val autoDetectSource: Boolean
        get() = resolveSourceLanguage(settings).second

    /** 生效的目标语言；未选时为空字符串。 */
    val effectiveTarget: String
        get() = resolveTargetLanguage(settings).orEmpty()

    /** 原文语言是否已经选到"可以开始翻译"的程度，决定详情页是否要拦住翻译。 */
    val sourceConfigured: Boolean
        get() = translationSetupComplete(
            sourceLanguage = settings.sourceLanguage,
            targetLanguage = settings.targetLanguage,
            autoDetectSource = autoDetectSource,
        )

    /** 最终生效的文风（漫画 → 分类 → 全局）。 */
    val effectiveStyle: String
        get() = resolveTranslationStyle(settings, categoryStyle, globalStyle)

    /** 生效来源的说明，显示在文风框下面。 */
    val effectiveStyleSource: String
        get() = when {
            !settings.customStyle.isNullOrBlank() -> "正在使用这部作品的文风"
            !categoryStyle.isNullOrBlank() -> "留空，正在使用分类「${categoryName ?: "未知"}」的文风"
            else -> "留空，正在使用全局默认文风"
        }

    /** 原文语言显示名；没选就是「未选」。 */
    val sourceLabel: String
        get() = effectiveSource ?: "未选"

    val targetLabel: String
        get() = effectiveTarget.ifEmpty { "未选" }

}

/** 自动识别在界面上的显示名（它不是一种语言，因此不能和语言名混在一列里）。 */
internal const val AUTO_DETECT_LABEL = "自动识别"
