package com.lmreader.ui.video

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.lmreader.core.database.media.VideoCueRecord
import com.lmreader.core.model.*
import com.lmreader.core.vision.BubbleMaskRenderer
import com.lmreader.core.vision.BubbleOverlay
import com.lmreader.di.AppContainer
import com.lmreader.ui.video.player.*
import kotlinx.coroutines.*
import org.json.JSONObject

object VideoPlayerTools : VideoPlayerToolHost {
    @Composable override fun Content(container: AppContainer, videoId: String, positionMs: Long, player: Player,
        request: VideoPlayerToolRequest?, showTranslation: Boolean, onDismiss: () -> Unit) {
        val activity = LocalActivity.current
        var initial by remember(videoId) { mutableStateOf(activity?.intent?.getStringExtra("initial-tool")?.let { VideoPlayerToolRequest(videoId, positionMs, it) }) }
        LaunchedEffect(videoId) { activity?.intent?.removeExtra("initial-tool") }
        val current = request ?: initial
        if(current?.action != "EDIT") VideoSavedOverlays(container, videoId, positionMs, player, showTranslation)
        if(current != null) {
            LaunchedEffect(current) { if(current.action in setOf("EDIT", "FRAME")) player.pause() }
            VideoActionDialog(container, current.videoId, current.action, current.positionMs, current.presentationTimeUs,
                onSeek = { player.seekTo(it) }, onDismiss = { initial = null; onDismiss() }, player = player)
        }
    }
}

private data class TimedMask(val overlay: BubbleOverlay, val width: Int, val height: Int, val start: Long, val end: Long)
private data class PreparedScene(val videoId: String, val atMs: Long, val masks: List<TimedMask>, val luma: FloatArray?)

@Composable internal fun VideoSavedOverlays(container: AppContainer, id: String, positionMs: Long, player: Player, enabled: Boolean) {
    val projects by container.videoLibrary.projects.collectAsStateWithLifecycle()
    val rows by container.videoCatalog.entries.collectAsStateWithLifecycle(emptyList())
    val project = projects.firstOrNull { it.id == id }
    val verified by container.videoTasks.verifiedSources.collectAsStateWithLifecycle()
    val catalog = rows.firstOrNull { it.videoId == id }
    val settings by container.videoResults.results().observeSettings(id).collectAsStateWithLifecycle(null)
    val cues by container.videoResults.results().observeCues(id).collectAsStateWithLifecycle(emptyList())
    val regionRows by remember(id, project?.sha256) {
        container.videoResults.results().observeRegions(id, project?.sha256.orEmpty())
    }.collectAsStateWithLifecycle(emptyList())
    val tasks by container.taskScheduler.tasks.collectAsStateWithLifecycle(emptyList())
    val globalStyle by container.preferences.videoSubtitleStyle.collectAsStateWithLifecycle("{}")
    val generatedTrack = generatedVideoTrack(id, tasks)
    val style = remember(settings?.style, globalStyle, generatedTrack, cues) {
        resolveVideoSubtitleStyle(globalStyle, settings?.style ?: "{}").apply {
            put("generatedTrackId", generatedTrack); put("trackId", resolvedVideoSubtitleTrack(this, cues))
        }
    }
    val ready = player.playbackState == Player.STATE_READY && catalog?.accessible == true && catalog.sourceHash == project?.sha256 && project != null && verified[id] == catalog.sourceRevision
    val decoder = remember(id, catalog?.uri) { catalog?.uri?.let { VideoFrameDecoder(container.applicationContext, it, 960) } }
    var scene by remember(id) { mutableStateOf<PreparedScene?>(null) }
    DisposableEffect(decoder) { onDispose { container.backgroundScope.launch { decoder?.close() } } }
    LaunchedEffect(id) { container.videoTasks.existingProject(id) }
    LaunchedEffect(id, catalog?.sourceRevision, project?.sha256) { if(project != null) container.videoTasks.verifyForPlayback(id) }
    LaunchedEffect(id, positionMs / 400, enabled, ready, settings?.revision, project?.revision, regionRows) {
        if(!ready || decoder == null || project == null) { scene = null; return@LaunchedEffect }
        val now = VideoFrameStamp.capture(id, positionMs)?.div(1000) ?: positionMs
        val resultDao = container.videoResults.results()
        val candidates = if(enabled) resultDao.framesAt(id, project.sha256, now * 1000) else emptyList()
        val preferred = JSONObject(settings?.json ?: "{}").optString("frameIdentity")
        val chosen = candidates.firstOrNull { it.identity == preferred } ?: candidates.firstOrNull()
        val identity = preferred.ifBlank { chosen?.identity.orEmpty() }
        val similarity = container.screenTranslation.preferences.settings.value.videoSimilarityPercent
        val intervals = if(enabled) resultDao.regionsAtIdentity(id, project.sha256, identity, now)
            .sortedWith(compareByDescending<com.lmreader.core.database.media.VideoRegionInterval> { it.manualTiming }.thenByDescending { it.startMs })
            .distinctBy { it.trackId } else emptyList()
        if(intervals.isEmpty() && !style.optBoolean("smartColor")) { scene = PreparedScene(id, now, emptyList(), null); return@LaunchedEffect }
        val frame = try { decoder.frameAt(now * 1000) } catch(e: CancellationException) { throw e }
            catch(_: Exception) { scene = null; return@LaunchedEffect } ?: return@LaunchedEffect
        try {
            val observations = intervals.associate { time -> time.intervalId to
                resultDao.observationAt(id, project.sha256, identity, time.trackId, now * 1000) }
            val anchors = intervals.associate { time -> time.intervalId to
                resultDao.observationAt(id, project.sha256, identity, time.trackId, time.anchorUs ?: (time.observedStartMs * 1000 + 999)) }
            val masks = withContext(Dispatchers.Default) {
                intervals.groupBy { it.pageId }.flatMap { (pageId, times) ->
                    val saved = container.videoLibrary.artifacts(id).current(pageId) ?: return@flatMap emptyList()
                    val proof = VideoFrameProof.load(container.videoLibrary.proofs(id), pageId)
                    val scaled = if(frame.bitmap.width == saved.width && frame.bitmap.height == saved.height) frame.bitmap else Bitmap.createScaledBitmap(frame.bitmap, saved.width, saved.height, true)
                    try { times.mapNotNull { time ->
                        val original = saved.regions.firstOrNull { it.region.id == time.regionId } ?: return@mapNotNull null
                        val observation = observations[time.intervalId]
                        if(!time.manualTiming && (observation?.matches(frame.bitmap, similarity)
                                ?: proof?.matches(frame.bitmap, saved.copy(regions = listOf(original)), similarity)) != true) return@mapNotNull null
                        val region = if(observation != null) moveVideoRegion(original, anchors[time.intervalId], observation, saved.width, saved.height) else original
                        TimedMask(BubbleMaskRenderer().prepare(scaled, listOf(region), saved.renderSettings), saved.width, saved.height, time.startMs, time.endMs)
                    } } finally { if(scaled !== frame.bitmap) scaled.recycle() }
                }
            }
            val luma = if(style.optBoolean("smartColor") && decoder.colorSamplingReliable) FloatArray(40 * 24) { i ->
                val pixel = frame.bitmap.getPixel(((i % 40 + .5f) * frame.bitmap.width / 40).toInt().coerceAtMost(frame.bitmap.width - 1),
                    ((i / 40 + .5f) * frame.bitmap.height / 24).toInt().coerceAtMost(frame.bitmap.height - 1))
                (Color.red(pixel) * .2126f + Color.green(pixel) * .7152f + Color.blue(pixel) * .0722f) / 255
            } else null
            currentCoroutineContext().ensureActive(); scene = PreparedScene(id, now, masks, luma)
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { scene = null }
        finally { frame.close() }
    }
    AndroidView(factory = { VideoResultOverlayView(it) }, modifier = Modifier.fillMaxSize(), update = { view ->
        val size = player.videoSize
        view.update(id, positionMs, if(ready && enabled) scene else null, cues.filter { it.sourceHash == project?.sha256 }, style,
            size.width * size.pixelWidthHeightRatio, size.height.toFloat(), ready && style.optBoolean("enabled", true))
    })
}

private class VideoResultOverlayView(context: Context) : View(context) {
    private var id = ""; private var positionMs = 0L; private var scene: PreparedScene? = null
    private var cues = emptyList<VideoCueRecord>(); private var style = JSONObject(); private var videoWidth = 1f; private var videoHeight = 1f
    private var subtitles = false
    private var smoothed = .35f; private var smartWhite = true; private var candidateWhite = true; private var candidateAt = 0L; private var lastSample = Long.MIN_VALUE
    private var mixedBackground = false
    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    fun update(id: String, position: Long, scene: PreparedScene?, cues: List<VideoCueRecord>, style: JSONObject,
        videoWidth: Float, videoHeight: Float, subtitles: Boolean) {
        if(this.id != id) { smoothed = .35f; smartWhite = true; mixedBackground = false; lastSample = Long.MIN_VALUE }
        this.id = id; positionMs = position; this.scene = scene; this.cues = cues; this.style = style
        this.videoWidth = videoWidth.coerceAtLeast(1f); this.videoHeight = videoHeight.coerceAtLeast(1f); this.subtitles = subtitles
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val time = VideoFrameStamp.capture(id, positionMs)?.div(1000) ?: positionMs
        val scale = minOf(width / videoWidth, height / videoHeight)
        val rect = RectF((width - videoWidth * scale) / 2, (height - videoHeight * scale) / 2,
            (width + videoWidth * scale) / 2, (height + videoHeight * scale) / 2)
        scene?.takeIf { it.videoId == id }?.masks?.filter { time >= it.start && time < it.end }?.forEach { mask ->
            canvas.save(); canvas.translate(rect.left, rect.top); canvas.scale(rect.width() / mask.width, rect.height() / mask.height)
            mask.overlay.draw(canvas); canvas.restore()
        }
        if(subtitles) drawSubtitles(canvas, rect, time)
        if(scene?.masks?.isNotEmpty() == true || subtitles) postInvalidateOnAnimation()
    }
    private fun drawSubtitles(canvas: Canvas, video: RectF, time: Long) {
        val offset = style.optLong("offsetMs")
        val track = style.optString("trackId", "all")
        val generated = style.optString("generatedTrackId").takeUnless { it.isBlank() || it == "null" }
        val active = cues.filter { !it.deleted && videoSubtitleMatchesTrack(it.trackId, track, generated) &&
            time >= it.startMs + offset && time < it.endMs + offset }
        if(active.isEmpty()) return
        val display = style.optString("display", "TRANSLATED")
        val text = active.joinToString("\n") { cue -> when(display) {
            "ORIGINAL" -> cue.source
            "BILINGUAL" -> listOf(cue.source, cue.translation.takeIf { it.isNotBlank() && it != cue.source }).filterNotNull().joinToString("\n")
            else -> cue.translation.ifBlank { cue.source }
        } }
        val density = resources.displayMetrics.density
        val subtitleStyle = style
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = subtitleStyle.optDouble("fontSizeSp", 22.0).toFloat().coerceIn(8f, 96f) * resources.displayMetrics.scaledDensity
            typeface = Typeface.create(subtitleStyle.optString("fontFamily", "sans-serif"), Typeface.NORMAL)
            color = subtitleStyle.optInt("textColor", Color.WHITE)
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, (video.width() * .85f).toInt().coerceAtLeast(1))
            .setAlignment(when(style.optString("align", "CENTER")) { "LEFT" -> Layout.Alignment.ALIGN_NORMAL; "RIGHT" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_CENTER })
            .setIncludePad(false).setLineSpacing(0f, style.optDouble("lineSpacing", 1.1).toFloat().coerceIn(.5f, 3f)).build()
        val left = (video.left + video.width() * style.optDouble("x", .5).toFloat() - layout.width / 2).coerceIn(video.left, maxOf(video.left, video.right - layout.width))
        val top = (video.top + video.height() * style.optDouble("y", .9).toFloat() - layout.height).coerceIn(video.top, maxOf(video.top, video.bottom - layout.height))
        val coverage = RectF((left - video.left) / video.width(), (top - video.top) / video.height(),
            (left + layout.width - video.left) / video.width(), (top + layout.height - video.top) / video.height())
        val grid = scene?.luma
        if(style.optBoolean("smartColor") && grid != null && scene?.atMs != lastSample) {
            lastSample = scene!!.atMs
            val values = (0 until 40 * 24).filter { (it % 40 + .5f) / 40 in coverage.left..coverage.right && (it / 40 + .5f) / 24 in coverage.top..coverage.bottom }.map { grid[it] }
            if(values.isNotEmpty()) {
                val mean = values.average().toFloat(); smoothed = .7f * smoothed + .3f * mean
                mixedBackground = values.maxOrNull()!! - values.minOrNull()!! > .45f
                val wanted = if(smoothed > .62f) false else if(smoothed < .42f) true else smartWhite
                val now = android.os.SystemClock.elapsedRealtime()
                if(wanted != candidateWhite) { candidateWhite = wanted; candidateAt = now }
                if(wanted == candidateWhite && now - candidateAt >= 400) smartWhite = wanted
            }
        }
        val color = if(style.optBoolean("smartColor") && grid != null) { if(smartWhite) Color.WHITE else Color.BLACK } else style.optInt("textColor", Color.WHITE)
        background.color = Color.BLACK; background.alpha = ((style.optDouble("backgroundAlpha", .35).coerceIn(0.0, 1.0).toFloat().let { if(mixedBackground) maxOf(.25f, it) else it }) * 255).toInt()
        canvas.drawRoundRect(RectF(left - 4 * density, top - 2 * density, left + layout.width + 4 * density, top + layout.height + 2 * density), 4 * density, 4 * density, background)
        canvas.save(); canvas.translate(left, top)
        paint.style = Paint.Style.STROKE; paint.strokeJoin = Paint.Join.ROUND; paint.strokeWidth = 3 * density
        paint.color = if(style.optBoolean("smartColor")) { if(color == Color.WHITE) Color.BLACK else Color.WHITE } else style.optInt("outlineColor", Color.BLACK)
        layout.draw(canvas)
        paint.style = Paint.Style.FILL; paint.color = color; layout.draw(canvas); canvas.restore()
    }
}
