package com.lmreader.core.model

import java.time.ZonedDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckFrequencyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun time(date: String) = ZonedDateTime.parse("${date}+08:00[Asia/Shanghai]").toInstant().toEpochMilli()

    @Test fun dailyUsesFirstLaunchOfCalendarDay() {
        val previous = time("2026-10-04T23:59:00")
        assertFalse(UpdateCheckFrequency.DAILY.isDue(previous, previous + 1000, zone))
        assertTrue(UpdateCheckFrequency.DAILY.isDue(previous, time("2026-10-05T00:01:00"), zone))
    }

    @Test fun threeDaysKeepsTheIntervalAcrossRestarts() {
        val previous = time("2026-10-02T23:59:00")
        assertFalse(UpdateCheckFrequency.EVERY_THREE_DAYS.isDue(previous, time("2026-10-04T12:00:00"), zone))
        assertTrue(UpdateCheckFrequency.EVERY_THREE_DAYS.isDue(previous, time("2026-10-05T00:01:00"), zone))
    }

    @Test fun offNeverAutomaticallyChecksButEveryLaunchDoes() {
        val now = time("2026-10-05T12:00:00")
        assertFalse(UpdateCheckFrequency.OFF.isDue(0, now, zone))
        assertFalse(UpdateCheckFrequency.OFF.isDue(now, now, zone))
        assertTrue(UpdateCheckFrequency.EVERY_LAUNCH.isDue(now, now, zone))
    }

    @Test fun firstLaunchAndClockRollbackDoNotPreventChecks() {
        val now = time("2026-10-05T12:00:00")
        listOf(UpdateCheckFrequency.DAILY, UpdateCheckFrequency.EVERY_THREE_DAYS).forEach {
            assertTrue(it.isDue(0, now, zone))
            assertTrue(it.isDue(now + 60000, now, zone))
        }
    }

    @Test fun calendarDayIsCorrectAcrossDaylightSavingTime() {
        val newYork = ZoneId.of("America/New_York")
        val first = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, newYork).toInstant().toEpochMilli()
        val next = ZonedDateTime.of(2026, 3, 9, 0, 0, 0, 0, newYork).toInstant().toEpochMilli()
        assertEquals(23 * 3600000L, next - first)
        assertTrue(UpdateCheckFrequency.DAILY.isDue(first, next, newYork))
    }
}
