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

        val s = REF
        val h = s / 2f
        val v = s * STROKE_FRACTION / 2f
        val dd = s * DIAMOND_FRACTION

        val p = Path()
        val base = paths[ortho]
        if (base != null) p.addPath(base)
        else p.addCircle(0f, 0f, v, Path.Direction.CW)   // round hub for diagonal-only cells

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
        return p
    }
}
