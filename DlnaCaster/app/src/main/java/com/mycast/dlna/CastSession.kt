package com.mycast.dlna

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * حالة البث الكاملة في مكان واحد (تعيش مع الخدمة الأمامية لا مع الشاشة).
 * بهذا يستمر كل شيء لو خرجت من التطبيق: التحكم من الإشعار وشاشة القفل، الهزّ للإيقاف، حفظ موضع المشاهدة.
 * كل الدوال العامة تُستدعى من الخيط الرئيسي.
 */
object CastSession {

    interface Listener {
        fun onState() {}
        fun onProgress() {}
    }

    private lateinit var app: Context
    private var inited = false
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<Listener>()

    lateinit var phoneAudio: PhoneAudio
        private set

    /** الجهاز الذي اختاره المستخدم في الشاشة الرئيسية (تستعمله شاشة يوتيوب). */
    var selected: Controller? = null

    var controller: Controller? = null
        private set
    var casting = false
        private set
    var playing = false
        private set
    var duration = 0
        private set
    var position = 0
        private set
    var title = ""
        private set
    var phoneAudioWanted = false
        private set

    /** هل «الإيقاف بالهزّ» مفعّل؟ حالة واحدة تشترك فيها الشاشة الرئيسية والمتصفح فتبقيان متطابقتين دائمًا. */
    var shakeExit = false
        private set

    /** «الإيقاف بزر الصوت»: ضغط أي زر صوت في الهاتف أثناء البث يوقفه، وتتعطل ميزة تغيير صوت التلفاز بالأزرار. */
    var volStop = false
        private set

    /** رابط الصفحة التي بُثّ منها الفيديو الحالي (للمتصفح فقط)، ليُستأنف لاحقًا. */
    private var curPage: String? = null
    private var prepPage: String? = null

    /** رابط ملف الهاتف الجاري بثه (للسجل)، أو null إن كان البث من المتصفح. */
    private var histUri: String? = null

    // ───────────── حالة يوتيوب ─────────────

    /** مصدر يوتيوب الجاري بثه (قائمة الجودات) أو null لو الفيديو من الهاتف. */
    var yt: YouTubeResolver.Source? = null
        private set
    var ytIndex = -1
        private set
    var preparing = false
        private set
    var prepPct = 0
        private set
    var prepText = ""
        private set

    /** آخر مستوى صوت معروف للتلفاز (-1 = غير معروف). */
    var volume = -1
        private set

    private val prepExec = Executors.newSingleThreadExecutor()
    @Volatile
    private var prepCancel: AtomicBoolean? = null
    private var currentFile: File? = null

    private var videoUri: Uri? = null

    /** رابط صوت الهاتف إن اختلف عن رابط الفيديو (خيار «صورة فقط والصوت من الهاتف»). */
    private var phoneAudioUri: Uri? = null
    private var videoIsHls = false
    private var pendingAudioUri: Uri? = null
    private var progressKey: String? = null

    // متابعة حالة التلفاز الفعلية (إيقاف/استئناف/انتهاء من ريموت التلفاز)
    private var seenPlaying = false
    private var stoppedCount = 0
    private var cmdAt = 0L
    private var lastStateAt = 0L

    @Volatile
    private var pollActive = false
    private var pollThread: Thread? = null
    private var lastSave = 0L

    val prefs by lazy { app.getSharedPreferences("dlna", Context.MODE_PRIVATE) }

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        phoneAudioWanted = prefs.getBoolean("phone_audio", false)
        shakeExit = prefs.getBoolean("shake_exit", false)
        volStop = prefs.getBoolean("vol_stop", false)
        exec.execute { YouTubeMux.clean(app, null) } // بقايا تجهيز سابق
        phoneAudio = PhoneAudio(app).also { pa ->
            pa.onError = {
                toast("تعذّر تشغيل صوت هذا الملف على الهاتف، عاد الصوت إلى التلفاز")
                setPhoneAudio(false)
            }
            pa.onNoSync = { toast("التلفاز لا يبلّغ موضع التشغيل، لذلك المزامنة تقريبية") }
        }
    }

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun notifyState() = listeners.forEach { it.onState() }
    private fun notifyProgress() = listeners.forEach { it.onProgress() }
    private fun toast(msg: String) = main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }

    // ───────────── حفظ موضع المشاهدة ─────────────

    fun progressKey(name: String, size: Long) = "pos_${name}_$size"

    fun savedPosition(key: String?): Int = if (key == null) 0 else prefs.getInt(key, 0)

    private fun saveProgress(sync: Boolean = false) {
        val p = position
        val d = duration
        val e = prefs.edit()
        val k = progressKey
        if (k != null) {
            if (d > 0 && p >= d - 30) e.remove(k)       // أنهى الفيديو تقريبًا: يبدأ من الصفر المرة القادمة
            else if (p >= 15) e.putInt(k, p)
            else e.remove(k)
        }
        // آخر فيديو بُثّ من المتصفح: نحفظ الصفحة والموضع ليُستأنف قبل 5 ثوانٍ من نقطة التوقف
        val page = curPage
        if (page != null && casting) {
            if (d > 0 && p >= d - 20) e.remove(LAST_KEY)
            else e.putString(
                LAST_KEY,
                JSONObject().put("t", title).put("p", page).put("h", currentHeight())
                    .put("pos", p).put("dur", d).put("ts", System.currentTimeMillis()).toString()
            )
        }
        if (sync) e.commit() else e.apply()
        if (casting) recordHistory(p, d, sync)
    }

    /** يسجّل الفيديو الجاري في سجل المشاهدة (المصدر + آخر موضع). */
    private fun recordHistory(p: Int, d: Int, sync: Boolean) {
        try {
            val page = curPage
            if (page != null) {
                val vid = YouTubeResolver.videoId(page)
                val id = if (vid != null) "yt_$vid" else "web_" + page.substringBefore('#').trimEnd('/')
                val done = d > 0 && p >= d - 20
                History.record(id, History.WEB, title, "", page, currentHeight(), if (done) 0 else p, d, done, sync)
            } else {
                val k = progressKey ?: return
                val u = histUri ?: return
                val done = d > 0 && p >= d - 30
                History.record(k, History.LOCAL, title, u, "", 0, if (done) 0 else p, d, done, sync)
            }
        } catch (e: Exception) {
        }
    }

    private fun currentHeight(): Int = yt?.options?.getOrNull(ytIndex)?.height ?: 0

    /** آخر فيديو بُثّ من المتصفح. */
    class LastCast(val title: String, val page: String, val height: Int, val pos: Int, val dur: Int, val ts: Long) {
        /** نقطة الاستئناف: قبل 5 ثوانٍ من لحظة التوقف. */
        val resumeAt: Int get() = maxOf(0, pos - CastSession.RESUME_BACK)
    }

    fun lastCast(): LastCast? {
        val s = prefs.getString(LAST_KEY, null) ?: return null
        return try {
            val o = JSONObject(s)
            LastCast(o.optString("t"), o.getString("p"), o.optInt("h"), o.optInt("pos"), o.optInt("dur"), o.optLong("ts"))
        } catch (e: Exception) {
            null
        }
    }

    fun clearLastCast() = prefs.edit().remove(LAST_KEY).apply()

    private const val LAST_KEY = "last_cast"
    const val RESUME_BACK = 5

    // ───────────── مؤقت النوم ─────────────

    /** لحظة انتهاء مؤقت النوم (elapsedRealtime) أو 0 إن لم يكن مفعّلًا. */
    var sleepAt = 0L
        private set

    private val sleepRun = Runnable {
        sleepAt = 0L
        if (casting) {
            toast("انتهى مؤقت النوم، أُوقف البث")
            stop()
        } else notifyState()
    }

    fun setSleepTimer(minutes: Int) {
        main.removeCallbacks(sleepRun)
        if (minutes <= 0) {
            sleepAt = 0L
        } else {
            sleepAt = SystemClock.elapsedRealtime() + minutes * 60_000L
            main.postDelayed(sleepRun, minutes * 60_000L)
        }
        notifyState()
    }

    /** الدقائق المتبقية على مؤقت النوم (0 = غير مفعّل). */
    fun sleepLeftMin(): Int {
        if (sleepAt == 0L) return 0
        return maxOf(1, ((sleepAt - SystemClock.elapsedRealtime() + 59_999L) / 60_000L).toInt())
    }

    // ───────────── مشترك بين الشاشات ─────────────

    fun setVolStop(on: Boolean) {
        volStop = on
        prefs.edit().putBoolean("vol_stop", on).apply()
        notifyState()
    }

    fun setShakeExit(on: Boolean) {
        shakeExit = on
        prefs.edit().putBoolean("shake_exit", on).apply()
        notifyState()
    }

    /** خروج كامل: يوقف البث ويحفظ مكانك ثم يُنهي الخدمة، فلا يبقى شيء يعمل في الخلفية. */
    fun shutdown() {
        main.removeCallbacks(sleepRun)
        sleepAt = 0L
        prepCancel?.set(true)
        stop(true)
        prefs.edit().commit()
        app.stopService(Intent(app, CastService::class.java))
    }

    // ───────────── البث ─────────────

    fun cast(
        c: Controller, uri: Uri, name: String, key: String?, startAt: Int, mime: String? = null,
        src: YouTubeResolver.Source? = null, srcIndex: Int = -1, file: File? = null,
        headers: Map<String, String> = emptyMap(), hls: Boolean = false, bandwidth: Int = -1,
        extHint: String? = null,
        page: String? = null,
        done: (Boolean) -> Unit
    ) {
        // استبدال بث قائم
        if (casting) {
            saveProgress()
            val old = controller
            if (phoneAudio.active && old != null) exec.execute { old.muteTv(false) }
            phoneAudio.stop()
            stopPolling()
        }
        val oldFile = currentFile
        yt = src
        ytIndex = srcIndex
        curPage = page
        // فيديو الهاتف (لا صفحة ولا مصدر ويب): نحفظ رابط الملف ليُستأنف من سجل المشاهدة
        histUri = if (page == null && src == null) uri.toString() else null
        currentFile = file
        controller = c
        c.speed = "1"
        speed = "1"
        title = name
        videoUri = uri
        phoneAudioUri = pendingAudioUri
        videoIsHls = hls
        pendingAudioUri = null
        progressKey = key
        position = startAt
        duration = 0
        phoneAudio.calibMs = calibFor(c)
        seenPlaying = false
        stoppedCount = 0
        cmdAt = SystemClock.elapsedRealtime()
        ContextCompat.startForegroundService(app, Intent(app, CastService::class.java))

        exec.execute {
            val ok = try {
                val ip = NetUtil.localIp() ?: throw IllegalStateException("no wifi")
                val server = ServerHolder.get(app)
                val remote = uri.scheme == "http" || uri.scheme == "https"
                val ext = extHint ?: if (remote || mime == "video/mp4") "mp4" else extOf(name)
                val mimeType = mime ?: mimeOf(ext)
                val id = server.register(uri, mimeType, headers, hls, bandwidth)
                val url = "http://$ip:${server.listeningPort}/m/$id.$ext"
                // صوت الهاتف يقرأ من خادم الهاتف نفسه كي تُرسل الترويسات ويُعاد كتابة HLS
                if (remote && (headers.isNotEmpty() || hls)) videoUri = Uri.parse(url)
                c.cast(url, name, mimeType)
            } catch (e: Exception) {
                false
            }
            if (ok && startAt > 0) seekWhenReady(c, startAt)
            if (ok) oldFile?.let { if (it != file) it.delete() }
            main.post {
                if (ok) {
                    casting = true
                    playing = true
                    lastSave = SystemClock.elapsedRealtime()
                    saveProgress()
                    if (phoneAudioWanted) applyPhoneAudio(true)
                    startPolling()
                } else {
                    casting = false
                    playing = false
                    yt = null
                    ytIndex = -1
                    file?.delete()
                    currentFile = null
                    app.stopService(Intent(app, CastService::class.java))
                }
                notifyState()
                done(ok)
            }
        }
    }

    /** بعض الأجهزة ترفض التقديم قبل أن يبدأ التشغيل فعلًا، لذلك نعيد المحاولة. */
    private fun seekWhenReady(c: Controller, sec: Int) {
        for (i in 0 until 10) {
            try { Thread.sleep(800) } catch (e: InterruptedException) { return }
            if (c.seek(sec)) {
                try { Thread.sleep(500) } catch (e: InterruptedException) { return }
                val p = c.position()
                if (p == null || p.first >= sec - 3) return
            }
        }
    }

    fun stop(sync: Boolean = false) {
        if (!casting && controller == null) return
        main.removeCallbacks(sleepRun)
        sleepAt = 0L
        saveProgress(sync)
        val c = controller
        val wasPhone = phoneAudio.active
        if (c != null) exec.execute {
            c.stop()
            if (wasPhone) c.muteTv(false)
        }
        stopPolling()
        phoneAudio.stop()
        prepCancel?.set(true)
        casting = false
        playing = false
        position = 0
        duration = 0
        yt = null
        ytIndex = -1
        curPage = null
        histUri = null
        currentFile?.delete()
        currentFile = null
        app.stopService(Intent(app, CastService::class.java))
        notifyState()
    }

    fun play() {
        if (!casting || playing) return
        val c = controller ?: return
        exec.execute { c.play() }
        playing = true
        cmdAt = SystemClock.elapsedRealtime()
        phoneAudio.setPlaying(true)
        notifyState()
    }

    fun pause() {
        if (!casting || !playing) return
        val c = controller ?: return
        exec.execute { c.pause() }
        phoneAudio.setPlaying(false)
        playing = false
        cmdAt = SystemClock.elapsedRealtime()
        saveProgress()
        notifyState()
    }

    fun togglePlay() = if (playing) pause() else play()

    fun seek(sec: Int) {
        if (!casting) return
        val c = controller ?: return
        var t = sec.coerceAtLeast(0)
        if (duration > 0) t = minOf(t, duration)
        position = t
        cmdAt = SystemClock.elapsedRealtime()
        phoneAudio.onUserSeek(t)
        exec.execute { c.seek(t) }
        notifyState()
    }

    fun seekBy(delta: Int) = seek(position + delta)

    fun setVolume(v: Int) {
        val c = controller ?: selected ?: return
        exec.execute { c.setVolume(v) }
    }

    /** يحفظ مستوى الصوت المقروء من التلفاز. */
    fun setKnownVolume(v: Int) {
        volume = v
        notifyState()
    }

    private val volExec = Executors.newSingleThreadExecutor()
    private val volSteps = java.util.concurrent.atomic.AtomicInteger(0)
    private val volRunning = AtomicBoolean(false)
    private var volToast: Toast? = null

    /**
     * رفع/خفض صوت التلفاز: dir = +1 أو -1. الخطوة تُحسب من مدى الصوت الذي يعلنه التلفاز نفسه.
     * الضغطات تُجمَّع وتُرسل على خيط مستقل (لا ينتظر أوامر البث)، فلا تتراكم الطلبات عند الضغط المستمر.
     */
    fun volumeStep(dir: Int, showToast: Boolean = false) {
        val c = controller ?: selected ?: return
        volSteps.addAndGet(dir)
        if (!volRunning.compareAndSet(false, true)) return
        volExec.execute {
            try {
                val max = c.volumeMax()
                val step = (max / 20).coerceAtLeast(1)
                var cur = c.getVolume() ?: (if (volume >= 0) volume else max / 4)
                var failed = false
                while (true) {
                    val d = volSteps.getAndSet(0)
                    if (d == 0) break
                    val sign = if (d > 0) 1 else -1
                    var n = (cur + d * step).coerceIn(0, max)
                    if (n == cur) continue
                    var ok = c.setVolume(n)
                    if (!ok) {
                        // بعض الأجهزة ترفض القفزات الكبيرة: نجرّب خطوات أصغر
                        for (st in intArrayOf(maxOf(1, step / 2), 1)) {
                            n = (cur + sign * st).coerceIn(0, max)
                            if (n == cur) continue
                            ok = c.setVolume(n)
                            if (ok) break
                        }
                    }
                    if (ok) {
                        cur = n
                        val shown = n
                        main.post {
                            volume = shown
                            notifyState()
                            if (showToast) {
                                volToast?.cancel()
                                volToast = Toast.makeText(app, "صوت التلفاز: $shown", Toast.LENGTH_SHORT).also { it.show() }
                            }
                        }
                    } else {
                        failed = true
                    }
                }
                if (failed) toast("التلفاز لا يقبل أوامر الصوت من التطبيق (استعمل ريموت التلفاز)")
            } finally {
                volRunning.set(false)
                if (volSteps.get() != 0) main.post { volumeStep(0, showToast) }
            }
        }
    }

    /** أزرار الصوت في الهاتف: إمّا توقف البث (إن فُعّل «الإيقاف بزر الصوت») أو تتحكم بصوت التلفاز أثناء البث. */
    fun handleVolumeKey(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode != android.view.KeyEvent.KEYCODE_VOLUME_UP && keyCode != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (volStop) {
            if (event.repeatCount > 0) return true // الضغط المستمر بعد الإيقاف لا يغيّر صوت الهاتف
            if (!casting) return false
            stop()
            toast("تم إيقاف البث")
            return true
        }
        if (!casting || phoneAudioWanted || (controller ?: selected) == null) return false
        volumeStep(if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) 1 else -1, true)
        return true
    }

    // ───────────── سرعة التشغيل ─────────────

    /** السرعة الحالية بصيغة UPnP ("1" = عادية). */
    var speed = "1"
        private set

    /** السرعات المتاحة (تُقرأ من التلفاز؛ استدعها من خيط خلفي). */
    fun speedOptions(): List<String> = (controller ?: selected)?.supportedSpeeds() ?: listOf("1")

    fun speedAdvertised(): Boolean = (controller ?: selected)?.speedAdvertised() ?: false

    /** done(true) عند النجاح؛ ويكون الرد على الخيط الرئيسي. */
    fun setSpeed(sp: String, done: (Boolean) -> Unit) {
        val c = controller
        if (!casting || c == null) { done(false); return }
        if (phoneAudioWanted && Controller.speedValue(sp) != 1.0) { done(false); return }
        if (!playing) {
            // لا نستأنف التشغيل: تُطبَّق السرعة عند الضغط على تشغيل
            c.speed = sp
            speed = sp
            notifyState()
            done(true)
            return
        }
        exec.execute {
            val ok = c.setSpeed(sp)
            main.post {
                if (ok) { speed = sp; cmdAt = SystemClock.elapsedRealtime() }
                notifyState()
                done(ok)
            }
        }
    }

    // ───────────── يوتيوب: اختيار الجودة وتجهيزها ─────────────

    /**
     * يجهّز الجودة المختارة (تحميل ودمج إن لزم) ثم يبثها.
     * startAt = -1 يعني «بدّل الجودة أثناء المشاهدة»: يستمر التشغيل الحالي حتى يجهز الجديد ثم يكمل من الموضع نفسه.
     */
    fun castSource(
        c: Controller, src: YouTubeResolver.Source, idx: Int, startAt: Int,
        page: String? = null, done: (Boolean, String?) -> Unit
    ) {
        if (preparing) {
            done(false, "هناك تجهيز قيد التنفيذ")
            return
        }
        val opt = src.options.getOrNull(idx)
        if (opt == null) {
            done(false, "جودة غير صالحة")
            return
        }
        prepPage = page ?: curPage
        val cancel = AtomicBoolean(false)
        prepCancel = cancel
        preparing = true
        prepPct = 0
        prepText = if (opt.muxed) "جارٍ البدء…" else "جارٍ تجهيز ${opt.label}… 0%"
        ContextCompat.startForegroundService(app, Intent(app, CastService::class.java))
        notifyState()
        val keep = currentFile
        // الخيار الإضافي يفعّل صوت الهاتف لهذا البث فقط؛ غيره يتبع إعداد المستخدم المحفوظ
        phoneAudioWanted = opt.phoneAudio || prefs.getBoolean("phone_audio", false)
        pendingAudioUri = if (opt.phoneAudio) opt.audioUrl?.let { Uri.parse(it) } else null

        prepExec.execute {
            var file: File? = null
            var uri: Uri? = null
            var error: String? = null
            try {
                if (opt.muxed) {
                    uri = Uri.parse(opt.videoUrl)
                } else {
                    YouTubeMux.clean(app, keep)
                    file = YouTubeMux.prepare(app, opt, cancel) { pct -> main.post { onPrep(pct, opt.label) } }
                    uri = Uri.fromFile(file)
                }
            } catch (e: YouTubeMux.Cancelled) {
                uri = null
            } catch (e: Throwable) {
                uri = null
                error = e.message ?: "تعذّر تجهيز الفيديو"
            }
            main.post { finishPrep(c, src, idx, startAt, uri, file, error, cancel.get(), done) }
        }
    }

    fun cancelPrepare() {
        val c = prepCancel ?: return
        c.set(true)
        prepText = "جارٍ الإلغاء…"
        notifyState()
    }

    private fun onPrep(pct: Int, label: String) {
        if (!preparing || prepCancel?.get() == true) return
        prepPct = pct
        prepText = if (pct >= 90) "جارٍ دمج الصورة والصوت… $pct%" else "جارٍ تجهيز $label… $pct%"
        notifyState()
    }

    private fun finishPrep(
        c: Controller, src: YouTubeResolver.Source, idx: Int, startAt: Int,
        uri: Uri?, file: File?, error: String?, cancelled: Boolean,
        done: (Boolean, String?) -> Unit
    ) {
        preparing = false
        prepCancel = null
        prepPct = 0

        if (uri == null || cancelled) {
            file?.delete()
            if (!casting) app.stopService(Intent(app, CastService::class.java))
            notifyState()
            done(false, if (cancelled) null else error)
            return
        }

        var at = startAt
        if (startAt < 0) {
            // تبديل جودة أثناء المشاهدة: تأكد أن البث ما زال لنفس الفيديو
            if (!casting || yt?.id != src.id) {
                file?.delete()
                notifyState()
                done(false, null)
                return
            }
            at = maxOf(0, position - 2)
        }

        val o = src.options[idx]
        cast(
            c, uri, src.title, "yt_${src.id}", at, o.mime ?: "video/mp4", src, idx, file,
            o.headers, o.hls, o.bandwidth, o.ext, prepPage
        ) { ok ->
            if (!ok && o.phoneAudio) {
                // التلفاز لم يقبل صورة بلا صوت: نرجع تلقائيًا إلى الخيار العادي (مع التجهيز) لنفس الجودة
                val alt = src.options.indexOfFirst { !it.phoneAudio && it.height == o.height && it.fps == o.fps }
                if (alt >= 0) {
                    castSource(c, src, alt, startAt, prepPage) { ok2, err2 ->
                        done(ok2, if (ok2) "لم يقبل التلفاز الصورة وحدها، فجُهّزت الجودة بالطريقة العادية" else err2)
                    }
                    return@cast
                }
            }
            done(ok, if (ok) null else "فشل البث. تأكد أن التلفاز على نفس الشبكة")
        }
    }

    // ───────────── معايرة المزامنة (لكل تلفاز ولكل مخرج صوت) ─────────────

    /** مخرج الصوت الحالي: سماعة بلوتوث تتأخر عادة عن السماعة السلكية أو سماعة الهاتف. */
    private fun audioRoute(): String {
        return try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            var wired = false
            for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                val t = d.type
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= 31 && (t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_SPEAKER))
                ) return "bt"
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    t == AudioDeviceInfo.TYPE_USB_HEADSET
                ) wired = true
            }
            if (wired) "wired" else "spk"
        } catch (e: Exception) {
            "spk"
        }
    }

    private fun calibKey(c: Controller) = "calib_" + c.r.location + "_" + audioRoute()

    private fun calibFor(c: Controller): Int {
        val k = calibKey(c)
        // نقرأ القيمة القديمة (بلا مخرج) إن لم توجد قيمة جديدة
        return if (prefs.contains(k)) prefs.getInt(k, 0) else prefs.getInt("calib_" + c.r.location, 0)
    }

    /** إزاحة المزامنة الحالية بالميلي ثانية (موجبة = صوت الهاتف أبكر). */
    val calibrationMs: Int get() = phoneAudio.calibMs

    fun routeName(): String = when (audioRoute()) {
        "bt" -> "سماعة بلوتوث"
        "wired" -> "سماعة سلكية"
        else -> "سماعة الهاتف"
    }

    fun adjustCalibration(delta: Int): Int {
        val v = (phoneAudio.calibMs + delta).coerceIn(-2000, 2000)
        phoneAudio.calibMs = v
        controller?.let { prefs.edit().putInt(calibKey(it), v).apply() }
        return v
    }

    fun resetCalibration() {
        phoneAudio.calibMs = 0
        controller?.let { prefs.edit().putInt(calibKey(it), 0).apply() }
    }

    // ───────────── صوت الهاتف ─────────────

    fun setPhoneAudio(on: Boolean) {
        phoneAudioWanted = on
        prefs.edit().putBoolean("phone_audio", on).apply()
        if (casting) applyPhoneAudio(on)
        notifyState()
    }

    private fun applyPhoneAudio(on: Boolean) {
        val c = controller ?: return
        if (on) {
            if (Controller.speedValue(speed) != 1.0) setSpeed("1") {}
            val uri = phoneAudioUri ?: videoUri ?: return
            val remote = uri.scheme == "http" || uri.scheme == "https"
            phoneAudio.calibMs = calibFor(c)
            phoneAudio.start(uri, remote, position, playing, phoneAudioUri == null && videoIsHls)
            if (!phoneAudio.active) return // فشل وعاد الصوت إلى التلفاز
            exec.execute { c.muteTv(true) }
        } else {
            phoneAudio.stop()
            exec.execute { c.muteTv(false) }
        }
        startPolling()
    }

    // ───────────── متابعة التشغيل ─────────────

    private fun startPolling() {
        stopPolling()
        val c = controller ?: return
        pollActive = true
        pollThread = Thread {
            while (pollActive) {
                pollOnce(c)
                try {
                    // مع صوت الهاتف نسأل بسرعة لنلتقط لحظة تغيّر الثانية بدقة
                    Thread.sleep(if (phoneAudio.active) (if (phoneAudio.precise) 350L else 150L) else 1000L)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopPolling() {
        pollActive = false
        pollThread?.interrupt()
        pollThread = null
    }

    /** حالة التلفاز الفعلية: تُصلح الصوت لو أوقفت أو استأنفت من ريموت التلفاز أو انتهى الفيديو. */
    private fun onTransport(st: String) {
        if (!casting) return
        if (SystemClock.elapsedRealtime() - cmdAt < 3500) return // نتجاهل ما قبل أن يستجيب التلفاز لأمرنا
        when (st) {
            "PLAYING" -> {
                seenPlaying = true
                stoppedCount = 0
                if (!playing) {
                    playing = true
                    phoneAudio.setPlaying(true)
                    notifyState()
                }
            }
            "PAUSED_PLAYBACK" -> {
                stoppedCount = 0
                if (playing) {
                    playing = false
                    phoneAudio.setPlaying(false)
                    saveProgress()
                    notifyState()
                }
            }
            "STOPPED", "NO_MEDIA_PRESENT" -> {
                if (seenPlaying && ++stoppedCount >= 2) stop() // انتهى الفيديو أو أُوقف من التلفاز
            }
        }
    }

    private fun now0() = SystemClock.elapsedRealtime()

    private fun pollOnce(c: Controller) {
        val nowS = SystemClock.elapsedRealtime()
        if (nowS - lastStateAt >= 1200) {
            lastStateAt = nowS
            val st = c.transportState()
            if (st != null) main.post { if (pollActive) onTransport(st) }
        }
        val t0 = SystemClock.elapsedRealtime()
        val pd = c.positionDetailed()
        if (pd == null) {
            // التلفاز لم يردّ بالموضع: نخبر مشغّل صوت الهاتف كي لا يبقى صامتًا
            val tn = SystemClock.elapsedRealtime()
            main.post { if (pollActive && casting) phoneAudio.onNoPosition(tn) }
            return
        }
        val t1 = SystemClock.elapsedRealtime()
        val ts = (t0 + t1) / 2
        val rtt = t1 - t0
        main.post {
            if (!pollActive || !casting) return@post
            val durChanged = pd.dur > 0 && pd.dur != duration
            if (pd.dur > 0) duration = pd.dur
            // التلفاز يرجع إلى 0 بعد الإيقاف: لا نُضيّع آخر موضع مشاهدة حقيقي
            val reset = pd.sec == 0 && position > 10 && now0() - cmdAt > 3500
            if (!reset) position = pd.sec
            phoneAudio.onTvPosition(ts, pd.sec, pd.ms, rtt)
            val now = SystemClock.elapsedRealtime()
            if (now - lastSave > 3000) {
                lastSave = now
                saveProgress()
            }
            if (durChanged) notifyState()
            notifyProgress()
        }
    }
}
