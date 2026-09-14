package uk.dbgh.drawncodes

import android.graphics.Path
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Skeleton-stroke renderer: the model is a graph (cell centres = nodes,
 * connection bits = edges). Edges are paired into continuing routes at
 * each node (straightest continuation first), every corner of every
 * route is rounded with an arc of half-a-cell radius, and the result is
 * stroked with a thick round pen. All corner behaviour — the 90° corner
 * annulus, 135° sweeps, 45° diagonal blends — falls out of that one
 * rounding rule; there are no per-code tiles to keep consistent.
 *
 * Coordinates are cell units with each cell centred on integer (x=col,
 * y=row). `strokes` is drawn with a round-cap round-join stroke of width
 * STROKE (cell units); `fills` (corner diamonds, dots) is filled.
 */
object PathInk {

    const val STROKE = 0.5f
    const val CORNER_RADIUS = 0.5f
    private const val DIAMOND = 0.36f

    class Ink(val strokes: Path, val fills: Path)

    private data class End(val edge: Int, val side: Int)

    fun build(model: GridModel): Ink {
        // ---- edges (each stored once: R, D, DR, DL from each cell) ----
        val ax = ArrayList<Int>(); val ay = ArrayList<Int>()
        val bx = ArrayList<Int>(); val by = ArrayList<Int>()
        model.forEach { r, c, code ->
            if (code and GridModel.RIGHT != 0) { ax.add(c); ay.add(r); bx.add(c + 1); by.add(r) }
            if (code and GridModel.DOWN != 0) { ax.add(c); ay.add(r); bx.add(c); by.add(r + 1) }
            if (code and GridModel.DR != 0) { ax.add(c); ay.add(r); bx.add(c + 1); by.add(r + 1) }
            if (code and GridModel.DL != 0) { ax.add(c); ay.add(r); bx.add(c - 1); by.add(r + 1) }
        }
        val n = ax.size

        fun nodeX(e: Int, side: Int) = if (side == 0) ax[e] else bx[e]
        fun nodeY(e: Int, side: Int) = if (side == 0) ay[e] else by[e]
        fun key(x: Int, y: Int) = (y.toLong() shl 32) or (x.toLong() and 0xffffffffL)

        val incident = HashMap<Long, MutableList<End>>()
        for (e in 0 until n) {
            incident.getOrPut(key(ax[e], ay[e])) { ArrayList() }.add(End(e, 0))
            incident.getOrPut(key(bx[e], by[e])) { ArrayList() }.add(End(e, 1))
        }

        // outgoing direction of an edge-end, degrees
        fun angleOf(end: End): Float {
            val dx = (nodeX(end.edge, 1 - end.side) - nodeX(end.edge, end.side)).toFloat()
            val dy = (nodeY(end.edge, 1 - end.side) - nodeY(end.edge, end.side)).toFloat()
            return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        }

        // ---- pair edge-ends at each node, straightest continuation first
        val partner = HashMap<End, End>()
        for (ends in incident.values) {
            if (ends.size < 2) continue
            data class Cand(val dev: Float, val e1: End, val e2: End)
            val cands = ArrayList<Cand>()
            for (i in ends.indices) for (j in i + 1 until ends.size) {
                var d = abs(angleOf(ends[i]) - angleOf(ends[j])) % 360f
                if (d > 180f) d = 360f - d
                val dev = 180f - d          // 0 = straight through
                if (dev <= 90.5f) cands.add(Cand(dev, ends[i], ends[j]))
            }
            cands.sortBy { it.dev }
            val used = HashSet<End>()
            for (cand in cands) {
                if (cand.e1 in used || cand.e2 in used) continue
                used.add(cand.e1); used.add(cand.e2)
                partner[cand.e1] = cand.e2
                partner[cand.e2] = cand.e1
            }
        }

        // ---- walk paths ------------------------------------------------
        val visited = BooleanArray(n)
        val strokes = Path()

        fun walk(startEdge: Int, startSide: Int): ArrayList<FloatArray> {
            val pts = ArrayList<FloatArray>()
            pts.add(floatArrayOf(nodeX(startEdge, startSide).toFloat(),
                                 nodeY(startEdge, startSide).toFloat()))
            var e = startEdge
            var side = startSide
            while (true) {
                visited[e] = true
                pts.add(floatArrayOf(nodeX(e, 1 - side).toFloat(),
                                     nodeY(e, 1 - side).toFloat()))
                val next = partner[End(e, 1 - side)] ?: break
                if (visited[next.edge]) break
                e = next.edge
                side = next.side
            }
            return pts
        }

        for (e in 0 until n) {
            if (visited[e]) continue
            val pts = when {
                partner[End(e, 0)] == null -> walk(e, 0)
                partner[End(e, 1)] == null -> walk(e, 1)
                else -> continue
            }
            appendRounded(strokes, pts, closed = false)
        }
        for (e in 0 until n) {           // remaining edges are closed loops
            if (visited[e]) continue
            val pts = walk(e, 0)
            appendRounded(strokes, pts, closed = true)
        }

        // ---- fills: diamonds on diagonal crossings, dots ---------------
        val fills = Path()
        model.forEach { r, c, code ->
            if (code and GridModel.SHAPE_MASK == 0 && code and GridModel.TOUCHED != 0) {
                fills.addCircle(c.toFloat(), r.toFloat(), STROKE / 2f, Path.Direction.CW)
            }
            for ((bit, dc) in listOf(GridModel.DR to 1, GridModel.DL to -1)) {
                if (code and bit != 0) {
                    val cx = c + dc / 2f
                    val cy = r + 0.5f
                    fills.moveTo(cx - DIAMOND, cy)
                    fills.lineTo(cx, cy - DIAMOND)
                    fills.lineTo(cx + DIAMOND, cy)
                    fills.lineTo(cx, cy + DIAMOND)
                    fills.close()
                }
            }
        }
        return Ink(strokes, fills)
    }

    /**
     * Append a polyline with every interior corner replaced by an arc of
     * CORNER_RADIUS (shrunk when a segment is too short). Arcs are emitted
     * as short chords — invisible under the ink smoothing pass.
     */
    private fun appendRounded(path: Path, pts: ArrayList<FloatArray>, closed: Boolean) {
        if (pts.size < 2) return
        if (closed && pts.size >= 2 &&
            (pts[0][0] != pts[pts.size - 1][0] || pts[0][1] != pts[pts.size - 1][1])) {
            pts.add(floatArrayOf(pts[0][0], pts[0][1]))
        }
        // for closed loops also round the seam vertex by wrapping context
        val out = ArrayList<FloatArray>()
        val last = pts.size - 1
        val startK = if (closed) 0 else 1
        val endK = if (closed) last - 1 else last - 1
        if (!closed) out.add(pts[0])
        for (k in startK..endK) {
            if (!closed && (k == 0 || k == last)) continue
            val p0 = if (closed && k == 0) pts[last - 1] else pts[k - 1]
            val p1 = pts[k]
            val p2 = pts[k + 1]
            var ux = p1[0] - p0[0]; var uy = p1[1] - p0[1]
            var wx = p2[0] - p1[0]; var wy = p2[1] - p1[1]
            val lu = hypot(ux.toDouble(), uy.toDouble()).toFloat()
            val lw = hypot(wx.toDouble(), wy.toDouble()).toFloat()
            ux /= lu; uy /= lu; wx /= lw; wy /= lw
            val cross = ux * wy - uy * wx
            val dot = ux * wx + uy * wy
            val dev = atan2(abs(cross).toDouble(), dot.toDouble())
            if (dev < 1e-3) { out.add(p1); continue }
            var r = CORNER_RADIUS
            var t = (r * tan(dev / 2)).toFloat()
            val tMax = minOf(lu, lw) / 2f - 1e-4f
            if (t > tMax) { t = tMax; r = (t / tan(dev / 2)).toFloat() }
            val side = if (cross > 0) 1f else -1f
            val f1x = p1[0] - ux * t; val f1y = p1[1] - uy * t
            val cx = f1x - uy * side * r
            val cy = f1y + ux * side * r
            val a1 = atan2((f1y - cy).toDouble(), (f1x - cx).toDouble())
            val steps = maxOf(4, (dev * 10).toInt())
            for (kk in 0..steps) {
                val a = a1 + side * dev * kk / steps
                out.add(floatArrayOf((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat()))
            }
        }
        if (!closed) out.add(pts[last])
        path.moveTo(out[0][0], out[0][1])
        for (i in 1 until out.size) path.lineTo(out[i][0], out[i][1])
        if (closed) path.close()
    }
}
