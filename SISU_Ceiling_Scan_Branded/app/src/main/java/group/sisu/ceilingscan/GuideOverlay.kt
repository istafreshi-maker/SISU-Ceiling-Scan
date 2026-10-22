package group.sisu.ceilingscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/** Visual alignment only: NOT a geometric proof of 75% overlap. */
class GuideOverlay(context: Context) : View(context) {
    var showGhost: Boolean = true
        set(value) { field = value; invalidate() }
    var ghost: Bitmap? = null
        set(value) { field = value; invalidate() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(175, 77, 231, 201); strokeWidth = 2.5f }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 197, 76); strokeWidth = 4f }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 65 }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (showGhost && ghost != null) {
            val bm = ghost!!
            // approximate composition reference, not pixel-accurate alignment
            canvas.drawBitmap(bm, null, Rect(0, 0, width, height), bitmapPaint)
        }
        // both 25%-step boundaries are visible for 75% overlapping strips
        for (i in 1..3) {
            val x = w * i / 4f
            canvas.drawLine(x, 0f, x, h, if (i == 1 || i == 3) edge else line)
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, if (i == 1 || i == 3) edge else line)
        }
    }
}
