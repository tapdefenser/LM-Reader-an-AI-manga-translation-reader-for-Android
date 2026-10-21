package com.lmreader.ui.video

import com.lmreader.core.model.PageTranslatedRegion

fun retargetVideoRegionId(regionId: String, fromPage: String, toPage: String): String {
    require(regionId.startsWith("$fromPage:")) { "画面区域身份与所属画面不一致" }
    return "$toPage:" + regionId.removePrefix("$fromPage:")
}

fun retargetVideoRegions(regions: List<PageTranslatedRegion>, fromPage: String, toPage: String) =
    regions.map { it.copy(region = it.region.copy(id = retargetVideoRegionId(it.region.id, fromPage, toPage))) }
