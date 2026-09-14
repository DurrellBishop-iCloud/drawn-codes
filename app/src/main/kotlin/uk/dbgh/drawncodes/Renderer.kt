package uk.dbgh.drawncodes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * Draws a model (fill first, then the stroked skeleton) onto any canvas —
 * the screen view and the PNG exporter share this. The transform is given
 * as cellSize plus the pixel offset of world cell (0,0)'s top-left corner.
 */
object Renderer {

    val ink: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val stroke: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = PathInk.STROKE
    }

    private class Cached(var version: Long, var ink: PathInk.Ink)
    private val cache = java.util.WeakHashMap<GridModel, Cached>()

    private val tri = Path()

    fun render(canvas: Canvas, model: GridModel, fill: FillEngine.Fill,
               cellSize: Float, offsetX: Float, offsetY: Float,
               color: Int = Color.BLACK) {
        ink.color = color
        stroke.color = color
        drawFill(canvas, fill, cellSize, offsetX, offsetY)

        var cached = cache[model]
        if (cached == null || cached.version != model.version) {
            cached = Cached(model.version, PathInk.build(model))
            cache[model] = cached
        }
        val paths = cached.ink
        canvas.save()
        // path coordinates are cell units with centres on integers
        canvas.translate(offsetX + 0.5f * cellSize, offsetY + 0.5f * cellSize)
        canvas.scale(cellSize, cellSize)
        canvas.drawPath(paths.strokes, stroke)
        canvas.drawPath(paths.fills, ink)
        canvas.restore()
    }

    // enclosed-area fill: row runs of fully-filled half-cells (slightly
    // padded so antialiased edges don't leave seams), then the triangle
    // halves left by 45° boundaries
    private fun drawFill(canvas: Canvas, fill: FillEngine.Fill,
                         cellSize: Float, offsetX: Float, offsetY: Float) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
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
    }

    // A cut half-cell's diagonal follows its cell's centre→corner line:
    // NW–SE when local x,y share parity (t0 = NE triangle), NE–SW
    // otherwise (t0 = NW triangle).
    private fun drawTriangle(canvas: Canvas, st: Byte, y: Int, x: Int,
                             left: Float, top: Float, hs: Float) {
        val right = left + hs
        val bottom = top + hs
        tri.rewind()
        if ((x % 2) == (y % 2)) {
            if (st == FillEngine.TRI0) {
                tri.moveTo(left, top); tri.lineTo(right, top); tri.lineTo(right, bottom)
            } else {
                tri.moveTo(left, top); tri.lineTo(left, bottom); tri.lineTo(right, bottom)
            }
        } else {
            if (st == FillEngine.TRI0) {
                tri.moveTo(left, top); tri.lineTo(right, top); tri.lineTo(left, bottom)
            } else {
                tri.moveTo(right, top); tri.lineTo(right, bottom); tri.lineTo(left, bottom)
            }
        }
        tri.close()
        canvas.drawPath(tri, ink)
    }
}
