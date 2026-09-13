package uk.dbgh.drawncodes

import android.graphics.Rect

/**
 * Pan/zoom state mapping world cell coordinates to screen pixels.
 * `cellSize` is the on-screen size of one cell; (originX, originY) is the
 * world cell coordinate at the screen's top-left corner.
 */
class Viewport {

    var cellSize = 96f
        private set
    var originX = 0f
        private set
    var originY = 0f
        private set

    companion object {
        const val MIN_CELL = 14f
        const val MAX_CELL = 400f
    }

    fun screenToCellX(px: Float) = originX + px / cellSize
    fun screenToCellY(py: Float) = originY + py / cellSize
    fun cellToScreenX(cx: Float) = (cx - originX) * cellSize
    fun cellToScreenY(cy: Float) = (cy - originY) * cellSize

    fun panByPixels(dx: Float, dy: Float) {
        originX -= dx / cellSize
        originY -= dy / cellSize
    }

    /** Zoom by `factor` keeping the world point under (focusX, focusY) fixed. */
    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val newSize = (cellSize * factor).coerceIn(MIN_CELL, MAX_CELL)
        val wx = screenToCellX(focusX)
        val wy = screenToCellY(focusY)
        cellSize = newSize
        originX = wx - focusX / cellSize
        originY = wy - focusY / cellSize
    }

    /** Frame the given cell bounds (inclusive) inside viewWidth×viewHeight. */
    fun fit(bounds: Rect, viewWidth: Int, viewHeight: Int) {
        val cellsW = bounds.width() + 3f   // inclusive bounds + 1 cell margin
        val cellsH = bounds.height() + 3f
        cellSize = minOf(viewWidth / cellsW, viewHeight / cellsH)
            .coerceIn(MIN_CELL, MAX_CELL)
        originX = bounds.left - 1 - (viewWidth / cellSize - (cellsW - 2)) / 2f
        originY = bounds.top - 1 - (viewHeight / cellSize - (cellsH - 2)) / 2f
    }

    fun restore(size: Float, ox: Float, oy: Float) {
        cellSize = size.coerceIn(MIN_CELL, MAX_CELL)
        originX = ox
        originY = oy
    }

    fun reset(viewWidth: Int) {
        cellSize = viewWidth / 12f
        originX = 0f
        originY = 0f
    }
}
