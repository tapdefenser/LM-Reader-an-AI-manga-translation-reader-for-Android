package com.lmreader.ui.video

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.UUID

object VideoMediaSource {
    suspend fun inspect(context: Context, uri: Uri, persist: Boolean = true, onProgress: suspend (Long) -> Unit = {}): VideoProject = withContext(Dispatchers.IO) {
        require(uri.scheme in setOf("content", "file")) { "请选择本地视频文件" }
        if (persist && uri.scheme == "content") {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { error("此文件不能保持读取授权，请从本地文件目录重新选择") }
        }
        var name = uri.lastPathSegment.orEmpty().substringAfterLast('/').take(500)
        if (uri.scheme == "content") context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0).orEmpty().take(500)
        }
        val digest = MessageDigest.getInstance("SHA-256"); var bytes = 0L
        var reportedAt = 0L
        requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取原视频，请重新定位" }.use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                ensureActive(); val n = input.read(buffer); if (n < 0) break
                digest.update(buffer, 0, n); bytes += n
                val now = System.nanoTime()
                if(now - reportedAt >= 250_000_000) { onProgress(bytes); reportedAt = now }
            }
        }
        onProgress(bytes)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun metadata(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0L
            val duration = metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val width = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            val height = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            require(duration > 0 && width in 1..16384 && height in 1..16384) { "文件没有可播放的视频画面" }
            VideoProject(UUID.randomUUID().toString(), uri.toString(), name.ifBlank { "本地视频" },
                digest.digest().joinToString("") { "%02x".format(it) }, bytes, duration, width, height,
                metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt())
        } finally { retriever.release() }
    }
}
