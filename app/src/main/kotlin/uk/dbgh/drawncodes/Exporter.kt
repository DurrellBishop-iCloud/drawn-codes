package uk.dbgh.drawncodes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders all layers cropped to their combined used cells (+1 cell
 * margin) and saves a PNG into Pictures/DrawnCodes via MediaStore. Each
 * layer is rendered as a mask, smoothed on the CPU, then composited in
 * its colour, bottom layer first.
 */
object Exporter {

    private const val PX_PER_CELL = 80f
    private const val MAX_SIDE = 8192

    fun renderBitmap(view: DrawingView): Bitmap? {
        var b: Rect? = null
        for (m in view.layers) {
            val mb = m.bounds() ?: continue
            if (b == null) b = mb else b.union(mb)
        }
        if (b == null) return null
        val minC = b.left - 1
        val minR = b.top - 1
        val cellsW = b.width() + 3   // inclusive bounds + margin both sides
        val cellsH = b.height() + 3
        var px = PX_PER_CELL
        val side = maxOf(cellsW, cellsH) * px
        if (side > MAX_SIDE) px = MAX_SIDE / maxOf(cellsW, cellsH).toFloat()

        val w = (cellsW * px).toInt()
        val h = (cellsH * px).toInt()
        val outPx = IntArray(w * h) { 0xFFFFFFFF.toInt() }

        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val maskPx = IntArray(w * h)
        for (i in view.layerOrder) {
            val m = view.layers[i]
            if (m.isEmpty) continue
            mask.eraseColor(Color.WHITE)
            val canvas = Canvas(mask)
            Renderer.render(canvas, m, view.fillFor(i), px, -minC * px, -minR * px, Color.BLACK)
            InkSmooth.smoothMask(mask, (px * InkSmooth.RADIUS_FRACTION).toInt())
            mask.getPixels(maskPx, 0, w, 0, 0, w, h)
            val color = view.layerColors[i]
            for (j in maskPx.indices) {
                if (maskPx[j] and 0xFF < 128) outPx[j] = color
            }
        }
        mask.recycle()

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        return out
    }

    /** Returns the saved display name, or null on failure. */
    fun saveToPhotos(context: Context, bmp: Bitmap): String? {
        val name = "code_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DrawnCodes")
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: return null
        return name
    }
}
