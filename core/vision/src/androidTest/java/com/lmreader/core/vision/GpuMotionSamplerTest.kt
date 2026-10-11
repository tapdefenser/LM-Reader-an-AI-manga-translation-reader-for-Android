package com.lmreader.core.vision

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

class GpuMotionSamplerTest {
    @Test fun importedBuffersPreserveOrientationAndCanFallBackWithoutLosingAFrame() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val reader = ImageReader.newInstance(384, 512, PixelFormat.RGBA_8888, 3,
            HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val sampler = GpuMotionSampler()
        try {
            repeat(8) { iteration ->
                val canvas = reader.surface.lockCanvas(null)
                canvas.drawColor(Color.WHITE)
                val paint = Paint().apply { color = Color.rgb(20 + iteration * 10, 70, 150) }
                canvas.drawRect(20f, 35f, 270f, 220f, paint)
                paint.color = Color.BLACK; canvas.drawRect(110f, 310f, 320f, 450f, paint)
                reader.surface.unlockCanvasAndPost(canvas)
                var image: android.media.Image? = null
                withTimeout(2000) { while (image == null) { image = reader.acquireLatestImage(); if (image == null) delay(5) } }
                image!!.use { input ->
                    val width = 96; val height = 128
                    var reference: ByteArray? = null
                    fun cpu(): ByteArray {
                        val plane = input.planes[0]; val buffer = plane.buffer.duplicate()
                        return ByteArray(width * height) { i ->
                            var sum = 0
                            for (v in 0..3) for (u in 0..3) {
                                val x = ((i % width + (u + .5f) / 4) * input.width / width).toInt()
                                val y = ((i / width + (v + .5f) / 4) * input.height / height).toInt()
                                val p = y * plane.rowStride + x * plane.pixelStride
                                sum += ((buffer.get(p).toInt() and 255) * 77 + (buffer.get(p + 1).toInt() and 255) * 150 +
                                    (buffer.get(p + 2).toInt() and 255) * 29) shr 8
                            }
                            (sum / 16).toByte()
                        }.also { reference = it }
                    }
                    val gray = sampler.sample(input, width, height, ::cpu)
                    val expected = reference ?: cpu()
                    assertEquals(expected.size, gray.size)
                    val error = gray.indices.sumOf { abs((gray[it].toInt() and 255) - (expected[it].toInt() and 255)).toLong() }.toDouble() / gray.size
                    assertTrue("buffer=$iteration backend=${sampler.backend} error=$error", error <= 2)
                    assertTrue("The top and bottom of the imported image must not flip", (gray[0].toInt() and 255) > 240)
                }
            }
        } finally { sampler.close(); reader.close() }
    }
}
