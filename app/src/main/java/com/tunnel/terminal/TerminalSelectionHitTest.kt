package com.tunnel.terminal

/**
 * Wave-20b: Accurate terminal text selection hit-testing.
 *
 * OLD BUG: pos→cell used firstVisibleItemIndex + assumed uniform charH:
 *   row = firstIdx + ((y - pad + firstOff) / charH)
 * That drifts when LazyColumn item layout doesn't match metrics (font padding
 * history, density, partial scroll), so the highlight lands on the line *above*
 * the finger/mouse.
 *
 * FIX: Map Y through real visible-item offsets from LazyListLayoutInfo, and map X
 * through viewportWidth / cols (same grid the PTY was sized for).
 */
object TerminalSelectionHitTest {

    data class VisibleItem(
        val index: Int,
        /** Offset from top of LazyColumn viewport (px). May be negative if partially scrolled. */
        val offset: Int,
        val size: Int
    )

    /**
     * @param localX X relative to LazyColumn viewport (already subtracted outer pad)
     * @param localY Y relative to LazyColumn viewport (already subtracted outer pad)
     * @param visibleItems from listState.layoutInfo.visibleItemsInfo
     * @param viewportWidthPx LazyList viewport width
     * @param cols terminal columns (renderCols)
     * @param totalRows scrollback + live row count
     * @param fallbackCharW used only if viewport/cols invalid
     * @param fallbackCharH used only if no visible items
     * @param firstVisibleIndex fallback index
     * @param firstVisibleScrollOffset fallback px scrolled within first item
     */
    fun posToCell(
        localX: Float,
        localY: Float,
        visibleItems: List<VisibleItem>,
        viewportWidthPx: Int,
        cols: Int,
        totalRows: Int,
        fallbackCharW: Float,
        fallbackCharH: Float,
        firstVisibleIndex: Int = 0,
        firstVisibleScrollOffset: Int = 0
    ): Pair<Int, Int> {
        val maxRow = (totalRows - 1).coerceAtLeast(0)
        val maxCol = (cols - 1).coerceAtLeast(0)

        val row = resolveRow(
            localY = localY,
            visibleItems = visibleItems,
            maxRow = maxRow,
            fallbackCharH = fallbackCharH.coerceAtLeast(1f),
            firstVisibleIndex = firstVisibleIndex,
            firstVisibleScrollOffset = firstVisibleScrollOffset
        )

        val charW = cellWidthPx(viewportWidthPx, cols, fallbackCharW)
        val col = (localX / charW).toInt().coerceIn(0, maxCol)
        return Pair(row, col)
    }

    fun resolveRow(
        localY: Float,
        visibleItems: List<VisibleItem>,
        maxRow: Int,
        fallbackCharH: Float,
        firstVisibleIndex: Int,
        firstVisibleScrollOffset: Int
    ): Int {
        if (visibleItems.isNotEmpty()) {
            /* Above the first visible item → that item (or clamp later). */
            val first = visibleItems.first()
            if (localY < first.offset) {
                return first.index.coerceIn(0, maxRow)
            }
            for (item in visibleItems) {
                val top = item.offset.toFloat()
                val bottom = (item.offset + item.size).toFloat()
                if (localY < bottom) {
                    return item.index.coerceIn(0, maxRow)
                }
            }
            /* Below last visible → last visible index. */
            return visibleItems.last().index.coerceIn(0, maxRow)
        }
        /* No layout yet — approximate with metrics (legacy formula, fixed sign). */
        val h = fallbackCharH.coerceAtLeast(1f)
        val row = firstVisibleIndex +
            ((localY + firstVisibleScrollOffset) / h).toInt()
        return row.coerceIn(0, maxRow)
    }

    /** Cell width matching PTY grid: prefer viewport / cols over em estimate. */
    fun cellWidthPx(viewportWidthPx: Int, cols: Int, fallbackCharW: Float): Float {
        if (cols > 0 && viewportWidthPx > 0) {
            return (viewportWidthPx.toFloat() / cols).coerceAtLeast(1f)
        }
        return fallbackCharW.coerceAtLeast(1f)
    }

    /**
     * Viewport Y (relative to LazyColumn content, before outer pad) of the top of [row].
     * Returns null if row is not among visible items and cannot be estimated.
     */
    fun rowTopInViewport(
        row: Int,
        visibleItems: List<VisibleItem>,
        fallbackCharH: Float
    ): Float? {
        visibleItems.find { it.index == row }?.let { return it.offset.toFloat() }
        val first = visibleItems.firstOrNull() ?: return null
        val h = (visibleItems.firstOrNull()?.size?.toFloat() ?: fallbackCharH).coerceAtLeast(1f)
        return first.offset + (row - first.index) * h
    }

    fun rowBottomInViewport(
        row: Int,
        visibleItems: List<VisibleItem>,
        fallbackCharH: Float
    ): Float? {
        visibleItems.find { it.index == row }?.let {
            return (it.offset + it.size).toFloat()
        }
        val top = rowTopInViewport(row, visibleItems, fallbackCharH) ?: return null
        val h = (visibleItems.firstOrNull()?.size?.toFloat() ?: fallbackCharH).coerceAtLeast(1f)
        return top + h
    }
}
