package com.lmreader.ui.video

import android.graphics.Bitmap
import android.graphics.Color
import com.lmreader.core.model.*
import com.lmreader.ui.queue.ExportFiles
import com.lmreader.ui.reader.translation.ReaderPageTranslation
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** Only tiny source-stroke samples are retained, never full decoded frames. */
class VideoFrameProof(private val samples: Map<String, String>) {
    fun retarget(fromPage: String, toPage: String) = VideoFrameProof(samples.mapKeys { (id, _) -> retargetVideoRegionId(id, fromPage, toPage) })
    fun matches(bitmap: Bitmap, saved: ReaderPageTranslation): Boolean {
        if (saved.regions.isEmpty()) return false
        val previous = saved.regions.map { row ->
            val bytes = samples[row.region.id]?.let { Base64.getDecoder().decode(it) } ?: return false
            if (bytes.size != ScreenVideoAppearance.EDGE * ScreenVideoAppearance.EDGE) return false
            ScreenVideoRegion(row.region.id, row.region.kind, row.region.bounds,
                ScreenVideoAppearance.capture { x, y -> bytes[y * ScreenVideoAppearance.EDGE + x].toInt() and 255 }, row.region.contour)
        }
        val fresh = saved.regions.map { row -> ScreenVideoRegion(row.region.id, row.region.kind, row.region.bounds,
            ScreenVideoAppearance.capture { x, y -> sample(bitmap, saved, row.region, x, y) }, row.region.contour) }
        return screenVideoRegionMatches(previous, fresh, 94).size == previous.size
    }
    fun save(root: File, pageId: String) {
        val j = JSONObject(samples)
        ExportFiles.commit(file(root, pageId)) { it.write(j.toString().toByteArray(Charsets.UTF_8)) }
    }
    companion object {
        private fun sample(bitmap: Bitmap, saved: ReaderPageTranslation, region: PageTextRegion, x: Int, y: Int): Int {
            val px = ((region.bounds.left + (x + .5f) * region.bounds.width / ScreenVideoAppearance.EDGE) * bitmap.width / saved.width)
                .toInt().coerceIn(0, bitmap.width - 1)
            val py = ((region.bounds.top + (y + .5f) * region.bounds.height / ScreenVideoAppearance.EDGE) * bitmap.height / saved.height)
                .toInt().coerceIn(0, bitmap.height - 1)
            val color = bitmap.getPixel(px, py)
            return (Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29) / 256
        }
        fun capture(bitmap: Bitmap, saved: ReaderPageTranslation) = VideoFrameProof(saved.regions.associate { row ->
            row.region.id to Base64.getEncoder().encodeToString(ByteArray(ScreenVideoAppearance.EDGE * ScreenVideoAppearance.EDGE) { i ->
                sample(bitmap, saved, row.region, i % ScreenVideoAppearance.EDGE, i / ScreenVideoAppearance.EDGE).toByte() })
        })
        fun load(root: File, pageId: String): VideoFrameProof? {
            val file = file(root, pageId); if (!file.isFile) return null
            require(file.length() <= 2_000_000)
            val j = JSONObject(file.readText(Charsets.UTF_8))
            require(j.length() <= 1000)
            return VideoFrameProof(j.keys().asSequence().associateWith {
                require(it.length <= 500)
                j.getString(it).also { text -> require(Base64.getDecoder().decode(text).size == ScreenVideoAppearance.EDGE * ScreenVideoAppearance.EDGE) }
            })
        }
        private fun file(root: File, id: String): File {
            check(root.isDirectory || root.mkdirs())
            val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
            return File(root, "$name.json")
        }
    }
}
