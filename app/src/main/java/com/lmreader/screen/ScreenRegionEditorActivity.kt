package com.lmreader.screen

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.content.Intent
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.*
import com.lmreader.core.model.*
import com.lmreader.core.storage.settings.ScreenTranslationPreferences
import com.lmreader.core.storage.settings.GeneralPreferences
import com.lmreader.di.AppContainer
import com.lmreader.ui.i18n.UiTextTranslations
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import kotlin.math.abs

internal data class ScreenRegionEditorResult(val token: String, val selection: ScreenRegionSelection,
    val mode: ScreenTranslationMode = ScreenTranslationMode.BROWSE, val consumed: Boolean = false)
internal object ScreenRegionEditorResults { val latest = MutableStateFlow<ScreenRegionEditorResult?>(null) }

/** A separate translucent task exposes the target app but consumes all touches.
 * Orientation is requested on this activity only; system rotation settings are untouched. */
class ScreenRegionEditorActivity : ComponentActivity() {
    private val container by lazy { AppContainer.from(this) }
    private lateinit var editor: RegionView
    private lateinit var status: TextView
    private lateinit var add: Button
    private lateinit var done: Button
    private var landscape = false
    private var draft = ScreenRegionSelection()
    private var initialDraft = ScreenRegionSelection()
    private var mode = ScreenTranslationMode.BROWSE
    private var token = ""
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private fun label(text: String) = UiTextTranslations.translate(this, text)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.createConfigurationContext(android.content.res.Configuration(newBase.resources.configuration).apply {
            setLocales(android.os.LocaleList.forLanguageTags(GeneralPreferences(newBase).effectiveLanguageTag))
        }))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra("token").orEmpty()
        landscape = intent.getBooleanExtra("landscape", false)
        initialDraft = ScreenTranslationPreferences.decodeRegions(JSONObject(intent.getStringExtra("regions") ?: "{}"))
        draft = savedInstanceState?.getString("regions")?.let { ScreenTranslationPreferences.decodeRegions(JSONObject(it)) } ?: initialDraft
        mode = runCatching { ScreenTranslationMode.valueOf(intent.getStringExtra("mode").orEmpty()) }.getOrDefault(ScreenTranslationMode.BROWSE)
        originalOrientation = savedInstanceState?.getInt("originalOrientation") ?: requestedOrientation
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val root = FrameLayout(this)
        editor = RegionView().apply { regions.addAll(if (landscape) draft.landscape else draft.portrait) }
        root.addView(editor, FrameLayout.LayoutParams(-1, -1))
        val toolbox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.argb(185, 25, 35, 45)) }
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f }
        toolbox.addView(status)
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = label(text); isAllCaps = false; setOnClickListener { action() }; toolbox.addView(this)
        }
        add = button("＋ 添加区域") {
            if (editor.regions.size < 8) {
                val offset = editor.regions.size * .035f
                editor.regions += ScreenNormalizedRegion(.12f + offset, .18f + offset, .62f + offset, .52f + offset)
                editor.invalidate(); refreshTools()
            }
        }
        done = button("完成区域编辑") { complete(apply = true) }
        button("取消本次编辑") { complete(apply = false) }
        root.addView(toolbox, FrameLayout.LayoutParams(dp(210), -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(64); rightMargin = dp(24) })
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { complete(apply = false) }
        })
        root.post(::refreshTools)
    }
    override fun onStart() { super.onStart(); container.screenTranslation.setVisible(this, true) }
    override fun onStop() { container.screenTranslation.setVisible(this, false); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        syncDraft(); outState.putString("regions", ScreenTranslationPreferences.encodeRegions(draft).toString())
        outState.putInt("originalOrientation", originalOrientation); super.onSaveInstanceState(outState)
    }
    @Suppress("DEPRECATION")
    private fun displayBounds(): PixelRect {
        val metrics = android.util.DisplayMetrics(); windowManager.defaultDisplay.getRealMetrics(metrics)
        return PixelRect(0f, 0f, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
    }
    private fun correctOrientation() = (displayBounds().width > displayBounds().height) == landscape
    private fun refreshTools() {
        if (!::status.isInitialized) return
        val ready = correctOrientation()
        status.text = if (ready) label(if (landscape) "横屏区域" else "竖屏区域") + "  ${editor.regions.size}/8\n" + label("没有矩形框时翻译全屏")
            else label("系统未应用所选方向，请先旋转设备，再编辑。")
        add.isEnabled = ready && editor.regions.size < 8; done.isEnabled = ready
    }
    private fun syncDraft() {
        draft = if (landscape) draft.copy(landscape = editor.regions.toList()) else draft.copy(portrait = editor.regions.toList())
    }
    private fun complete(apply: Boolean) {
        if (apply) syncDraft()
        val selection = if (apply) draft else initialDraft
        ScreenRegionEditorResults.latest.value = ScreenRegionEditorResult(token, selection, mode)
        requestedOrientation = originalOrientation
        startActivity(ScreenTranslationSettingsActivity.intent(this)
            .putExtra("region-return-token", token).putExtra("region-return-mode", mode.name)
            .putExtra("region-return-selection", ScreenTranslationPreferences.encodeRegions(selection).toString()))
        finishAndRemoveTask()
    }
    private inner class RegionView : View(this@ScreenRegionEditorActivity) {
        val regions = mutableListOf<ScreenNormalizedRegion>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val location = IntArray(2)
        private var selected = -1; private var handle = 0
        private var startX = 0f; private var startY = 0f; private var start = PixelRect(0f, 0f, 0f, 0f)
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); post(::refreshTools) }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.argb(18, 0, 0, 0))
            val display = displayBounds(); getLocationOnScreen(location)
            val save = canvas.save(); canvas.translate(-location[0].toFloat(), -location[1].toFloat())
            for ((index, region) in regions.withIndex()) {
                val r = region.pixels(display)
                paint.style = Paint.Style.FILL; paint.color = Color.argb(42, 60, 200, 255); canvas.drawRect(r.left, r.top, r.right, r.bottom, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); paint.color = Color.CYAN
                canvas.drawRect(r.left, r.top, r.right, r.bottom, paint)
                paint.style = Paint.Style.FILL
                for (x in listOf(r.left, (r.left + r.right) / 2, r.right)) for (y in listOf(r.top, (r.top + r.bottom) / 2, r.bottom))
                    if (x != (r.left + r.right) / 2 || y != (r.top + r.bottom) / 2) canvas.drawCircle(x, y, dp(5).toFloat(), paint)
                paint.color = Color.WHITE; paint.textSize = dp(16).toFloat(); canvas.drawText("${index + 1}", r.left + dp(10), r.top + dp(24), paint)
                val cx = r.right - dp(20); val cy = r.top + dp(20)
                paint.color = Color.argb(220, 190, 35, 40); canvas.drawCircle(cx, cy, dp(16).toFloat(), paint)
                paint.color = Color.WHITE; paint.strokeWidth = dp(3).toFloat()
                canvas.drawLine(cx - dp(6), cy - dp(6), cx + dp(6), cy + dp(6), paint)
                canvas.drawLine(cx - dp(6), cy + dp(6), cx + dp(6), cy - dp(6), paint)
            }
            canvas.restoreToCount(save)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!correctOrientation()) return true
            getLocationOnScreen(location)
            val x = event.x + location[0]; val y = event.y + location[1]; val display = displayBounds()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    selected = -1; handle = 0
                    for (i in regions.indices.reversed()) {
                        val r = regions[i].pixels(display)
                        if (abs(x - (r.right - dp(20))) < dp(18) && abs(y - (r.top + dp(20))) < dp(18)) {
                            regions.removeAt(i); invalidate(); refreshTools(); return true
                        }
                        val radius = dp(20)
                        if (x < r.left - radius || x > r.right + radius || y < r.top - radius || y > r.bottom + radius) continue
                        selected = i; start = r; startX = x; startY = y
                        if (abs(x - r.left) < radius) handle = handle or 1
                        if (abs(x - r.right) < radius) handle = handle or 2
                        if (abs(y - r.top) < radius) handle = handle or 4
                        if (abs(y - r.bottom) < radius) handle = handle or 8
                        break
                    }
                }
                MotionEvent.ACTION_MOVE -> if (selected in regions.indices) {
                    val dx = x - startX; val dy = y - startY
                    val minimumX = display.width * .025f; val minimumY = display.height * .025f
                    val r = if (handle == 0) {
                        val mx = dx.coerceIn(-start.left, display.right - start.right); val my = dy.coerceIn(-start.top, display.bottom - start.bottom)
                        start.offset(mx, my)
                    } else PixelRect(
                        if (handle and 1 != 0) (start.left + dx).coerceIn(0f, start.right - minimumX) else start.left,
                        if (handle and 4 != 0) (start.top + dy).coerceIn(0f, start.bottom - minimumY) else start.top,
                        if (handle and 2 != 0) (start.right + dx).coerceIn(start.left + minimumX, display.right) else start.right,
                        if (handle and 8 != 0) (start.bottom + dy).coerceIn(start.top + minimumY, display.bottom) else start.bottom)
                    regions[selected] = ScreenNormalizedRegion(r.left / display.width, r.top / display.height, r.right / display.width, r.bottom / display.height)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> { selected = -1; performClick() }
                MotionEvent.ACTION_CANCEL -> selected = -1
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
