package com.lmreader.screen

import android.content.pm.ActivityInfo
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lmreader.core.model.*
import com.lmreader.core.storage.settings.GeneralPreferences
import com.lmreader.di.AppContainer
import com.lmreader.ui.i18n.UiTextTranslations
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

/** Exercises the actual modal -> separate editor task -> modal round trip. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 34)
class ScreenRegionSettingsTest {
    // The product returns from a separate editor task and may recreate this
    // activity. Own its lifecycle directly rather than pinning ActivityScenario
    // to the original dialog instance.
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = AppContainer.from(instrumentation.targetContext)
    private lateinit var original: ScreenTranslationSettings
    private fun label(text: String): String {
        val context = instrumentation.targetContext
        val localized = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
            setLocales(android.os.LocaleList.forLanguageTags(GeneralPreferences(context).effectiveLanguageTag))
        })
        return UiTextTranslations.translate(localized, text)
    }

    @Before fun prepare() = runBlocking<Unit> {
        container.startupReady.await()
        original = container.screenTranslation.preferences.current()
        container.screenTranslation.stopAndAwait()
        val prepared = original.copy(mode = ScreenTranslationMode.BROWSE,
            browseRegions = ScreenRegionSelection(landscape = listOf(ScreenNormalizedRegion(.2f, .3f, .8f, .9f))),
            videoRegions = ScreenRegionSelection("video-fixture", portrait = listOf(ScreenNormalizedRegion(.1f, .6f, .9f, .95f))),
            regionPresets = emptyList())
        container.screenTranslation.preferences.update { prepared }
        container.screenTranslation.preferences.settings.first { it == prepared }
        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, ScreenTranslationSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen-region-select").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun restore() = runBlocking<Unit> {
        container.screenTranslation.preferences.update { original }
        container.screenTranslation.preferences.settings.first { it == original }
        // Editor returns may create a replacement settings instance in another
        // task. Finish all of this fixture's instances, including the paused
        // original, before the next test launches its own settings task.
        instrumentation.runOnMainSync {
            Stage.entries.filter { it != Stage.DESTROYED }.flatMap {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it).toList()
            }.filter { it is ScreenTranslationSettingsActivity || it is ScreenRegionEditorActivity }.distinct().forEach { it.finish() }
        }
    }

    private fun button(root: View, text: String): Button? {
        if (root is Button && root.text.toString() == text) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) button(root.getChildAt(i), text)?.let { return it }
        return null
    }
    private fun editorAction(text: String) = runBlocking {
        withTimeout(10_000) {
            while (true) {
                var clicked = false
                instrumentation.runOnMainSync {
                    val editor = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<ScreenRegionEditorActivity>().firstOrNull()
                    editor?.let { activity -> button(activity.window.decorView, UiTextTranslations.translate(activity, text))
                        ?.takeIf { it.isEnabled }?.let { clicked = it.performClick() } }
                }
                if (clicked) break
                delay(50)
            }
        }
    }
    private fun recreateSettings(orientation: Int? = null) {
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ScreenTranslationSettingsActivity>().single()
            if (orientation == null) activity.recreate() else activity.requestedOrientation = orientation
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen-region-portrait").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun savedPresetReturnsToTheDialogAndCanBeLoadedIntoEachModeIndependently() {
        val preferences = container.screenTranslation.preferences
        val originalVideo = preferences.settings.value.videoRegions
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen-region-select").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("screen-region-select").performScrollTo().performClick()
        val name = "screen-region-ui-fixture"
        compose.onNodeWithTag("screen-region-new").performClick()
        compose.onNodeWithTag("screen-region-new-name").performTextInput(name)
        compose.onNodeWithTag("screen-region-create").performClick()
        compose.waitUntil(5000) { preferences.settings.value.regionPresets.any { it.name == name } }
        assertEquals(ScreenRegionSelection(name), preferences.settings.value.regionPresets.single())
        compose.onNodeWithTag("screen-region-portrait").performClick()
        editorAction("＋ 添加区域")
        instrumentation.runOnMainSync {
            val editor = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ScreenRegionEditorActivity>().single()
            assertNull("Saving is managed by the regions dialog", button(editor.window.decorView, UiTextTranslations.translate(editor, "保存当前方案")))
        }
        editorAction("完成区域编辑")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen-region-portrait").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("screen-region-landscape").performScrollTo().performClick()
        editorAction("＋ 添加区域")
        editorAction("完成区域编辑")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen-region-portrait").fetchSemanticsNodes().isNotEmpty() }
        // A background dialog can be destroyed during the editor's orientation
        // change. Exercise the returned instance rather than the original rule instance.
        recreateSettings()
        compose.onNodeWithTag("screen-region-portrait").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label("应用区域")).performClick()
        compose.waitUntil(5000) { preferences.settings.value.browseRegions.name == name && preferences.settings.value.browseRegions.portrait.size == 1 }
        val preset = preferences.settings.value.regionPresets.single()
        assertEquals(1, preset.portrait.size); assertEquals(1, preset.landscape.size)
        assertEquals(preset, preferences.settings.value.browseRegions)
        assertEquals(originalVideo, preferences.settings.value.videoRegions)

        runBlocking { preferences.update { it.copy(mode = ScreenTranslationMode.VIDEO) } }
        compose.onNodeWithTag("screen-region-select").performScrollTo().performClick()
        compose.onNodeWithTag("screen-choice:加载方案").performScrollTo().performClick()
        compose.onNodeWithText(name).performClick()
        assertEquals("Loading a preset only changes the dialog draft", originalVideo, preferences.settings.value.videoRegions)
        compose.onNodeWithText(label("应用区域")).performClick()
        compose.waitUntil(5000) { preferences.settings.value.videoRegions == preset }
        assertEquals(preset, preferences.settings.value.browseRegions)

        compose.onNodeWithTag("screen-region-select").performScrollTo().performClick()
        compose.onNodeWithTag("screen-choice:加载方案").performScrollTo().performClick()
        compose.onNodeWithText(label("全屏（无区域）")).performClick()
        recreateSettings()
        compose.onNodeWithText(label("竖屏 0/8 · 横屏 0/8")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label("取消")).performClick()
        assertEquals(preset, preferences.settings.value.videoRegions)
        runBlocking { preferences.update { it.withRegionSelection(ScreenTranslationMode.VIDEO, ScreenRegionSelection()) } }
        assertEquals(preset, preferences.settings.value.browseRegions)
        assertEquals(listOf(preset), preferences.settings.value.regionPresets)
    }

    @Test fun newValidatesNamesAndDeletingAPresetDoesNotChangeAppliedModeCopies() {
        val preferences = container.screenTranslation.preferences
        val preset = preferences.settings.value.videoRegions
        runBlocking {
            preferences.update { it.copy(browseRegions = preset, regionPresets = listOf(preset)) }
            preferences.settings.first { it.browseRegions == preset && it.regionPresets == listOf(preset) }
        }
        compose.onNodeWithTag("screen-region-select").performScrollTo().performClick()
        compose.onNodeWithTag("screen-region-new").performClick()
        compose.onNodeWithTag("screen-region-create").performClick()
        compose.onNodeWithText(label("请输入方案名称")).assertIsDisplayed()
        compose.onNodeWithTag("screen-region-new-name").performTextInput("  ${preset.name}  ")
        compose.onNodeWithTag("screen-region-create").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(label("同名方案已存在")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label("同名方案已存在")).assertIsDisplayed()
        assertEquals(listOf(preset), preferences.settings.value.regionPresets)
        compose.onNodeWithTag("screen-region-create-cancel").performClick()
        compose.onNodeWithTag("screen-region-delete").performClick()
        compose.waitUntil(5000) { preferences.settings.value.regionPresets.isEmpty() }
        assertEquals(preset, preferences.settings.value.browseRegions)
        assertEquals(preset, preferences.settings.value.videoRegions)
        compose.onNodeWithText(label("取消")).performClick()
        // The still-applied geometry is a copy of a now deleted preset.
        compose.onNodeWithTag("screen-region-select").performScrollTo().performClick()
        compose.onNodeWithTag("screen-region-delete").assertIsNotEnabled()
        compose.onNodeWithText(label("应用区域")).performClick()
        compose.waitForIdle()
        assertTrue(preferences.settings.value.regionPresets.isEmpty())
        assertEquals(preset, preferences.settings.value.videoRegions)
    }

    @Test fun landscapeDialogScrollsToBothEditorsAndKeepsActionsVisible() {
        compose.onNodeWithTag("screen-region-select").performScrollTo()
        val selectBounds = compose.onNodeWithTag("screen-region-select").fetchSemanticsNode().boundsInRoot
        val modeBounds = compose.onNodeWithText(label("浏览"), substring = false).fetchSemanticsNode().boundsInRoot
        assertTrue("Region selection directly follows the mode choices", selectBounds.top >= modeBounds.bottom && selectBounds.top - modeBounds.bottom < 80)
        compose.onNodeWithTag("screen-region-select").performClick()
        recreateSettings(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        compose.waitUntil(10_000) { instrumentation.targetContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        compose.onNodeWithTag("screen-region-scrollbar").assertIsDisplayed()
        compose.onNodeWithTag("screen-region-portrait").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("screen-region-landscape").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label("竖屏 0/8 · 横屏 1/8")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("screen-region-new").assertIsDisplayed()
        compose.onNodeWithTag("screen-region-delete").assertIsDisplayed()
        compose.onNodeWithText(label("应用区域")).assertIsDisplayed()
        compose.onNodeWithText(label("取消")).assertIsDisplayed().performClick()
    }
}
