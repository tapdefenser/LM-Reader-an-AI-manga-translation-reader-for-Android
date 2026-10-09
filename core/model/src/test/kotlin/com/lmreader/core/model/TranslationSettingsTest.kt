package com.lmreader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文风与语言的解析（用户口径：**覆盖**关系，不是继承链）。
 *
 * "如果漫画设置了就用漫画的，如果漫画的留空了就用分类的，如果分类的还留空就用全局的"。
 * 判据是"这一层**显式**写了自定义文本"，所以"选了自定义但文本清空"必须继续往下回退——
 * 得到一段空文风比回退更糟（模型会收到一条空指令）。
 */
class TranslationSettingsTest {
    @Test fun `new defaults preserve saved opacity and free text overrides`() {
        assertEquals(85,BubbleRenderSettings().opacityPercent)
        assertEquals(7,BubbleRenderSettings().textPaddingPercent)
        assertEquals(85,MangaTranslationSettings().effectiveBubbleRender(BubbleRenderSettings()).opacityPercent)
        assertEquals(100,MangaTranslationSettings(bubbleOpacityPercent=100).effectiveBubbleRender(BubbleRenderSettings()).opacityPercent)
        assertEquals(.45f,MangaTranslationSettings().effectiveFreeTextMergeGapRatio())
        assertEquals(.35f,MangaTranslationSettings(freeTextMergeGapRatio=.35f).effectiveFreeTextMergeGapRatio())
        listOf(Float.NaN,Float.POSITIVE_INFINITY,-1f,3f).forEach {
            assertEquals(.45f,MangaTranslationSettings(freeTextMergeGapRatio=it).effectiveFreeTextMergeGapRatio())
        }
    }
    @Test fun `text quality options use new defaults and ignore invalid persisted values`() {
        assertEquals(.35f,MangaTranslationSettings().effectiveSegThreshold())
        assertEquals(.35f,MangaTranslationSettings().effectiveTextDetectionThreshold())
        assertEquals(6,MangaTranslationSettings().effectiveBubbleRender(BubbleRenderSettings()).freeTextMaskExpansionPercent)
        assertEquals(.35f,MangaTranslationSettings(textDetectionThreshold=Float.NaN).effectiveTextDetectionThreshold())
        assertEquals(6,MangaTranslationSettings(freeTextMaskExpansionPercent=30).effectiveBubbleRender(BubbleRenderSettings()).freeTextMaskExpansionPercent)
        assertEquals(12,MangaTranslationSettings(freeTextMaskExpansionPercent=12).effectiveBubbleRender(BubbleRenderSettings()).freeTextMaskExpansionPercent)
    }

    private val global = "全局默认文风"

    @Test
    fun `漫画写了自定义就用漫画的`() {
        val manga = MangaTranslationSettings(styleMode = StyleMode.CUSTOM, customStyle = "漫画文风")
        assertEquals("漫画文风", resolveTranslationStyle(manga, "分类文风", global))
    }

    @Test
    fun `漫画留空则用分类的`() {
        assertEquals("分类文风", resolveTranslationStyle(MangaTranslationSettings(), "分类文风", global))
        assertEquals(global, resolveTranslationStyle(MangaTranslationSettings(), null, global))
    }

    @Test
    fun `文风只看文本是否为空不再看模式`() {
        // 用户口径："点开就是一个输入框，如果留空就是自动应用分类"。
        // 因此"模式是跟随分类但文本非空"与"模式是自定义"结果相同——模式不再参与判断。
        val withText = MangaTranslationSettings(styleMode = StyleMode.CATEGORY, customStyle = "漫画文风")
        assertEquals("漫画文风", resolveTranslationStyle(withText, "分类文风", global))

        // 反过来：模式是自定义但文本为空，也必须继续往下回退（空文风比回退更糟）。
        val blankCustom = MangaTranslationSettings(styleMode = StyleMode.CUSTOM, customStyle = "   ")
        assertEquals("分类文风", resolveTranslationStyle(blankCustom, "分类文风", global))
        assertEquals(global, resolveTranslationStyle(blankCustom, null, global))
    }

    @Test
    fun `文风两端空白会被去掉`() {
        val manga = MangaTranslationSettings(customStyle = "  漫画文风  ")
        assertEquals("漫画文风", resolveTranslationStyle(manga, null, global))
    }

    @Test
    fun `目标语言必须明确填写`() {
        assertEquals("英语", resolveTargetLanguage(MangaTranslationSettings(targetLanguage = " 英语 ")))
        assertEquals(null, resolveTargetLanguage(MangaTranslationSettings()))
        assertEquals(null, resolveTargetLanguage(MangaTranslationSettings(targetLanguage = "  ")))
    }

    @Test
    fun `源语言的自动识别优先于手工指定`() {
        val settings = MangaTranslationSettings(sourceLanguage = "英语", autoDetectSource = true)
        assertEquals(null to true, resolveSourceLanguage(settings))
    }

    @Test
    fun `源语言只认漫画这一层`() {
        assertEquals("韩语" to false, resolveSourceLanguage(MangaTranslationSettings(sourceLanguage = "韩语")))
        // 没有全局回退：不设就是没定，由调用方拦在翻译之前（用户口径：每部漫画都得手动选）。
        assertEquals(null to false, resolveSourceLanguage(MangaTranslationSettings()))
        assertEquals(null to false, resolveSourceLanguage(MangaTranslationSettings(sourceLanguage = "  ")))
    }

    @Test
    fun `入队要求明确的源语言和目标语言`() {
        assertTrue(translationSetupComplete("日语", "简体中文", false))
        assertFalse(translationSetupComplete("日语", null, false))
        assertFalse(translationSetupComplete(null, "简体中文", false))
        assertFalse(translationSetupComplete("日语", "简体中文", true))
    }

    @Test
    fun `入队请求不能绕过语言和快照校验`() {
        fun rejects(source: String?, target: String, auto: Boolean, snapshot: String?) {
            val result = runCatching { TranslationRequest(target, source, auto, snapshot, 1L) }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
        rejects(null, "简体中文", false, "{}")
        rejects("日语", "", false, "{}")
        rejects("日语", "简体中文", true, "{}")
        rejects("日语", "简体中文", false, null)
        assertEquals("日语", TranslationRequest("简体中文", "日语", false, "{}", 1L).sourceLanguage)
    }

}
