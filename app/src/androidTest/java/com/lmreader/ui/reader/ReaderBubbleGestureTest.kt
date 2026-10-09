package com.lmreader.ui.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.ImageSource
import com.lmreader.R
import com.lmreader.core.model.*
import com.lmreader.core.vision.BubbleMaskRenderer
import com.lmreader.ui.reader.translation.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderBubbleGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun saved() = ReaderPageTranslation("gesture-fixture", "fixture", File("unused.json"),
        LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.CHINESE_SIMPLIFIED, "fixture", 400, 500,
        listOf(PageTranslatedRegion(PageTextRegion("bubble", RegionKind.BUBBLE, PixelRect(90f, 160f, 250f, 280f),
            emptyList(), "HELLO", emptyList()), "Hello")), emptyList(), 0)

    @Test fun nativeHandlesMoveResizeAndRotateAsThreeUndoableGestures() {
        var draft = PageBubbleDraft(saved()).select("bubble")
        val original = Bitmap.createBitmap(400, 500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val seed = BubbleMaskRenderer().prepareSource(original, draft.regions.map { it.region })
        lateinit var view: TapAwareSubsamplingImageView
        var navigationTaps = 0
        compose.setContent { AndroidView(factory = { activity ->
            view = TapAwareSubsamplingImageView(activity, { _, _ -> navigationTaps++ }).apply {
                overlayGeometry = ReaderOverlayGeometry(400, 500, PixelRect(0f, 0f, 400f, 500f))
                editing = true
                selectedBubble = "bubble"
                editableRegions = draft.regions
                overlay = seed.layout(draft.regions, BubbleRenderSettings())
                onBubbleGesture = { gesture ->
                    draft = when (gesture) {
                        is BubbleEditGesture.Begin -> draft.beginTransform(gesture.id)
                        is BubbleEditGesture.Transform -> draft.transform(gesture.id, gesture.bounds, gesture.rotation)
                        BubbleEditGesture.End -> draft.finishTransform()
                    }
                    editableRegions = draft.regions
                    overlay = seed.layout(draft.regions, BubbleRenderSettings())
                    invalidate()
                }
                setImage(ImageSource.bitmap(original))
            }
            view
        }, modifier = Modifier.fillMaxSize()) }
        compose.waitUntil(10_000) { view.isReady && view.width > 0 }
        fun drag(from: PixelPoint, to: PixelPoint) = compose.activityRule.scenario.onActivity {
            val start = checkNotNull(view.sourceToViewCoord(from.x, from.y))
            val end = checkNotNull(view.sourceToViewCoord(to.x, to.y))
            val down = SystemClock.uptimeMillis()
            for ((action, point, time) in listOf(Triple(MotionEvent.ACTION_DOWN, start, down),
                Triple(MotionEvent.ACTION_MOVE, end, down + 40), Triple(MotionEvent.ACTION_UP, end, down + 80))) {
                val event = MotionEvent.obtain(down, time, action, point.x, point.y, 0)
                try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
        }
        try {
            drag(PixelPoint(170f, 220f), PixelPoint(190f, 240f))
            assertEquals(110f, draft.selected!!.region.bounds.left, .1f)
            assertEquals(180f, draft.selected!!.region.bounds.top, .1f)
            assertEquals(1, draft.undo.size)
            drag(PixelPoint(270f, 300f), PixelPoint(310f, 330f))
            assertEquals(240f, draft.selected!!.region.bounds.width, .1f)
            assertEquals(180f, draft.selected!!.region.bounds.height, .1f)
            assertEquals(2, draft.undo.size)
            val bounds = draft.selected!!.region.bounds
            val cx = (bounds.left + bounds.right) / 2
            val cy = (bounds.top + bounds.bottom) / 2
            val corner = PixelPoint(bounds.right, bounds.top)
            drag(corner, PixelPoint(cx - (corner.y - cy), cy + (corner.x - cx)))
            assertEquals(90f, draft.selected!!.rotationDegrees, .1f)
            assertEquals(3, draft.undo.size)
            assertEquals(0, navigationTaps)
            compose.waitForIdle()
            val rendered = java.util.concurrent.CountDownLatch(1)
            compose.activityRule.scenario.onActivity {
                view.postOnAnimation { view.postOnAnimation { rendered.countDown() } }
            }
            assertTrue(rendered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val capture = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try { File(compose.activity.filesDir, "bubble-edit-validation.png").outputStream().use {
                assertTrue(capture.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { capture.recycle() }
            draft = draft.undoChange().undoChange().undoChange()
            assertEquals(saved().regions, draft.regions)
        } finally { compose.activityRule.scenario.onActivity { view.recycle() } }
    }

    @Test fun toolbarAddsBubblesAndChangesFontWithUndoAndSave() {
        var draft by mutableStateOf(PageBubbleDraft(saved()).select("bubble"))
        var saves = 0
        compose.setContent { MaterialTheme {
            ReaderBubbleEditor(ReaderTranslationUiState(editing = true, draft = draft),
                onText = { draft = draft.editText(it) }, onDelete = { draft = draft.deleteSelected() },
                onSave = { saves++ }, onUndo = { draft = draft.undoChange() }, onExit = {}, onClearFailure = {},
                onAdd = { draft = draft.addBubble() }, onIncreaseFont = { draft = draft.scaleFont(10) },
                onDecreaseFont = { draft = draft.scaleFont(-10) })
        } }
        fun click(id: Int) = compose.onNodeWithText(compose.activity.getString(id)).performClick()
        click(R.string.reader_bubble_font_increase)
        compose.runOnIdle { assertEquals(110, draft.selected!!.fontScalePercent) }
        click(R.string.reader_bubble_font_decrease)
        compose.runOnIdle { assertEquals(100, draft.selected!!.fontScalePercent) }
        click(R.string.reader_bubble_add)
        compose.runOnIdle { assertEquals(2, draft.regions.size); assertTrue(draft.selected!!.region.id.contains(":manual:")) }
        click(R.string.reader_bubble_delete)
        compose.runOnIdle { assertEquals(1, draft.regions.size) }
        click(R.string.reader_bubble_undo)
        compose.runOnIdle { assertEquals(2, draft.regions.size) }
        click(R.string.reader_bubble_save)
        compose.runOnIdle { assertEquals(1, saves) }
    }
}
