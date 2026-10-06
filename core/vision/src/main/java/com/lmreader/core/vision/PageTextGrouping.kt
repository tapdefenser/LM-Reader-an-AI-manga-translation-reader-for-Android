package com.lmreader.core.vision

import com.lmreader.core.model.*
import kotlin.math.*

/** Each OCR line belongs to exactly one region; text detections inside bubbles do not duplicate it. */
fun groupPageText(seg: SegResult, ocr: LocalOcrResult, scope: SegTextScope = SegTextScope.ALL,
    freeTextMergeGapRatio: Float = DEFAULT_FREE_TEXT_MERGE_GAP_RATIO): List<PageTextRegion> {
    require(seg.imageId == ocr.imageId && seg.width == ocr.width && seg.height == ocr.height)
    require(ocr.lines.map { it.id }.distinct().size == ocr.lines.size)
    require(seg.regions.map { it.id }.distinct().size == seg.regions.size)
    val candidates = selectSegRegions(seg, scope, freeTextMergeGapRatio)
    val groups = linkedMapOf<String, Pair<SegRegion, MutableList<OcrLine>>>()
    for (line in ocr.lines.filter { it.text.isNotBlank() && it.bounds.area > 0 }) {
        val match = regionOwner(line.bounds, candidates)
            ?: if (scope.includes(RegionKind.FREE_TEXT) && regionOwner(line.bounds, seg.regions.filter { it.kind == RegionKind.BUBBLE }) == null)
                SegRegion(line.id, RegionKind.FREE_TEXT, line.bounds, line.confidence) else continue
        groups.getOrPut(match.id) { match to arrayListOf() }.second += line
    }
    return groups.values.map { (region, lines) ->
        val coverage=if(region.kind==RegionKind.FREE_TEXT) PixelRect(
            min(region.bounds.left,lines.minOf {it.bounds.left})-2,min(region.bounds.top,lines.minOf {it.bounds.top})-2,
            max(region.bounds.right,lines.maxOf {it.bounds.right})+2,max(region.bounds.bottom,lines.maxOf {it.bounds.bottom})+2) else region.bounds
        val bounds = PixelRect(coverage.left.coerceIn(0f,seg.width.toFloat()), coverage.top.coerceIn(0f,seg.height.toFloat()),
            coverage.right.coerceIn(0f,seg.width.toFloat()), coverage.bottom.coerceIn(0f,seg.height.toFloat()))
        val contour = region.contour.takeIf { points -> points.size >= 3 && points.all { it.x.isFinite() && it.y.isFinite() } }
            ?.map { PixelPoint(it.x.coerceIn(0f,seg.width.toFloat()), it.y.coerceIn(0f,seg.height.toFloat())) }.orEmpty()
        PageTextRegion(region.id,region.kind,bounds,contour,joinOcrText(lines.map { it.text }, ocr.language),lines.map { it.bounds })
    }
}

fun bindPageTranslations(regions: List<PageTextRegion>, translations: List<LocalTranslatedText>): List<PageTranslatedRegion> {
    require(regions.map { it.id }.distinct().size == regions.size)
    require(translations.size == regions.size && translations.map { it.id }.toSet().size == translations.size &&
        translations.map { it.id }.toSet() == regions.map { it.id }.toSet()) { "Translation IDs do not match page regions" }
    val byId = translations.associateBy { it.id }
    return regions.map { region ->
        val translated = byId.getValue(region.id)
        require(translated.sourceText == region.sourceText) { "Translation source text does not match OCR" }
        PageTranslatedRegion(region, translated.translatedText)
    }
}

internal fun joinOcrLines(lines: List<String>): String = joinOcrText(lines, LocalOcrLanguage.JAPANESE)

internal fun polygonContains(points: List<PixelPoint>,x: Float,y: Float): Boolean {
    var inside=false; var j=points.lastIndex
    for(i in points.indices) {
        val a=points[i];val b=points[j]
        if((a.y>y)!=(b.y>y) && x < (b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x) inside=!inside
        j=i
    }
    return inside
}

/** A coarse inscribed rectangle keeps text away from tails and irregular mask edges. */
internal fun safeTextBounds(region: PageTextRegion): PixelRect {
    val bounds=region.bounds
    if(region.contour.size<3 || bounds.area<=0) return bounds
    val n=64;val heights=IntArray(n);var bestArea=0;var best=bounds
    for(y in 0 until n) {
        for(x in 0 until n) {
            val px=bounds.left+(x+.5f)*bounds.width/n;val py=bounds.top+(y+.5f)*bounds.height/n
            heights[x]=if(polygonContains(region.contour,px,py)) heights[x]+1 else 0
        }
        val stack=ArrayList<Int>()
        for(x in 0..n) {
            val height=if(x==n) 0 else heights[x]
            while(stack.isNotEmpty() && heights[stack.last()]>height) {
                val h=heights[stack.removeAt(stack.lastIndex)];val left=if(stack.isEmpty()) 0 else stack.last()+1
                val area=h*(x-left)
                if(area>bestArea) {
                    bestArea=area
                    best=PixelRect(bounds.left+left*bounds.width/n,bounds.top+(y-h+1)*bounds.height/n,
                        bounds.left+x*bounds.width/n,bounds.top+(y+1)*bounds.height/n)
                }
            }
            if(x<n) stack+=x
        }
    }
    return best
}
