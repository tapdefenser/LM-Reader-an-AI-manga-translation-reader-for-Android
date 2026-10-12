package com.lmreader.core.model

import org.junit.Assert.*
import org.junit.Test

class ScreenApplicationWindowsTest {
    private val main = ScreenApplicationWindow(1, "reader", PixelRect(0f, 0f, 400f, 800f), 2, active = true)
    private val popup = ScreenApplicationWindow(2, "reader", PixelRect(40f, 200f, 360f, 500f), 4, active = true)
    @Test fun dialogsAndNestedPopupsKeepTheUnderlyingWindowAcrossOpenAndDismiss() {
        val windows = ScreenApplicationWindows()
        assertEquals(1, windows.update(listOf(main))!!.window.id)
        val covered = windows.update(listOf(main.copy(active = false), popup))!!
        assertEquals(1, covered.window.id); assertEquals(listOf(popup.bounds), covered.occlusions)
        val nested = popup.copy(id = 3, layer = 5)
        assertEquals(1, windows.update(listOf(main.copy(active = false), popup.copy(active = false), nested))!!.window.id)
        assertTrue(windows.update(listOf(main))!!.occlusions.isEmpty())
    }
    @Test fun fullWindowDialogIsPreservedButANewActivityWithoutTheOldWindowIsNavigation() {
        val windows = ScreenApplicationWindows(); windows.update(listOf(main))
        assertEquals(1, windows.update(listOf(main.copy(active = false), popup.copy(bounds = main.bounds)))!!.window.id)
        assertEquals(2, windows.update(listOf(popup.copy(bounds = main.bounds)))!!.window.id)
    }
    @Test fun permissionDialogDoesNotClearThePageButAnotherFullScreenApplicationDoes() {
        val windows = ScreenApplicationWindows(); windows.update(listOf(main))
        assertEquals(1, windows.update(listOf(main.copy(active = false), popup.copy(packageName = "permissions")))!!.window.id)
        assertEquals(2, windows.update(listOf(main.copy(active = false), popup.copy(packageName = "browser", bounds = main.bounds)))!!.window.id)
    }
    @Test fun modalDialogMayTemporarilyRemoveTheBaseFromAccessibleWindowsAndRenumberLayers() {
        val windows = ScreenApplicationWindows(); windows.update(listOf(main))
        val modal = windows.update(listOf(popup.copy(layer = 0)))!!
        assertEquals(main.id, modal.window.id); assertEquals(listOf(popup.bounds), modal.occlusions)
        val nested = popup.copy(id = 3, layer = 1)
        assertEquals(main.id, windows.update(listOf(nested))!!.window.id)
        val restored = windows.update(listOf(main))!!
        assertEquals(main.id, restored.window.id); assertTrue(restored.occlusions.isEmpty())
    }
    @Test fun systemSurfaceCanTemporarilyHideAllApplicationWindows() {
        val windows = ScreenApplicationWindows(); windows.update(listOf(main))
        assertEquals(main.id, windows.update(emptyList())!!.window.id)
        assertEquals(listOf(main.bounds), windows.update(emptyList())!!.occlusions)
        assertTrue(windows.update(listOf(main))!!.occlusions.isEmpty())
        windows.reset(); assertNull(windows.update(emptyList()))
    }
    @Test fun explicitlyReportedFullScreenDialogKeepsItsTemporarilyInaccessibleBase() {
        val windows = ScreenApplicationWindows(); windows.update(listOf(main))
        val modal = windows.update(listOf(popup.copy(layer = 0, bounds = main.bounds, modal = true)))!!
        assertEquals(main.id, modal.window.id); assertEquals(listOf(main.bounds), modal.occlusions)
        assertEquals(main.id, windows.update(listOf(main))!!.window.id)
    }
}
