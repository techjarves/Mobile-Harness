package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayScaleTest {
    @Test
    fun `ui scale snaps to nearest option`() {
        assertEquals(1f, normalizeUiScale(0.5f))
        assertEquals(1.15f, normalizeUiScale(1.11f))
        assertEquals(1.3f, normalizeUiScale(9f))
    }

    @Test
    fun `terminal size snaps to nearest option`() {
        assertEquals(12, normalizeTerminalFontSize(10))
        assertEquals(14, normalizeTerminalFontSize(13))
        assertEquals(16, normalizeTerminalFontSize(99))
    }
}
