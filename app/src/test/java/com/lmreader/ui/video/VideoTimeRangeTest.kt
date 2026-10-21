package com.lmreader.ui.video

import org.junit.Assert.*
import org.junit.Test

class VideoTimeRangeTest {
    @Test fun middleClearPreservesBothOutsideIntervals() {
        assertEquals(listOf(VideoTimePart(10, 30, false), VideoTimePart(30, 60, true), VideoTimePart(60, 90, false)),
            partitionVideoTime(10, 90, 30, 60))
    }
    @Test fun clippingAndTouchingBoundariesDoNotDeleteAdjacentMaterial() {
        assertEquals(listOf(VideoTimePart(10, 20, true)), partitionVideoTime(10, 20, 0, 100))
        assertEquals(listOf(VideoTimePart(10, 20, false)), partitionVideoTime(10, 20, 20, 30))
        assertEquals(listOf(VideoTimePart(10, 15, true), VideoTimePart(15, 20, false)), partitionVideoTime(10, 20, 0, 15))
    }
    @Test fun partitionsExactlyCoverOriginalRangeWithoutOverlap() {
        for(a in 0L..12) for(b in a + 1..14) {
            val pieces = partitionVideoTime(3, 11, a, b)
            assertEquals(3L, pieces.first().startMs)
            assertEquals(11L, pieces.last().endMs)
            assertEquals(8L, pieces.sumOf { it.endMs - it.startMs })
            assertTrue(pieces.zipWithNext().all { (left, right) -> left.endMs == right.startMs })
            assertTrue(pieces.filter { it.cleared }.all { it.startMs >= a && it.endMs <= b })
        }
    }
}
