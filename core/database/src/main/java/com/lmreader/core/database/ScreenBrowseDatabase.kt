package com.lmreader.core.database

import android.content.Context
import androidx.room.*

/** Screen geometry is session-local. Keep it separate from the reader library:
 * beginning a new screen session discards stale poses, never library data. */
@Entity(tableName = "screen_regions", primaryKeys = ["sessionId", "id"])
data class ScreenBrowseRegionEntity(val sessionId: String, val id: String, val sceneEpoch: Long,
    val version: Long, val status: String, val geometry: String, val successors: String,
    val sourceText: String, val translatedText: String)

@Dao
abstract class ScreenBrowseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(rows: List<ScreenBrowseRegionEntity>)
    @Query("DELETE FROM screen_regions WHERE sessionId = :sessionId")
    abstract suspend fun clearSession(sessionId: String)
    @Query("DELETE FROM screen_regions")
    abstract suspend fun clear()
    @Query("SELECT * FROM screen_regions WHERE sessionId = :sessionId")
    abstract suspend fun regions(sessionId: String): List<ScreenBrowseRegionEntity>
    @Transaction
    open suspend fun replaceScene(sessionId: String, rows: List<ScreenBrowseRegionEntity>) {
        clearSession(sessionId)
        if (rows.isNotEmpty()) insert(rows)
    }
}

@Database(entities = [ScreenBrowseRegionEntity::class], version = 1, exportSchema = true)
abstract class ScreenBrowseDatabase : RoomDatabase() {
    abstract fun regions(): ScreenBrowseDao
    companion object {
        fun build(context: Context): ScreenBrowseDatabase = Room.databaseBuilder(context.applicationContext,
            ScreenBrowseDatabase::class.java, "screen-browse-session.db").build()
    }
}
