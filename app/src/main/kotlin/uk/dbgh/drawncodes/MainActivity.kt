package uk.dbgh.drawncodes

// Drawn Codes v0.10.0
// Grid drawing tool: finger crossing a cell boundary sets one of four
// orthogonal + four diagonal connection bits per cell. One finger draws,
// two fingers pinch-zoom and pan; the canvas is unbounded. Enclosed
// areas fill solid (even-odd nesting).
//
// Modules: GridModel (sparse cells + undo), FillEngine (enclosure),
// PathInk (skeleton-stroke geometry), Renderer (screen + export),
// Viewport (pan/zoom), DrawingView (gestures), Exporter (PNG save).

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

const val APP_VERSION = "0.10.0"

class MainActivity : AppCompatActivity() {

    private lateinit var drawingView: DrawingView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        drawingView = DrawingView(this)
        Store.load(this, drawingView)
        drawingView.onChanged = { Store.save(this, drawingView) }

        // canvas with a quiet chip row floating on top
        val canvasFrame = FrameLayout(this)
        canvasFrame.addView(drawingView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), 0)
        }

        fun chip(label: String, active: () -> Boolean, onTap: () -> Unit): TextView =
            TextView(this).apply {
                text = label
                textSize = 12f
                setPadding(dp(12), dp(7), dp(12), dp(7))
                fun restyle() {
                    if (active()) {
                        setBackgroundColor(Color.argb(200, 0, 0, 0))
                        setTextColor(Color.WHITE)
                    } else {
                        setBackgroundColor(Color.argb(28, 0, 0, 0))
                        setTextColor(Color.argb(140, 0, 0, 0))
                    }
                }
                restyle()
                setOnClickListener { onTap(); restyle() }
                chipRow.addView(this, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, dp(6), 0)
                })
            }

        chip("UNDO", { false }) { drawingView.undo() }

        // colour picker panel (hidden until the ＋ chip is tapped):
        // adjusts the active layer's colour live
        val picker = ColorPickerView(this)
        val pickerPanel = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(235, 250, 250, 250))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(picker, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            visibility = android.view.View.GONE
        }

        // four colour dots — each one is a layer
        val dots = ArrayList<Pair<android.view.View, android.graphics.drawable.GradientDrawable>>()
        fun restyleDots() {
            for ((idx, pair) in dots.withIndex()) {
                pair.second.setColor(drawingView.layerColors[idx])
                pair.second.setStroke(
                    if (drawingView.activeLayer == idx) dp(3) else dp(1),
                    if (drawingView.activeLayer == idx) Color.rgb(60, 60, 60)
                    else Color.argb(70, 0, 0, 0))
            }
        }
        for (i in 0 until DrawingView.LAYER_COUNT) {
            val d = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(drawingView.layerColors[i])
            }
            val dot = android.view.View(this).apply {
                background = d
                setOnClickListener {
                    drawingView.activeLayer = i
                    restyleDots()
                    if (pickerPanel.visibility == android.view.View.VISIBLE) {
                        picker.setColor(drawingView.layerColors[i])
                    }
                    Store.save(this@MainActivity, drawingView)
                }
            }
            dots.add(dot to d)
            chipRow.addView(dot, LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                setMargins(dp(5), dp(2), dp(5), 0)
            })
        }
        restyleDots()

        picker.onColorChanged = { color ->
            drawingView.layerColors[drawingView.activeLayer] = color
            restyleDots()
            drawingView.invalidate()
        }
        picker.onColorCommitted = { Store.save(this, drawingView) }

        // one more chip: opens the colour picker for the current dot
        chip("＋", { pickerPanel.visibility == android.view.View.VISIBLE }) {
            if (pickerPanel.visibility == android.view.View.VISIBLE) {
                pickerPanel.visibility = android.view.View.GONE
            } else {
                picker.setColor(drawingView.layerColors[drawingView.activeLayer])
                pickerPanel.visibility = android.view.View.VISIBLE
            }
        }

        chipRow.addView(android.view.View(this),
            LinearLayout.LayoutParams(0, 1, 1f))   // spacer
        chip("45", { drawingView.allow45 }) { drawingView.allow45 = !drawingView.allow45 }
        chip("90", { drawingView.allow90 }) { drawingView.allow90 = !drawingView.allow90 }
        chip("FILL", { drawingView.showFill }) {
            drawingView.showFill = !drawingView.showFill
            drawingView.invalidate()
        }

        val topOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(chipRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(pickerPanel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(200)).apply {
                setMargins(dp(8), dp(6), dp(8), 0)
            })
        }
        canvasFrame.addView(topOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP))

        root.addView(canvasFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(20, 20, 20))
            setPadding(dp(6), dp(6), dp(6), dp(10))
        }

        fun barButton(label: String, onClick: (Button) -> Unit): Button =
            Button(this).apply {
                text = label
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(50, 50, 50))
                setPadding(dp(4), 0, dp(4), 0)
                minWidth = 0; minimumWidth = 0
                setOnClickListener { onClick(this) }
                bar.addView(this, LinearLayout.LayoutParams(
                    0, dp(44), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
            }

        barButton("ERASE") { b ->
            drawingView.erasing = !drawingView.erasing
            b.text = if (drawingView.erasing) "DRAW" else "ERASE"
            b.setBackgroundColor(
                if (drawingView.erasing) Color.rgb(150, 40, 40) else Color.rgb(50, 50, 50))
        }
        barButton("SNAP") { b ->
            drawingView.snapping = !drawingView.snapping
            b.setBackgroundColor(
                if (drawingView.snapping) Color.rgb(40, 90, 150) else Color.rgb(50, 50, 50))
        }.setBackgroundColor(Color.rgb(40, 90, 150))
        barButton("CLEAR") { drawingView.clearAll() }
        barButton("FIT") { drawingView.fitContent() }
        barButton("SAVE") { saveDrawing() }

        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    override fun onPause() {
        super.onPause()
        Store.save(this, drawingView)   // catches pan/zoom-only changes too
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun saveDrawing() {
        val bmp = Exporter.renderBitmap(drawingView)
        if (bmp == null) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            return
        }
        val name = Exporter.saveToPhotos(this, bmp)
        Toast.makeText(this,
            if (name != null) "Saved $name" else "Save failed", Toast.LENGTH_SHORT).show()
    }
}
