package com.lmreader.ui.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoTimecodeTest {
    private fun parse(text: String): Long = requireNotNull(parseVideoTimecode(text)) { "expected a valid timecode: $text" }

    private fun assertInvalid(text: String) = assertNull("expected an invalid timecode: $text", parseVideoTimecode(text))

    @Test fun parsesSecondsMinutesAndHours() {
        assertEquals(5_000L, parse("5"))
        assertEquals(80_000L, parse("1:20"))
        assertEquals(3_600_000L, parse("1::"))
        assertEquals(3_723_000L, parse("1:02:03"))
        assertEquals(3_600_000L, parse("01:00:00"))
        assertEquals(360_000_000L, parse("100:00:00"))
    }

    @Test fun missingComponentsDefaultToZero() {
        assertEquals(0L, parse(""))
        assertEquals(0L, parse("   \t "))
        assertEquals(0L, parse(":"))
        assertEquals(0L, parse("::"))
        assertEquals(5_000L, parse(":5"))
        assertEquals(60_000L, parse("1:"))
        assertEquals(3_602_000L, parse("1::2"))
        assertEquals(3_600_000L, parse(" 1 : : "))
    }

    @Test fun secondsMayExceedSixtyOnlyWithoutColons() {
        assertEquals(0L, parse("0"))
        assertEquals(59_000L, parse("59"))
        assertEquals(60_000L, parse("60"))
        assertEquals(59_000L, parse("0:59"))
        assertEquals(59_000L, parse("0:00:59"))
        assertInvalid("0:60")
        assertInvalid("1:60")
        assertInvalid("0:60:00")
        assertInvalid("0:00:60")
        assertInvalid("1:2:60")
    }

    @Test fun minutesOfThreePartValuesMustStayBelowSixty() {
        assertEquals(3_599_000L, parse("0:59:59"))
        assertEquals(3_600_000L, parse("1:00:00"))
        assertInvalid("1:60:00")
        assertInvalid("1:99:00")
    }

    @Test fun fractionsAreMillisecondsUpToThreeDigits() {
        assertEquals(1_500L, parse("1.5"))
        assertEquals(500L, parse(".5"))
        assertEquals(5_000L, parse("5."))
        assertEquals(80_500L, parse("1:20.5"))
        assertEquals(80_050L, parse("1:20.05"))
        assertEquals(80_005L, parse("1:20.005"))
        assertEquals(80_500L, parse("1:20.500"))
        assertEquals(3_723_000L, parse("1:2:3."))
        assertInvalid("1:20.1234")
        assertInvalid("1:20.12x")
        assertInvalid("1.2:3")
        assertInvalid("1:2.3:4")
        assertInvalid(".")
    }

    @Test fun invalidShapesReturnNull() {
        assertInvalid("abc")
        assertInvalid("-1")
        assertInvalid("1:-2")
        assertInvalid("+1")
        assertInvalid("1:2:3:4")
        assertInvalid("1:2:3..4")
        assertInvalid("1:2:3.4.5")
    }

    @Test fun overflowReturnsNullInsteadOfWrapping() {
        assertInvalid(Long.MAX_VALUE.toString())
        assertInvalid("9223372036854776")
        assertEquals(Long.MAX_VALUE, parse("9223372036854775.807"))
        assertInvalid("9223372036854775.808")
        assertInvalid("${Long.MAX_VALUE}:00:00")
        assertInvalid("1:${Long.MAX_VALUE}")
        assertInvalid("1:${Long.MAX_VALUE}:00")
    }

    @Test fun formatsFixedWidthHoursMinutesSecondsAndMillis() {
        assertEquals("00:00:00.000", formatVideoTimecode(0L))
        assertEquals("00:00:05.000", formatVideoTimecode(5_000L))
        assertEquals("00:00:59.999", formatVideoTimecode(59_999L))
        assertEquals("00:01:00.000", formatVideoTimecode(60_000L))
        assertEquals("00:01:20.000", formatVideoTimecode(80_000L))
        assertEquals("01:00:00.000", formatVideoTimecode(3_600_000L))
        assertEquals("01:02:03.004", formatVideoTimecode(3_723_004L))
        assertEquals("100:00:00.000", formatVideoTimecode(360_000_000L))
    }

    @Test fun formatClampsNegativesAndSurvivesTheWholeLongRange() {
        assertEquals("00:00:00.000", formatVideoTimecode(-1L))
        assertEquals("00:00:00.000", formatVideoTimecode(Long.MIN_VALUE))
        assertEquals("2562047788015:12:55.807", formatVideoTimecode(Long.MAX_VALUE))
    }

    @Test fun roundTripsRepresentativeValues() {
        listOf(0L, 1L, 999L, 1_000L, 59_999L, 60_000L, 3_599_999L, 3_600_000L, 3_723_004L, Long.MAX_VALUE).forEach {
            assertEquals(it, parse(formatVideoTimecode(it)))
        }
    }
}
