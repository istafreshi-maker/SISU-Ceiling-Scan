package group.sisu.ceilingscan

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var previewView: PreviewView
    private lateinit var guides: GuideOverlay
    private lateinit var status: TextView
    private lateinit var level: TextView
    private lateinit var lensButton: Button
    private lateinit var countText: TextView
    private lateinit var cellText: TextView
    private var planProject: ScanProject? = null
    private lateinit var snapButton: Button
    private lateinit var stitchButton: Button
    private lateinit var sensorManager: SensorManager
    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val photos = mutableListOf<Uri>()
    private lateinit var folder: String
    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null
    private var boundCamera: Camera? = null
    private var widestInfo: CameraInfo? = null
    private var useWide = true
    private var inProgress = false
    private var ghostEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(14, 29, 37)
        window.navigationBarColor = Color.rgb(14, 29, 37)
        planProject = ProjectStore.load(this)
        folder = planProject?.id ?: newFolder()
        planProject?.images?.values?.flatten()?.map(Uri::parse)?.let { photos.addAll(it) }
        buildScreen()
        updateCounter()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initializeCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 700)
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 30, 37))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val title = TextView(this).apply {
            text = "SISU  |  Ceiling Scan"
            textSize = 22f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(10, dp(16), 10, dp(7))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_sisu_launcher)
            contentDescription = "SISU Ceiling Scan logo"
        }, LinearLayout.LayoutParams(dp(50), dp(50)))
        header.addView(title)
        root.addView(header)
        val description = TextView(this).apply {
            text = "عکاسی هدایت‌شده با پلان • هم‌پوشانی هدف ۷۵٪"
            textSize = 13f; setTextColor(Color.rgb(173, 208, 216))
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), dp(10))
        }
        root.addView(description)

        val cameraPanel = FrameLayout(this)
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        cameraPanel.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        guides = GuideOverlay(this)
        cameraPanel.addView(guides, FrameLayout.LayoutParams(-1, -1))
        level = TextView(this).apply {
            text = "تراز گوشی: در انتظار حسگر"
            textSize = 12f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(180, 9, 35, 42))
            setPadding(dp(9), dp(6), dp(9), dp(6))
        }
        val levelLP = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(12)
        }
        cameraPanel.addView(level, levelLP)
        root.addView(cameraPanel, LinearLayout.LayoutParams(-1, 0, 1f))

        status = TextView(this).apply {
            text = "دوربین در حال راه‌اندازی است…"
            textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        root.addView(status)
        countText = TextView(this).apply {
            text = "۰ تصویر • هر بار گوشی را حدود یک‌چهارم عرض کادر جابه‌جا کن"
            textSize = 13f; setTextColor(Color.rgb(166, 215, 208)); gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), dp(8))
        }
        root.addView(countText)
        cellText = TextView(this).apply {
            textSize = 15f; setTypeface(null,Typeface.BOLD)
            setTextColor(Color.rgb(255,208,125)); gravity = Gravity.CENTER
            setPadding(dp(10),dp(4),dp(10),dp(7))
        }
        root.addView(cellText)

        val controls1 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        lensButton = addBtn(controls1, "لنز عریض", true) { useWide = !useWide; bindCamera() }
        addBtn(controls1, "نمای قبلی", false) {
            ghostEnabled = !ghostEnabled
            guides.showGhost = ghostEnabled
            toast(if (ghostEnabled) "نمای شفاف عکس قبل روشن شد" else "نمای شفاف عکس قبل خاموش شد")
        }
        addBtn(controls1, "خانه بعدی", false) {
            val p=ProjectStore.load(this) ?:return@addBtn toast("ابتدا روی پلان خانه را انتخاب کن")
            p.selected=p.nextCell(p.selected)
            ProjectStore.save(this,p)
            planProject=p
            updateCounter()
        }
        root.addView(controls1)
        val controls2 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        addBtn(controls2, "بازگشت به پلان", false) {
            if (!inProgress) startActivity(Intent(this, PlanActivity::class.java))
        }
        snapButton = addBtn(controls2, "● عکس بگیر", true) { takePhoto() }
        stitchButton = addBtn(controls2, "ترکیب عکس‌ها", false) { makeMosaic() }
        root.addView(controls2)
        val foot = TextView(this).apply {
            text = "عکس‌های اصلی با شناسه خانه روی پلان در گالری ذخیره می‌شوند.\nبعد از هر عکس، خانه بعدی پیشنهاد می‌شود. جانمایی خودکار هنوز مکانیابی مستقل نیست."
            textSize = 11f; setTextColor(Color.rgb(164, 175, 186)); gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(14))
        }
        root.addView(foot)
        setContentView(root)
    }

    private fun addBtn(row: LinearLayout, label: String, accent: Boolean, onClick: () -> Unit): Button {
        val btn = Button(this).apply {
            text = label; textSize = 12f; isAllCaps = false
            setTextColor(if (accent) Color.rgb(5, 27, 30) else Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (accent) Color.rgb(91, 224, 194) else Color.rgb(35, 66, 79))
            }
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        row.addView(btn, lp)
        return btn
    }

    private fun initializeCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                val backs = provider!!.availableCameraInfos.filter {
                    it.lensFacing == CameraSelector.LENS_FACING_BACK
                }
                // Among publicly exposed CameraX lenses, look for the smallest native magnification.
                widestInfo = backs.minByOrNull { info ->
                    try { info.intrinsicZoomRatio } catch (_: Exception) { 1f }
                }
                bindCamera()
            } catch (e: Exception) {
                status.text = "خطای راه‌اندازی دوربین: ${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val p = provider ?: return
        val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        val selector = if (useWide && widestInfo != null) {
            val targetId = Camera2CameraInfo.from(widestInfo!!).cameraId
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .addCameraFilter { infos ->
                    infos.filter { info -> Camera2CameraInfo.from(info).cameraId == targetId }
                }.build()
        } else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            p.unbindAll()
            boundCamera = p.bindToLifecycle(this, selector, preview, capture)
            if (useWide) {
                // Some A35 camera stacks expose 0.5x only as part of logical zoom.
                val zoom = boundCamera!!.cameraInfo.zoomState.value
                val minZoom = zoom?.minZoomRatio ?: 1f
                if (minZoom < 1f) boundCamera!!.cameraControl.setZoomRatio(minZoom)
            }
            val z = boundCamera!!.cameraInfo.zoomState.value
            val min = z?.minZoomRatio ?: 1f
            status.text = if (useWide) {
                if (min < 1f) "حالت عریض فعال • کوچک‌ترین زوم قابل دسترس: ${"%.2f".format(min)}×"
                else "عریض‌ترین لنزِ قابل‌دسترسی فعال شد (ممکن است فوق‌عریض سامسونگ مخفی باشد)"
            } else "حالت لنز پیش‌فرض فعال"
            lensButton.text = if (useWide) "لنز: عریض" else "لنز: اصلی"
        } catch (e: Exception) {
            if (useWide) {
                useWide = false
                bindCamera()
            } else {
                status.text = "دسترسی به دوربین ممکن نشد: ${e.localizedMessage}"
            }
        }
    }

    private fun takePhoto() {
        if (inProgress) return
        val imageCapture = capture ?: return toast("دوربین آماده نیست")
        inProgress = true; snapButton.isEnabled = false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SISU_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SISU Ceiling Scan/$folder")
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        imageCapture.takePicture(output, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                result.savedUri?.let { uri ->
                    photos.add(uri)
                    if (ProjectStore.load(this@MainActivity) != null) {
                        planProject=ProjectStore.captureIntoSelected(this@MainActivity, uri)
                    }
                    bg.execute {
                        val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                        val thumb = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
                        main.post { guides.ghost = thumb }
                    }
                }
                inProgress = false; snapButton.isEnabled = true
                updateCounter()
                status.text = "عکس در خانه انتخاب‌شده ثبت شد. با هم‌پوشانی حدود ۷۵٪ به خانه پیشنهادی بعدی برو."
            }
            override fun onError(exception: ImageCaptureException) {
                inProgress = false; snapButton.isEnabled = true
                status.text = "خطا در ثبت عکس: ${exception.localizedMessage}"
            }
        })
    }

    private fun updateCounter() {
        val p=planProject
        countText.text = "${photos.size} عکس ثبت شد • ترکیب آزمایشی ۲ تا ۱۲ عکس"
        if (::cellText.isInitialized) {
            cellText.text = if(p==null) "پلان انتخاب نشده است" else {
                val x=p.selected%p.columns+1
                val y=p.selected/p.columns+1
                "خانه بعدی: ردیف $y، ستون $x  |  ${p.completed()} / ${p.activeCount()} خانه تکمیل"
            }
        }
    }

    private fun makeMosaic() {
        if (inProgress) return
        if (photos.size !in 2..12) return toast("۲ تا ۱۲ عکس بگیر؛ برای بخش‌های بزرگ چند جلسه بساز.")
        inProgress = true
        stitchButton.isEnabled = false; snapButton.isEnabled = false
        status.text = "در حال بررسی نقاط مشترک و اتصال محافظه‌کارانه تصاویر…"
        val snapshot = photos.toList()
        bg.execute {
            try {
                val result = MosaicEngine.stitch(contentResolver, snapshot)
                val uri = saveJpeg(result.jpeg)
                main.post {
                    status.text = "موزاییک ${result.width}×${result.height} ساخته شد. برای مشاهده باز می‌شود."
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "image/jpeg")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (_: Exception) { toast("خروجی در گالری ذخیره شد.") }
                }
            } catch (e: Exception) {
                main.post { status.text = "ترکیب متوقف شد: ${e.localizedMessage}. عکس‌های اصلی محفوظ‌اند." }
            } finally {
                main.post { inProgress = false; stitchButton.isEnabled = true; snapButton.isEnabled = true }
            }
        }
    }

    private fun saveJpeg(bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SISU_MOSAIC_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SISU Ceiling Scan/$folder")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("ذخیره در گالری ممکن نشد")
        try {
            contentResolver.openOutputStream(uri).use { stream ->
                checkNotNull(stream) { "امکان باز کردن فایل نبود" }
                stream.write(bytes)
            }
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
            return uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun newFolder(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + .5f).toInt()

    override fun onResume() {
        super.onResume()
        val current = ProjectStore.load(this)
        if (current?.id != planProject?.id) {
            folder = current?.id ?: newFolder()
            photos.clear()
            current?.images?.values?.flatten()?.map(Uri::parse)?.let { photos.addAll(it) }
            guides.ghost = null
        }
        planProject=current
        if (::cellText.isInitialized) updateCounter()
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }
    override fun onPause() { sensorManager.unregisterListener(this); super.onPause() }
    override fun onDestroy() { bg.shutdown(); super.onDestroy() }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]; val y = event.values[1]
        val flat = abs(x) < 1.1 && abs(y) < 1.1
        level.text = if (flat) "✓ گوشی تقریباً موازی سقف است" else "گوشی را موازی سقف نگه دار (شیب: ${"%.1f".format(Math.hypot(x.toDouble(), y.toDouble()))})"
        level.setTextColor(if (flat) Color.rgb(137, 255, 196) else Color.rgb(255, 220, 133))
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 700 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) initializeCamera()
        else status.text = "برای عکاسی، اجازه دوربین را فعال کن."
    }
}
