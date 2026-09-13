package uk.dbgh.drawncodes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build

/**
 * Final smoothing pass over the rendered ink: gaussian blur + threshold
 * (morphological rounding). Closes sub-stroke-width cracks and rounds
 * every concave notch that per-cell tile composition can't see across
 * cells — and matches the soft look of the reference marker drawings.
 *
 * Screen path uses a RenderNode with a blur + AGSL threshold chain
 * (API 33+; older devices just draw unsmoothed). Export uses a CPU
 * box-blur approximation + threshold.
 */
object InkSmooth {

    /** Smoothing radius as a fraction of a cell. */
    const val RADIUS_FRACTION = 0.07f

    private const val THRESHOLD_AGSL = """
        uniform shader inp;
        half4 main(float2 xy) {
            half a = inp.eval(xy).a;
            half t = smoothstep(0.42, 0.58, a);
            return half4(0.0, 0.0, 0.0, t);
        }
    """

    private val node: RenderNode? =
        if (Build.VERSION.SDK_INT >= 29) RenderNode("ink") else null

    val available: Boolean
        get() = Build.VERSION.SDK_INT >= 33 && node != null

    /**
     * Draw `drawInk` smoothed onto `canvas`. Falls back to drawing
     * directly when the effect isn't available (old API, software canvas).
     */
    fun draw(canvas: Canvas, width: Int, height: Int, radiusPx: Float,
             drawInk: (Canvas) -> Unit) {
        val n = node
        if (!available || n == null || !canvas.isHardwareAccelerated || radiusPx < 1f) {
            drawInk(canvas)
            return
        }
        n.setPosition(0, 0, width, height)
        val rec = n.beginRecording(width, height)
        try {
            drawInk(rec)
        } finally {
            n.endRecording()
        }
        val blur = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
        val threshold = RuntimeShader(THRESHOLD_AGSL)
        n.setRenderEffect(RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(threshold, "inp"), blur))
        canvas.drawRenderNode(n)
    }

    /**
     * CPU equivalent for export bitmaps: blur the black/white image
     * (3× separable box blur ≈ gaussian) and threshold back to ink.
     */
    fun smoothBitmap(bmp: Bitmap, radiusPx: Int) {
        if (radiusPx < 1) return
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var gray = FloatArray(w * h) { (px[it] and 0xFF).toFloat() }   // blue channel: 0 ink, 255 paper
        val r = (radiusPx / 1.7f).toInt().coerceAtLeast(1)   // 3 box passes ≈ gaussian radius
        repeat(3) {
            gray = boxBlurH(gray, w, h, r)
            gray = boxBlurV(gray, w, h, r)
        }
        for (i in px.indices) {
            px[i] = if (gray[i] < 128f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun boxBlurH(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val out = FloatArray(w * h)
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (x in -r..r) sum += src[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                out[row + x] = sum / div
                sum += src[row + (x + r + 1).coerceAtMost(w - 1)]
                sum -= src[row + (x - r).coerceAtLeast(0)]
            }
        }
        return out
    }

    private fun boxBlurV(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val out = FloatArray(w * h)
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sum = 0f
            for (y in -r..r) sum += src[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / div
                sum += src[(y + r + 1).coerceAtMost(h - 1) * w + x]
                sum -= src[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }
}
