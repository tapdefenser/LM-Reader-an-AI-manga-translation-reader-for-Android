package com.lmreader.core.vision

import android.hardware.HardwareBuffer
import android.media.Image
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.math.abs

/** GLES preprocessing is optional. Import, fence wait and readback are included in its device trial. */
class GpuMotionSampler : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "screen-motion-gles") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var handle = 0L
    private var disabled = Build.VERSION.SDK_INT < 33
    private var trials = 0
    private var gpuNanos = 0L
    private var cpuNanos = 0L
    @Volatile private var closed = false
    @Volatile var backend = "CPU"
        private set
    suspend fun sample(image: Image, width: Int, height: Int, cpu: () -> ByteArray): ByteArray {
        if (closed || disabled) return cpu()
        val requested = System.nanoTime()
        return try { withContext(dispatcher) {
            if (closed || disabled) return@withContext cpu()
            val started = requested
            val result = try {
                if (handle == 0L) { check(loaded); handle = nativeCreate(); check(handle != 0L) }
                if (Build.VERSION.SDK_INT < 33) return@withContext cpu()
                image.fence.use { fence -> check(!fence.isValid || fence.await(Duration.ofMillis(50))) }
                checkNotNull(image.hardwareBuffer).use { buffer -> nativeSample(handle, buffer, width, height) }
            } catch (_: Exception) { null }
            val elapsed = System.nanoTime() - started
            if (result == null) {
                disabled = true; backend = "CPU (GLES unavailable)"; return@withContext cpu()
            }
            if (trials < 5) {
                val cpuStarted = System.nanoTime(); val reference = cpu(); val cpuElapsed = System.nanoTime() - cpuStarted
                if (result.size != reference.size || result.indices.sumOf { abs((result[it].toInt() and 255) - (reference[it].toInt() and 255)).toLong() } > result.size * 2L) {
                    disabled = true; backend = "CPU (GLES validation failed)"; return@withContext reference
                }
                if (trials >= 2) { gpuNanos += elapsed; cpuNanos += cpuElapsed }
                trials++
                if (trials == 5) {
                    disabled = gpuNanos >= cpuNanos * .9
                    backend = if (disabled) "CPU (faster on this device)" else "GLES"
                    Log.i("ScreenTranslation", "motion preprocessing=$backend trialGPU=${gpuNanos / 3_000_000.0}ms trialCPU=${cpuNanos / 3_000_000.0}ms")
                }
                reference // use one consistent sampling kernel during calibration
            } else result
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { disabled = true; backend = "CPU (GLES unavailable)"; cpu() }
    }
    override fun close() {
        if (closed) return
        closed = true
        executor.execute { if (handle != 0L) { nativeClose(handle); handle = 0 }; dispatcher.close() }
    }
    private external fun nativeCreate(): Long
    private external fun nativeSample(handle: Long, buffer: HardwareBuffer, width: Int, height: Int): ByteArray?
    private external fun nativeClose(handle: Long)
    companion object {
        private val loaded = runCatching { System.loadLibrary("lmreader_vision"); true }.getOrDefault(false)
    }
}
