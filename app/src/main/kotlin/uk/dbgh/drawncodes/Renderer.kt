package uk.dbgh.drawncodes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

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

    fun render(canvas: Canvas, model: GridModel, fill: FillEngine.Fill,
               cellSize: Float, offsetX: Float, offsetY: Float) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        // enclosed-area fill: row runs of half-cells, slightly padded so
        // antialiased edges don't leave seams
        val hs = cellSize / 2f
        for (y in 0 until fill.height) {
            val top = offsetY + (fill.originHalfR + y) * hs
            if (top + hs < 0 || top > h) continue
            var x = 0
            while (x < fill.width) {
                if (fill.at(y, x)) {
                    var x2 = x
                    while (x2 + 1 < fill.width && fill.at(y, x2 + 1)) x2++
                    val left = offsetX + (fill.originHalfC + x) * hs
                    val right = offsetX + (fill.originHalfC + x2 + 1) * hs
                    if (right >= 0 && left <= w) {
                        canvas.drawRect(left - 0.5f, top - 0.5f,
                            right + 0.5f, top + hs + 0.5f, ink)
                    }
                    x = x2 + 1
                } else x++
            }
        }

        // tiles
        val scale = cellSize / TileSet.REF
        model.forEach { r, c, code ->
            val bits = code and 15
            val path = if (bits == 0) {
                if (code and GridModel.TOUCHED != 0) TileSet.dot else null
            } else TileSet.paths[bits]
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
}
