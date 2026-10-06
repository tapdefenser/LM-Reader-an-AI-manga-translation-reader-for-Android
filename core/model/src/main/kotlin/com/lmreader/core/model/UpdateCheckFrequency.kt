package com.lmreader.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class UpdateCheckFrequency {
    EVERY_LAUNCH, DAILY, EVERY_THREE_DAYS, OFF;

    fun isDue(lastAttempt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if(this == OFF) return false
        if(this == EVERY_LAUNCH || lastAttempt <= 0 || lastAttempt > now) return true
        val previous = Instant.ofEpochMilli(lastAttempt).atZone(zone).toLocalDate()
        val current = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(previous, current) >= if(this == DAILY) 1 else 3
    }
}
