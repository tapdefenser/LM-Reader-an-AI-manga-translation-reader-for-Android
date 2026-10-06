package com.lmreader.core.vision

import android.graphics.*
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.lmreader.core.model.*
import kotlin.math.*

/** Shared overlay preparation for reading and export. Original pixels are never modified. */
class BubbleMaskRenderer {
    /** Paths and sampled original colors are reusable across text edits and appearance changes. */
    fun prepareSource(source: Bitmap, regions: List<PageTextRegion>): BubbleOverlaySource {
        require(!source.isRecycled && source.config != Bitmap.Config.HARDWARE)
        return BubbleOverlaySource(regions.map { region ->
            val path = regionPath(region)
            if (region.kind == RegionKind.BUBBLE && region.contour.size >= 3) {
                val border = Path()
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
                    strokeWidth = min(region.bounds.width, region.bounds.height).times(.015f).coerceIn(2f, 8f) * 2
                }.getFillPath(path, border)
                val inner = Path(path)
                if (inner.op(border, Path.Op.DIFFERENCE) && !inner.isEmpty) path.set(inner)
            }
            val background = backgroundColor(source, region)
            OverlaySeed(region, path, background,
                if (region.kind == RegionKind.BUBBLE && region.textBounds.isNotEmpty()) protectedInk(source, region, background) else null)
        })
    }

    fun prepare(source: Bitmap, regions: List<PageTranslatedRegion>, settings: BubbleRenderSettings) =
        prepareSource(source, regions.map { it.region }).layout(regions, settings, hideEmpty = true)

    /** Export composition only. Reader draws the very same prepared overlay directly on its Canvas. */
    fun render(source: Bitmap, regions: List<PageTranslatedRegion>, settings: BubbleRenderSettings): Bitmap {
        val overlay = prepare(source, regions, settings)
        val output = source.copy(Bitmap.Config.ARGB_8888, true) ?: error("Cannot allocate export image")
        try { overlay.draw(Canvas(output)); return output }
        catch (failure: Throwable) { output.recycle(); throw failure }
    }

    private fun regionPath(region: PageTextRegion) = Path().apply {
        if (region.contour.size >= 3) {
            moveTo(region.contour[0].x, region.contour[0].y)
            region.contour.drop(1).forEach { lineTo(it.x, it.y) }; close()
        } else {
            val r = region.bounds; val radius = min(r.width, r.height) * .08f
            addRoundRect(r.left, r.top, r.right, r.bottom, radius, radius, Path.Direction.CW)
        }
    }

    private fun protectedInk(source: Bitmap, region: PageTextRegion, background: Int): Path {
        // Preserve original outlines by cutting holes in the mask. Store paths, not image patches.
        val result = Path()
        val left = floor(region.bounds.left).toInt().coerceIn(0, source.width)
        val right = ceil(region.bounds.right).toInt().coerceIn(left, source.width)
        val top = floor(region.bounds.top).toInt().coerceIn(0, source.height)
        val bottom = ceil(region.bounds.bottom).toInt().coerceIn(top, source.height)
        val width = right - left
        if (width == 0) return result
        val pixels = IntArray(width)
        for (y in top until bottom) {
            source.getPixels(pixels, 0, width, left, y, width, 1)
            var start = -1
            for (column in 0..width) {
                val x = left + column
                val keep = if (column == width) false else {
                    val color = pixels[column]
                    val text = region.textBounds.any { x >= it.left - 2 && x <= it.right + 2 && y >= it.top - 2 && y <= it.bottom + 2 }
                    val contrast = maxOf(abs(Color.red(color) - Color.red(background)), abs(Color.green(color) - Color.green(background)), abs(Color.blue(color) - Color.blue(background)))
                    !text && contrast >= 64
                }
                if (keep && start < 0) start = x
                if (!keep && start >= 0) {
                    result.addRect(start.toFloat(), y.toFloat(), x.toFloat(), y + 1f, Path.Direction.CW)
                    start = -1
                }
            }
        }
        return result
    }

    internal fun fitLayout(text: String,width: Int,height: Int,paint: TextPaint): StaticLayout {
        require(width>0 && height>0 && text.isNotEmpty())
        fun build(size: Float): StaticLayout {
            paint.textSize=size
            return StaticLayout.Builder.obtain(text,0,text.length,paint,width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setLineSpacing(0f,1.05f)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY).build()
        }
        // Trailing spaces are consumed by wrapping, not visible overflow. Including
        // them makes otherwise fitting paragraphs fail and shrinks the font too far.
        fun fits(layout: StaticLayout)=layout.height<=height && (0 until layout.lineCount).all {layout.getLineMax(it)<=width+.5f} &&
            layout.getLineEnd(layout.lineCount-1)==text.length
        var low=0f;var high=max(width,height).toFloat()
        require(fits(build(low))) {"Text cannot fit inside its mask"}
        repeat(12) { if (high - low > .25f) { val mid=(low+high)/2;if(fits(build(mid))) low=mid else high=mid } }
        return build(low)
    }

    private fun backgroundColor(image: Bitmap,region: PageTextRegion): Int {
        if (region.kind == RegionKind.FREE_TEXT) {
            // VL regions have no recognized line boxes. Sampling inside them chooses the
            // heavy lettering as the background; upstream samples the surrounding artwork.
            val r = region.bounds
            val distance = max(1f, min(r.width, r.height) * .06f)
            var red = 0L; var green = 0L; var blue = 0L; var count = 0
            fun outside(x: Float, y: Float) {
                if (x < 0 || y < 0 || x >= image.width || y >= image.height) return
                val color = image.getPixel(x.toInt(), y.toInt())
                red += Color.red(color); green += Color.green(color); blue += Color.blue(color); count++
            }
            val horizontal = ceil(r.width / 4).toInt().coerceIn(1, 512)
            val vertical = ceil(r.height / 4).toInt().coerceIn(1, 512)
            repeat(horizontal) { i ->
                val x = r.left + (i + .5f) * r.width / horizontal
                outside(x, r.top - distance); outside(x, r.bottom + distance)
            }
            repeat(vertical) { i ->
                val y = r.top + (i + .5f) * r.height / vertical
                outside(r.left - distance, y); outside(r.right + distance, y)
            }
            if (count > 0) return Color.rgb((red/count).toInt(), (green/count).toInt(), (blue/count).toInt())
        }
        // Sample the untouched image. Quantized dominant color rejects sparse ink and panel lines.
        val buckets=linkedMapOf<Int,MutableList<Int>>()
        fun sample(px: Float,py: Float) {
            if(region.textBounds.any {px>=it.left && px<=it.right && py>=it.top && py<=it.bottom}) return
            val color=image.getPixel(px.toInt().coerceIn(0,image.width-1),py.toInt().coerceIn(0,image.height-1))
            val key=(Color.red(color)/16 shl 8)+(Color.green(color)/16 shl 4)+Color.blue(color)/16
            buckets.getOrPut(key) {arrayListOf()} += color
        }
        val r=region.bounds
        for(y in 0 until 24) for(x in 0 until 24) {
            val px=r.left+(x+.5f)*r.width/24;val py=r.top+(y+.5f)*r.height/24
            if(region.contour.size>=3 && !polygonContains(region.contour,px,py)) continue
            sample(px,py)
        }
        // A fallback OCR box is entirely occupied by text; sample the surrounding paper instead.
        if(buckets.isEmpty()) {
            val margin=max(3f,min(r.width,r.height)*.15f)
            for(i in 0 until 24) {
                val x=r.left+(i+.5f)*r.width/24;val y=r.top+(i+.5f)*r.height/24
                sample(x,r.top-margin);sample(x,r.bottom+margin)
                sample(r.left-margin,y);sample(r.right+margin,y)
            }
        }
        val colors=buckets.maxByOrNull {it.value.size}?.value ?: return Color.WHITE
        return Color.rgb(colors.map {Color.red(it)}.average().roundToInt(),colors.map {Color.green(it)}.average().roundToInt(),colors.map {Color.blue(it)}.average().roundToInt())
    }
    companion object {const val VERSION=4}
}

internal data class OverlayBubble(val region: PageTextRegion, val path: Path, val fill: Paint,
    val textRect: RectF, val layout: StaticLayout?, val protectedInk: Path?)

internal data class OverlaySeed(val region: PageTextRegion, val path: Path, val background: Int, val protectedInk: Path?) {
    // Caption contours follow the original ink, not the rectangular space available
    // to a translated paragraph. Inscribing a rectangle can discard most of that space.
    val textBounds = if(region.kind == RegionKind.FREE_TEXT) region.bounds else safeTextBounds(region)
    fun maskPath(settings: BubbleRenderSettings): Path {
        if (region.kind != RegionKind.FREE_TEXT || settings.freeTextMaskExpansionPercent == 0) return path
        // Keep the original seed reusable: mask expansion changes immediately with style.
        val expanded = Path(path); val border = Path()
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
            strokeWidth = (min(region.bounds.width, region.bounds.height) * settings.freeTextMaskExpansionPercent / 100).coerceAtMost(32f) * 2
        }.getFillPath(path, border)
        check(expanded.op(border, Path.Op.UNION))
        return expanded
    }
}

class BubbleOverlaySource internal constructor(seeds: List<OverlaySeed>) {
    private val byId = seeds.associateBy { it.region.id }
    fun layout(regions: List<PageTranslatedRegion>, settings: BubbleRenderSettings, hideEmpty: Boolean = false,
        includeEmptyForEditing: Boolean = false): BubbleOverlay {
        val renderer = BubbleMaskRenderer()
        return BubbleOverlay(regions.filter { !hideEmpty || includeEmptyForEditing || it.translatedText.isNotBlank() }.map { item ->
            val seed = byId.getValue(item.region.id)
            require(seed.region.renderGeometry() == item.region.renderGeometry()) { "Bubble geometry changed; prepare the original again" }
            val color = if (settings.fillMode == BubbleFillMode.AUTO) seed.background else Color.WHITE
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                alpha = if (hideEmpty && item.translatedText.isBlank()) 0 else settings.opacityPercent * 255 / 100
            }
            val safe = seed.textBounds
            val pad = min(safe.width, safe.height) * settings.textPaddingPercent / 100 *
                if(item.region.kind == RegionKind.FREE_TEXT) .35f else 1f
            val rect = RectF(safe.left + pad, safe.top + pad, safe.right - pad, safe.bottom - pad)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = if (Color.red(color) * .2126f + Color.green(color) * .7152f + Color.blue(color) * .0722f > 145) Color.BLACK else Color.WHITE
                typeface = Typeface.create(when (settings.font) {
                    BubbleFont.SYSTEM -> null
                    BubbleFont.SANS_SERIF -> "sans-serif"
                    BubbleFont.SERIF -> "serif"
                    BubbleFont.MONOSPACE -> "monospace"
                }, if (settings.bold) Typeface.BOLD else Typeface.NORMAL)
            }
            val text = item.translatedText.trim()
            var layout = if (text.isNotEmpty() && rect.width() >= 1 && rect.height() >= 1)
                renderer.fitLayout(text, rect.width().toInt(), rect.height().toInt(), paint) else null
            if (layout != null && settings.fontScalePercent != 100) {
                paint.textSize *= settings.fontScalePercent / 100f
                layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, rect.width().toInt())
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setLineSpacing(0f,1.05f)
                    .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY).build()
            }
            OverlayBubble(item.region, seed.maskPath(settings), fill, rect, layout, seed.protectedInk)
        })
    }
}

/** Holds only vector paths, paints and text layouts; no source or translated bitmap. */
class BubbleOverlay internal constructor(private val bubbles: List<OverlayBubble>) {
    private val editingPen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    fun draw(canvas: Canvas) {
        bubbles.forEach { bubble ->
            val fillSave = canvas.save()
            try {
                bubble.protectedInk?.let { canvas.clipOutPath(it) }
                canvas.drawPath(bubble.path, bubble.fill)
            } finally { canvas.restoreToCount(fillSave) }
            val layout = bubble.layout ?: return@forEach
            val rect = bubble.textRect
            val save = canvas.save()
            try {
                if(bubble.region.kind == RegionKind.FREE_TEXT) {
                    val bounds = bubble.region.bounds
                    canvas.clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                } else { canvas.clipPath(bubble.path); canvas.clipRect(rect) }
                canvas.translate(rect.left + (rect.width() - layout.width) / 2, rect.top + (rect.height() - layout.height) / 2)
                // Keep captions legible where their new line wrapping crosses artwork
                // outside the original ink mask. The artwork and mask contour stay intact.
                if(bubble.region.kind == RegionKind.FREE_TEXT && layout.paint.textSize >= 4f) {
                    val paint = layout.paint
                    val color = paint.color; val stroke = paint.strokeWidth; val style = paint.style; val join = paint.strokeJoin
                    try {
                        paint.color = if(color == Color.BLACK) Color.WHITE else Color.BLACK
                        paint.style = Paint.Style.STROKE; paint.strokeJoin = Paint.Join.ROUND
                        paint.strokeWidth = (paint.textSize * .06f).coerceIn(1f, 4f)
                        layout.draw(canvas)
                    } finally { paint.color = color; paint.strokeWidth = stroke; paint.style = style; paint.strokeJoin = join }
                }
                layout.draw(canvas)
            } finally { canvas.restoreToCount(save) }
        }
    }

    fun hitTest(x: Float, y: Float): String? = bubbles.asReversed().firstOrNull { bubble ->
        val r = bubble.region
        x >= r.bounds.left && x <= r.bounds.right && y >= r.bounds.top && y <= r.bounds.bottom &&
            (r.contour.size < 3 || polygonContains(r.contour, x, y))
    }?.region?.id

    fun drawEditing(canvas: Canvas, selected: String?, strokeWidth: Float) {
        val pen = editingPen
        bubbles.forEach { bubble ->
            pen.color = if (bubble.region.id == selected) Color.rgb(255, 166, 45) else Color.rgb(38, 140, 255)
            pen.strokeWidth = strokeWidth * if (bubble.region.id == selected) 2 else 1
            canvas.drawPath(bubble.path, pen)
        }
    }
}
