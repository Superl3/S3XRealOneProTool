package com.raphael.handmouse.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DexTasksTest {
    // Shape of `am stack list` on the SM-S937N, 2026-11-19, shortened.
    private val list = """
RootTask id=12918 bounds=[0,0][1920,1080] displayId=73 userId=0
 configuration={1.08 450mcc6mnc}
  taskId=12918: unknown bounds=[0,0][1920,1080] userId=0 visible=true

RootTask id=12916 bounds=[0,0][1920,1080] displayId=73 userId=0
  taskId=12917: com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher bounds=[0,0][1920,1080] userId=0 visible=true topActivity=ComponentInfo{x}

RootTask id=12919 bounds=[0,0][1920,1080] displayId=73 userId=0
  taskId=12921: com.android.settings/com.android.settings.homepage.SettingsHomepageActivity bounds=[558,120][1362,822] userId=0 visible=true topActivity=ComponentInfo{y}

RootTask id=1 bounds=[0,0][1080,2340] displayId=0 userId=0
  taskId=5: com.android.settings/.Settings bounds=[0,0][1080,2340] userId=0 visible=true
"""

    @Test
    fun `parses tasks with their display`() {
        val tasks = DexTasks.parse(list)
        assertEquals(listOf(12918, 12917, 12921, 5), tasks.map { it.id })
        assertEquals(73, tasks[2].displayId)
        assertEquals(DexTasks.Bounds(558, 120, 1362, 822), tasks[2].bounds)
        assertEquals(0, tasks[3].displayId)
    }

    @Test
    fun `target skips the desk container and the launcher`() {
        val tasks = DexTasks.parse(list)
        assertEquals(12921, DexTasks.target(tasks, 73, 700f, 300f, null)?.id)
        assertNull(DexTasks.target(tasks, 73, 100f, 1000f, null))
        assertEquals(12921, DexTasks.target(tasks, 73, null, null, null)?.id)
    }

    @Test
    fun `window bounds pick the matching task`() {
        val tasks = DexTasks.parse(list)
        assertEquals(12921, DexTasks.target(tasks, 73, 0f, 0f, DexTasks.Bounds(560, 118, 1360, 824))?.id)
    }

    @Test
    fun `maximize toggles to full and back`() {
        val window = DexTasks.Bounds(558, 120, 1362, 822)
        val full = DexTasks.Bounds(0, 0, 1920, 1080)
        assertEquals(full, DexTasks.toggleBounds(window, 1920, 1080, null))
        assertEquals(window, DexTasks.toggleBounds(full, 1920, 1080, window))
        assertEquals(DexTasks.Bounds(320, 180, 1600, 900), DexTasks.toggleBounds(full, 1920, 1080, null))
    }

    @Test
    fun `only a display-sized window counts as maximized`() {
        assertTrue(DexTasks.fillsDisplay(DexTasks.Bounds(0, 0, 1920, 1080), 1920, 1080))
        assertTrue(DexTasks.fillsDisplay(DexTasks.Bounds(2, 0, 1918, 1080), 1920, 1080))
        assertFalse(DexTasks.fillsDisplay(DexTasks.Bounds(558, 120, 1362, 822), 1920, 1080))
        assertFalse(DexTasks.fillsDisplay(DexTasks.Bounds(0, 60, 1920, 1080), 1920, 1080))
    }
}
