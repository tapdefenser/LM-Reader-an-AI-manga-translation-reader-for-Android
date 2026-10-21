package com.lmreader.ui.video

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object VideoSubtitleFiles {
    suspend fun save(context: Context, uri: Uri, contents: String) = withContext(Dispatchers.IO) {
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(contents.toByteArray(Charsets.UTF_8)) }
    }
    suspend fun shareIntent(context: Context, contents: String): Intent {
        val file = withContext(Dispatchers.IO) {
            val root = File(context.cacheDir, "video-share").apply { mkdirs() }
            root.listFiles().orEmpty().filter { System.currentTimeMillis() - it.lastModified() > 7 * 86400000L }.forEach { it.delete() }
            File(root, "subtitles-${UUID.randomUUID()}.srt").apply { writeText(contents, Charsets.UTF_8) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_SEND).setType("application/x-subrip").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newUri(context.contentResolver, "SRT", uri) }
    }
}
