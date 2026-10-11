package com.lmreader.screen

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import com.lmreader.R
import com.lmreader.core.model.*
import com.lmreader.core.vision.BubbleMaskRenderer
import com.lmreader.core.vision.BubbleOverlay

internal class ScreenOverlay(private val context: Context, trusted: Boolean, private val tap: () -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val type = if (trusted) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private val opacity = if (trusted || Build.VERSION.SDK_INT < 31) 1f else context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
    private val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    private val geometryHandler = Handler(Looper.getMainLooper())
    private val displays = context.getSystemService(DisplayManager::class.java)
    private var closed = false
    private var geometryRevision = 0L
    private var geometry: DisplayGeometry? = null
    private var dockAfterResize = false
    private data class DisplayGeometry(val width: Int, val height: Int, val rotation: Int, val viewport: ScreenBallViewport)
    private val settleButton = Runnable {
        if (!closed) {
            if (dockAfterResize) ballPosition = ballPosition.docked()
            dockAfterResize = false
            placeButton()
            savePosition()
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            @Suppress("DEPRECATION")
            if (displayId == manager.defaultDisplay.displayId) geometryChanged()
        }
    }
    private val bubbles = ArrayList<Bubble>()
    private val publications = ScreenRegionPublication()
    private var objectRevision = 0L
    private var contentToScreen = ScreenTransform()
    private var translationAreas: List<PixelRect>? = null
    private var translationClip: Path? = null
    var trackingRevision = 0L
        private set
    private var inside = true
    private var temporarilyHidden = false
    private data class Bubble(val id: String, val bounds: PixelRect, val drawing: BubbleOverlay, var transform: ScreenTransform,
        var contentBounds: PixelRect = transform.bounds(bounds), val template: ScreenObjectTemplate? = null,
        val version: Long = 0, var revision: Long = 0, var fixedPose: ScreenTransform? = null,
        var failures: Int = 0, var sampledSequence: Long = Long.MIN_VALUE,
        val capturedContentBounds: PixelRect = contentBounds)
    private val layer = object : View(context) {
        private val location = IntArray(2)
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
            super.onConfigurationChanged(newConfig)
            geometryChanged()
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            geometryChanged()
        }
        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            val result = super.onApplyWindowInsets(insets)
            geometryChanged()
            return result
        }
        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(location)
            val root = canvas.save(); canvas.translate(-location[0].toFloat(), -location[1].toFloat())
            translationClip?.let(canvas::clipPath)
            try { bubbles.forEach { bubble ->
                val save = canvas.save()
                val pose = bubble.fixedPose ?: bubble.transform.then(contentToScreen)
                canvas.translate(pose.x, pose.y); canvas.scale(pose.scale, pose.scale)
                bubble.drawing.draw(canvas); canvas.restoreToCount(save)
            } } finally { canvas.restoreToCount(root) }
        }
    }
    private val layerParams = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).apply { alpha = opacity }
    private val size = (48 * context.resources.displayMetrics.density).toInt()
    private val tabSize = (size / 3).coerceAtLeast(1)
    private val icon = ImageView(context).apply {
        setImageResource(R.drawable.lmreader_ic_translate)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val padding = size / 4
        setPadding(padding, padding, padding, padding)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(27, 75, 90)) }
    }
    private val position = context.getSharedPreferences("screen-ball-position", Context.MODE_PRIVATE)
    private var ballPosition = ScreenBallPosition(position.getFloat("x", .9f).coerceIn(0f, 1f),
        position.getFloat("y", .7f).coerceIn(0f, 1f), position.getBoolean("tucked", false))
    private var clickAllowed = true
    private val button = FrameLayout(context).apply {
        contentDescription = context.getString(R.string.screen_translation_button)
        addView(icon, FrameLayout.LayoutParams(size, size))
        elevation = 8 * context.resources.displayMetrics.density
        setOnClickListener {
            if (ballPosition.tucked) {
                ballPosition = ballPosition.copy(tucked = false); placeButton(); savePosition()
            } else if (clickAllowed) tap()
        }
    }
    private val buttonParams = params(size, size, flags).apply {
        // Positions below already include the safe insets, in display coordinates.
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }
    private fun params(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
    @Suppress("DEPRECATION")
    private fun displayGeometry(): DisplayGeometry {
        // A service's Resources metrics can still describe the previous orientation.
        val display = manager.defaultDisplay
        val metrics = android.util.DisplayMetrics().also(display::getRealMetrics)
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        // Accessibility overlays can receive zero view insets while a status bar
        // is visible. Display metrics provide the same safe area for both types.
        val insets = if (Build.VERSION.SDK_INT >= 30) manager.maximumWindowMetrics.windowInsets else layer.rootWindowInsets
        val safe = if (Build.VERSION.SDK_INT >= 30) {
            insets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())?.let { Rect(it.left, it.top, it.right, it.bottom) } ?: Rect()
        } else Rect(insets?.systemWindowInsetLeft ?: 0, insets?.systemWindowInsetTop ?: 0,
            insets?.systemWindowInsetRight ?: 0, insets?.systemWindowInsetBottom ?: 0)
        val left = safe.left.coerceIn(0, width - 1)
        val top = safe.top.coerceIn(0, height - 1)
        val viewport = ScreenBallViewport(left, top, (width - safe.right).coerceIn(left + 1, width),
            (height - safe.bottom).coerceIn(top + 1, height))
        return DisplayGeometry(width, height, display.rotation, viewport)
    }
    private fun geometryChanged() {
        if (closed) return
        val next = displayGeometry()
        val previous = geometry
        if (next == previous) return
        geometry = next
        geometryRevision++
        if (previous != null && (next.rotation != previous.rotation || next.width != previous.width || next.height != previous.height)) dockAfterResize = true
        // Keep the hit target inside immediately, then dock after rotation/insets settle.
        placeButton(next)
        geometryHandler.removeCallbacks(settleButton)
        geometryHandler.postDelayed(settleButton, 150)
    }
    private fun placeButton(display: DisplayGeometry = displayGeometry()) {
        val placement = display.viewport.layout(ballPosition, size, tabSize)
        buttonParams.width = placement.width
        buttonParams.height = placement.height
        buttonParams.x = placement.x
        buttonParams.y = placement.y
        icon.translationX = if (ballPosition.tucked && ballPosition.x < .5f) (tabSize - size).toFloat() else 0f
        if (button.isAttachedToWindow) manager.updateViewLayout(button, buttonParams)
        trackingRevision++
    }
    private fun savePosition() {
        position.edit().putFloat("x", ballPosition.x).putFloat("y", ballPosition.y).putBoolean("tucked", ballPosition.tucked).apply()
    }
    init {
        geometry = displayGeometry()
        placeButton()
        manager.addView(layer, layerParams); manager.addView(button, buttonParams)
        displays.registerDisplayListener(displayListener, geometryHandler)
        var downX = 0f; var downY = 0f; var start = ballPosition; var dragging = false
        var gestureGeometry = geometryRevision
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        button.setOnTouchListener { _, event ->
            // A gesture started in the old orientation must not undo the repair.
            if (event.actionMasked != MotionEvent.ACTION_DOWN && gestureGeometry != geometryRevision) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    geometryHandler.removeCallbacks(settleButton); dockAfterResize = false
                    downX = event.rawX; downY = event.rawY; start = ballPosition; dragging = false; gestureGeometry = geometryRevision; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.hypot(event.rawX - downX, event.rawY - downY) > touchSlop) dragging = true
                    if (dragging) {
                        val viewport = displayGeometry().viewport
                        ballPosition = start.drag(event.rawX - downX, event.rawY - downY,
                            (viewport.width - size).coerceAtLeast(0).toFloat(), (viewport.height - size).coerceAtLeast(0).toFloat(), touchSlop)
                        placeButton()
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) button.performClick() else {
                        val viewport = displayGeometry().viewport
                        ballPosition = start.drag(event.rawX - downX, event.rawY - downY,
                            (viewport.width - size).coerceAtLeast(0).toFloat(), (viewport.height - size).coerceAtLeast(0).toFloat(), touchSlop, released = true)
                        placeButton(); savePosition()
                    }; true
                }
                MotionEvent.ACTION_CANCEL -> { ballPosition = ballPosition.copy(tucked = false); placeButton(); savePosition(); true }
                else -> true
            }
        }
    }
    fun buttonState(inside: Boolean, ready: Boolean, busy: Boolean, active: Boolean, manual: Boolean) {
        this.inside = inside
        refreshVisibility()
        // Disabled Views cannot receive drag gestures. Gate only translation clicks.
        clickAllowed = !(manual && busy)
        button.alpha = if (!ready || manual && busy || !manual && !active) .35f else 1f
    }
    private fun refreshVisibility() {
        val visibility = if (inside || temporarilyHidden) View.INVISIBLE else View.VISIBLE
        layer.visibility = visibility; button.visibility = visibility
    }
    fun visible(visible: Boolean) { temporarilyHidden = !visible; refreshVisibility() }
    suspend fun awaitDraw() {
        // Wait for view submission to cross a display frame boundary, without a fixed sleep.
        repeat(2) { kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
            val choreographer = Choreographer.getInstance()
            val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit, onCancellation = { _, _, _ -> }) }
            continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
            choreographer.postFrameCallback(callback)
        } }
    }
    fun systemExclusions() = (context as? ScreenAccessibilityService)?.systemExclusions.orEmpty()
    fun buttonBounds(): PixelRect {
        val location = IntArray(2); button.getLocationOnScreen(location)
        return PixelRect(location[0].toFloat(), location[1].toFloat(), (location[0] + button.width).toFloat(), (location[1] + button.height).toFloat())
    }
    /** Reject full-display streams containing this opaque button. No extra verification UI. */
    fun containsOwnButton(frame: CapturedScreen): Boolean {
        if (inside || temporarilyHidden || button.alpha < .99f || button.width < 2 || button.height < 2) return false
        val bounds = buttonBounds()
        if (!bounds.intersects(frame.bounds)) return false
        val expected = Bitmap.createBitmap(button.width, button.height, Bitmap.Config.ARGB_8888)
        try {
            button.draw(Canvas(expected))
            var count = 0; var agree = 0
            for (y in 2 until expected.height - 2 step 2) for (x in 2 until expected.width - 2 step 2) {
                val color = expected.getPixel(x, y)
                if (Color.alpha(color) < 250) continue
                val px = ((bounds.left + x - frame.bounds.left) * frame.bitmap.width / frame.bounds.width).toInt()
                val py = ((bounds.top + y - frame.bounds.top) * frame.bitmap.height / frame.bounds.height).toInt()
                if (px !in 0 until frame.bitmap.width || py !in 0 until frame.bitmap.height) continue
                val actual = frame.bitmap.getPixel(px, py)
                val error = kotlin.math.abs(Color.red(color) - Color.red(actual)) + kotlin.math.abs(Color.green(color) - Color.green(actual)) + kotlin.math.abs(Color.blue(color) - Color.blue(actual))
                count++; if (error < 50) agree++
            }
            return count >= 50 && agree > count * .8f
        } finally { expected.recycle() }
    }
    val count get() = bubbles.size
    fun screenBounds(): PixelRect {
        val display = displayGeometry()
        return PixelRect(0f, 0f, display.width.toFloat(), display.height.toFloat())
    }
    fun setTranslationAreas(areas: List<PixelRect>?) {
        if (translationAreas == areas) return
        translationAreas = areas?.toList()
        translationClip = areas?.let { rectangles -> Path().apply { rectangles.forEach { r ->
            addRect(r.left, r.top, r.right, r.bottom, Path.Direction.CW)
        } } }
        layer.invalidate()
    }
    fun positions() = bubbles.associate { it.id to (it.fixedPose ?: it.transform.then(contentToScreen)).bounds(it.bounds) }
    fun sourceRegions(rawPose: ScreenTransform) = bubbles.map { bubble -> ScreenSourceRegion(bubble.id,
        (bubble.fixedPose ?: bubble.transform.then(rawPose)).bounds(bubble.bounds), bubble.version) }
    fun fixedRegions() = bubbles.mapNotNull { bubble -> bubble.fixedPose?.bounds(bubble.bounds) }
    fun fixedContentAreas() = bubbles.filter { it.fixedPose != null }.map { it.capturedContentBounds }
    fun motionObjects(rawPose: ScreenTransform) = bubbles.map { bubble -> ScreenMotionObject(bubble.id, bubble.revision,
        bubble.bounds, bubble.fixedPose ?: bubble.transform.then(rawPose), bubble.template,
        if (bubble.fixedPose == null) ScreenObjectAttachment.CONTENT else ScreenObjectAttachment.FIXED,
        bubble.failures, bubble.sampledSequence) }
    fun trackingExclusions(): List<PixelRect> = positions().values.toList() +
        PixelRect(buttonParams.x.toFloat(), buttonParams.y.toFloat(), (buttonParams.x + buttonParams.width).toFloat(), (buttonParams.y + size).toFloat()) +
        (context as? ScreenAccessibilityService)?.systemExclusions.orEmpty()
    /** Actual visible insets: a hidden/absent navigation bar reserves no space. */
    fun matchingArea(viewport: PixelRect): PixelRect {
        val safe = displayGeometry().viewport
        val left = maxOf(viewport.left, safe.left.toFloat()).coerceAtMost(viewport.right - 1)
        val top = maxOf(viewport.top, safe.top.toFloat()).coerceAtMost(viewport.bottom - 1)
        return PixelRect(left, top,
            minOf(viewport.right, safe.right.toFloat()).coerceAtLeast(left + 1),
            minOf(viewport.bottom, safe.bottom.toFloat()).coerceAtLeast(top + 1))
    }
    fun clear() { bubbles.clear(); publications.clear(); contentToScreen = ScreenTransform(); trackingRevision++; layer.invalidate() }
    fun clearArea(area: PixelRect) { bubbles.removeAll { (it.fixedPose ?: it.transform.then(contentToScreen)).bounds(it.bounds).intersects(area) }; trackingRevision++; layer.invalidate() }
    /** Resolve content/fixed candidates before the single render invalidation. */
    fun follow(rawPose: ScreenTransform, displayPose: ScreenTransform, viewport: PixelRect, margin: Int,
        decisions: List<ScreenObjectDecision>): List<PixelRect> {
        val invalidated = ArrayList<PixelRect>()
        var redraw = contentToScreen != displayPose
        for (decision in decisions) {
            val bubble = bubbles.find { it.id == decision.id && it.revision == decision.revision } ?: continue
            val before = bubble.fixedPose ?: bubble.transform.then(contentToScreen)
            if (decision.remove) {
                invalidated += bubble.capturedContentBounds
                invalidated += rawPose.inverse().bounds(decision.sourceToScreen.bounds(bubble.bounds))
                bubbles.remove(bubble); redraw = true; continue
            }
            if (decision.attachment == ScreenObjectAttachment.FIXED) {
                if (bubble.fixedPose == null) invalidated += bubble.capturedContentBounds
                bubble.fixedPose = decision.sourceToScreen
            } else {
                bubble.fixedPose = null
                bubble.transform = decision.sourceToScreen.then(rawPose.inverse())
                bubble.contentBounds = bubble.transform.bounds(bubble.bounds)
            }
            bubble.failures = decision.failures; bubble.sampledSequence = decision.sequence; bubble.revision = ++objectRevision
            val after = bubble.fixedPose ?: bubble.transform.then(displayPose)
            if (kotlin.math.abs(before.scale - after.scale) > .000001f || kotlin.math.abs(before.x - after.x) > .01f ||
                kotlin.math.abs(before.y - after.y) > .01f) redraw = true
        }
        contentToScreen = displayPose
        val retained = screenRetentionBounds(viewport, margin)
        if (bubbles.removeAll { !(it.fixedPose ?: it.transform.then(rawPose)).bounds(it.bounds).intersects(retained) }) redraw = true
        publications.retain(rawPose.inverse().bounds(retained), retained)
        if (redraw) { trackingRevision++; layer.postInvalidateOnAnimation() }
        return invalidated
    }
    fun move(transform: ScreenTransform, viewport: PixelRect, margin: Int, scrollingArea: PixelRect? = null) {
        if (scrollingArea != null) {
            val area = contentToScreen.inverse().bounds(scrollingArea)
            val compensate = contentToScreen.then(transform.inverse()).then(contentToScreen.inverse())
            bubbles.forEach { bubble -> if (!bubble.contentBounds.intersects(area)) {
                bubble.transform = bubble.transform.then(compensate)
                bubble.contentBounds = bubble.transform.bounds(bubble.bounds)
            } }
        }
        // A whole-screen pan/zoom changes one shared transform. Paths, glyphs
        // and each bubble's local transform stay prepared at their original size.
        contentToScreen = contentToScreen.then(transform)
        trackingRevision++
        val retained = contentToScreen.inverse().bounds(screenRetentionBounds(viewport, margin))
        bubbles.removeAll { it.fixedPose == null && !it.contentBounds.intersects(retained) }
        layer.postInvalidateOnAnimation()
    }
    data class PreparedBubble(val id: String, val bounds: PixelRect, val drawing: BubbleOverlay, val template: ScreenObjectTemplate? = null)
    /** Expensive paths and text layout run outside the gesture/event thread. */
    fun prepare(source: Bitmap, regions: List<PageTranslatedRegion>, settings: BubbleRenderSettings): List<PreparedBubble> {
        val render = settings.copy(opacityPercent = (minOf(settings.opacityPercent / 100f, opacity) / opacity * 100).toInt().coerceIn(0, 100))
        val renderer = BubbleMaskRenderer()
        return regions.filter { it.translatedText.isNotBlank() }.map { region ->
            PreparedBubble(region.region.id, region.region.bounds, renderer.prepare(source, listOf(region), render))
        }
    }
    fun add(prepared: List<PreparedBubble>, transform: ScreenTransform) {
        val local = transform.then(contentToScreen.inverse())
        prepared.forEach { region ->
            val bounds = local.bounds(region.bounds)
            fun overlap(r: PixelRect) = (minOf(bounds.right, r.right) - maxOf(bounds.left, r.left)).coerceAtLeast(0f) *
                (minOf(bounds.bottom, r.bottom) - maxOf(bounds.top, r.top)).coerceAtLeast(0f)
            // A context crop at an exposed edge may contain only part of an
            // already translated bubble. Preserve that complete old bubble.
            if (bubbles.any { old -> val r = old.contentBounds
                bounds.area < r.area * .65f && overlap(r) > bounds.area * .65f }) return@forEach
            bubbles.removeAll { old ->
                val r = old.contentBounds
                overlap(r) > minOf(bounds.area, r.area) * .65f
            }
            bubbles += Bubble(region.id, region.bounds, region.drawing, local)
        }
        trackingRevision++; layer.invalidate()
    }
    fun replaceBrowse(prepared: List<PreparedBubble>, pageToCapture: ScreenTransform, atCapture: ScreenTransform,
        rawPose: ScreenTransform, displayPose: ScreenTransform, version: Long, crop: ScreenMergedCrop,
        decisions: List<ScreenObjectDecision>, ownedAreas: List<PixelRect> = listOf(crop.bounds)) {
        val selected = decisions.associateBy { it.id }
        val accepted = prepared.mapNotNull { region ->
            if (crop.preserves.any { it.bounds.intersects(pageToCapture.bounds(region.bounds)) }) return@mapNotNull null
            val decision = selected[region.id]
            if (decision?.remove == true) return@mapNotNull null
            val fixed = decision?.attachment == ScreenObjectAttachment.FIXED
            val screen = decision?.sourceToScreen ?: pageToCapture.then(atCapture.inverse().then(rawPose))
            val local = screen.then(rawPose.inverse())
            val ownership = if (fixed) screen.bounds(region.bounds) else atCapture.inverse().bounds(pageToCapture.bounds(region.bounds))
            if (!publications.accepts(ownership, version, fixed)) return@mapNotNull null
            Bubble(region.id, region.bounds, region.drawing, local, local.bounds(region.bounds), region.template,
                version, ++objectRevision, if (fixed) screen else null, decision?.failures ?: 0,
                decision?.sequence ?: Long.MIN_VALUE, atCapture.inverse().bounds(pageToCapture.bounds(region.bounds)))
        }
        bubbles.removeAll { old -> old.id in crop.replaces && old.version <= version &&
            publications.accepts(old.fixedPose?.bounds(old.bounds) ?: old.contentBounds, version, old.fixedPose != null) }
        val incomingIds = accepted.map { it.id }.toSet()
        bubbles.removeAll { it.id in incomingIds && it.version <= version }
        bubbles.addAll(accepted)
        for (area in ownedAreas) {
            publications.commit(atCapture.inverse().bounds(area), version)
            publications.commit(area, version, screenFixed = true)
        }
        contentToScreen = displayPose; trackingRevision++; layer.invalidate()
    }
    override fun close() {
        if (closed) return
        closed = true
        geometryHandler.removeCallbacks(settleButton)
        displays.unregisterDisplayListener(displayListener)
        bubbles.clear(); runCatching { manager.removeViewImmediate(button) }; runCatching { manager.removeViewImmediate(layer) }
    }
}
