package group.sisu.ceilingscan

import android.content.ContentValues
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import kotlin.math.abs

class PlanActivity : AppCompatActivity() {
    private var project: ScanProject? = null
    private lateinit var board: PlanGridView
    private lateinit var summary: TextView
    private lateinit var hint: TextView
    private lateinit var layoutLabel: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var busy = false

    private val pickPlan = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (busy) return@registerForActivityResult
            busy=true
            hint.text="در حال وارد کردن پلان…"
            worker.execute {
                try {
                    val p= ProjectStore.newFromDocument(this,uri)
                    ui.post { busy=false; project=p; loadBoard(); updateUI(); hint.text="برای اصلاح محدوده، دکمه تعیین محدوده را بزن" }
                } catch(e:Exception) {
                    ui.post { busy=false; hint.text="خطای باز کردن پلان: ${e.localizedMessage}" }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=Color.rgb(10, 26, 34)
        project=ProjectStore.load(this)
        createScreen()
        loadBoard()
        updateUI()
    }

    private fun createScreen() {
        val root = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 30, 37))
            layoutDirection=View.LAYOUT_DIRECTION_RTL
        }
        val brandHeader = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 6)
        }
        brandHeader.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_sisu_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "SISU Ceiling Scan logo"
        }, LinearLayout.LayoutParams(72, 72))
        brandHeader.addView(TextView(this).apply {
            text="SISU  |  Ceiling Scan"
            textColor=Color.WHITE; textSize=21f; gravity=Gravity.CENTER_VERTICAL
            setTypeface(null,Typeface.BOLD); setPadding(12,0,12,0)
        })
        root.addView(brandHeader)
        summary=TextView(this).apply {
            textSize=13f; gravity=Gravity.CENTER; setTextColor(Color.rgb(171,229,214))
            setPadding(8,6,8,8)
        }
        root.addView(summary)
        val top = LinearLayout(this).apply {gravity=Gravity.CENTER}
        button(top,"۱. بارگذاری پلان",true) {
            if ((project?.count() ?: 0) == 0) {
                pickPlan.launch(arrayOf("image/*","application/pdf"))
            } else {
                AlertDialog.Builder(this)
                    .setTitle("پلان جدید")
                    .setMessage("پلان جدید، جانمایی جلسه فعلی را از صفحه اصلی جایگزین می‌کند. عکس‌های اصلی در گالری باقی می‌مانند. ادامه می‌دهی؟")
                    .setNegativeButton("انصراف",null)
                    .setPositiveButton("پلان جدید") { _, _ -> pickPlan.launch(arrayOf("image/*","application/pdf")) }
                    .show()
            }
        }
        button(top,"۲. تعیین محدوده",false) {
            if (project==null) return@button toast("اول پلان را وارد کن")
            if ((project?.count() ?: 0) > 0) return@button toast("بعد از ثبت عکس، محدوده مش قابل تغییر نیست")
            board.cropping=true
            hint.text="دو گوشه مقابل محدوده داخلی سقف را روی پلان لمس کن"
        }
        root.addView(top)
        val controls=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        button(controls,"ردیف −",false) { resizeGrid(0,-1) }
        button(controls,"ردیف +",false) { resizeGrid(0,1) }
        layoutLabel=TextView(this).apply {
            textColor=Color.WHITE; textSize=12f; gravity=Gravity.CENTER
        }
        controls.addView(layoutLabel,LinearLayout.LayoutParams(0,52,1f))
        button(controls,"ستون −",false) { resizeGrid(-1,0) }
        button(controls,"ستون +",false) { resizeGrid(1,0) }
        root.addView(controls)
        board=PlanGridView(this)
        board.onUpdated={ p ->
            ProjectStore.save(this,p)
            updateUI()
        }
        board.onHint={ msg -> hint.text=msg }
        root.addView(board,LinearLayout.LayoutParams(-1,0,1f))
        hint=TextView(this).apply {
            text="خانه را لمس کن؛ لمس طولانی = حذف/فعال‌سازی خانه خارج از پلان"
            textSize=12f; gravity=Gravity.CENTER; setTextColor(Color.rgb(218,231,233))
            setPadding(8,7,8,7)
        }
        root.addView(hint)
        val row=LinearLayout(this).apply{gravity=Gravity.CENTER}
        button(row,"۳. شروع عکاسی",true) {
            if(project==null) toast("ابتدا پلان وارد کن")
            else startActivity(Intent(this,MainActivity::class.java))
        }
        button(row,"پیش‌نمایش روی پلان",false) { exportPhotomap() }
        root.addView(row)
        val row2=LinearLayout(this).apply{gravity=Gravity.CENTER}
        button(row2,"اتصال هم‌پوشانی اطراف خانه",false) { stitchNearby() }
        root.addView(row2)
        root.addView(TextView(this).apply {
            text="پیش‌نمایش، جانمایی خانه‌هاست؛ نه ارتوموزاییک دقیق. تصاویر اصلی و شواهد هر خانه حفظ می‌شوند."
            textSize=11f; setTextColor(Color.rgb(155,170,178)); gravity=Gravity.CENTER
            setPadding(12,4,12,14)
        })
        setContentView(root)
    }

    private fun button(parent:LinearLayout,label:String,accent:Boolean,action:()->Unit):Button {
        val btn=Button(this).apply {
            text=label; textSize=11f; isAllCaps=false
            setTextColor(if(accent) Color.rgb(7,29,34) else Color.WHITE)
            background=GradientDrawable().apply {
                cornerRadius=16f
                setColor(if(accent) Color.rgb(102,222,194) else Color.rgb(39,69,80))
            }
            setOnClickListener{action()}
        }
        parent.addView(btn,LinearLayout.LayoutParams(0,52,1f).apply {setMargins(3,3,3,3)})
        return btn
    }

    private fun loadBoard() {
        board.show(if(project!=null) ProjectStore.planBitmap(this) else null,project)
    }

    private fun updateUI() {
        val p=project
        summary.text=if(p==null) "یک پلان JPG، PNG یا PDF وارد کن" else
            "${p.completed()} از ${p.activeCount()} خانه ثبت شد  |  ${p.count()} عکس اصلی"
        layoutLabel.text=if(p==null) "شبکه" else "${p.rows}×${p.columns}"
        board.refresh(p)
    }

    private fun resizeGrid(dx:Int,dy:Int) {
        val p=project ?: return toast("اول پلان وارد کن")
        if(p.count()>0) return toast("برای حفظ مکان عکس‌ها، پس از عکاسی شبکه قابل تغییر نیست")
        p.columns=(p.columns+dx).coerceIn(2,16)
        p.rows=(p.rows+dy).coerceIn(2,25)
        p.disabled.clear()
        p.selected=0
        ProjectStore.save(this,p)
        updateUI()
    }

    private fun exportPhotomap() {
        val p=project ?:return toast("اول پلان وارد کن")
        if(busy) return
        busy=true; hint.text="در حال تولید نمای جانمایی روی پلان…"
        worker.execute {
            try {
                val bytes=PlanPreview.render(this,p)
                val uri=saveImage(bytes,"SISU_PLAN_OVERVIEW")
                ui.post { hint.text="شیت جانمایی در گالری ذخیره شد"; viewImage(uri) }
            }catch(e:Exception){ui.post{hint.text="خطا: ${e.localizedMessage}"}}
            finally{ui.post{busy=false}}
        }
    }

    private fun stitchNearby() {
        val p=project ?:return toast("اول پلان وارد کن")
        if(busy) return
        val selected=p.selected
        val col=selected%p.columns; val row=selected/p.columns
        val relevant=p.images.entries.filter { (cell,_) ->
            abs(cell%p.columns-col)<=1 && abs(cell/p.columns-row)<=1 && cell !in p.disabled
        }.flatMap{it.value}.distinct().sorted() // Capture names use epoch milliseconds.
            .map(Uri::parse)
        if(relevant.size !in 2..12) return toast("این محدوده باید ۲ تا ۱۲ عکس هم‌پوشان داشته باشد؛ الان ${relevant.size} عکس دارد")
        busy=true; hint.text="در حال بررسی تطبیق واقعی عکس‌ها؛ در صورت ابهام اتصال رد می‌شود…"
        worker.execute {
            try {
                val result=MosaicEngine.stitch(contentResolver,relevant)
                val uri=saveImage(result.jpeg,"SISU_GRID_STITCH")
                ui.post {hint.text="ترکیب محلی ساخته شد؛ برای نقشه دقیق باید کنترل شود"; viewImage(uri)}
            } catch(e:Exception) { ui.post {hint.text="هم‌پوشانی تأیید نشد: ${e.localizedMessage}"} }
            finally { ui.post {busy=false} }
        }
    }

    private fun saveImage(bytes:ByteArray,prefix:String):Uri {
        val details=ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,"${prefix}_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/SISU Ceiling Scan/${project?.id ?: "export"}")
            put(MediaStore.Images.Media.IS_PENDING,1)
        }
        val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,details)
            ?: error("ذخیره تصویر ناموفق بود")
        try {
            contentResolver.openOutputStream(uri).use { stream -> requireNotNull(stream).write(bytes) }
            contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
            return uri
        }catch(e:Exception){contentResolver.delete(uri,null,null); throw e}
    }
    private fun viewImage(uri:Uri){
        try { startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri,"image/jpeg");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }) } catch (_:Exception) {toast("خروجی در گالری ذخیره شد")}
    }
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_LONG).show()

    override fun onResume(){
        super.onResume()
        val reloaded=ProjectStore.load(this)
        if (reloaded!=null) {project=reloaded; if(::board.isInitialized) updateUI()}
    }
    override fun onDestroy(){
        worker.shutdown()
        if(::board.isInitialized) board.show(null,null)
        super.onDestroy()
    }
}
