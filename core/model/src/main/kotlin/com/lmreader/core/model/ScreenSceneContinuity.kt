package com.lmreader.core.model

/** A concentrated foreign panel surrounded by retained artwork is occlusion,
 * not a page cut. Compare texture per grid cell: a whole-frame average would
 * let the new panel dominate. Toolbar-only agreement is insufficient. */
fun screenVisualOcclusions(before: ScreenGrayFrame, after: ScreenGrayFrame,
    pose: ScreenTransform = ScreenTransform()): List<PixelRect> {
    if (before.width != after.width || before.height != after.height) return emptyList()
    val retained = ArrayList<Int>(); val changed = ArrayList<PixelRect>(); val changedCells = ArrayList<Int>(); var textured = 0
    for (row in 0 until 8) for (column in 0 until 8) {
        val l = column * before.width / 8; val r = (column + 1) * before.width / 8
        val t = row * before.height / 8; val b = (row + 1) * before.height / 8
        val samples = ScreenAppearanceSamples()
        for (y in t + 1 until b - 1 step 2) for (x in l + 1 until r - 1 step 2) {
            val i = y * before.width + x
            if (before.valid?.get(i) == 0.toByte()) continue
            val point = pose.point(PixelPoint(x.toFloat(), y.toFloat()))
            if (point.x < 0 || point.y < 0 || point.x >= after.width - 1 || point.y >= after.height - 1) continue
            val xx = point.x.toInt(); val yy = point.y.toInt()
            val j = yy * after.width + xx
            if (after.valid?.let { it[j] == 0.toByte() || it[j + 1] == 0.toByte() ||
                    it[j + after.width] == 0.toByte() || it[j + after.width + 1] == 0.toByte() } == true) continue
            val u = point.x - xx; val v = point.y - yy
            val current = (after.at(xx, yy) * (1 - u) + after.at(xx + 1, yy) * u) * (1 - v) +
                (after.at(xx, yy + 1) * (1 - u) + after.at(xx + 1, yy + 1) * u) * v
            samples.add(before.at(x, y).toFloat(), current)
        }
        if (!samples.textured) continue
        textured++
        val correlation = samples.correlation()
        if (correlation != null && correlation >= .85f) retained += row * 8 + column
        else if (correlation == null || correlation < .45f) {
            changed += pose.bounds(PixelRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()))
            changedCells += row * 8 + column
        }
    }
    if (retained.size < maxOf(8, textured / 4) || retained.map { it / 8 }.distinct().size < 3 ||
        retained.map { it % 8 }.distinct().size < 3 || changed.size < 6 || changed.size > 48) return emptyList()
    // Repeated white bubbles can survive a page cut while all surrounding art
    // changes. A local panel has a local footprint, rather than spanning the
    // entire frame around several retained islands.
    val columns = changedCells.map { it % 8 }; val rows = changedCells.map { it / 8 }
    val footprint = (columns.max() - columns.min() + 1) * (rows.max() - rows.min() + 1)
    if (footprint > 56) return emptyList()
    // Partially covered cells can retain enough old texture to pass correlation.
    // Mask the panel's continuous footprint, with a small boundary allowance,
    // rather than only its most visibly changed cells.
    val marginX = before.width / 16f; val marginY = before.height / 16f
    return listOf(pose.bounds(PixelRect(
        (columns.min() * before.width / 8f - marginX).coerceAtLeast(0f),
        (rows.min() * before.height / 8f - marginY).coerceAtLeast(0f),
        ((columns.max() + 1) * before.width / 8f + marginX).coerceAtMost(before.width.toFloat()),
        ((rows.max() + 1) * before.height / 8f + marginY).coerceAtMost(before.height.toFloat()))))
}
