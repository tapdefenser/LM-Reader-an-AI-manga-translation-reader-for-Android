package com.lmreader.ui.video.player

/** Actual presentation timestamps from Media3, captured synchronously with an interaction. */
object VideoFrameStamp {
    private data class Stamp(val videoId: String, val ptsUs: Long)
    @Volatile private var last: Stamp? = null
    fun update(videoId: String, ptsUs: Long) { last = Stamp(videoId, ptsUs) }
    fun clear() { last = null }
    fun capture(videoId: String, positionMs: Long): Long? = last?.takeIf {
        it.videoId == videoId && kotlin.math.abs(it.ptsUs / 1000 - positionMs) < 1000
    }?.ptsUs
}
