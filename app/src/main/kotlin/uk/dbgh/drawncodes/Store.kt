package uk.dbgh.drawncodes

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Persists the drawing (and the viewport) to app-private storage so the
 * canvas survives restarts and app updates. The data is tiny — a handful
 * of bytes per touched cell — so saves are synchronous after each stroke.
 */
object Store {

    private const val MAGIC = 0x44430001   // "DC" + format version 1

    private fun file(context: Context) = File(context.filesDir, "drawing.bin")

    fun save(context: Context, view: DrawingView) {
        try {
            val tmp = File(context.filesDir, "drawing.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeFloat(view.viewport.cellSize)
                out.writeFloat(view.viewport.originX)
                out.writeFloat(view.viewport.originY)
                var count = 0
                view.model.forEach { _, _, _ -> count++ }
                out.writeInt(count)
                view.model.forEach { r, c, code ->
                    out.writeLong(GridModel.key(r, c))
                    out.writeInt(code)
                }
            }
            tmp.renameTo(file(context))
        } catch (_: Exception) {
            // a failed save never disturbs drawing
        }
    }

    fun load(context: Context, view: DrawingView) {
        val f = file(context)
        if (!f.exists()) return
        try {
            DataInputStream(f.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return
                val cellSize = input.readFloat()
                val ox = input.readFloat()
                val oy = input.readFloat()
                val count = input.readInt()
                val cells = HashMap<Long, Int>(count * 2)
                repeat(count) {
                    val k = input.readLong()
                    cells[k] = input.readInt()
                }
                view.model.restore(cells)
                view.viewport.restore(cellSize, ox, oy)
                view.viewportRestored = true
                view.invalidate()
            }
        } catch (_: Exception) {
            // unreadable file: start blank rather than crash
        }
    }
}
