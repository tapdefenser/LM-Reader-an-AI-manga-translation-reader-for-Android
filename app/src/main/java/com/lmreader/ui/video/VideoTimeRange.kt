package com.lmreader.ui.video

/** Half-open partitions preserve material outside an explicitly cleared time range. */
data class VideoTimePart(val startMs: Long, val endMs: Long, val cleared: Boolean)

fun partitionVideoTime(startMs: Long, endMs: Long, clearStartMs: Long, clearEndMs: Long): List<VideoTimePart> {
    require(startMs >= 0 && endMs > startMs && clearStartMs >= 0 && clearEndMs > clearStartMs)
    val from = maxOf(startMs, clearStartMs)
    val to = minOf(endMs, clearEndMs)
    if(from >= to) return listOf(VideoTimePart(startMs, endMs, false))
    return buildList {
        if(startMs < from) add(VideoTimePart(startMs, from, false))
        add(VideoTimePart(from, to, true))
        if(to < endMs) add(VideoTimePart(to, endMs, false))
    }
}
