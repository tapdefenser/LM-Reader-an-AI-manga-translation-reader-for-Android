package com.lmreader.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** Scrollable space for home overlays; the page itself keeps its full height. */
internal val LocalHomeOverlayPadding = staticCompositionLocalOf { 0.dp }
