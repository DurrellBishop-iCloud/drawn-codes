package uk.dbgh.drawncodes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the drawing cropped to its used cells (+1 cell margin) and saves
 * it as a PNG into Pictures/DrawnCodes via MediaStore.
 */
object Exporter {

    private const val PX_PER_CELL = 80f
    private const val MAX_SIDE = 8192

    fun renderBitmap(model: GridModel, fill: FillEngine.Fill): Bitmap? {
        val b = model.bounds() ?: return null
        val minC = b.left - 1
        val minR = b.top - 1
        val cellsW = b.width() + 3   // inclusive bounds + margin both sides
        val cellsH = b.height() + 3
        var px = PX_PER_CELL
        val side = maxOf(cellsW, cellsH) * px
        if (side > MAX_SIDE) px = MAX_SIDE / maxOf(cellsW, cellsH).toFloat()

        val bmp = Bitmap.createBitmap((cellsW * px).toInt(), (cellsH * px).toInt(),
            Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        Renderer.render(canvas, model, fill, px, -minC * px, -minR * px)
        InkSmooth.smoothBitmap(bmp, (px * InkSmooth.RADIUS_FRACTION).toInt())
        return bmp
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
