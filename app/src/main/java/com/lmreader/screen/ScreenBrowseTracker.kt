package com.lmreader.screen

import android.os.SystemClock
import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.pow

internal data class BrowseObservation(val capturePose: ScreenTransform, val displayPose: ScreenTransform,
    val moved: Boolean, val sceneChanged: Boolean = false, val reliable: Boolean = true, val newest: Boolean = true,
    val fixedAreas: List<PixelRect> = emptyList(), val sources: List<ScreenSourceRegion> = emptyList())

/** One estimator at a time. Recognition registers its exact frame through the same worker. */
internal class ScreenBrowseTracker(private val report: (String) -> Unit) {
    private data class Reference(val frame: CapturedMotion, val pose: ScreenTransform, val epoch: Long)
    private val mutex = Mutex()
    private val smoothing = ScreenMotionSmoother()
    private var reference: Reference? = null
    private var occlusionAnchor: Reference? = null
    var visualOcclusions: List<PixelRect> = emptyList()
        private set
    private var revision = 0L
    private var failedSince = 0L
    private var lastDelta = ScreenTransform()
    private var deltaMillis = 33L
    private val poses = linkedMapOf<Long, ScreenTransform>()
    var rawPose = ScreenTransform()
        private set
    var displayPose = ScreenTransform()
        private set
    val latestFrame get() = reference?.frame
    fun pauseForOcclusion() { revision++; failedSince = 0; lastDelta = ScreenTransform() }
    fun reset() { revision++; reference = null; occlusionAnchor = null; visualOcclusions = emptyList(); failedSince = 0; lastDelta = ScreenTransform(); poses.clear(); rawPose = ScreenTransform(); displayPose = rawPose; smoothing.reset() }
    suspend fun register(frame: CapturedMotion, epoch: Long): BrowseObservation = mutex.withLock {
        val previous = reference
        val stamp = revision
        val center = PixelPoint((frame.bounds.left + frame.bounds.right) / 2, (frame.bounds.top + frame.bounds.bottom) / 2)
        if (previous == null || previous.epoch != epoch || previous.frame.bounds != frame.bounds) {
            val changed = previous != null && previous.epoch == epoch
            smoothing.reset(); rawPose = ScreenTransform(); displayPose = rawPose; failedSince = 0; lastDelta = ScreenTransform(); poses.clear()
            reference = Reference(frame, rawPose, epoch); poses[frame.sequence] = rawPose
            occlusionAnchor = null; visualOcclusions = emptyList()
            return@withLock BrowseObservation(rawPose, displayPose, false, changed)
        }
        poses[frame.sequence]?.let {
            // A static app may stop producing frames altogether. Finish the UI
            // smoothing against the last measured pose without rerunning matching.
            if (frame.sequence == previous.frame.sequence) {
                displayPose = smoothing.update(rawPose, center)
                return@withLock BrowseObservation(it, displayPose, false)
            }
            return@withLock BrowseObservation(it, displayPose, false, newest = false)
        }
        val newest = frame.sequence > previous.frame.sequence
        val started = System.nanoTime()
        val elapsed = (frame.capturedAt - previous.frame.capturedAt).coerceAtLeast(1)
        val steps = (elapsed.toFloat() / deltaMillis).coerceIn(1f, 8f)
        val scale = lastDelta.scale.pow(steps)
        val multiple = if (abs(lastDelta.scale - 1) < .00001f) steps else (scale - 1) / (lastDelta.scale - 1)
        val prediction = ScreenTransform(scale, lastDelta.x * multiple, lastDelta.y * multiple)
        val anchor = occlusionAnchor ?: previous
        val screenPose = anchor.pose.inverse().then(previous.pose)
        val sx = frame.bounds.width / frame.gray.width; val sy = frame.bounds.height / frame.gray.height
        val pixelsPose = ScreenTransform(screenPose.scale,
            (screenPose.x + frame.bounds.left * (screenPose.scale - 1)) / sx,
            (screenPose.y + frame.bounds.top * (screenPose.scale - 1)) / sy)
        var visualPixels = withContext(Dispatchers.Default) { screenVisualOcclusions(anchor.frame.gray, frame.gray, pixelsPose) }
        var unmasked: ScreenMotionEstimate? = null
        if (visualPixels.isNotEmpty()) {
            // Static controls around a scrolling body also retain texture.
            // Verify content motion before declaring the body an opaque panel.
            unmasked = withContext(Dispatchers.Default) { ScreenMotionTracker.estimateModels(previous.frame.gray, frame.gray, prediction) }
            unmasked?.content?.takeIf { abs(it.scale - 1) >= .005f || abs(it.x) >= .75f || abs(it.y) >= .75f }?.let { motion ->
                visualPixels = withContext(Dispatchers.Default) { screenVisualOcclusions(anchor.frame.gray, frame.gray, pixelsPose.then(motion)) }
            }
        }
        val visual = visualPixels.map { r -> PixelRect(frame.bounds.left + r.left * sx, frame.bounds.top + r.top * sy,
                frame.bounds.left + r.right * sx, frame.bounds.top + r.bottom * sy) }
        if (revision != stamp) return@withLock BrowseObservation(rawPose, displayPose, false, reliable = false, newest = false)
        if (newest) {
            if (visualOcclusions != visual) report("OCCLUSION $visual sequence=${frame.sequence}")
            if (visual.isNotEmpty() && occlusionAnchor == null) occlusionAnchor = previous
            if (visual.isEmpty()) occlusionAnchor = null
            visualOcclusions = visual
        }
        val matchingFrame = frame.excluding(visual)
        val difference = previous.frame.gray.difference(matchingFrame.gray)
        val estimate = when {
            visual.isEmpty() && unmasked != null -> unmasked
            difference < .15f -> ScreenMotionEstimate(ScreenTransform())
            else -> withContext(Dispatchers.Default) { ScreenMotionTracker.estimateModels(previous.frame.gray, matchingFrame.gray, prediction) }
        }
        report("MATCH ${(System.nanoTime() - started) / 1_000_000}ms age=${SystemClock.uptimeMillis() - frame.capturedAt}ms diff=$difference sequence=${frame.sequence} pose=$estimate")
        if (revision != stamp) return@withLock BrowseObservation(rawPose, displayPose, false, reliable = false, newest = false)
        if (estimate == null) {
            if (!newest) return@withLock BrowseObservation(rawPose, displayPose, false, reliable = false, newest = false)
            if (failedSince == 0L) failedSince = SystemClock.uptimeMillis()
            val changed = difference >= 4f && SystemClock.uptimeMillis() - failedSince >= 500 &&
                ScreenAccessibilityService.instance?.contentOcclusions.isNullOrEmpty() && visual.isEmpty()
            return@withLock BrowseObservation(rawPose, displayPose, false, sceneChanged = changed, reliable = false)
        }
        val area = previous.frame.bounds
        val measured = estimate.content
        val delta = ScreenTransform(measured.scale, measured.x * sx + area.left * (1 - measured.scale), measured.y * sy + area.top * (1 - measured.scale))
        val pose = previous.pose.then(delta)
        if (!newest) return@withLock BrowseObservation(pose, displayPose, false, newest = false)
        failedSince = 0
        lastDelta = measured; deltaMillis = elapsed
        rawPose = pose; displayPose = smoothing.update(rawPose, center)
        reference = Reference(matchingFrame, rawPose, epoch); poses[frame.sequence] = rawPose
        if (poses.size > 32) poses.remove(poses.keys.first())
        val moved = abs(delta.scale - 1) >= .0005f || abs(delta.x) >= .35f || abs(delta.y) >= .35f
        val fixed = estimate.fixedAreas.map { r -> PixelRect(area.left + r.left * sx, area.top + r.top * sy,
            area.left + r.right * sx, area.top + r.bottom * sy) }
        BrowseObservation(rawPose, displayPose, moved, fixedAreas = fixed)
    }
}
