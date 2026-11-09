package com.jarves.mh.model

/**
 * Size of the terminal viewport, in character cells.
 *
 * Construction validates the range so every consumer can rely on sane values;
 * callers that only have a raw pixel-derived estimate should use [clamped].
 */
data class TerminalViewport(val columns: Int, val rows: Int) {
    init {
        require(columns in MIN_COLUMNS..MAX_COLUMNS) {
            "columns must be in $MIN_COLUMNS..$MAX_COLUMNS, was $columns"
        }
        require(rows in MIN_ROWS..MAX_ROWS) {
            "rows must be in $MIN_ROWS..$MAX_ROWS, was $rows"
        }
    }

    companion object {
        const val MIN_COLUMNS = 20
        const val MAX_COLUMNS = 400
        const val MIN_ROWS = 4
        const val MAX_ROWS = 200
        val Default = TerminalViewport(80, 24)

        /** Clamps raw pixel-derived estimates to the supported range. */
        fun clamped(columns: Int, rows: Int): TerminalViewport =
            TerminalViewport(
                columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS),
                rows.coerceIn(MIN_ROWS, MAX_ROWS),
            )
    }
}
