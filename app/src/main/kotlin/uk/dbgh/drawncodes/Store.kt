package uk.dbgh.drawncodes

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Persists the drawing (all layers and the viewport) to app-private
 * storage so the canvas survives restarts and app updates. The data is
 * tiny — a handful of bytes per touched cell — so saves are synchronous
 * after each stroke.
 */
object Store {

    private const val MAGIC_V1 = 0x44430001   // single layer
    private const val MAGIC_V2 = 0x44430002   // four layers + active index
    private const val MAGIC_V3 = 0x44430003   // + per-layer colours

    private fun file(context: Context) = File(context.filesDir, "drawing.bin")

    fun save(context: Context, view: DrawingView) {
        try {
            val tmp = File(context.filesDir, "drawing.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC_V3)
                out.writeFloat(view.viewport.cellSize)
                out.writeFloat(view.viewport.originX)
                out.writeFloat(view.viewport.originY)
                out.writeInt(view.activeLayer)
                for (color in view.layerColors) out.writeInt(color)
                for (m in view.layers) {
                    var count = 0
                    m.forEach { _, _, _ -> count++ }
                    out.writeInt(count)
                    m.forEach { r, c, code ->
                        out.writeLong(GridModel.key(r, c))
                        out.writeInt(code)
                    }
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
                val magic = input.readInt()
                if (magic != MAGIC_V1 && magic != MAGIC_V2 && magic != MAGIC_V3) return
                val cellSize = input.readFloat()
                val ox = input.readFloat()
                val oy = input.readFloat()
                if (magic != MAGIC_V1) {
                    view.activeLayer = input.readInt().coerceIn(0, DrawingView.LAYER_COUNT - 1)
                }
                if (magic == MAGIC_V3) {
                    for (i in 0 until DrawingView.LAYER_COUNT) view.layerColors[i] = input.readInt()
                }
                val nLayers = if (magic == MAGIC_V1) 1 else DrawingView.LAYER_COUNT
                for (i in 0 until nLayers) {
                    val count = input.readInt()
                    val cells = HashMap<Long, Int>(count * 2)
                    repeat(count) {
                        val k = input.readLong()
                        cells[k] = input.readInt()
                    }
                    view.layers[i].restore(cells)
                }
                view.viewport.restore(cellSize, ox, oy)
                view.viewportRestored = true
                view.invalidate()
            }
        } catch (_: Exception) {
            // unreadable file: start blank rather than crash
        }
    }
}
