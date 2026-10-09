package com.lmreader.core.model

/** A workflow revision is immutable once a task has captured it. */
enum class TranslationPageMode { BUBBLE }

data class TranslationWorkflow(
    val id: String,
    val revision: Int,
    val name: String,
    val description: String,
    val pageMode: TranslationPageMode,
    val parallelLimit: Int,
    val retries: Int,
    val builtIn: Boolean = false,
    val program: WorkflowProgram = WorkflowTemplates.localMachine(),
    val editable: Boolean = true,
) {
    init {
        require(id.isNotBlank() && id.length <= 100)
        require(revision > 0)
        require(name.isNotBlank() && name.length <= 80)
        require(description.length <= 500)
        require(parallelLimit in 1..8)
        require(retries in 0..5)
    }

    companion object {
        const val LOCAL_MACHINE_ID = "builtin.local-machine"
        val LOCAL_MACHINE = TranslationWorkflow(
            id = LOCAL_MACHINE_ID,
            revision = 1,
            name = "本地机翻",
            description = "本地 OCR 与 Bergamot 机翻，按页保存译文。",
            pageMode = TranslationPageMode.BUBBLE,
            parallelLimit = 1,
            retries = 1,
            builtIn = true,
        )
        val STANDARD_API = TranslationWorkflow("builtin.reference.standard-api", 1, "参考：标准翻译", "SEG → 气泡 OCR → 合并本页文本请求 API → 按气泡 ID 回填。先复制并选择 API。", TranslationPageMode.BUBBLE, 8, 1, true, WorkflowReferenceTemplates.standard())
        val FULL_MANGA_API = TranslationWorkflow("builtin.reference.full-manga-api", 1, "参考：全文速译", "整漫画 SEG/OCR → 所有气泡原文一次 API → 分页回填；无译名提取。需要足够的输入上下文与输出长度。先复制并选择 API。", TranslationPageMode.BUBBLE, 8, 1, true, WorkflowReferenceTemplates.fullManga())
        val VISION_API = TranslationWorkflow("builtin.reference.vision-api", 1, "参考：VL 直接翻译", "SEG → 气泡图片 API → 原文／译文回填 → 可编辑的章末译名提取。不调用本地 OCR 或机翻。先复制并选择视觉 API。", TranslationPageMode.BUBBLE, 8, 1, true, WorkflowReferenceTemplates.vision())
        val VISION_PAGE_API = TranslationWorkflow("builtin.reference.vision-page-api", 1, "参考：VL 整页直接翻译", "SEG → 按顺序收集本页裁图 → 一次视觉 API 流式翻译 → 逐项回填；严格校验图片与译文项数，不调用本地 OCR 或机翻。先复制并选择视觉 API。", TranslationPageMode.BUBBLE, 8, 2, true, WorkflowReferenceTemplates.visionPage())
        val BUILT_INS = listOf(LOCAL_MACHINE, STANDARD_API, VISION_API, VISION_PAGE_API)
    }
}

fun MangaTranslationSettings.effectiveSegThreshold(): Float =
    segThreshold?.takeIf { it.isFinite() && it in 0f..1f } ?: .35f

fun MangaTranslationSettings.effectiveTextDetectionThreshold(): Float =
    textDetectionThreshold?.takeIf { it.isFinite() && it in 0f..1f } ?: .35f

const val DEFAULT_FREE_TEXT_MERGE_GAP_RATIO = .45f

fun MangaTranslationSettings.effectiveFreeTextMergeGapRatio(): Float =
    freeTextMergeGapRatio?.takeIf { it.isFinite() && it in 0f..2f } ?: DEFAULT_FREE_TEXT_MERGE_GAP_RATIO

fun MangaTranslationSettings.effectiveBubbleRender(legacy: BubbleRenderSettings): BubbleRenderSettings =
    BubbleRenderSettings(
        fillMode = bubbleFillMode ?: legacy.fillMode,
        opacityPercent = bubbleOpacityPercent?.takeIf { it in 0..100 } ?: legacy.opacityPercent,
        textPaddingPercent = bubbleTextPaddingPercent?.takeIf { it in 0..20 } ?: legacy.textPaddingPercent,
        font = bubbleFont ?: legacy.font,
        fontScalePercent = bubbleFontScalePercent?.takeIf { it in 50..150 } ?: legacy.fontScalePercent,
        bold = bubbleBold ?: legacy.bold,
        freeTextMaskExpansionPercent = freeTextMaskExpansionPercent?.takeIf { it in 0..20 } ?: legacy.freeTextMaskExpansionPercent,
    )
