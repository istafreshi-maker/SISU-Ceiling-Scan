package group.sisu.ceilingscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Interactive plan board. Tap = choose next scan station; long press = exclude a station.
 *  Crop mode = tap the two diagonally opposite corners of the interior scanning footprint.
 */
class PlanGridView(context: Context) : View(context) {
    var project: ScanProject? = null
        private set
    private var plan: Bitmap? = null
    private val photoRect = RectF()
    private var cropFirst: PointF? = null
    var cropping = false
        set(value) { field = value; cropFirst = null; invalidate() }
    var onUpdated: ((ScanProject) -> Unit)? = null
    var onHint: ((String) -> Unit)? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(94, 225, 197); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val selectedLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 197, 95); style = Paint.Style.STROKE; strokeWidth = 5f
    }
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f }

    private val detector = GestureDetector(context, object: GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { handleTap(e.x, e.y); return true }
        override fun onLongPress(e: MotionEvent) {
            if (cropping) return
            val p = project ?: return
            val c = indexAt(e.x, e.y) ?: return
            if (c in p.disabled) p.disabled.remove(c) else p.disabled.add(c)
            if (p.selected in p.disabled) p.selected = p.nextCell(p.selected)
            onUpdated?.invoke(p)
            onHint?.invoke("خانه ${c / p.columns + 1}-${c % p.columns + 1} ${if (c in p.disabled) "حذف شد" else "فعال شد"}")
            invalidate()
        }
    })

    fun show(bitmap: Bitmap?, nextProject: ScanProject?) {
        if (plan !== bitmap) plan?.recycle()
        plan = bitmap
        project = nextProject
        invalidate()
    }
    fun refresh(p: ScanProject?) { project = p; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(10, 23, 29))
        val bmp = plan ?: return
        val p = project ?: return
        val margin = 8f
        val s = min((width-2*margin)/bmp.width, (height-2*margin)/bmp.height)
        val realW = bmp.width * s; val realH = bmp.height * s
        photoRect.set((width-realW)/2f, (height-realH)/2f,
            (width+realW)/2f, (height+realH)/2f)
        canvas.drawBitmap(bmp, null, photoRect, Paint(Paint.FILTER_BITMAP_FLAG))
        val x0 = photoRect.left + p.left*photoRect.width()
        val y0 = photoRect.top + p.top*photoRect.height()
        val dx = (p.right-p.left)*photoRect.width()/p.columns
        val dy = (p.bottom-p.top)*photoRect.height()/p.rows
        tag.textSize = max(11f, min(dx,dy)*0.22f)
        for (index in 0 until p.cells) {
            val col = index % p.columns; val row = index / p.columns
            val rect = RectF(x0 + col*dx, y0 + row*dy, x0 +(col+1)*dx, y0+(row+1)*dy)
            if (index in p.disabled) {
                canvas.drawRect(rect, Paint().apply { color = Color.argb(130, 80, 80, 80) })
            } else if (p.images[index].orEmpty().isNotEmpty()) {
                canvas.drawRect(rect, Paint().apply { color = Color.argb(100, 17, 173, 159) })
            }
            canvas.drawRect(rect, if (index == p.selected && index !in p.disabled) selectedLine else line)
            if (index !in p.disabled && dx > 25f) {
                canvas.drawText("${row+1}-${col+1}", rect.left + 3, rect.top+tag.textSize+2, tag)
                val count = p.images[index]?.size ?: 0
                if (count > 0) canvas.drawText("$count ✓", rect.left+3, rect.bottom-4, tag)
            }
        }
        cropFirst?.let {
            val xx = photoRect.left + it.x*photoRect.width()
            val yy = photoRect.top + it.y*photoRect.height()
            canvas.drawCircle(xx, yy, 9f, selectedLine)
        }
    }

    private fun indexAt(x:Float, y:Float):Int? {
        val p = project ?: return null
        if (photoRect.width() <= 0 || photoRect.height() <= 0) return null
        val u = (x-photoRect.left)/photoRect.width()
        val v = (y-photoRect.top)/photoRect.height()
        if (u !in p.left..p.right || v !in p.top..p.bottom) return null
        val col = ((u-p.left)/(p.right-p.left)*p.columns).toInt().coerceIn(0,p.columns-1)
        val row = ((v-p.top)/(p.bottom-p.top)*p.rows).toInt().coerceIn(0,p.rows-1)
        return row*p.columns + col
    }

    private fun handleTap(x: Float, y: Float) {
        val p = project ?: return
        if (cropping) {
            if (!photoRect.contains(x,y)) return
            val u = ((x-photoRect.left)/photoRect.width()).coerceIn(0.0f,1.0f)
            val v = ((y-photoRect.top)/photoRect.height()).coerceIn(0.0f,1.0f)
            val first = cropFirst
            if (first == null) {
                cropFirst = PointF(u,v)
                onHint?.invoke("حالا گوشه مقابل محدوده عکاسی را انتخاب کن")
            } else {
                val l = min(first.x,u); val r = max(first.x,u)
                val t = min(first.y,v); val b = max(first.y,v)
                if (r-l < .10f || b-t < .10f) {
                    onHint?.invoke("محدوده بسیار کوچک است؛ دوباره گوشه دوم را انتخاب کن")
                    return
                }
                p.left=l; p.right=r; p.top=t; p.bottom=b
                cropping=false
                cropFirst=null
                onUpdated?.invoke(p)
                onHint?.invoke("محدوده جدید شبکه ثبت شد")
            }
            invalidate()
            return
        }
        val index = indexAt(x,y) ?: return
        if (index in p.disabled) {
            onHint?.invoke("این خانه غیرفعال است؛ با لمس طولانی فعالش کن")
            return
        }
        p.selected=index
        onUpdated?.invoke(p)
        onHint?.invoke("خانه ${index/p.columns+1}-${index%p.columns+1} برای عکس بعدی انتخاب شد")
        invalidate()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        return true
    }
}
