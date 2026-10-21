package com.lmreader.ui.video

import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.os.SystemClock
import android.view.*
import android.widget.FrameLayout
import com.lmreader.core.vision.BubbleOverlay
import kotlin.math.*

/** Texture contains only decoded source pixels; masks are a separate sibling view. */
class VideoPlaybackView(context: Context, val project: VideoProject,
    private val discontinuity: () -> Unit, private val failure: (String) -> Unit) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val texture = TextureView(context).apply { surfaceTextureListener = this@VideoPlaybackView }
    private val masks = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            val data = this@VideoPlaybackView.overlay ?: return
            if (SystemClock.uptimeMillis() - verifiedAt > 600 || seeking) return
            val save = canvas.save()
            canvas.scale(width.toFloat() / analysisWidth, height.toFloat() / analysisHeight)
            data.draw(canvas); canvas.restoreToCount(save)
        }
    }
    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var wantedPosition = project.positionMs
    private var pendingSeek: Long? = null
    private var audioFocus = false
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focusListener = AudioManager.OnAudioFocusChangeListener { if (it <= 0) pause() }
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener(focusListener).build()
    private var overlay: BubbleOverlay? = null
    private var analysisWidth = 1; private var analysisHeight = 1
    private var verifiedAt = 0L
    var speed = 1f; private set
    val positionMs: Long get() = if (prepared) runCatching { player?.currentPosition?.toLong() ?: wantedPosition }.getOrDefault(wantedPosition) else wantedPosition
    val playing get() = prepared && player?.isPlaying == true
    val seeking get() = pendingSeek != null
    val ready get() = prepared && !seeking && texture.isAvailable
    init { setBackgroundColor(Color.BLACK); addView(texture); addView(masks); keepScreenOn = true }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val rotated = project.rotation in setOf(90, 270)
        val sw = if (rotated) project.height else project.width; val sh = if (rotated) project.width else project.height
        val scale = min(w.toFloat() / sw, h.toFloat() / sh)
        val cw = (sw * scale).roundToInt().coerceAtLeast(1); val ch = (sh * scale).roundToInt().coerceAtLeast(1)
        for (view in listOf(texture, masks)) view.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (view in listOf(texture, masks)) { val x = (width - view.measuredWidth) / 2; val y = (height - view.measuredHeight) / 2
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight) }
    }
    fun play() {
        if (!prepared || seeking) return
        if (!audioFocus) audioFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (audioFocus) runCatching { player?.start() }.onFailure { failure("播放失败：${it.message}") }
    }
    fun pause() { if (prepared) runCatching { player?.pause() }; if (audioFocus) { audio.abandonAudioFocusRequest(focus); audioFocus = false } }
    fun seek(position: Long) {
        discontinuity(); showOverlay(null)
        wantedPosition = position.coerceIn(0, project.durationMs - 1)
        if (prepared) { pendingSeek = wantedPosition; player?.seekTo(wantedPosition, MediaPlayer.SEEK_CLOSEST) }
    }
    fun setSpeed(value: Float) {
        require(value in .5f..2f); discontinuity(); showOverlay(null)
        if (!prepared) return
        val wasPlaying = playing
        runCatching { player?.playbackParams = PlaybackParams().setSpeed(value); speed = value; if (!wasPlaying) player?.pause() }
            .onFailure { failure("设备不支持此播放速度") }
    }
    fun capture(): Bitmap? {
        if (!ready || texture.width <= 0 || texture.height <= 0) return null
        val scale = min(1f, 1280f / max(texture.width, texture.height))
        return texture.getBitmap((texture.width * scale).roundToInt().coerceAtLeast(1), (texture.height * scale).roundToInt().coerceAtLeast(1))
    }
    fun showOverlay(data: BubbleOverlay?, width: Int = 1, height: Int = 1) {
        overlay = data; analysisWidth = width; analysisHeight = height
        verifiedAt = SystemClock.uptimeMillis(); masks.invalidate()
        if (data != null) masks.postInvalidateDelayed(610)
    }
    fun release() { pause(); prepared = false; player?.release(); player = null; surface?.release(); surface = null; showOverlay(null); keepScreenOn = false }
    override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(value)
        try {
            player = MediaPlayer().also { mp ->
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                mp.setSurface(surface); mp.setDataSource(context, Uri.parse(project.uri))
                mp.setOnPreparedListener { prepared = true; mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT); seek(wantedPosition) }
                mp.setOnSeekCompleteListener {
                    val desired = pendingSeek
                    if (desired != null && abs(positionMs - desired) > 600) mp.seekTo(desired, MediaPlayer.SEEK_CLOSEST) else pendingSeek = null
                }
                mp.setOnCompletionListener { pause() }
                mp.setOnErrorListener { _, what, extra -> prepared = false; showOverlay(null); discontinuity(); failure("视频无法播放，请检查格式或重新定位文件（$what/$extra）"); true }
                mp.prepareAsync()
            }
        } catch (error: Exception) { release(); failure("无法读取原视频，请重新定位：${error.message}") }
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { discontinuity(); showOverlay(null) }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { wantedPosition = positionMs; release(); discontinuity(); return true }
}
