package com.lmreader.screen

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

internal class ScreenPerformanceTrace(private val sequence: Long, private val startedAt: Long) {
    private val stages = ConcurrentHashMap<String, LongAdder>()
    fun record(stage: String, milliseconds: Long) {
        stages.computeIfAbsent(stage) { LongAdder() }.add(milliseconds)
        Log.d("ScreenTranslation", "frame=$sequence stage=$stage duration=${milliseconds}ms")
    }
    fun summary(): String {
        fun ms(stage: String) = stages[stage]?.sum() ?: 0
        val total = (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0)
        return "本次 ${total}ms · 截图 ${ms("CAPTURE")} · 图像 ${ms("IMAGE")} · SEG/DET ${ms("DETECT")} · OCR ${ms("OCR")} · 机翻 ${ms("TRANSLATE")} · 排队 ${ms("QUEUE")} · 绘制 ${ms("RENDER")}ms".also {
            Log.i("ScreenTranslation", "frame=$sequence $it (parallel stage durations are cumulative)")
        }
    }
}
