package com.terminuke.app.terminal

import kotlin.math.roundToInt

/** Termux's TerminalView passes its integer text size directly to Paint in pixels. */
internal fun terminalFontSizePixels(fontSizeSp: Int, density: Float, fontScale: Float): Int =
    (fontSizeSp * density * fontScale).roundToInt()
