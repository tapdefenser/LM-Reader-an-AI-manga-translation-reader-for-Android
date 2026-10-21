package com.lmreader.core.model

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * One imported subtitle cue.
 *
 * [id] is an opaque, stable identifier; the other fields are the displayable
 * data. Times are milliseconds on the source video timeline and use a
 * half-open `[startMs, endMs)` interval.
 */
data class VideoSubtitleCue(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val source: String,
    val translation: String = "",
    val manuallyEdited: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "subtitle id must not be blank" }
        require(id.length <= MAX_ID_LENGTH) { "subtitle id exceeds $MAX_ID_LENGTH characters" }
        require(id.none { it.code < 0x20 || it.code in 0x7F..0x9F }) { "subtitle id contains control characters" }
        require(startMs >= 0) { "subtitle start must not be negative" }
        require(endMs > startMs) { "subtitle end must be later than its start" }
        require(source.length <= MAX_TEXT_LENGTH) { "subtitle source exceeds $MAX_TEXT_LENGTH characters" }
        require(translation.length <= MAX_TEXT_LENGTH) { "subtitle translation exceeds $MAX_TEXT_LENGTH characters" }
    }

    companion object {
        const val MAX_ID_LENGTH = 128
        const val MAX_TEXT_LENGTH = 4 * 1024 * 1024
    }
}

/** Which text (if any) an exported cue should contain. */
enum class VideoSubtitleDisplay { ORIGINAL, TRANSLATED, BILINGUAL }

/**
 * Strict SRT parsing and deterministic SRT export.
 *
 * Parsing never repairs or drops a bad block: malformed input fails with the
 * 1-based line number so an import can point the user at the actual problem.
 * Cue identity is derived from the cue ordinal and its timing/text, not from
 * the SRT sequence number (which is allowed to be duplicated).
 */
object VideoSubtitles {
    const val MAX_INPUT_BYTES = 4 * 1024 * 1024
    const val MAX_CUES = 20_000

    private val timestampPattern = Regex("""(\d{2,}):([0-5]\d):([0-5]\d)[,.](\d{3})""")
    private const val HEX = "0123456789abcdef"

    /**
     * Parse a UTF-8 SRT file. A leading BOM and CRLF/CR line endings are
     * accepted. An optional [durationMs] rejects cues whose end is past the
     * known video duration.
     */
    fun parse(bytes: ByteArray, durationMs: Long? = null): List<VideoSubtitleCue> {
        require(durationMs == null || durationMs >= 0) { "video duration must not be negative" }
        require(bytes.size <= MAX_INPUT_BYTES) {
            "subtitle input exceeds $MAX_INPUT_BYTES bytes"
        }
        if (bytes.isEmpty()) return emptyList()

        val decoded = decodeUtf8(bytes)
        val withoutBom = if (decoded.startsWith('\uFEFF')) decoded.substring(1) else decoded
        val text = withoutBom.replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) return emptyList()

        val lines = text.split('\n')
        val cues = ArrayList<VideoSubtitleCue>()
        var index = 0
        var previousStart = Long.MIN_VALUE

        while (true) {
            while (index < lines.size && lines[index].isBlank()) index++
            if (index >= lines.size) break

            if (cues.size >= MAX_CUES) {
                fail(index + 1, "subtitle cue count exceeds $MAX_CUES")
            }

            val sequenceLine = index + 1
            val sequenceText = lines[index].trim()
            if (sequenceText.toIntOrNull() == null) {
                fail(sequenceLine, "cue sequence must be an integer")
            }
            index++

            if (index >= lines.size || lines[index].isBlank()) {
                fail(sequenceLine, "cue is missing its timing line")
            }
            val timingLine = index + 1
            val timing = parseTiming(lines[index], timingLine)
            index++

            if (timing.first < previousStart) {
                fail(
                    timingLine,
                    "start time is earlier than the previous cue start " +
                        "(${formatTimestamp(previousStart)})",
                )
            }
            if (timing.second <= timing.first) {
                fail(timingLine, "subtitle end must be later than its start")
            }
            if (durationMs != null && timing.second > durationMs) {
                fail(
                    timingLine,
                    "subtitle end ${formatTimestamp(timing.second)} exceeds video duration " +
                        formatTimestamp(durationMs),
                )
            }

            val textStart = index
            while (index < lines.size && !lines[index].isBlank()) index++
            if (index == textStart) {
                fail(timingLine, "cue is missing its text")
            }
            val source = lines.subList(textStart, index).joinToString("\n")

            cues += VideoSubtitleCue(
                id = parsedCueId(cues.size, timing.first, timing.second, source),
                startMs = timing.first,
                endMs = timing.second,
                source = source,
            )
            previousStart = timing.first
        }

        return cues
    }

    /**
     * Export cues as renumbered SRT. Offset is applied before clamping at
     * zero; intervals that collapse to zero length are omitted. Text is kept
     * verbatim (no HTML escaping or trimming).
     */
    fun encode(
        cues: List<VideoSubtitleCue>,
        display: VideoSubtitleDisplay,
        offsetMs: Long = 0,
    ): String = buildString {
        var sequence = 0
        for (cue in cues) {
            val start = saturatedAdd(cue.startMs, offsetMs).coerceAtLeast(0)
            val end = saturatedAdd(cue.endMs, offsetMs).coerceAtLeast(0)
            if (end <= start) continue

            sequence++
            append(sequence).append('\n')
            append(formatTimestamp(start)).append(" --> ").append(formatTimestamp(end)).append('\n')
            append(displayText(cue, display)).append('\n')
            append('\n')
        }
    }

    /** Cues whose shifted half-open interval contains [positionMs]. */
    fun active(
        cues: List<VideoSubtitleCue>,
        positionMs: Long,
        offsetMs: Long = 0,
    ): List<VideoSubtitleCue> {
        return cues.filter { cue ->
            val start = saturatedAdd(cue.startMs, offsetMs)
            val end = saturatedAdd(cue.endMs, offsetMs)
            positionMs >= start && positionMs < end
        }
    }

    private fun displayText(cue: VideoSubtitleCue, display: VideoSubtitleDisplay): String {
        return when (display) {
            VideoSubtitleDisplay.ORIGINAL -> cue.source
            VideoSubtitleDisplay.TRANSLATED -> if (cue.translation.isEmpty()) cue.source else cue.translation
            VideoSubtitleDisplay.BILINGUAL -> when {
                cue.translation.isEmpty() || cue.translation == cue.source -> cue.source
                cue.source.isEmpty() -> cue.translation
                else -> cue.source + "\n" + cue.translation
            }
        }
    }

    private fun parseTiming(line: String, lineNumber: Int): Pair<Long, Long> {
        val arrow = line.indexOf("-->")
        if (arrow < 0 || line.indexOf("-->", arrow + 3) >= 0) {
            fail(lineNumber, "timing line must contain exactly one --> separator")
        }
        val start = parseTimestamp(line.substring(0, arrow).trim(), lineNumber, "start")
        val end = parseTimestamp(line.substring(arrow + 3).trim(), lineNumber, "end")
        return start to end
    }

    private fun parseTimestamp(value: String, lineNumber: Int, label: String): Long {
        val match = timestampPattern.matchEntire(value)
            ?: fail(lineNumber, "$label timestamp must use HH:MM:SS,mmm (dot millis is allowed)")
        val hours = match.groupValues[1].toLongOrNull()
            ?: fail(lineNumber, "$label timestamp is out of range")
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        val millis = match.groupValues[4].toLong()
        return try {
            Math.addExact(
                Math.addExact(Math.multiplyExact(hours, 3_600_000L), minutes * 60_000L),
                seconds * 1_000L + millis,
            )
        } catch (_: ArithmeticException) {
            fail(lineNumber, "$label timestamp is out of range")
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("subtitle input is not valid UTF-8")
        }
    }

    private fun parsedCueId(ordinal: Int, startMs: Long, endMs: Long, source: String): String {
        val payload = "$ordinal\u0000$startMs\u0000$endMs\u0000$source"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = buildString(32) {
            for (index in 0 until 16) {
                val value = digest[index].toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
        return "v_$hex"
    }

    private fun saturatedAdd(value: Long, delta: Long): Long = when {
        delta > 0 && value > Long.MAX_VALUE - delta -> Long.MAX_VALUE
        delta < 0 && value < Long.MIN_VALUE - delta -> Long.MIN_VALUE
        else -> value + delta
    }

    private fun formatTimestamp(milliseconds: Long): String {
        val value = milliseconds.coerceAtLeast(0)
        val hours = value / 3_600_000L
        val minutes = (value % 3_600_000L) / 60_000L
        val seconds = (value % 60_000L) / 1_000L
        val millis = value % 1_000L
        return "${hours.toString().padStart(2, '0')}:" +
            "${minutes.toString().padStart(2, '0')}:" +
            "${seconds.toString().padStart(2, '0')}," +
            millis.toString().padStart(3, '0')
    }

    private fun fail(lineNumber: Int, message: String): Nothing {
        throw IllegalArgumentException("line $lineNumber: $message")
    }
}
