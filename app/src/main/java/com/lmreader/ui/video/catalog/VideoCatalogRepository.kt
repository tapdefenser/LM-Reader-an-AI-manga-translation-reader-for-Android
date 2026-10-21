package com.lmreader.ui.video.catalog

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.provider.MediaStore
import androidx.room.withTransaction
import com.lmreader.core.database.media.MediaTaskDatabase
import com.lmreader.core.database.media.VideoCatalogEntry
import com.lmreader.core.database.media.VideoMediaWatermark
import com.lmreader.ui.settings.backup.MediaTaskBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Backend for the system video catalog.
 *
 * The repository only reads MediaStore metadata. It never hashes files, decodes
 * thumbnails, copies originals, or reads `VideoLibraryStore` JSON to discover media.
 * Formal translation results stay owned by `files/videos`; this layer stores the
 * catalog row and user state (shelf/category/position/play count).
 *
 * Rescan contract:
 * - a row's [VideoCatalogEntry.videoId] is kept when the URI (preferred) or the
 *   volume+mediaId locator is found again;
 * - scanner updates use a targeted UPDATE, never a whole-row replace;
 * - a metadata change clears `sourceHash` and bumps `sourceRevision`;
 * - a failed/cancelled volume pass never advances that volume's watermark.
 */
class VideoCatalogRepository(
    context: Context,
    val database: MediaTaskDatabase = MediaTaskDatabase.build(context),
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val dao = database.videoCatalogDao()

    /**
     * Metadata-only projection. No thumbnails, no hash inputs, no file bytes.
     * `RELATIVE_PATH`/`ORIENTATION` are API 29+; pre-Q uses `DATA` only to derive
     * the parent directory (there is no relative-path column there).
     */
    private val projection: Array<String> = buildList {
        add(BaseColumns._ID)
        add(MediaStore.MediaColumns.DISPLAY_NAME)
        add(MediaStore.MediaColumns.SIZE)
        add(MediaStore.MediaColumns.DURATION)
        add(MediaStore.MediaColumns.WIDTH)
        add(MediaStore.MediaColumns.HEIGHT)
        add(MediaStore.MediaColumns.DATE_ADDED)
        add(if(Build.VERSION.SDK_INT >= 30) COLUMN_GENERATION_MODIFIED else MediaStore.MediaColumns.DATE_MODIFIED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.MediaColumns.ORIENTATION)
        } else {
            add(MediaStore.MediaColumns.DATA)
        }
    }.toTypedArray()

    val entries: Flow<List<VideoCatalogEntry>> = dao.observeEntries()

    /** Discovery reads metadata rows; only an opened item may probe its container. */
    suspend fun completeMetadata(id: String): VideoCatalogEntry? {
        val row = dao.byId(id) ?: return null
        if(!row.accessible || row.durationMs > 0 && row.width > 0 && row.height > 0) return row
        val values = com.lmreader.core.model.ResourceGovernor.shared.use("decode") { withContext(Dispatchers.IO) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(appContext, Uri.parse(row.uri))
                fun value(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0L
                longArrayOf(value(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION),
                    value(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                    value(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                    value(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
            } finally { retriever.release() }
        } }
        currentCoroutineContext().ensureActive()
        scanLock.withLock { dao.fillMissingMetadata(id, row.sourceRevision, values[0].coerceAtLeast(0),
            values[1].toInt().coerceIn(0, 16384), values[2].toInt().coerceIn(0, 16384), values[3].toInt()) }
        return dao.byId(id)
    }

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Serializes full/delta scans with legacy identity rebinds. Always acquire this
    // before opening a Room transaction (never the other way around).
    private val scanLock = Mutex()
    // The media permission mode is a *durable* fact: comparing the live permission with
    // the mode that produced the last successful scan is how a process restart detects
    // a grant/revoke/partial-selection change. A missing value forces the first pass to
    // be full, which is also the migration path for installs that predate this key.
    private val permissionPreferences =
        appContext.getSharedPreferences(PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
    @Volatile private var lastPermissionState: String? = permissionPreferences
        .getString(KEY_LAST_PERMISSION, null)
        ?.takeIf { it == FULL || it == PARTIAL || it == NONE }
    @Volatile private var initialValidationPending = true
    @Volatile private var observer: ContentObserver? = null
    @Volatile private var debounceJob: Job? = null

    /**
     * FULL = all videos are visible (READ_MEDIA_VIDEO on 33+, READ_EXTERNAL_STORAGE
     * below that). PARTIAL = Android 14 selected-video access. NONE = no media read
     * permission. MANAGE_EXTERNAL_STORAGE does *not* satisfy MediaStore.Video on
     * Android 13+, so it is intentionally not treated as FULL.
     */
    fun permissionState(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (granted(Manifest.permission.READ_MEDIA_VIDEO)) return FULL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            ) {
                return PARTIAL
            }
            return NONE
        }
        return if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) FULL else NONE
    }

    /**
     * Optional media-library observer with a short debounce.
     *
     * The observer never scans directly: after the debounce it only invokes [onChanged],
     * so the caller remains the single owner of task submission/lifecycle policy. In this
     * app that caller is `VideoMaintenance`, which submits its normal scan task only while
     * `TaskServiceController.canStart()` is true; `MainActivity` keeps its foreground
     * refresh and there is no second scan runner.
     */
    fun start(scope: CoroutineScope, onChanged: () -> Unit) {
        if (!scope.isActive) return
        synchronized(this) {
            if (observer != null) return
            val registered = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    debounceJob?.cancel()
                    debounceJob = scope.launch {
                        delay(OBSERVER_DEBOUNCE_MS)
                        onChanged()
                    }
                }
            }
            observer = registered
            try {
                resolver.registerContentObserver(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    true,
                    registered,
                )
            } catch (e: SecurityException) {
                observer = null
                _error.value = SECURITY_ERROR
                return
            }
            scope.coroutineContext[Job]?.invokeOnCompletion {
                resolver.unregisterContentObserver(registered)
                synchronized(this@VideoCatalogRepository) {
                    if (observer === registered) observer = null
                }
            }
        }
    }

    /**
     * Scan mounted external volumes.
     *
     * [full] forces a complete visible-row refresh. A first scan, a per-volume
     * MediaStore version change, a generation reset, a permission change, and every
     * PARTIAL-permission pass also force a full visible refresh so revoked videos are
     * marked inaccessible rather than silently staying visible.
     */
    suspend fun scan(full: Boolean = false) {
        scanLock.withLock {
            _scanning.value = true
            try {
                withContext(Dispatchers.IO) { scanLocked(full) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "扫描视频媒体库失败：${e.message ?: "未知错误"}"
            } finally {
                _scanning.value = false
            }
        }
    }

    suspend fun find(id: String): VideoCatalogEntry? = dao.byId(id)

    /**
     * Bulk load for a frozen player snapshot. Chunked below SQLite's variable limit;
     * cancellation is checked before and after every chunk so replacing a playlist
     * stops a stale scan promptly. Order is reconstructed by the caller.
     */
    suspend fun findMany(ids: List<String>): List<VideoCatalogEntry> {
        if (ids.isEmpty()) return emptyList()
        val rows = ArrayList<VideoCatalogEntry>(ids.size)
        for (chunk in ids.chunked(500)) {
            currentCoroutineContext().ensureActive()
            rows += dao.byIds(chunk)
            currentCoroutineContext().ensureActive()
        }
        return rows
    }
    /** Only called after explicit file selection and full content verification. */
    suspend fun bindVerified(id: String, candidate: com.lmreader.ui.video.VideoProject, onBound: suspend () -> Unit = {}) = scanLock.withLock {
        database.withTransaction {
            val row = requireNotNull(dao.byId(id)) { "视频引用不存在" }
            val other = dao.byUri(candidate.uri)?.takeIf { it.videoId != id }
            if(other != null) {
                check(database.results().settings(other.videoId) == null &&
                    !java.io.File(appContext.filesDir, "videos/${other.videoId}.json").exists() &&
                    database.tasks().all().none { it.ownerKey.startsWith("video:${other.videoId}") && it.state !in setOf("CANCELLED", "SUCCEEDED", "FAILED") }) {
                    "所选文件已关联另一个有任务或成果的视频，请先在对应条目中处理"
                }
                dao.removeVerifiedDuplicate(other.videoId)
            }
            check(dao.update(row.copy(uri = candidate.uri, name = candidate.name, bytes = candidate.bytes, durationMs = candidate.durationMs,
                width = candidate.width, height = candidate.height, rotation = candidate.rotation, sourceHash = candidate.sha256,
                sourceRevision = UUID.randomUUID().toString(), accessible = true, accessReason = null, mediaId = 0,
                volume = LEGACY_IMPORT_VOLUME, parentPath = "", generation = 0, mediaVersion = 0, migrationComplete = true,
                onShelf = row.onShelf || other?.onShelf == true, playCount = maxOf(row.playCount, other?.playCount ?: 0),
                categoryId = if(row.onShelf) row.categoryId else other?.categoryId ?: row.categoryId,
                positionMs = row.positionMs.coerceAtMost(candidate.durationMs))) == 1)
            onBound()
        }
    }

    suspend fun ensureOnShelf(id: String) {
        dao.addToShelf(id)
    }

    suspend fun setCategory(id: String, categoryId: Long) {
        dao.setCategory(id, categoryId)
    }

    suspend fun removeFromShelf(id: String) {
        dao.removeFromShelf(id)
    }

    suspend fun savePosition(id: String, positionMs: Long) {
        dao.updatePosition(id, positionMs.coerceAtLeast(0L))
    }

    suspend fun recordView(id: String) {
        dao.incrementPlayCount(id)
    }

    /**
     * Idempotent transfer of one formal legacy project.
     *
     * - The supplied legacy UUID wins; an existing catalog row is kept and marked
     *   on-shelf (category is preserved).
     * - If the locator was already scanned under another UUID, that row's `videoId`
     *   is reassigned transactionally to the legacy UUID; no user row/result is
     *   deleted.
     * - A new row is created with migrationComplete = true and generation = 0, so a
     *   MediaStore full scan cannot hide a legacy SAF/file import that is not indexed
     *   by the system media library.
     */
    suspend fun importLegacy(
        id: String,
        uri: String,
        name: String,
        sha256: String,
        bytes: Long,
        durationMs: Long,
        width: Int,
        height: Int,
        rotation: Int,
        positionMs: Long,
    ) {
        val legacyId = runCatching { UUID.fromString(id).toString() }
            .getOrElse { throw IllegalArgumentException("旧视频 ID 不是 UUID") }
        val parsed = Uri.parse(uri)
        require(parsed.scheme == "content" || parsed.scheme == "file") {
            "旧视频引用必须是 content:// 或 file://"
        }
        val locator = legacyLocator(parsed)
        val cleanName = name.ifBlank { "本地视频" }
        val hash = sha256.takeIf { it.isNotBlank() }
        val safeBytes = bytes.coerceAtLeast(0L)
        val safeDuration = durationMs.coerceAtLeast(0L)
        val safeWidth = width.coerceAtLeast(0)
        val safeHeight = height.coerceAtLeast(0)
        val safeRotation = normalizeRotation(rotation)
        val safePosition = positionMs.coerceAtLeast(0L)

        scanLock.withLock {
            database.withTransaction {
                importLegacyLocked(
                    legacyId = legacyId,
                    uri = uri,
                    locator = locator,
                    cleanName = cleanName,
                    hash = hash,
                    safeBytes = safeBytes,
                    safeDuration = safeDuration,
                    safeWidth = safeWidth,
                    safeHeight = safeHeight,
                    safeRotation = safeRotation,
                    safePosition = safePosition,
                )
            }
        }
    }

    private suspend fun importLegacyLocked(
        legacyId: String,
        uri: String,
        locator: LegacyLocator,
        cleanName: String,
        hash: String?,
        safeBytes: Long,
        safeDuration: Long,
        safeWidth: Int,
        safeHeight: Int,
        safeRotation: Int,
        safePosition: Long,
    ) {
        val existing = dao.byId(legacyId)
        if (existing != null) {
            if (existing.migrationComplete || existing.isRestoredNeedsLocation()) {
                // Idempotent retry: do not reset newer progress/statistics. A restored row
                // is never adopted automatically; only an explicit re-location may clear
                // its restored reason and bind a source from this device.
                dao.addToShelf(legacyId)
            } else {
                dao.adoptLegacy(
                    videoId = legacyId,
                    uri = uri,
                    volume = locator.volume,
                    mediaId = locator.mediaId,
                    parentPath = locator.parentPath,
                    name = cleanName,
                    bytes = safeBytes,
                    durationMs = safeDuration,
                    width = safeWidth,
                    height = safeHeight,
                    rotation = safeRotation,
                    positionMs = safePosition,
                    sourceHash = hash,
                )
            }
            return
        }

        val target = dao.byUri(uri)
            ?: if (locator.mediaId > 0L) dao.byLocator(locator.volume, locator.mediaId) else null
        if (target != null) {
            // Never bind an old restored row (and its formal results) to a new device just
            // because the URI or MediaStore locator happens to collide.
            if (target.isRestoredNeedsLocation()) return
            dao.reassignVideoId(target.videoId, legacyId)
            dao.adoptLegacy(
                videoId = legacyId,
                uri = uri,
                volume = locator.volume,
                mediaId = locator.mediaId,
                parentPath = locator.parentPath,
                name = cleanName,
                bytes = safeBytes,
                durationMs = safeDuration,
                width = safeWidth,
                height = safeHeight,
                rotation = safeRotation,
                positionMs = safePosition,
                sourceHash = hash,
            )
            return
        }

        val inserted = dao.insertIgnore(
            VideoCatalogEntry(
                videoId = legacyId,
                uri = uri,
                volume = locator.volume,
                mediaId = locator.mediaId,
                parentPath = locator.parentPath,
                name = cleanName,
                bytes = safeBytes,
                durationMs = safeDuration,
                width = safeWidth,
                height = safeHeight,
                rotation = safeRotation,
                dateAdded = System.currentTimeMillis() / 1000L,
                generation = 0L,
                accessible = true,
                accessReason = null,
                onShelf = true,
                categoryId = 0L,
                positionMs = safePosition,
                playCount = 0L,
                sourceHash = hash,
                sourceRevision = "",
                migrationComplete = true,
            ),
        )
        if (inserted == -1L) {
            // Defensive: another writer inserted while we were in this transaction.
            val raced = dao.byId(legacyId) ?: dao.byUri(uri)
            if (raced != null) {
                if (raced.isRestoredNeedsLocation()) return
                if (raced.videoId != legacyId) dao.reassignVideoId(raced.videoId, legacyId)
                dao.adoptLegacy(
                    videoId = legacyId,
                    uri = uri,
                    volume = locator.volume,
                    mediaId = locator.mediaId,
                    parentPath = locator.parentPath,
                    name = cleanName,
                    bytes = safeBytes,
                    durationMs = safeDuration,
                    width = safeWidth,
                    height = safeHeight,
                    rotation = safeRotation,
                    positionMs = safePosition,
                    sourceHash = hash,
                )
            } else {
                error("旧视频迁移写入冲突：$uri")
            }
        }
    }

    private suspend fun scanLocked(requestedFull: Boolean) {
        _error.value = null
        val permission = permissionState()
        if (permission == NONE) {
            // Permission loss is itself a visibility change. This validation needs no
            // MediaStore query, so it always runs (foreground or maintenance): only
            // previously discovered rows (generation > 0) are revoked. Legacy SAF/file
            // imports (generation 0), restored-needs-location rows, and every formal
            // result table stay untouched.
            try {
                database.withTransaction {
                    dao.markAllDiscoveredUnavailable(REASON_NO_PERMISSION, RESTORED_ACCESS_REASON)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "无法更新视频访问状态：${e.message ?: "未知错误"}"
                return
            }
            recordSuccessfulPermission(permission)
            initialValidationPending = false
            return
        }

        val mounted = try {
            mountedVolumes()
        } catch (e: SecurityException) {
            _error.value = SECURITY_ERROR
            return
        } catch (e: Exception) {
            _error.value = "无法读取存储卷列表：${e.message ?: "未知错误"}"
            return
        }

        val forceFull = requestedFull ||
            permission != lastPermissionState ||
            permission == PARTIAL ||
            (initialValidationPending && dao.hasRevokedRows(RESTORED_ACCESS_REASON))

        // A volume that is no longer mounted is not a user deletion. Keep its rows
        // (and every formal result), but mark them inaccessible and invalidate the
        // version so the returning volume gets a full visible revalidation.
        val knownVolumes = dao.volumes()
        for (volume in knownVolumes) {
            // "legacy" is a synthetic bucket for SAF/file imports, not a storage volume.
            if (volume != LEGACY_IMPORT_VOLUME && volume !in mounted) {
                database.withTransaction {
                    dao.markVolumeUnavailable(volume, REASON_VOLUME_ABSENT, RESTORED_ACCESS_REASON)
                    dao.invalidateWatermarkVersion(volume)
                }
            }
        }

        var firstError: String? = null
        for (volume in mounted.sorted()) {
            currentCoroutineContext().ensureActive()
            try {
                scanVolume(volume, forceFull, permission)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                if (firstError == null) {
                    firstError = "读取视频媒体库失败（$volume）：权限不足"
                }
                // Failed volume: never advance its watermark, never delete its rows.
                database.withTransaction {
                    dao.markVolumeUnavailable(volume, SECURITY_ERROR, RESTORED_ACCESS_REASON)
                    dao.invalidateWatermarkVersion(volume)
                }
            } catch (e: Exception) {
                if (firstError == null) {
                    firstError = "读取视频媒体库失败（$volume）：${e.message ?: "未知错误"}"
                }
                // Transient/provider failure: keep the old watermark and accessibility.
            }
        }
        _error.value = firstError
        if (firstError == null) {
            recordSuccessfulPermission(permission)
            initialValidationPending = false
        }
    }

    /**
     * The permission snapshot advances only after a scan/validation has completed
     * successfully. A failed volume pass must not make a later process mistake an old
     * permission mode for the current one.
     */
    private fun recordSuccessfulPermission(permission: String) {
        lastPermissionState = permission
        permissionPreferences.edit().putString(KEY_LAST_PERMISSION, permission).commit()
    }

    private suspend fun scanVolume(
        volume: String,
        requestedFull: Boolean,
        permission: String,
    ) {
        val version: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getVersion(appContext, volume)
        } else {
            null
        }
        val upperGeneration: Long? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.getGeneration(appContext, volume)
        } else {
            null
        }
        val saved = dao.watermark(volume)
        val effectiveVersion = version ?: LEGACY_VERSION
        val versionChanged = saved != null && saved.version != effectiveVersion
        val generationInvalid = saved != null && upperGeneration != null &&
            (saved.generation <= 0L || saved.generation > upperGeneration)
        val full = requestedFull || saved == null || versionChanged || generationInvalid
        val scanGeneration = maxOf(System.currentTimeMillis(), dao.nextDiscoveryGeneration())

        val stats = when {
            full ->
                scanAllByKeyset(volume, scanGeneration)
            upperGeneration != null ->
                scanDeltaByGeneration(volume, saved!!, upperGeneration, scanGeneration)
            else ->
                scanDeltaFallback(volume, saved!!, scanGeneration)
        }

        database.withTransaction {
            if (full) {
                // Only after every page of this volume succeeded.
                dao.markUnseenUnavailable(
                    volume = volume,
                    generation = scanGeneration,
                    reason = unseenReason(permission),
                    restoredReason = RESTORED_ACCESS_REASON,
                )
            }
            dao.upsertWatermark(
                VideoMediaWatermark(
                    volume = volume,
                    version = effectiveVersion,
                    generation = upperGeneration ?: 0L,
                    lastId = if(full) stats.lastId else maxOf(stats.lastId, saved?.lastId ?: 0L),
                    lastAdded = if(full) stats.lastAdded else maxOf(stats.lastAdded, saved?.lastAdded ?: 0L),
                ),
            )
        }
    }

    /**
     * Full refresh of every visible row. The watermark still records the fixed
     * upper generation captured before the pass; rows changed while this scan runs
     * are simply picked up again by the next delta.
     */
    private suspend fun scanAllByKeyset(
        volume: String,
        scanGeneration: Long,
    ): ScanStats {
        var lastPageId = 0L
        var maxId = 0L
        var maxAdded = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val selection = if (lastPageId > 0L) "${BaseColumns._ID} > ?" else null
            val args = if (lastPageId > 0L) {
                arrayOf(lastPageId.toString())
            } else {
                null
            }
            val page = queryPage(
                volume = volume,
                selection = selection,
                selectionArgs = args,
            )
            if (page.isEmpty()) break
            persistPage(page, scanGeneration)
            maxId = maxOf(maxId, page.maxOf { it.mediaId })
            maxAdded = maxOf(maxAdded, page.maxOf { it.dateAdded })
            // The provider may ignore QUERY_ARG_LIMIT. queryPage itself caps the cursor
            // read at PAGE_SIZE and this _ID keyset is the only page boundary, so a short
            // page does not mean the volume is exhausted.
            val next = page.maxOf { it.mediaId }
            if (next <= lastPageId) break
            lastPageId = next
        }
        return ScanStats(maxId, maxAdded)
    }

    private suspend fun scanDeltaByGeneration(
        volume: String,
        saved: VideoMediaWatermark,
        upperGeneration: Long,
        scanGeneration: Long,
    ): ScanStats {
        var lastPageId = 0L
        var maxId = 0L
        var maxAdded = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val clauses = mutableListOf(
                "$COLUMN_GENERATION_MODIFIED > ?",
                "$COLUMN_GENERATION_MODIFIED <= ?",
            )
            val args = mutableListOf(saved.generation.toString(), upperGeneration.toString())
            if (lastPageId > 0L) {
                clauses += "${BaseColumns._ID} > ?"
                args += lastPageId.toString()
            }
            val page = queryPage(
                volume = volume,
                selection = clauses.joinToString(" AND "),
                selectionArgs = args.toTypedArray(),
            )
            if (page.isEmpty()) break
            persistPage(page, scanGeneration)
            maxId = maxOf(maxId, page.maxOf { it.mediaId })
            maxAdded = maxOf(maxAdded, page.maxOf { it.dateAdded })
            val next = page.maxOf { it.mediaId }
            if (next <= lastPageId) break
            lastPageId = next
        }
        return ScanStats(maxId, maxAdded)
    }

    /**
     * API < 30 fallback. There is no generation here, so the bounded query uses
     * `_ID`/`DATE_ADDED`; repeated rows are de-duplicated by the URI/locator upsert.
     */
    private suspend fun scanDeltaFallback(
        volume: String,
        saved: VideoMediaWatermark,
        scanGeneration: Long,
    ): ScanStats {
        var lastPageId = 0L
        var maxId = 0L
        var maxAdded = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val clauses = mutableListOf(
                "(${BaseColumns._ID} > ? OR ${MediaStore.MediaColumns.DATE_ADDED} > ?)",
            )
            val args = mutableListOf(saved.lastId.toString(), saved.lastAdded.toString())
            if (lastPageId > 0L) {
                clauses += "${BaseColumns._ID} > ?"
                args += lastPageId.toString()
            }
            val page = queryPage(
                volume = volume,
                selection = clauses.joinToString(" AND "),
                selectionArgs = args.toTypedArray(),
            )
            if (page.isEmpty()) break
            persistPage(page, scanGeneration)
            maxId = maxOf(maxId, page.maxOf { it.mediaId })
            maxAdded = maxOf(maxAdded, page.maxOf { it.dateAdded })
            val next = page.maxOf { it.mediaId }
            if (next <= lastPageId) break
            lastPageId = next
        }
        return ScanStats(maxId, maxAdded)
    }

    /**
     * One bounded keyset page. API 26+ uses the Bundle query arguments: the provider
     * receives `QUERY_ARG_LIMIT = 200` plus the raw selection/sort columns, never a
     * `LIMIT` concatenated into SQL sortOrder. Because a provider is allowed to ignore
     * that hint, the cursor loop also stops after exactly [PAGE_SIZE] rows; the caller's
     * strictly-increasing `_ID > lastId` bound makes the next page stable either way.
     */
    private suspend fun queryPage(
        volume: String,
        selection: String?,
        selectionArgs: Array<String>?,
    ): List<ScannedVideo> {
        val collection = collectionUri(volume)
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val args = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, PAGE_SIZE)
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(BaseColumns._ID))
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_ASCENDING,
                )
            }
            resolver.query(collection, projection, args, null)
        } else {
            // minSdk is 26, but this branch documents the pre-query-args contract and
            // keeps the sort boundary identical if the floor is ever lowered.
            resolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${BaseColumns._ID} ASC",
            )
        } ?: throw IllegalStateException("MediaStore 未返回查询结果")
        return cursor.use { rows ->
            val result = ArrayList<ScannedVideo>(minOf(rows.count.coerceAtLeast(0), PAGE_SIZE))
            var read = 0
            while (read < PAGE_SIZE && rows.moveToNext()) {
                read++
                // Per-row cancellation check: a large volume must stop promptly.
                currentCoroutineContext().ensureActive()
                readRow(rows, volume, collection)?.let(result::add)
            }
            result
        }
    }

    private fun readRow(cursor: Cursor, volume: String, collection: Uri): ScannedVideo? {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID))
        if (id <= 0L) return null
        val displayName = cursor.getString(
            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME),
        )
        val bytes = cursor.getLongOr(
            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE),
        )
        val durationMs = cursor.getLongOr(
            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION),
        )
        val width = cursor.getIntOr(
            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH),
        )
        val height = cursor.getIntOr(
            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT),
        )
        val dateAdded = cursor.getLongOr(
            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED),
        )
        val parentPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getString(
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH),
            )?.trimEnd('/').orEmpty()
        } else {
            cursor.getString(
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA),
            )?.substringBeforeLast('/', "").orEmpty()
        }
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            normalizeRotation(
                cursor.getIntOr(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION),
                ),
            )
        } else {
            0
        }
        val name = displayName?.takeIf { it.isNotBlank() } ?: "视频"
        return ScannedVideo(
            uri = ContentUris.withAppendedId(collection, id).toString(),
            volume = volume,
            mediaId = id,
            parentPath = parentPath,
            name = name,
            bytes = bytes.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            width = width.coerceAtLeast(0),
            height = height.coerceAtLeast(0),
            rotation = rotation,
            dateAdded = dateAdded.coerceAtLeast(0L),
            mediaVersion = cursor.getLongOr(cursor.getColumnIndexOrThrow(if(Build.VERSION.SDK_INT >= 30) COLUMN_GENERATION_MODIFIED else MediaStore.MediaColumns.DATE_MODIFIED)),
        )
    }

    private suspend fun persistPage(page: List<ScannedVideo>, scanGeneration: Long) {
        database.withTransaction {
            for (video in page) {
                currentCoroutineContext().ensureActive()
                persist(video, scanGeneration)
            }
        }
    }

    /**
     * Targeted scanner write. Existing `videoId`, shelf/category, position and play
     * count are preserved. Only a content/locator metadata change invalidates the
     * hash and bumps the source revision.
     */
    private suspend fun persist(video: ScannedVideo, scanGeneration: Long) {
        val existing = dao.byUri(video.uri)
            ?: if (video.mediaId > 0L) dao.byLocator(video.volume, video.mediaId) else null
        if (existing == null) {
            val inserted = dao.insertIgnore(
                video.toEntry(UUID.randomUUID().toString(), scanGeneration),
            )
            if (inserted != -1L) return
            val conflict = dao.byUri(video.uri)
                ?: if (video.mediaId > 0L) dao.byLocator(video.volume, video.mediaId) else null
            if (conflict == null) error("视频目录写入冲突：${video.uri}")
            updateExisting(conflict, video, scanGeneration)
            return
        }
        updateExisting(existing, video, scanGeneration)
    }

    private suspend fun updateExisting(
        existing: VideoCatalogEntry,
        discovered: ScannedVideo,
        scanGeneration: Long,
    ) {
        // A restored row keeps its stable id and formal results, but its URI/locator was
        // observed on another device. Matching it by URI or MediaStore id here would
        // silently reuse those old results for different local content. Retain it until
        // an explicit re-location path clears [MediaTaskBackup.RESTORE_ACCESS_REASON].
        if (existing.isRestoredNeedsLocation()) return
        // Preserve successful on-demand probes when a provider keeps returning empty columns.
        val unchangedContent = existing.bytes == discovered.bytes && existing.mediaVersion == discovered.mediaVersion
        val video = if(unchangedContent) discovered.copy(
            durationMs = discovered.durationMs.takeIf { it > 0 } ?: existing.durationMs,
            width = discovered.width.takeIf { it > 0 } ?: existing.width,
            height = discovered.height.takeIf { it > 0 } ?: existing.height,
            rotation = if(discovered.width <= 0 || discovered.height <= 0) existing.rotation else discovered.rotation) else discovered
        val metadataChanged = existing.uri != video.uri ||
            existing.volume != video.volume ||
            existing.mediaId != video.mediaId ||
            existing.parentPath != video.parentPath ||
            existing.name != video.name ||
            existing.bytes != video.bytes ||
            existing.durationMs != video.durationMs ||
            existing.width != video.width ||
            existing.height != video.height ||
            existing.rotation != video.rotation ||
            existing.dateAdded != video.dateAdded || existing.mediaVersion != video.mediaVersion
        dao.updateScannedMetadata(
            videoId = existing.videoId,
            uri = video.uri,
            volume = video.volume,
            mediaId = video.mediaId,
            parentPath = video.parentPath,
            name = video.name,
            bytes = video.bytes,
            durationMs = video.durationMs,
            width = video.width,
            height = video.height,
            rotation = video.rotation,
            dateAdded = video.dateAdded,
            generation = scanGeneration,
            mediaVersion = video.mediaVersion,
            sourceHash = if (metadataChanged) null else existing.sourceHash,
            sourceRevision = if (metadataChanged) {
                UUID.randomUUID().toString()
            } else {
                existing.sourceRevision
            },
        )
    }

    private fun mountedVolumes(): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                setOf(LEGACY_VOLUME)
            } else {
                emptySet()
            }
        }
        return MediaStore.getExternalVolumeNames(appContext)
            .filterNot { it == MediaStore.VOLUME_EXTERNAL }
            .toSet()
    }

    private fun collectionUri(volume: String): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(volume)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    private fun granted(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun unseenReason(permission: String): String =
        if (permission == PARTIAL) REASON_PARTIAL else REASON_UNSEEN

    private fun VideoCatalogEntry.isRestoredNeedsLocation(): Boolean =
        accessReason == MediaTaskBackup.RESTORE_ACCESS_REASON

    private fun legacyLocator(uri: Uri): LegacyLocator {
        if (uri.authority != MediaStore.AUTHORITY) {
            val parent = if (uri.scheme == "file") {
                uri.path?.substringBeforeLast('/').orEmpty()
            } else {
                ""
            }
            return LegacyLocator(LEGACY_IMPORT_VOLUME, 0L, parent)
        }
        val mediaId = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)
            .takeIf { it >= 0L } ?: 0L
        val rawVolume = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: LEGACY_VOLUME
        // Q+ exposes the primary volume as external_primary; legacy formal URIs often
        // still carry the aggregate "external" name. Canonicalizing lets the normal
        // volume+mediaId locator merge the imported row with its MediaStore discovery.
        val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            rawVolume == MediaStore.VOLUME_EXTERNAL
        ) {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        } else {
            rawVolume
        }
        return LegacyLocator(volume, mediaId, "")
    }

    private fun normalizeRotation(value: Int): Int =
        if (value in setOf(0, 90, 180, 270)) value else 0

    private data class ScannedVideo(
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
        val mediaVersion: Long,
    ) {
        fun toEntry(videoId: String, scanGeneration: Long) = VideoCatalogEntry(
            videoId = videoId,
            uri = uri,
            volume = volume,
            mediaId = mediaId,
            parentPath = parentPath,
            name = name,
            bytes = bytes,
            durationMs = durationMs,
            width = width,
            height = height,
            rotation = rotation,
            dateAdded = dateAdded,
            generation = scanGeneration,
            accessible = true,
            accessReason = null,
            onShelf = false,
            categoryId = 0L,
            positionMs = 0L,
            playCount = 0L,
            sourceHash = null,
            sourceRevision = "",
            migrationComplete = false,
            mediaVersion = mediaVersion,
        )
    }

    private data class ScanStats(val lastId: Long, val lastAdded: Long)

    private data class LegacyLocator(
        val volume: String,
        val mediaId: Long,
        val parentPath: String,
    )

    companion object {
        const val FULL = "FULL"
        const val PARTIAL = "PARTIAL"
        const val NONE = "NONE"

        private const val PAGE_SIZE = 200
        private const val OBSERVER_DEBOUNCE_MS = 1_500L
        // MediaStore.MediaColumns.GENERATION_MODIFIED is API 30; the column name is stable.
        private const val COLUMN_GENERATION_MODIFIED = "generation_modified"
        private const val LEGACY_VOLUME = "external"
        private const val LEGACY_IMPORT_VOLUME = "legacy"
        private const val LEGACY_VERSION = "legacy"
        private const val SECURITY_ERROR = "无法读取视频媒体库（权限不足）"
        private const val REASON_VOLUME_ABSENT = "存储卷未挂载"
        private const val REASON_UNSEEN = "媒体库中已不可见"
        private const val REASON_PARTIAL = "当前仅可访问部分视频，条目不在可见范围"
        private const val REASON_NO_PERMISSION = "未授予视频读取权限"
        private val RESTORED_ACCESS_REASON = MediaTaskBackup.RESTORE_ACCESS_REASON
        private const val PERMISSION_PREFERENCES = "video-catalog-state"
        private const val KEY_LAST_PERMISSION = "last-successful-permission"
    }
}

private fun Cursor.getLongOr(index: Int): Long = if (isNull(index)) 0L else getLong(index)

private fun Cursor.getIntOr(index: Int): Int = if (isNull(index)) 0 else getInt(index)
