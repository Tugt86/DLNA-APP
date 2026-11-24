package com.mycast.dlna

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.ImageViewCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * علامات التبويب تبقى حيّة طوال عمر التطبيق، فكلما فتحت شاشة المتصفح
 * تجد نفسك في المكان نفسه الذي تركته (نفس التبويبات والصفحات والفيديو) دون إعادة تحميل.
 */
object BrowserWeb {
    private val tabs = ArrayList<WebView>()
    private var cur = 0
    private var wrap: MutableContextWrapper? = null
    private var fresh = false

    val count: Int get() = tabs.size
    val index: Int get() = cur
    fun all(): List<WebView> = tabs.toList()

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(c: Context): WebView {
        val wv = WebView(c)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)

        // كاشف الفيديو: الجسر أولًا ثم السكربت الذي يُحقن عند بدء كل مستند وإطار
        wv.addJavascriptInterface(VideoSniffer.Bridge(), "DlnaBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(wv, VideoSniffer.SCRIPT, setOf("*"))
            } catch (e: Exception) {
            }
        }
        // رابط فيديو يحاول الموقع تنزيله: نسجّله ليظهر في البث
        wv.setDownloadListener { url, _, _, mime, _ ->
            if (mime?.startsWith("video/") == true || VideoSniffer.looksLikeMedia(url)) {
                VideoSniffer.add(url, "video", wv.url, wv.title)
            }
        }
        return wv
    }

    /** يعيد التبويب الحالي (وينشئ أول تبويب إن لم يوجد). */
    fun attach(activity: Activity): WebView {
        var c = wrap
        if (c == null) {
            c = MutableContextWrapper(activity.applicationContext)
            wrap = c
        }
        c.baseContext = activity
        if (tabs.isEmpty()) {
            tabs.add(create(c))
            cur = 0
            fresh = true
        }
        val w = tabs[cur]
        (w.parent as? ViewGroup)?.removeView(w)
        return w
    }

    /** true مرة واحدة فقط: عند إنشاء الصفحة لأول مرة (ليحمّل آخر رابط). */
    fun consumeFresh(): Boolean {
        val f = fresh
        fresh = false
        return f
    }

    /** ينشئ تبويبًا جديدًا ويجعله الحالي. */
    fun newTab(): WebView {
        val w = create(wrap!!)
        tabs.add(w)
        cur = tabs.size - 1
        return w
    }

    /** يجعل التبويب رقم i هو الحالي ويعيده. */
    fun select(i: Int): WebView {
        cur = i.coerceIn(0, tabs.size - 1)
        val w = tabs[cur]
        (w.parent as? ViewGroup)?.removeView(w)
        return w
    }

    /** يغلق تبويبًا. إن كان الأخير يُنشأ بدله تبويب فارغ. */
    fun close(i: Int) {
        if (i !in tabs.indices) return
        val w = tabs.removeAt(i)
        (w.parent as? ViewGroup)?.removeView(w)
        try {
            w.stopLoading()
            w.destroy()
        } catch (e: Exception) {
        }
        if (tabs.isEmpty()) {
            tabs.add(create(wrap!!))
            cur = 0
        } else {
            if (i < cur) cur--
            if (cur >= tabs.size) cur = tabs.size - 1
        }
    }

    fun detach(app: Context) {
        for (w in tabs) {
            (w.parent as? ViewGroup)?.removeView(w)
            w.webViewClient = WebViewClient()
            w.webChromeClient = WebChromeClient()
        }
        wrap?.baseContext = app.applicationContext
    }
}

/** متصفح داخل التطبيق. في أي موقع يمكنك الضغط على «بث إلى التلفاز» ليكتشف التطبيق الفيديو ويبثه. */
class BrowserActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var btnCast: TextView
    private lateinit var webHolder: FrameLayout
    private lateinit var urlBox: EditText
    private lateinit var progress: ProgressBar
    private lateinit var btnStar: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var btnNewTab: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var btnExitBrowser: ImageButton
    private lateinit var tabRow: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var fullHolder: FrameLayout
    private lateinit var startPage: View
    private lateinit var startGrid: GridLayout
    private lateinit var startEmpty: TextView
    private lateinit var startGlow: View
    private lateinit var startResume: View
    private lateinit var startResumeTitle: TextView
    private lateinit var startResumeSub: TextView
    /** الصفحة التي يُبثّ منها الآن/المنتظر بثها (تُحفظ مع آخر بث). */
    private var pendingPage: String? = null
    /** استئناف تلقائي لصفحة ويب بانتظار تحميلها وكشف الفيديو. */
    private var resumeWeb: CastSession.LastCast? = null
    private var resumeTries = 0
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val warmExec = Executors.newSingleThreadExecutor()

    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private var currentId: String? = null   // معرّف فيديو يوتيوب إن كانت الصفحة صفحة مشاهدة
    private var busy = false
    @Volatile private var lastUrl: String? = null
    private var lastIcon: Bitmap? = null
    private var lastIconHost: String? = null
    private val iconTried = HashSet<String>()
    private var loadedUrl: String? = null
    private var pauseOnLoad = false

    private val sessionListener = object : CastSession.Listener {
        override fun onState() = updateButton()
    }

    private val poll = object : Runnable {
        override fun run() {
            checkUrl(web.url)
            ui.postDelayed(this, 700)
        }
    }

    private fun isYt(url: String?) = url != null &&
        (url.startsWith("https://m.youtube.com") || url.startsWith("https://www.youtube.com") ||
            url.startsWith("https://youtube.com") || url.startsWith("https://youtu.be"))

    private fun isStart(url: String?) = url == null || url.startsWith(START_BASE) ||
        url == "about:blank" || url.startsWith("data:")

    private fun stripFrag(u: String) = u.substringBefore('#')

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        CastSession.init(this)
        AdBlocker.init(applicationContext, CastSession.prefs)

        btnCast = findViewById(R.id.btnCastWeb)
        urlBox = findViewById(R.id.urlBox)
        progress = findViewById(R.id.webProgress)
        fullHolder = findViewById(R.id.fullHolder)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnReload = findViewById(R.id.btnReload)
        btnNewTab = findViewById(R.id.btnNewTab)
        btnMenu = findViewById(R.id.btnMenu)
        btnExitBrowser = findViewById(R.id.btnExitBrowser)
        tabRow = findViewById(R.id.tabRow)
        tabScroll = findViewById(R.id.tabScroll)
        btnStar = findViewById(R.id.btnWebStar)
        startGlow = findViewById(R.id.startGlow)
        startResume = findViewById(R.id.startResume)
        startResumeTitle = findViewById(R.id.startResumeTitle)
        startResumeSub = findViewById(R.id.startResumeSub)
        startPage = findViewById(R.id.startPage)
        startGrid = findViewById(R.id.startGrid)
        startEmpty = findViewById(R.id.startEmpty)

        btnCast.setOnClickListener { castCurrent() }
        btnStar.setOnClickListener { toggleBookmark() }
        startResume.setOnClickListener { startResume() }
        btnBack.setOnClickListener { if (web.canGoBack()) web.goBack() }
        btnForward.setOnClickListener { if (web.canGoForward()) web.goForward() }
        btnReload.setOnClickListener { web.reload() }
        btnNewTab.setOnClickListener { openNewTab() }
        btnMenu.setOnClickListener { showChromeMenu() }
        // خروج سريع من المتصفح (يبقى البث شغالًا ونعود لصفحة التحكم)، والضغط المطوّل يخرج من التطبيق كله
        btnExitBrowser.setOnClickListener { leaveToControl() }
        btnExitBrowser.setOnLongClickListener { confirmExit(); true }
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        urlBox.setOnEditorActionListener { v, actionId, ev ->
            val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                go(v.text.toString())
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                true
            } else false
        }

        webHolder = findViewById(R.id.webHolder)
        web = BrowserWeb.attach(this)
        for (w in BrowserWeb.all()) setupClients(w)
        webHolder.addView(
            web, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        renderTabStrip()
        updateNavButtons()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustom()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })

        val fresh = BrowserWeb.consumeFresh()
        if (intent?.getBooleanExtra(EXTRA_RESUME, false) == true) {
            // جاء من سجل المشاهدة (صفحة محددة) أو من «تابع من حيث توقفت»
            startResume(resumeFromIntent())
        } else if (fresh) {
            // أول تشغيل بعد فتح التطبيق: نعود لآخر صفحة كنت فيها إن وُجدت
            val saved = CastSession.prefs.getString("web_last_url", null)
                ?: CastSession.prefs.getString("yt_last_url", null)
            if (saved != null && (saved.startsWith("https://") || saved.startsWith("http://"))) {
                pauseOnLoad = true
                web.loadUrl(saved)
            } else loadStart()
        }
    }

    override fun onStart() {
        super.onStart()
        CastSession.addListener(sessionListener)
        VideoSniffer.onChange = {
            updateButton()
            schedulePrewarm()
        }
    }

    override fun onStop() {
        CastSession.removeListener(sessionListener)
        ui.removeCallbacks(prewarmRun)
        VideoSniffer.onChange = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        updateButton()
        showUrl(web.url)
        refreshStart()
        ui.post(poll)
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        pauseVideo() // لا يبقى صوت الفيديو شغالًا على الهاتف في الخلفية
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (customView != null) hideCustom()
        // لا نُتلف الصفحة: تبقى حيّة لتجدها كما تركتها المرة القادمة
        BrowserWeb.detach(applicationContext)
        super.onDestroy()
    }

    /** يربط التبويب بعمليات المتصفح (التقدّم، ملء الشاشة، كشف الفيديو...). كل الأحداث تُهمَل لو لم يكن هو التبويب الظاهر. */
    private fun setupClients(w: WebView) {
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view !== web) return
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                if (newProgress == 100) updateNavButtons()
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (view === web) renderTabStrip()
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                if (view !== web || icon == null) return
                val u = view.url ?: return
                if (!isStart(u) && Bookmarks.has(u)) {
                    Thumbs.saveIcon(applicationContext, u, icon)
                }
                lastIcon = icon
                lastIconHost = Thumbs.hostOf(u)
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                showCustom(view, callback)
            }

            override fun onHideCustomView() = hideCustom()
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val s = r.url.scheme
                return !(s == "http" || s == "https") // نمنع فتح تطبيقات خارجية
            }

            /** نراقب طلبات الشبكة لنكتشف الفيديو، ونحظر الإعلانات بمحرك Brave إن فُعّل. */
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                try {
                    val blocked = AdBlocker.intercept(r)
                    if (blocked != null) return blocked
                    val u = r.url.toString()
                    if (v === web && !isYt(lastUrl) && VideoSniffer.looksLikeMedia(u)) {
                        VideoSniffer.onRequest(u, r.requestHeaders?.get("Referer") ?: lastUrl)
                    }
                } catch (e: Exception) {
                }
                return null
            }

            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                if (v !== web) return
                if (url != null && (loadedUrl == null || stripFrag(url) != loadedUrl)) {
                    loadedUrl = stripFrag(url)
                    VideoSniffer.clear()
                }
                checkUrl(url)
            }

            override fun onPageFinished(v: WebView, url: String?) {
                if (v !== web) return
                checkUrl(url)
                // احتياط للأجهزة التي لا تدعم الحقن عند بدء المستند
                if (!isYt(url) && !isStart(url)) v.evaluateJavascript(VideoSniffer.SCRIPT, null)
                AdBlocker.injectCosmetics(v, url)
                maybeCaptureThumb(url)
                resumeWeb?.let { last ->
                    // قد تعيد الصفحة التوجيه لعنوان مختلف، لذلك نكتفي بأنها صفحة حقيقية وليست البداية
                    if (!isStart(url) && resumeTries == 0) {
                        resumeTries = 1
                        ui.postDelayed({ attemptWebResume(last) }, 2500)
                    }
                }
                if (pauseOnLoad) {
                    pauseOnLoad = false
                    for (d in longArrayOf(600, 1800, 3500)) ui.postDelayed({ pauseVideo() }, d)
                }
            }

            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                if (v !== web) return
                if (url != null && !isReload && stripFrag(url) != loadedUrl) {
                    loadedUrl = stripFrag(url)
                    VideoSniffer.clear()
                }
                checkUrl(url)
            }
        }

    }

    // ───────────── التنقل ─────────────

    private fun loadStart() {
        loadedUrl = null
        web.loadDataWithBaseURL(START_BASE, START_HTML, "text/html", "utf-8", null)
        refreshStart()
    }

    private val ytWords = setOf("youtube", "يوتيوب", "يوتوب", "اليوتيوب")

    private fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.lowercase() in ytWords -> "https://m.youtube.com"
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
        web.loadUrl(url)
    }

    private fun showUrl(url: String?) {
        if (urlBox.hasFocus()) return
        val shown = if (isStart(url)) "" else url ?: ""
        if (urlBox.text.toString() != shown) urlBox.setText(shown)
    }

    // ───────────── علامات التبويب ─────────────

    private fun putWeb(w: WebView) {
        webHolder.removeAllViews()
        web = w
        web.onResume()
        webHolder.addView(
            web, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    /** بعد تغيير التبويب الظاهر: نحدّث الشريط والعنوان وكاشف الفيديو. */
    private fun afterSwitch() {
        val u = web.url
        loadedUrl = if (isStart(u)) null else stripFrag(u ?: "")
        lastUrl = null
        VideoSniffer.clear()
        if (!isStart(u)) web.evaluateJavascript("window.__dlnaScan&&window.__dlnaScan()", null)
        busy = false
        urlBox.clearFocus()
        checkUrl(u)
        showUrl(u)
        refreshStart()
        updateButton()
        renderTabStrip()
        updateNavButtons()
    }

    private fun switchTo(i: Int) {
        pauseVideo()
        web.onPause()
        val w = BrowserWeb.select(i)
        setupClients(w)
        putWeb(w)
        afterSwitch()
    }

    private fun newTab() {
        pauseVideo()
        web.onPause()
        val w = BrowserWeb.newTab()
        setupClients(w)
        putWeb(w)
        loadStart()
        afterSwitch()
    }

    private fun openNewTab() = newTab()

    private fun closeTab(i: Int) {
        val wasCurrent = i == BrowserWeb.index
        BrowserWeb.close(i)
        if (wasCurrent) {
            val w = BrowserWeb.select(BrowserWeb.index)
            setupClients(w)
            putWeb(w)
            if (w.url == null) loadStart()
            afterSwitch()
        } else {
            renderTabStrip()
        }
    }

    private fun showTabs() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val sv = ScrollView(this).apply { addView(col) }
        var dlg: androidx.appcompat.app.AlertDialog? = null

        fun fill() {
            col.removeAllViews()
            val list = BrowserWeb.all()
            for (i in list.indices) {
                val w = list[i]
                val isCur = i == BrowserWeb.index
                val u = w.url
                val start = isStart(u)
                val title = if (start) "تبويب جديد"
                else (w.title?.takeIf { it.isNotBlank() } ?: (u ?: ""))
                val sub = if (start) "الصفحات المحفوظة"
                else (u ?: "").substringAfter("://").removePrefix("www.")
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(4), dp(10))
                    isClickable = true
                    if (isCur) {
                        background = GradientDrawable().apply {
                            cornerRadius = dp(12).toFloat()
                            setColor(0x338B7CFF)
                        }
                    }
                    setOnClickListener {
                        dlg?.dismiss()
                        if (i != BrowserWeb.index) switchTo(i)
                    }
                }
                val texts = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                texts.addView(TextView(this).apply {
                    text = title
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 15f
                    if (isCur) setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                texts.addView(TextView(this).apply {
                    text = sub
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 12f
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                })
                row.addView(texts)
                row.addView(TextView(this).apply {
                    text = "✕"
                    setTextColor(getColor(R.color.danger))
                    textSize = 18f
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    setOnClickListener {
                        closeTab(i)
                        fill()
                    }
                })
                col.addView(row)
            }
        }
        fill()
        val d = MaterialAlertDialogBuilder(this)
            .setTitle("علامات التبويب")
            .setView(sv)
            .setPositiveButton("تبويب جديد") { _, _ -> newTab() }
            .setNegativeButton("إغلاق", null)
            .create()
        dlg = d
        d.show()
        d.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    // ───────────── ملء الشاشة للفيديو ─────────────

    private fun showCustom(view: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            cb.onCustomViewHidden()
            return
        }
        customView = view
        customCb = cb
        fullHolder.addView(
            view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        fullHolder.visibility = View.VISIBLE
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun hideCustom() {
        val v = customView ?: return
        fullHolder.removeView(v)
        fullHolder.visibility = View.GONE
        customView = null
        customCb?.onCustomViewHidden()
        customCb = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    // ───────────── زر البث ─────────────

    private fun pauseVideo() {
        web.evaluateJavascript(
            "(function(){document.querySelectorAll('video').forEach(function(v){v.pause();});})()", null
        )
    }

    private fun deviceLabel(): String {
        val c = CastSession.selected
        return if (c != null) "إلى: ${c.r.name}" else "لم يُختر جهاز عرض"
    }

    private fun checkUrl(url: String?) {
        if (url == null || url == lastUrl) return
        lastUrl = url
        if (!isStart(url) && (url.startsWith("http://") || url.startsWith("https://"))) {
            CastSession.prefs.edit().putString("web_last_url", url).apply()
        }
        currentId = YouTubeResolver.videoId(url)
        showUrl(url)
        updateButton()
        updateNavButtons()
        renderTabStrip()
    }

    // ───────────── الأزرار السريعة ─────────────

    /** الأيقونة بلون مميّز (بنفسجي) إن كانت مفعّلة، وبلون رمادي إن لم تكن. */
    private fun tint(b: ImageButton, on: Boolean) {
        val col = getColor(if (on) R.color.accent else R.color.text_secondary)
        ImageViewCompat.setImageTintList(b, ColorStateList.valueOf(col))
    }

    private fun tintColor(b: ImageButton, res: Int) =
        ImageViewCompat.setImageTintList(b, ColorStateList.valueOf(getColor(res)))

    private fun updateChips() {
        renderTabStrip()
        updateNavButtons()
    }

    private fun updateNavButtons() {
        if (!::web.isInitialized) return
        btnBack.alpha = if (web.canGoBack()) 1f else 0.35f
        btnBack.isEnabled = web.canGoBack()
        btnForward.alpha = if (web.canGoForward()) 1f else 0.35f
        btnForward.isEnabled = web.canGoForward()
    }

    /** شريط تبويبات بأسلوب كروم أعلى الصفحة. */
    private fun renderTabStrip() {
        if (!::tabRow.isInitialized) return
        tabRow.removeAllViews()
        val list = BrowserWeb.all()
        val cur = BrowserWeb.index
        for (i in list.indices) {
            val w = list[i]
            val active = i == cur
            val u = w.url
            val title = when {
                isStart(u) -> "تبويب جديد"
                !w.title.isNullOrBlank() -> w.title!!
                else -> (u ?: "").substringAfter("://").removePrefix("www.").ifBlank { "صفحة" }
            }.take(28)

            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(140), dp(36)).apply {
                    marginEnd = dp(4)
                }
                background = getDrawable(if (active) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive)
                setPadding(dp(10), 0, dp(4), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { if (i != BrowserWeb.index) switchTo(i) }
            }
            chip.addView(TextView(this).apply {
                text = title
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(getColor(if (active) R.color.text_primary else R.color.text_secondary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            chip.addView(ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(getColor(R.color.text_secondary)))
                background = null
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                contentDescription = "إغلاق التبويب"
                setOnClickListener { closeTab(i) }
            })
            tabRow.addView(chip)
        }
        // مرّر للتبويب الحالي
        tabScroll.post {
            val child = tabRow.getChildAt(cur) ?: return@post
            tabScroll.smoothScrollTo(child.left - dp(16), 0)
        }
    }

    /** قائمة كروم: سجل، مانع إعلانات، إعدادات البث، خروج. */
    private fun showChromeMenu() {
        val pm = PopupMenu(this, btnMenu)
        pm.menu.add(0, 1, 0, "الصفحة الرئيسية")
        pm.menu.add(0, 2, 0, "سجل المشاهدة")
        pm.menu.add(0, 3, 0, "تبويب جديد")
        val adLabel = if (AdBlocker.strict)
            "مانع Brave (adblock-rust): مفعّل ✓"
        else
            "مانع Brave (adblock-rust): معطّل"
        pm.menu.add(0, 4, 0, adLabel)
        pm.menu.add(0, 5, 0, if (CastSession.shakeExit) "وضع الهز: مفعّل ✓" else "وضع الهز")
        pm.menu.add(0, 6, 0, if (CastSession.phoneAudioWanted) "صوت الهاتف: مفعّل ✓" else "صوت الهاتف")
        pm.menu.add(0, 14, 0, if (CastSession.volStop) "الإيقاف بزر الصوت: مفعّل ✓" else "الإيقاف بزر الصوت")
        pm.menu.add(0, 7, 0, "معايرة الصوت")
        pm.menu.add(0, 8, 0, "سرعة البث")
        pm.menu.add(0, 9, 0, deviceLabel())
        if (CastSession.casting) {
            pm.menu.add(0, 10, 0, if (CastSession.playing) "إيقاف مؤقت للبث" else "استئناف البث")
            pm.menu.add(0, 11, 0, "إيقاف البث")
        }
        pm.menu.add(0, 12, 0, "إغلاق المتصفح")
        pm.menu.add(0, 13, 0, "خروج من التطبيق")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { loadStart(); true }
                2 -> {
                    startActivity(android.content.Intent(this, HistoryActivity::class.java))
                    true
                }
                3 -> { openNewTab(); true }
                4 -> {
                    AdBlocker.strict = !AdBlocker.strict
                    AdBlocker.clearCache()
                    if (AdBlocker.strict) {
                        AdBlocker.ensureEngineAsync()
                        toast("مانع Brave مفعّل — جارٍ تحميل قوائم EasyList…")
                    } else {
                        toast("مانع الإعلانات معطّل")
                    }
                    web.reload()
                    true
                }
                5 -> { toggleShake(); true }
                6 -> { togglePhone(); true }
                14 -> {
                    val on = !CastSession.volStop
                    CastSession.setVolStop(on)
                    toast(if (on) "اضغط زر الصوت أثناء البث لإيقافه" else "أزرار الصوت ترجع لتغيير صوت التلفاز")
                    true
                }
                7 -> { showCalibration(); true }
                8 -> { SpeedDialog.show(this); true }
                9 -> { toast(deviceLabel()); true }
                10 -> {
                    if (CastSession.casting) CastSession.togglePlay()
                    true
                }
                11 -> {
                    if (CastSession.casting) CastSession.stop()
                    true
                }
                12 -> { finish(); true }
                13 -> { confirmExit(); true }
                else -> false
            }
        }
        pm.show()
    }

    private fun makeEnv(pageUrl: String, title: String): WebVideoResolver.Env {
        val ua = web.settings.userAgentString
        return WebVideoResolver.Env(pageUrl, title, ua) { u -> CookieManager.getInstance().getCookie(u) }
    }

    /** بعد كشف الفيديو بثانية ونصف نفحص الروابط في الخلفية، فيصير الضغط على «بث» شبه فوري. */
    private val prewarmRun = Runnable {
        val url = web.url ?: return@Runnable
        if (isYt(url) || isStart(url) || CastSession.preparing) return@Runnable
        val snap = VideoSniffer.snapshot()
        if (snap.isEmpty()) return@Runnable
        val env = makeEnv(url, web.title ?: "")
        warmExec.execute {
            try {
                WebVideoResolver.prewarm(env, snap)
            } catch (e: Exception) {
            }
        }
    }

    private fun schedulePrewarm() {
        ui.removeCallbacks(prewarmRun)
        ui.postDelayed(prewarmRun, 1500)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (CastSession.handleVolumeKey(keyCode, event ?: return super.onKeyDown(keyCode, event))) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun showCalibration() {
        if (!CastSession.casting) {
            toast("يعمل ضبط المزامنة أثناء البث مع تفعيل صوت الهاتف")
            return
        }
        val v = CastSession.calibrationMs
        val txt = (if (v > 0) "+" else "") + v + " م.ث"
        val d = MaterialAlertDialogBuilder(this)
            .setTitle("ضبط مزامنة الصوت")
            .setMessage(
                "المخرج: ${CastSession.routeName()}\nالإزاحة الحالية: $txt\n\n" +
                    "إن وصل الصوت متأخرًا عن الصورة اضغط «أبكر»، وإن سبق الصورة اضغط «أبطأ». " +
                    "تُحفظ القيمة لهذا التلفاز ولهذا المخرج."
            )
            .setPositiveButton("أبكر +50") { _, _ ->
                CastSession.adjustCalibration(50)
                showCalibration()
            }
            .setNegativeButton("أبطأ −50") { _, _ ->
                CastSession.adjustCalibration(-50)
                showCalibration()
            }
            .setNeutralButton("صفر") { _, _ ->
                CastSession.resetCalibration()
                showCalibration()
            }
            .create()
        d.show()
        d.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    /** الخيار المقترح: أعلى جودة لا تتجاوز آخر جودة اخترتها، وإلا أقرب جودة أعلى منها. */
    private fun preferredIndex(src: YouTubeResolver.Source, last: Int): Int {
        if (last <= 0) return -1
        val o = src.options
        var best = -1
        for (i in o.indices) if (o[i].height in 1..last && (best < 0 || o[i].height > o[best].height)) best = i
        if (best < 0) for (i in o.indices) if (o[i].height > last && (best < 0 || o[i].height < o[best].height)) best = i
        return best
    }

    private fun toggleShake() {
        val on = !CastSession.shakeExit
        CastSession.setShakeExit(on)
        updateChips()
        toast(if (on) "وضع الهز مفعّل: هزّ الهاتف هزّة قوية أثناء البث للخروج" else "تم إيقاف وضع الهز")
    }

    private fun togglePhone() {
        val on = !CastSession.phoneAudioWanted
        CastSession.setPhoneAudio(on)
        updateChips()
        toast(if (on) "الصوت سيصل إلى الهاتف أثناء البث. من القائمة: معايرة الصوت" else "الصوت عاد إلى التلفاز")
    }

    // ───────────── شاشة البداية (مربعات المحفوظات) ─────────────

    private fun refreshStart() {
        val onStart = isStart(web.url)
        startPage.visibility = if (onStart) View.VISIBLE else View.GONE
        startGlow.visibility = if (onStart) View.VISIBLE else View.GONE
        if (!onStart) return
        val last = if (CastSession.casting) null else CastSession.lastCast()
        if (last != null) {
            startResume.visibility = View.VISIBLE
            startResumeTitle.text = last.title.ifBlank { last.page.substringAfter("://").removePrefix("www.") }
            startResumeSub.text = "تابع من ${fmtTime(last.resumeAt)} (قبل التوقف بـ ${CastSession.RESUME_BACK} ثوانٍ)"
        } else {
            startResume.visibility = View.GONE
        }
        val items = Bookmarks.all()
        startEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        startGrid.removeAllViews()
        startGrid.columnCount = START_COLS
        val gap = dp(7)
        val tileW = (resources.displayMetrics.widthPixels - dp(32) - gap * 2 * START_COLS) / START_COLS
        val palette = intArrayOf(
            0xFF8B7CFF.toInt(), 0xFF35C3FF.toInt(), 0xFF35D399.toInt(),
            0xFFFFC93C.toInt(), 0xFFFF6B7A.toInt(), 0xFFFF8A4C.toInt()
        )
        val missingIcons = ArrayList<String>()
        for (b in items) {
            val host = Thumbs.hostOf(b.url)
            val label = (b.title.takeIf { it.isNotBlank() && it != b.url } ?: host)
            val letter = (host.firstOrNull() ?: label.first()).uppercaseChar().toString()
            val color = palette[(host.hashCode() and 0x7fffffff) % palette.size]
            val shot = Thumbs.loadShot(applicationContext, b.url)
            val icon = Thumbs.loadIcon(applicationContext, b.url)
            if (icon == null && iconTried.add(host)) missingIcons.add(b.url)

            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(10))
                background = getDrawable(R.drawable.bg_tile)
                isClickable = true
                setOnClickListener { web.loadUrl(b.url) }
                setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@BrowserActivity)
                        .setTitle("إزالة من المحفوظات؟")
                        .setMessage(label)
                        .setPositiveButton("إزالة") { _, _ ->
                            Bookmarks.remove(b.url)
                            Thumbs.delete(applicationContext, b.url)
                            refreshStart()
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                        .window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
                    true
                }
            }

            // الصورة العلوية: لقطة الصفحة، أو شعار الموقع، أو الحرف الأول
            val banner = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(if (shot != null) 0xFF1C2336.toInt() else (color and 0x00FFFFFF) or 0x33000000)
                }
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104))
            }
            if (shot != null) {
                banner.addView(ImageView(this).apply {
                    setImageBitmap(shot)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                if (icon != null) {
                    banner.addView(ImageView(this).apply {
                        setImageBitmap(icon)
                        setPadding(dp(3), dp(3), dp(3), dp(3))
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.WHITE)
                        }
                    }, FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                        gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                        setMargins(dp(6), dp(6), dp(6), dp(6))
                    })
                }
            } else if (icon != null) {
                banner.addView(ImageView(this).apply {
                    setImageBitmap(icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, FrameLayout.LayoutParams(dp(56), dp(56)).apply {
                    gravity = android.view.Gravity.CENTER
                })
            } else {
                banner.addView(TextView(this).apply {
                    text = letter
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.WHITE)
                    textSize = 26f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                    }
                }, FrameLayout.LayoutParams(dp(56), dp(56)).apply {
                    gravity = android.view.Gravity.CENTER
                })
            }
            tile.addView(banner)
            tile.addView(TextView(this).apply {
                text = label
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.text_primary))
                textSize = 13.5f
                setPadding(0, dp(8), 0, 0)
            })
            tile.addView(TextView(this).apply {
                text = host
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            })
            startGrid.addView(tile, GridLayout.LayoutParams().apply {
                width = tileW
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(gap, gap, gap, gap)
            })
        }

        // شعارات ناقصة: نجلبها في الخلفية مرة واحدة ثم نحدّث المربعات
        if (missingIcons.isNotEmpty()) {
            val ctx = applicationContext
            exec.execute {
                var any = false
                for (u in missingIcons) if (Thumbs.fetchIcon(ctx, u)) any = true
                if (any) ui.post { if (!isFinishing && !isDestroyed) refreshStart() }
            }
        }
    }

    private fun updateStar() {
        val u = web.url
        btnStar.visibility = if (isStart(u)) View.INVISIBLE else View.VISIBLE
        btnStar.setImageResource(if (Bookmarks.has(u)) R.drawable.ic_star_filled else R.drawable.ic_star)
    }

    /** صفحة محفوظة: نحدّث لقطتها بعد أن تُرسم (مرة كل 12 ساعة تقريبًا). */
    private fun maybeCaptureThumb(url: String?) {
        if (url == null || isStart(url) || !Bookmarks.has(url)) return
        if (!Thumbs.shotStale(applicationContext, url)) return
        ui.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (customCb != null || web.url != url) return@postDelayed
            Thumbs.saveShot(applicationContext, url, web)
        }, 2000)
    }

    private fun toggleBookmark() {
        val u = web.url
        if (u == null || isStart(u)) return
        val saved = Bookmarks.toggle(web.title, u)
        if (saved) {
            Thumbs.saveShot(applicationContext, u, web)
            val ic = lastIcon
            if (ic != null && lastIconHost == Thumbs.hostOf(u)) Thumbs.saveIcon(applicationContext, u, ic)
        } else {
            Thumbs.delete(applicationContext, u)
        }
        updateStar()
        toast(if (saved) "تم حفظ الصفحة ★" else "أُزيلت الصفحة من المحفوظات")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun updateButton() {
        updateStar()
        updateChips()
        val onStart = isStart(web.url)
        if (onStart != (startPage.visibility == View.VISIBLE)) refreshStart()
        val s = CastSession
        val url = web.url
        btnCast.visibility = if (isStart(url) && !s.preparing) View.GONE else View.VISIBLE
        when {
            s.preparing -> {
                btnCast.text = s.prepText + " — إلغاء"
                btnCast.alpha = 0.9f
            }
            busy -> {
                btnCast.text = "جارٍ البحث عن الفيديو…"
                btnCast.alpha = 0.7f
            }
            else -> {
                val n = if (isYt(url)) 0 else VideoSniffer.count()
                btnCast.text = if (n > 0) "بث إلى التلفاز ($n)" else "بث إلى التلفاز"
                btnCast.alpha = if (currentId != null || n > 0) 1f else 0.85f
            }
        }
    }

    private fun castCurrent() {
        if (CastSession.preparing) {
            confirmCancel()
            return
        }
        if (busy) return
        val c = CastSession.selected
        if (c == null) {
            toast("اختر جهاز العرض من الشاشة الرئيسية أولًا")
            return
        }
        val pageUrl = web.url ?: return
        if (isStart(pageUrl)) return
        pendingPage = pageUrl

        if (isYt(pageUrl)) {
            // يوتيوب: المستخرج الخاص به كما كان
            if (YouTubeResolver.videoId(pageUrl) == null) {
                toast("افتح فيديو يوتيوب أولًا ثم اضغط الزر")
                return
            }
            busy = true
            updateButton()
            pauseVideo()
            exec.execute {
                try {
                    val src = YouTubeResolver.load(pageUrl)
                    ui.post { onSource(c, src, "اختر الجودة", yt = true) }
                } catch (e: Exception) {
                    ui.post { fail(e) }
                }
            }
            return
        }

        // أي موقع آخر: نطلب من الصفحة مسحًا فوريًا، ثم نحلّل ما كُشف
        busy = true
        updateButton()
        val env = makeEnv(pageUrl, web.title ?: "")
        web.evaluateJavascript("window.__dlnaScan&&window.__dlnaScan()", null)
        pauseVideo()
        ui.postDelayed({
            exec.execute {
                try {
                    val src = WebVideoResolver.resolve(env, VideoSniffer.snapshot())
                    ui.post { onSource(c, src, "اختر الجودة والسيرفر", yt = false) }
                } catch (e: Exception) {
                    ui.post { fail(e) }
                }
            }
        }, if (VideoSniffer.count() > 0) 350L else 700L)
    }

    private fun fail(e: Exception) {
        busy = false
        updateButton()
        toast(e.message ?: "تعذّر استخراج الفيديو")
    }

    /** تُعرض الجودات المتاحة دائمًا قبل البث لتختار منها، وتُعلَّم آخر جودة اخترتها (✓). */
    private fun onSource(c: Controller, src: YouTubeResolver.Source, title: String, yt: Boolean) {
        busy = false
        updateButton()
        if (isFinishing || isDestroyed) return
        val key = if (yt) "yt_last_h" else "web_last_h"
        val pre = preferredIndex(src, CastSession.prefs.getInt(key, 0))
        val t = if (pre >= 0) "$title (✓ آخر اختيار)" else title
        QualityDialog.show(this, src, pre, t) { idx ->
            val h = src.options[idx].height
            if (h > 0) CastSession.prefs.edit().putInt(key, h).apply()
            // نفس الفيديو الذي توقفت عنده سابقًا؟ نعرض المتابعة من قبل التوقف بـ 5 ثوانٍ
            // (من آخر بث، أو من سجل المشاهدة لو فتحت الصفحة نفسها يدويًا)
            val last = CastSession.lastCast()
            var stopPos = -1
            if (last != null && samePage(last.page, pendingPage)) {
                stopPos = last.pos
            } else {
                val h = History.all().firstOrNull { it.type == History.WEB && !it.done && samePage(it.page, pendingPage) }
                if (h != null) stopPos = h.pos
            }
            val resumeAt = maxOf(0, stopPos - CastSession.RESUME_BACK)
            if (stopPos >= 0 && resumeAt >= 10) {
                val d = MaterialAlertDialogBuilder(this)
                    .setTitle("متابعة المشاهدة؟")
                    .setMessage("توقفت عند ${fmtTime(stopPos)}. سيبدأ من ${fmtTime(resumeAt)}.")
                    .setPositiveButton("متابعة") { _, _ -> startCast(c, src, idx, resumeAt) }
                    .setNegativeButton("من البداية") { _, _ -> startCast(c, src, idx, 0) }
                    .create()
                d.show()
                d.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
            } else {
                startCast(c, src, idx, 0)
            }
        }
    }

    private fun startCast(c: Controller, src: YouTubeResolver.Source, idx: Int, startAt: Int = 0) {
        val label = src.options[idx].label
        CastSession.castSource(c, src, idx, startAt, pendingPage) { ok, err ->
            updateButton()
            if (ok) {
                toast(
                    if (startAt > 0) "بدأ البث من ${fmtTime(startAt)} ($label)"
                    else "بدأ البث ($label)"
                )
                // عند بدء البث ننتقل تلقائيًا لصفحة التحكم (MainActivity تحت المتصفح في المكدس)
                if (!isFinishing && !isDestroyed) leaveToControl()
            }
            else if (err != null) toast(err)
        }
        updateButton()
    }

    // ───────────── استئناف آخر بث ─────────────

    private fun samePage(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val ia = YouTubeResolver.videoId(a)
        val ib = YouTubeResolver.videoId(b)
        if (ia != null && ib != null) return ia == ib
        return stripFrag(a).trimEnd('/') == stripFrag(b).trimEnd('/')
    }

    /** يفتح آخر صفحة بُثّ منها ويعيد البث من قبل نقطة التوقف بـ 5 ثوانٍ. */
    /** عنصر من سجل المشاهدة مرّره MainActivity (الرابط + الموضع + الجودة)، أو null. */
    private fun resumeFromIntent(): CastSession.LastCast? {
        val page = intent?.getStringExtra(EXTRA_PAGE) ?: return null
        return CastSession.LastCast(
            intent.getStringExtra(EXTRA_TITLE) ?: "", page,
            intent.getIntExtra(EXTRA_HEIGHT, 0), intent.getIntExtra(EXTRA_POS, 0),
            intent.getIntExtra(EXTRA_DUR, 0), System.currentTimeMillis()
        )
    }

    private fun startResume(given: CastSession.LastCast? = null) {
        val last = given ?: CastSession.lastCast()
        if (last == null) {
            toast("لا يوجد بث سابق لاستئنافه")
            return
        }
        pauseOnLoad = true
        loadedUrl = null
        pendingPage = last.page
        web.loadUrl(last.page)
        val c = CastSession.selected
        if (c == null) {
            toast("اختر جهاز العرض من الشاشة الرئيسية ثم اضغط «بث» وسيكمل من ${fmtTime(last.resumeAt)}")
            return
        }
        if (CastSession.preparing) return
        busy = true
        updateButton()
        if (isYt(last.page)) {
            exec.execute {
                try {
                    val src = YouTubeResolver.load(last.page)
                    ui.post { autoResume(c, src, last) }
                } catch (e: Exception) {
                    ui.post { fail(e) }
                }
            }
        } else {
            // المواقع العامة: ننتظر تحميل الصفحة ثم نكشف الفيديو (في onPageFinished)
            resumeWeb = last
            resumeTries = 0
            ui.postDelayed({
                if (resumeWeb === last) {
                    resumeWeb = null
                    busy = false
                    updateButton()
                    toast("تعذّر استئناف البث تلقائيًا. شغّل الفيديو ثم اضغط «بث» وسيعرض المتابعة من ${fmtTime(last.resumeAt)}")
                }
            }, 30000)
        }
    }

    private fun attemptWebResume(last: CastSession.LastCast) {
        if (isFinishing || isDestroyed || resumeWeb !== last) return
        val c = CastSession.selected ?: run { resumeWeb = null; busy = false; updateButton(); return }
        val pageUrl = web.url ?: return
        val env = makeEnv(pageUrl, web.title ?: "")
        web.evaluateJavascript("window.__dlnaScan&&window.__dlnaScan()", null)
        ui.postDelayed({
            exec.execute {
                try {
                    val src = WebVideoResolver.resolve(env, VideoSniffer.snapshot())
                    ui.post {
                        if (resumeWeb === last) {
                            resumeWeb = null
                            autoResume(c, src, last)
                        }
                    }
                } catch (e: Exception) {
                    ui.post {
                        if (resumeWeb !== last) return@post
                        if (++resumeTries < 4) {
                            ui.postDelayed({ attemptWebResume(last) }, 2000)
                        } else {
                            resumeWeb = null
                            busy = false
                            updateButton()
                            toast("لم يُكتشف الفيديو تلقائيًا. شغّله في الصفحة ثم اضغط «بث» وسيعرض المتابعة من ${fmtTime(last.resumeAt)}")
                        }
                    }
                }
            }
        }, 700)
    }

    private fun autoResume(c: Controller, src: YouTubeResolver.Source, last: CastSession.LastCast) {
        busy = false
        updateButton()
        if (isFinishing || isDestroyed || src.options.isEmpty()) return
        var idx = preferredIndex(src, last.height)
        if (idx < 0) idx = src.options.indices.maxByOrNull { src.options[it].height } ?: 0
        startCast(c, src, idx, last.resumeAt)
    }

    /** يغلق المتصفح ويعود لصفحة التحكم (وإن لم تكن موجودة تحته نفتحها). */
    private fun leaveToControl() {
        if (isTaskRoot) {
            startActivity(
                android.content.Intent(this, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        finish()
    }

    private fun confirmExit() {
        if (!CastSession.casting) {
            exitApp(applicationContext, this)
            return
        }
        val d = MaterialAlertDialogBuilder(this)
            .setTitle("الخروج من التطبيق؟")
            .setMessage("سيتوقف البث ويُحفظ مكانك لتكمل من قبل التوقف بـ ${CastSession.RESUME_BACK} ثوانٍ عند عودتك.")
            .setPositiveButton("خروج") { _, _ -> exitApp(applicationContext, this) }
            .setNegativeButton("إلغاء", null)
            .create()
        d.show()
        d.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun confirmCancel() {
        val d = MaterialAlertDialogBuilder(this)
            .setTitle("إلغاء التجهيز؟")
            .setPositiveButton("إلغاء التجهيز") { _, _ -> CastSession.cancelPrepare() }
            .setNegativeButton("متابعة الانتظار", null)
            .create()
        d.show()
        d.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun toast(m: String) = Toast.makeText(applicationContext, m, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_RESUME = "resume"
        const val EXTRA_PAGE = "resume_page"
        const val EXTRA_TITLE = "resume_title"
        const val EXTRA_HEIGHT = "resume_height"
        const val EXTRA_POS = "resume_pos"
        const val EXTRA_DUR = "resume_dur"

        /** عدد أعمدة مربعات الصفحات المحفوظة (2 = مربعات أكبر، 3 = أصغر). */
        const val START_COLS = 2

        const val START_BASE = "https://start.dlna.local/"

        const val START_HTML =
            "<!doctype html><html><head><meta charset=\"utf-8\"></head><body style=\"margin:0;background:#0A0D14\"></body></html>"
    }
}
