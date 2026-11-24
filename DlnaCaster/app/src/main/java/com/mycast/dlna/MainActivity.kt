package com.mycast.dlna

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.content.res.ColorStateList
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val ioExec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var spinner: Spinner
    private lateinit var btnSearch: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var txtVideo: TextView
    private lateinit var txtPickHint: TextView
    private lateinit var txtNowTitle: TextView
    private lateinit var txtCur: TextView
    private lateinit var txtTotal: TextView
    private lateinit var seekPos: SeekBar
    private lateinit var btnVolDown: View
    private lateinit var btnVolUp: View
    private lateinit var txtVol: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var rowPrep: View
    private lateinit var txtPrep: TextView
    private lateinit var progPrep: ProgressBar
    private lateinit var btnPrepCancel: View
    private lateinit var btnQuality: TextView
    private lateinit var tileShake: View
    private lateinit var tilePhone: View
    private lateinit var tileVolStop: View
    private lateinit var icVolStop: ImageView
    private lateinit var txtVolStopState: TextView
    private lateinit var icShake: ImageView
    private lateinit var icPhone: ImageView
    private lateinit var txtShakeState: TextView
    private lateinit var txtPhoneState: TextView
    private lateinit var chipLive: View
    private lateinit var rowVolume: View
    private lateinit var cardPlayer: View
    private lateinit var btnSleep: TextView
    private lateinit var txtStatus: TextView
    private lateinit var dotStatus: View
    private lateinit var adapter: ArrayAdapter<Renderer>

    private val renderers = mutableListOf<Renderer>()
    private var controller: Controller? = null

    private var videoUri: Uri? = null
    private var videoName = ""
    private var videoKey: String? = null

    private var userSeeking = false

    private val prefs get() = CastSession.prefs

    private val sessionListener = object : CastSession.Listener {
        override fun onState() = render()
        override fun onProgress() = renderProgress()
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // ليس كل المزودين يدعمون ذلك، ويكفي الإذن المؤقت
            }
            setVideo(uri)
            onCastClicked() // الاختيار يبدأ البث مباشرة (مع عرض المتابعة إن وُجد موضع محفوظ)
        }
    }

    // عنصر اختاره المستخدم من سجل المشاهدة
    private val historyPick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val id = res.data?.getStringExtra(HistoryActivity.EXTRA_ID)
        if (res.resultCode == RESULT_OK && id != null) History.get(id)?.let { resumeHistory(it) }
    }

    // إذن الإشعارات، وبعد الرد عليه ننتقل لطلب استثناء البطارية
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        askBatteryExemption()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        CastSession.init(this)

        spinner = findViewById(R.id.spinnerDevice)
        btnSearch = findViewById(R.id.btnSearch)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        txtVideo = findViewById(R.id.txtVideo)
        txtPickHint = findViewById(R.id.txtPickHint)
        txtNowTitle = findViewById(R.id.txtNowTitle)
        txtCur = findViewById(R.id.txtCur)
        txtTotal = findViewById(R.id.txtTotal)
        seekPos = findViewById(R.id.seekPos)
        btnVolDown = findViewById(R.id.btnVolDown)
        btnVolUp = findViewById(R.id.btnVolUp)
        txtVol = findViewById(R.id.txtVol)
        btnSpeed = findViewById(R.id.btnSpeed)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        rowPrep = findViewById(R.id.rowPrep)
        txtPrep = findViewById(R.id.txtPrep)
        progPrep = findViewById(R.id.progPrep)
        btnPrepCancel = findViewById(R.id.btnPrepCancel)
        btnQuality = findViewById(R.id.btnQuality)
        tileShake = findViewById(R.id.tileShake)
        tilePhone = findViewById(R.id.tilePhone)
        tileVolStop = findViewById(R.id.tileVolStop)
        icVolStop = findViewById(R.id.icVolStop)
        txtVolStopState = findViewById(R.id.txtVolStopState)
        icShake = findViewById(R.id.icShake)
        icPhone = findViewById(R.id.icPhone)
        txtShakeState = findViewById(R.id.txtShakeState)
        txtPhoneState = findViewById(R.id.txtPhoneState)
        chipLive = findViewById(R.id.chipLive)
        rowVolume = findViewById(R.id.rowVolume)
        cardPlayer = findViewById(R.id.cardPlayer)
        btnSleep = findViewById(R.id.btnSleep)
        txtStatus = findViewById(R.id.txtStatus)
        dotStatus = findViewById(R.id.dotStatus)

        adapter = ArrayAdapter(this, R.layout.item_device, renderers)
        adapter.setDropDownViewResource(R.layout.item_device_dropdown)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos in renderers.indices) selectRenderer(renderers[pos])
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnSearch.setOnClickListener { search() }
        // الصف الأول: المتصفح | ملفات الهاتف
        findViewById<View>(R.id.btnBrowser).setOnClickListener {
            startActivity(Intent(this, BrowserActivity::class.java))
        }
        findViewById<View>(R.id.btnPickFile).setOnClickListener {
            if (controller == null) {
                toast("اختر جهاز العرض أولًا")
            } else {
                pickVideo.launch(arrayOf("video/*"))
            }
        }
        // صف المفاتيح تحت منطقة التحكم: الهزّ (ضغط مطوّل = الحساسية) | صوت الهاتف | أزرار الصوت
        tileShake.setOnClickListener {
            val on = !CastSession.shakeExit
            CastSession.setShakeExit(on)
            toast(if (on) "الإيقاف بالهزّ مفعّل: هزّ الهاتف هزّة قوية أثناء البث لإيقافه (اضغط مطوّلًا لضبط الحساسية)" else "تم إيقاف الهزّ")
        }
        tileShake.setOnLongClickListener { showSensDialog(); true }
        tilePhone.setOnClickListener {
            val on = !CastSession.phoneAudioWanted
            CastSession.setPhoneAudio(on)
            toast(if (on) "الصوت سيصل إلى الهاتف أثناء البث" else "الصوت عاد إلى التلفاز")
        }
        tileVolStop.setOnClickListener {
            val on = !CastSession.volStop
            CastSession.setVolStop(on)
            toast(if (on) "الإيقاف بزر الصوت مفعّل: اضغط أي زر صوت في الهاتف أثناء البث لإيقافه" else "تم إيقاف الإيقاف بزر الصوت: أزرار الصوت ترجع لتغيير صوت التلفاز")
        }
        // الترويسة
        findViewById<View>(R.id.btnExit).setOnClickListener { confirmExit() }
        findViewById<View>(R.id.btnHistory).setOnClickListener {
            historyPick.launch(Intent(this, HistoryActivity::class.java))
        }
        // الشريط السفلي: DLNA = الصفحة الحالية، والريموت بلا وظيفة حاليًا
        findViewById<View>(R.id.tabDlna).setOnClickListener {
            findViewById<android.widget.ScrollView>(R.id.scrollMain).smoothScrollTo(0, 0)
        }
        btnSleep.setOnClickListener { showSleepDialog() }

        btnPlayPause.setOnClickListener { CastSession.togglePlay() }
        findViewById<View>(R.id.btnStop).setOnClickListener { CastSession.stop() }
        findViewById<View>(R.id.btnBack).setOnClickListener { CastSession.seekBy(-10) }
        findViewById<View>(R.id.btnFwd).setOnClickListener { CastSession.seekBy(10) }

        seekPos.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) txtCur.text = fmtTime(p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                userSeeking = false
                CastSession.seek(s?.progress ?: 0)
            }
        })

        holdRepeat(btnVolDown, -1)
        holdRepeat(btnVolUp, 1)
        btnPrepCancel.setOnClickListener { CastSession.cancelPrepare() }
        btnQuality.setOnClickListener { showQualityDialog() }
        btnSpeed.setOnClickListener { SpeedDialog.show(this) }

        startPermissionFlow()

        search()
    }

    override fun onStart() {
        super.onStart()
        CastSession.addListener(sessionListener)
        render()
    }

    override fun onStop() {
        CastSession.removeListener(sessionListener)
        super.onStop()
    }

    // ───────────── الأذونات عند أول تشغيل ─────────────

    /** الإشعارات أولًا، ثم السماح بالعمل في الخلفية بدون تحسين البطارية. */
    private fun startPermissionFlow() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askBatteryExemption()
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun askBatteryExemption() {
        if (isFinishing || isDestroyed) return
        if (isIgnoringBatteryOptimizations()) return
        if (prefs.getBoolean("bg_asked", false)) return // نسأل مرة واحدة فقط
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("العمل في الخلفية")
            .setMessage(
                "حتى لا يتوقف البث عند إغلاق الشاشة أو الخروج من التطبيق، اسمح للتطبيق " +
                    "بالعمل في الخلفية بدون قيود تحسين البطارية."
            )
            .setCancelable(false)
            .setPositiveButton("السماح") { _, _ ->
                prefs.edit().putBoolean("bg_asked", true).apply()
                requestIgnoreBatteryOptimizations()
            }
            .setNegativeButton("لاحقًا") { _, _ ->
                prefs.edit().putBoolean("bg_asked", true).apply()
            }
            .create()
        dlg.show()
        dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            // بعض الأجهزة لا تدعم النافذة المباشرة، فنفتح صفحة إعدادات البطارية العامة
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast("افتح إعدادات البطارية واستثنِ التطبيق يدويًا")
            }
        }
    }

    // ───────────── مساعدات ─────────────

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrEmpty()) return n
                }
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment ?: "video"
    }

    private fun sizeOf(uri: Uri): Long {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        } catch (e: Exception) {
        }
        return -1L
    }

    private fun sensLabel(v: Int): String {
        val label = when {
            v >= 70 -> "عالية"
            v >= 35 -> "متوسطة"
            else -> "منخفضة"
        }
        return "حساسية الهزّ: $label · $v%"
    }

    /** ضبط حساسية الهزّ (ضغط مطوّل على زر الهاتف المهتز). */
    private fun showSensDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_sens, null)
        val tv = v.findViewById<TextView>(R.id.txtSens)
        val sl = v.findViewById<Slider>(R.id.sliderSens)
        sl.value = (prefs.getInt("shake_sens", 50).coerceIn(0, 100) / 5 * 5).toFloat()
        tv.text = sensLabel(sl.value.toInt())
        sl.addOnChangeListener { _, value, fromUser ->
            tv.text = sensLabel(value.toInt())
            if (fromUser) prefs.edit().putInt("shake_sens", value.toInt()).apply()
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("حساسية الإيقاف بالهزّ")
            .setView(v)
            .setPositiveButton("تم", null)
            .create()
        dlg.show()
        dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    // ───────────── الفيديو ─────────────

    private fun setVideo(uri: Uri) {
        videoUri = uri
        videoName = displayName(uri)
        videoKey = CastSession.progressKey(videoName, sizeOf(uri))
        prefs.edit().putString("last_video", uri.toString()).putString("last_video_name", videoName).apply()
        txtPickHint.text = videoName
    }

    /** يستأنف عنصرًا من سجل المشاهدة: المحلي من الدقيقة نفسها، والموقع من الرابط نفسه. */
    private fun resumeHistory(h: History.Item) {
        if (controller == null) {
            toast("اختر جهاز العرض أولًا")
            return
        }
        if (h.type == History.WEB) {
            startActivity(
                Intent(this, BrowserActivity::class.java)
                    .putExtra(BrowserActivity.EXTRA_RESUME, true)
                    .putExtra(BrowserActivity.EXTRA_PAGE, h.page)
                    .putExtra(BrowserActivity.EXTRA_TITLE, h.title)
                    .putExtra(BrowserActivity.EXTRA_HEIGHT, h.height)
                    .putExtra(BrowserActivity.EXTRA_POS, if (h.done) 0 else h.pos)
                    .putExtra(BrowserActivity.EXTRA_DUR, h.dur)
            )
            return
        }
        // فيديو من الهاتف: قد يكون الملف حُذف أو نُقل أو سقط إذنه
        val uri = try { Uri.parse(h.uri) } catch (e: Exception) { null }
        val ok = uri != null && try {
            contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
        if (!ok || uri == null) {
            toast("تعذّر فتح الفيديو، ربما حُذف أو نُقل من الهاتف")
            return
        }
        setVideo(uri)
        startCast(h.resumeAt)
    }

    // ───────────── اكتشاف الأجهزة ─────────────

    private fun selectRenderer(r: Renderer) {
        val c = Controller(r)
        controller = c
        CastSession.selected = c
        prefs.edit().putString("last", r.location).apply()
        render()
        ioExec.execute {
            val v = c.getVolume()
            if (v != null) ui.post { CastSession.setKnownVolume(v) }
        }
    }

    private fun search() {
        btnSearch.isEnabled = false
        ioExec.execute {
            val found = Discovery.search(this)
            ui.post {
                btnSearch.isEnabled = true
                renderers.clear()
                renderers.addAll(found)
                adapter.notifyDataSetChanged()
                if (found.isEmpty()) {
                    controller = null
                    CastSession.selected = null
                    render()
                    toast("لم يُعثر على أجهزة. تأكد أن الهاتف والتلفاز على نفس الشبكة")
                } else {
                    val last = prefs.getString("last", null)
                    val idx = found.indexOfFirst { it.location == last }.coerceAtLeast(0)
                    spinner.setSelection(idx)
                    selectRenderer(found[idx])
                }
            }
        }
    }

    // ───────────── البث ─────────────

    private fun onCastClicked() {
        if (controller == null) {
            toast("اختر جهاز العرض أولًا")
            return
        }
        if (videoUri == null) {
            toast("اختر فيديو أولًا")
            return
        }
        val saved = CastSession.savedPosition(videoKey)
        if (saved >= 15) {
            val dlg = MaterialAlertDialogBuilder(this)
                .setTitle("متابعة المشاهدة؟")
                .setMessage("توقفت المرة السابقة عند ${fmtTime(saved)}")
                .setPositiveButton("متابعة") { _, _ -> startCast(maxOf(0, saved - CastSession.RESUME_BACK)) }
                .setNegativeButton("من البداية") { _, _ -> startCast(0) }
                .create()
            dlg.show()
            dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        } else {
            startCast(0)
        }
    }

    private fun startCast(startAt: Int) {
        val c = controller ?: return
        val uri = videoUri ?: return
        findViewById<View>(R.id.btnPickFile).apply { isEnabled = false; alpha = 0.6f }
        CastSession.cast(c, uri, videoName, videoKey, startAt) { ok ->
            findViewById<View>(R.id.btnPickFile).apply { isEnabled = true; alpha = 1f }
            if (!ok) toast("فشل البث. تأكد من الاتصال بالشبكة وأن التلفاز يدعم هذا الفيديو")
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (CastSession.handleVolumeKey(keyCode, event ?: return super.onKeyDown(keyCode, event))) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (CastSession.casting && (CastSession.volStop || !CastSession.phoneAudioWanted)) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** ضغطة واحدة = خطوة، والضغط المستمر يكرّر الخطوة بسرعة. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun holdRepeat(v: View, dir: Int) {
        val tick = object : Runnable {
            override fun run() {
                CastSession.volumeStep(dir)
                ui.postDelayed(this, 260)
            }
        }
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    ui.removeCallbacks(tick)
                    tick.run()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    ui.removeCallbacks(tick)
                }
            }
            true
        }
    }

    // ───────────── جودة يوتيوب أثناء المشاهدة ─────────────

    private fun showQualityDialog() {
        val src = CastSession.yt ?: return
        if (CastSession.preparing) {
            toast("هناك تجهيز جارٍ بالفعل")
            return
        }
        val current = CastSession.ytIndex
        QualityDialog.show(this, src, current) { idx ->
            if (idx != current) {
                val c = CastSession.controller
                if (c != null) {
                    CastSession.castSource(c, src, idx, -1) { ok, err ->
                        if (ok) toast("تم التبديل إلى ${src.options[idx].label}")
                        else if (err != null) toast(err)
                    }
                }
            }
        }
    }

    // ───────────── عرض الحالة ─────────────

    private fun render() {
        val s = CastSession
        // منطقة التحكم ظاهرة دائمًا وتخفت عند عدم وجود بث
        cardPlayer.alpha = if (s.casting) 1f else 0.45f
        chipLive.visibility = if (s.casting) View.VISIBLE else View.GONE
        btnPlayPause.setImageResource(if (s.casting && s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        txtNowTitle.text = if (s.casting) s.title else "لا يوجد بث حاليًا"
        rowVolume.visibility = if (s.phoneAudioWanted) View.GONE else View.VISIBLE
        txtVol.text = if (s.volume >= 0) "الصوت  ${s.volume}" else "الصوت"
        btnSpeed.text = "السرعة: ${Controller.speedLabel(s.speed)}  ▾"
        btnSpeed.visibility = if (s.casting) View.VISIBLE else View.GONE
        rowPrep.visibility = if (s.preparing) View.VISIBLE else View.GONE
        if (s.preparing) {
            txtPrep.text = s.prepText
            progPrep.progress = s.prepPct
        }
        val src = s.yt
        if (s.casting && src != null && s.ytIndex in src.options.indices) {
            btnQuality.text = "الجودة: ${src.options[s.ytIndex].label}  ▾"
            btnQuality.visibility = View.VISIBLE
        } else {
            btnQuality.visibility = View.GONE
        }
        if (s.duration > 0 && seekPos.max != s.duration) seekPos.max = s.duration
        // الهزّ وصوت الهاتف: القيمة المرجعية الوحيدة هي CastSession (تتزامن مع المتصفح)
        setTile(tileShake, icShake, txtShakeState, s.shakeExit, if (s.shakeExit) "مفعّل" else "متوقف")
        setTile(tilePhone, icPhone, txtPhoneState, s.phoneAudioWanted, if (s.phoneAudioWanted) "مفعّل" else "متوقف")
        setTile(tileVolStop, icVolStop, txtVolStopState, s.volStop, if (s.volStop) "مفعّل" else "متوقف")
        renderStatus()
        renderProgress()
    }

    /** بلاطة تبديل: بنفسجية عند التفعيل ورمادية عند الإيقاف. */
    private fun setTile(tile: View, icon: ImageView, state: TextView, on: Boolean, text: String) {
        tile.isActivated = on
        icon.imageTintList = ColorStateList.valueOf(getColor(if (on) R.color.accent else R.color.text_secondary))
        state.text = text
        state.setTextColor(getColor(if (on) R.color.accent else R.color.text_secondary))
    }

    private fun renderStatus() {
        val s = CastSession
        val name = (controller ?: s.selected)?.r?.name
        dotStatus.visibility = if (name != null) View.VISIBLE else View.GONE
        txtStatus.text = when {
            s.casting && name != null -> "يُعرض على $name"
            name != null -> "جاهز للبث · $name"
            else -> "لم يُختر جهاز عرض بعد"
        }
    }

    private fun renderSleep() {
        val m = CastSession.sleepLeftMin()
        btnSleep.text = if (m > 0) "ينتهي البث بعد $m د  ✕" else "مؤقت النوم"
    }

    private fun showSleepDialog() {
        val labels = arrayOf("15 دقيقة", "30 دقيقة", "45 دقيقة", "ساعة", "ساعة ونصف", "ساعتان")
        val mins = intArrayOf(15, 30, 45, 60, 90, 120)
        if (CastSession.sleepLeftMin() > 0) {
            CastSession.setSleepTimer(0)
            toast("أُلغي مؤقت النوم")
            return
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("إيقاف البث تلقائيًا بعد…")
            .setItems(labels) { _, i ->
                CastSession.setSleepTimer(mins[i])
                toast("سيتوقف البث بعد ${labels[i]}")
            }
            .setNegativeButton("إلغاء", null)
            .create()
        dlg.show()
        dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun confirmExit() {
        if (!CastSession.casting) {
            exitApp(applicationContext, this)
            return
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("الخروج من التطبيق؟")
            .setMessage("سيتوقف البث ويُحفظ مكانك لتكمل من قبل التوقف بـ ${CastSession.RESUME_BACK} ثوانٍ عند عودتك.")
            .setPositiveButton("خروج") { _, _ -> exitApp(applicationContext, this) }
            .setNegativeButton("إلغاء", null)
            .create()
        dlg.show()
        dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun renderProgress() {
        renderSleep()
        val s = CastSession
        if (s.duration > 0 && seekPos.max != s.duration) seekPos.max = s.duration
        txtTotal.text = fmtTime(s.duration)
        if (!userSeeking) {
            seekPos.progress = s.position
            txtCur.text = fmtTime(s.position)
        }
    }
}
