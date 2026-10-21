package com.lmreader.core.database.media

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Catalog reads and *targeted* writes.
 *
 * Scanner writes never use a whole-row upsert. A full upsert would replace an existing
 * row and silently reset shelf/category/position/playCount; [updateScannedMetadata]
 * only touches system-owned columns, so a rescan preserves user state and `videoId`.
 *
 * `videoId` is intentionally never rewritten by normal scanning. The only PK rewrite
 * is [reassignVideoId], used by the legacy importer to adopt an existing scanned row
 * into the legacy UUID without deleting any user result.
 */
@Dao
interface VideoCatalogDao {

    @Query("SELECT * FROM video_catalog ORDER BY dateAdded DESC, videoId ASC")
    fun observeEntries(): Flow<List<VideoCatalogEntry>>

    @Query("SELECT * FROM video_catalog ORDER BY dateAdded DESC, videoId ASC")
    suspend fun entries(): List<VideoCatalogEntry>

    @Query("SELECT * FROM video_catalog WHERE videoId = :id")
    suspend fun byId(id: String): VideoCatalogEntry?

    /** Bulk playlist lookup; callers stay at or below 500 bind variables per chunk. */
    @Query("SELECT * FROM video_catalog WHERE videoId IN (:ids)")
    suspend fun byIds(ids: List<String>): List<VideoCatalogEntry>

    @Query("UPDATE video_catalog SET sourceHash=:hash WHERE videoId=:id AND sourceRevision=:revision")
    suspend fun verifiedSource(id: String, revision: String, hash: String): Int

    @Query("UPDATE video_catalog SET durationMs=CASE WHEN durationMs<=0 THEN :duration ELSE durationMs END, width=CASE WHEN width<=0 THEN :width ELSE width END, height=CASE WHEN height<=0 THEN :height ELSE height END, rotation=:rotation WHERE videoId=:id AND sourceRevision=:revision AND accessible=1")
    suspend fun fillMissingMetadata(id: String, revision: String, duration: Long, width: Int, height: Int, rotation: Int): Int

    @Query("SELECT * FROM video_catalog WHERE uri = :uri")
    suspend fun byUri(uri: String): VideoCatalogEntry?
    @Query("DELETE FROM video_catalog WHERE videoId=:id") suspend fun removeVerifiedDuplicate(id: String)

    @Query("SELECT * FROM video_catalog WHERE volume = :volume AND mediaId = :mediaId LIMIT 1")
    suspend fun byLocator(volume: String, mediaId: Long): VideoCatalogEntry?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entry: VideoCatalogEntry): Long

    @Update
    suspend fun update(entry: VideoCatalogEntry): Int

    @Upsert
    suspend fun upsertWatermark(watermark: VideoMediaWatermark)

    @Query("SELECT * FROM video_watermarks WHERE volume = :volume")
    suspend fun watermark(volume: String): VideoMediaWatermark?

    @Query("SELECT * FROM video_watermarks ORDER BY volume ASC")
    suspend fun allWatermarks(): List<VideoMediaWatermark>

    /**
     * Invalidates only the opaque version, not `generation`/`lastId`/`lastAdded`.
     * Used when a volume is absent or a per-volume read failed: the next successful
     * pass is forced to be full, but no watermark progress is claimed.
     */
    @Query("UPDATE video_watermarks SET version = '' WHERE volume = :volume")
    suspend fun invalidateWatermarkVersion(volume: String): Int

    /**
     * Marks one volume unavailable without overwriting a restored-needs-location reason.
     * The row is already inaccessible; retaining that reason is what keeps a later scan
     * from automatically rebinding another device's URI/locator and formal results.
     */
    @Query(
        """
        UPDATE video_catalog
        SET accessible = 0, accessReason = :reason
        WHERE volume = :volume
          AND (accessReason IS NULL OR accessReason <> :restoredReason)
        """,
    )
    suspend fun markVolumeUnavailable(volume: String, reason: String, restoredReason: String): Int

    @Query("UPDATE video_catalog SET positionMs = :positionMs WHERE videoId = :id")
    suspend fun updatePosition(id: String, positionMs: Long): Int

    /** Only flips the shelf bit; the existing categoryId is preserved. */
    @Query("UPDATE video_catalog SET onShelf = 1 WHERE videoId = :id")
    suspend fun addToShelf(id: String): Int

    @Query("UPDATE video_catalog SET categoryId = :categoryId, onShelf = 1 WHERE videoId = :id")
    suspend fun setCategory(id: String, categoryId: Long): Int

    @Query("UPDATE video_catalog SET onShelf = 0 WHERE videoId = :id")
    suspend fun removeFromShelf(id: String): Int

    @Query("UPDATE video_catalog SET playCount = playCount + 1 WHERE videoId = :id")
    suspend fun incrementPlayCount(id: String): Int

    /**
     * Writes only system-owned metadata. Shelf, category, playback position, play count,
     * source revision and migration state are deliberately absent from this UPDATE.
     */
    @Query(
        """
        UPDATE video_catalog
        SET uri = :uri,
            volume = :volume,
            mediaId = :mediaId,
            parentPath = :parentPath,
            name = :name,
            bytes = :bytes,
            durationMs = :durationMs,
            width = :width,
            height = :height,
            rotation = :rotation,
            dateAdded = :dateAdded,
            generation = :generation,
            accessible = 1,
            accessReason = NULL,
            sourceHash = :sourceHash,
            sourceRevision = :sourceRevision,
            mediaVersion = :mediaVersion
        WHERE videoId = :videoId
        """,
    )
    suspend fun updateScannedMetadata(
        videoId: String,
        uri: String,
        volume: String,
        mediaId: Long,
        parentPath: String,
        name: String,
        bytes: Long,
        durationMs: Long,
        width: Int,
        height: Int,
        rotation: Int,
        dateAdded: Long,
        generation: Long,
        sourceHash: String?,
        sourceRevision: String,
        mediaVersion: Long,
    ): Int

    /**
     * After a fully successful full-volume query, revoke accessibility from rows that
     * were not stamped with [generation].
     *
     * `generation > 0` protects legacy/SAF imports (generation 0), which are not
     * necessarily present in MediaStore. `< generation` also protects a newer
     * concurrent scan's rows from being revoked by an older scan. Rows carrying
     * [restoredReason] are left untouched so a restored row keeps its explicit
     * re-location marker. No row is ever deleted.
     */
    @Query(
        """
        UPDATE video_catalog
        SET accessible = 0, accessReason = :reason
        WHERE volume = :volume
          AND generation > 0
          AND generation < :generation
          AND (accessReason IS NULL OR accessReason <> :restoredReason)
        """,
    )
    suspend fun markUnseenUnavailable(
        volume: String,
        generation: Long,
        reason: String,
        restoredReason: String,
    ): Int

    /**
     * Permission-loss validation. This is a local database fact, so it needs no metadata
     * read permission and does not query MediaStore. Only discovered rows (generation > 0)
     * are revoked; legacy SAF/file imports (generation 0) and restored rows are preserved.
     */
    @Query(
        """
        UPDATE video_catalog
        SET accessible = 0, accessReason = :reason
        WHERE generation > 0
          AND (accessReason IS NULL OR accessReason <> :restoredReason)
        """,
    )
    suspend fun markAllDiscoveredUnavailable(reason: String, restoredReason: String): Int

    @Query("SELECT DISTINCT volume FROM video_catalog")
    suspend fun volumes(): List<String>

    /**
     * One-time-per-process validation signal: after a process restart we cannot know
     * whether the media permission changed while we were dead, so the first scan does
     * one full visible pass if any discovered row was left revoked. Restored rows are
     * excluded: they are intentionally inaccessible until explicitly re-located.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM video_catalog
            WHERE accessible = 0
              AND generation > 0
              AND (accessReason IS NULL OR accessReason <> :restoredReason)
        )
        """,
    )
    suspend fun hasRevokedRows(restoredReason: String): Boolean

    @Query("SELECT COALESCE(MAX(generation), 0) + 1 FROM video_catalog")
    suspend fun nextDiscoveryGeneration(): Long

    /** Legacy rebind: changes the stable owner id while keeping the row (and its stats). */
    @Query("UPDATE video_catalog SET videoId = :newId WHERE videoId = :oldId")
    suspend fun reassignVideoId(oldId: String, newId: String): Int

    /**
     * Adopts legacy formal-project metadata into an existing row. User state that is not
     * part of the legacy payload (category, play count, discovery generation) is kept.
     */
    @Query(
        """
        UPDATE video_catalog
        SET uri = :uri,
            volume = :volume,
            mediaId = :mediaId,
            parentPath = :parentPath,
            name = :name,
            bytes = :bytes,
            durationMs = :durationMs,
            width = :width,
            height = :height,
            rotation = :rotation,
            positionMs = MAX(positionMs, :positionMs),
            accessible = 1,
            accessReason = NULL,
            sourceHash = :sourceHash,
            onShelf = 1,
            migrationComplete = 1
        WHERE videoId = :videoId
        """,
    )
    suspend fun adoptLegacy(
        videoId: String,
        uri: String,
        volume: String,
        mediaId: Long,
        parentPath: String,
        name: String,
        bytes: Long,
        durationMs: Long,
        width: Int,
        height: Int,
        rotation: Int,
        positionMs: Long,
        sourceHash: String?,
    ): Int
}
