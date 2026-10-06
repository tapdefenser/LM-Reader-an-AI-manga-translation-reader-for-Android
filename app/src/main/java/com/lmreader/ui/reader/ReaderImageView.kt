package com.lmreader.ui.reader

import android.graphics.BitmapFactory
import android.graphics.PointF
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.lmreader.core.model.ImageScaleType
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ZoomStart
import com.lmreader.core.model.BubbleRenderSettings
import com.lmreader.core.model.PageTranslatedRegion
import com.lmreader.core.model.renderGeometry
import com.lmreader.core.vision.BubbleOverlaySource
import com.lmreader.ui.reader.translation.ReaderPageTranslation
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 一页的渲染视口：由 Mihon 使用的图片引擎承担缩放、平移、分块解码与裁白边。
 *
 * ## 这一层是薄壳，这是刻意的
 *
 * Mihon 的 `ReaderPageImageView` 同样只做"设属性 + 读返回值"三件事：
 * `setMinimumScaleType` / `setCropBorders` / `setDoubleTapZoomStyle`、
 * `getPanRemaining`、`animateScaleAndCenter`。所有变换数学都在库里。
 * 因此本文件**不实现任何矩阵运算**——那部分抄不来，也不该重写。
 *
 * ## 为什么用 `ImageSource.inputStream` 而不是先解码成 Bitmap
 *
 * 传 Bitmap 会让库失去分块解码的能力（内存已经按整图付过一次了）。
 * 传流时库自己对文件做区域解码，超大跨页才不会 OOM——这正是本阶段之前手写采样解码
 * 想要、但做不到的那件事。
 *
 * ## 缩放类型映射（对照 Mihon `PagerPageHolder` → `ReaderPageImageView`）
 *
 * | 我们的值 | 库常量 |
 * |---|---|
 * | [ImageScaleType.FIT_SCREEN] | `SCALE_TYPE_CENTER_INSIDE` |
 * | [ImageScaleType.FIT_WIDTH] | `SCALE_TYPE_FIT_WIDTH` |
 * | [ImageScaleType.FIT_HEIGHT] | `SCALE_TYPE_FIT_HEIGHT` |
 * | [ImageScaleType.ORIGINAL_SIZE] | `SCALE_TYPE_CENTER_INSIDE` + 最小缩放 1（见下） |
 * | [ImageScaleType.STRETCH] | `SCALE_TYPE_FIT_XY` |
 * | [ImageScaleType.SMART_FIT] | 库没有对应值；按方向选宽度或高度（见 [smartFitScaleType]） |
 */
@Composable
internal fun EnginePageView(
    source: PageSource,
    page: ReaderPage,
    settings: ReaderSettings,
    modifier: Modifier = Modifier,
    onSingleTap: (x: Float, y: Float) -> Unit,
    onLongPress: (() -> Unit)? = null,
    onReady: () -> Unit = {},
    /** 页面字节的预取缓存；命中时不必再过一次 SAF。 */
    prefetcher: PagePrefetcher? = null,
    translation: ReaderPageTranslation? = null,
    regions: List<PageTranslatedRegion> = translation?.regions.orEmpty(),
    renderSettings: BubbleRenderSettings = BubbleRenderSettings(),
    showingOriginal: Boolean = false,
    editing: Boolean = false,
    selectedBubble: String? = null,
    onBubbleSelected: (String?) -> Unit = {},
) {
    // 视图实例随页面身份重建：库内部持有解码状态与瓦片缓存，复用实例会让上一页的
    // 缩放位置与瓦片残留到下一页（Mihon 在 `ReaderPageImageView.recycle()` 里显式清理
    // 同一个实例，我们选择更简单且不会串页的做法）。
    val context = LocalContext.current
    var view by remember(page.pageId) {
        mutableStateOf<TapAwareSubsamplingImageView?>(null)
    }
    var decodeFailed by remember(page.pageId) { mutableStateOf(false) }
    var overlaySource by remember(page.pageId) { mutableStateOf<BubbleOverlaySource?>(null) }
    val geometryRegions = translation?.regions?.map { it.region.renderGeometry() }

    // 解码目标（长边）：屏幕短边 × 4/3，与 EhViewer 同口径（见 [ReaderImageSampling]）。
    // 用屏幕尺寸而不是视图尺寸，是为了不必等布局——布局还没发生时就需要决定解码计划，
    // 而"等布局"正是上一版把解码拖到主线程上的原因之一。
    //
    // 「加载原图」打开时目标取 0：`planPageDecode` 对非正目标一律原样交流，
    // 也就是"一个像素都不减"。0 同时被用进下面的 LaunchedEffect 键与视图 tag，
    // 因此**切换开关会让当前页按新策略重新解码一次**，不必退出重进。
    val targetLongEdge = if (settings.loadOriginalImage) {
        0
    } else {
        remember(context.resources) {
            val metrics = context.resources.displayMetrics
            ReaderImageSampling.targetLongEdge(metrics.widthPixels, metrics.heightPixels)
        }
    }

    AndroidView(
        factory = { context ->
            TapAwareSubsamplingImageView(
                context = context,
                onSingleTap = onSingleTap,
                onLongPress = onLongPress,
            ).also { created ->
                // 解码完成之前视图是"什么都没有"，而分页器在滑动过程中就会把它画出来。
                // 不给底色的话，那一瞬间看到的是**下层内容透出来**（看起来就是"闪一下"）。
                // 给一个与阅读背景同色的不透明底色，同一帧里就是一块纯色，而不是穿帮。
                created.setBackgroundColor(android.graphics.Color.BLACK)
                configure(created, settings, onReady = { onReady() }, onError = { decodeFailed = true })
                view = created
            }
        },
        modifier = modifier.fillMaxSize(),
        // 等布局完成再载图。
        //
        // 尺寸为 0 时 `setImage` 的行为不可预期：真机日志里出现过
        // `setImage: 001.jpg view=0x0`，而库的瓦片初始化依赖视图尺寸。
        // 在那之前载图既可能白跑一次（随后尺寸变化还要重来），也是"同一页被解码
        // 多次"的一个来源，而每次解码都要一整张图的 ByteBuffer（见下）。
        update = { created ->
            view = created
            created.onSingleTap = onSingleTap
            created.onBubbleSelected = onBubbleSelected
            created.editing = editing && translation != null
            created.selectedBubble = selectedBubble
            created.showTranslation = !showingOriginal
            created.invalidate()
        },
    )

    // 载入这一页的图像流。
    //
    // 三件事必须同时做到：
    // 1. **等视图有尺寸再 setImage**：尺寸为 0 时库的瓦片初始化行为不可预期，
    //    真机日志里出现过 `setImage: 001.jpg view=0x0`；
    // 2. **同一页只 setImage 一次**：见下面关于"整图解码"的说明；
    // 3. **准备图源必须在 IO 上**：读文件头、必要时解码上千万像素，全是阻塞操作。
    //    上一版这段直接跑在组合的主线程上，真机实测翻页单帧 2300~3700ms。
    //
    // 流在协程里先准备好，布局回调只负责 `setImage`。
    LaunchedEffect(view, page.pageId, source, prefetcher, targetLongEdge, geometryRegions, translation?.sourceSha256, settings.effectiveCropBorders) {
        val target = view ?: return@LaunchedEffect
        val baseTag = "${page.pageId}|$targetLongEdge|${settings.effectiveCropBorders}"
        val loadTag = baseTag + if (settings.effectiveCropBorders) "|${geometryRegions.hashCode()}|${translation?.sourceSha256}" else ""
        target.setTag(IMAGE_REQUESTED_TAG,loadTag)
        decodeFailed = false
        var prepared: PreparedOverlayPage? = null
        var attached = false
        try {
            if (target.getTag(IMAGE_LOADED_TAG) == loadTag) {
                if (translation == null) {
                    target.overlay = null; overlaySource = null; target.invalidate()
                } else {
                    val seed = withContext(Dispatchers.IO) { prepareOverlaySource(context, source, page, translation) }
                    ensureActive()
                    // A loaded cropped bitmap already has offsets. Preserve them when
                    // only refreshing its mask, including after the PageSource changes.
                    if (!settings.effectiveCropBorders) {
                        target.overlayGeometry = com.lmreader.ui.reader.translation.ReaderOverlayGeometry(translation.width, translation.height,
                            com.lmreader.core.model.PixelRect(0f, 0f, translation.width.toFloat(), translation.height.toFloat()))
                    }
                    overlaySource = seed
                }
                return@LaunchedEffect
            }
            overlaySource = null
            target.overlay = null
            val imageSource = withContext(Dispatchers.IO) {
                if (translation != null) {
                    prepareOverlayPage(context, source, page, translation, targetLongEdge, settings.effectiveCropBorders)
                        .also { prepared = it }.imageSource
                } else buildImageSource(source, page, prefetcher, targetLongEdge)
            }
            ensureActive()
            if (imageSource == null) { decodeFailed = true; return@LaunchedEffect }
            if (target.width == 0 || target.height == 0) suspendCancellableCoroutine<Unit> { continuation ->
                target.doOnLayout { if (continuation.isActive) continuation.resume(Unit) }
            }
            // tag 带上解码策略：同一页在**同一策略下**只 setImage 一次（避免重组触发第二次
            // 整图解码），但用户切换「加载原图」时必须让它重新载一次——那就是这个开关的意义。
            withContext(Dispatchers.Main.immediate) {
                if(target.getTag(IMAGE_REQUESTED_TAG)!=loadTag || target.getTag(IMAGE_LOADED_TAG)==loadTag) return@withContext
                if(target.isReady && target.minScale>0f && target.sWidth>0 && target.sHeight>0) {
                    target.center?.let {center ->target.pendingViewport=PageViewport(target.scale/target.minScale,
                        center.x/target.sWidth,center.y/target.sHeight)}
                }
                target.overlayGeometry = prepared?.geometry
                // Cropping translated pages is performed once above with explicit offsets.
                target.setCropBorders(translation == null && settings.effectiveCropBorders)
                target.setImage(imageSource)
                // Record success only after setImage actually accepts the new source.
                target.setTag(IMAGE_LOADED_TAG, loadTag)
                attached = true
                overlaySource = prepared?.overlay
            }
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(error:Exception) {
            android.util.Log.e("ReaderImage", "Cannot prepare reader image or overlay", error)
            decodeFailed = true
        }
        finally { if (!attached) prepared?.bitmap?.recycle() }
    }

    LaunchedEffect(view, overlaySource, regions, renderSettings, translation?.preview, editing) {
        val target = view ?: return@LaunchedEffect
        val seed = overlaySource ?: return@LaunchedEffect
        val overlay = withContext(Dispatchers.Default) {
            seed.layout(regions, renderSettings, hideEmpty = true, includeEmptyForEditing = editing)
        }
        ensureActive()
        target.overlay = overlay
        target.invalidate()
    }

    DisposableEffect(view) {
        val target = view
        onDispose {
            // 清理顺序有讲究：先摘监听器，再 recycle。
            //
            // `recycle()` 只释放解码器持有的瓦片与位图；正在跑的 `TilesInitTask` 是
            // 库内部的 AsyncTask，它完成时仍会回调监听器。先摘掉监听器可以避免
            // 已经离开屏幕的页面再触发一次状态更新（那会让 Compose 重新组合一个
            // 已经销毁的节点）。
            target?.setOnImageEventListener(null)
            target?.setTag(IMAGE_LOADED_TAG, null)
            target?.setTag(IMAGE_REQUESTED_TAG, null)
            target?.overlay = null
            target?.recycle()
        }
    }
}

/** 标记"这一页已经载图"，防止重组时重复整图解码。见上面的载图分支。 */
private const val IMAGE_LOADED_TAG = -0x4C4D52 // 负数，避开库与框架可能使用的正数 tag key
private const val IMAGE_REQUESTED_TAG = -0x4C4D53

/**
 * 构造交给引擎的图源，必要时先降采样。
 *
 * ## 为什么需要这一步（这是 OOM 的关键）
 *
 * 反编译 `com.davemorrissey.labs.subscaleview.decoder.Decoder` 后确认：这个 fork 的解码器
 * **不管输入是文件流还是 Bitmap，都会先把整张图完整解码出来**
 * （`Decoder.init` 无条件调用 `InputProvider.openStream()`，把结果读成一个 `ByteBuffer`
 * 再转 byte[]），而且位图格式**硬编码 `ARGB_8888`**。因此：
 *
 * - `setPreferredBitmapConfig(RGB_565)` 与 `setMaxTileSize` 对它**完全无效**；
 * - 每载一页都要付一次"整图 × 4 字节"的代价：3024×1700 约 20MB，
 *   而真机的堆增长上限是 256MB。真机上那笔失败的 `8294416` 字节分配就是它。
 *
 * 既然无法从外部换掉解码器（`TilesInitTask` 里是硬编码 `new Decoder(...)`，
 * `decoder` 字段 private 且无 setter），就改为**控制送进去的像素量**：够大的图
 * 按 2 的幂降采样成位图直接交给引擎（`ImageSource.bitmap`），那次必然发生的
 * 整图分配于是被压到预算内。
 *
 * ## 与上游对齐（这一版改掉了什么）
 *
 * 上一版把降采样结果**编成 PNG** 再交回去，而且整段跑在组合的主线程上。真机实测
 * 翻页单帧 2300~3700ms、3.7 秒只出 22 帧——PNG 编码一张 2550×3299 的位图就是秒级，
 * 而阈值判断还 off-by-one（那种尺寸算出的 sample 是 1，等于完全没降采样）。
 *
 * 现在的规则与上游一致（EhViewer `image/Image.kt` 用 `min(屏宽,屏高) * 4/3` 作解码目标，
 * Mihon 条漫也把视图级位图交给引擎），细节与推导见 [ReaderImageSampling]：
 * 不到"2 × 目标"就原样交流，到了才解一次位图，**不做任何编码**。
 *
 * ## 线程
 *
 * 这里每一步都是阻塞 IO 或上千万像素的解码，调用方必须在
 * [kotlinx.coroutines.Dispatchers.IO] 上执行（见 [EnginePageView] 的 `LaunchedEffect`）。
 */
private suspend fun buildImageSource(
    source: PageSource,
    page: ReaderPage,
    prefetcher: PagePrefetcher?,
    targetLongEdge: Int,
): ImageSource? {
    // 「加载原图」：目标为 0/负 = 不降采样。连尺寸都不必探测——探测只为决定采样率，
    // 不采样时它只是白白多开一次文件。预取命中仍优先用本地字节（省一次跨进程读取）。
    if (targetLongEdge <= 0) {
        if (prefetcher != null) {
            runCatching { prefetcher.stream(page.pageId) }.getOrNull()?.let {
                return ImageSource.inputStream(it)
            }
        }
        val raw = runCatching { source.open(page) }.getOrNull() ?: return null
        return ImageSource.inputStream(raw)
    }

    // 预取命中时优先走本地字节：省掉一次跨进程的 `openInputStream`。
    // 这里**只读文件头**——把整页 readBytes() 进堆是上一版的另一个卡顿来源。
    if (prefetcher != null) {
        val bounds = runCatching {
            prefetcher.stream(page.pageId)?.use { stream ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, options)
                options.outWidth to options.outHeight
            }
        }.getOrNull()
        if (bounds != null) {
            val plan = planPageDecode(bounds.first, bounds.second, targetLongEdge)
            if (plan.passThrough) {
                runCatching { prefetcher.stream(page.pageId) }.getOrNull()?.let {
                    return ImageSource.inputStream(it)
                }
            } else {
                runCatching {
                    prefetcher.stream(page.pageId)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, bitmapOptions(plan.sampleSize))
                    }
                }.getOrNull()?.let { return ImageSource.bitmap(it) }
            }
        }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { source.open(page).use { BitmapFactory.decodeStream(it, null, bounds) } }
    val plan = planPageDecode(bounds.outWidth, bounds.outHeight, targetLongEdge)
    if (plan.passThrough) {
        // 常见情形：不采样，直接把原始流交给库，一个字节都不多搬。
        val raw = runCatching { source.open(page) }.getOrNull() ?: return null
        return ImageSource.inputStream(raw)
    }

    // 降采样路径：解码一次、交位图、不做任何编码。解不出来就退回原始流——
    // 宁可慢一点，也不能因为这一步失败而白屏。
    val sampled = runCatching {
        source.open(page).use { BitmapFactory.decodeStream(it, null, bitmapOptions(plan.sampleSize)) }
    }.getOrNull() ?: run {
        val raw = runCatching { source.open(page) }.getOrNull() ?: return null
        return ImageSource.inputStream(raw)
    }
    return ImageSource.bitmap(sampled)
}

/**
 * 降采样解码的选项。
 *
 * `RGB_565`：这张位图只用于显示，且是屏幕级尺寸（目标长边的量级），每像素省一半
 * 内存；原图仍然按原始流交给引擎的那条路径不受影响。
 */
private fun bitmapOptions(sampleSize: Int): BitmapFactory.Options = BitmapFactory.Options().apply {
    inSampleSize = sampleSize
    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
}

/**
 * 应用与 Mihon 同源的引擎配置。
 *
 * 数值全部对照 Mihon `ReaderPageImageView`：
 * - `maxScale = scale * 5`（`MAX_ZOOM_SCALE = 5F`）
 * - 双击目标 `scale * 2`
 * - `setDoubleTapZoomStyle(ZOOM_FOCUS_CENTER)`、`setPanLimit(PAN_LIMIT_INSIDE)`
 * - `setMinimumTileDpi(180)`、`setMinimumDpi(1)`
 */
private fun configure(
    view: SubsamplingScaleImageView,
    settings: ReaderSettings,
    onReady: () -> Unit,
    onError: () -> Unit,
) {
    view.setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
    view.setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
    view.setMinimumTileDpi(MIN_TILE_DPI)
    view.setMinimumDpi(MIN_DPI)
    // 限制单块瓦片的像素。默认值在 1440 宽的屏上会取到约 3000×1500 的块
    // （565 下约 9MB），而真机上已经出现过解码期 OOM。2048 把峰值压到约 4MB，
    // 代价只是每页多几次区域解码。
    view.setMaxTileSize(MAX_TILE_PX)
    view.setMinimumScaleType(settings.imageScaleType.toLibraryScaleType())
    // 裁白边是 fork 相对上游 SSIV 的新增能力，也是我们唯一无法自己等价实现的一项
    // （纯 Compose 只能裁掉溢出部分，做不到按内容裁白）。
    view.setCropBorders(settings.effectiveCropBorders)
    view.setDoubleTapZoomDuration(settings.doubleTapAnimMillis.coerceAtLeast(1))
    view.setOnImageEventListener(
        object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
            override fun onReady() {
                // 缩放上限与双击目标必须在图片就绪后设置：它们都以当前初始缩放为基准，
                // 而初始缩放要等库算出适配比例才存在（Mihon 的 `setupZoom` 同理）。
                val base = view.minScale
                view.maxScale = base * MAX_ZOOM_SCALE
                view.setDoubleTapZoomScale(base * DOUBLE_TAP_ZOOM_FACTOR)
                val saved=(view as? TapAwareSubsamplingImageView)?.pendingViewport
                if(saved!=null) {
                    (view as TapAwareSubsamplingImageView).pendingViewport=null
                    view.setScaleAndCenter((base*saved.zoom).coerceIn(base,view.maxScale),PointF(saved.centerX*view.sWidth,saved.centerY*view.sHeight))
                } else applyZoomStart(view, settings)

                onReady()
            }

            override fun onImageLoadError(e: Exception) {
                onError()
            }
        },
    )
}


/**
 * 起始可见位置（Mihon `pref_zoom_start_key`）。
 *
 * 它**不改变缩放比例**，只移动可见窗口：宽图在初始缩放下本来就溢出屏幕，
 * 左右两种默认值让读者先看到该先看的那一半。因为库设了 `PAN_LIMIT_INSIDE`，
 * 请求的中心会被夹回合法范围。
 */
private fun applyZoomStart(view: SubsamplingScaleImageView, settings: ReaderSettings) {
    if (!view.isReady) return
    val scale = view.scale
    val target = when (settings.zoomStart.resolve(settings.readingMode)) {
        ZoomStart.LEFT -> PointF(0f, 0f)
        ZoomStart.RIGHT -> PointF(view.sWidth.toFloat(), 0f)
        ZoomStart.CENTER, ZoomStart.AUTOMATIC ->
            PointF(view.sWidth / 2f, view.sHeight / 2f)
    }
    view.setScaleAndCenter(scale, target)
}

/**
 * 缩放类型映射。
 *
 * 这个 fork 的常量集合**正好覆盖** Mihon 的六种 `ImageScaleType`
 * （上游 3.10.0 只有 `CENTER_INSIDE` / `CENTER_CROP` / `CUSTOM` / `START` 四个，
 * 且没有 `setCropBorders`——因此这里必须用 fork，不能用上游）：
 *
 * | 我们的值 | 库常量 |
 * |---|---|
 * | [ImageScaleType.FIT_SCREEN] | `SCALE_TYPE_CENTER_INSIDE` |
 * | [ImageScaleType.FIT_WIDTH] | `SCALE_TYPE_FIT_WIDTH` |
 * | [ImageScaleType.FIT_HEIGHT] | `SCALE_TYPE_FIT_HEIGHT` |
 * | [ImageScaleType.ORIGINAL_SIZE] | `SCALE_TYPE_ORIGINAL_SIZE` |
 * | [ImageScaleType.SMART_FIT] | `SCALE_TYPE_SMART_FIT` |
 * | [ImageScaleType.STRETCH] | `SCALE_TYPE_CENTER_CROP` |
 *
 * `STRETCH`（"拉伸填满"）是本组里唯一的近似：库没有"不保持比例地拉伸"这一项，
 * `CENTER_CROP` 是"填满并使短边对齐、超出部分裁掉"。两者都会铺满视口，差别在于
 * 是否变形。之所以不自己算矩阵去实现真拉伸：那会绕开库的分块绘制路径，
 * 在超大图上反而丢掉内存保护。
 */
private fun ImageScaleType.toLibraryScaleType(): Int = when (this) {
    ImageScaleType.FIT_SCREEN -> SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
    ImageScaleType.FIT_WIDTH -> SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH
    ImageScaleType.FIT_HEIGHT -> SubsamplingScaleImageView.SCALE_TYPE_FIT_HEIGHT
    ImageScaleType.ORIGINAL_SIZE -> SubsamplingScaleImageView.SCALE_TYPE_ORIGINAL_SIZE
    ImageScaleType.SMART_FIT -> SubsamplingScaleImageView.SCALE_TYPE_SMART_FIT
    ImageScaleType.STRETCH -> SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP
}

/** Mihon `ReaderPageImageView.MAX_ZOOM_SCALE`。 */
private const val MAX_ZOOM_SCALE = 5F

/** Mihon 双击缩放目标：初始缩放的 2 倍。 */
private const val DOUBLE_TAP_ZOOM_FACTOR = 2f

/** Mihon `setMinimumTileDpi(180)` / `setMinimumDpi(1)`。 */
private const val MIN_TILE_DPI = 180
private const val MIN_DPI = 1

/** 单块瓦片的最大边长（像素）。见 `configure` 里的内存说明。 */
private const val MAX_TILE_PX = 2048
