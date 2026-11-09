package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayScaleTest {
    @Test
    fun `ui scale snaps to nearest option and rounds ties up`() {
        assertEquals(0.85f, normalizeUiScale(0.8f))
        assertEquals(0.85f, normalizeUiScale(0.9f))
        assertEquals(1.15f, normalizeUiScale(1.11f))
        assertEquals(1.3f, normalizeUiScale(1.25f))
    }

    @Test
    fun `ui scale falls back to default when out of range or non-finite`() {
        assertEquals(1f, normalizeUiScale(0.5f))
        assertEquals(1f, normalizeUiScale(9f))
        assertEquals(1f, normalizeUiScale(Float.NaN))
    }

    @Test
    fun `terminal size snaps to nearest option and rounds ties up`() {
        assertEquals(12, normalizeTerminalFontSize(10))
        assertEquals(14, normalizeTerminalFontSize(13))
        assertEquals(16, normalizeTerminalFontSize(99))
    }

    @Test
    fun `ui scale options are the closed design set`() {
        assertEquals(listOf(0.75f, 0.85f, 1f, 1.15f, 1.3f), UI_SCALE_OPTIONS.map { it.scale })
    }
}
