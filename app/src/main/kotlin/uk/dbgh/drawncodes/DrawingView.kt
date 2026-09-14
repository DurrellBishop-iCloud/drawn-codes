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

    /** Four drawing layers, one colour each; strokes go to the active one. */
    val layers = Array(LAYER_COUNT) { GridModel() }
    private val fillEngines = Array(LAYER_COUNT) { FillEngine() }
    var activeLayer = 0

    /** The layer strokes currently draw into. */
    val model: GridModel get() = layers[activeLayer]

    val viewport = Viewport()

    var erasing = false
    var allow45 = true    // recognise 45° moves through corner diamonds
    var allow90 = true    // draw orthogonal connections
    var showFill = true   // fill enclosed areas
    var snapping = true   // start-of-stroke offset to the nearest cell centre

    // touch-down offset to the nearest cell centre (screen px): applied to
    // the whole stroke, so pure finger MOTION determines the path and
    // angles are easy to hit from any landing spot
    private var snapX = 0f
    private var snapY = 0f

    /** Called after anything worth persisting (stroke end, undo, clear). */
    var onChanged: (() -> Unit)? = null
    /** Set when a saved viewport was loaded, so layout doesn't reset it. */
    var viewportRestored = false

    private enum class Mode { NONE, DRAW, NAV }
    private var mode = Mode.NONE

    // draw state
    private var lastCol = 0
    private var lastRow = 0
    private var lastX = 0f
    private var lastY = 0f
    private var drawPointerId = -1

    // corner-diamond pass-through state: a stroke that enters the diamond
    // zone on a grid corner and leaves into the diagonal cell reads as a
    // 45° connection — the diamonds are what make angles recognisable
    private var inCorner = false
    private var cornerR = 0
    private var cornerC = 0

    companion object {
        /** Corner diamond reach (|dx|+|dy| in cell units) for hit-testing. */
        const val CORNER_ZONE = 0.38f

        const val LAYER_COUNT = 4
        val DEFAULT_COLORS = intArrayOf(
            0xFF000000.toInt(),   // black
            0xFFE0362C.toInt(),   // red
            0xFF1D6FE0.toInt(),   // blue
            0xFFF2A900.toInt())   // amber
    }

    /** Current colour of each layer (editable via the palette). */
    val layerColors = DEFAULT_COLORS.copyOf()

    /** Render order, bottom to top — reordered by dragging the dots. */
    val layerOrder = intArrayOf(0, 1, 2, 3)

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
        if (ow == 0 && !viewportRestored) viewport.reset(w)
    }

    fun undo() {
        model.undo()
        invalidate()
        onChanged?.invoke()
    }

    fun clearAll() {
        model.pushUndo()
        model.clear()
        invalidate()
        onChanged?.invoke()
    }

    fun fitContent() {
        var b: android.graphics.Rect? = null
        for (m in layers) {
            val mb = m.bounds() ?: continue
            if (b == null) b = mb else b.union(mb)
        }
        if (b != null) viewport.fit(b, width, height) else viewport.reset(width)
        invalidate()
    }

    fun fillFor(layer: Int): FillEngine.Fill =
        if (showFill) fillEngines[layer].fillFor(layers[layer]) else FillEngine.Fill.EMPTY

    // ---- drawing -------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGuideGrid(canvas)
        val radius = viewport.cellSize * InkSmooth.RADIUS_FRACTION
        for (i in layerOrder) {
            if (layers[i].isEmpty) continue
            InkSmooth.draw(canvas, width, height, radius, layerColors[i], i) { c ->
                Renderer.render(c, layers[i], fillFor(i),
                    viewport.cellSize,
                    -viewport.originX * viewport.cellSize,
                    -viewport.originY * viewport.cellSize,
                    layerColors[i])
            }
        }
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
                if (snapping) {
                    val wx = viewport.screenToCellX(e.x)
                    val wy = viewport.screenToCellY(e.y)
                    snapX = (floor(wx) + 0.5f - wx) * viewport.cellSize
                    snapY = (floor(wy) + 0.5f - wy) * viewport.cellSize
                } else {
                    snapX = 0f; snapY = 0f
                }
                lastX = e.x + snapX; lastY = e.y + snapY
                lastCol = colAt(lastX); lastRow = rowAt(lastY)
                inCorner = false
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
                            strokeTo(e.getHistoricalX(idx, i) + snapX,
                                     e.getHistoricalY(idx, i) + snapY)
                        }
                        strokeTo(e.getX(idx) + snapX, e.getY(idx) + snapY)
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
                val wasDrawing = mode == Mode.DRAW
                mode = Mode.NONE
                drawPointerId = -1
                if (wasDrawing) onChanged?.invoke()
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
            visitPoint(lastX + (x - lastX) * t, lastY + (y - lastY) * t)
        }
        lastX = x; lastY = y
    }

    private fun visitPoint(px: Float, py: Float) {
        val wx = viewport.screenToCellX(px)
        val wy = viewport.screenToCellY(py)
        val c = floor(wx).toInt()
        val r = floor(wy).toInt()

        if (!erasing && allow45) {   // erasing works on whole cells, corners ignored
            // nearest grid corner, in cell units
            val kc = Math.round(wx).toInt()
            val kr = Math.round(wy).toInt()
            if (Math.abs(wx - kc) + Math.abs(wy - kr) <= CORNER_ZONE) {
                if (!inCorner) { inCorner = true; cornerR = kr; cornerC = kc }
                return   // hold position while inside the diamond
            }
            if (inCorner) {
                inCorner = false
                if (r != lastRow || c != lastCol) {
                    val viaThisCorner =
                        cornerR == maxOf(r, lastRow) && cornerC == maxOf(c, lastCol)
                    if (viaThisCorner &&
                        Math.abs(r - lastRow) == 1 && Math.abs(c - lastCol) == 1) {
                        model.connectDiagonal(lastRow, lastCol, r, c)
                        lastRow = r; lastCol = c
                        return
                    }
                }
            }
        }
        visitCell(c, r)
    }

    private fun visitCell(c: Int, r: Int) {
        if (c == lastCol && r == lastRow) return
        if (!erasing && !allow90) {   // orthogonal drawing off: move without ink
            lastCol = c
            lastRow = r
            return
        }
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
