package group.sisu.ceilingscan

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative feature-based mosaic for overhead scans. This is NOT a survey-grade
 * orthomosaic. It intentionally stops when alignment fails, rather than fabricating pipes.
 * Uses a winner-takes-one seam mask rather than alpha blending overlapping pipes.
 * Works best with one strip of 2–8 near-nadir photos and >65% overlap.
 */
object MosaicEngine {
    data class StitchResult(val jpeg: ByteArray, val width: Int, val height: Int)

    fun stitch(resolver: ContentResolver, uris: List<Uri>): StitchResult {
        require(uris.size in 2..12) { "برای ترکیب آزمایشی ۲ تا ۱۲ عکس لازم است." }
        check(OpenCVLoader.initLocal()) { "کتابخانه پردازش تصویر راه‌اندازی نشد." }

        val images = mutableListOf<Mat>()
        try {
            uris.forEach { uri ->
                val bmp = readOrientedScaled(resolver, uri, 1300)
                val rgba = Mat()
                Utils.bitmapToMat(bmp, rgba)
                val bgr = Mat()
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                rgba.release()
                bmp.recycle()
                images.add(bgr)
            }
            val transforms = mutableListOf<Mat>()
            transforms.add(Mat.eye(3, 3, CvType.CV_64F))
            for (i in 1 until images.size) {
                val h = matchHomography(images[i - 1], images[i], i)
                val next = Mat()
                Core.gemm(transforms.last(), h, 1.0, Mat(), 0.0, next)
                val s = next.get(2, 2)[0]
                check(abs(s) > 1e-9) { "خطای هندسی در عکس ${i + 1}" }
                Core.multiply(next, Scalar(1.0 / s), next)
                transforms.add(next)
                h.release()
            }
            val corners = mutableListOf<Point>()
            for (i in images.indices) {
                val img = images[i]
                val pts = MatOfPoint2f(
                    Point(0.0, 0.0), Point(img.cols().toDouble(), 0.0),
                    Point(img.cols().toDouble(), img.rows().toDouble()), Point(0.0, img.rows().toDouble())
                )
                val mapped = MatOfPoint2f()
                Core.perspectiveTransform(pts, mapped, transforms[i])
                corners.addAll(mapped.toArray())
                pts.release(); mapped.release()
            }
            val minX = corners.minOf { it.x }
            val minY = corners.minOf { it.y }
            val w = ceil(corners.maxOf { it.x } - minX).toInt()
            val h = ceil(corners.maxOf { it.y } - minY).toInt()
            check(w > 0 && h > 0 && w.toLong() * h < 24_000_000L) {
                "موزاییک برای گوشی خیلی بزرگ است؛ عکس‌ها را در گروه‌های کوچک‌تر ترکیب کن."
            }
            val shift = Mat.eye(3, 3, CvType.CV_64F)
            shift.put(0, 2, -minX)
            shift.put(1, 2, -minY)
            val result = Mat.zeros(h, w, CvType.CV_8UC3)
            // Keep exactly ONE contributing source at each output pixel.
            // A half-transparent overlap can duplicate or blur pipes, so we instead
            // prefer the source with the greatest distance from its warped border.
            val bestWeight = Mat.zeros(h, w, CvType.CV_32FC1)
            val size = Size(w.toDouble(), h.toDouble())
            for (i in images.indices) {
                val dstH = Mat()
                Core.gemm(shift, transforms[i], 1.0, Mat(), 0.0, dstH)
                val warp = Mat.zeros(h, w, CvType.CV_8UC3)
                Imgproc.warpPerspective(images[i], warp, dstH, size, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0))
                val srcMask = Mat(images[i].rows(), images[i].cols(), CvType.CV_8UC1, Scalar(255.0))
                val dstMask = Mat.zeros(h, w, CvType.CV_8UC1)
                Imgproc.warpPerspective(srcMask, dstMask, dstH, size, Imgproc.INTER_NEAREST)

                val distance = Mat()
                Imgproc.distanceTransform(dstMask, distance, Imgproc.DIST_L2, 3)
                val better = Mat()
                Core.compare(distance, bestWeight, better, Core.CMP_GT)
                Core.bitwise_and(better, dstMask, better)
                warp.copyTo(result, better)
                Core.max(bestWeight, distance, bestWeight)

                dstH.release(); warp.release(); srcMask.release(); dstMask.release()
                distance.release(); better.release()
            }
            // Encode directly, avoiding an extra full-size bitmap in memory.
            val jpg = MatOfByte()
            val params = MatOfInt(org.opencv.imgcodecs.Imgcodecs.IMWRITE_JPEG_QUALITY, 94)
            val ok = org.opencv.imgcodecs.Imgcodecs.imencode(".jpg", result, jpg, params)
            check(ok) { "ذخیره تصویر نهایی ناموفق بود." }
            val bytes = jpg.toArray()
            jpg.release(); params.release(); result.release(); bestWeight.release(); shift.release()
            transforms.forEach { it.release() }
            return StitchResult(bytes, w, h)
        } finally {
            images.forEach { it.release() }
        }
    }

    // H maps current image -> previous image.
    private fun matchHomography(previous: Mat, current: Mat, index: Int): Mat {
        val gPrev = Mat(); val gCur = Mat()
        Imgproc.cvtColor(previous, gPrev, Imgproc.COLOR_BGR2GRAY)
        Imgproc.cvtColor(current, gCur, Imgproc.COLOR_BGR2GRAY)
        val orb = ORB.create(5000)
        val kpPrev = MatOfKeyPoint(); val kpCur = MatOfKeyPoint()
        val desPrev = Mat(); val desCur = Mat()
        orb.detectAndCompute(gPrev, Mat(), kpPrev, desPrev)
        orb.detectAndCompute(gCur, Mat(), kpCur, desCur)
        gPrev.release(); gCur.release()
        check(!desPrev.empty() && !desCur.empty()) { "نقاط مشترک عکس ${index} و ${index + 1} کافی نیست." }
        val matches = mutableListOf<MatOfDMatch>()
        val bf = BFMatcher.create(Core.NORM_HAMMING, false)
        bf.knnMatch(desCur, desPrev, matches, 2)
        val good = matches.mapNotNull { m ->
            val pair = m.toArray()
            if (pair.size == 2 && pair[0].distance < 0.75 * pair[1].distance) pair[0] else null
        }
        matches.forEach { it.release() }
        check(good.size >= 18) { "هم‌پوشانی عکس ${index} و ${index + 1} کافی نیست؛ حداقل ۷۰٪ هم‌پوشانی بگیر." }
        val curPts = kpCur.toArray(); val prevPts = kpPrev.toArray()
        val src = MatOfPoint2f(*good.map { curPts[it.queryIdx].pt }.toTypedArray())
        val dst = MatOfPoint2f(*good.map { prevPts[it.trainIdx].pt }.toTypedArray())
        val inlierMask = Mat()
        val h = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 4.0, inlierMask)
        val inliers = if (inlierMask.empty()) 0 else Core.countNonZero(inlierMask)
        src.release(); dst.release(); inlierMask.release()
        kpCur.release(); kpPrev.release(); desCur.release(); desPrev.release()
        check(!h.empty() && inliers >= 14 && inliers >= (good.size * 0.30).toInt()) {
            "اتصال عکس ${index} به ${index + 1} قابل اعتماد نیست؛ خطوط وافل تکراری‌اند یا زاویه زیاد تغییر کرده."
        }
        // Reject extreme warps / wrong matches before creating misleading pipeline maps.
        val x0 = current.cols() / 2.0
        val y0 = current.rows() / 2.0
        val test = MatOfPoint2f(Point(x0, y0), Point(x0 + 100, y0), Point(x0, y0 + 100))
        val mapped = MatOfPoint2f(); Core.perspectiveTransform(test, mapped, h)
        val p = mapped.toArray()
        val dx = Math.hypot(p[1].x - p[0].x, p[1].y - p[0].y) / 100.0
        val dy = Math.hypot(p[2].x - p[0].x, p[2].y - p[0].y) / 100.0
        test.release(); mapped.release()
        check(dx in 0.65..1.55 && dy in 0.65..1.55) { "تغییر مقیاس/پرسپکتیو زیاد است؛ عکس‌ها را مجدداً در یک ارتفاع بگیر." }
        return h
    }

    private fun readOrientedScaled(resolver: ContentResolver, uri: Uri, maxWidth: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        var sample = 1
        while (max(options.outWidth, options.outHeight) / (sample * 2) >= maxWidth) sample *= 2
        val original = resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("امکان خواندن یکی از عکس‌ها نبود")
        val rotate = resolver.openInputStream(uri).use { stream ->
            val exif = ExifInterface(stream!!)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
        val oriented = if (rotate == 0f) original else {
            val m = Matrix().apply { postRotate(rotate) }
            val turned = Bitmap.createBitmap(original, 0, 0, original.width, original.height, m, true)
            original.recycle()
            turned
        }
        val scale = min(1.0f, maxWidth.toFloat() / max(oriented.width, oriented.height))
        if (scale == 1f) return oriented
        val small = Bitmap.createScaledBitmap(oriented, (oriented.width * scale).toInt(), (oriented.height * scale).toInt(), true)
        oriented.recycle()
        return small
    }
}
