package com.lmreader.screen

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.core.model.*
import com.lmreader.core.storage.settings.ScreenTranslationPreferences
import com.lmreader.ui.i18n.UiTextTranslations
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

@androidx.test.filters.SdkSuppress(minSdkVersion = 34)
class ScreenRegionEditorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun button(root: View, text: String): Button? {
        if (root is Button && root.text.toString() == text) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) button(root.getChildAt(i), text)?.let { return it }
        return null
    }
    @Suppress("DEPRECATION")
    @Test fun editorForcesBothRealOrientationsAndKeepsTheOtherOrientationWithEightRegionLimit() = runBlocking<Unit> {
        for (landscape in listOf(false, true)) {
            val token = java.util.UUID.randomUUID().toString()
            val other = ScreenNormalizedRegion(.1f, .1f, .5f, .6f)
            val draft = ScreenRegionSelection("fixture", if (landscape) listOf(other) else emptyList(), if (landscape) emptyList() else listOf(other))
            val intent = Intent(context, ScreenRegionEditorActivity::class.java).putExtra("regions", ScreenTranslationPreferences.encodeRegions(draft).toString())
                .putExtra("landscape", landscape).putExtra("token", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<ScreenRegionEditorActivity>(intent).use { scenario ->
                withTimeout(10_000) {
                    while (true) {
                        var ready = false
                        scenario.onActivity { activity ->
                            val metrics = android.util.DisplayMetrics(); activity.windowManager.defaultDisplay.getRealMetrics(metrics)
                            ready = (metrics.widthPixels > metrics.heightPixels) == landscape &&
                                button(activity.window.decorView, UiTextTranslations.translate(activity, "＋ 添加区域"))?.isEnabled == true
                        }
                        if (ready) break
                        delay(50)
                    }
                }
                scenario.onActivity { activity ->
                    val add = checkNotNull(button(activity.window.decorView, UiTextTranslations.translate(activity, "＋ 添加区域")))
                    repeat(8) { assertTrue(add.performClick()) }
                    assertFalse("Ninth rectangle must not be offered", add.isEnabled)
                    checkNotNull(button(activity.window.decorView, UiTextTranslations.translate(activity, "完成区域编辑"))).performClick()
                }
                withTimeout(5000) { while (ScreenRegionEditorResults.latest.value?.token != token) delay(30) }
                val result = ScreenRegionEditorResults.latest.value!!.selection
                assertEquals(8, if (landscape) result.landscape.size else result.portrait.size)
                assertEquals(listOf(other), if (landscape) result.portrait else result.landscape)
            }
        }
    }
    @Test fun transparentEditorBlocksTouchesToTheVisibleUnderlyingApplication() = runBlocking<Unit> {
        context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName, ScreenFixtureActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        delay(500)
        val intent = Intent(context, ScreenRegionEditorActivity::class.java).putExtra("regions", "{}")
            .putExtra("landscape", true).putExtra("token", "touch-fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ScreenRegionEditorActivity>(intent).use {
            delay(600)
            val before = checkNotNull(automation.takeScreenshot())
            try {
                val x = before.width * .2f; val y = before.height * .7f
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0)
                    try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                delay(300)
                val after = checkNotNull(automation.takeScreenshot())
                try {
                    val area = PixelRect(0f, before.height * .35f, before.width * .55f, before.height * .9f)
                    val bounds = PixelRect(0f, 0f, before.width.toFloat(), before.height.toFloat())
                    val a = CapturedScreen(before, bounds) {}.gray(area); val b = CapturedScreen(after, bounds) {}.gray(area)
                    assertTrue("The underlying fixture must remain visible", a.pixels.any { (it.toInt() and 255) > 150 })
                    assertTrue("Touch leaked to underlying app and changed its page", a.difference(b) < .2f)
                } finally { after.recycle() }
            } finally { before.recycle() }
        }
    }
    @Test fun maskedCropsAndMotionMasksUseTheUnionAndNeverChangeTheOriginal() {
        val bounds = PixelRect(0f, 0f, 200f, 100f)
        val areas = ScreenRegionSelection(landscape = listOf(ScreenNormalizedRegion(.1f, .1f, .6f, .7f),
            ScreenNormalizedRegion(.4f, .3f, .9f, .9f))).areas(bounds, bounds)
        val source = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val masked = maskedScreenCrop(source, bounds, areas)
        try {
            assertEquals(Color.RED, source.getPixel(0, 0)); assertEquals(Color.WHITE, masked.getPixel(0, 0))
            assertEquals(Color.RED, masked.getPixel(100, 50)); assertEquals(Color.RED, masked.getPixel(170, 85))
            val gray = ScreenGrayFrame(200, 100, ByteArray(200 * 100) { 90 })
            val motion = CapturedMotion(gray, bounds).restricted(areas)
            assertEquals(0.toByte(), motion.gray.valid!![0])
            assertEquals(1.toByte(), motion.gray.valid!![50 * 200 + 100])
            assertEquals(1.toByte(), motion.gray.valid!![85 * 200 + 170])
        } finally { if (masked !== source) masked.recycle(); source.recycle() }
    }
}
