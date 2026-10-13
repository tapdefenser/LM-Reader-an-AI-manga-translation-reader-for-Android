package com.lmreader.screen

import android.graphics.Bitmap
import androidx.core.graphics.get
import com.lmreader.core.model.*

internal fun screenVideoRegions(image: Bitmap, regions: List<SegRegion>, toScreen: ScreenTransform): List<ScreenVideoRegion> =
    regions.filter { it.bounds.area > 0 }.map { region ->
        val appearance = ScreenVideoAppearance.capture { x, y ->
            val px = (region.bounds.left + (x + .5f) * region.bounds.width / ScreenVideoAppearance.EDGE).toInt().coerceIn(0, image.width - 1)
            val py = (region.bounds.top + (y + .5f) * region.bounds.height / ScreenVideoAppearance.EDGE).toInt().coerceIn(0, image.height - 1)
            val color = image[px, py]
            ((color shr 16 and 255) * 77 + (color shr 8 and 255) * 150 + (color and 255) * 29) shr 8
        }
        ScreenVideoRegion(region.id, region.kind, toScreen.bounds(region.bounds), appearance, region.contour.map(toScreen::point))
    }
