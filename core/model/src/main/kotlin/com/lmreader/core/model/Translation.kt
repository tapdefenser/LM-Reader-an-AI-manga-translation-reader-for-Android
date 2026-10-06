package com.lmreader.core.model

/** 每章只有一套译文；新语言选择只影响之后提交的任务。语言选择保存 BCP 47 标签，兼容旧显示名。 */

/** 一章的翻译记录。 */
data class ChapterTranslation(
    val chapterId: String,
    val mangaId: String,
    val targetLanguage: String,
    val state: TranslationState,
    /** 入队时的源语言快照；旧数据可空，新请求必须明确填写。 */
    val sourceLanguage: String?,
    val autoDetectSource: Boolean,
    /** 入队时的有效配置快照（JSON）：队列不因用户之后改设置而改变行为。 */
    val configSnapshot: String?,
    val queuedAt: Long?,
    val translatedAt: Long?,
    /** 已保存的页数；「清除翻译文本」会把它和 [translatedAt] 一起抹掉。 */
    val translatedCount: Int,
    val failure: String?,
    val updatedAt: Long,
) {
    /** 界面上是否显示「待翻译」徽标。 */
    val pending: Boolean get() = state !in listOf(TranslationState.DONE, TranslationState.CANCELLED)
}

/**
 * 漫画译名字典的一条（用户口径：**只和漫画有关，与语言无关**）。
 *
 * ## 为什么不按目标语言分区
 *
 * 上游规格（`docs/翻译配置与上游对照.md` TR09）写的是"按 mangaId+targetLanguage 维护"，
 * 因此第一版把它做成了按语言分套的字典。用户看过之后明确否掉了：他要的是"这部作品的
 * 译名字典"，多一层语言维度只让界面多出一个他从不关心的"正在编辑：简体中文"标题。
 *
 * **已知代价**：同一部作品翻成两种语言时，字典是同一份——把某词定成"简中译名"，
 * 换成英语目标语言时它仍然生效。用户只翻一种语言时这没有影响；真要同时翻多种语言，
 * 需要把这一维加回来（那就是一次迁移 + 界面加一个切换器）。
 */
data class GlossaryEntry(
    val mangaId: String,
    /** 原词，同一部作品内是去重键。 */
    val source: String,
    val target: String,
    /**
     * true = 用户手工录入；false = 自动新增。
     *
     * 仅记录来源。所有后来新增的条目均不能覆盖已有原词，与此标记无关。
     */
    val manual: Boolean,
    val updatedAt: Long,
)

/**
 * 一次入队的请求（「翻译所选」与「全部翻译」共用）。
 *
 * [configSnapshot] 是**入队那一刻**解析出来的有效配置：翻译方式、源/目标语言、文风。
 * 队列带着快照走，用户随后改设置不会让已排队的任务换一种翻法——这是"每部漫画一套
 * 独立翻译方式"能成立的前提，也是开发文档"队列包含有效配置快照"的要求。
 */
data class TranslationRequest(
    val targetLanguage: String,
    val sourceLanguage: String?,
    val autoDetectSource: Boolean,
    val configSnapshot: String?,
    val at: Long,
) {
    init {
        require(!autoDetectSource && !sourceLanguage.isNullOrBlank()) { "原文语言必填" }
        require(targetLanguage.isNotBlank()) { "目标语言必填" }
        require(!configSnapshot.isNullOrBlank()) { "翻译配置快照必填" }
    }
}

/**
 * 漫画级翻译设置（**「翻译选项」页面上的东西**，跟着漫画走）。
 *
 * 与**应用级**的翻译设置（设置里的那一套：主 AI、OCR、模式、全局文风；P3）区分开：
 * 这一层只回答"这部作品要怎么翻"。
 *
 * 原文语言与目标语言都必须由用户为这部作品明确填写；工作流不提供默认语言。
 */
data class MangaTranslationSettings(
    /** null = 还没为这部作品选过原文语言。**必填**，不继承任何上层。 */
    val sourceLanguage: String? = null,
    /** true = 自动识别原文语言（优先于 [sourceLanguage]，两者互斥）。 */
    val autoDetectSource: Boolean = false,
    /** null = 尚未选目标语言，禁止入队。 */
    val targetLanguage: String? = null,
    /** null 或非 [StyleMode.CUSTOM] = 不用漫画自己的文风，往下走分类与全局。 */
    val styleMode: StyleMode? = null,
    val customStyle: String? = null,
    /** null selects the immutable built-in machine workflow. */
    val workflowId: String? = null,
    /** null uses the selected workflow's default page mode. */
    val pageMode: TranslationPageMode? = null,
    /** A confidence threshold for new Seg runs; rendering changes do not rerun Seg. */
    val segThreshold: Float? = null,
    /** Per-manga overlay style; null fields preserve the pre-migration global preference. */
    val bubbleFillMode: BubbleFillMode? = null,
    val bubbleOpacityPercent: Int? = null,
    val bubbleTextPaddingPercent: Int? = null,
    val bubbleFont: BubbleFont? = null,
    val bubbleFontScalePercent: Int? = null,
    val bubbleBold: Boolean? = null,
    /** Per-manga SEG selection; old manga rows keep both kinds of text. */
    val segTextScope: SegTextScope = SegTextScope.ALL,
    val textDetectionThreshold: Float? = null,
    val freeTextMaskExpansionPercent: Int? = null,
    /** Maximum inter-line gap relative to line thickness; zero keeps independent lines separate. */
    val freeTextMergeGapRatio: Float? = null,
)

/**
 * 文风解析：**覆盖**关系（用户口径："留空就自动应用分类"）。
 *
 * 判据只看**文本本身是否为空**，不再看 `StyleMode`：用户要的是"这个框留空就往下退化"，
 * 多一个"用自定义 / 跟随分类"的单选只是把同一件事说了两遍（而且会出现"选了自定义却
 * 留空"这种自相矛盾的状态）。因此：
 *
 * 1. 漫画的文本非空 → 用它；
 * 2. 否则分类的文本非空 → 用它；
 * 3. 否则全局默认。
 *
 * [MangaTranslationSettings.styleMode] 保留在数据里（旧行还在），但**不再参与判断**。
 */
fun resolveTranslationStyle(
    manga: MangaTranslationSettings,
    categoryStyle: String?,
    globalStyle: String,
): String = when {
    !manga.customStyle.isNullOrBlank() -> manga.customStyle.trim()
    !categoryStyle.isNullOrBlank() -> categoryStyle.trim()
    else -> globalStyle
}

/**
 * 目标语言只认漫画上的显式选择；空值返回 null，由入队入口拦截。
 */
fun resolveTargetLanguage(manga: MangaTranslationSettings): String? =
    manga.targetLanguage?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 有效原文语言：自动识别优先；否则只认漫画这一层。
 *
 * **没有全局回退**（用户口径："翻译的原文语言没有全局默认这一说，每个新的漫画都必须得
 * 手动选"）。都没设时返回 `null to false` = 还没定，由调用方拦在翻译之前。
 */
fun resolveSourceLanguage(manga: MangaTranslationSettings): Pair<String?, Boolean> = when {
    manga.autoDetectSource -> null to true
    !manga.sourceLanguage.isNullOrBlank() -> manga.sourceLanguage.trim() to false
    else -> null to false
}

/**
 * 翻译选项完整性：源语言和目标语言均须明确填写，自动识别不能代替源语言。
 */
fun translationSetupComplete(
    sourceLanguage: String?,
    targetLanguage: String?,
    autoDetectSource: Boolean,
): Boolean = !autoDetectSource && !sourceLanguage.isNullOrBlank() && !targetLanguage.isNullOrBlank()
