package com.lmreader.ui.video

import android.media.*
import java.io.File

/** Eight seconds of generated source strokes, switching at four seconds. No external video required. */
internal object VideoFixture {
    fun create(file: File, rotation: Int = 0) {
        val width = 160; val height = 120; val fps = 6; val count = 48
        val codec = MediaCodec.createEncoderByType("video/avc")
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 128000); setInteger(MediaFormat.KEY_FRAME_RATE, fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)
        var started = false; var track = -1; var finished = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            val info = MediaCodec.BufferInfo()
            fun drain(end: Boolean): Boolean {
                if(finished) return true
                val deadline = System.currentTimeMillis() + 10000
                do {
                    val output = codec.dequeueOutputBuffer(info, if (end) 10000 else 0)
                    when {
                        output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
                        output >= 0 -> {
                            val data = codec.getOutputBuffer(output)!!
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) { check(started); muxer.writeSampleData(track, data, info) }
                            val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(output, false); if (done) { finished = true; return true }
                        }
                        !end -> return false
                    }
                } while (System.currentTimeMillis() < deadline)
                check(!end) { "Video encoder did not finish" }; return false
            }
            for (frame in 0..count) {
                var index = codec.dequeueInputBuffer(10000)
                while (index < 0) { drain(false); index = codec.dequeueInputBuffer(10000) }
                if (frame == count) codec.queueInputBuffer(index, 0, 0, frame * 1000000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                else {
                    val image = requireNotNull(codec.getInputImage(index))
                    for ((planeIndex, plane) in image.planes.withIndex()) {
                        val w = if (planeIndex == 0) width else width / 2; val h = if (planeIndex == 0) height else height / 2
                        val start = plane.buffer.position()
                        for (y in 0 until h) for (x in 0 until w) {
                            val ink = if (frame < count / 2) x in 20..132 && y in 22..66 && x % 10 < 4
                                else x in 20..132 && y in 22..66 && y % 8 < 3
                            val value = if (planeIndex != 0) 128 else if (ink) 16 else 220
                            plane.buffer.put(start + y * plane.rowStride + x * plane.pixelStride, value.toByte())
                        }
                    }
                    codec.queueInputBuffer(index, 0, width * height * 3 / 2, frame * 1000000L / fps, 0)
                }
                drain(false)
            }
            drain(true)
        } finally { runCatching { codec.stop() }; codec.release(); if (started) muxer.stop(); muxer.release() }
        check(file.length() > 0)
    }
}
