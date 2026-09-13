package uk.dbgh.drawncodes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * Draws a model (fill first, then tiles) onto any canvas — the screen view
 * and the PNG exporter share this. The transform is given as cellSize plus
 * the pixel offset of world cell (0,0).
 */
object Renderer {

    val ink: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val tri = Path()

    fun render(canvas: Canvas, model: GridModel, fill: FillEngine.Fill,
               cellSize: Float, offsetX: Float, offsetY: Float) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        // enclosed-area fill: row runs of fully-filled half-cells (slightly
        // padded so antialiased edges don't leave seams), then the
        // triangle halves left by 45° boundaries
        val hs = cellSize / 2f
        for (y in 0 until fill.height) {
            val top = offsetY + (fill.originHalfR + y) * hs
            if (top + hs < 0 || top > h) continue
            var x = 0
            while (x < fill.width) {
                val st = fill.at(y, x)
                if (st == FillEngine.FULL) {
                    var x2 = x
                    while (x2 + 1 < fill.width && fill.at(y, x2 + 1) == FillEngine.FULL) x2++
                    val left = offsetX + (fill.originHalfC + x) * hs
                    val right = offsetX + (fill.originHalfC + x2 + 1) * hs
                    if (right >= 0 && left <= w) {
                        canvas.drawRect(left - 0.5f, top - 0.5f,
                            right + 0.5f, top + hs + 0.5f, ink)
                    }
                    x = x2 + 1
                } else {
                    if (st == FillEngine.TRI0 || st == FillEngine.TRI1) {
                        val left = offsetX + (fill.originHalfC + x) * hs
                        if (left + hs >= 0 && left <= w) {
                            drawTriangle(canvas, st, y, x, left, top, hs)
                        }
                    }
                    x++
                }
            }
        }

        // tiles
        val scale = cellSize / TileSet.REF
        model.forEach { r, c, code ->
            val path = if (code and GridModel.SHAPE_MASK == 0) {
                if (code and GridModel.TOUCHED != 0) TileSet.dot else null
            } else TileSet.pathFor(code)
            if (path != null) {
                val cx = offsetX + (c + 0.5f) * cellSize
                val cy = offsetY + (r + 0.5f) * cellSize
                if (cx + cellSize >= 0 && cx - cellSize <= w &&
                    cy + cellSize >= 0 && cy - cellSize <= h) {
                    canvas.save()
                    canvas.translate(cx, cy)
                    canvas.scale(scale, scale)
                    canvas.drawPath(path, ink)
                    canvas.restore()
                }
            }
        }
    }

    // A cut half-cell's diagonal follows its cell's centre→corner line:
    // NW–SE when local x,y share parity (t0 = NE triangle), NE–SW
    // otherwise (t0 = NW triangle). Origins are cell-aligned, so local
    // parity is quadrant parity.
    private fun drawTriangle(canvas: Canvas, st: Byte, y: Int, x: Int,
                             left: Float, top: Float, hs: Float) {
        val right = left + hs
        val bottom = top + hs
        tri.rewind()
        if ((x % 2) == (y % 2)) {   // NW–SE diagonal
            if (st == FillEngine.TRI0) {   // NE
                tri.moveTo(left, top); tri.lineTo(right, top); tri.lineTo(right, bottom)
            } else {                       // SW
                tri.moveTo(left, top); tri.lineTo(left, bottom); tri.lineTo(right, bottom)
            }
        } else {                    // NE–SW diagonal
            if (st == FillEngine.TRI0) {   // NW
                tri.moveTo(left, top); tri.lineTo(right, top); tri.lineTo(left, bottom)
            } else {                       // SE
                tri.moveTo(right, top); tri.lineTo(right, bottom); tri.lineTo(left, bottom)
            }
        }
        tri.close()
        canvas.drawPath(tri, ink)
    }
}
