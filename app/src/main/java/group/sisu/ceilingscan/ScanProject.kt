package group.sisu.ceilingscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** The app stores evidence in a grid explicitly linked to the selected architectural plan.
 *  Coordinates here refer to scanning STATIONS, not surveyed physical locations.
 */
data class ScanProject(
    var id: String,
    var columns: Int = 5,
    var rows: Int = 10,
    var left: Float = 0.04f,
    var top: Float = 0.04f,
    var right: Float = 0.96f,
    var bottom: Float = 0.96f,
    var selected: Int = 0,
    val disabled: MutableSet<Int> = mutableSetOf(),
    val images: MutableMap<Int, MutableList<String>> = mutableMapOf()
) {
    val cells: Int get() = columns * rows
    fun count(): Int = images.values.sumOf { it.size }
    fun completed(): Int = images.count { (cell, uris) -> cell !in disabled && uris.isNotEmpty() }
    fun activeCount(): Int = cells - disabled.size
    fun nextCell(after: Int = selected): Int {
        // Serpentine scan: row 0 left-to-right, row 1 right-to-left and so on.
        val order = (0 until rows).flatMap { y ->
            val xs = if (y % 2 == 0) (0 until columns).toList() else (columns - 1 downTo 0).toList()
            xs.map { x -> y * columns + x }
        }.filter { it !in disabled }
        if (order.isEmpty()) return selected
        val pos = order.indexOf(after)
        if (pos < 0) return order.first()
        // Prefer the next unphotographed tile, otherwise advance to next valid tile.
        val cyclic = (1..order.size).map { order[(pos + it) % order.size] }
        return cyclic.firstOrNull { images[it].isNullOrEmpty() } ?: cyclic.first()
    }
}

object ProjectStore {
    private const val PLAN = "sisu_plan_current.png"
    private const val CONFIG = "sisu_project_current.json"

    fun load(ctx: Context): ScanProject? {
        val json = File(ctx.filesDir, CONFIG)
        if (!json.exists() || !File(ctx.filesDir, PLAN).exists()) return null
        return try {
            val root = JSONObject(json.readText(Charsets.UTF_8))
            val project = ScanProject(
                id = root.getString("id"),
                columns = root.optInt("columns", 5).coerceIn(2, 16),
                rows = root.optInt("rows", 10).coerceIn(2, 25),
                left = root.optDouble("left", 0.04).toFloat(),
                top = root.optDouble("top", 0.04).toFloat(),
                right = root.optDouble("right", 0.96).toFloat(),
                bottom = root.optDouble("bottom", 0.96).toFloat(),
                selected = root.optInt("selected", 0)
            )
            val disabled = root.optJSONArray("disabled") ?: JSONArray()
            for (i in 0 until disabled.length()) project.disabled.add(disabled.getInt(i))
            val byCell = root.optJSONObject("images") ?: JSONObject()
            for (key in byCell.keys()) {
                val index = key.toIntOrNull() ?: continue
                val urls = byCell.optJSONArray(key) ?: continue
                project.images[index] = (0 until urls.length()).map { urls.getString(it) }.toMutableList()
            }
            project.selected = project.selected.coerceIn(0, project.cells - 1)
            project
        } catch (_: Exception) { null }
    }

    fun save(ctx: Context, p: ScanProject) {
        val j = JSONObject().apply {
            put("version", 2); put("id", p.id)
            put("columns", p.columns); put("rows", p.rows)
            put("left", p.left.toDouble()); put("top", p.top.toDouble())
            put("right", p.right.toDouble()); put("bottom", p.bottom.toDouble())
            put("selected", p.selected)
            put("disabled", JSONArray(p.disabled.sorted()))
            put("images", JSONObject().apply {
                p.images.forEach { (cell, refs) -> put(cell.toString(), JSONArray(refs)) }
            })
        }
        val dest = File(ctx.filesDir, CONFIG)
        val tmp = File(ctx.filesDir, "${CONFIG}.new")
        tmp.writeText(j.toString(2), Charsets.UTF_8)
        check(tmp.renameTo(dest) || tmp.copyTo(dest, overwrite = true).exists())
        if (tmp.exists()) tmp.delete()
    }

    fun newFromDocument(ctx: Context, uri: Uri): ScanProject {
        val bmp = decodePlan(ctx, uri)
        try {
            FileOutputStream(File(ctx.filesDir, PLAN)).use { output ->
                check(bmp.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally { bmp.recycle() }
        val p = ScanProject(SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
        save(ctx, p)
        return p
    }

    fun planBitmap(ctx: Context): Bitmap? = BitmapFactory.decodeFile(File(ctx.filesDir, PLAN).absolutePath)

    fun captureIntoSelected(ctx: Context, uri: Uri): ScanProject? {
        val p = load(ctx) ?: return null
        if (p.selected in p.disabled) return null
        val target = p.selected
        p.images.getOrPut(target) { mutableListOf() }.add(uri.toString())
        p.selected = p.nextCell(target)
        save(ctx, p)
        return p
    }

    private fun decodePlan(ctx: Context, uri: Uri): Bitmap {
        val type = ctx.contentResolver.getType(uri).orEmpty()
        if (type == "application/pdf" || uri.lastPathSegment.orEmpty().endsWith(".pdf", true)) {
            // PdfRenderer requires a seekable descriptor; copying to cache works with cloud providers.
            val tmp = File(ctx.cacheDir, "plan_import.pdf")
            ctx.contentResolver.openInputStream(uri).use { src ->
                requireNotNull(src) { "فایل PDF قابل خواندن نیست" }
                tmp.outputStream().use { out -> src.copyTo(out) }
            }
            try {
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { pdf ->
                        require(pdf.pageCount > 0) { "PDF صفحه‌ای ندارد" }
                        pdf.openPage(0).use { page ->
                            val factor = min(1.0, 1900.0 / max(page.width, page.height))
                            val bmp = Bitmap.createBitmap(max(1,(page.width * factor).toInt()),
                                max(1,(page.height * factor).toInt()), Bitmap.Config.ARGB_8888)
                            Canvas(bmp).drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            return bmp
                        }
                    }
                }
            } finally { tmp.delete() }
        }
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "فقط پلان JPG، PNG یا PDF پشتیبانی می‌شود" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2300) sample *= 2
        val result = resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        return requireNotNull(result) { "تصویر پلان خوانده نشد" }
    }
}

/** Plan-linked preview; not an optical ortho-mosaic or a dimensionally calibrated drawing. */
object PlanPreview {
    fun render(ctx: Context, p: ScanProject): ByteArray {
        val base = ProjectStore.planBitmap(ctx) ?: error("پلان در دسترس نیست")
        val s = min(2.4f, 2600f / max(base.width, base.height))
        val w = (base.width * s).toInt().coerceAtLeast(1)
        val h = (base.height * s).toInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(base, null, Rect(0, 0, w, h), paint)
        val x0 = p.left * w; val x1 = p.right * w
        val y0 = p.top * h; val y1 = p.bottom * h
        val cw = (x1 - x0) / p.columns; val ch = (y1 - y0) / p.rows
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(68, 210, 195); strokeWidth = max(1.3f, w / 900f)
            style = Paint.Style.STROKE
        }
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = max(12f, w / 48f)
            setShadowLayer(2.5f, 1f, 1f, Color.BLACK)
        }
        for (i in 0 until p.cells) {
            val col = i % p.columns; val row = i / p.columns
            val r = RectF(x0 + cw * col, y0 + ch * row, x0 + cw * (col + 1), y0 + ch * (row + 1))
            if (i in p.disabled) {
                canvas.drawRect(r, Paint().apply { color = Color.argb(140, 90, 90, 90) })
                continue
            }
            val uri = p.images[i]?.lastOrNull()
            if (uri != null) {
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                    val image = ctx.contentResolver.openInputStream(Uri.parse(uri)).use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                    if (image != null) {
                        val dst = RectF(r.left+2, r.top+2, r.right-2, r.bottom-2)
                        val ratio = max(dst.width()/image.width, dst.height()/image.height)
                        val sw = (dst.width()/ratio).toInt().coerceAtLeast(1)
                        val sh = (dst.height()/ratio).toInt().coerceAtLeast(1)
                        val src = Rect((image.width-sw)/2, (image.height-sh)/2,
                            (image.width+sw)/2, (image.height+sh)/2)
                        canvas.drawBitmap(image, src, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 195 })
                        image.recycle()
                    }
                } catch (_: Exception) { /* unresolved evidence stays as an empty cell */ }
            }
            canvas.drawRect(r, line)
            canvas.drawText("${row+1}-${col+1}", r.left + 5, r.top + mark.textSize + 3, mark)
            val n = p.images[i]?.size ?: 0
            if (n > 0) canvas.drawText("×$n", r.left + 5, r.bottom - 4, mark)
        }
        val data = java.io.ByteArrayOutputStream()
        check(output.compress(Bitmap.CompressFormat.JPEG, 92, data))
        output.recycle(); base.recycle()
        return data.toByteArray()
    }
}
