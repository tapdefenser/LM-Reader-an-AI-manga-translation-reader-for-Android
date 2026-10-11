package com.lmreader.core.model

import kotlin.math.*

/** Small clean grayscale frames describe simultaneous pan and uniform zoom. */
data class ScreenGrayFrame(val width: Int, val height: Int, val pixels: ByteArray, val valid: ByteArray? = null) {
    init { require(width > 16 && height > 16 && pixels.size == width * height && (valid == null || valid.size == pixels.size)) }
    fun at(x: Int, y: Int) = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)].toInt() and 255
    fun difference(other: ScreenGrayFrame): Float {
        if (width != other.width || height != other.height) return 100f
        var sum = 0L; var count = 0
        for (i in pixels.indices) if ((valid == null || valid[i] != 0.toByte()) && (other.valid == null || other.valid[i] != 0.toByte())) {
            sum += abs((pixels[i].toInt() and 255) - (other.pixels[i].toInt() and 255)); count++
        }
        return if (count == 0) 0f else sum * 100f / (count * 255f)
    }
}

data class ScreenMotionEstimate(val content: ScreenTransform, val fixedAreas: List<PixelRect> = emptyList())

object ScreenMotionTracker {
    private data class Feature(val x: Int, val y: Int, val score: Float)
    private data class Match(val from: Feature, val to: Feature)
    private val pairs = java.util.Random(17031).let { random -> List(64) { IntArray(4) { random.nextInt(33) - 16 } } }
    private fun descriptor(frame: ScreenGrayFrame, point: Feature, scale: Float, radius: Int): Long {
        var bits = 0L
        val support = scale * radius / 16
        pairs.forEachIndexed { index, p ->
            if (frame.at(point.x + (p[0] * support).roundToInt(), point.y + (p[1] * support).roundToInt()) <
                frame.at(point.x + (p[2] * support).roundToInt(), point.y + (p[3] * support).roundToInt())) bits = bits or (1L shl index)
        }
        return bits
    }
    private fun features(frame: ScreenGrayFrame, radius: Int = 16, limit: Int = 220, step: Int = 1): List<Feature> {
        val points = ArrayList<Feature>()
        // O(1) patch checks prevent our own masks/buttons becoming stationary
        // features, without painting artificial edges into the source frame.
        val stride = frame.width + 1
        val blocked = frame.valid?.let { valid -> IntArray(stride * (frame.height + 1)).also { sum ->
            for (y in 0 until frame.height) for (x in 0 until frame.width) {
                val i = (y + 1) * stride + x + 1
                sum[i] = sum[i - 1] + sum[i - stride] - sum[i - stride - 1] + if (valid[y * frame.width + x] == 0.toByte()) 1 else 0
            }
        } }
        fun usable(x: Int, y: Int): Boolean {
            if (blocked == null) return true
            val l = maxOf(0, x - radius); val r = minOf(frame.width, x + radius + 1)
            val t = maxOf(0, y - radius); val b = minOf(frame.height, y + radius + 1)
            return blocked[b * stride + r] - blocked[b * stride + l] - blocked[t * stride + r] + blocked[t * stride + l] == 0
        }
        for (top in 8 until frame.height - 8 step 12) for (left in 8 until frame.width - 8 step 12) {
            var best: Feature? = null
            for (y in top until min(top + 12, frame.height - 8) step step) for (x in left until min(left + 12, frame.width - 8) step step) {
                if (!usable(x, y)) continue
                var xx = 0f; var yy = 0f; var xy = 0f
                for (dy in -1..1) for (dx in -1..1) {
                    val gx = (frame.at(x + dx + 1, y + dy) - frame.at(x + dx - 1, y + dy)).toFloat()
                    val gy = (frame.at(x + dx, y + dy + 1) - frame.at(x + dx, y + dy - 1)).toFloat()
                    xx += gx * gx; yy += gy * gy; xy += gx * gy
                }
                val energy = xx + yy
                val score = xx * yy - xy * xy - .04f * energy * energy
                if (score > 10000 && (best == null || score > best.score)) best = Feature(x, y, score)
            }
            best?.let(points::add)
        }
        // Round-robin quotas over an 8x8 grid. A dense text block must not
        // displace all corners from the rest of the page.
        val cells = points.groupBy { (it.y * 8 / frame.height).coerceAtMost(7) * 8 + (it.x * 8 / frame.width).coerceAtMost(7) }
            .toSortedMap().values.map { it.sortedByDescending { point -> point.score } }
        val selected = ArrayList<Feature>(limit)
        for (rank in 0 until (limit + 63) / 64 + 1) for (cell in cells) {
            cell.getOrNull(rank)?.let { if (selected.size < limit) selected += it }
        }
        return selected
    }

    private fun sample(frame: ScreenGrayFrame, x: Float, y: Float): Float {
        val l = floor(x).toInt(); val t = floor(y).toInt(); val u = x - l; val v = y - t
        return (frame.at(l, t) * (1 - u) + frame.at(l + 1, t) * u) * (1 - v) +
            (frame.at(l, t + 1) * (1 - u) + frame.at(l + 1, t + 1) * u) * v
    }
    private fun patchValid(frame: ScreenGrayFrame, x: Float, y: Float, radius: Int = 4): Boolean {
        val l = floor(x).toInt() - radius; val t = floor(y).toInt() - radius
        if (l < 0 || t < 0 || l + radius * 2 + 1 >= frame.width || t + radius * 2 + 1 >= frame.height) return false
        val mask = frame.valid ?: return true
        for (yy in t..t + radius * 2 + 1) for (xx in l..l + radius * 2 + 1) if (mask[yy * frame.width + xx] == 0.toByte()) return false
        return true
    }
    private fun pyramid(frame: ScreenGrayFrame): List<ScreenGrayFrame> {
        val result = ArrayList<ScreenGrayFrame>(3); result += frame
        repeat(2) {
            val previous = result.last(); val w = previous.width / 2; val h = previous.height / 2
            if (minOf(w, h) <= 16) return@repeat
            val mask = previous.valid?.let { ByteArray(w * h) }
            val pixels = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x; val p = y * 2 * previous.width + x * 2
                pixels[i] = ((previous.at(x * 2, y * 2) + previous.at(x * 2 + 1, y * 2) +
                    previous.at(x * 2, y * 2 + 1) + previous.at(x * 2 + 1, y * 2 + 1)) / 4).toByte()
                if (mask != null) mask[i] = if (previous.valid[p] != 0.toByte() && previous.valid[p + 1] != 0.toByte() &&
                    previous.valid[p + previous.width] != 0.toByte() && previous.valid[p + previous.width + 1] != 0.toByte()) 1 else 0
            }
            result += ScreenGrayFrame(w, h, pixels, mask)
        }
        return result
    }
    /** Sparse inverse-compositional flow: bounded 7x7 patches instead of full-frame scale searches. */
    private fun track(before: List<ScreenGrayFrame>, after: List<ScreenGrayFrame>, point: PixelPoint, initial: PixelPoint): PixelPoint? {
        var dx = initial.x - point.x; var dy = initial.y - point.y
        var tracked = false
        val values = FloatArray(49); val target = FloatArray(49); val gx = FloatArray(49); val gy = FloatArray(49)
        for (level in before.lastIndex downTo 0) {
            val a = before[level]; val b = after[level]; val factor = (1 shl level).toFloat()
            val x = point.x / factor; val y = point.y / factor
            if (!patchValid(a, x, y)) continue
            var qx = x + dx / factor; var qy = y + dy / factor
            var xx = 0f; var xy = 0f; var yy = 0f; var index = 0
            var mean = 0f; var square = 0f
            for (v in -3..3) for (u in -3..3) {
                val px = x + u; val py = y + v
                values[index] = sample(a, px, py)
                mean += values[index]; square += values[index] * values[index]
                gx[index] = (sample(a, px + 1, py) - sample(a, px - 1, py)) * .5f
                gy[index] = (sample(a, px, py + 1) - sample(a, px, py - 1)) * .5f
                xx += gx[index] * gx[index]; xy += gx[index] * gy[index]; yy += gy[index] * gy[index]; index++
            }
            val determinant = xx * yy - xy * xy
            if (determinant < 1000f) continue
            mean /= 49
            val variance = maxOf(0f, square / 49 - mean * mean)
            fun readTarget(): Pair<Float, Float>? {
                var sum = 0f; var squared = 0f; var i = 0
                for (v in -3..3) for (u in -3..3) {
                    val value = sample(b, qx + u, qy + v)
                    target[i++] = value; sum += value; squared += value * value
                }
                val targetMean = sum / 49
                val targetVariance = maxOf(0f, squared / 49 - targetMean * targetMean)
                if (minOf(variance, targetVariance) < 4f) return null
                val gain = sqrt(variance / targetVariance)
                return if (gain in .25f..4f) gain to (mean - gain * targetMean) else null
            }
            var valid = true
            for (iteration in 0 until 10) {
                if (!patchValid(b, qx, qy)) { valid = false; break }
                val lighting = readTarget()
                if (lighting == null) { valid = false; break }
                var ex = 0f; var ey = 0f; index = 0
                for (v in -3..3) for (u in -3..3) {
                    val error = values[index] - (target[index] * lighting.first + lighting.second)
                    ex += gx[index] * error; ey += gy[index] * error; index++
                }
                val stepX = (yy * ex - xy * ey) / determinant
                val stepY = (xx * ey - xy * ex) / determinant
                if (!stepX.isFinite() || !stepY.isFinite() || abs(stepX) > 6 || abs(stepY) > 6) { valid = false; break }
                qx += stepX; qy += stepY
                if (stepX * stepX + stepY * stepY < .0004f) break
            }
            if (!valid || !patchValid(b, qx, qy)) continue
            if (level == 0) {
                val lighting = readTarget() ?: return null
                var error = 0f; index = 0
                while (index < 49) {
                    error += abs(values[index] - (target[index] * lighting.first + lighting.second)); index++
                }
                if (error / 49 > 18) return null
                tracked = true
            }
            dx = (qx - x) * factor; dy = (qy - y) * factor
        }
        return if (tracked) PixelPoint(point.x + dx, point.y + dy) else null
    }
    private data class Flow(val from: PixelPoint, val to: PixelPoint)
    private fun flowMatches(before: ScreenGrayFrame, after: ScreenGrayFrame, prediction: ScreenTransform): List<Flow> {
        val points = features(before, radius = 5, limit = 80, step = 2)
        if (points.size < 8) return emptyList()
        val a = pyramid(before); val b = pyramid(after)
        return points.mapNotNull { feature ->
            val p = PixelPoint(feature.x.toFloat(), feature.y.toFloat())
            val q = track(a, b, p, prediction.point(p)) ?: return@mapNotNull null
            val returned = track(b, a, q, p) ?: return@mapNotNull null
            if (hypot(p.x - returned.x, p.y - returned.y) > .7f) null else Flow(p, q)
        }
    }
    private fun fit(inliers: List<Flow>): ScreenTransform? {
        val ax = inliers.map { it.from.x }.average(); val ay = inliers.map { it.from.y }.average()
        val bx = inliers.map { it.to.x }.average(); val by = inliers.map { it.to.y }.average()
        var numerator = 0.0; var denominator = 0.0
        for (match in inliers) {
            val dx = match.from.x - ax; val dy = match.from.y - ay
            numerator += dx * (match.to.x - bx) + dy * (match.to.y - by); denominator += dx * dx + dy * dy
        }
        if (denominator < 100) return null
        val scale = (numerator / denominator).toFloat()
        return if (scale in .4f..2.5f) ScreenTransform(scale, (bx - ax * scale).toFloat(), (by - ay * scale).toFloat()) else null
    }
    private fun residual(match: Flow, transform: ScreenTransform): Float {
        val p = transform.point(match.from)
        return hypot(p.x - match.to.x, p.y - match.to.y)
    }
    private fun fixedAreas(matches: List<Flow>, width: Int, height: Int, moving: ScreenTransform): List<PixelRect> {
        val cells = matches.groupBy { (it.from.y.toInt() * 8 / height).coerceIn(0, 7) * 8 + (it.from.x.toInt() * 8 / width).coerceIn(0, 7) }
        return cells.mapNotNull { (cell, points) ->
            val fixed = points.count { residual(it, ScreenTransform()) < .65f && residual(it, moving) > 1.2f }
            val mobile = points.count { residual(it, moving) < .9f }
            if (fixed == 0 || fixed <= mobile) null else PixelRect(cell % 8 * width / 8f, cell / 8 * height / 8f,
                (cell % 8 + 1) * width / 8f, (cell / 8 + 1) * height / 8f)
        }
    }
    /** Fit a content model separately from identity. A dense fixed toolbar must
     * not win just because it owns more corners than the moving artwork. */
    private fun fitModels(matches: List<Flow>, before: ScreenGrayFrame, after: ScreenGrayFrame,
        tolerance: Float, minimumFraction: Float): ScreenMotionEstimate? {
        if (matches.size < 8) return null
        val mobile = matches.filter { residual(it, ScreenTransform()) > .65f }
        fun support(transform: ScreenTransform, pool: List<Flow>) = pool.filter { residual(it, transform) < tolerance }
            .sortedBy { residual(it, transform) }.distinctBy { it.from }
        fun grid(points: List<Flow>) = points.map { (it.from.y.toInt() * 8 / before.height).coerceIn(0, 7) * 8 +
            (it.from.x.toInt() * 8 / before.width).coerceIn(0, 7) }.toSet()
        fun confirm(transform: ScreenTransform, points: List<Flow>): Boolean {
            val samples = ScreenAppearanceSamples()
            for (point in points) for (dy in -2..2 step 2) for (dx in -2..2 step 2) {
                val x = point.from.x + dx; val y = point.from.y + dy
                val target = transform.point(PixelPoint(x, y))
                if (!patchValid(before, x, y, 1) || !patchValid(after, target.x, target.y, 1)) continue
                samples.add(sample(before, x, y), sample(after, target.x, target.y))
            }
            return samples.count >= 36 && samples.correlation()?.let { ScreenAppearanceSamples.error(it) < .14f } == true
        }
        fun solve(pool: List<Flow>, moving: Boolean): ScreenTransform? {
            val pointCount = pool.map { it.from }.distinct().size
            if (pointCount < 8) return null
            var best = emptyList<Flow>(); var score = -1f
            fun consider(transform: ScreenTransform) {
                val agree = support(transform, pool)
                val cells = grid(agree)
                if (moving && (cells.size < 4 || cells.map { it / 8 }.distinct().size < 2 || cells.map { it % 8 }.distinct().size < 2)) return
                val value = cells.size * 3f + agree.size
                if (value > score) { score = value; best = agree }
            }
            consider(ScreenTransform())
            pool.forEach { consider(ScreenTransform(1f, it.to.x - it.from.x, it.to.y - it.from.y)) }
            val random = java.util.Random(91)
            repeat(240) {
                val p = pool[random.nextInt(pool.size)]; val q = pool[random.nextInt(pool.size)]
                val dx = p.from.x - q.from.x; val dy = p.from.y - q.from.y; val length = dx * dx + dy * dy
                if (length > 100) {
                    val s = (dx * (p.to.x - q.to.x) + dy * (p.to.y - q.to.y)) / length
                    if (s in .4f..2.5f) consider(ScreenTransform(s, p.to.x - p.from.x * s, p.to.y - p.from.y * s))
                }
            }
            if (best.size < maxOf(8, (pointCount * minimumFraction).roundToInt())) return null
            var pose = fit(best) ?: return null
            repeat(2) { pose = fit(support(pose, pool)) ?: return null }
            return pose.takeIf { confirm(it, support(it, pool)) }
        }
        solve(mobile, moving = true)?.let { return ScreenMotionEstimate(it, fixedAreas(matches, before.width, before.height, it)) }
        val pose = solve(matches, moving = false) ?: return null
        // Identity is a valid content result only if there is no significant
        // independently moving population; otherwise try another estimator.
        if (residual(Flow(PixelPoint(before.width / 2f, before.height / 2f),
                pose.point(PixelPoint(before.width / 2f, before.height / 2f))), ScreenTransform()) < .65f &&
            (mobile.size >= 8 || (normalizedPredictionError(before, after, pose) ?: 100f) > 2.5f)) return null
        return ScreenMotionEstimate(pose, fixedAreas(matches, before.width, before.height, pose))
    }

    /** Cheap confirmation of an event prediction; null means there is too little visible art. */
    fun predictionError(before: ScreenGrayFrame, after: ScreenGrayFrame, prediction: ScreenTransform): Float? {
        if (before.width != after.width || before.height != after.height) return null
        return normalizedPredictionError(before.trackingContrast() ?: return null, after.trackingContrast() ?: return null, prediction)
    }
    private fun normalizedPredictionError(before: ScreenGrayFrame, after: ScreenGrayFrame, prediction: ScreenTransform): Float? {
        val samples = ScreenAppearanceSamples()
        for (y in 2 until before.height - 2 step 6) for (x in 2 until before.width - 2 step 6) {
            val i = y * before.width + x
            if (before.valid != null && before.valid[i] == 0.toByte()) continue
            // Flat pixels cannot confirm either displacement or zoom.
            if (abs(before.at(x + 1, y) - before.at(x - 1, y)) + abs(before.at(x, y + 1) - before.at(x, y - 1)) < 12) continue
            val p = prediction.point(PixelPoint(x.toFloat(), y.toFloat()))
            if (p.x < 1 || p.y < 1 || p.x >= after.width - 2 || p.y >= after.height - 2) continue
            val j = p.y.toInt() * after.width + p.x.toInt()
            if (after.valid != null && (after.valid[i] == 0.toByte() || after.valid[j] == 0.toByte() || after.valid[j + 1] == 0.toByte() ||
                after.valid[j + after.width] == 0.toByte() || after.valid[j + after.width + 1] == 0.toByte())) continue
            samples.add(before.at(x, y).toFloat(), sample(after, p.x, p.y))
        }
        return samples.correlation()?.let { ScreenAppearanceSamples.error(it) * 100 }
    }
    /** Returns null on scene cuts or ambiguous/repeated texture, instead of guessing a bubble position. */
    fun estimate(before: ScreenGrayFrame, after: ScreenGrayFrame): ScreenTransform? = estimateModels(before, after)?.content

    fun estimateModels(before: ScreenGrayFrame, after: ScreenGrayFrame, prediction: ScreenTransform = ScreenTransform()): ScreenMotionEstimate? {
        if (before.width != after.width || before.height != after.height) return null
        // A newly drawn/moved overlay may be present in only one mask snapshot.
        // Invalidate it in BOTH images so its stationary glyphs cannot win over
        // the moving artwork. Difference alone already uses this intersection.
        val valid = if (before.valid == null && after.valid == null) null else ByteArray(before.pixels.size) { i ->
            if ((before.valid == null || before.valid[i] != 0.toByte()) && (after.valid == null || after.valid[i] != 0.toByte())) 1 else 0
        }
        val first = before.copy(valid = valid).trackingContrast() ?: return null
        val secondFrame = after.copy(valid = valid).trackingContrast() ?: return null
        // A moving content prediction and identity are both needed: a toolbar
        // corner must not be initialized at the artwork's predicted position.
        val predicted = flowMatches(first, secondFrame, prediction)
        val flows = if (prediction == ScreenTransform()) predicted else
            (predicted + flowMatches(first, secondFrame, ScreenTransform())).distinctBy { it.from to it.to }
        fitModels(flows, first, secondFrame, .9f, .55f)?.let { return it }
        fitModels(descriptorMatches(first, secondFrame), first, secondFrame, 2.5f, .4f)?.let { return it }
        // Registration checks source and destination masks at their own
        // transformed coordinates, unlike descriptor/flow corner selection.
        // Mask stationary corner cells before the expensive fallback too.
        val stationary = fixedAreas(flows, before.width, before.height, ScreenTransform(x = before.width.toFloat()))
        fun withoutFixed(frame: ScreenGrayFrame): ScreenGrayFrame {
            if (stationary.isEmpty()) return frame
            val mask = frame.valid?.copyOf() ?: ByteArray(frame.pixels.size) { 1 }
            for (r in stationary) for (y in r.top.toInt() until r.bottom.toInt())
                for (x in r.left.toInt() until r.right.toInt()) mask[y * frame.width + x] = 0
            return frame.copy(valid = mask)
        }
        return estimateAlignment(withoutFixed(first), withoutFixed(secondFrame))?.let { ScreenMotionEstimate(it, stationary) }
    }

    private data class AlignmentSamples(val frame: ScreenGrayFrame, val indices: IntArray, val centerX: Float, val centerY: Float)
    private fun alignmentSamples(frame: ScreenGrayFrame): AlignmentSamples {
        val points = ArrayList<Int>()
        var sx = 0f; var sy = 0f
        for (y in 2 until frame.height - 2 step 2) for (x in 2 until frame.width - 2 step 2) {
            val i = y * frame.width + x
            if (frame.valid != null && frame.valid[i] == 0.toByte()) continue
            if (abs(frame.at(x + 1, y) - frame.at(x - 1, y)) + abs(frame.at(x, y + 1) - frame.at(x, y - 1)) < 10) continue
            points += i; sx += x; sy += y
        }
        return AlignmentSamples(frame, points.toIntArray(), sx / maxOf(1, points.size), sy / maxOf(1, points.size))
    }
    private fun alignmentCost(source: AlignmentSamples, after: ScreenGrayFrame, transform: ScreenTransform): Float {
        val samples = ScreenAppearanceSamples()
        for (i in source.indices) {
            val x = i % source.frame.width; val y = i / source.frame.width
            val qx = x * transform.scale + transform.x; val qy = y * transform.scale + transform.y
            if (qx < 1 || qy < 1 || qx >= after.width - 2 || qy >= after.height - 2) continue
            val j = qy.toInt() * after.width + qx.toInt()
            if (after.valid != null && (after.valid[j] == 0.toByte() || after.valid[j + 1] == 0.toByte() ||
                after.valid[j + after.width] == 0.toByte() || after.valid[j + after.width + 1] == 0.toByte())) continue
            samples.add(source.frame.at(x, y).toFloat(), sample(after, qx, qy))
        }
        if (samples.count < maxOf(12, source.indices.size / 3)) return Float.POSITIVE_INFINITY
        val correlation = samples.correlation() ?: return Float.POSITIVE_INFINITY
        // This is a correlation loss, not an 8-bit pixel error. Thin strokes
        // change their antialiasing footprint during downsampling/zoom; the old
        // MAE cutoff would reject even the correctly registered clean texture.
        return (1 - correlation) + (1 - samples.count.toFloat() / source.indices.size) * .04f
    }
    /** Rare large zooms need registration, not more OCR. Search a tiny pyramid
     * first, then refine only three parameters with cached visible-art samples. */
    private fun estimateAlignment(before: ScreenGrayFrame, after: ScreenGrayFrame): ScreenTransform? {
        val (transform, cost) = alignmentFit(before, after) ?: return null
        // Registration already validates hundreds of visible-art samples.
        // A second sparse test can be dominated by a handful of mask edges.
        return transform.takeIf { cost < .28f }
    }
    private fun alignmentFit(before: ScreenGrayFrame, after: ScreenGrayFrame): Pair<ScreenTransform, Float>? {
        val a = pyramid(before); val b = pyramid(after)
        val samples = a.map(::alignmentSamples)
        val coarse = samples.last()
        if (coarse.indices.size < 12) return null
        val candidates = ArrayList<Pair<Float, ScreenTransform>>()
        for (k in -8..8) {
            val scale = 2f.pow(k / 8f)
            for (px in 0..2) for (py in 0..2) {
                val transform = ScreenTransform(scale, (1 - scale) * px * coarse.frame.width / 2, (1 - scale) * py * coarse.frame.height / 2)
                val cost = alignmentCost(coarse, b.last(), transform)
                if (cost.isFinite()) candidates += cost to transform
            }
        }
        var best: ScreenTransform? = null; var bestCost = Float.POSITIVE_INFINITY
        for ((_, seed) in candidates.sortedBy { it.first }.take(3)) {
            var transform = seed
            for (level in a.lastIndex downTo 0) {
                if (level != a.lastIndex) transform = ScreenTransform(transform.scale, transform.x * 2, transform.y * 2)
                val points = samples[level]
                var cost = alignmentCost(points, b[level], transform)
                var pixelStep = 1f; var scaleStep = if (level == a.lastIndex) .08f else .02f
                for (iteration in 0 until 28) {
                    var improved = false
                    for (parameter in 0..2) {
                        var chosen = transform; var chosenCost = cost
                        for (direction in -1..1 step 2) {
                            val step = direction * if (parameter == 2) scaleStep else pixelStep
                            val candidate = when (parameter) {
                                0 -> transform.copy(x = transform.x + step)
                                1 -> transform.copy(y = transform.y + step)
                                else -> ScreenTransform(transform.scale + step, transform.x - points.centerX * step, transform.y - points.centerY * step)
                            }
                            if (candidate.scale !in .4f..2.5f) continue
                            val candidateCost = alignmentCost(points, b[level], candidate)
                            if (candidateCost < chosenCost) { chosen = candidate; chosenCost = candidateCost }
                        }
                        if (chosenCost < cost) { transform = chosen; cost = chosenCost; improved = true }
                    }
                    if (!improved) { pixelStep *= .5f; scaleStep *= .5f }
                    if (pixelStep < .06f && scaleStep < .001f) break
                }
                if (level == 0 && cost < bestCost) { best = transform; bestCost = cost }
            }
        }
        val transform = best ?: return null
        return transform to bestCost
    }

    private fun descriptorMatches(first: ScreenGrayFrame, secondFrame: ScreenGrayFrame): List<Flow> {
        var a = features(first); var b = features(secondFrame)
        val radius = if (minOf(a.size, b.size) >= 24) 16 else 8
        if (radius == 8) { a = features(first, radius = 8); b = features(secondFrame, radius = 8) }
        if (min(a.size, b.size) < 8) return emptyList()
        val scales = listOf(.5f, .65f, .8f, 1f, 1.25f, 1.5f, 2f)
        val descriptors = b.map { point -> scales.map { descriptor(secondFrame, point, it, radius) } }
        val matches = a.mapNotNull { point ->
            val key = descriptor(first, point, 1f, radius)
            var best = 65; var second = 65; var index = -1
            b.indices.forEach { i ->
                val distance = descriptors[i].minOf { java.lang.Long.bitCount(key xor it) }
                if (distance < best) { second = best; best = distance; index = i } else second = minOf(second, distance)
            }
            if (best <= 13 && best < second * .78f) Match(point, b[index]) else null
        }.distinctBy { it.to.x to it.to.y }
        return matches.map { Flow(PixelPoint(it.from.x.toFloat(), it.from.y.toFloat()), PixelPoint(it.to.x.toFloat(), it.to.y.toFloat())) }
    }
}
