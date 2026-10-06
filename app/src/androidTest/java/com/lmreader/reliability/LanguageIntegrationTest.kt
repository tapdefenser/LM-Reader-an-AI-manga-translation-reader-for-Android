package com.lmreader.reliability

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.R
import com.lmreader.core.storage.settings.GeneralPreferences
import com.lmreader.ui.i18n.UiTextTranslations
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LanguageIntegrationTest {
    private fun context(tag: String) = ApplicationProvider.getApplicationContext<android.content.Context>().let { base ->
        base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(tag)))
        })
    }
    @Test fun otherSystemLanguagesHaveEnglishUiAndPlatformResources() {
        for (tag in listOf("en-US", "ja-JP", "ko-KR", "fr-FR", "ar", "zh-TW", "zh-Hant")) {
            val context = context(tag)
            assertEquals(tag, "All files access information", context.getString(R.string.lmreader_all_files_access_title))
            assertEquals(tag, "Pause", context.getString(R.string.lmreader_task_pause_one))
            assertEquals(tag, "Settings", UiTextTranslations.translate(context, "设置"))
        }
    }
    @Test fun simplifiedChineseHasChineseUiAndPlatformResources() {
        for (tag in listOf("zh-CN", "zh-SG", "zh-Hans")) {
            val context = context(tag)
            assertEquals(tag, "全部文件访问说明", context.getString(R.string.lmreader_all_files_access_title))
            assertEquals(tag, "暂停", context.getString(R.string.lmreader_task_pause_one))
            assertEquals(tag, "设置", UiTextTranslations.translate(context, "设置"))
        }
    }
    @Test fun manualChoiceAndFollowSystemKeepTheirSelection() {
        val isolated = IsolatedApp(context("ja-JP"))
        val preferences = GeneralPreferences(isolated)
        preferences.setLanguageTag(null)
        assertEquals(null, preferences.languageTag); assertEquals("en", preferences.effectiveLanguageTag)
        preferences.setLanguageTag("zh-Hans")
        assertEquals("zh-Hans", preferences.effectiveLanguageTag)
        preferences.setLanguageTag("en")
        assertEquals("en", preferences.effectiveLanguageTag)
        preferences.setLanguageTag(null)
        assertEquals(null, preferences.languageTag); assertEquals("en", preferences.effectiveLanguageTag)
    }
    @Test fun workflowTemplatesCountsAndDynamicCopyHaveReviewedEnglishTranslations() {
        val english = context("en-US")
        assertEquals("Reference: VL page translation", UiTextTranslations.translate(english, "参考：VL 整页直接翻译"))
        assertEquals("<Number> · 我的图片 · Item count", UiTextTranslations.translate(english, "<数字> · 我的图片 · 项数"))
        assertEquals("API output item count mismatch: expected 3, received 2", UiTextTranslations.translate(english, "API 输出项数不匹配：期望 3 项，实际 2 项"))
        assertEquals(" · Revision 2 · 14 rows", UiTextTranslations.translate(english, " · 版本 2 · 14 行"))
        assertEquals("VL page translation (one API request for all crops)", UiTextTranslations.translate(english, "VL 整页直接翻译（多图一次 API）"))
        val chinese = context("zh-Hans")
        assertEquals("参考：VL 整页直接翻译", UiTextTranslations.translate(chinese, "参考：VL 整页直接翻译"))
    }
}
