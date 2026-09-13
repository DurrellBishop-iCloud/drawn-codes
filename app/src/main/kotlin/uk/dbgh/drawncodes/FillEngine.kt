package uk.dbgh.drawncodes

/**
 * Computes which areas are fully enclosed by strokes.
 *
 * Orthogonal stroke centrelines are edges of a half-cell lattice: each
 * connection bit blocks passage between two half-cells. A 45° stroke runs
 * exactly along the centre→corner diagonal of one half-cell in each of the
 * two cells it links, so those "cut" half-cells are split into two
 * triangle nodes. Flood-fill the node graph over the drawing's bounds
 * (+1 cell margin so the outside is one connected ring), then walk the
 * region graph from the outside — crossing a stroke is one nesting
 * level — and fill odd-depth regions. White pockets smaller than a
 * one-cell hole are absorbed: they come from finger wobble, not intent.
 */
class FillEngine {

    companion object {
        const val NONE: Byte = 0   // half-cell unfilled
        const val FULL: Byte = 1   // half-cell filled
        const val TRI0: Byte = 2   // only triangle 0 filled (N/E or N/W side)
        const val TRI1: Byte = 3   // only triangle 1 filled (the other side)
    }

    /** Result grid of half-cell states; origin in absolute half-cell coords. */
    class Fill(
        val originHalfR: Int, val originHalfC: Int,
        val width: Int, val height: Int,
        private val state: ByteArray
    ) {
        fun at(localY: Int, localX: Int): Byte = state[localY * width + localX]
        companion object { val EMPTY = Fill(0, 0, 0, 0, ByteArray(0)) }
    }

    private var cached = Fill.EMPTY
    private var cachedVersion = -1L

    fun fillFor(model: GridModel): Fill {
        if (model.version != cachedVersion) {
            cached = compute(model)
            cachedVersion = model.version
        }
        return cached
    }

    private fun compute(model: GridModel): Fill {
        val b = model.bounds() ?: return Fill.EMPTY
        val originR = b.top - 1
        val originC = b.left - 1
        val hw = (b.width() + 3) * 2   // bounds are inclusive: w+2 cells, ×2
        val hh = (b.height() + 3) * 2
        val n = hw * hh

        fun code(y: Int, x: Int) = model[originR + y / 2, originC + x / 2]

        // which diagonal bit would cut this half-cell (its cell's corner
        // quadrant), e.g. the top-right half-cell is cut by UR
        fun cutBit(y: Int, x: Int): Int {
            val top = y % 2 == 0
            val left = x % 2 == 0
            return when {
                top && left -> GridModel.UL
                top -> GridModel.UR
                left -> GridModel.DL
                else -> GridModel.DR
            }
        }

        fun isCut(y: Int, x: Int) = (code(y, x) and cutBit(y, x)) != 0

        // Node ids: half-cell i → nodes 2i (t0) and 2i+1 (t1, cut only).
        // For a NW–SE cut (x,y same parity) t0 is the NE triangle
        // (touching the N and E borders); for a NE–SW cut t0 is the NW
        // triangle (N and W borders).
        // border: 0=N, 1=E, 2=S, 3=W
        fun nodeAt(y: Int, x: Int, border: Int): Int {
            val i = y * hw + x
            if (!isCut(y, x)) return 2 * i
            val t0 = if ((x % 2) == (y % 2)) border == 0 || border == 1
                     else border == 0 || border == 3
            return if (t0) 2 * i else 2 * i + 1
        }

        // moving down across the S border of (y,x): blocked by the
        // horizontal arm through the middle of that cell
        fun blockedDown(y: Int, x: Int): Boolean {
            if (y % 2 == 1) return false
            val v = code(y, x)
            return (v and (if (x % 2 == 0) GridModel.LEFT else GridModel.RIGHT)) != 0
        }

        fun blockedRight(y: Int, x: Int): Boolean {
            if (x % 2 == 1) return false
            val v = code(y, x)
            return (v and (if (y % 2 == 0) GridModel.UP else GridModel.DOWN)) != 0
        }

        // ---- union-find over nodes ------------------------------------
        val parent = IntArray(2 * n) { it }
        fun find(a: Int): Int {
            var x0 = a
            while (parent[x0] != x0) { parent[x0] = parent[parent[x0]]; x0 = parent[x0] }
            return x0
        }
        fun union(a: Int, bb: Int) {
            val ra = find(a); val rb = find(bb)
            if (ra != rb) parent[ra] = rb
        }

        // stroke crossings: pairs of nodes separated by ink
        val crossings = ArrayList<Long>()
        fun crossing(a: Int, bb: Int) { crossings.add((a.toLong() shl 32) or bb.toLong()) }

        for (y in 0 until hh) for (x in 0 until hw) {
            if (x < hw - 1) {
                val a = nodeAt(y, x, 1); val bb = nodeAt(y, x + 1, 3)
                if (blockedRight(y, x)) crossing(a, bb) else union(a, bb)
            }
            if (y < hh - 1) {
                val a = nodeAt(y, x, 2); val bb = nodeAt(y + 1, x, 0)
                if (blockedDown(y, x)) crossing(a, bb) else union(a, bb)
            }
            if (isCut(y, x)) {
                val i = y * hw + x
                crossing(2 * i, 2 * i + 1)   // the 45° stroke splits the two triangles
            }
        }

        // ---- regions, nesting depth, area -----------------------------
        val regionOf = HashMap<Int, Int>()
        val area = ArrayList<Int>()
        fun regionId(root: Int): Int = regionOf.getOrPut(root) { area.add(0); area.size - 1 }

        val nodeRegion = IntArray(2 * n) { -1 }
        for (y in 0 until hh) for (x in 0 until hw) {
            val i = y * hw + x
            val r0 = regionId(find(2 * i))
            nodeRegion[2 * i] = r0
            area[r0]++
            if (isCut(y, x)) {
                val r1 = regionId(find(2 * i + 1))
                nodeRegion[2 * i + 1] = r1
                area[r1]++
            }
        }

        val nReg = area.size
        val adj = Array(nReg) { mutableSetOf<Int>() }
        for (cr in crossings) {
            val a = nodeRegion[(cr shr 32).toInt()]
            val bb = nodeRegion[cr.toInt()]
            if (a != bb) { adj[a].add(bb); adj[bb].add(a) }
        }

        val depth = IntArray(nReg) { -1 }
        val queue = ArrayDeque<Int>()
        val outside = nodeRegion[0]   // local (0,0) is in the margin ring
        depth[outside] = 0
        queue.addLast(outside)
        while (queue.isNotEmpty()) {
            val a = queue.removeFirst()
            for (bb in adj[a]) if (depth[bb] == -1) {
                depth[bb] = depth[a] + 1
                queue.addLast(bb)
            }
        }

        fun filledRegion(rg: Int): Boolean {
            val d = depth[rg]
            return d > 0 && (d % 2 == 1 || area[rg] <= 3)
        }

        val state = ByteArray(n)
        for (y in 0 until hh) for (x in 0 until hw) {
            val i = y * hw + x
            val f0 = filledRegion(nodeRegion[2 * i])
            if (!isCut(y, x)) {
                state[i] = if (f0) FULL else NONE
            } else {
                val f1 = filledRegion(nodeRegion[2 * i + 1])
                state[i] = when {
                    f0 && f1 -> FULL   // stroke covers the seam between them
                    f0 -> TRI0
                    f1 -> TRI1
                    else -> NONE
                }
            }
        }
        return Fill(originR * 2, originC * 2, hw, hh, state)
    }
}
