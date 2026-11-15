package com.jarves.mh.model

import kotlin.math.abs

val UI_SCALE_OPTIONS = listOf(1f, 1.15f, 1.3f)
const val DEFAULT_UI_SCALE = 1f

val TERMINAL_FONT_SIZE_OPTIONS = listOf(12, 14, 16)
const val DEFAULT_TERMINAL_FONT_SIZE = 12

/** Snaps a stored or requested scale to the nearest offered option. */
fun normalizeUiScale(value: Float): Float =
    UI_SCALE_OPTIONS.minByOrNull { abs(it - value) } ?: DEFAULT_UI_SCALE

/** Snaps a stored or requested terminal size to the nearest offered option, rounding ties up. */
fun normalizeTerminalFontSize(value: Int): Int =
    TERMINAL_FONT_SIZE_OPTIONS.minWithOrNull(
        compareBy<Int> { abs(it - value) }.thenByDescending { it },
    ) ?: DEFAULT_TERMINAL_FONT_SIZE
