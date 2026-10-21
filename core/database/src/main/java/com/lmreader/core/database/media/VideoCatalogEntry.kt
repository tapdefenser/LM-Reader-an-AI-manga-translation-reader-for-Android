package com.lmreader.core.database.media

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One MediaStore video as seen by the system media library.
 *
 * This row is deliberately *not* the formal translation result. It only carries the
 * locator/metadata plus the user state that must survive a rescan (shelf, category,
 * position and play count). Formal artifacts remain in `files/videos/` and are never
 * written or discovered by this catalog.
 *
 * `videoId` is the stable identity. Scans keep it when the same locator is found again
 * (URI first, then volume+mediaId); a changed URI alone must not create a new result
 * owner. Name/size matches are never used to merge entries.
 *
 * `generation` is the catalog's own discovery generation. A successful full volume
 * scan stamps every visible row with the new generation and then revokes accessibility
 * from rows of that volume that still carry an older non-zero generation. Legacy
 * imports use generation 0 so a system-catalog refresh cannot hide a file that is not
 * indexed by MediaStore in the first place.
 */
@Entity(
    tableName = "video_catalog",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["volume", "parentPath"]),
        Index(value = ["name"]),
        Index(value = ["dateAdded"]),
        Index(value = ["onShelf"]),
        // Locator lookup for a changed URI. Not unique: a legacy/SAF import may use a
        // synthetic locator, and identity is still owned by videoId.
        Index(value = ["volume", "mediaId"]),
    ],
)
data class VideoCatalogEntry(
    @PrimaryKey val videoId: String,
    val uri: String,
    val volume: String,
    val mediaId: Long,
    val parentPath: String,
    val name: String,
    val bytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val dateAdded: Long,
    val generation: Long,
    val accessible: Boolean = true,
    val accessReason: String? = null,
    val onShelf: Boolean = false,
    val categoryId: Long = 0L,
    val positionMs: Long = 0L,
    val playCount: Long = 0L,
    val sourceHash: String? = null,
    val sourceRevision: String = "",
    val migrationComplete: Boolean = false,
    /** Provider content generation (API30+) or last-modified timestamp on older systems. */
    val mediaVersion: Long = 0,
)
