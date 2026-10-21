package com.lmreader.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.lmreader.core.api.ApiAudio
import com.lmreader.core.api.ApiImage
import com.lmreader.core.model.VideoSubtitleCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reconstructible resources for one subtitle unit; no audio or images are loaded until referenced. */
class VideoSentenceResources(private val context: Context, private val uri: String, private val audioTrack: Int) {
    suspend fun audio(cue: VideoSubtitleCue): ApiAudio {
        require(cue.endMs > cue.startMs && cue.endMs - cue.startMs <= MAX_AUDIO_MS) {
            "单句录音必须在 2 分钟内，请先在播放器中调整该句的起止时间"
        }
        val decoder = VideoAudioDecoder(context, uri, audioTrack, cue.startMs, cue.endMs)
        val pcm = ByteArrayOutputStream()
        try {
            decoder.decode { chunk ->
                val first = ((cue.startMs - chunk.startMs) * 16).coerceIn(0, chunk.samples.size.toLong()).toInt()
                val last = ((cue.endMs - chunk.startMs) * 16).coerceIn(first.toLong(), chunk.samples.size.toLong()).toInt()
                require(pcm.size().toLong() + (last - first) * 2L <= MAX_AUDIO_MS * 32) { "单句音频附件过大" }
                // Decoder chunks overlap by one second. Each source sample enters the attachment once.
                val nextSample = cue.startMs * 16 + pcm.size() / 2
                val uniqueFirst = maxOf(first, (nextSample - chunk.startMs * 16).coerceIn(0, chunk.samples.size.toLong()).toInt())
                for(index in uniqueFirst until last) {
                    currentCoroutineContext().ensureActive()
                    val sample = (chunk.samples[index].coerceIn(-1f, 1f) * 32767).toInt()
                    pcm.write(sample and 255); pcm.write((sample shr 8) and 255)
                }
            }
        } finally { decoder.close() }
        check(pcm.size() > 0) { "该字幕区间没有可读取的录音" }
        val wav = wav(pcm.toByteArray())
        return ApiAudio("audio/wav", Base64.encodeToString(wav, Base64.NO_WRAP))
    }

    suspend fun image(cue: VideoSubtitleCue): ApiImage {
        val decoder = VideoFrameDecoder(context, uri, 1280)
        try {
            val frame = decoder.frameAt(midpointMs(cue) * 1000) ?: error("无法读取字幕区间中点的原画面")
            try {
                return withContext(Dispatchers.Default) {
                    val bytes = ByteArrayOutputStream()
                    check(frame.bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes)) { "无法生成句子截图" }
                    ApiImage("image/jpeg", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
                }
            } finally { frame.close() }
        } finally { decoder.close() }
    }

    companion object {
        private const val MAX_AUDIO_MS = 120_000L
        fun midpointMs(cue: VideoSubtitleCue): Long = cue.startMs + (cue.endMs - cue.startMs) / 2
        internal fun wav(pcm: ByteArray): ByteArray {
            require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= MAX_AUDIO_MS * 32)
            return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
                putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                put("data".toByteArray()).putInt(pcm.size).put(pcm)
            }.array()
        }
    }
}
