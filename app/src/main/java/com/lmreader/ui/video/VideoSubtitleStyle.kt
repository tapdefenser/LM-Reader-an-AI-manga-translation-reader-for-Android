package com.lmreader.ui.video

import org.json.JSONObject

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
