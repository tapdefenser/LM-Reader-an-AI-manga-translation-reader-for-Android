package com.lmreader.core.vision

import android.content.Context
import android.graphics.*
import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * 独立 Seg/OCR 组件。调用者在挂起调用结束前保持 bitmap 有效，不持有、不写回原图。
 * 单个实例内部串行，由独立实例池提供 Seg/OCR 并发；取消在本次 native 调用返回后生效。
 */
internal class LocalVisionSession(context: Context, private val settings: VisionExecutionSettings, private val report: (String) -> Unit,
    private val resourcesChanged: (List<LoadedEngineResource>) -> Unit = {}) {
    private val context = context.applicationContext
    private val mutex = Mutex()
    private val models = VisionModels(context)
    private var segmenter: TfliteDualModel? = null
    private var detector: PaddleDetector? = null
    private var recognizer: PaddleRecognizer? = null
    private var koreanRecognizer: PaddleRecognizer? = null
    private fun publishResources() = resourcesChanged(buildList {
        segmenter?.let { add(LoadedEngineResource("seg", InferenceEngineKind.SEG, "气泡分割模型", it.backend)) }
        detector?.let { add(LoadedEngineResource("det", InferenceEngineKind.OCR, "文字检测模型", it.backend)) }
        recognizer?.let { add(LoadedEngineResource("rec", InferenceEngineKind.OCR, "中日与拉丁文字识别模型", it.backend)) }
        koreanRecognizer?.let { add(LoadedEngineResource("ko", InferenceEngineKind.OCR, "韩文识别模型", it.backend)) }
    })

    suspend fun segment(imageId: String, image: Bitmap, threshold: Float = .35f,
                        progress: (VisionProgress) -> Unit = {}): SegResult = execute("气泡分割") {
        validate(imageId,image); require(threshold in 0f..1f)
        val started = System.nanoTime()
        progress(VisionProgress("加载 Seg 模型",0,1))
        val model = segmenter ?: TfliteDualModel(context,models.asset("seg.tflite"),2,settings.segGpu,report).also { segmenter = it; publishResources() }
        val tiles = planTiles(image.width,image.height); val regions = ArrayList<SegRegion>()
        for ((index,tile) in tiles.withIndex()) {
            coroutineContext.ensureActive(); progress(VisionProgress("气泡分割",index,tiles.size))
            val source = Bitmap.createBitmap(image,tile.x,tile.y,tile.width,tile.height)
            try {
                val transform = Letterbox(tile.width,tile.height,model.inputWidth,model.inputHeight)
                val prepared = Bitmap.createBitmap(model.inputWidth,model.inputHeight,Bitmap.Config.ARGB_8888)
                try {
                    Canvas(prepared).apply {
                        drawColor(Color.rgb(114,114,114))
                        drawBitmap(source,null,Rect(transform.left,transform.top,transform.left+transform.contentWidth,
                            transform.top+transform.contentHeight),Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                    model.run(prepared)
                } finally { prepared.recycle() }
                coroutineContext.ensureActive()
                requireFiniteTensor(model.detections,"Seg 检测头")
                requireFiniteTensor(model.prototypes,"Seg 掩码")
                for (raw in decodeSeg(model.detections,model.anchorCount,model.inputWidth,model.inputHeight,threshold)) {
                    val bounds = transform.rect(raw.bounds).offset(tile.x.toFloat(),tile.y.toFloat())
                    if (bounds.width <= 1 || bounds.height <= 1) continue
                    val contours = segContours(raw,model.prototypes,model.protoWidth,model.protoHeight,transform)
                        .map { points -> points.map { PixelPoint(it.x+tile.x,it.y+tile.y) } }
                    if (contours.size > 1) contours.forEach { contour ->
                        regions += SegRegion("", RegionKind.BUBBLE, contourBounds(contour), raw.confidence, contour)
                    } else regions += SegRegion("",if (raw.classId == 0) RegionKind.BUBBLE else RegionKind.FREE_TEXT,
                        bounds,raw.confidence,contours.firstOrNull().orEmpty())
                }
            } finally { if (source !== image) source.recycle() }
        }
        val kept = keepSegRegions(regions)
        progress(VisionProgress("气泡分割完成",tiles.size,tiles.size))
        SegResult(imageId,image.width,image.height,kept.mapIndexed { i,r -> r.copy(id="$imageId:seg:$i") },
            (System.nanoTime()-started)/1_000_000,model.backend)
    }

    suspend fun detectTextLines(imageId: String, image: Bitmap, regions: List<PixelRect>? = null,
        vertical: Boolean = true, progress: (VisionProgress) -> Unit = {}): List<DetectedTextLine> = execute("文字检测") {
        validate(imageId,image)
        val areas = checkedAreas(image,regions)
        detectTextBoxes(image,areas,vertical,.45f,progress).map { (bounds,confidence) -> DetectedTextLine(bounds,confidence) }
    }

    /** Small text loses strokes at page scale. Refine its lines inside SEG, in the OCR pool. */
    suspend fun detectSegTextLines(seg: SegResult, image: Bitmap,
        scoreThreshold: Float = .45f,
        progress: (VisionProgress) -> Unit = {}): List<DetectedTextLine> = execute("文字检测") {
        validate(seg.imageId, image)
        require(scoreThreshold.isFinite() && scoreThreshold in 0f..1f)
        val page = detectTextBoxes(image, checkedAreas(image, null), true, scoreThreshold, progress)
            .map { (bounds, confidence) -> DetectedTextLine(bounds, confidence) }
        val regions = textRefinementRegions(seg.copy(textLines = page))
        val refined = ArrayList<DetectedTextLine>()
        for (region in regions) {
            coroutineContext.ensureActive()
            cropSegRegion(image, region, seg.regions).use { crop ->
                val local = detectTextBoxes(crop.bitmap, checkedAreas(crop.bitmap, null), true, scoreThreshold, progress)
                    .map { (bounds, confidence) -> DetectedTextLine(bounds.offset(crop.left.toFloat(), crop.top.toFloat()), confidence) }
                refined += selectRegionTextLines(region, seg.regions, local)
            }
        }
        val lines = keepCompleteRegions(page + refined, { it.bounds }, { it.confidence })
        require(lines.size <= 1000) { "文字行过多，请分批处理图片" }
        lines
    }

    /** The OCR workflow step only recognizes the lines supplied by SEG. */
    suspend fun recognize(imageId: String, image: Bitmap, language: LocalOcrLanguage,
                          regions: List<PixelRect>?, textLines: List<DetectedTextLine>,
                          progress: (VisionProgress) -> Unit = {}): LocalOcrResult = execute("本地 OCR") {
        validate(imageId,image)
        val started = System.nanoTime()
        progress(VisionProgress("加载 OCR 模型",0,1))
        val recognizer = if (language == LocalOcrLanguage.KOREAN) koreanRecognizer ?: PaddleRecognizer(models,true,settings.ocrBackend,report).also { koreanRecognizer = it; publishResources() }
            else recognizer ?: PaddleRecognizer(models,false,settings.ocrBackend,report).also { recognizer = it; publishResources() }
        val cjk = language in setOf(LocalOcrLanguage.JAPANESE,LocalOcrLanguage.CHINESE_SIMPLIFIED,LocalOcrLanguage.CHINESE_TRADITIONAL)
        checkedAreas(image,regions)
        val boxes = textLines.map { it.bounds to it.confidence }
        require(boxes.size <= 1000 && boxes.all { (bounds, _) -> bounds.area > 0 && bounds.left >= 0 && bounds.top >= 0 && bounds.right <= image.width && bounds.bottom <= image.height }) { "文字行必须在当前图片内，最多 1000 个" }
        val lines = ArrayList<OcrLine>()
        for ((index,box) in boxes.withIndex()) {
            coroutineContext.ensureActive(); progress(VisionProgress("文字识别",index,boxes.size))
            val line = crop(image,box.first)
            try {
                val decoded = if (cjk && box.first.height > box.first.width*1.5f) {
                    val rotated = rotateCounterClockwise(line)
                    var best = try { recognizer.recognize(rotated) } finally { if (rotated !== line) rotated.recycle() }
                    coroutineContext.ensureActive()
                    horizontalGlyphStrip(line)?.let { strip ->
                        val upright = try { recognizer.recognize(strip) } finally { strip.recycle() }
                        if (upright.confidence > best.confidence) best = upright
                    }
                    best
                } else recognizer.recognize(line)
                if (decoded.text.isNotBlank()) lines += OcrLine("",box.first,decoded.text,decoded.confidence)
            } finally { if (line !== image) line.recycle() }
        }
        coroutineContext.ensureActive(); progress(VisionProgress("OCR 完成",boxes.size,boxes.size))
        LocalOcrResult(imageId,image.width,image.height,language,readingOrder(lines,language).mapIndexed { i,l -> l.copy(id="$imageId:ocr:$i") },
            (System.nanoTime()-started)/1_000_000)
    }

    /** SEG detects text lines once; workflow OCR receives those boxes and only recognizes text. */
    private suspend fun detectTextBoxes(image: Bitmap, areas: List<PixelRect>, vertical: Boolean, scoreThreshold: Float,
        progress: (VisionProgress) -> Unit): List<Pair<PixelRect, Float>> {
        progress(VisionProgress("加载文字检测模型",0,1))
        val model = detector ?: PaddleDetector(models,settings.ocrBackend,report).also { detector = it; publishResources() }
        val candidates = ArrayList<Pair<PixelRect,Float>>()
        for ((areaIndex,area) in areas.withIndex()) {
            val areaImage = crop(image,area)
            val areaX = kotlin.math.floor(area.left); val areaY = kotlin.math.floor(area.top)
            try {
                val tiles = planTiles(areaImage.width,areaImage.height)
                for ((index,tile) in tiles.withIndex()) {
                    coroutineContext.ensureActive(); progress(VisionProgress("文字行检测 ${areaIndex+1}/${areas.size}",index,tiles.size))
                    val source = Bitmap.createBitmap(areaImage,tile.x,tile.y,tile.width,tile.height)
                    try {
                        var boxes = model.detect(source,scoreThreshold)
                        if (vertical) {
                            coroutineContext.ensureActive()
                            val rotated = rotateCounterClockwise(source)
                            val upright = try {
                                model.detect(rotated,scoreThreshold).map { (r,score) ->
                                    PixelRect(source.width-r.bottom,r.left,source.width-r.top,r.right) to score
                                }.filter { it.first.height > it.first.width*1.5f }
                            } finally { if (rotated !== source) rotated.recycle() }
                            boxes = boxes.filter { b -> upright.none { overlap(it.first,b.first)/b.first.area > .65f } } + upright
                        }
                        boxes.forEach { (b,score) ->
                            // DB can predict a full-page box from letterbox padding on a blank image.
                            // A uniformly colored crop has no text; test original pixels before adding it.
                            if (hasPixelVariation(source,b)) candidates += b.offset(tile.x+areaX,tile.y+areaY) to score
                        }
                    } finally { if (source !== areaImage) source.recycle() }
                }
            } finally { if (areaImage !== image) areaImage.recycle() }
        }
        val boxes = keepCompleteRegions(candidates,{ it.first },{ it.second })
        require(boxes.size <= 1000) { "文字行过多，请分批处理图片" }
        return boxes
    }

    /** 可在工作流空闲或内存紧张时释放；下次调用重新加载，不关闭进程共享 OrtEnvironment。 */
    suspend fun releaseModels() = withContext(Dispatchers.IO) {
        mutex.withLock {
            segmenter?.close(); segmenter = null
            detector?.close(); detector = null
            recognizer?.close(); recognizer = null
            koreanRecognizer?.close(); koreanRecognizer = null
            publishResources()
        }
    }
    suspend fun retainModels(needSeg: Boolean, needTextDetection: Boolean, languages: Set<LocalOcrLanguage>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!needSeg) { segmenter?.close(); segmenter = null }
            if (!needTextDetection) { detector?.close(); detector = null }
            if (languages.none { it != LocalOcrLanguage.KOREAN }) { recognizer?.close(); recognizer = null }
            if (LocalOcrLanguage.KOREAN !in languages) { koreanRecognizer?.close(); koreanRecognizer = null }
            publishResources()
        }
    }
    private suspend fun <T> execute(stage: String, action: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { coroutineContext.ensureActive(); action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: LocalVisionException) { throw failure }
            catch (failure: Exception) { throw LocalVisionException("$stage 失败：${failure.message ?: failure.javaClass.simpleName}",failure) }
            catch (failure: LinkageError) { throw LocalVisionException("$stage 的本地运行库不可用：${failure.message}",failure) }
            finally { publishResources() }
        }
    }
    private fun checkedAreas(image: Bitmap, regions: List<PixelRect>?): List<PixelRect> {
        val areas = regions ?: listOf(PixelRect(0f,0f,image.width.toFloat(),image.height.toFloat()))
        require(areas.size <= 300 && areas.all { it.left >= 0 && it.top >= 0 && it.right <= image.width && it.bottom <= image.height && it.area > 0 }) {
            "文字区域必须在当前图片内，最多 300 个"
        }
        return areas
    }
    private fun hasPixelVariation(image: Bitmap, bounds: PixelRect): Boolean {
        val left = kotlin.math.floor(bounds.left).toInt().coerceIn(0,image.width-1)
        val top = kotlin.math.floor(bounds.top).toInt().coerceIn(0,image.height-1)
        val right = kotlin.math.ceil(bounds.right).toInt().coerceIn(left+1,image.width)
        val bottom = kotlin.math.ceil(bounds.bottom).toInt().coerceIn(top+1,image.height)
        val row = IntArray(right-left)
        val first = image.getPixel(left,top) and 0xffffff
        for(y in top until bottom) {
            image.getPixels(row,0,row.size,left,y,row.size,1)
            if(row.any { (it and 0xffffff) != first }) return true
        }
        return false
    }
    private fun validate(imageId: String, image: Bitmap) {
        require(imageId.isNotBlank() && !image.isRecycled && image.width > 1 && image.height > 1)
        require(image.config != Bitmap.Config.HARDWARE) { "请使用软件 Bitmap" }
        require(image.width.toLong()*image.height <= 16_000_000L) { "图片超过 1600 万像素，请缩小或分割后再处理" }
    }
}
