package com.lmreader.core.database.media

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "video_frames", primaryKeys = ["videoId", "sourceHash", "identity", "sampleIndex"],
    indices = [Index(value = ["videoId", "sourceHash", "ptsUs", "untilUs"]), Index("producerTaskId")])
data class VideoFrameIndex(val videoId: String, val sourceHash: String, val identity: String, val sampleIndex: Long,
    val targetUs: Long, val ptsUs: Long, val untilUs: Long, val pageId: String, val resultRevision: String,
    val noText: Boolean, val producerTaskId: String)

@Entity(tableName = "video_regions", indices = [Index(value = ["videoId", "sourceHash", "startMs", "endMs"]), Index(value = ["pageId", "regionId"])])
data class VideoRegionInterval(@PrimaryKey val intervalId: String, val videoId: String, val sourceHash: String,
    val pageId: String, val regionId: String, val startMs: Long, val endMs: Long,
    val observedStartMs: Long = startMs, val observedEndMs: Long = endMs, val manualTiming: Boolean = false,
    val deleted: Boolean = false, val revision: String, val trackId: String = intervalId,
    val anchorUs: Long? = null)

@Entity(tableName = "video_cues", primaryKeys = ["videoId", "cueId"],
    indices = [Index(value = ["videoId", "startMs", "endMs"]), Index("sourceHash")])
data class VideoCueRecord(val videoId: String, val cueId: String, val sourceHash: String, val trackId: String,
    val startMs: Long, val endMs: Long, val source: String, val translation: String = "",
    val deleted: Boolean = false, val manuallyEdited: Boolean = false, val revision: String,
    val identity: String? = null, val producerTaskId: String? = null)

@Entity(tableName = "video_settings")
data class VideoSettingsRecord(@PrimaryKey val videoId: String, val json: String = "{}", val style: String = "{}",
    val revision: String = java.util.UUID.randomUUID().toString(), val migrated: Boolean = false)

@Dao
abstract class VideoResultDao {
    @Query("SELECT * FROM video_frames WHERE videoId=:id AND sourceHash=:hash ORDER BY ptsUs, sampleIndex")
    abstract suspend fun frames(id: String, hash: String): List<VideoFrameIndex>
    @Query("SELECT * FROM video_frames WHERE videoId=:id AND sourceHash=:hash AND identity=:identity AND sampleIndex=:sample LIMIT 1")
    abstract suspend fun sample(id: String, hash: String, identity: String, sample: Long): VideoFrameIndex?
    @Query("SELECT * FROM video_frames WHERE videoId=:id AND sourceHash=:hash AND ptsUs<=:timeUs AND untilUs>:timeUs ORDER BY ptsUs DESC LIMIT 1")
    abstract suspend fun at(id: String, hash: String, timeUs: Long): VideoFrameIndex?
    @Query("SELECT * FROM video_frames WHERE videoId=:id AND sourceHash=:hash AND ptsUs<=:timeUs AND untilUs>:timeUs ORDER BY ptsUs DESC,targetUs DESC")
    abstract suspend fun framesAt(id: String, hash: String, timeUs: Long): List<VideoFrameIndex>
    @Query("SELECT * FROM video_regions WHERE videoId=:id AND trackId=:trackId")
    abstract suspend fun trackRegions(id: String, trackId: String): List<VideoRegionInterval>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putFrame(row: VideoFrameIndex)
    @Query("UPDATE video_frames SET resultRevision=:revision,noText=:noText WHERE pageId=:pageId")
    abstract suspend fun updateFrameRevision(pageId: String, revision: String, noText: Boolean)
    @Query("DELETE FROM video_frames WHERE videoId=:id AND pageId=:pageId")
    abstract suspend fun deletePageFrames(id: String, pageId: String)
    @Query("DELETE FROM video_regions WHERE videoId=:id AND pageId=:pageId")
    abstract suspend fun deletePageRegions(id: String, pageId: String)
    @Query("SELECT * FROM video_regions WHERE videoId=:id AND sourceHash=:hash ORDER BY startMs,intervalId")
    abstract fun observeRegions(id: String, hash: String): Flow<List<VideoRegionInterval>>
    @Query("SELECT * FROM video_regions WHERE videoId=:id AND sourceHash=:hash ORDER BY startMs,intervalId")
    abstract suspend fun regions(id: String, hash: String): List<VideoRegionInterval>
    @Query("SELECT * FROM video_regions WHERE videoId=:id AND sourceHash=:hash AND startMs<=:timeMs AND endMs>:timeMs AND deleted=0 ORDER BY startMs,intervalId")
    abstract suspend fun regionsAt(id: String, hash: String, timeMs: Long): List<VideoRegionInterval>
    /**
     * Active intervals whose translation anchor belongs to [identity]. Manual intervals do
     * not depend on a machine frame row; workflow intervals are anchored only when the same
     * video/source/page/identity frame exists.
     */
    @Query("SELECT r.* FROM video_regions r WHERE r.videoId=:id AND r.sourceHash=:hash AND r.startMs<=:timeMs AND r.endMs>:timeMs AND r.deleted=0 AND (r.manualTiming=1 OR EXISTS (SELECT 1 FROM video_frames f WHERE f.videoId=r.videoId AND f.sourceHash=r.sourceHash AND f.pageId=r.pageId AND f.identity=:identity)) ORDER BY r.startMs,r.intervalId")
    abstract suspend fun regionsAtIdentity(id: String, hash: String, identity: String, timeMs: Long): List<VideoRegionInterval>
    @Query("SELECT * FROM video_regions WHERE pageId=:pageId") abstract suspend fun pageRegions(pageId: String): List<VideoRegionInterval>
    @Query("SELECT * FROM video_regions WHERE intervalId=:id") abstract suspend fun region(id: String): VideoRegionInterval?
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun addRegion(row: VideoRegionInterval): Long
    @Update abstract suspend fun updateRegion(row: VideoRegionInterval)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putObservations(rows: List<VideoRegionObservation>)
    @Query("DELETE FROM video_region_observations WHERE videoId=:id AND sourceHash=:hash AND identity=:identity AND sampleIndex=:sample")
    abstract suspend fun deleteSampleObservations(id: String, hash: String, identity: String, sample: Long)
    @Query("SELECT * FROM video_region_observations WHERE videoId=:id AND sourceHash=:hash AND identity=:identity AND sampleIndex=:sample ORDER BY trackId")
    abstract suspend fun sampleObservations(id: String, hash: String, identity: String, sample: Long): List<VideoRegionObservation>
    @Query("SELECT * FROM video_region_observations WHERE videoId=:id AND sourceHash=:hash ORDER BY identity,sampleIndex,trackId")
    abstract suspend fun observations(id: String, hash: String): List<VideoRegionObservation>
    @Query("SELECT * FROM video_region_observations WHERE videoId=:id AND sourceHash=:hash AND identity=:identity AND trackId=:trackId AND ptsUs<=:timeUs AND untilUs>:timeUs ORDER BY ptsUs DESC,targetUs DESC,sampleIndex DESC LIMIT 1")
    abstract suspend fun observationAt(id: String, hash: String, identity: String, trackId: String, timeUs: Long): VideoRegionObservation?
    @Query("SELECT * FROM video_region_observations WHERE videoId=:id AND sourceHash=:hash AND identity=:identity AND trackId=:trackId ORDER BY ptsUs DESC,targetUs DESC,sampleIndex DESC LIMIT 1")
    abstract suspend fun lastTrackObservation(id: String, hash: String, identity: String, trackId: String): VideoRegionObservation?
    @Query("SELECT * FROM video_region_observations")
    abstract suspend fun allObservations(): List<VideoRegionObservation>
    /** Removes sampled observations whose presentation time is in the half-open [startUs, endUs). */
    @Query("DELETE FROM video_region_observations WHERE videoId=:id AND sourceHash=:hash AND ptsUs>=:startUs AND ptsUs<:endUs")
    abstract suspend fun deleteSourceObservations(id: String, hash: String, startUs: Long, endUs: Long)
    @Query("SELECT * FROM video_cues WHERE videoId=:id ORDER BY startMs,trackId,cueId") abstract fun observeCues(id: String): Flow<List<VideoCueRecord>>
    @Query("SELECT * FROM video_cues WHERE videoId=:id ORDER BY startMs,trackId,cueId") abstract suspend fun cues(id: String): List<VideoCueRecord>
    @Query("SELECT * FROM video_cues WHERE videoId=:id AND sourceHash=:hash AND trackId=:trackId AND startMs<:endMs AND endMs>:startMs ORDER BY startMs,cueId")
    abstract suspend fun cuesInRange(id: String, hash: String, trackId: String, startMs: Long, endMs: Long): List<VideoCueRecord>
    @Query("SELECT * FROM video_cues WHERE videoId=:id AND cueId=:cueId") abstract suspend fun cue(id: String, cueId: String): VideoCueRecord?
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun addCue(row: VideoCueRecord): Long
    @Update abstract suspend fun updateCue(row: VideoCueRecord)
    @Query("SELECT * FROM video_settings WHERE videoId=:id") abstract suspend fun settings(id: String): VideoSettingsRecord?
    @Query("SELECT * FROM video_settings WHERE videoId=:id") abstract fun observeSettings(id: String): Flow<VideoSettingsRecord?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putSettings(row: VideoSettingsRecord)
    @Query("DELETE FROM video_frames WHERE videoId=:id AND ptsUs>=:startUs AND ptsUs<:endUs")
    abstract suspend fun clearFrameIndex(id: String, startUs: Long, endUs: Long)
    @Query("SELECT * FROM video_frames WHERE videoId=:id AND sourceHash=:hash AND identity=:identity AND sampleIndex>=0 AND sampleIndex<=:beforeSample ORDER BY sampleIndex DESC LIMIT 1")
    abstract suspend fun latestFrame(id: String, hash: String, identity: String, beforeSample: Long): VideoFrameIndex?
    @Query("SELECT * FROM video_frames") abstract suspend fun allFrames(): List<VideoFrameIndex>
    @Query("SELECT * FROM video_regions") abstract suspend fun allRegions(): List<VideoRegionInterval>
    @Query("SELECT * FROM video_cues") abstract suspend fun allCues(): List<VideoCueRecord>
    @Query("SELECT * FROM video_settings") abstract suspend fun allSettings(): List<VideoSettingsRecord>
    @Transaction open suspend fun saveRegion(row: VideoRegionInterval, expectedRevision: String) {
        check(region(row.intervalId)?.revision == expectedRevision) { "气泡时间已更新，请重新载入" }
        require(row.startMs >= 0 && row.endMs > row.startMs)
        updateRegion(row.copy(revision = java.util.UUID.randomUUID().toString()))
    }
    @Transaction open suspend fun saveCue(row: VideoCueRecord, expectedRevision: String?) {
        val previous = cue(row.videoId, row.cueId)
        check(previous?.revision == expectedRevision) { "字幕已更新，请重新载入" }
        require(row.startMs >= 0 && row.endMs > row.startMs)
        val next = row.copy(revision = java.util.UUID.randomUUID().toString())
        if(previous == null) check(addCue(next) != -1L) else updateCue(next)
    }
}

@Database(entities = [VideoFrameIndex::class, VideoRegionInterval::class, VideoRegionObservation::class,
    VideoCueRecord::class, VideoSettingsRecord::class], version = 2, exportSchema = true)
abstract class VideoResultDatabase : RoomDatabase() {
    abstract fun results(): VideoResultDao
    companion object { fun build(context: Context) = Room.databaseBuilder(context.applicationContext,
        VideoResultDatabase::class.java, "video-results.db")
        .addMigrations(VideoResultMigrations.MIGRATION_1_2).build() }
}
