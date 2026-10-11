package com.lmreader.screen

/** The first drag can only dock; tucking requires a new outward drag from that edge. */
internal data class ScreenBallPosition(val x: Float = .94f, val y: Float = .76f, val tucked: Boolean = false) {
    fun docked() = copy(x = if (x < .5f) 0f else 1f, y = y.coerceIn(0f, 1f))

    fun drag(dx: Float, dy: Float, rangeX: Float, rangeY: Float, touchSlop: Float, released: Boolean = false): ScreenBallPosition {
        val left = x * rangeX <= 1f
        val right = (1f - x) * rangeX <= 1f
        return ScreenBallPosition(
            if (rangeX > 0) ((x * rangeX + dx) / rangeX).coerceIn(0f, 1f) else 0f,
            if (rangeY > 0) ((y * rangeY + dy) / rangeY).coerceIn(0f, 1f) else 0f,
            released && rangeX > 0 && (left && dx < -touchSlop || right && dx > touchSlop)
        )
    }

    fun offsetX(rangeX: Float, size: Float, tab: Float): Float = when {
        !tucked -> x * rangeX
        x < .5f -> tab - size
        else -> rangeX + size - tab
    }
}

/** Coordinates inside the current display's visible bars and cutout insets. */
internal data class ScreenBallViewport(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(right > left && bottom > top) }
    val width get() = right - left
    val height get() = bottom - top

    fun layout(position: ScreenBallPosition, diameter: Int, tab: Int): ScreenBallLayout {
        val buttonWidth = (if (position.tucked) tab else diameter).coerceIn(1, width)
        val buttonHeight = diameter.coerceIn(1, height)
        val x = if (position.tucked) {
            if (position.x < .5f) left else right - buttonWidth
        } else left + (position.x.coerceIn(0f, 1f) * (width - buttonWidth)).toInt()
        val y = top + (position.y.coerceIn(0f, 1f) * (height - buttonHeight)).toInt()
        return ScreenBallLayout(x, y, buttonWidth, buttonHeight)
    }
}

internal data class ScreenBallLayout(val x: Int, val y: Int, val width: Int, val height: Int)
