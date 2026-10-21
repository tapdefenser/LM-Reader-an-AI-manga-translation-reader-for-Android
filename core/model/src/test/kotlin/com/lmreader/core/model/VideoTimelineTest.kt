package com.lmreader.core.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoTimelineTest {

    @Test
    fun `sampling rate requires positive numerator and denominator`() {
        assertEquals(VideoSamplingRate(1, 2), VideoSamplingRate(1, 2))

        assertFailsWith<IllegalArgumentException> { VideoSamplingRate(0) }
        assertFailsWith<IllegalArgumentException> { VideoSamplingRate(-1) }
        assertFailsWith<IllegalArgumentException> { VideoSamplingRate(1, 0) }
        assertFailsWith<IllegalArgumentException> { VideoSamplingRate(1, -1) }
    }

    @Test
    fun `supported presets are half one two and five fps`() {
        assertEquals(VideoSamplingRate(1, 2), VideoSamplingRate.HALF_FPS)
        assertEquals(VideoSamplingRate(1), VideoSamplingRate.ONE_FPS)
        assertEquals(VideoSamplingRate(2), VideoSamplingRate.TWO_FPS)
        assertEquals(VideoSamplingRate(5), VideoSamplingRate.FIVE_FPS)
        assertEquals(
            listOf(
                VideoSamplingRate(1, 2),
                VideoSamplingRate(1),
                VideoSamplingRate(2),
                VideoSamplingRate(5),
            ),
            VideoSamplingRate.SUPPORTED_PRESETS,
        )
    }

    @Test
    fun `one hour at one fps has 3600 samples`() {
        val plan = VideoSamplingPlan(
            durationMs = 3_600_000L,
            rate = VideoSamplingRate.ONE_FPS,
        )

        assertEquals(0L, plan.firstIndex)
        assertEquals(3_600L, plan.sampleCount)
        assertEquals(0L, plan.targetUs(0L))
        assertEquals(1_000_000L, plan.targetUs(1L))
        assertEquals(3_599_000_000L, plan.targetUs(3_599L))
        assertFailsWith<IllegalArgumentException> { plan.targetUs(-1L) }
        assertFailsWith<IllegalArgumentException> { plan.targetUs(3_600L) }
    }

    @Test
    fun `half fps samples every two seconds`() {
        val plan = VideoSamplingPlan(
            durationMs = 60_000L,
            rate = VideoSamplingRate.HALF_FPS,
        )

        assertEquals(0L, plan.firstIndex)
        assertEquals(30L, plan.sampleCount)
        assertEquals(0L, plan.targetUs(0L))
        assertEquals(2_000_000L, plan.targetUs(1L))
        assertEquals(58_000_000L, plan.targetUs(29L))
        assertNull(plan.nextIndexAfter(29L))
    }

    @Test
    fun `five fps samples every 200 milliseconds`() {
        val plan = VideoSamplingPlan(
            durationMs = 1_000L,
            rate = VideoSamplingRate.FIVE_FPS,
        )

        assertEquals(0L, plan.firstIndex)
        assertEquals(5L, plan.sampleCount)
        assertEquals(0L, plan.targetUs(0L))
        assertEquals(200_000L, plan.targetUs(1L))
        assertEquals(800_000L, plan.targetUs(4L))
        assertFailsWith<IllegalArgumentException> { plan.targetUs(5L) }
    }

    @Test
    fun `nonzero range keeps global grid indexes and half open end`() {
        val plan = VideoSamplingPlan(
            durationMs = 10_000L,
            rate = VideoSamplingRate.ONE_FPS,
            startMs = 2_500L,
            endMs = 7_500L,
        )

        assertEquals(3L, plan.firstIndex)
        assertEquals(5L, plan.sampleCount)
        assertEquals(3_000_000L, plan.targetUs(3L))
        assertEquals(7_000_000L, plan.targetUs(7L))
        assertFailsWith<IllegalArgumentException> { plan.targetUs(2L) }
        assertFailsWith<IllegalArgumentException> { plan.targetUs(8L) }
        assertEquals(3L, plan.nextIndexAfter(null))
        assertEquals(4L, plan.nextIndexAfter(3L))
        assertNull(plan.nextIndexAfter(7L))
    }

    @Test
    fun `fractional half fps boundary rounds up on the global grid`() {
        val plan = VideoSamplingPlan(
            durationMs = 20_000L,
            rate = VideoSamplingRate.HALF_FPS,
            startMs = 4_999L,
            endMs = 13_000L,
        )

        assertEquals(3L, plan.firstIndex)
        assertEquals(4L, plan.sampleCount)
        assertEquals(6_000_000L, plan.targetUs(3L))
        assertEquals(8_000_000L, plan.targetUs(4L))
        assertEquals(10_000_000L, plan.targetUs(5L))
        assertEquals(12_000_000L, plan.targetUs(6L))
        assertNull(plan.nextIndexAfter(6L))
    }

    @Test
    fun `sample exactly at end is excluded`() {
        val plan = VideoSamplingPlan(
            durationMs = 10_000L,
            rate = VideoSamplingRate.ONE_FPS,
        )

        assertEquals(10L, plan.sampleCount)
        assertEquals(9_000_000L, plan.targetUs(9L))
        assertFailsWith<IllegalArgumentException> { plan.targetUs(10L) }
        assertNull(plan.nextIndexAfter(9L))
        assertNull(plan.nextIndexAfter(10L))
    }

    @Test
    fun `checkpoint resume skips committed indexes and finishes`() {
        val plan = VideoSamplingPlan(
            durationMs = 10_000L,
            rate = VideoSamplingRate.FIVE_FPS,
            startMs = 1_000L,
            endMs = 9_000L,
        )

        assertEquals(5L, plan.firstIndex)
        assertEquals(40L, plan.sampleCount)
        assertEquals(5L, plan.nextIndexAfter(null))
        assertEquals(6L, plan.nextIndexAfter(5L))
        assertEquals(5L, plan.nextIndexAfter(0L))
        assertEquals(5L, plan.nextIndexAfter(-100L))
        assertEquals(44L, plan.nextIndexAfter(43L))
        assertNull(plan.nextIndexAfter(44L))
        assertNull(plan.nextIndexAfter(Long.MAX_VALUE))

        var committed: Long? = null
        val visited = ArrayList<Long>()
        while (true) {
            val next = plan.nextIndexAfter(committed) ?: break
            visited += next
            committed = next
        }

        assertEquals(40, visited.size)
        assertEquals(5L, visited.first())
        assertEquals(44L, visited.last())
        assertEquals((5L..44L).toList(), visited)
    }

    @Test
    fun `zero length plan has no resumable sample`() {
        val plan = VideoSamplingPlan(
            durationMs = 1_000L,
            rate = VideoSamplingRate.ONE_FPS,
            startMs = 500L,
            endMs = 500L,
        )

        assertEquals(1L, plan.firstIndex)
        assertEquals(0L, plan.sampleCount)
        assertNull(plan.nextIndexAfter(null))
        assertNull(plan.nextIndexAfter(0L))
        assertFailsWith<IllegalArgumentException> { plan.targetUs(0L) }
        assertFailsWith<IllegalArgumentException> { plan.targetUs(1L) }
    }

    @Test
    fun `sampling plan validates duration and half open bounds`() {
        assertFailsWith<IllegalArgumentException> {
            VideoSamplingPlan(-1L, VideoSamplingRate.ONE_FPS)
        }
        assertFailsWith<IllegalArgumentException> {
            VideoSamplingPlan(1_000L, VideoSamplingRate.ONE_FPS, startMs = -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            VideoSamplingPlan(1_000L, VideoSamplingRate.ONE_FPS, startMs = 800L, endMs = 700L)
        }
        assertFailsWith<IllegalArgumentException> {
            VideoSamplingPlan(1_000L, VideoSamplingRate.ONE_FPS, endMs = 1_001L)
        }
    }

    @Test
    fun `big integer math stays exact at the microsecond limit`() {
        val maxEndMs = Long.MAX_VALUE / 1_000L
        val plan = VideoSamplingPlan(
            durationMs = maxEndMs,
            rate = VideoSamplingRate.FIVE_FPS,
        )

        assertEquals(0L, plan.firstIndex)
        assertEquals(46_116_860_184_274L, plan.sampleCount)
        val lastIndex = plan.firstIndex + plan.sampleCount - 1L
        assertEquals(9_223_372_036_854_600_000L, plan.targetUs(lastIndex))
        assertEquals(lastIndex, plan.nextIndexAfter(lastIndex - 1L))
        assertNull(plan.nextIndexAfter(lastIndex))

        val wideDuration = VideoSamplingPlan(
            durationMs = Long.MAX_VALUE,
            rate = VideoSamplingRate.FIVE_FPS,
            endMs = maxEndMs,
        )
        assertEquals(plan.sampleCount, wideDuration.sampleCount)
        assertEquals(plan.targetUs(lastIndex), wideDuration.targetUs(lastIndex))
    }

    @Test
    fun `big start offset near the limit uses exact global indexes`() {
        val maxEndMs = Long.MAX_VALUE / 1_000L
        val plan = VideoSamplingPlan(
            durationMs = maxEndMs,
            rate = VideoSamplingRate.FIVE_FPS,
            startMs = maxEndMs - 1_000L,
            endMs = maxEndMs,
        )

        assertEquals(46_116_860_184_269L, plan.firstIndex)
        assertEquals(5L, plan.sampleCount)
        assertEquals(9_223_372_036_853_800_000L, plan.targetUs(plan.firstIndex))
        assertEquals(9_223_372_036_854_600_000L, plan.targetUs(plan.firstIndex + 4L))
    }

    @Test
    fun `unrepresentable ends and index overflow are rejected`() {
        val maxEndMs = Long.MAX_VALUE / 1_000L

        assertFailsWith<IllegalArgumentException> {
            VideoSamplingPlan(maxEndMs + 1L, VideoSamplingRate.ONE_FPS)
        }
        assertFailsWith<IllegalArgumentException> {
            VideoSamplingPlan(maxEndMs, VideoSamplingRate(Int.MAX_VALUE))
        }
    }

    @Test
    fun `very high rate still follows the floor grid without overflow`() {
        val plan = VideoSamplingPlan(
            durationMs = 1_000L,
            rate = VideoSamplingRate(Int.MAX_VALUE),
        )

        assertEquals(0L, plan.firstIndex)
        assertEquals(2_147_483_647L, plan.sampleCount)
        assertEquals(0L, plan.targetUs(1L))
        assertEquals(999_999L, plan.targetUs(2_147_483_646L))
    }

    @Test
    fun `watch threshold is half duration capped at 30 seconds`() {
        assertEquals(1L, VideoWatchSession(1L).countThresholdMs)
        assertEquals(5L, VideoWatchSession(9L).countThresholdMs)
        assertEquals(30_000L, VideoWatchSession(60_000L).countThresholdMs)
        assertEquals(30_000L, VideoWatchSession(120_000L).countThresholdMs)
        assertEquals(30_000L, VideoWatchSession(Long.MAX_VALUE).countThresholdMs)
    }

    @Test
    fun `watch counts once at threshold and keeps accumulating`() {
        val before = VideoWatchSession(120_000L).advancePlaying(29_999L)
        assertFalse(before.counted)
        assertEquals(29_999L, before.accumulatedPlayingMs)

        val counted = before.advancePlaying(1L)
        assertTrue(counted.counted)
        assertEquals(30_000L, counted.accumulatedPlayingMs)

        val after = counted.advancePlaying(60_000L)
        assertTrue(after.counted)
        assertEquals(90_000L, after.accumulatedPlayingMs)

        val ended = after.advancePlaying(Long.MAX_VALUE)
        assertTrue(ended.counted)
        assertEquals(120_000L, ended.accumulatedPlayingMs)
    }

    @Test
    fun `short watch counts at ceil half and zero elapsed is a no op`() {
        assertFalse(VideoWatchSession(9L).advancePlaying(4L).counted)
        assertTrue(VideoWatchSession(9L).advancePlaying(5L).counted)

        val idle = VideoWatchSession(9L)
        assertEquals(idle, idle.advancePlaying(0L))
    }

    @Test
    fun `watch rejects invalid duration accumulated time and elapsed`() {
        assertFailsWith<IllegalArgumentException> { VideoWatchSession(0L) }
        assertFailsWith<IllegalArgumentException> { VideoWatchSession(-1L) }
        assertFailsWith<IllegalArgumentException> {
            VideoWatchSession(1_000L, accumulatedPlayingMs = -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            VideoWatchSession(1_000L, accumulatedPlayingMs = 1_001L)
        }
        assertFailsWith<IllegalArgumentException> {
            VideoWatchSession(1_000L).advancePlaying(-1L)
        }
    }
}
