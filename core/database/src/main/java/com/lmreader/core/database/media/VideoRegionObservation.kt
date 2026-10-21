package com.lmreader.core.database.media

import androidx.room.Entity
import androidx.room.Index

/**
 * One sampled geometry observation for a stable video region track.
 *
 * [VideoRegionInterval] remains the single translation anchor for a track: its
 * `pageId`/`regionId` points at the translated artifact, while these rows keep the
 * mask geometry actually observed at each sampling point. Retained masks can therefore
 * follow a moving track without re-running OCR/translation.
 *
 * Coordinates are pixels in the [width] x [height] source image. [appearance] is an
 * optional Base64-encoded 32x32 grayscale thumbnail of the source region, never a whole
 * frame. [contour] is a JSON array of `[x, y]` pixel pairs.
 */
@Entity(
    tableName = "video_region_observations",
    primaryKeys = ["videoId", "sourceHash", "identity", "sampleIndex", "trackId"],
    indices = [
        Index(value = ["videoId", "sourceHash", "identity", "sampleIndex"]),
        Index(value = ["videoId", "trackId", "ptsUs"]),
        Index(value = ["videoId", "sourceHash", "identity", "ptsUs"]),
    ],
)
data class VideoRegionObservation(
    val videoId: String,
    val sourceHash: String,
    val identity: String,
    val sampleIndex: Long,
    val trackId: String,
    val targetUs: Long,
    val ptsUs: Long,
    val untilUs: Long,
    val width: Int,
    val height: Int,
    val kind: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val contour: String = "[]",
    val appearance: String? = null,
)
