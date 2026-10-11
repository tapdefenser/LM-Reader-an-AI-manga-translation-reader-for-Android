package com.lmreader.core.model

import kotlin.math.*

/** Tracking-only contrast stretch. A uniform translucent color is a positive
 * grayscale gain plus an offset; the valid intensity endpoints follow both.
 * Preserve even rare dark strokes instead of clipping them at percentiles.
 * Masked UI never contributes. Recognition still receives the original. */
internal fun ScreenGrayFrame.trackingContrast(): ScreenGrayFrame? {
    var count = 0; var low = 255; var high = 0
    for (i in pixels.indices) if (valid == null || valid[i] != 0.toByte()) {
        val value = pixels[i].toInt() and 255
        low = minOf(low, value); high = maxOf(high, value); count++
    }
    if (count < 12) return null
    // Do not amplify a flat/near-opaque frame's quantization noise into corners.
    if (high - low < 12) return null
    val lut = ByteArray(256) { i -> (16f + (i - low) * 224f / (high - low)).roundToInt().coerceIn(0, 255).toByte() }
    return copy(pixels = ByteArray(pixels.size) { lut[pixels[it].toInt() and 255] })
}

/** Zero-mean, unit-contrast comparison. Positive gain/offset changes cannot
 * turn a matching object into a mismatch, but different texture still can. */
internal class ScreenAppearanceSamples {
    var count = 0; private set
    private var a = 0.0; private var b = 0.0
    private var aa = 0.0; private var bb = 0.0; private var ab = 0.0
    fun add(first: Float, second: Float) {
        count++; a += first; b += second
        aa += first.toDouble() * first; bb += second.toDouble() * second; ab += first.toDouble() * second
    }
    fun correlation(): Float? {
        if (count < 12) return null
        val va = aa - a * a / count; val vb = bb - b * b / count
        if (minOf(va, vb) < count * 4.0) return null
        return ((ab - a * b / count) / sqrt(va * vb)).toFloat().coerceIn(-1f, 1f)
    }
    companion object {
        // RMS distance between centered, standardized patches, scaled to the
        // existing small-error confidence range (unrelated texture is ~0.28).
        fun error(correlation: Float) = sqrt(maxOf(0f, 2 * (1 - correlation))) / 5
    }
}
