package com.lmreader.ui.settings.vision

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lmreader.core.model.*
import com.lmreader.core.vision.LocalVisionEngine
import com.lmreader.core.vision.VisionImageDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

internal enum class VisionTestMode(val label: String) { OCR("本地 OCR"), SEG("Seg 气泡与文字检测"), BOTH("Seg + OCR") }
internal data class LocalVisionUiState(
    val image: Bitmap? = null, val imageId: String = "", val imageLabel: String = "",
    val language: LocalOcrLanguage = LocalOcrLanguage.ENGLISH, val mode: VisionTestMode = VisionTestMode.BOTH,
    val running: Boolean = false, val cancelling: Boolean = false, val progress: VisionProgress? = null,
    val segmentation: SegResult? = null, val ocr: LocalOcrResult? = null, val error: String? = null,
)

internal class LocalVisionViewModel(private val engine: LocalVisionEngine, private val context: Context): ViewModel() {
    private val mutable = MutableStateFlow(LocalVisionUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun language(value: LocalOcrLanguage) { if (!state.value.running) mutable.update { it.copy(language=value,ocr=null,error=null) } }
    fun mode(value: VisionTestMode) { if (!state.value.running) mutable.update { it.copy(mode=value,error=null) } }
    fun demo(sample: VisionDemo) {
        if (state.value.running) return
        load {
            Triple(VisionDemoImages.create(sample),sample.label,sample.language)
        }
    }
    fun image(uri: Uri) {
        if (state.value.running) return
        load {
            val bitmap = VisionImageDecoder.decode(context,uri)
            Triple(bitmap,"选取的图片（方向已校正；大图按需缩小）",state.value.language)
        }
    }
    private fun load(action: () -> Triple<Bitmap,String,LocalOcrLanguage>) {
        mutable.update { it.copy(running=true,error=null,progress=VisionProgress("准备图片",0,1),segmentation=null,ocr=null) }
        job = viewModelScope.launch {
            var loaded: Bitmap? = null
            try {
                // NonCancellable 保证解码完成后位图可以交回当前协程进行清理，避免取消时泄漏。
                val result = withContext(Dispatchers.IO + NonCancellable) { action().also { loaded=it.first } }
                ensureActive()
                // 已发布给 Compose 的 bitmap 交给 GC；手动 recycle 会与退出动画/上一帧绘制竞争。
                loaded=null
                mutable.update { it.copy(image=result.first,imageId=UUID.randomUUID().toString(),imageLabel=result.second,
                    language=result.third,running=false,progress=null,cancelling=false) }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(running=false,cancelling=false,progress=null,error="已取消") }; throw cancelled
            } catch (failure: Exception) { mutable.update { it.copy(running=false,cancelling=false,progress=null,error="图片读取失败：${failure.message}") } }
            finally { loaded?.recycle() }
        }
    }
    fun run() {
        val snapshot = state.value; val image = snapshot.image ?: return
        if (snapshot.running) return
        mutable.update { it.copy(running=true,cancelling=false,error=null,ocr=null,segmentation=null) }
        job = viewModelScope.launch {
            try {
                val progress: (VisionProgress) -> Unit = { value -> mutable.update { it.copy(progress=value) } }
                if (snapshot.mode != VisionTestMode.OCR) {
                    val result = engine.segment(snapshot.imageId,image,progress=progress)
                    ensureActive(); mutable.update { it.copy(segmentation=result) }
                }
                if (snapshot.mode != VisionTestMode.SEG) {
                    val result = engine.recognize(snapshot.imageId,image,snapshot.language,textLines=state.value.segmentation?.textLines,progress=progress)
                    ensureActive(); mutable.update { it.copy(ocr=result) }
                }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(error="已取消",segmentation=null,ocr=null) }; throw cancelled
            } catch (failure: Exception) { mutable.update { it.copy(error=failure.message ?: "本地组件测试失败") } }
            finally { mutable.update { it.copy(running=false,cancelling=false,progress=null) } }
        }
    }
    fun cancel() { if (state.value.running) { mutable.update { it.copy(cancelling=true) }; job?.cancel() } }
}
