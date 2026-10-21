package com.lmreader.ui.video

import org.json.JSONObject
import com.lmreader.core.database.media.VideoCueRecord
import com.lmreader.core.database.tasks.TaskRecord

/** Track, timing offset and visibility always belong to one video. Appearance can inherit globally. */
fun resolveVideoSubtitleStyle(global: String, local: String): JSONObject {
    val inherited = runCatching { JSONObject(global) }.getOrDefault(JSONObject())
    val localStyle = runCatching { JSONObject(local) }.getOrDefault(JSONObject())
    val explicit = localStyle.optBoolean("overrideStyle", localStyle.length() > 0)
    if(explicit) localStyle.keys().forEach { key -> inherited.put(key, localStyle.get(key)) }
    for(key in listOf("offsetMs", "trackId", "enabled")) {
        inherited.remove(key)
        if(localStyle.has(key)) inherited.put(key, localStyle.get(key))
    }
    return inherited.put("overrideStyle", explicit)
}

internal fun generatedVideoTrack(id: String, tasks: List<TaskRecord>): String? = tasks
    .filter { it.type == "VIDEO_SUBTITLES" && it.ownerKey == "video:$id:subtitles" }
    .sortedByDescending { it.updatedAt }
    .firstNotNullOfOrNull { JSONObject(it.checkpoint).optString("sourceTrackId").takeIf(String::isNotBlank) }

/** Old 'all' preferences choose a concrete source, keeping imported-only libraries readable. */
internal fun resolvedVideoSubtitleTrack(style: JSONObject, cues: List<VideoCueRecord>): String {
    val selected = style.optString("trackId", "all")
    if(selected.isNotBlank() && selected != "all") return selected
    return if(cues.any { !it.deleted && !it.trackId.startsWith("external") }) "generated"
        else cues.firstOrNull { !it.deleted && it.trackId.startsWith("external") }?.trackId ?: "generated"
}

internal fun videoSubtitleMatchesTrack(track: String, selected: String, generated: String?): Boolean =
    track == selected || selected == "generated" && (track == "manual" ||
        if(generated != null) track == generated else !track.startsWith("external"))
