package com.lmreader.ui.video

import com.lmreader.ui.queue.TranslationCacheBudget
import com.lmreader.ui.workflow.WorkflowPageSegmentation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One admitted input owns its pixels and memory reservation until publication or failure. */
internal class VideoFrameInput(
    val frame: DecodedVideoFrame,
    val lease: TranslationCacheBudget.Lease,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<VideoFrameInput>
    private val closed = AtomicBoolean()
    val sourceBytes = frame.bitmap.allocationByteCount.toLong()
    suspend fun close() = withContext(NonCancellable) {
        if (closed.compareAndSet(false, true)) {
            try { frame.close() } finally { lease.release() }
        }
    }
}

internal fun videoSegmentationBytes(detection: WorkflowPageSegmentation?): Long = detection?.let {
    it.result.regions.sumOf { region -> 256L + region.contour.size * 8L + region.id.length * 2L } +
        it.result.textLines.size * 48L
} ?: 0L
