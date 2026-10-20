package com.beiwater.inkcompat

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceholderTest {
    @Test
    fun versionIsPlaceholder() {
        assertEquals("0.0.1-placeholder", inkCompatVersion())
    }
}
