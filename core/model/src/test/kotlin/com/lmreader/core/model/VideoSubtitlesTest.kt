package com.lmreader.core.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoSubtitlesTest {
    private fun parseText(text: String, durationMs: Long? = null): List<VideoSubtitleCue> =
        VideoSubtitles.parse(text.toByteArray(Charsets.UTF_8), durationMs)

    @Test
    fun `empty and BOM-only input are empty`() {
        assertTrue(VideoSubtitles.parse(ByteArray(0)).isEmpty())
        assertTrue(VideoSubtitles.parse(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())).isEmpty())
        assertTrue(parseText(" \r\n\t").isEmpty())
    }

    @Test
    fun `BOM CRLF multiline and dot millis are accepted`() {
        val srt = "\uFEFF1\r\n00:00:01,250 --> 00:00:03,500\r\nHello\r\nworld\r\n\r\n" +
            "2\r\n00:00:04.000 --> 00:00:05.750\r\nSecond\r\n"

        val cues = parseText(srt)

        assertEquals(2, cues.size)
        assertEquals(1_250L, cues[0].startMs)
        assertEquals(3_500L, cues[0].endMs)
        assertEquals("Hello\nworld", cues[0].source)
        assertEquals(4_000L, cues[1].startMs)
        assertEquals(5_750L, cues[1].endMs)
        assertEquals("Second", cues[1].source)
    }

    @Test
    fun `invalid UTF8 fails strictly`() {
        val error = assertFailsWith<IllegalArgumentException> {
            VideoSubtitles.parse(byteArrayOf(0x31, 0x0A, 0xC3.toByte(), 0x28))
        }
        assertTrue(error.message.orEmpty().contains("UTF-8"))
    }

    @Test
    fun `malformed lines report their line and are never skipped`() {
        val badTiming = "1\n00:00:01,000 --> not-a-time\nHello"
        val timingError = assertFailsWith<IllegalArgumentException> { parseText(badTiming) }
        assertTrue(timingError.message.orEmpty().contains("line 2"), timingError.message)

        val badLaterBlock = "1\n00:00:01,000 --> 00:00:02,000\nFirst\n\noops\nHello"
        val laterError = assertFailsWith<IllegalArgumentException> { parseText(badLaterBlock) }
        assertTrue(laterError.message.orEmpty().contains("line 5"), laterError.message)
    }

    @Test
    fun `inverted start ordering is rejected with its timing line`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            First

            2
            00:00:00,500 --> 00:00:03,000
            Second
        """.trimIndent()

        val error = assertFailsWith<IllegalArgumentException> { parseText(srt) }

        assertTrue(error.message.orEmpty().contains("line 6"), error.message)
        assertTrue(error.message.orEmpty().contains("start"), error.message)
    }

    @Test
    fun `overlap is kept and active uses half-open intervals with offset`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,000
            First

            2
            00:00:02,000 --> 00:00:04,000
            Second
        """.trimIndent()

        val cues = parseText(srt)

        assertEquals(2, cues.size)
        assertEquals(listOf("First"), VideoSubtitles.active(cues, 1_000).map { it.source })
        assertEquals(listOf("First", "Second"), VideoSubtitles.active(cues, 2_000).map { it.source })
        assertEquals(listOf("Second"), VideoSubtitles.active(cues, 3_000).map { it.source })
        assertTrue(VideoSubtitles.active(cues, 4_000).isEmpty())

        assertEquals(listOf("First"), VideoSubtitles.active(cues, 1_500, offsetMs = 500).map { it.source })
        assertEquals(
            listOf("First", "Second"),
            VideoSubtitles.active(cues, 2_500, offsetMs = 500).map { it.source },
        )
    }

    @Test
    fun `parsed identities are deterministic and distinct even with duplicate sequence numbers`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            Same

            1
            00:00:01,000 --> 00:00:02,000
            Same
        """.trimIndent()

        val first = parseText(srt)
        val second = parseText(srt)

        assertEquals(2, first.size)
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(2, first.map { it.id }.distinct().size)
    }

    @Test
    fun `duration and cue timing bounds are enforced`() {
        val srt = "1\n00:00:01,000 --> 00:00:05,000\nText"
        assertEquals(1, parseText(srt, durationMs = 5_000).size)

        val tooLong = assertFailsWith<IllegalArgumentException> { parseText(srt, durationMs = 4_999) }
        assertTrue(tooLong.message.orEmpty().contains("line 2"), tooLong.message)
        assertTrue(tooLong.message.orEmpty().contains("duration"), tooLong.message)

        val zeroLength = "1\n00:00:03,000 --> 00:00:03,000\nText"
        val zeroError = assertFailsWith<IllegalArgumentException> { parseText(zeroLength) }
        assertTrue(zeroError.message.orEmpty().contains("line 2"), zeroError.message)

        val negative = "1\n-00:00:01,000 --> 00:00:02,000\nText"
        val negativeError = assertFailsWith<IllegalArgumentException> { parseText(negative) }
        assertTrue(negativeError.message.orEmpty().contains("line 2"), negativeError.message)
    }

    @Test
    fun `translated bilingual fallback and HTML text survive a round trip`() {
        val cue = VideoSubtitleCue("cue", 1_000, 2_000, "Hello", "Bonjour")

        val translated = VideoSubtitles.encode(listOf(cue), VideoSubtitleDisplay.TRANSLATED)
        assertTrue(translated.contains("Bonjour"))
        assertFalse(translated.contains("Hello"))
        assertEquals("Bonjour", parseText(translated).single().source)

        val bilingual = VideoSubtitles.encode(listOf(cue), VideoSubtitleDisplay.BILINGUAL)
        assertTrue(bilingual.contains("Hello\nBonjour"))
        assertEquals("Hello\nBonjour", parseText(bilingual).single().source)

        val fallback = VideoSubtitles.encode(
            listOf(cue.copy(translation = "")),
            VideoSubtitleDisplay.TRANSLATED,
        )
        assertTrue(fallback.contains("\nHello\n"), fallback)

        val duplicate = VideoSubtitles.encode(
            listOf(cue.copy(translation = "Hello")),
            VideoSubtitleDisplay.BILINGUAL,
        )
        assertEquals(1, Regex("Hello").findAll(duplicate).count())

        val emptySource = VideoSubtitles.encode(
            listOf(cue.copy(source = "", translation = "Only")),
            VideoSubtitleDisplay.BILINGUAL,
        )
        assertTrue(emptySource.contains("\nOnly\n"), emptySource)

        val html = VideoSubtitles.encode(
            listOf(cue.copy(source = "<i>A & B</i>", translation = "")),
            VideoSubtitleDisplay.ORIGINAL,
        )
        assertTrue(html.contains("<i>A & B</i>"), html)
    }

    @Test
    fun `encode renumbers cues and applies offset before clamping`() {
        val cue = VideoSubtitleCue("cue", 1_000, 2_000, "Hello", "Bonjour")
        val renumbered = VideoSubtitles.encode(
            listOf(
                cue.copy(id = "7"),
                cue.copy(id = "7", startMs = 3_000, endMs = 4_000, source = "Second"),
            ),
            VideoSubtitleDisplay.ORIGINAL,
        )
        assertTrue(renumbered.startsWith("1\n"), renumbered)
        assertTrue(renumbered.contains("\n\n2\n"), renumbered)

        val shifted = VideoSubtitles.encode(listOf(cue), VideoSubtitleDisplay.BILINGUAL, offsetMs = 250)
        val shiftedCue = parseText(shifted).single()
        assertEquals(1_250L, shiftedCue.startMs)
        assertEquals(2_250L, shiftedCue.endMs)
        assertEquals("Hello\nBonjour", shiftedCue.source)

        val clipping = VideoSubtitleCue("clip", 100, 200, "Clip")
        val clipped = VideoSubtitles.encode(listOf(clipping), VideoSubtitleDisplay.ORIGINAL, offsetMs = -150)
        assertTrue(clipped.contains("00:00:00,000 --> 00:00:00,050"), clipped)
        assertEquals("", VideoSubtitles.encode(listOf(clipping), VideoSubtitleDisplay.ORIGINAL, offsetMs = -200))
        assertEquals("", VideoSubtitles.encode(listOf(clipping), VideoSubtitleDisplay.ORIGINAL, offsetMs = -300))

        val edge = VideoSubtitleCue("edge", Long.MAX_VALUE - 10, Long.MAX_VALUE, "Edge")
        assertEquals("", VideoSubtitles.encode(listOf(edge), VideoSubtitleDisplay.ORIGINAL, offsetMs = 10))
        assertTrue(VideoSubtitles.active(listOf(edge), Long.MAX_VALUE, offsetMs = 10).isEmpty())
    }

    @Test
    fun `input size and cue count are bounded`() {
        val oversized = ByteArray(VideoSubtitles.MAX_INPUT_BYTES + 1) { 0x20 }
        assertFailsWith<IllegalArgumentException> { VideoSubtitles.parse(oversized) }

        assertEquals(20_000, parseText(manyCues(20_000)).size)
        assertFailsWith<IllegalArgumentException> { parseText(manyCues(20_001)) }
    }

    @Test
    fun `cue model validates identity times and bounded strings`() {
        assertFailsWith<IllegalArgumentException> { VideoSubtitleCue("", 0, 1, "source") }
        assertFailsWith<IllegalArgumentException> { VideoSubtitleCue("id\n", 0, 1, "source") }
        assertFailsWith<IllegalArgumentException> {
            VideoSubtitleCue("x".repeat(VideoSubtitleCue.MAX_ID_LENGTH + 1), 0, 1, "source")
        }
        assertFailsWith<IllegalArgumentException> { VideoSubtitleCue("id", -1, 1, "source") }
        assertFailsWith<IllegalArgumentException> { VideoSubtitleCue("id", 1, 1, "source") }
        assertFailsWith<IllegalArgumentException> { VideoSubtitleCue("id", 2, 1, "source") }
        assertFailsWith<IllegalArgumentException> {
            VideoSubtitleCue("id", 0, 1, "x".repeat(VideoSubtitleCue.MAX_TEXT_LENGTH + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            VideoSubtitleCue("id", 0, 1, "source", "x".repeat(VideoSubtitleCue.MAX_TEXT_LENGTH + 1))
        }

        assertTrue(VideoSubtitleCue("ok", 0, 1, "source", manuallyEdited = true).manuallyEdited)
    }

    private fun manyCues(count: Int): String = buildString {
        repeat(count) { index ->
            append(index + 1).append('\n')
            append("00:00:00,000 --> 00:00:01,000\n")
            append("x\n\n")
        }
    }
}
