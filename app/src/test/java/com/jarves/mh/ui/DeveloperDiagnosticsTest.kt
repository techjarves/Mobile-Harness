package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperDiagnosticsTest {
    @Test
    fun countsUtf8WithoutCreatingEncodedCopies() {
        assertEquals(5L, utf8SizeInBytes("hello"))
        assertEquals(6L, utf8SizeInBytes("नम"))
        assertEquals(4L, utf8SizeInBytes("🚀"))
        assertEquals(11L, utf8SizeInBytes("Aन🚀éB"))
    }
}
