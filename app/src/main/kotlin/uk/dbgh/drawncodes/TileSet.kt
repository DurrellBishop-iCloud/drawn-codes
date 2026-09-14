package uk.dbgh.drawncodes

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF

/**
 * The 16 canonical tile shapes, built once at a reference size
 * (REF × REF, origin at the cell centre). The renderer scales the canvas,
 * so tiles never need rebuilding when the zoom changes.
 *
 * Stroke width is STROKE_FRACTION of a cell. Shapes: stub = arm + round
 * cap at the centre; two adjacent bits = quarter annulus centred on the
 * shared cell corner; T and cross = arms + concave fillets (corner circles
 * subtracted).
 */
object TileSet {

    const val REF = 128f
    const val STROKE_FRACTION = 0.5f
    // Corner-diamond half-diagonal (along the axes) as a cell fraction.
    // Flush with the 45° bar's edges (half-width v perpendicular →
    // v·√2 = 0.354 along the axes), plus a hair to avoid seams — so
    // diagonal strokes keep exactly the same visual weight as straight
    // ones. The diamonds remain as touch/recognition zones.
    const val DIAMOND_FRACTION = 0.36f

    val paths: Array<Path?> = arrayOfNulls(16)
    val dot: Path

    /** Composed shapes (orthogonal tile + diagonal arms + corner diamonds). */
    private val composed = HashMap<Int, Path?>()

    init {
        val s = REF
        val h = s / 2f
        val v = s * STROKE_FRACTION / 2f   // half stroke width
        val rin = h - v
        val rout = h + v

        fun circle(cx: Float, cy: Float, r: Float): Path =
            Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }

        dot = circle(0f, 0f, v)

        val stub = Path().apply {   // pointing UP
            addRect(-v, -h, v, 0f, Path.Direction.CW)
            op(circle(0f, 0f, v), Path.Op.UNION)
        }

        val straight = Path().apply { addRect(-v, -h, v, h, Path.Direction.CW) }  // UP|DOWN

        val corner = Path().apply {  // UP|RIGHT: annulus on the top-right corner
            val outer = RectF(h - rout, -h - rout, h + rout, -h + rout)
            val inner = RectF(h - rin, -h - rin, h + rin, -h + rin)
            moveTo(-v, -h)
            arcTo(outer, 180f, -90f)
            lineTo(h, -v)
            arcTo(inner, 90f, 90f)
            close()
        }

        // quarter of the cell minus the corner circle → concave fillet
        fun filletQuadrant(qx: Int, qy: Int): Path = Path().apply {
            addRect(if (qx < 0) -h else 0f, if (qy < 0) -h else 0f,
                    if (qx < 0) 0f else h, if (qy < 0) 0f else h, Path.Direction.CW)
            op(circle(qx * h, qy * h, rin), Path.Op.DIFFERENCE)
        }

        val tee = Path().apply {  // UP|RIGHT|DOWN (open left)
            addRect(-v, -h, v, h, Path.Direction.CW)
            op(Path().apply { addRect(0f, -v, h, v, Path.Direction.CW) }, Path.Op.UNION)
            op(filletQuadrant(1, -1), Path.Op.UNION)
            op(filletQuadrant(1, 1), Path.Op.UNION)
        }

        val cross = Path().apply {
            addRect(-h, -h, h, h, Path.Direction.CW)
            op(circle(-h, -h, rin), Path.Op.DIFFERENCE)
            op(circle(h, -h, rin), Path.Op.DIFFERENCE)
            op(circle(-h, h, rin), Path.Op.DIFFERENCE)
            op(circle(h, h, rin), Path.Op.DIFFERENCE)
        }

        fun rotated(p: Path, deg: Float): Path = Path(p).apply {
            val m = Matrix(); m.setRotate(deg); transform(m)
        }

        val u = GridModel.UP; val r = GridModel.RIGHT
        val d = GridModel.DOWN; val l = GridModel.LEFT
        paths[u] = stub
        paths[r] = rotated(stub, 90f)
        paths[d] = rotated(stub, 180f)
        paths[l] = rotated(stub, 270f)
        paths[u or d] = straight
        paths[l or r] = rotated(straight, 90f)
        paths[u or r] = corner
        paths[r or d] = rotated(corner, 90f)
        paths[d or l] = rotated(corner, 180f)
        paths[l or u] = rotated(corner, 270f)
        paths[u or r or d] = tee
        paths[r or d or l] = rotated(tee, 90f)
        paths[u or d or l] = rotated(tee, 180f)
        paths[u or r or l] = rotated(tee, 270f)
        paths[u or r or d or l] = cross
    }

    /**
     * Shape for a full cell code (orthogonal + diagonal bits, TOUCHED
     * ignored): the orthogonal tile, plus a 45° bar to each connected
     * corner and a solid diamond on that corner — the diamonds are the
     * angle markers from Durrell's sketches, and a ring of alternating
     * straight/diagonal segments reads as an octagon.
     */
    fun pathFor(code: Int): Path? {
        val key = code and GridModel.SHAPE_MASK
        return composed.getOrPut(key) { compose(key) }
    }

    private fun compose(key: Int): Path? {
        val ortho = key and GridModel.ORTHO_MASK
        val diag = key and GridModel.DIAG_MASK
        if (diag == 0) return paths[ortho]

        // A cell with exactly two connections 90° or 135° apart is a pure
        // turn: draw it as an arc band tangent to both arms — the
        // generalisation of the orthogonal corner annulus — so turns into
        // diagonals get the same sweeping outer curve.
        if (Integer.bitCount(key) == 2) {
            turnBand(key)?.let { return it }
        }

        val s = REF
        val h = s / 2f
        val v = s * STROKE_FRACTION / 2f
        val dd = s * DIAMOND_FRACTION

        // A diagonal-carrying cell is built from straight arms so the
        // sector fillets always meet straight edges (the pre-built corner
        // tile is an annulus, whose curved boundary left fillet slivers
        // poking out as spikes). Adjacent orthogonal pairs still get the
        // annulus unioned in for the rounded outer corner.
        val p = Path()
        p.addCircle(0f, 0f, v, Path.Direction.CW)   // round hub
        if (ortho and GridModel.UP != 0) p.addRect(-v, -h, v, 0f, Path.Direction.CW)
        if (ortho and GridModel.DOWN != 0) p.addRect(-v, 0f, v, h, Path.Direction.CW)
        if (ortho and GridModel.RIGHT != 0) p.addRect(0f, -v, h, v, Path.Direction.CW)
        if (ortho and GridModel.LEFT != 0) p.addRect(-h, -v, 0f, v, Path.Direction.CW)
        for (pair in intArrayOf(
                GridModel.UP or GridModel.RIGHT, GridModel.RIGHT or GridModel.DOWN,
                GridModel.DOWN or GridModel.LEFT, GridModel.LEFT or GridModel.UP)) {
            if (ortho and pair == pair) p.op(paths[pair]!!, Path.Op.UNION)
        }

        // n = perpendicular half-width offset of a 45° bar, per axis
        val n = v / kotlin.math.sqrt(2f)
        for ((bit, sx, sy) in listOf(
            Triple(GridModel.UR, 1f, -1f), Triple(GridModel.DR, 1f, 1f),
            Triple(GridModel.DL, -1f, 1f), Triple(GridModel.UL, -1f, -1f))) {
            if (key and bit == 0) continue
            val cx = sx * h
            val cy = sy * h
            p.op(Path().apply {   // bar from the centre to the corner
                moveTo(n * sy, -n * sx)   // offset by the perpendicular (sy, -sx)·n
                lineTo(cx + n * sy, cy - n * sx)
                lineTo(cx - n * sy, cy + n * sx)
                lineTo(-n * sy, n * sx)
                close()
            }, Path.Op.UNION)
            p.op(Path().apply {   // corner diamond
                moveTo(cx - dd, cy)
                lineTo(cx, cy - dd)
                lineTo(cx + dd, cy)
                lineTo(cx, cy + dd)
                close()
            }, Path.Op.UNION)
        }

        // concave fillets between adjacent arms, wherever a diagonal is
        // involved — same radius as the orthogonal tiles' fillets, so
        // inside 45° junctions get the same rounded language
        val rin = h - v
        val dirs = ArrayList<Float>(8)
        if (key and GridModel.RIGHT != 0) dirs.add(0f)
        if (key and GridModel.DR != 0) dirs.add(45f)
        if (key and GridModel.DOWN != 0) dirs.add(90f)
        if (key and GridModel.DL != 0) dirs.add(135f)
        if (key and GridModel.LEFT != 0) dirs.add(180f)
        if (key and GridModel.UL != 0) dirs.add(225f)
        if (key and GridModel.UP != 0) dirs.add(270f)
        if (key and GridModel.UR != 0) dirs.add(315f)
        if (dirs.size >= 2) {
            for (i in dirs.indices) {
                val a1 = dirs[i]
                val a2 = if (i + 1 < dirs.size) dirs[i + 1] else dirs[0] + 360f
                val gap = a2 - a1
                if (gap == 135f || gap == 90f) {
                    // radius a hair past tangent so Path.op never leaves
                    // degenerate slivers at the touch points
                    p.op(sectorFillet(a1, gap, v, rin + 0.75f), Path.Op.UNION)
                } else if (gap == 45f) {
                    // narrow wedge between a straight and a diagonal arm:
                    // its sharp tip lands in the next cell over. Radius is
                    // capped so the fillet stays against ink even when the
                    // neighbouring bar ends in a stub cap right after the
                    // junction ((v+rf)·cot22.5° must stay within one cell).
                    p.op(sectorFillet(a1, gap, v, s * 0.15f), Path.Op.UNION)
                }
            }
        }
        return p
    }

    private val DIR_ANGLES = listOf(
        GridModel.RIGHT to 0f, GridModel.DR to 45f, GridModel.DOWN to 90f,
        GridModel.DL to 135f, GridModel.LEFT to 180f, GridModel.UL to 225f,
        GridModel.UP to 270f, GridModel.UR to 315f)

    /**
     * Two-connection turn as an arc band: centreline arc of radius h
     * tangent to both arm centrelines (for an orthogonal pair this IS the
     * corner annulus), plus trimmed arms out to the cell boundary and the
     * diamond on any diagonal corner. Returns null unless the two
     * connections are 90° or 135° apart (45° is a hairpin, 180° straight).
     */
    private fun turnBand(key: Int): Path? {
        val pair = DIR_ANGLES.filter { key and it.first != 0 }
        if (pair.size != 2) return null
        var a1 = pair[0].second
        var a2 = pair[1].second
        if (a2 < a1) { val t = a1; a1 = a2; a2 = t }
        var gap = a2 - a1
        if (gap > 180f) { val t = a1; a1 = a2; a2 = t + 360f; gap = 360f - gap }
        if (gap != 90f && gap != 135f) return null

        val s = REF
        val h = s / 2f
        val v = s * STROKE_FRACTION / 2f
        val half = Math.toRadians(gap / 2.0)
        val cDist = (h / kotlin.math.sin(half)).toFloat()
        val bisDeg = a1 + gap / 2f
        val bis = Math.toRadians(bisDeg.toDouble())
        val cx = (cDist * kotlin.math.cos(bis)).toFloat()
        val cy = (cDist * kotlin.math.sin(bis)).toFloat()
        val foot = (h / kotlin.math.tan(half)).toFloat()
        val span = 180f - gap
        val rOut = h + v
        val rIn = kotlin.math.max(h - v, 0.5f)
        val startDeg = bisDeg + 180f - span / 2f

        val p = Path()
        p.arcTo(android.graphics.RectF(cx - rOut, cy - rOut, cx + rOut, cy + rOut),
                startDeg, span)
        p.arcTo(android.graphics.RectF(cx - rIn, cy - rIn, cx + rIn, cy + rIn),
                startDeg + span, -span)
        p.close()

        val dd = s * DIAMOND_FRACTION
        for ((bit, ang) in pair) {
            val isDiag = bit and GridModel.DIAG_MASK != 0
            val len = if (isDiag) h * kotlin.math.sqrt(2f) else h
            val ar = Math.toRadians(ang.toDouble())
            val dx = kotlin.math.cos(ar).toFloat()
            val dy = kotlin.math.sin(ar).toFloat()
            val px = -dy * v
            val py = dx * v
            if (len > foot + 0.01f) {
                p.op(Path().apply {   // arm from the band's tangent foot outward
                    moveTo(dx * foot + px, dy * foot + py)
                    lineTo(dx * len + px, dy * len + py)
                    lineTo(dx * len - px, dy * len - py)
                    lineTo(dx * foot - px, dy * foot - py)
                    close()
                }, Path.Op.UNION)
            }
            if (isDiag) {
                val ccx = dx * len
                val ccy = dy * len
                p.op(Path().apply {   // corner diamond
                    moveTo(ccx - dd, ccy); lineTo(ccx, ccy - dd)
                    lineTo(ccx + dd, ccy); lineTo(ccx, ccy + dd)
                    close()
                }, Path.Op.UNION)
            }
        }
        return p
    }

    /**
     * Filler for the sector between two arms `gap` degrees apart: bounded
     * by the arms' edges and a concave arc of radius `rf` tangent to both.
     */
    private fun sectorFillet(a1: Float, gap: Float, v: Float, rf: Float): Path {
        val half = Math.toRadians(gap / 2.0)
        val bis = Math.toRadians((a1 + gap / 2f).toDouble())
        val pDist = (v / kotlin.math.sin(half)).toFloat()      // sharp corner point
        val cDist = ((v + rf) / kotlin.math.sin(half)).toFloat()  // arc centre
        val cx = (cDist * kotlin.math.cos(bis)).toFloat()
        val cy = (cDist * kotlin.math.sin(bis)).toFloat()
        val n1 = Math.toRadians((a1 + 90f).toDouble())
        val n2 = Math.toRadians((a1 + gap - 90f).toDouble())
        val path = Path()
        path.moveTo((pDist * kotlin.math.cos(bis)).toFloat(),
                    (pDist * kotlin.math.sin(bis)).toFloat())
        path.lineTo(cx - rf * kotlin.math.cos(n1).toFloat(),
                    cy - rf * kotlin.math.sin(n1).toFloat())
        path.arcTo(android.graphics.RectF(cx - rf, cy - rf, cx + rf, cy + rf),
                   a1 - 90f, gap - 180f)
        path.lineTo(cx - rf * kotlin.math.cos(n2).toFloat(),
                    cy - rf * kotlin.math.sin(n2).toFloat())
        path.close()
        return path
    }
}
