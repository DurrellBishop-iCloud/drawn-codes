package uk.dbgh.drawncodes

/**
 * Computes which areas are fully enclosed by strokes.
 *
 * The stroke centrelines are edges of a half-cell lattice: each connection
 * bit blocks passage between two half-cells. Flood-fill regions of
 * half-cells over the drawing's bounds (+1 cell margin so the outside is
 * one connected ring), then walk the region graph from the outside —
 * crossing a stroke is one nesting level — and fill odd-depth regions.
 * White pockets smaller than a one-cell hole (4 half-cells) are absorbed:
 * they come from finger wobble, not intent.
 */
class FillEngine {

    /** Result grid of half-cells; origin in absolute half-cell coords. */
    class Fill(
        val originHalfR: Int, val originHalfC: Int,
        val width: Int, val height: Int,
        private val grid: BooleanArray
    ) {
        fun at(localY: Int, localX: Int) = grid[localY * width + localX]
        companion object { val EMPTY = Fill(0, 0, 0, 0, BooleanArray(0)) }
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

        fun bits(y: Int, x: Int) = model[originR + y / 2, originC + x / 2] and 15

        // moving down from local half-cell (y,x): crossing the horizontal
        // arm through the middle of that cell
        fun blockedDown(y: Int, x: Int): Boolean {
            if (y % 2 == 1) return false
            val v = bits(y, x)
            return (v and (if (x % 2 == 0) GridModel.LEFT else GridModel.RIGHT)) != 0
        }

        // moving right from (y,x): crossing the vertical arm
        fun blockedRight(y: Int, x: Int): Boolean {
            if (x % 2 == 1) return false
            val v = bits(y, x)
            return (v and (if (y % 2 == 0) GridModel.UP else GridModel.DOWN)) != 0
        }

        // label connected regions
        val region = IntArray(n) { -1 }
        var nReg = 0
        val stack = ArrayDeque<Int>()
        for (start in 0 until n) {
            if (region[start] != -1) continue
            region[start] = nReg
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                val y = i / hw
                val x = i % hw
                if (y > 0 && region[i - hw] == -1 && !blockedDown(y - 1, x)) {
                    region[i - hw] = nReg; stack.addLast(i - hw)
                }
                if (y < hh - 1 && region[i + hw] == -1 && !blockedDown(y, x)) {
                    region[i + hw] = nReg; stack.addLast(i + hw)
                }
                if (x > 0 && region[i - 1] == -1 && !blockedRight(y, x - 1)) {
                    region[i - 1] = nReg; stack.addLast(i - 1)
                }
                if (x < hw - 1 && region[i + 1] == -1 && !blockedRight(y, x)) {
                    region[i + 1] = nReg; stack.addLast(i + 1)
                }
            }
            nReg++
        }

        // region adjacency across stroke edges
        val adj = Array(nReg) { mutableSetOf<Int>() }
        for (y in 0 until hh) for (x in 0 until hw) {
            val i = y * hw + x
            if (y < hh - 1 && blockedDown(y, x)) {
                val a = region[i]; val bb = region[i + hw]
                if (a != bb) { adj[a].add(bb); adj[bb].add(a) }
            }
            if (x < hw - 1 && blockedRight(y, x)) {
                val a = region[i]; val bb = region[i + 1]
                if (a != bb) { adj[a].add(bb); adj[bb].add(a) }
            }
        }

        // nesting depth from the outside (local (0,0) is in the margin
        // ring, which strokes can never cut)
        val depth = IntArray(nReg) { -1 }
        val queue = ArrayDeque<Int>()
        depth[region[0]] = 0
        queue.addLast(region[0])
        while (queue.isNotEmpty()) {
            val a = queue.removeFirst()
            for (bb in adj[a]) if (depth[bb] == -1) {
                depth[bb] = depth[a] + 1
                queue.addLast(bb)
            }
        }

        val area = IntArray(nReg)
        for (i in 0 until n) area[region[i]]++

        val grid = BooleanArray(n)
        for (i in 0 until n) {
            val rg = region[i]
            val d = depth[rg]
            grid[i] = d > 0 && (d % 2 == 1 || area[rg] <= 3)
        }
        return Fill(originR * 2, originC * 2, hw, hh, grid)
    }
}
