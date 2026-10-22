package com.lmreader.core.model

/** Shared model demand; segmentation scope and later workflow nodes contribute independently. */
data class VisionModelDemand(
    val bubbleSeg: Boolean = false,
    val textDetection: Boolean = false,
    val recognitionLanguages: Set<LocalOcrLanguage> = emptySet(),
) {
    operator fun plus(other: VisionModelDemand) = VisionModelDemand(
        bubbleSeg || other.bubbleSeg, textDetection || other.textDetection,
        recognitionLanguages + other.recognitionLanguages)

    companion object {
        fun blocks(scope: SegTextScope) = VisionModelDemand(
            bubbleSeg = scope != SegTextScope.FREE_TEXT,
            textDetection = scope != SegTextScope.BUBBLES)
    }
}

fun LocalTranslationLanguage.visionOcrLanguage() = when (this) {
    LocalTranslationLanguage.JAPANESE -> LocalOcrLanguage.JAPANESE
    LocalTranslationLanguage.KOREAN -> LocalOcrLanguage.KOREAN
    LocalTranslationLanguage.CHINESE_SIMPLIFIED -> LocalOcrLanguage.CHINESE_SIMPLIFIED
    LocalTranslationLanguage.CHINESE_TRADITIONAL -> LocalOcrLanguage.CHINESE_TRADITIONAL
    else -> LocalOcrLanguage.ENGLISH
}

fun WorkflowProgram.visionModelDemand(scope: SegTextScope, source: LocalTranslationLanguage,
    target: LocalTranslationLanguage): VisionModelDemand {
    if (inputScenario != WorkflowInputScenario.IMAGE) return VisionModelDemand()
    val blocks = if (uses(WorkflowKind.SEG)) VisionModelDemand.blocks(scope) else VisionModelDemand()
    val languages = allNodes().filter { it.kind == WorkflowKind.OCR }.map { node ->
        val language = when (val value = node.inputs["language"]) {
            WorkflowSystem.ref(WorkflowSystem.SOURCE) -> source
            WorkflowSystem.ref(WorkflowSystem.TARGET) -> target
            is WorkflowExpression.Text -> LocalTranslationLanguage.fromTag(value.value)
            else -> error("${node.label.ifBlank { "OCR" }}：语言需明确绑定")
        }
        language.visionOcrLanguage()
    }.toSet()
    // OCR may need DET inside its input crop. This is separate from block detection.
    return blocks + VisionModelDemand(textDetection = languages.isNotEmpty(), recognitionLanguages = languages)
}
