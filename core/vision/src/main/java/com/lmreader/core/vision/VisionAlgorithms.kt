package com.lmreader.core.vision

import com.lmreader.core.model.*
import java.nio.FloatBuffer
import kotlin.math.*

internal data class Letterbox(val sourceWidth: Int, val sourceHeight: Int, val targetWidth: Int, val targetHeight: Int) {
    init { require(minOf(sourceWidth, sourceHeight, targetWidth, targetHeight) > 0) }
    private val gain = min(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
    val contentWidth = (sourceWidth * gain).toInt().coerceIn(1, targetWidth)
    val contentHeight = (sourceHeight * gain).toInt().coerceIn(1, targetHeight)
    val left = (targetWidth - contentWidth) / 2
    val top = (targetHeight - contentHeight) / 2
    fun point(x: Float, y: Float) = PixelPoint(
        ((x - left) * sourceWidth / contentWidth).coerceIn(0f, sourceWidth.toFloat()),
        ((y - top) * sourceHeight / contentHeight).coerceIn(0f, sourceHeight.toFloat()))
    fun rect(bounds: PixelRect): PixelRect {
        val a = point(bounds.left, bounds.top); val b = point(bounds.right, bounds.bottom)
        return PixelRect(a.x, a.y, b.x, b.y)
    }
}

internal data class ImageTile(val x: Int, val y: Int, val width: Int, val height: Int)
internal fun planTiles(width: Int, height: Int): List<ImageTile> {
    require(width > 0 && height > 0)
    // 竖长图按宽的两倍切片，横长图同理；25% 重叠避免截断边界文字/气泡。
    val vertical = height > width * 2L
    val horizontal = width > height * 2L
    if ((!vertical && !horizontal) || max(width,height) <= 1536) return listOf(ImageTile(0, 0, width, height))
    val length = if (vertical) height else width
    val size = min(length, max(512, (if (vertical) width else height) * 2))
    val step = max(1, size * 3 / 4)
    val starts = mutableListOf(0)
    while (starts.last() + size < length) starts += min(starts.last() + step, length - size)
    require(starts.size <= 128) { "图片过长，请先裁成较短的页面" }
    return starts.map { if (vertical) ImageTile(0, it, width, size) else ImageTile(it, 0, size, height) }
}

internal fun overlap(a: PixelRect, b: PixelRect): Float =
    max(0f, min(a.right, b.right) - max(a.left, b.left)) * max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
internal fun iou(a: PixelRect, b: PixelRect): Float {
    val intersection = overlap(a, b); val union = a.area + b.area - intersection
    return if (union > 0) intersection / union else 0f
}
internal fun duplicate(a: PixelRect, b: PixelRect): Boolean = iou(a, b) > .45f ||
    (min(a.area, b.area) > 0 && overlap(a, b) / min(a.area, b.area) > .8f)

/** 优先保留完整的大框；切片边缘的残字/半气泡即便分数更高，也不能挤掉完整结果。 */
internal fun <T> keepCompleteRegions(candidates: List<T>, bounds: (T) -> PixelRect, confidence: (T) -> Float,
                                     sameKind: (T,T) -> Boolean = { _,_ -> true }): List<T> {
    val kept=ArrayList<T>()
    for (candidate in candidates.sortedWith(compareByDescending<T> { bounds(it).area }.thenByDescending(confidence))) {
        if (kept.none { sameKind(it,candidate) && duplicate(bounds(it),bounds(candidate)) }) kept += candidate
    }
    return kept
}

internal data class RawSeg(val bounds: PixelRect, val confidence: Float, val classId: Int, val coefficients: FloatArray)
internal fun decodeSeg(values: FloatBuffer, anchors: Int, width: Int, height: Int, threshold: Float): List<RawSeg> {
    require(anchors > 0 && values.limit().toLong() == 38L * anchors && width > 0 && height > 0)
    require(threshold in 0f..1f)
    val candidates = ArrayList<RawSeg>()
    for (anchor in 0 until anchors) {
        fun at(channel: Int) = values[channel * anchors + anchor]
        val a = at(4); val b = at(5)
        if (!a.isFinite() || !b.isFinite()) continue
        val score = max(a, b)
        if (score !in threshold..1f) continue
        val cx = at(0) * width; val cy = at(1) * height
        val w = at(2) * width; val h = at(3) * height
        if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0 || h <= 0) continue
        val coefficients = FloatArray(32) { at(6 + it) }
        if (coefficients.any { !it.isFinite() }) continue
        candidates += RawSeg(PixelRect(cx-w/2, cy-h/2, cx+w/2, cy+h/2), score, if (a >= b) 0 else 1, coefficients)
    }
    val kept = ArrayList<RawSeg>()
    for (candidate in candidates.sortedByDescending { it.confidence }) {
        if (kept.none { it.classId == candidate.classId && duplicateSegBounds(it.bounds, candidate.bounds) }) kept += candidate
        if (kept.size == 300) break
    }
    return kept
}

internal data class Component(val indices: IntArray, val bounds: PixelRect)
/** 8 连通：供 DB 概率图及 Seg mask 共用，队列/访问表按整张概率图一次分配。 */
internal fun components(width: Int, height: Int, foreground: (Int) -> Boolean): List<Component> {
    require(width > 0 && height > 0)
    val visited = BooleanArray(width * height); val queue = IntArray(visited.size)
    val result = ArrayList<Component>()
    for (start in visited.indices) {
        if (visited[start] || !foreground(start)) continue
        var head = 0; var tail = 1; queue[0] = start; visited[start] = true
        var left = width; var top = height; var right = 0; var bottom = 0
        while (head < tail) {
            val index = queue[head++]; val x = index % width; val y = index / width
            left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
            for (ny in max(0, y-1)..min(height-1, y+1)) for (nx in max(0, x-1)..min(width-1, x+1)) {
                val next = ny * width + nx
                if (!visited[next] && foreground(next)) { visited[next] = true; queue[tail++] = next }
            }
        }
        result += Component(queue.copyOf(tail), PixelRect(left.toFloat(), top.toFloat(), (right+1).toFloat(), (bottom+1).toFloat()))
    }
    return result
}

internal fun segContour(raw: RawSeg, prototypes: FloatBuffer, protoWidth: Int, protoHeight: Int,
                        transform: Letterbox): List<PixelPoint> = segContours(raw, prototypes, protoWidth, protoHeight, transform).firstOrNull().orEmpty()

/** Retain substantial second lobes; a merged prediction's largest component is not its only balloon. */
internal fun segContours(raw: RawSeg, prototypes: FloatBuffer, protoWidth: Int, protoHeight: Int,
                        transform: Letterbox): List<List<PixelPoint>> {
    require(prototypes.limit() == protoWidth * protoHeight * 32)
    val sx = protoWidth.toFloat() / transform.targetWidth; val sy = protoHeight.toFloat() / transform.targetHeight
    val left = floor(raw.bounds.left * sx).toInt().coerceIn(0, protoWidth)
    val top = floor(raw.bounds.top * sy).toInt().coerceIn(0, protoHeight)
    val right = ceil(raw.bounds.right * sx).toInt().coerceIn(left, protoWidth)
    val bottom = ceil(raw.bounds.bottom * sy).toInt().coerceIn(top, protoHeight)
    val w = right-left; val h = bottom-top
    if (w == 0 || h == 0) return emptyList()
    val foreground = BooleanArray(w*h) { i ->
        val offset = ((i/w+top)*protoWidth + i%w+left)*32
        var value = 0f
        for (c in 0..31) value += raw.coefficients[c] * prototypes[offset+c]
        value.isFinite() && value > 0f // sigmoid(value) > 0.5
    }
    val parts = components(w,h) { foreground[it] }.sortedByDescending { it.indices.size }
    val largestSize = parts.firstOrNull()?.indices?.size ?: return emptyList()
    // Text masks may have disconnected lines, dots and accents. They form one text target;
    // balloon components still need separate identities to isolate connected speech.
    val retained = if (raw.classId == 1) listOf(parts.flatMap { it.indices.asIterable() }.toIntArray())
        else parts.filter { it.indices.size >= max(6, (largestSize * .04f).toInt()) }.map { it.indices }
    return retained.map { indices ->
        val minX = IntArray(h) { w }; val maxX = IntArray(h) { -1 }
        indices.forEach { i -> minX[i/w] = min(minX[i/w],i%w); maxX[i/w] = max(maxX[i/w],i%w) }
        val rows = (0 until h).filter { maxX[it] >= 0 }
        val sampled = rows.filterIndexed { index, _ -> index % max(1, rows.size/48) == 0 || index == rows.lastIndex }
        fun point(x: Int,y: Int) = transform.point((left+x)/sx,(top+y)/sy)
        sampled.map { point(minX[it],it) } +
            listOf(point(minX[rows.last()], rows.last()+1), point(maxX[rows.last()]+1, rows.last()+1)) +
            sampled.asReversed().map { point(maxX[it]+1,it) }
    }
}

internal fun dbBoxes(probabilities: FloatBuffer, width: Int, height: Int, transform: Letterbox,
    scoreThreshold: Float = .45f): List<Pair<PixelRect,Float>> {
    require(probabilities.limit() == width*height)
    require(scoreThreshold.isFinite() && scoreThreshold in 0f..1f)
    return components(width,height) {
        val value=probabilities[it]
        require(value.isFinite() && value in 0f..1.001f) { "文字检测输出不是有限概率" }
        value > .2f
    }.mapNotNull { c ->
        if (c.indices.size < 3) return@mapNotNull null // upstream retains small text kernels
        val score = c.indices.sumOf { probabilities[it].toDouble() }.toFloat()/c.indices.size
        if (score < scoreThreshold) return@mapNotNull null
        val box = c.bounds
        val expansion = box.area * 1.5f / (2 * (box.width+box.height))
        val sx = transform.targetWidth.toFloat()/width; val sy = transform.targetHeight.toFloat()/height
        val mapped = transform.rect(PixelRect((box.left-expansion)*sx,(box.top-expansion)*sy,
            (box.right+expansion)*sx,(box.bottom+expansion)*sy))
        if (mapped.width < 2 || mapped.height < 2) null else mapped to score
    }
}

internal fun requireFiniteTensor(values: FloatBuffer,name: String) {
    for (index in 0 until values.limit()) require(values[index].isFinite()) { "$name 输出包含非有限值" }
}

internal data class DecodedText(val text: String, val confidence: Float)
internal fun decodeCtc(values: FloatBuffer, shape: LongArray, characters: List<String>): DecodedText {
    require(shape.size == 3 && shape[0] == 1L && shape[2] == characters.size.toLong()) {
        "识别输出必须为 [1,T,${characters.size}]，实际为 ${shape.contentToString()}；模型与字符字典不匹配"
    }
    val steps = shape[1].toInt(); val classes = characters.size
    require(steps > 0 && values.limit().toLong() == steps.toLong()*classes && characters[0].isEmpty())
    val text = StringBuilder(); var last = -1; var score = 0f; var count = 0
    for (step in 0 until steps) {
        var best = 0; var probability = Float.NEGATIVE_INFINITY
        for (c in 0 until classes) {
            val p = values[step*classes+c]
            require(p.isFinite()) { "OCR 返回非有限概率" }
            if (p > probability) { probability = p; best = c }
        }
        if (best != 0 && best != last) {
            require(probability in 0f..1.001f) { "OCR 输出不是概率" }
            text.append(characters[best]); score += probability.coerceAtMost(1f); count++
        }
        last = best
    }
    return DecodedText(text.toString().trim(), if (count == 0) 0f else score/count)
}

internal fun readingOrder(lines: List<OcrLine>, language: LocalOcrLanguage): List<OcrLine> {
    val cjk = language in setOf(LocalOcrLanguage.JAPANESE, LocalOcrLanguage.CHINESE_SIMPLIFIED, LocalOcrLanguage.CHINESE_TRADITIONAL)
    val vertical = cjk && lines.count { it.bounds.height > it.bounds.width*1.5f } * 2 > lines.size
    return if (vertical) lines.sortedWith(compareByDescending<OcrLine> { it.bounds.right }.thenBy { it.bounds.top })
    else lines.sortedWith(compareBy<OcrLine> { it.bounds.top }.thenBy { it.bounds.left })
}
