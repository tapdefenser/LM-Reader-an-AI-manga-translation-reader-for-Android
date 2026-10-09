package com.lmreader.ui.reader.translation

import com.lmreader.core.model.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ReaderPageTranslation(
    val pageId: String, val revision: String, val dataFile: File,
    val source: LocalTranslationLanguage, val target: LocalTranslationLanguage,
    val sourceSha256: String, val width: Int, val height: Int,
    val regions: List<PageTranslatedRegion>, val modelPacks: List<String>, val elapsedMillis: Long,
    val renderSettings: BubbleRenderSettings = BubbleRenderSettings(),
    val preview: Boolean = false,
)

internal class PageTranslationRevisionConflict : IllegalStateException("Page translation changed before saving edits")

/** Durable page data, never an image cache. Atomic replacement also protects user edits. */
class ReaderPageArtifactStore(val root: File, private val legacyRoot: File? = null) {
    init { check(root.isDirectory || root.mkdirs()) }
    fun has(pageId: String) = file(pageId).isFile || legacyPointer(pageId)?.isFile == true
    @Synchronized
    fun snapshot(pageIds: List<String>, target: File): Map<String, ReaderPageTranslation> {
        migrateLegacy(); check(target.isDirectory || target.mkdirs())
        return pageIds.mapNotNull { id -> read(id)?.let { saved ->
            com.lmreader.ui.queue.ExportFiles.commit(File(target, saved.dataFile.name)) { output -> saved.dataFile.inputStream().use { it.copyTo(output) } }
            id to saved
        } }.toMap()
    }

    @Synchronized
    fun delete(pageId: String) {
        // Migrate before deletion so the old pointer cannot recreate a cleared translation.
        if (!file(pageId).exists()) migratePage(pageId)
        val saved = file(pageId)
        check(!saved.exists() || saved.delete()) { "Cannot clear page translation" }
    }

    @Synchronized
    fun load(pageId: String, sourceSha256: String): ReaderPageTranslation? {
        if (!file(pageId).exists()) migratePage(pageId)
        val saved = read(pageId) ?: return null
        return saved.takeIf { it.sourceSha256 == sourceSha256 }
    }

    @Synchronized
    fun save(pageId: String, hash: String, source: LocalTranslationLanguage, target: LocalTranslationLanguage,
        width: Int, height: Int, regions: List<PageTranslatedRegion>, packs: List<String>, elapsed: Long,
        render: BubbleRenderSettings = BubbleRenderSettings(), expectedRevision: String? = null,
        requireAbsent: Boolean = false,
        checkCancelled: () -> Unit = {}): ReaderPageTranslation {
        if (requireAbsent && read(pageId) != null) throw PageTranslationRevisionConflict()
        if (expectedRevision != null && read(pageId)?.revision != expectedRevision) throw PageTranslationRevisionConflict()
        val result = ReaderPageTranslation(pageId, UUID.randomUUID().toString(), file(pageId), source, target,
            hash, width, height, regions, packs, elapsed, render)
        validate(result)
        val document = encode(result).toString()
        val contents = JSONObject().put("sha256", sha256(document.toByteArray(Charsets.UTF_8)))
            .put("document", document).toString()
        require(contents.toByteArray(Charsets.UTF_8).size <= MAX_DATA_BYTES) { "Page data is too large" }
        val temporary = File(root, result.dataFile.name + "." + UUID.randomUUID() + ".part")
        try {
            checkCancelled()
            FileOutputStream(temporary).use { it.write(contents.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            checkCancelled()
            check(temporary.renameTo(result.dataFile)) { "Cannot save page translation" }
            return result
        } finally { temporary.delete() }
    }

    fun saveEdits(saved: ReaderPageTranslation, regions: List<PageTranslatedRegion>, checkCancelled: () -> Unit = {}) =
        save(saved.pageId, saved.sourceSha256, saved.source, saved.target, saved.width, saved.height, regions,
            saved.modelPacks, saved.elapsedMillis, saved.renderSettings, saved.revision.takeIf { it.isNotEmpty() },
            requireAbsent = saved.revision.isEmpty(), checkCancelled = checkCancelled)

    /** Import old metadata before removing its private PNG. No comic source is opened. */
    @Synchronized
    fun migrateLegacy() {
        val oldRoot = legacyRoot ?: return
        oldRoot.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }.forEach { pointer ->
            runCatching {
                val (_, json) = legacyRecord(pointer) ?: return@runCatching
                migratePage(json.getString("pageId"))
            }
            // Preserve malformed legacy records for recovery instead of deleting them.
        }
        if(oldRoot.listFiles().orEmpty().none { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }) {
            oldRoot.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[a-f0-9-]{36}")) &&
                it.canonicalFile.parentFile==oldRoot.canonicalFile }.forEach { folder ->
                File(folder,"translated.png").delete(); File(folder,"metadata.json").delete(); folder.delete()
            }
        }
    }

    private fun migratePage(pageId: String) {
        val pointer = legacyPointer(pageId) ?: return
        val (folder, json) = legacyRecord(pointer) ?: return
        require(json.getString("pageId") == pageId && json.getInt("schema") == 1)
        if (!file(pageId).exists()) {
            val legacy = decode(json, pageId, UUID.randomUUID().toString())
            save(pageId, legacy.sourceSha256, legacy.source, legacy.target, legacy.width, legacy.height,
                legacy.regions, legacy.modelPacks, legacy.elapsedMillis, legacy.renderSettings)
        } else read(pageId) ?: return
        // Remove only owned cache files, after durable publication succeeded.
        File(folder, "translated.png").delete()
        File(folder, "metadata.json").delete()
        folder.delete()
        pointer.delete()
    }

    private fun legacyRecord(pointer: File): Pair<File, JSONObject>? {
        val oldRoot = legacyRoot ?: return null
        if (!pointer.isFile || pointer.length() > 4096) return null
        require(pointer.canonicalFile.parentFile == oldRoot.canonicalFile)
        val revision = JSONObject(pointer.readText()).getString("revision")
        require(revision.matches(Regex("[a-f0-9-]{36}")))
        val folder = File(oldRoot, revision)
        require(folder.canonicalFile.parentFile == oldRoot.canonicalFile)
        val metadata = File(folder, "metadata.json")
        if (!metadata.isFile || metadata.length() > MAX_DATA_BYTES) return null
        return folder to JSONObject(metadata.readText())
    }

    private fun read(pageId: String): ReaderPageTranslation? {
        val destination = file(pageId)
        if (!destination.isFile) return null
        require(destination.length() <= MAX_DATA_BYTES) { "Invalid page data size" }
        val envelope = JSONObject(destination.readText())
        val document = envelope.getString("document")
        require(envelope.getString("sha256") == sha256(document.toByteArray(Charsets.UTF_8))) { "Damaged page translation data" }
        val json = JSONObject(document)
        require(json.getInt("schema") == 2)
        return decode(json, pageId, json.getString("revision"))
    }

    private fun decode(json: JSONObject, pageId: String, revision: String): ReaderPageTranslation {
        require(json.getString("pageId") == pageId)
        val list = json.getJSONArray("regions")
        require(list.length() <= 1000)
        val regions = (0 until list.length()).map { i ->
            val item = list.getJSONObject(i)
            val contour = item.getJSONArray("contour"); require(contour.length() <= 2048)
            val boxes = item.getJSONArray("textBounds"); require(boxes.length() <= 1000)
            PageTranslatedRegion(PageTextRegion(item.getString("id"), RegionKind.valueOf(item.getString("kind")),
                rect(item.getJSONArray("bounds")), (0 until contour.length()).map { j ->
                    contour.getJSONArray(j).let { PixelPoint(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
                }, item.getString("sourceText"), (0 until boxes.length()).map { rect(boxes.getJSONArray(it)) }),
                item.getString("translatedText"), item.optInt("fontScale", 100), item.optDouble("rotation", 0.0).toFloat())
        }
        val packs = json.getJSONArray("modelPacks")
        return ReaderPageTranslation(pageId, revision, file(pageId), LocalTranslationLanguage.fromTag(json.getString("source")),
            LocalTranslationLanguage.fromTag(json.getString("target")), json.getString("sourceSha256"),
            json.getInt("width"), json.getInt("height"), regions, (0 until packs.length()).map { packs.getString(it) },
            json.getLong("elapsedMillis"), json.optJSONObject("render")?.let {
                BubbleRenderSettings(BubbleFillMode.valueOf(it.getString("fillMode")), it.getInt("opacity"), it.getInt("padding"),
                    runCatching { BubbleFont.valueOf(it.optString("font")) }.getOrDefault(BubbleFont.SYSTEM),
                    it.optInt("fontScale", 100), it.optBoolean("bold", false), it.optInt("freeTextMaskExpansion", 6))
            } ?: BubbleRenderSettings()).also(::validate)
    }

    private fun validate(data: ReaderPageTranslation) {
        require(data.width > 1 && data.height > 1 && data.width.toLong() * data.height <= 4_000_000)
        require(data.regions.size <= 1000 && data.regions.map { it.region.id }.distinct().size == data.regions.size)
        fun validRect(r: PixelRect) = listOf(r.left, r.top, r.right, r.bottom).all(Float::isFinite) &&
            r.left >= 0 && r.top >= 0 && r.right <= data.width && r.bottom <= data.height && r.width > 0 && r.height > 0
        data.regions.forEach { item ->
            val r = item.region
            require(r.id.startsWith(data.pageId + ":") && r.sourceText.length <= 4096 && item.translatedText.length <= 16384)
            require(item.fontScalePercent in 25..400 && item.rotationDegrees.isFinite())
            require(validRect(r.bounds) && r.contour.size <= 2048 && r.textBounds.size <= 1000 && r.textBounds.all(::validRect))
            require(r.contour.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..data.width.toFloat() && it.y in 0f..data.height.toFloat() })
        }
    }

    private fun encode(data: ReaderPageTranslation) = JSONObject().put("schema", 2).put("pageId", data.pageId)
        .put("revision", data.revision).put("sourceSha256", data.sourceSha256).put("source", data.source.tag).put("target", data.target.tag)
        .put("width", data.width).put("height", data.height).put("elapsedMillis", data.elapsedMillis)
        .put("modelPacks", JSONArray(data.modelPacks)).put("render", JSONObject().put("fillMode", data.renderSettings.fillMode.name)
            .put("opacity", data.renderSettings.opacityPercent).put("padding", data.renderSettings.textPaddingPercent)
            .put("font", data.renderSettings.font.name).put("fontScale", data.renderSettings.fontScalePercent).put("bold", data.renderSettings.bold)
            .put("freeTextMaskExpansion", data.renderSettings.freeTextMaskExpansionPercent))
        .put("regions", JSONArray().apply { data.regions.forEach { item ->
            val r = item.region
            put(JSONObject().put("id", r.id).put("kind", r.kind.name).put("bounds", rectJson(r.bounds)).put("sourceText", r.sourceText)
                .put("translatedText", item.translatedText).put("contour", JSONArray().apply { r.contour.forEach { put(JSONArray(listOf(it.x, it.y))) } })
                .put("fontScale", item.fontScalePercent).put("rotation", item.rotationDegrees.toDouble())
                .put("textBounds", JSONArray().apply { r.textBounds.forEach { put(rectJson(it)) } }))
        } })

    private fun file(pageId: String) = File(root, sha256(pageId.toByteArray(Charsets.UTF_8)) + ".json")
    private fun legacyPointer(pageId: String) = legacyRoot?.let { File(it, file(pageId).name) }
    private fun rectJson(r: PixelRect) = JSONArray(listOf(r.left, r.top, r.right, r.bottom))
    private fun rect(a: JSONArray) = PixelRect(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
    companion object {
        private const val MAX_DATA_BYTES = 8_000_000L
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun hashFile(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
                val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n)
            } }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
