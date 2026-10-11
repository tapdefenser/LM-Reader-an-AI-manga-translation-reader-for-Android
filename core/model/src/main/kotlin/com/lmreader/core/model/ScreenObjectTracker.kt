package com.lmreader.core.model

import kotlin.math.*

enum class ScreenObjectAttachment { CONTENT, FIXED }

/** Small immutable samples of the original art, including a little surrounding
 * texture. No translated pixels or full-size screenshots are retained per object. */
class ScreenObjectTemplate private constructor(private val samples: List<Sample>, private val support: PixelRect) {
    private data class Sample(val u: Float, val v: Float, val value: Float)
    data class Similarity(val error: Float, val correlation: Float, val visible: Float) {
        val matches get() = visible >= .65f && error < .14f && correlation > .45f
        val rejects get() = visible >= .65f && error > .22f && correlation < .25f
        val cost get() = error + (1 - correlation) * .12f
    }
    fun compare(frame: ScreenGrayFrame, frameBounds: PixelRect, objectBounds: PixelRect): Similarity? {
        val patch = PixelRect(objectBounds.left + support.left * objectBounds.width,
            objectBounds.top + support.top * objectBounds.height,
            objectBounds.left + support.right * objectBounds.width,
            objectBounds.top + support.bottom * objectBounds.height)
        var count = 0; var error = 0f; var a = 0.0; var b = 0.0; var aa = 0.0; var bb = 0.0; var ab = 0.0
        for (sample in samples) {
            val x = (patch.left + sample.u * patch.width - frameBounds.left) * frame.width / frameBounds.width
            val y = (patch.top + sample.v * patch.height - frameBounds.top) * frame.height / frameBounds.height
            val value = pixel(frame, x, y) ?: continue
            count++; error += abs(value - sample.value)
            a += sample.value; b += value; aa += sample.value * sample.value; bb += value * value; ab += sample.value * value
        }
        if (count < 12) return null
        val variance = sqrt(maxOf(0.0, aa - a * a / count) * maxOf(0.0, bb - b * b / count))
        if (variance < 100) return null
        return Similarity(error / (count * 255), ((ab - a * b / count) / variance).toFloat().coerceIn(-1f, 1f), count.toFloat() / samples.size)
    }
    companion object {
        private fun pixel(frame: ScreenGrayFrame, x: Float, y: Float): Float? {
            if (x < 1 || y < 1 || x >= frame.width - 2 || y >= frame.height - 2) return null
            val l = floor(x).toInt(); val t = floor(y).toInt(); val i = t * frame.width + l
            if (frame.valid?.let { it[i] == 0.toByte() || it[i + 1] == 0.toByte() ||
                it[i + frame.width] == 0.toByte() || it[i + frame.width + 1] == 0.toByte() } == true) return null
            val u = x - l; val v = y - t
            return (frame.at(l, t) * (1 - u) + frame.at(l + 1, t) * u) * (1 - v) +
                (frame.at(l, t + 1) * (1 - u) + frame.at(l + 1, t + 1) * u) * v
        }
        fun capture(frame: ScreenGrayFrame, frameBounds: PixelRect, objectBounds: PixelRect): ScreenObjectTemplate? {
            if (objectBounds.width <= 0 || objectBounds.height <= 0) return null
            val mx = minOf(objectBounds.width * .15f, frameBounds.width * 3 / frame.width)
            val my = minOf(objectBounds.height * .15f, frameBounds.height * 3 / frame.height)
            val patch = PixelRect(objectBounds.left - mx, objectBounds.top - my, objectBounds.right + mx, objectBounds.bottom + my)
            val samples = ArrayList<Sample>()
            // Equal normalized positions compare the same source strokes at every zoom.
            for (y in 0 until 16) for (x in 0 until 16) {
                val u = (x + .5f) / 16; val v = (y + .5f) / 16
                val px = (patch.left + patch.width * u - frameBounds.left) * frame.width / frameBounds.width
                val py = (patch.top + patch.height * v - frameBounds.top) * frame.height / frameBounds.height
                val value = pixel(frame, px, py) ?: continue
                val gx = abs((pixel(frame, px + 1, py) ?: value) - (pixel(frame, px - 1, py) ?: value))
                val gy = abs((pixel(frame, px, py + 1) ?: value) - (pixel(frame, px, py - 1) ?: value))
                if (gx + gy >= 12) samples += Sample(u, v, value)
            }
            if (samples.size < 12) return null
            return ScreenObjectTemplate(samples, PixelRect(-mx / objectBounds.width, -my / objectBounds.height,
                1 + mx / objectBounds.width, 1 + my / objectBounds.height))
        }
    }
}

data class ScreenMotionObject(val id: String, val revision: Long, val sourceBounds: PixelRect,
    val sourceToScreen: ScreenTransform, val template: ScreenObjectTemplate?,
    val attachment: ScreenObjectAttachment = ScreenObjectAttachment.CONTENT, val failures: Int = 0,
    val sampledSequence: Long = Long.MIN_VALUE)
data class ScreenObjectDecision(val id: String, val revision: Long, val sourceToScreen: ScreenTransform,
    val attachment: ScreenObjectAttachment, val failures: Int, val sequence: Long, val remove: Boolean = false)

object ScreenObjectTracker {
    fun evaluate(objects: List<ScreenMotionObject>, frame: ScreenGrayFrame, bounds: PixelRect,
        delta: ScreenTransform, sequence: Long): List<ScreenObjectDecision> = objects.map { obj ->
        val moving = obj.sourceToScreen.then(delta)
        val fixed = obj.sourceToScreen
        fun decision(pose: ScreenTransform, attachment: ScreenObjectAttachment, failures: Int = 0, remove: Boolean = false) =
            ScreenObjectDecision(obj.id, obj.revision, pose, attachment, failures, sequence, remove)
        if (obj.template == null || sequence <= obj.sampledSequence) {
            return@map decision(if (obj.attachment == ScreenObjectAttachment.FIXED) fixed else moving, obj.attachment, obj.failures)
        }
        val movingMatch = obj.template.compare(frame, bounds, moving.bounds(obj.sourceBounds))
        val fixedMatch = obj.template.compare(frame, bounds, fixed.bounds(obj.sourceBounds))
        if (movingMatch?.matches == true && fixedMatch?.matches == true &&
            abs(moving.scale / fixed.scale - 1) < .002f && abs(moving.x - fixed.x) < .35f && abs(moving.y - fixed.y) < .35f)
            return@map decision(fixed, obj.attachment)
        if (movingMatch?.matches == true && (fixedMatch?.matches != true || movingMatch.cost <= fixedMatch.cost + .03f))
            return@map decision(moving, ScreenObjectAttachment.CONTENT)
        if (fixedMatch?.matches == true) return@map decision(fixed, ScreenObjectAttachment.FIXED)
        if (movingMatch?.rejects == true && fixedMatch?.rejects == true) {
            val failures = obj.failures + 1
            return@map decision(fixed, obj.attachment, failures, remove = failures >= 2)
        }
        // Insufficient visibility/texture is not evidence that the object disappeared.
        decision(if (obj.attachment == ScreenObjectAttachment.FIXED) fixed else moving, obj.attachment)
    }
}
