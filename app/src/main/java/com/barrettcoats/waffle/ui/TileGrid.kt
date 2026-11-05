package com.barrettcoats.waffle.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.barrettcoats.waffle.data.RowAlign

/**
 * A centered grid of equal-width columns, as wide as the widest child. Every row lines up with
 * the same columns; a row with fewer tiles than columns sits left, centered or right per [align].
 * Children are centered in their cell, so mixed tile sizes still line up.
 *
 * [columns] 0 means as many columns as fit. That count doesn't shrink when a section has fewer
 * tiles, so a one-row section follows [align] too, instead of always being centered.
 */
@Composable
fun TileGrid(
    gap: Dp,
    align: RowAlign,
    modifier: Modifier = Modifier,
    columns: Int = 0,
    /** Told how many columns there are, every layout pass (for moving tiles up and down by rows). */
    onColumns: (Int) -> Unit = {},
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val maxWidth = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints(maxWidth = maxWidth)) }
        if (placeables.isEmpty()) return@Layout layout(maxWidth, 0) {}

        val gapPx = gap.roundToPx()
        val cellWidth = placeables.maxOf { it.width }
        val count = if (columns > 0) columns else ((maxWidth + gapPx) / (cellWidth + gapPx)).coerceAtLeast(1)
        onColumns(count)
        val rows = placeables.chunked(count)
        val rowHeights = rows.map { row -> row.maxOf { it.height } }
        val height = rowHeights.sum() + gapPx * (rows.size - 1)
        val step = cellWidth + gapPx
        val left = (maxWidth - (count * cellWidth + (count - 1) * gapPx)) / 2

        layout(maxWidth, height) {
            var y = 0
            rows.forEachIndexed { r, row ->
                val empty = count - row.size
                val offset = when (align) {
                    RowAlign.LEFT -> 0
                    RowAlign.CENTER -> empty * step / 2
                    RowAlign.RIGHT -> empty * step
                }
                row.forEachIndexed { i, p ->
                    val x = left + offset + i * step + (cellWidth - p.width) / 2
                    p.place(x, y + (rowHeights[r] - p.height) / 2)
                }
                y += rowHeights[r] + gapPx
            }
        }
    }
}
