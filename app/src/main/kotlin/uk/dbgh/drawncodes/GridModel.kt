package uk.dbgh.drawncodes

import android.graphics.Rect

/**
 * Sparse, unbounded grid of cells. Each cell holds a 4-bit connection code
 * (up=1000, right=0100, down=0010, left=0001) plus a TOUCHED flag so an
 * isolated tapped cell renders as a dot. Coordinates are signed — the
 * canvas grows in any direction as strokes reach new territory.
 */
class GridModel {

    companion object {
        const val UP = 8
        const val RIGHT = 4
        const val DOWN = 2
        const val LEFT = 1
        const val TOUCHED = 16
        // 45° connections, through the corner diamonds
        const val UR = 32
        const val DR = 64
        const val DL = 128
        const val UL = 256
        const val ORTHO_MASK = 15
        const val DIAG_MASK = UR or DR or DL or UL
        const val SHAPE_MASK = ORTHO_MASK or DIAG_MASK

        fun key(r: Int, c: Int): Long = (r.toLong() shl 32) or (c.toLong() and 0xffffffffL)
        fun keyRow(k: Long): Int = (k shr 32).toInt()
        fun keyCol(k: Long): Int = k.toInt()
    }

    private val cells = HashMap<Long, Int>()
    private val undoStack = ArrayDeque<HashMap<Long, Int>>()

    /** Bumped on every mutation; consumers cache against it. */
    var version = 0L
        private set

    operator fun get(r: Int, c: Int): Int = cells[key(r, c)] ?: 0

    val isEmpty: Boolean get() = cells.isEmpty()

    private fun put(r: Int, c: Int, code: Int) {
        if (code == 0) cells.remove(key(r, c)) else cells[key(r, c)] = code
        version++
    }

    fun forEach(action: (r: Int, c: Int, code: Int) -> Unit) {
        for ((k, code) in cells) action(keyRow(k), keyCol(k), code)
    }

    /** Bounding box of occupied cells (left/top/right/bottom inclusive), or null. */
    fun bounds(): Rect? {
        if (cells.isEmpty()) return null
        var minR = Int.MAX_VALUE; var maxR = Int.MIN_VALUE
        var minC = Int.MAX_VALUE; var maxC = Int.MIN_VALUE
        for (k in cells.keys) {
            val r = keyRow(k); val c = keyCol(k)
            if (r < minR) minR = r; if (r > maxR) maxR = r
            if (c < minC) minC = c; if (c > maxC) maxC = c
        }
        return Rect(minC, minR, maxC, maxR)
    }

    fun touch(r: Int, c: Int) = put(r, c, this[r, c] or TOUCHED)

    /** Connect two 4-neighbour cells (both get their facing bits). */
    fun connect(r0: Int, c0: Int, r1: Int, c1: Int) {
        val pair = when {
            r1 == r0 - 1 && c1 == c0 -> UP to DOWN
            r1 == r0 + 1 && c1 == c0 -> DOWN to UP
            c1 == c0 + 1 && r1 == r0 -> RIGHT to LEFT
            c1 == c0 - 1 && r1 == r0 -> LEFT to RIGHT
            else -> return
        }
        put(r0, c0, this[r0, c0] or pair.first or TOUCHED)
        put(r1, c1, this[r1, c1] or pair.second or TOUCHED)
    }

    /** Connect two diagonal neighbours (both get their facing corner bits). */
    fun connectDiagonal(r0: Int, c0: Int, r1: Int, c1: Int) {
        val pair = when {
            r1 == r0 - 1 && c1 == c0 + 1 -> UR to DL
            r1 == r0 + 1 && c1 == c0 + 1 -> DR to UL
            r1 == r0 + 1 && c1 == c0 - 1 -> DL to UR
            r1 == r0 - 1 && c1 == c0 - 1 -> UL to DR
            else -> return
        }
        put(r0, c0, this[r0, c0] or pair.first or TOUCHED)
        put(r1, c1, this[r1, c1] or pair.second or TOUCHED)
    }

    /** Clear a cell and the facing bits of its neighbours. */
    fun erase(r: Int, c: Int) {
        put(r, c, 0)
        put(r - 1, c, this[r - 1, c] and DOWN.inv())
        put(r + 1, c, this[r + 1, c] and UP.inv())
        put(r, c - 1, this[r, c - 1] and RIGHT.inv())
        put(r, c + 1, this[r, c + 1] and LEFT.inv())
        put(r - 1, c + 1, this[r - 1, c + 1] and DL.inv())
        put(r + 1, c + 1, this[r + 1, c + 1] and UL.inv())
        put(r + 1, c - 1, this[r + 1, c - 1] and UR.inv())
        put(r - 1, c - 1, this[r - 1, c - 1] and DR.inv())
    }

    fun clear() {
        cells.clear()
        version++
    }

    // ---- undo ----------------------------------------------------------

    fun pushUndo() {
        undoStack.addLast(HashMap(cells))
        while (undoStack.size > 60) undoStack.removeFirst()
    }

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        cells.clear()
        cells.putAll(prev)
        version++
        return true
    }
}
