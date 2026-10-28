package com.lmreader.ui.video

import com.lmreader.core.model.BubbleTextColorMode
import org.json.JSONObject

/** Display-only per-video preference, independent of recognition/task snapshots. */
internal fun videoMaskTextColor(style: String?): BubbleTextColorMode {
    val key = runCatching { JSONObject(style ?: "{}").optString("maskTextColor") }.getOrNull()
    return BubbleTextColorMode.entries.firstOrNull { it.name == key } ?: BubbleTextColorMode.AUTO
}

internal fun withVideoMaskTextColor(style: String, color: BubbleTextColorMode): String =
    JSONObject(style).put("maskTextColor", color.name).toString()
