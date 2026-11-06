package com.jarves.mh.model

import androidx.annotation.StringRes
import com.jarves.mh.R
import kotlin.math.abs

/** One selectable UI scale together with the resource for its localized label. */
data class DisplayScaleOption(val scale: Float, @StringRes val labelRes: Int)

val UI_SCALE_OPTIONS = listOf(
    DisplayScaleOption(0.75f, R.string.st_text_size_xsmall),
    DisplayScaleOption(0.85f, R.string.st_text_size_small),
    DisplayScaleOption(1f, R.string.st_text_size_normal),
    DisplayScaleOption(1.15f, R.string.st_text_size_large),
    DisplayScaleOption(1.3f, R.string.st_text_size_xlarge),
)
const val DEFAULT_UI_SCALE = 1f

val TERMINAL_FONT_SIZE_OPTIONS = listOf(12, 14, 16)
const val DEFAULT_TERMINAL_FONT_SIZE = 12

/**
 * Snaps a stored or requested scale to the nearest offered option.
 *
 * Non-finite values and values outside the offered `0.75..1.3` range fall back to
 * [DEFAULT_UI_SCALE], so a corrupt preference cannot leave the UI at an unusable density.
 * Ties round up to the larger option (for example `0.8f -> 0.85f`).
 */
fun normalizeUiScale(value: Float): Float {
    if (!value.isFinite() || value < UI_SCALE_OPTIONS.first().scale || value > UI_SCALE_OPTIONS.last().scale) {
        return DEFAULT_UI_SCALE
    }
    return UI_SCALE_OPTIONS.minWithOrNull(
        compareBy<DisplayScaleOption> { abs(it.scale - value) }.thenByDescending { it.scale },
    )?.scale ?: DEFAULT_UI_SCALE
}

/**
 * Snaps a stored or requested terminal size to the nearest offered option.
 *
 * Ties round up to the larger size (for example `13 -> 14`), and values above the range clamp
 * to the largest option (`99 -> 16`).
 */
fun normalizeTerminalFontSize(value: Int): Int =
    TERMINAL_FONT_SIZE_OPTIONS.minWithOrNull(
        compareBy<Int> { abs(it - value) }.thenByDescending { it },
    ) ?: DEFAULT_TERMINAL_FONT_SIZE
