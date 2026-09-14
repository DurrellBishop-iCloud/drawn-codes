package uk.dbgh.drawncodes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * Compact HSV colour picker: saturation/value square with a hue bar
 * underneath. Reports every change live and a commit on finger lift.
 */
class ColorPickerView(context: Context) : View(context) {

    var onColorChanged: ((Int) -> Unit)? = null
    var onColorCommitted: (() -> Unit)? = null

    private val hsv = floatArrayOf(0f, 1f, 0f)   // start on black
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val sv = RectF()
    private val hue = RectF()
    private var inHueBar = false

    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        invalidate()
    }

    val color: Int get() = Color.HSVToColor(hsv)

    override fun onDraw(canvas: Canvas) {
        val pad = 8f
        val hueH = height * 0.22f
        sv.set(pad, pad, width - pad, height - hueH - 2 * pad)
        hue.set(pad, height - hueH - pad, width - pad, height - pad)

        // saturation (white → pure hue) then value (transparent → black)
        val pure = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        paint.shader = LinearGradient(sv.left, 0f, sv.right, 0f,
            Color.WHITE, pure, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(sv, 8f, 8f, paint)
        paint.shader = LinearGradient(0f, sv.top, 0f, sv.bottom,
            0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(sv, 8f, 8f, paint)
        paint.shader = null

        // hue bar
        val stops = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        paint.shader = LinearGradient(hue.left, 0f, hue.right, 0f,
            stops, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(hue, 8f, 8f, paint)
        paint.shader = null

        // markers
        val mx = sv.left + hsv[1] * sv.width()
        val my = sv.top + (1f - hsv[2]) * sv.height()
        markerPaint.color = if (hsv[2] > 0.5f && hsv[1] < 0.5f) Color.BLACK else Color.WHITE
        canvas.drawCircle(mx, my, 14f, markerPaint)
        val hx = hue.left + hsv[0] / 360f * hue.width()
        markerPaint.color = Color.WHITE
        canvas.drawRoundRect(hx - 6f, hue.top - 3f, hx + 6f, hue.bottom + 3f, 6f, 6f, markerPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inHueBar = e.y > sv.bottom + 8f
                update(e)
            }
            MotionEvent.ACTION_MOVE -> update(e)
            MotionEvent.ACTION_UP -> {
                update(e)
                onColorCommitted?.invoke()
            }
        }
        return true
    }

    private fun update(e: MotionEvent) {
        if (inHueBar) {
            hsv[0] = ((e.x - hue.left) / hue.width()).coerceIn(0f, 1f) * 360f
        } else {
            hsv[1] = ((e.x - sv.left) / sv.width()).coerceIn(0f, 1f)
            hsv[2] = 1f - ((e.y - sv.top) / sv.height()).coerceIn(0f, 1f)
        }
        onColorChanged?.invoke(color)
        invalidate()
    }
}
