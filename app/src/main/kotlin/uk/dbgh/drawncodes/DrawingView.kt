package uk.dbgh.drawncodes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.floor
import kotlin.math.hypot

/**
 * The interactive canvas. One finger draws (or erases); a second finger
 * cancels the stroke in progress and switches to pinch-zoom / pan. The
 * world is unbounded — pan and draw anywhere.
 */
class DrawingView(context: Context) : View(context) {

    val model = GridModel()
    val viewport = Viewport()
    private val fillEngine = FillEngine()

    var erasing = false

    private enum class Mode { NONE, DRAW, NAV }
    private var mode = Mode.NONE

    // draw state
    private var lastCol = 0
    private var lastRow = 0
    private var lastX = 0f
    private var lastY = 0f
    private var drawPointerId = -1

    // nav state
    private var navX = 0f
    private var navY = 0f
    private var navSpan = 0f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 225, 225); strokeWidth = 1f
    }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(180, 180, 180); textSize = 28f
    }

    init {
        setBackgroundColor(Color.WHITE)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (ow == 0) viewport.reset(w)
    }

    fun undo() { model.undo(); invalidate() }

    fun clearAll() {
        model.pushUndo()
        model.clear()
        invalidate()
    }

    fun fitContent() {
        val b = model.bounds()
        if (b != null) viewport.fit(b, width, height) else viewport.reset(width)
        invalidate()
    }

    fun currentFill(): FillEngine.Fill = fillEngine.fillFor(model)

    // ---- drawing -------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGuideGrid(canvas)
        Renderer.render(canvas, model, fillEngine.fillFor(model),
            viewport.cellSize,
            -viewport.originX * viewport.cellSize,
            -viewport.originY * viewport.cellSize)
        canvas.drawText("v$APP_VERSION", 12f, height - 10f, hudPaint)
    }

    private fun drawGuideGrid(canvas: Canvas) {
        val s = viewport.cellSize
        if (s < 20f) return   // too dense to be useful when zoomed far out
        var cx = floor(viewport.screenToCellX(0f))
        while (true) {
            val px = viewport.cellToScreenX(cx)
            if (px > width) break
            canvas.drawLine(px, 0f, px, height.toFloat(), gridPaint)
            cx += 1f
        }
        var cy = floor(viewport.screenToCellY(0f))
        while (true) {
            val py = viewport.cellToScreenY(cy)
            if (py > height) break
            canvas.drawLine(0f, py, width.toFloat(), py, gridPaint)
            cy += 1f
        }
    }

    // ---- touch ---------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.DRAW
                drawPointerId = e.getPointerId(0)
                model.pushUndo()
                lastX = e.x; lastY = e.y
                lastCol = colAt(e.x); lastRow = rowAt(e.y)
                if (erasing) model.erase(lastRow, lastCol) else model.touch(lastRow, lastCol)
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.DRAW) {
                    // a second finger means navigation, not drawing:
                    // revert the stroke started by the first finger
                    model.undo()
                    mode = Mode.NAV
                }
                rebaseNav(e)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAW -> {
                    val idx = e.findPointerIndex(drawPointerId)
                    if (idx >= 0) {
                        for (i in 0 until e.historySize) {
                            strokeTo(e.getHistoricalX(idx, i), e.getHistoricalY(idx, i))
                        }
                        strokeTo(e.getX(idx), e.getY(idx))
                        invalidate()
                    }
                }
                Mode.NAV -> {
                    val (mx, my, span) = navAnchor(e)
                    if (navSpan > 0f && span > 0f) {
                        viewport.zoomBy(span / navSpan, navX, navY)
                    }
                    viewport.panByPixels(mx - navX, my - navY)
                    navX = mx; navY = my; navSpan = span
                    invalidate()
                }
                Mode.NONE -> {}
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.NAV) rebaseNav(e, ignoreIndex = e.actionIndex)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                drawPointerId = -1
            }
        }
        return true
    }

    /** Midpoint and span of the active pointers (optionally skipping one). */
    private fun navAnchor(e: MotionEvent, ignoreIndex: Int = -1): Triple<Float, Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == ignoreIndex) continue
            sx += e.getX(i); sy += e.getY(i); n++
        }
        val mx = sx / n; val my = sy / n
        var span = 0f
        if (n >= 2) {
            var i0 = -1; var i1 = -1
            for (i in 0 until e.pointerCount) {
                if (i == ignoreIndex) continue
                if (i0 < 0) i0 = i else if (i1 < 0) i1 = i
            }
            span = hypot((e.getX(i0) - e.getX(i1)).toDouble(),
                         (e.getY(i0) - e.getY(i1)).toDouble()).toFloat()
        }
        return Triple(mx, my, span)
    }

    private fun rebaseNav(e: MotionEvent, ignoreIndex: Int = -1) {
        val (mx, my, span) = navAnchor(e, ignoreIndex)
        navX = mx; navY = my; navSpan = span
    }

    private fun colAt(px: Float) = floor(viewport.screenToCellX(px)).toInt()
    private fun rowAt(py: Float) = floor(viewport.screenToCellY(py)).toInt()

    /**
     * Advance the stroke to (x, y), sampling along the segment from the
     * last point so fast strokes still cross boundaries one cell at a time.
     */
    private fun strokeTo(x: Float, y: Float) {
        val dist = hypot((x - lastX).toDouble(), (y - lastY).toDouble()).toFloat()
        val steps = (dist / (viewport.cellSize / 4f)).toInt() + 1
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            visitCell(colAt(lastX + (x - lastX) * t), rowAt(lastY + (y - lastY) * t))
        }
        lastX = x; lastY = y
    }

    private fun visitCell(c: Int, r: Int) {
        if (c == lastCol && r == lastRow) return
        var cc = lastCol
        var cr = lastRow
        while (cc != c || cr != r) {
            val dx = c - cc
            val dy = r - cr
            val stepX = if (dx > 0) 1 else if (dx < 0) -1 else 0
            val stepY = if (dy > 0) 1 else if (dy < 0) -1 else 0
            val (nc, nr) = if (Math.abs(dx) >= Math.abs(dy) && stepX != 0)
                Pair(cc + stepX, cr) else Pair(cc, cr + stepY)
            if (erasing) model.erase(nr, nc) else model.connect(cr, cc, nr, nc)
            cc = nc; cr = nr
        }
        lastCol = c
        lastRow = r
    }
}
