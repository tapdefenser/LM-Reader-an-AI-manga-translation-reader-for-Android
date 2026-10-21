package com.lmreader.core.model

import java.math.BigInteger

/**
 * A video sampling rate expressed as an exact, positive rational
 * samples-per-second value.
 *
 * [SUPPORTED_PRESETS] contains the rates offered by the app. Any positive
 * fraction is accepted so callers can describe a rate without depending on a
 * fixed enum.
 */
data class VideoSamplingRate(
    val numerator: Int,
    val denominator: Int = 1,
) {
    init {
        require(numerator > 0) { "sampling numerator must be positive" }
        require(denominator > 0) { "sampling denominator must be positive" }
    }

    companion object {
        val HALF_FPS: VideoSamplingRate = VideoSamplingRate(1, 2)
        val ONE_FPS: VideoSamplingRate = VideoSamplingRate(1)
        val TWO_FPS: VideoSamplingRate = VideoSamplingRate(2)
        val FIVE_FPS: VideoSamplingRate = VideoSamplingRate(5)

        val SUPPORTED_PRESETS: List<VideoSamplingRate> = listOf(
            HALF_FPS,
            ONE_FPS,
            TWO_FPS,
            FIVE_FPS,
        )
    }
}

private const val MICROS_PER_MILLISECOND = 1_000L
private const val MICROS_PER_SECOND = 1_000_000L
private const val WATCH_COUNT_DELAY_CAP_MS = 30_000L

private val MICROS_PER_MILLISECOND_BIG: BigInteger = BigInteger.valueOf(MICROS_PER_MILLISECOND)
private val MICROS_PER_SECOND_BIG: BigInteger = BigInteger.valueOf(MICROS_PER_SECOND)
private val LONG_MAX_BIG: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)

/**
 * A sampling plan over one range of an offline video timeline.
 *
 * Sample indices are global: grid sample `k` is always
 * `floor(k * 1_000_000 * denominator / numerator)` microseconds from the
 * start of the video, independent of [startMs]. The plan covers the half-open
 * range `[startMs, endMs)` and never includes a sample exactly at [endMs].
 *
 * Indices and counts are computed exactly with [BigInteger] instead of
 * accumulating timestamps, so long videos and fractional boundaries do not
 * drift or overflow. No sample list is materialized.
 *
 * Because [targetUs] returns microseconds in a [Long], a plan end beyond
 * `Long.MAX_VALUE / 1_000` milliseconds (the largest representable duration
 * in microseconds) is rejected.
 */
class VideoSamplingPlan(
    val durationMs: Long,
    val rate: VideoSamplingRate,
    val startMs: Long = 0,
    val endMs: Long = durationMs,
) {
    /** First global grid index contained in this plan, or the end bound when empty. */
    val firstIndex: Long

    /** Number of grid samples in `[startMs, endMs)`. */
    val sampleCount: Long

    private val endIndexExclusive: Long

    private val rateNumerator: BigInteger = BigInteger.valueOf(rate.numerator.toLong())
    private val rateDenominator: BigInteger = BigInteger.valueOf(rate.denominator.toLong())

    init {
        require(durationMs >= 0) { "duration must not be negative" }
        require(startMs >= 0) { "start must not be negative" }
        require(startMs <= endMs) { "start must not be after end" }
        require(endMs <= durationMs) { "end must not exceed duration" }
        require(endMs <= MAX_REPRESENTABLE_END_MS) {
            "end exceeds the representable microsecond range"
        }

        val first = gridIndexAtOrAfter(startMs)
        val end = gridIndexAtOrAfter(endMs)
        require(end <= LONG_MAX_BIG) { "sampling index exceeds the Long range" }

        firstIndex = first.toLong()
        endIndexExclusive = end.toLong()
        sampleCount = endIndexExclusive - firstIndex
    }

    /**
     * Absolute microsecond timestamp of global grid [index].
     *
     * Indices before [firstIndex] or at/after the plan end are rejected.
     */
    fun targetUs(index: Long): Long {
        require(index >= firstIndex && index < endIndexExclusive) {
            "sample index $index is outside [$firstIndex, $endIndexExclusive)"
        }
        return BigInteger.valueOf(index)
            .multiply(MICROS_PER_SECOND_BIG)
            .multiply(rateDenominator)
            .divide(rateNumerator)
            .longValueExact()
    }

    /**
     * Next global sample index after [committedIndex], or `null` when done.
     *
     * `null` starts at [firstIndex]. A committed index before the plan starts
     * also resumes at [firstIndex]; an index at or past the last sample ends
     * the plan. Paused or seeking playback must not call this.
     */
    fun nextIndexAfter(committedIndex: Long?): Long? {
        if (sampleCount == 0L) return null
        if (committedIndex == null || committedIndex < firstIndex) return firstIndex
        if (committedIndex >= endIndexExclusive - 1) return null
        return committedIndex + 1
    }

    private fun gridIndexAtOrAfter(milliseconds: Long): BigInteger {
        val microseconds = BigInteger.valueOf(milliseconds).multiply(MICROS_PER_MILLISECOND_BIG)
        return ceilDivide(
            microseconds.multiply(rateNumerator),
            MICROS_PER_SECOND_BIG.multiply(rateDenominator),
        )
    }

    private fun ceilDivide(value: BigInteger, divisor: BigInteger): BigInteger {
        val quotient = value.divide(divisor)
        return if (value.mod(divisor).signum() == 0) quotient else quotient.add(BigInteger.ONE)
    }

    companion object {
        private const val MAX_REPRESENTABLE_END_MS = Long.MAX_VALUE / MICROS_PER_MILLISECOND
    }
}

/**
 * Playing-time accumulator for one video watch session.
 *
 * Counting happens once, after
 * `min(30000 ms, ceil(durationMs / 2))` of playing time. Callers must only
 * pass actual playing time to [advancePlaying]; paused time and seek jumps do
 * not advance the session.
 */
data class VideoWatchSession(
    val durationMs: Long,
    val accumulatedPlayingMs: Long = 0,
    val counted: Boolean = false,
) {
    /** Playing time needed to count this session. */
    val countThresholdMs: Long =
        minOf(WATCH_COUNT_DELAY_CAP_MS, durationMs / 2 + durationMs % 2)

    init {
        require(durationMs > 0) { "watch duration must be positive" }
        require(accumulatedPlayingMs >= 0) { "accumulated playing time must not be negative" }
        require(accumulatedPlayingMs <= durationMs) {
            "accumulated playing time must not exceed duration"
        }
    }

    /**
     * Advance by [elapsedMs] of actual playing time.
     *
     * Accumulation is clamped to [durationMs], and [counted] latches once the
     * threshold is reached. A negative elapsed value is rejected; zero is a
     * valid no-op.
     */
    fun advancePlaying(elapsedMs: Long): VideoWatchSession {
        require(elapsedMs >= 0) { "elapsed playing time must not be negative" }

        val remaining = durationMs - accumulatedPlayingMs
        val added = if (elapsedMs >= remaining) remaining else elapsedMs
        val nextAccumulated = accumulatedPlayingMs + added
        val nextCounted = counted || nextAccumulated >= countThresholdMs

        if (nextAccumulated == accumulatedPlayingMs && nextCounted == counted) return this
        return copy(accumulatedPlayingMs = nextAccumulated, counted = nextCounted)
    }
}
