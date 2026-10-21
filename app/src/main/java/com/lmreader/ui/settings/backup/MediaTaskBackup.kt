package com.lmreader.ui.settings.backup

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lmreader.ui.queue.ExportFiles
import com.lmreader.ui.reader.translation.ReaderPageArtifactStore
import com.lmreader.ui.reader.translation.ReaderPageTranslation
import com.lmreader.ui.video.VideoFrameProof
import com.lmreader.ui.video.VideoLibraryStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Backup boundary for the unified media/task work database.
 *
 * The archive document is deliberately a closed, schema-versioned projection:
 * table and column names always come from [COLUMNS], never from the ZIP. A restore
 * validates the document against the live `PRAGMA` schema before touching the
 * destination. `restored = true` merges incoming rows (local primary keys and local
 * newer/human data win); `restored = false` is the local rollback path and replaces
 * exactly the seven fixed tables.
 *
 * MediaStore ids are not portable. Every incoming catalog row is marked inaccessible;
 * a row whose URI/locator conflicts with a different local `videoId` keeps its stable
 * id but gets a synthetic unresolved locator so it can never be rebound to the local
 * file by URI/name/size.
 */
object MediaTaskBackup {
    const val FILE = "media-work.json"
    const val SCHEMA = 1
    const val RESTORE_ACCESS_REASON = "从备份恢复，请重新定位或授权原视频"
    const val UNRESOLVED_URI_PREFIX = "content://com.lmreader.unresolved/"

    private const val TASK_PAUSE_REASON = "RESTORE"
    private const val MAX_TEXT = 8_000_000
    private const val MAX_TASK_JSON = 4_000_000
    private const val MAX_TOTAL_ROWS = 3_000_000L
    private val UUID_VALUE = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val SHA256_VALUE = Regex("[a-f0-9]{64}")
    private val TERMINAL_STATES = setOf("CANCELLED", "SUCCEEDED", "FAILED")

    private val TABLES = listOf(
        "tasks",
        "task_requests",
        "video_catalog",
        "video_watermarks",
        "video_frames",
        "video_regions",
        "video_cues",
        "video_settings",
    )

    /** Fixed projection of the current exported Room schema. Extra/missing columns fail validation. */
    private val COLUMNS = mapOf(
        "task_requests" to listOf("requestId", "taskId", "generation", "scopeKey", "status"),
        "tasks" to listOf(
            "taskId", "type", "title", "ownerKey", "activeKey", "payload", "parentTaskId", "source",
            "priority", "orderIndex", "state", "userPaused", "pauseReason", "generation", "checkpoint",
            "completed", "total", "stage", "waitingReason", "failure", "remoteOutcomeUnknown",
            "recoverable", "createdAt", "updatedAt",
        ),
        "video_catalog" to listOf(
            "videoId", "uri", "volume", "mediaId", "parentPath", "name", "bytes", "durationMs", "width",
            "height", "rotation", "dateAdded", "generation", "accessible", "accessReason", "onShelf",
            "categoryId", "positionMs", "playCount", "sourceHash", "sourceRevision", "migrationComplete", "mediaVersion",
        ),
        "video_watermarks" to listOf("volume", "version", "generation", "lastId", "lastAdded"),
        "video_frames" to listOf(
            "videoId", "sourceHash", "identity", "sampleIndex", "targetUs", "ptsUs", "untilUs", "pageId",
            "resultRevision", "noText", "producerTaskId",
        ),
        "video_regions" to listOf(
            "intervalId", "videoId", "sourceHash", "pageId", "regionId", "startMs", "endMs",
            "observedStartMs", "observedEndMs", "manualTiming", "deleted", "revision", "trackId",
        ),
        "video_cues" to listOf(
            "videoId", "cueId", "sourceHash", "trackId", "startMs", "endMs", "source", "translation",
            "deleted", "manuallyEdited", "revision", "identity", "producerTaskId",
        ),
        "video_settings" to listOf("videoId", "json", "style", "revision", "migrated"),
    )

    /** The archive already caps each entry at 64 MB; these row caps reject decompression/parse abuse early. */
    private val MAX_ROWS = mapOf(
        "task_requests" to 2_000_000,
        "tasks" to 200_000,
        "video_catalog" to 500_000,
        "video_watermarks" to 10_000,
        "video_frames" to 2_000_000,
        "video_regions" to 2_000_000,
        "video_cues" to 2_000_000,
        "video_settings" to 500_000,
    )

    fun snapshot(db: SupportSQLiteDatabase, root: File, secrets: Boolean) {
        val document = JSONObject().put("schema", SCHEMA)
        val tables = JSONObject()
        for (table in TABLES) {
            schema(db, table) // Fail closed on unexpected schema drift before writing the archive.
            val columns = COLUMNS.getValue(table)
            val rows = JSONArray()
            db.query("SELECT ${columns.joinToString { "`$it`" }} FROM `$table`").use { cursor ->
                require(cursor.columnCount == columns.size) { "媒体任务数据库结构不匹配：$table" }
                while (cursor.moveToNext()) {
                    val row = JSONArray()
                    for (index in columns.indices) row.put(cursorValue(cursor, index))
                    if (table == "tasks" && !secrets) redactTaskJson(row, columns)
                    rows.put(row)
                }
            }
            tables.put(table, JSONObject().put("columns", JSONArray(columns)).put("rows", rows))
        }
        document.put("tables", tables)
        val text = document.toString()
        require(text.toByteArray(Charsets.UTF_8).size <= BackupArchive.MAX_ENTRY) { "媒体任务备份超过大小上限" }
        File(root, FILE).writeText(text)
    }

    fun validate(db: SupportSQLiteDatabase, root: File, videos: File?, semantic: Boolean = true) {
        val file = File(root, FILE)
        require(file.isFile && file.length() in 1..BackupArchive.MAX_ENTRY) { "媒体任务备份缺失或超过大小上限" }
        val document = JSONObject(file.readText())
        require(document.keys().asSequence().toSet() == setOf("schema", "tables")) { "媒体任务备份包含未知字段" }
        require(document.get("schema") is Number && document.getInt("schema") == SCHEMA) { "不支持的媒体任务备份版本" }
        val tables = document.getJSONObject("tables")
        require(tables.keys().asSequence().toSet() == TABLES.toSet()) { "媒体任务备份包含未知表或缺少表" }
        val schemas = TABLES.associateWith { schema(db, it) }
        val documents = mutableMapOf<String, JSONObject>()
        var totalRows = 0L
        for (table in TABLES) {
            val tableSchema = schemas.getValue(table)
            val entry = tables.getJSONObject(table)
            require(entry.keys().asSequence().toSet() == setOf("columns", "rows")) { "媒体任务表包含未知字段：$table" }
            val savedColumns = entry.getJSONArray("columns")
            val columns = (0 until savedColumns.length()).map { savedColumns.getString(it) }
            require(columns == COLUMNS.getValue(table)) { "媒体任务备份列不匹配：$table" }
            val rows = entry.getJSONArray("rows")
            require(rows.length() <= MAX_ROWS.getValue(table)) { "媒体任务表行数超过上限：$table" }
            totalRows += rows.length()
            require(totalRows <= MAX_TOTAL_ROWS) { "媒体任务备份行数超过上限" }
            val byName = tableSchema.columns.associateBy { it.name }
            val uniquePositions = tableSchema.uniqueKeys.map { key ->
                key.map { name -> columns.indexOf(name) }.also { positions ->
                    require(positions.all { it >= 0 }) { "媒体任务备份唯一键不匹配：$table" }
                }
            }
            val seen = List(uniquePositions.size) { mutableSetOf<List<Any?>>() }
            for (index in 0 until rows.length()) {
                val row = rows.getJSONArray(index)
                require(row.length() == columns.size) { "媒体任务备份行宽不匹配：$table" }
                val values = arrayOfNulls<Any?>(columns.size)
                for (column in columns.indices) {
                    values[column] = typedValue(
                        byName.getValue(columns[column]),
                        row.get(column),
                        "$table.${columns[column]}",
                    )
                }
                uniquePositions.forEachIndexed { keyIndex, positions ->
                    val key = positions.map { values[it] }
                    // SQLite unique indexes allow multiple NULLs.
                    if (key.none { it == null }) require(seen[keyIndex].add(key)) { "媒体任务备份包含重复键：$table" }
                }
            }
            documents[table] = entry
        }
        if (semantic) {
            validateTasks(documents.getValue("tasks"), COLUMNS.getValue("tasks"))
            val taskColumns = COLUMNS.getValue("tasks")
            val taskRows = documents.getValue("tasks").getJSONArray("rows")
            val ids = (0 until taskRows.length()).map { taskRows.getJSONArray(it).getString(taskColumns.indexOf("taskId")) }.toSet()
            val requests = documents.getValue("task_requests").getJSONArray("rows")
            val requestColumns = COLUMNS.getValue("task_requests")
            for(index in 0 until requests.length()) {
                val row = requests.getJSONArray(index)
                require(row.getString(requestColumns.indexOf("taskId")) in ids) { "请求记录缺少对应任务" }
                require(row.getString(requestColumns.indexOf("requestId")).length in 1..200) { "请求编号无效" }
                require(row.getString(requestColumns.indexOf("scopeKey")).length <= 2000) { "请求成果范围无效" }
                require(row.getLong(requestColumns.indexOf("generation")) >= 0) { "请求执行代次无效" }
                require(row.getString(requestColumns.indexOf("status")) in setOf("PREPARED", "SENT", "RECEIVED", "UNKNOWN", "REJECTED", "PUBLISHED", "RETRY_AUTHORIZED")) { "请求状态无效" }
            }
            validateVideoValues(documents, COLUMNS)
            if (videos != null) validateReferences(documents, videos)
        }
    }

    /** Incoming result rows are merged only when their formal video/header/artifact exists after file merge. */
    fun restore(db: SupportSQLiteDatabase, root: File, restored: Boolean, videos: File) {
        val file = File(root, FILE)
        if (!file.isFile) return // Old archive without media-work.json keeps the whole local work database.
        // Structural validation only: incoming semantic checks already ran before application,
        // and the rollback path must restore captured rows exactly, including odd local states.
        validate(db, root, null, semantic = false)
        val document = JSONObject(file.readText())
        if (restored) merge(db, document, videos) else replace(db, document)
    }

    /** Validates headers, referenced formal frame documents and optional manual proofs. */
    fun validateVideoLibrary(videos: File) {
        val projects = VideoLibraryStore(videos).projects.value
        for (project in projects) {
            for (frame in project.frames) {
                val saved = loadArtifact(videos, project.id, frame.pageId, project.sha256)
                    ?: error("视频画面成果缺失：${project.id}")
                require(saved.resultIdentity == null || saved.resultIdentity == frame.identity) {
                    "视频画面成果身份不匹配"
                }
                val proofRoot = File(videos, "${project.id}/proofs")
                if (proofRoot.isDirectory) VideoFrameProof.load(proofRoot, frame.pageId)
            }
        }
    }

    /** `restored = true`: copy only absent files; an existing local header/artifact always wins. */
    fun mergeVideoLibrary(incoming: File, destination: File) {
        if (!incoming.isDirectory) return
        check(destination.isDirectory || destination.mkdirs())
        val root = destination.canonicalFile
        incoming.walkTopDown().filter { it.isFile }.forEach { source ->
            val relative = source.relativeTo(incoming).invariantSeparatorsPath
            require(BackupArchive.allowed("videos/$relative")) { "视频备份文件不受支持" }
            val target = File(root, relative).canonicalFile
            require(target.path.startsWith(root.path + File.separator)) { "视频备份路径越界" }
            if (target.exists()) {
                if(relative.matches(Regex("[a-f0-9-]{36}\\.json"))) {
                    val imported = VideoLibraryStore.decode(JSONObject(source.readText()))
                    val local = VideoLibraryStore.decode(JSONObject(target.readText()))
                    if(imported.sha256 != local.sha256) {
                        val history = File(root, "${imported.id}/sources/${imported.sha256}.json")
                        if(!history.exists()) {
                            check(history.parentFile!!.mkdirs() || history.parentFile!!.isDirectory)
                            ExportFiles.commit(history) { it.write(source.readBytes()) }
                        }
                    }
                }
                return@forEach
            }
            var parent: File? = target.parentFile
            while (parent != null && parent.path.startsWith(root.path)) {
                parent.mkdirs()
                parent = parent.parentFile
            }
            ExportFiles.commit(target) { output -> source.inputStream().use { it.copyTo(output) } }
        }
    }

    /** `restored = false`: exact rollback of the locally captured video directory. */
    fun replaceVideoLibrary(incoming: File, destination: File) {
        check(!destination.exists() || destination.deleteRecursively()) { "无法清理回滚视频目录" }
        check(destination.mkdirs() || destination.isDirectory) { "无法重建视频目录" }
        mergeVideoLibrary(incoming, destination)
    }

    private fun merge(db: SupportSQLiteDatabase, document: JSONObject, videos: File) {
        val tables = document.getJSONObject("tables")
        val importedTasks = mergeTasks(db, tables.getJSONObject("tasks"))
        val requests = tables.getJSONObject("task_requests")
        val requestColumns = columns(requests)
        val requestRows = requests.getJSONArray("rows")
        for(index in 0 until requestRows.length()) {
            val values = rowValues(requestColumns, requestRows.getJSONArray(index))
            if(values["taskId"] in importedTasks) insert(db, "task_requests", requestColumns, values)
        }
        mergeCatalog(db, tables.getJSONObject("video_catalog"))
        mergeWatermarks(db, tables.getJSONObject("video_watermarks"))
        mergeFrames(db, tables.getJSONObject("video_frames"), videos)
        mergeRegions(db, tables.getJSONObject("video_regions"), videos)
        mergeCues(db, tables.getJSONObject("video_cues"), videos)
        mergeSettings(db, tables.getJSONObject("video_settings"))
    }

    private fun replace(db: SupportSQLiteDatabase, document: JSONObject) {
        val tables = document.getJSONObject("tables")
        for (table in TABLES.asReversed()) db.execSQL("DELETE FROM `$table`")
        for (table in TABLES) {
            val entry = tables.getJSONObject(table)
            val columns = columns(entry)
            val rows = entry.getJSONArray("rows")
            for (index in 0 until rows.length()) {
                insert(db, table, columns, rowValues(columns, rows.getJSONArray(index)), ignore = false)
            }
        }
    }

    private fun mergeTasks(db: SupportSQLiteDatabase, entry: JSONObject): Set<String> {
        val columns = columns(entry)
        val rows = entry.getJSONArray("rows")
        val existingIds = mutableSetOf<String>()
        val existingActive = mutableSetOf<String>()
        db.query("SELECT taskId, activeKey FROM `tasks`").use { cursor ->
            while (cursor.moveToNext()) {
                existingIds += cursor.getString(0)
                if (!cursor.isNull(1)) existingActive += cursor.getString(1)
            }
        }
        val pending = mutableListOf<MutableMap<String, Any?>>()
        for (index in 0 until rows.length()) {
            val values = rowValues(columns, rows.getJSONArray(index))
            val taskId = values.getValue("taskId") as String
            if (taskId in existingIds) continue // Local row and local state win.
            sanitizeTask(values)
            val activeKey = values["activeKey"] as String?
            if (activeKey != null && activeKey in existingActive) continue // Never create a duplicate execution intent.
            pending += values
        }
        // Do not attach imported children to a pre-existing local parent: the scheduler's
        // parent reconciliation could otherwise mutate unrelated local state.
        val restoredIds = pending.map { it.getValue("taskId") as String }.toSet()
        val imported = mutableSetOf<String>()
        for (values in pending) {
            val parent = values["parentTaskId"] as String?
            if (parent != null && parent !in restoredIds) values["parentTaskId"] = null
            val activeKey = values["activeKey"] as String?
            if (activeKey != null && activeKey in existingActive) continue
            insert(db, "tasks", columns, values)
            imported += values.getValue("taskId") as String
            existingIds += values.getValue("taskId") as String
            if (activeKey != null) existingActive += activeKey
        }
        return imported
    }

    private fun sanitizeTask(values: MutableMap<String, Any?>) {
        values["payload"] = redactJsonText(values.getValue("payload") as String)
        values["checkpoint"] = redactJsonText(values.getValue("checkpoint") as String)
        val state = values.getValue("state") as String
        if (state in TERMINAL_STATES) {
            // A terminal row must not keep a unique activeKey and block a future explicit task.
            values["activeKey"] = null
            return
        }
        val unknownOutcome = (values["remoteOutcomeUnknown"] as? Number)?.toLong()?.let { it != 0L } ?: false
        values["state"] = if (unknownOutcome) "INTERRUPTED" else "PAUSED"
        values["userPaused"] = 1L
        values["pauseReason"] = TASK_PAUSE_REASON
        values["recoverable"] = 0L
        // An ambiguous remote outcome must never be retried automatically. The explicit
        // ACCESS reason also covers the restored, temporarily inaccessible video source.
        if (unknownOutcome) values["waitingReason"] = "ACCESS"
    }

    private fun mergeCatalog(db: SupportSQLiteDatabase, entry: JSONObject) {
        val columns = columns(entry)
        val rows = entry.getJSONArray("rows")
        val ids = mutableSetOf<String>()
        val uris = mutableSetOf<String>()
        val locators = mutableSetOf<Pair<String, Long>>()
        db.query("SELECT videoId, uri, volume, mediaId FROM `video_catalog`").use { cursor ->
            while (cursor.moveToNext()) {
                ids += cursor.getString(0)
                uris += cursor.getString(1)
                val mediaId = cursor.getLong(3)
                if (mediaId > 0L) locators += cursor.getString(2) to mediaId
            }
        }
        for (index in 0 until rows.length()) {
            val values = rowValues(columns, rows.getJSONArray(index))
            val videoId = values.getValue("videoId") as String
            if (videoId in ids) continue // Same stable UUID: preserve the current local row untouched.
            val volume = values.getValue("volume") as String
            var uri = values.getValue("uri") as String
            val mediaId = (values.getValue("mediaId") as Number).toLong()
            val uriConflict = uri in uris
            val locatorConflict = mediaId > 0L && (volume to mediaId) in locators
            if (uriConflict || locatorConflict) {
                var unresolved = UNRESOLVED_URI_PREFIX + videoId
                if (unresolved in uris) unresolved = UNRESOLVED_URI_PREFIX + UUID.randomUUID().toString()
                uri = unresolved
            }
            // The old MediaStore locator was not verified on this device. It must be
            // re-located/re-authorized before any automatic source binding.
            values["uri"] = uri
            values["mediaId"] = 0L
            values["accessible"] = 0L
            values["accessReason"] = RESTORE_ACCESS_REASON
            // Prevent the startup legacy-project importer from adopting this row and clearing
            // the restored inaccessible state without a user re-location/authorization.
            values["migrationComplete"] = 1L
            insert(db, "video_catalog", columns, values)
            ids += videoId
            uris += uri
        }
    }

    private fun mergeWatermarks(db: SupportSQLiteDatabase, entry: JSONObject) {
        val columns = columns(entry)
        val rows = entry.getJSONArray("rows")
        for (index in 0 until rows.length()) {
            insert(db, "video_watermarks", columns, rowValues(columns, rows.getJSONArray(index)))
        }
    }

    private fun mergeFrames(db: SupportSQLiteDatabase, entry: JSONObject, videos: File) {
        val columns = columns(entry)
        val rows = entry.getJSONArray("rows")
        for (index in 0 until rows.length()) {
            val values = rowValues(columns, rows.getJSONArray(index))
            if (!artifactMatches(videos, values, revisionKey = "resultRevision", identityKey = "identity")) continue
            insert(db, "video_frames", columns, values)
        }
    }

    private fun mergeRegions(db: SupportSQLiteDatabase, entry: JSONObject, videos: File) {
        val columns = columns(entry)
        val rows = entry.getJSONArray("rows")
        for (index in 0 until rows.length()) {
            val values = rowValues(columns, rows.getJSONArray(index))
            if (!artifactMatches(videos, values)) continue
            insert(db, "video_regions", columns, values)
        }
    }

    private fun mergeCues(db: SupportSQLiteDatabase, entry: JSONObject, videos: File) {
        val columns = columns(entry)
        val rows = entry.getJSONArray("rows")
        for (index in 0 until rows.length()) {
            val values = rowValues(columns, rows.getJSONArray(index))
            if (!headerExists(videos, values.getValue("videoId") as String)) continue
            insert(db, "video_cues", columns, values)
        }
    }

    private fun mergeSettings(db: SupportSQLiteDatabase, entry: JSONObject) {
        val columns = columns(entry)
        val rows = entry.getJSONArray("rows")
        for (index in 0 until rows.length()) {
            val values = rowValues(columns, rows.getJSONArray(index))
            val videoId = values.getValue("videoId") as String
            if (!catalogExists(db, videoId)) continue
            insert(db, "video_settings", columns, values)
        }
    }

    private fun artifactMatches(
        videos: File,
        values: Map<String, Any?>,
        revisionKey: String? = null,
        identityKey: String? = null,
    ): Boolean {
        val videoId = values["videoId"] as? String ?: return false
        val sourceHash = values["sourceHash"] as? String ?: return false
        val pageId = values["pageId"] as? String ?: return false
        if (!headerExists(videos, videoId)) return false
        if(VideoLibraryStore(videos).sourceHeader(videoId, sourceHash) == null) return false
        val saved = try { loadArtifact(videos, videoId, pageId, sourceHash) } catch (_: Exception) { return false } ?: return false
        if (revisionKey != null && saved.revision != values[revisionKey]) return false
        if (identityKey != null) {
            val identity = values[identityKey] as? String ?: return false
            if (saved.resultIdentity != null && saved.resultIdentity != identity) return false
        }
        return true
    }

    private fun catalogExists(db: SupportSQLiteDatabase, videoId: String): Boolean =
        db.query("SELECT 1 FROM `video_catalog` WHERE videoId=? LIMIT 1", arrayOf<Any?>(videoId)).use { it.moveToFirst() }

    private fun headerExists(videos: File, videoId: String): Boolean =
        videoId.matches(UUID_VALUE) && File(videos, "$videoId.json").isFile

    private fun loadArtifact(videos: File, videoId: String, pageId: String, sourceHash: String): ReaderPageTranslation? {
        if (!videoId.matches(UUID_VALUE) || !videos.isDirectory) return null
        val frames = File(videos, "$videoId/frames")
        if (!frames.isDirectory) return null
        // The page's sourceSha256 hashes decoded original pixels, not the video container.
        // Video source identity is checked against the formal project/source header separately.
        return ReaderPageArtifactStore(frames).current(pageId)
    }

    private fun insert(
        db: SupportSQLiteDatabase,
        table: String,
        columns: List<String>,
        values: Map<String, Any?>,
        ignore: Boolean = true,
    ) {
        val verb = if (ignore) "INSERT OR IGNORE" else "INSERT"
        val sql = "$verb INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${columns.joinToString { "?" }})"
        db.execSQL(sql, Array<Any?>(columns.size) { index -> values[columns[index]] })
    }

    private fun columns(entry: JSONObject): List<String> {
        val array = entry.getJSONArray("columns")
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun rowValues(columns: List<String>, row: JSONArray): MutableMap<String, Any?> {
        val values = LinkedHashMap<String, Any?>(columns.size)
        for (index in columns.indices) values[columns[index]] = row.get(index).takeUnless { it == JSONObject.NULL }
        return values
    }

    private fun validateTasks(entry: JSONObject, columns: List<String>) {
        val rows = entry.getJSONArray("rows")
        val taskId = columns.indexOf("taskId")
        val state = columns.indexOf("state")
        val payload = columns.indexOf("payload")
        val checkpoint = columns.indexOf("checkpoint")
        val parents = mutableMapOf<String, String?>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONArray(index)
            val id = row.getString(taskId)
            require(id.isNotBlank() && id.length <= 200) { "媒体任务编号无效" }
            parents[id] = row.optString(columns.indexOf("parentTaskId")).takeUnless { it.isBlank() || it == "null" }
            require(row.getString(state).length in 1..32) { "媒体任务状态无效" }
            val payloadText = row.getString(payload)
            require(payloadText.length <= MAX_TASK_JSON && isJsonDocument(payloadText)) { "媒体任务负载损坏" }
            val checkpointText = row.getString(checkpoint)
            require(checkpointText.length <= MAX_TASK_JSON) { "媒体任务检查点损坏" }
            val trimmed = checkpointText.trimStart()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                require(isJsonDocument(checkpointText)) { "媒体任务检查点损坏" }
            }
        }
        for(id in parents.keys) {
            val visited = mutableSetOf<String>()
            var current: String? = id
            while(current != null) {
                require(visited.add(current)) { "任务父子关系包含循环" }
                require(current in parents) { "子任务缺少父任务" }
                current = parents[current]
            }
        }
    }

    private fun validateVideoValues(documents: Map<String, JSONObject>, columns: Map<String, List<String>>) {
        val catalog = documents.getValue("video_catalog")
        val catalogColumns = columns.getValue("video_catalog")
        for (index in 0 until catalog.getJSONArray("rows").length()) {
            val row = catalog.getJSONArray("rows").getJSONArray(index)
            val videoId = row.getString(catalogColumns.indexOf("videoId"))
            require(videoId.matches(UUID_VALUE)) { "视频编号无效" }
            val uri = row.getString(catalogColumns.indexOf("uri"))
            require(uri.startsWith("content://") || uri.startsWith("file://")) { "视频引用协议无效" }
            require(row.getString(catalogColumns.indexOf("volume")).length <= 200) { "视频存储卷无效" }
            require(row.getString(catalogColumns.indexOf("name")).length <= 1000) { "视频名称过长" }
            require(row.getLong(catalogColumns.indexOf("mediaId")) >= 0L) { "视频媒体编号无效" }
            require(row.getLong(catalogColumns.indexOf("bytes")) >= 0L) { "视频大小无效" }
            require(row.getLong(catalogColumns.indexOf("durationMs")) >= 0L) { "视频时长无效" }
            require(row.getInt(catalogColumns.indexOf("width")) in 0..32_768) { "视频宽度无效" }
            require(row.getInt(catalogColumns.indexOf("height")) in 0..32_768) { "视频高度无效" }
            require(row.getInt(catalogColumns.indexOf("rotation")) in setOf(0, 90, 180, 270)) { "视频旋转角无效" }
            require(row.getLong(catalogColumns.indexOf("generation")) >= 0L) { "视频发现代次无效" }
            require(row.getInt(catalogColumns.indexOf("accessible")) in 0..1) { "视频可访问标记无效" }
            require(row.getInt(catalogColumns.indexOf("onShelf")) in 0..1) { "视频书架标记无效" }
            require(row.getInt(catalogColumns.indexOf("migrationComplete")) in 0..1) { "视频迁移标记无效" }
            require(row.getLong(catalogColumns.indexOf("positionMs")) >= 0L) { "视频播放位置无效" }
            require(row.getLong(catalogColumns.indexOf("playCount")) >= 0L) { "视频播放次数无效" }
            if (!row.isNull(catalogColumns.indexOf("sourceHash"))) {
                require(row.getString(catalogColumns.indexOf("sourceHash")).matches(SHA256_VALUE)) { "视频源摘要无效" }
            }
        }

        val frames = documents.getValue("video_frames")
        val frameColumns = columns.getValue("video_frames")
        for (index in 0 until frames.getJSONArray("rows").length()) {
            val row = frames.getJSONArray("rows").getJSONArray(index)
            val videoId = row.getString(frameColumns.indexOf("videoId"))
            require(videoId.matches(UUID_VALUE)) { "画面视频编号无效" }
            require(row.getString(frameColumns.indexOf("sourceHash")).matches(SHA256_VALUE)) { "画面源摘要无效" }
            require(row.getString(frameColumns.indexOf("identity")).matches(SHA256_VALUE)) { "画面身份无效" }
            require(row.getString(frameColumns.indexOf("pageId")).startsWith("video:$videoId:")) { "画面编号无效" }
            require(row.getInt(frameColumns.indexOf("noText")) in 0..1) { "画面无文字标记无效" }
        }

        val regions = documents.getValue("video_regions")
        val regionColumns = columns.getValue("video_regions")
        for (index in 0 until regions.getJSONArray("rows").length()) {
            val row = regions.getJSONArray("rows").getJSONArray(index)
            val videoId = row.getString(regionColumns.indexOf("videoId"))
            require(videoId.matches(UUID_VALUE)) { "区域视频编号无效" }
            require(row.getString(regionColumns.indexOf("pageId")).startsWith("video:$videoId:")) { "区域画面编号无效" }
            require(row.getString(regionColumns.indexOf("sourceHash")).matches(SHA256_VALUE)) { "区域源摘要无效" }
            require(row.getLong(regionColumns.indexOf("endMs")) > row.getLong(regionColumns.indexOf("startMs"))) { "区域时间无效" }
            require(row.getInt(regionColumns.indexOf("manualTiming")) in 0..1) { "区域人工时间标记无效" }
            require(row.getInt(regionColumns.indexOf("deleted")) in 0..1) { "区域删除标记无效" }
        }

        val cues = documents.getValue("video_cues")
        val cueColumns = columns.getValue("video_cues")
        for (index in 0 until cues.getJSONArray("rows").length()) {
            val row = cues.getJSONArray("rows").getJSONArray(index)
            val videoId = row.getString(cueColumns.indexOf("videoId"))
            require(videoId.matches(UUID_VALUE)) { "字幕视频编号无效" }
            require(row.getString(cueColumns.indexOf("cueId")).isNotBlank()) { "字幕编号无效" }
            require(row.getString(cueColumns.indexOf("sourceHash")).matches(SHA256_VALUE)) { "字幕源摘要无效" }
            require(row.getLong(cueColumns.indexOf("endMs")) > row.getLong(cueColumns.indexOf("startMs"))) { "字幕时间无效" }
            require(row.getString(cueColumns.indexOf("translation")).length <= 4 * 1024 * 1024) { "字幕译文过长" }
            require(row.getInt(cueColumns.indexOf("deleted")) in 0..1) { "字幕删除标记无效" }
            require(row.getInt(cueColumns.indexOf("manuallyEdited")) in 0..1) { "字幕人工标记无效" }
            if (!row.isNull(cueColumns.indexOf("identity"))) {
                // Legacy projects can carry the sentinel "stale" here, not only a workflow identity.
                require(row.getString(cueColumns.indexOf("identity")).length <= 200) { "字幕身份无效" }
            }
        }

        val settings = documents.getValue("video_settings")
        val settingColumns = columns.getValue("video_settings")
        for (index in 0 until settings.getJSONArray("rows").length()) {
            val row = settings.getJSONArray("rows").getJSONArray(index)
            require(row.getString(settingColumns.indexOf("videoId")).matches(UUID_VALUE)) { "视频设置编号无效" }
            require(isJsonDocument(row.getString(settingColumns.indexOf("json")))) { "视频设置内容损坏" }
            require(isJsonDocument(row.getString(settingColumns.indexOf("style")))) { "视频样式内容损坏" }
            require(row.getInt(settingColumns.indexOf("migrated")) in 0..1) { "视频设置迁移标记无效" }
        }
    }

    private fun validateReferences(documents: Map<String, JSONObject>, videos: File) {
        val store = VideoLibraryStore(videos)
        val projects = try {
            store.projects.value.associateBy { it.id }
        } catch (error: Exception) {
            throw IllegalArgumentException("视频成果元数据损坏：${error.message}", error)
        }
        val frames = documents.getValue("video_frames")
        val frameColumns = columns(frames)
        for (index in 0 until frames.getJSONArray("rows").length()) {
            val row = frames.getJSONArray("rows").getJSONArray(index)
            val videoId = row.getString(frameColumns.indexOf("videoId"))
            val sourceHash = row.getString(frameColumns.indexOf("sourceHash"))
            val pageId = row.getString(frameColumns.indexOf("pageId"))
            // A removed project can leave orphan index rows behind. They are not restorable
            // without a formal header, so skip them instead of rejecting an otherwise valid backup.
            val project = projects[videoId] ?: continue
            require(store.sourceHeader(videoId, sourceHash) != null) { "视频画面缺少对应源版本" }
            val saved = loadArtifact(videos, videoId, pageId, sourceHash) ?: error("视频画面成果缺失：$videoId")
            require(saved.revision == row.getString(frameColumns.indexOf("resultRevision"))) { "视频画面成果版本不匹配" }
            val identity = row.getString(frameColumns.indexOf("identity"))
            require(saved.resultIdentity == null || saved.resultIdentity == identity) { "视频画面成果身份不匹配" }
            val proofRoot = File(videos, "$videoId/proofs")
            if (proofRoot.isDirectory) VideoFrameProof.load(proofRoot, pageId)
        }

        val regions = documents.getValue("video_regions")
        val regionColumns = columns(regions)
        for (index in 0 until regions.getJSONArray("rows").length()) {
            val row = regions.getJSONArray("rows").getJSONArray(index)
            val videoId = row.getString(regionColumns.indexOf("videoId"))
            val sourceHash = row.getString(regionColumns.indexOf("sourceHash"))
            val pageId = row.getString(regionColumns.indexOf("pageId"))
            val project = projects[videoId] ?: continue
            require(store.sourceHeader(videoId, sourceHash) != null) { "视频区域缺少对应源版本" }
            require(loadArtifact(videos, videoId, pageId, sourceHash) != null) { "视频区域成果缺失：$videoId" }
        }

    }

    private fun schema(db: SupportSQLiteDatabase, table: String): TableSchema {
        val expected = COLUMNS.getValue(table)
        val actual = mutableListOf<Column>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val pkIndex = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                val primaryKeyPosition = cursor.getInt(pkIndex)
                actual += Column(
                    name = cursor.getString(nameIndex),
                    type = cursor.getString(typeIndex).orEmpty(),
                    notNull = cursor.getInt(notNullIndex) != 0 || primaryKeyPosition > 0,
                    primaryKeyPosition = primaryKeyPosition,
                )
            }
        }
        require(actual.size == expected.size && actual.map { it.name }.toSet() == expected.toSet()) {
            "媒体任务数据库结构不匹配：$table"
        }
        val byName = actual.associateBy { it.name }
        val columns = expected.map { requireNotNull(byName[it]) }
        val primaryKey = actual.filter { it.primaryKeyPosition > 0 }
            .sortedBy { it.primaryKeyPosition }
            .map { it.name }
        require(primaryKey.isNotEmpty()) { "媒体任务表缺少主键：$table" }
        val uniqueKeys = mutableListOf<List<String>>()
        uniqueKeys += primaryKey
        val uniqueIndexes = mutableListOf<String>()
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val uniqueIndex = cursor.getColumnIndex("unique")
            val partialIndex = cursor.getColumnIndex("partial")
            while (cursor.moveToNext()) {
                if (uniqueIndex < 0 || cursor.getInt(uniqueIndex) == 0) continue
                if (partialIndex >= 0 && cursor.getInt(partialIndex) != 0) continue
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                if (!name.isNullOrBlank()) uniqueIndexes += name
            }
        }
        for (indexName in uniqueIndexes) {
            val indexed = mutableListOf<String>()
            db.query("PRAGMA index_info(`$indexName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex < 0 || cursor.isNull(nameIndex)) {
                        indexed.clear()
                        break
                    }
                    indexed += cursor.getString(nameIndex)
                }
            }
            if (indexed.isNotEmpty() && indexed.all { it in byName }) uniqueKeys += indexed
        }
        return TableSchema(columns, uniqueKeys.distinct())
    }

    private fun typedValue(column: Column, value: Any, where: String): Any? {
        if (value == JSONObject.NULL) {
            require(!column.notNull) { "$where 不能为空" }
            return null
        }
        return when (affinity(column.type)) {
            "TEXT" -> (value as? String)?.also { require(it.length <= MAX_TEXT) { "$where 文本过长" } }
                ?: error("$where 类型错误")
            "INTEGER" -> integerValue(value, where)
            "REAL" -> (value as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: error("$where 类型错误")
            else -> error("$where 类型不受支持")
        }
    }

    private fun integerValue(value: Any, where: String): Long {
        val number = value as? Number ?: error("$where 类型错误")
        val double = number.toDouble()
        require(double.isFinite() && double == Math.floor(double) &&
            double >= Long.MIN_VALUE.toDouble() && double <= Long.MAX_VALUE.toDouble()) { "$where 类型错误" }
        return number.toLong()
    }

    private fun affinity(type: String): String {
        val upper = type.uppercase()
        return when {
            "INT" in upper -> "INTEGER"
            "CHAR" in upper || "CLOB" in upper || "TEXT" in upper -> "TEXT"
            "BLOB" in upper || upper.isBlank() -> "BLOB"
            "REAL" in upper || "FLOA" in upper || "DOUB" in upper -> "REAL"
            else -> "NUMERIC"
        }
    }

    private fun cursorValue(cursor: Cursor, index: Int): Any = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
        else -> error("不支持的媒体任务数据库列")
    }

    private fun redactTaskJson(row: JSONArray, columns: List<String>) {
        for (key in listOf("payload", "checkpoint")) {
            val index = columns.indexOf(key)
            if (index < 0) continue
            val value = row.get(index)
            if (value is String) row.put(index, redactJsonText(value))
        }
    }

    private fun redactJsonText(text: String): String {
        val trimmed = text.trimStart()
        return when {
            trimmed.startsWith("{") -> BackupDatabase.redact(JSONObject(text)).toString()
            trimmed.startsWith("[") -> BackupDatabase.redact(JSONArray(text)).toString()
            else -> text
        }
    }

    private fun isJsonDocument(text: String): Boolean {
        val trimmed = text.trimStart()
        return runCatching {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> error("not json")
            }
        }.isSuccess
    }

    private data class Column(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val primaryKeyPosition: Int,
    )

    private data class TableSchema(
        val columns: List<Column>,
        val uniqueKeys: List<List<String>>,
    )
}
