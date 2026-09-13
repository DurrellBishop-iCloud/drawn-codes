package uk.dbgh.drawncodes

// Drawn Codes v0.4.2
// Grid drawing tool: finger crossing a cell boundary sets one of four
// connection bits per cell (up=1000, right=0100, down=0010, left=0001).
// One finger draws, two fingers pinch-zoom and pan; the canvas is
// unbounded. Enclosed areas fill solid (even-odd nesting).
//
// Modules: GridModel (sparse cells + undo), FillEngine (enclosure),
// TileSet (tile geometry), Renderer (screen + export drawing),
// Viewport (pan/zoom), DrawingView (gestures), Exporter (PNG save).

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

const val APP_VERSION = "0.4.2"

class MainActivity : AppCompatActivity() {

    private lateinit var drawingView: DrawingView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        drawingView = DrawingView(this)
        root.addView(drawingView, LinearLayout.LayoutParams(
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
        barButton("UNDO") { drawingView.undo() }
        barButton("CLEAR") { drawingView.clearAll() }
        barButton("FIT") { drawingView.fitContent() }
        barButton("SAVE") { saveDrawing() }

        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun saveDrawing() {
        val bmp = Exporter.renderBitmap(drawingView.model, drawingView.currentFill())
        if (bmp == null) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            return
        }
        val name = Exporter.saveToPhotos(this, bmp)
        Toast.makeText(this,
            if (name != null) "Saved $name" else "Save failed", Toast.LENGTH_SHORT).show()
    }
}
