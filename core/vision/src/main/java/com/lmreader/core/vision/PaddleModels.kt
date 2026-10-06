package com.lmreader.core.vision

import ai.onnxruntime.*
import android.graphics.*
import com.lmreader.core.model.*
import java.nio.FloatBuffer
import java.io.File
import android.util.Log
import kotlin.math.*

/** Paddle 的 BGR/NCHW 输入与 CTC 规则参考上游；不继承其吞掉推理异常的行为。 */
internal class PaddleModel(private val models: VisionModels, private val name: String,
    preference: OcrBackend = OcrBackend.CPU, private val report: (String) -> Unit = {}) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private data class Backend(val label: String, val provider: String?, val device: OrtEpDevice? = null,
        val qnnType: String? = null)
    private data class Session(val ort: OrtSession, val backend: Backend, var verified: Boolean = false)
    private val candidates = buildList {
        if (OcrBackend.QNN in preference.fallbackOrder) {
            try {
                val devices = QnnAvailability.devices(models)
                for ((type, label, option) in listOf(
                    Triple(OrtHardwareDevice.OrtHardwareDeviceType.NPU, "QNN NPU", "htp"),
                    Triple(OrtHardwareDevice.OrtHardwareDeviceType.GPU, "QNN GPU", "gpu"))) {
                    // The QNN plugin may advertise only one NPU EP device. The
                    // backend_type option still selects its separate GPU runtime.
                    (devices.firstOrNull { it.device.type == type }
                        ?: devices.firstOrNull().takeIf { option == "gpu" })?.let {
                        add(Backend(label, "QNNExecutionProvider", it, option))
                    }
                }
            } catch (failure: Exception) {
                Log.i("OcrAcceleration", "QNN unavailable: ${failure.message}")
                if (preference == OcrBackend.QNN) report("OCR QNN 不可用，已回退 CPU：${failure.message}")
            } catch (failure: LinkageError) {
                Log.i("OcrAcceleration", "QNN library unavailable: ${failure.message}")
                if (preference == OcrBackend.QNN) report("OCR QNN 运行库不可用，已回退 CPU：${failure.message}")
            }
            if (isEmpty() && preference == OcrBackend.QNN) report("OCR QNN 没有提供硬件设备，已回退 CPU")
        }
        if (OcrBackend.NNAPI in preference.fallbackOrder) {
            val reason = NnapiAvailability.unavailableReason
            if (reason == null) add(Backend("NNAPI", "NnapiExecutionProvider"))
            else if (isEmpty()) report("OCR 硬件加速不可用，已回退 CPU：$reason")
        }
        add(Backend("ONNX Runtime CPU", null))
    }.toMutableList()
    private var session = nextSession()
    private var wideLineSession: OrtSession? = null
    private val hardwareShape = if (name == "det.onnx") longArrayOf(1,3,960,960) else longArrayOf(1,3,48,320)
    private fun createSession(backend: Backend) = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
        if (backend.provider != null) {
            options.setSymbolicDimensionValue("DynamicDimension.0", 1)
            options.setSymbolicDimensionValue("DynamicDimension.1", if (name == "det.onnx") 960 else 320)
            if (name == "det.onnx") options.setSymbolicDimensionValue("DynamicDimension.2", 960)
            options.enableProfiling(models.profilePrefix())
            if (backend.device != null) {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                val configuration = mutableMapOf("backend_type" to backend.qnnType!!)
                if (backend.qnnType == "htp") configuration["htp_performance_mode"] = "balanced"
                options.addExecutionProvider(listOf(backend.device), configuration)
            } else options.addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED))
        }
        environment.createSession(models.file(name).absolutePath,options)
    }
    private fun nextSession(): Session {
        while (true) {
            val backend = candidates.removeAt(0)
            try { return Session(createSession(backend), backend) }
            catch (failure: Exception) {
                if (backend.provider == null) throw failure
                report("OCR ${backend.label} 初始化失败，尝试其他后端：${failure.message}")
            } catch (failure: LinkageError) {
                if (backend.provider == null) throw failure
                report("OCR ${backend.label} 运行库不可用，尝试其他后端：${failure.message}")
            }
        }
    }
    val inputShape: LongArray
    val backend get() = when {
        session.backend.provider == null -> session.backend.label
        !session.verified -> "${session.backend.label}（待验证）"
        else -> "${session.backend.label} / CPU"
    }
    private val inputName: String
    init {
        try {
            require(session.ort.inputNames.size == 1 && session.ort.outputNames.size == 1) { "OCR 模型输入/输出数量不正确" }
            inputName = session.ort.inputNames.single()
            val info = session.ort.inputInfo.getValue(inputName).info as TensorInfo
            require(info.type == OnnxJavaType.FLOAT && info.shape.size == 4 && info.shape[1] == 3L) { "OCR 输入必须为 FLOAT NCHW" }
            inputShape = info.shape.copyOf().also {
                // Keep long lines at their original resolution; the static 320-wide
                // accelerator graph is only used for inputs that fit it.
                if (name != "det.onnx") it[3] = -1
            }
        } catch (failure: Throwable) { session.ort.close(); throw failure }
    }
    fun <T> run(input: FloatArray, shape: LongArray, read: (FloatBuffer,LongArray) -> T): T {
        if (session.backend.provider != null && !shape.contentEquals(hardwareShape)) {
            val cpu = wideLineSession ?: createSession(Backend("ONNX Runtime CPU", null)).also { wideLineSession = it }
            return invoke(cpu, input, shape, read)
        }
        while (true) {
            try {
                val result = invoke(session.ort, input, shape, read)
                if (session.backend.provider != null && !session.verified) {
                    val profile = File(session.ort.endProfiling())
                    val count = try { hardwareNodeCount(profile.readText(), session.backend.provider!!) }
                        finally { profile.delete() }
                    check(count > 0) { "模型没有运算进入 ${session.backend.label}" }
                    session.verified = true
                    Log.i("OcrAcceleration", "$name ${session.backend.label}: $count hardware execution events")
                }
                return result
            } catch (failure: Exception) {
                if (session.backend.provider == null) throw failure
                fallback(failure)
            } catch (failure: LinkageError) {
                if (session.backend.provider == null) throw failure
                fallback(failure)
            }
        }
    }
    private fun fallback(failure: Throwable) {
        report("OCR ${session.backend.label} 推理失败，尝试其他后端：${failure.message}")
        closeSession()
        session = nextSession()
    }
    private fun closeSession() {
        if (session.backend.provider != null && !session.verified) {
            try { File(session.ort.endProfiling()).delete() } catch (_: Exception) { }
        }
        session.ort.close()
    }
    private fun <T> invoke(session: OrtSession, input: FloatArray, shape: LongArray, read: (FloatBuffer,LongArray) -> T): T =
        OnnxTensor.createTensor(environment,FloatBuffer.wrap(input),shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result[0] as? OnnxTensor ?: error("OCR 输出不是 Tensor")
                require(output.info.type == OnnxJavaType.FLOAT) { "OCR 输出必须为 FLOAT" }
                read(output.floatBuffer,output.info.shape)
            }
        }
    override fun close() {
        try { closeSession() } finally { wideLineSession?.close() }
    }
}

internal class PaddleDetector(models: VisionModels, preference: OcrBackend = OcrBackend.CPU, report: (String) -> Unit = {}) : AutoCloseable {
    private val model = PaddleModel(models,"det.onnx",preference,report)
    val backend get() = model.backend
    fun detect(image: Bitmap, scoreThreshold: Float = .45f): List<Pair<PixelRect,Float>> {
        val width = model.inputShape[3].takeIf { it > 0 }?.toInt() ?: 960
        val height = model.inputShape[2].takeIf { it > 0 }?.toInt() ?: 960
        val transform = Letterbox(image.width,image.height,width,height)
        val resized = Bitmap.createScaledBitmap(image,transform.contentWidth,transform.contentHeight,true)
        val pixels = IntArray(resized.width*resized.height)
        try { resized.getPixels(pixels,0,resized.width,0,0,resized.width,resized.height) }
        finally { if (resized !== image) resized.recycle() }
        val input = FloatArray(3*width*height)
        val means = floatArrayOf(.485f,.456f,.406f); val std = floatArrayOf(.229f,.224f,.225f)
        // Match upstream's black RGB letterbox before normalization, rather than leaving
        // a normalized gray border that the detector can mistake for a text rectangle.
        for (c in 0..2) input.fill(-means[c]/std[c], c*width*height, (c+1)*width*height)
        for (y in 0 until transform.contentHeight) for (x in 0 until transform.contentWidth) {
            val pixel = pixels[y*transform.contentWidth+x]
            val index = (y+transform.top)*width+x+transform.left
            for (c in 0..2) input[c*width*height+index] = (((pixel ushr (8*c)) and 255)/255f-means[c])/std[c]
        }
        return model.run(input,longArrayOf(1,3,height.toLong(),width.toLong())) { buffer,shape ->
            require(shape.size == 4 && shape[0] == 1L && shape[1] == 1L && shape[2] > 0 && shape[3] > 0) {
                "文字检测输出必须为 [1,1,H,W]，实际为 ${shape.contentToString()}"
            }
            dbBoxes(buffer,shape[3].toInt(),shape[2].toInt(),transform,scoreThreshold)
        }
    }
    override fun close() = model.close()
}

internal class PaddleRecognizer(models: VisionModels, korean: Boolean, preference: OcrBackend = OcrBackend.CPU, report: (String) -> Unit = {}) : AutoCloseable {
    private val characters = models.characters(if (korean) "ko.txt" else "rec.txt")
    private val model = PaddleModel(models,if (korean) "ko.onnx" else "rec.onnx",preference,report)
    val backend get() = model.backend
    fun recognize(image: Bitmap): DecodedText {
        val height = model.inputShape[2].takeIf { it > 0 }?.toInt() ?: 48
        val needed = ceil(height.toDouble()*image.width/image.height).toInt().coerceIn(1,2048)
        val width = model.inputShape[3].takeIf { it > 0 }?.toInt() ?: max(320,((needed+31)/32)*32)
        val content = min(needed,width)
        val resized = Bitmap.createScaledBitmap(image,content,height,true)
        val pixels = IntArray(content*height)
        try { resized.getPixels(pixels,0,content,0,0,content,height) }
        finally { if (resized !== image) resized.recycle() }
        val input = FloatArray(3*height*width)
        for (y in 0 until height) for (x in 0 until content) {
            val pixel = pixels[y*content+x]
            for (c in 0..2) input[c*height*width+y*width+x] = (((pixel ushr (8*c)) and 255)/255f-.5f)*2
        }
        return model.run(input,longArrayOf(1,3,height.toLong(),width.toLong())) { buffer,shape -> decodeCtc(buffer,shape,characters) }
    }
    override fun close() = model.close()
}

internal fun crop(source: Bitmap, rect: PixelRect): Bitmap {
    val left = floor(rect.left).toInt().coerceIn(0,source.width-1)
    val top = floor(rect.top).toInt().coerceIn(0,source.height-1)
    val right = ceil(rect.right).toInt().coerceIn(left+1,source.width)
    val bottom = ceil(rect.bottom).toInt().coerceIn(top+1,source.height)
    return Bitmap.createBitmap(source,left,top,right-left,bottom-top)
}

internal fun rotateCounterClockwise(source: Bitmap): Bitmap = Bitmap.createBitmap(source,0,0,source.width,source.height,
    Matrix().apply { setRotate(-90f) },true)

/** 竖排候选：按空白行拆字并横向拼接，保留字本身方向；与上游旋转候选比较置信度。 */
internal fun horizontalGlyphStrip(source: Bitmap): Bitmap? {
    val pixels = IntArray(source.width*source.height)
    source.getPixels(pixels,0,source.width,0,0,source.width,source.height)
    var minX = source.width; var maxX = -1
    val inkRows = BooleanArray(source.height)
    pixels.forEachIndexed { i,p ->
        val gray = (((p ushr 16) and 255)+((p ushr 8) and 255)+(p and 255))/3
        if (gray < 180) { inkRows[i/source.width] = true; minX = min(minX,i%source.width); maxX = max(maxX,i%source.width) }
    }
    if (maxX < minX) return null
    val glyphWidth = maxX-minX+1; val spans = ArrayList<IntRange>()
    var start = -1; var last = -1
    for (y in inkRows.indices) {
        if (inkRows[y]) {
            if (start < 0) start = y
            else if (y-last > max(2,glyphWidth/7)) { spans += start..last; start = y }
            last = y
        }
    }
    if (start >= 0) spans += start..last
    if (spans.size < 2 || spans.size > 80 || spans.any { it.count() < glyphWidth*.4f || it.count() > glyphWidth*1.8f }) return null
    val cell = max(glyphWidth,spans.maxOf { it.count() })+6
    val strip = Bitmap.createBitmap(cell*spans.size,cell,Bitmap.Config.ARGB_8888)
    val canvas = Canvas(strip); canvas.drawColor(Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    spans.forEachIndexed { i,span ->
        val y = (cell-span.count())/2f
        canvas.drawBitmap(source,Rect(minX,span.first,maxX+1,span.last+1),
            RectF(i*cell+3f,y,i*cell+3f+glyphWidth,y+span.count()),paint)
    }
    return strip
}
