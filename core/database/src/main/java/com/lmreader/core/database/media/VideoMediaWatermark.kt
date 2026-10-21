package com.lmreader.core.database.media

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-volume MediaStore scan watermark.
 *
 * [version] is the opaque per-volume `MediaStore.getVersion()` value. A version change
 * resets generation numbers, so the repository forces a full volume refresh.
 * [generation] is the fixed upper `MediaStore.getGeneration()` captured at the start
 * of the last fully successful scan-on API 30+.
 *
 * [lastId] / [lastAdded] are the bounded `_ID` / `DATE_ADDED` fallback cursor used on
 * API < 30 (and wherever generation is unavailable). They are advanced only after a
 * successful pass; a cancelled or failed scan leaves the old watermark untouched.
 */
@Entity(tableName = "video_watermarks")
data class VideoMediaWatermark(
    @PrimaryKey val volume: String,
    val version: String,
    val generation: Long,
    val lastId: Long = 0L,
    val lastAdded: Long = 0L,
)
