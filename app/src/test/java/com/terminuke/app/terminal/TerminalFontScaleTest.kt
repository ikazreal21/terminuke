package com.terminuke.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalFontScaleTest {
    @Test
    fun scalesSpByDisplayDensityAndAccessibilityFontScale() {
        assertEquals(69, terminalFontSizePixels(fontSizeSp = 23, density = 3f, fontScale = 1f))
        assertEquals(83, terminalFontSizePixels(fontSizeSp = 23, density = 3f, fontScale = 1.2f))
    }
}
