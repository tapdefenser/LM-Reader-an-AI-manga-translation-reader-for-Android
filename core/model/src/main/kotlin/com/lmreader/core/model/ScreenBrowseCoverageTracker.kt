package com.lmreader.core.model

/** Evidence for searched pixels, including blocks where SEG found no text.
 * Bubble attachment and deletion remain independent of this coarse coverage. */
class ScreenBrowseCoverageTracker {
    private data class Patch(val contentBounds: PixelRect, val template: ScreenObjectTemplate)
    private data class Block(val contentBounds: PixelRect, val patches: List<Patch>,
        var sequence: Long, var failures: Int = 0)
    private val blocks = ArrayList<Block>()

    @Synchronized fun clear() = blocks.clear()

    @Synchronized fun add(crops: List<PixelRect>, frame: ScreenGrayFrame, frameBounds: PixelRect,
        atCapture: ScreenTransform, sequence: Long) {
        val normalized = frame.trackingContrast() ?: return
        val inverse = atCapture.inverse()
        for (crop in crops) {
            val patches = ArrayList<Patch>()
            for (y in 0 until 4) for (x in 0 until 4) {
                val bounds = PixelRect(crop.left + crop.width * x / 4, crop.top + crop.height * y / 4,
                    crop.left + crop.width * (x + 1) / 4, crop.top + crop.height * (y + 1) / 4)
                val template = ScreenObjectTemplate.captureContrast(normalized, frameBounds, bounds) ?: continue
                patches += Patch(inverse.bounds(bounds), template)
            }
            if (patches.size < 4) continue // a couple of text strokes cannot certify a whole page
            val content = inverse.bounds(crop)
            blocks.removeAll { it.contentBounds == content }
            blocks += Block(content, patches, sequence)
        }
    }

    /** Called at recognition cadence using an already acquired following frame.
     * Only stable, reliable, unoccluded frames may invalidate a whole block. */
    @Synchronized fun evaluate(frame: ScreenGrayFrame, frameBounds: PixelRect, pose: ScreenTransform,
        sequence: Long, reliable: Boolean): List<PixelRect> {
        if (!reliable) { blocks.forEach { it.failures = 0 }; return emptyList() }
        val removed = ArrayList<PixelRect>()
        for (block in blocks) {
            if (sequence <= block.sequence) continue
            block.sequence = sequence
            val visible = block.patches.filter { pose.bounds(it.contentBounds).intersects(frameBounds) }
            val compared = visible.mapNotNull { it.template.compare(frame, frameBounds, pose.bounds(it.contentBounds)) }
                .filter { it.matches || it.rejects }
            val rejects = compared.count { it.rejects }
            val rejected = compared.size >= 4 && compared.size >= visible.size * .7f && rejects >= compared.size * .7f
            block.failures = if (rejected) block.failures + 1 else 0
            if (block.failures >= 2) removed += block.contentBounds
        }
        if (removed.isNotEmpty()) blocks.removeAll { it.contentBounds in removed }
        return removed
    }

    @Synchronized fun retain(screenArea: PixelRect, pose: ScreenTransform) {
        val area = pose.inverse().bounds(screenArea)
        blocks.removeAll { !it.contentBounds.intersects(area) }
    }
}
