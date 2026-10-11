package com.lmreader.screen

import android.app.UiAutomation
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    @get:Rule val compose = createAndroidComposeRule<ScreenTranslationSettingsActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = AppContainer.from(instrumentation.targetContext)
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
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
        container.screenTranslation.preferences.update { original.copy(mode = ScreenTranslationMode.BROWSE,
            browseRegions = ScreenRegionSelection(landscape = listOf(ScreenNormalizedRegion(.2f, .3f, .8f, .9f))),
            videoRegions = ScreenRegionSelection("video-fixture", portrait = listOf(ScreenNormalizedRegion(.1f, .6f, .9f, .95f))),
            regionPresets = emptyList()) }
    }
    @After fun restore() = runBlocking<Unit> { container.screenTranslation.preferences.update { original } }

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
    private fun find(node: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (test(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), test)?.let { return it }
        return null
    }
    private fun nativeNode(description: String, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo = runBlocking {
        try { withTimeout(5000) {
            while (true) {
                find(automation.rootInActiveWindow, test)?.let { return@withTimeout it }
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") error("node not found")
        } } catch (failure: TimeoutCancellationException) {
            fun tree(node: AccessibilityNodeInfo?): String {
                if (node == null) return "no active window"
                return "${node.className}: ${node.text} editable=${node.isEditable} clickable=${node.isClickable}\n" +
                    (0 until node.childCount).joinToString("") { tree(node.getChild(it)) }
            }
            throw AssertionError("Missing $description:\n${tree(automation.rootInActiveWindow)}", failure)
        }
    }

    @Test fun savedPresetReturnsToTheDialogAndCanBeLoadedIntoEachModeIndependently() {
        val preferences = container.screenTranslation.preferences
        val originalVideo = preferences.settings.value.videoRegions
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen-region-select").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("screen-region-select").performScrollTo().performClick()
        compose.onNodeWithTag("screen-region-portrait").performClick()
        editorAction("＋ 添加区域")
        editorAction("保存当前方案")
        val name = "screen-region-ui-fixture"
        assertTrue(nativeNode("preset name field") { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name) }))
        val saveText = label("保存")
        assertTrue(nativeNode("save button ($saveText)") { it.text?.toString()?.equals(saveText, ignoreCase = true) == true && it.isClickable }
            .performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val preset = runBlocking { withTimeout(5000) { preferences.settings.first { settings -> settings.regionPresets.any { it.name == name } }.regionPresets.single() } }
        assertEquals(1, preset.portrait.size); assertEquals(1, preset.landscape.size)
        editorAction("完成区域编辑")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen-region-portrait").fetchSemanticsNodes().isNotEmpty() }
        // A background dialog can be destroyed during the editor's orientation
        // change. Exercise the returned instance rather than the original rule instance.
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ScreenTranslationSettingsActivity>().single().recreate()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen-region-portrait").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("screen-region-portrait").assertIsDisplayed()
        compose.onNodeWithText(label("应用区域")).performClick()
        compose.waitUntil(5000) { preferences.settings.value.browseRegions == preset }
        assertEquals(originalVideo, preferences.settings.value.videoRegions)

        runBlocking { preferences.update { it.copy(mode = ScreenTranslationMode.VIDEO) } }
        compose.onNodeWithTag("screen-region-select").performScrollTo().performClick()
        compose.onNodeWithTag("screen-choice:加载方案").performClick()
        compose.onNodeWithText(name).performClick()
        assertEquals("Loading a preset only changes the dialog draft", originalVideo, preferences.settings.value.videoRegions)
        compose.onNodeWithText(label("应用区域")).performClick()
        compose.waitUntil(5000) { preferences.settings.value.videoRegions == preset }
        assertEquals(preset, preferences.settings.value.browseRegions)

        compose.onNodeWithTag("screen-region-select").performScrollTo().performClick()
        compose.onNodeWithTag("screen-choice:加载方案").performClick()
        compose.onNodeWithText(label("全屏（无区域）")).performClick()
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ScreenTranslationSettingsActivity>().single().recreate()
        }
        compose.onNodeWithText(label("竖屏 0/8 · 横屏 0/8")).assertIsDisplayed()
        compose.onNodeWithText(label("取消")).performClick()
        assertEquals(preset, preferences.settings.value.videoRegions)
        runBlocking { preferences.update { it.withRegionSelection(ScreenTranslationMode.VIDEO, ScreenRegionSelection()) } }
        assertEquals(preset, preferences.settings.value.browseRegions)
        assertEquals(listOf(preset), preferences.settings.value.regionPresets)
    }
}
