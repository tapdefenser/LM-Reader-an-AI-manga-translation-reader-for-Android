package com.lmreader.ui.video

import java.util.Locale

/**
 * Parses a video timecode in seconds, minutes:seconds or hours:minutes:seconds form.
 *
 * Missing colon-separated components count as zero, a blank value is 0, and the seconds
 * fraction accepts at most three digits. A single seconds value may be 60 or more; the
 * seconds field of a colon-separated value must stay below 60, as must the minutes field
 * of a three-part value. Negative and overflowing values are invalid.
 */
fun parseVideoTimecode(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0L
    val parts = trimmed.split(':')
    if (parts.size !in 1..3) return null

    val hours: Long
    val minutes: Long
    val secondsText: String
    when (parts.size) {
        1 -> {
            hours = 0L
            minutes = 0L
            secondsText = parts[0].trim()
        }
        2 -> {
            hours = 0L
            minutes = parseWholePart(parts[0].trim()) ?: return null
            secondsText = parts[1].trim()
        }
        else -> {
            hours = parseWholePart(parts[0].trim()) ?: return null
            minutes = parseWholePart(parts[1].trim()) ?: return null
            if (minutes > 59L) return null
            secondsText = parts[2].trim()
        }
    }

    val (seconds, millis) = parseSecondsPart(secondsText) ?: return null
    if (parts.size > 1 && seconds > 59L) return null
    return timecodeMillis(hours, minutes, seconds, millis)
}

/** Formats milliseconds as HH:MM:SS.mmm without overflowing on any Long value. */
fun formatVideoTimecode(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    return String.format(
        Locale.ROOT,
        "%02d:%02d:%02d.%03d",
        safe / 3_600_000L,
        safe / 60_000L % 60L,
        safe / 1_000L % 60L,
        safe % 1_000L,
    )
}

private fun parseWholePart(text: String): Long? {
    if (text.isEmpty()) return 0L
    if (text.any { it !in '0'..'9' }) return null
    return text.toLongOrNull()
}

private fun parseSecondsPart(text: String): Pair<Long, Long>? {
    if (text.isEmpty()) return 0L to 0L
    val dot = text.indexOf('.')
    if (dot < 0) {
        return parseWholePart(text)?.let { it to 0L }
    }
    if (text.indexOf('.', dot + 1) >= 0) return null
    val wholeText = text.substring(0, dot)
    val fractionText = text.substring(dot + 1)
    if (fractionText.length > 3) return null
    if (wholeText.isEmpty() && fractionText.isEmpty()) return null
    val whole = parseWholePart(wholeText) ?: return null
    if (fractionText.any { it !in '0'..'9' }) return null
    val millis = if (fractionText.isEmpty()) 0L else fractionText.padEnd(3, '0').toLong()
    return whole to millis
}

private fun timecodeMillis(hours: Long, minutes: Long, seconds: Long, millis: Long): Long? {
    if (hours < 0L || minutes < 0L || seconds < 0L || millis !in 0L..999L) return null
    val totalSeconds = try {
        Math.addExact(
            Math.addExact(Math.multiplyExact(hours, 3_600L), Math.multiplyExact(minutes, 60L)),
            seconds,
        )
    } catch (_: ArithmeticException) {
        return null
    }
    if (totalSeconds > (Long.MAX_VALUE - millis) / 1_000L) return null
    return totalSeconds * 1_000L + millis
}
