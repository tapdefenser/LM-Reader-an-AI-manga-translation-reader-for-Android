package com.lmreader.screen

import org.junit.Assert.*
import org.junit.Test

class ScreenBallPositionTest {
    private val portrait = ScreenBallViewport(0, 80, 1080, 2340)
    private val landscape = ScreenBallViewport(100, 0, 2400, 1000)
    private val diameter = 144
    private val tab = 48

    @Test fun rotationKeepsRightEdgeAndRelativeHeightWithinNewInsets() {
        val position = ScreenBallPosition(.98f, .8f).docked()
        val wide = landscape.layout(position, diameter, tab)
        assertEquals(landscape.right, wide.x + wide.width)
        assertEquals(684, wide.y)
        val tall = portrait.layout(position, diameter, tab)
        assertEquals(portrait.right, tall.x + tall.width)
        assertEquals(1772, tall.y)
    }

    @Test fun rotationKeepsLeftEdgeClearOfLandscapeCutout() {
        val placement = landscape.layout(ScreenBallPosition(.02f, .7f).docked(), diameter, tab)
        assertEquals(100, placement.x)
        assertEquals(599, placement.y)
        assertEquals(diameter, placement.width)
    }

    @Test fun tuckedHitTargetStaysOnCorrespondingEdgeInBothOrientations() {
        for (viewport in listOf(portrait, landscape)) for (x in listOf(0f, 1f)) {
            val position = ScreenBallPosition(x, 1f, tucked = true).docked()
            assertTrue(position.tucked)
            val placement = viewport.layout(position, diameter, tab)
            assertEquals(tab, placement.width)
            assertEquals(viewport.bottom, placement.y + placement.height)
            assertEquals(if (x == 0f) viewport.left else viewport.right - tab, placement.x)
        }
    }

    @Test fun repairCanRepeatWithoutChangingSavedHeightOrTuckedState() {
        val position = ScreenBallPosition(.9f, .76f, true).docked()
        assertEquals(position, position.docked().docked())
        assertEquals(.76f, position.y, 0f)
        assertTrue(position.tucked)
    }

    @Test fun changedInsetsImmediatelyKeepUndockedBallInside() {
        val placement = landscape.layout(ScreenBallPosition(.9f, 1f), diameter, tab)
        assertTrue(placement.x >= landscape.left)
        assertTrue(placement.x + placement.width <= landscape.right)
        assertEquals(landscape.bottom, placement.y + placement.height)
    }

    @Test fun tinyViewportKeepsEntireHitTargetInside() {
        val viewport = ScreenBallViewport(20, 30, 50, 80)
        for (tucked in listOf(false, true)) {
            val placement = viewport.layout(ScreenBallPosition(1f, 1f, tucked), diameter, tab)
            assertEquals(ScreenBallLayout(20, 30, 30, 50), placement)
        }
    }

    @Test fun firstOutwardDragDocksBeforeSecondDragTucks() {
        val first = ScreenBallPosition(.9f, .7f).drag(100f, 0f, 500f, 800f, 10f, released = true)
        assertEquals(1f, first.x, 0f)
        assertFalse(first.tucked)
        val second = first.drag(30f, 0f, 500f, 800f, 10f, released = true)
        assertTrue(second.tucked)
        assertEquals(1f, second.x, 0f)
    }
}
