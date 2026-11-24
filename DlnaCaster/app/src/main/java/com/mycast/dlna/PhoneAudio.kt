package com.mycast.dlna

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlin.math.abs

/**
 * يشغّل صوت الفيديو على الهاتف ويزامنه مع موضع التلفاز.
 *
 * الفكرة: التلفاز يبلّغ موضعه بالثواني الكاملة فقط، لكن لحظة تغيّر الرقم (الثانية التالية)
 * تُعرف بدقة حين نسأله بسرعة. كل لحظة تغيّر تضيّق نطاق الخطأ، فنعرف موضع التلفاز الفعلي
 * بدقة عشرات الميلي ثانية، ثم نصحّح صوت الهاتف بتسريع أو إبطاء طفيف بدل القفز.
 *
 * تحديث (إصلاح تأخّر الصوت):
 *  - المشغّل يجهّز الصوت من موضع البث نفسه (بدل البدء من الصفر) فلا ينتظر تحميلًا طويلًا.
 *  - عند الاستئناف يعود الصوت فورًا بالساعة الداخلية، ثم تصحّحه المزامنة حين تثبت.
 *  - لو لم تثبت المزامنة خلال مهلة قصيرة يعمل الصوت بالساعة الداخلية بدل أن يبقى صامتًا
 *    (في كل مرة: عند البدء، بعد التقديم، بعد تعثّر التلفاز) وليس في أول مرة فقط.
 *  - لا قفزات متتالية: لا نقفز إلا إذا كان الفرق كبيرًا فعلًا.
 *
 * كل الدوال تُستدعى من الخيط الرئيسي.
 */
@OptIn(UnstableApi::class)
class PhoneAudio(context: Context) {

    private val appCtx = context.applicationContext
    private var player: ExoPlayer? = null

    @Volatile
    var active = false
        private set

    /** إزاحة المزامنة بالميلي ثانية: موجبة = الصوت أبكر. */
    var calibMs = 0

    var onError: (() -> Unit)? = null
    var onNoSync: (() -> Unit)? = null

    private var prevTs = 0L
    private var prevPos = -1
    private var lastTick = 0L

    // اللحظة (بالميلي ثانية) التي كان فيها موضع التلفاز صفرًا، محصورة بين حدّين
    private var haveBase = false
    private var baseLo = 0L
    private var baseHi = 0L

    private var locked = false
    private var everLocked = false

    /** الصوت يعمل الآن بالساعة الداخلية (لم تثبت المزامنة بعد). */
    private var fallback = false
    private var lastSpeed = 1f

    /** هل التلفاز يشغّل الآن؟ (من أوامرنا ومن حالة التلفاز الفعلية). الصوت لا يعمل إلا حين تكون true. */
    private var wantPlaying = false

    /** المصدر عبر الإنترنت (التقديم فيه بطيء)، فنتسامح مع فرق أكبر ولا نقفز كثيرًا. */
    private var remote = false
    private var lastSeekAt = 0L

    // مهلة انتظار قفل المزامنة قبل تشغيل الصوت بالساعة الداخلية
    private var waitSince = 0L
    private var graceMs = 0L

    // ساعة داخلية تحسب موضع التشغيل (للتلفازات التي لا تبلّغ موضعها)
    private var clockMs = 0L
    private var clockSince = 0L

    private fun log(m: String) {
        try { android.util.Log.d("PhoneAudio", m) } catch (e: Exception) { }
    }

    private fun clockNow(): Long =
        if (clockSince != 0L) clockMs + (SystemClock.elapsedRealtime() - clockSince) else clockMs

    private fun setClock(ms: Long) {
        clockMs = ms
        clockSince = if (wantPlaying) SystemClock.elapsedRealtime() else 0L
    }

    private fun beginWait(grace: Long) {
        waitSince = SystemClock.elapsedRealtime()
        graceMs = grace
    }

    fun start(uri: Uri, isRemote: Boolean, posSec: Int, playing: Boolean, hls: Boolean = false) {
        stop()
        try {
            val load = DefaultLoadControl.Builder()
                // تنزيل مسبق مستمر: للمصادر عبر الإنترنت يواصل الهاتف التنزيل في الخلفية حتى يصبح أمام
                // موضع التشغيل نحو 5 دقائق من الصوت، فلا يتأثر الصوت بتذبذب الشبكة. البدء يبقى سريعًا
                // (عتبة البدء صغيرة)، وبعد أي نفاد يستأنف بعد تجميع بضع ثوانٍ كي لا يتكرر التقطّع.
                // الذاكرة آمنة: الفيديو معطّل فلا يُحفظ منه شيء، والصوت وحده صغير.
                .setBufferDurationsMs(
                    if (isRemote) 240_000 else 15_000,
                    if (isRemote) 300_000 else 60_000,
                    if (isRemote) 900 else 500,
                    if (isRemote) 5_000 else 2_000
                )
                .setBackBuffer(if (isRemote) 30_000 else 0, true)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            val p = ExoPlayer.Builder(appCtx).setLoadControl(load).build()
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
            p.setSeekParameters(SeekParameters.EXACT)
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false
            )
            p.setWakeMode(C.WAKE_MODE_LOCAL)
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    onError?.invoke()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    // انتهى التحميل: نصحّح فورًا ما فاتنا أثناء الانتظار
                    if (state == Player.STATE_READY && (locked || fallback) && wantPlaying) {
                        player?.let { correct(it, true) }
                    }
                }
            })
            // نجهّز الصوت من موضع البث نفسه، فيكون جاهزًا حين نحتاجه
            // HLS: نحدد النوع صراحةً لأن الرابط قد لا ينتهي بـ .m3u8
            val isHls = hls || uri.path?.lowercase()?.endsWith(".m3u8") == true
            val item = MediaItem.Builder().setUri(uri)
                .apply { if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .build()
            p.setMediaItem(item, maxOf(0L, posSec * 1000L + calibMs))
            p.prepare()
            player = p
            active = true
            remote = isRemote
            wantPlaying = playing
            setClock(posSec * 1000L)
            lastSeekAt = 0L
            everLocked = false
            resetPhase()
            prevPos = -1
            fracDistinct = 0
            lastFrac = -1
            resetPrecise()
            beginWait(if (isRemote) START_GRACE_REMOTE else START_GRACE_LOCAL)
        } catch (e: Exception) {
            stop()
            onError?.invoke()
        }
    }

    fun stop() {
        active = false
        wantPlaying = false
        try { player?.release() } catch (e: Exception) { }
        player = null
        resetPhase()
        prevPos = -1
    }

    /** المستخدم (أو التلفاز) أوقف مؤقتًا أو استأنف. */
    fun setPlaying(on: Boolean) {
        if (wantPlaying == on) return
        val now = SystemClock.elapsedRealtime()
        if (on) {
            clockSince = now
        } else {
            if (clockSince != 0L) clockMs += now - clockSince
            clockSince = 0L
        }
        wantPlaying = on
        prevPos = -1 // نبدأ المقارنة من جديد بعد الاستئناف كي لا تُعدّ قفزة
        resetPhase()
        val p = player ?: return
        if (!on) {
            try { p.pause() } catch (e: Exception) { }
            return
        }
        // استئناف: يعود الصوت فورًا بالساعة الداخلية، ثم تصحّحه المزامنة حين تثبت
        try {
            val target = maxOf(0L, clockMs + calibMs)
            if (abs(p.currentPosition - target) > 400) p.seekTo(target)
            p.play()
            fallback = true
            lastSeekAt = now
        } catch (e: Exception) { }
        beginWait(SEEK_GRACE)
    }

    fun pause() = setPlaying(false)

    /** المستخدم قدّم/أخّر الفيديو. */
    fun onUserSeek(sec: Int) {
        setClock(sec * 1000L)
        val p = player ?: return
        resetPhase()
        try {
            p.pause()
            p.seekTo(maxOf(0L, sec * 1000L + calibMs))
        } catch (e: Exception) { }
        prevPos = -1
        beginWait(SEEK_GRACE)
    }

    private fun resetPhase() {
        haveBase = false
        locked = false
        fallback = false
        lastSpeed = 1f
        resetPrecise()
        try { player?.playbackParameters = PlaybackParameters(1f, 1f) } catch (e: Exception) { }
    }

    // ───── الدقة العالية: بعض التلفازات تبلّغ الموضع بالميلي ثانية، فنقيس الإزاحة مباشرة ─────
    private var fracDistinct = 0
    private var lastFrac = -1
    private val bases = ArrayList<Long>()      // لحظة القراءة − موضع التلفاز
    private val baseRtt = ArrayList<Long>()
    private var preciseBase = 0L
    private var preciseOk = false

    /** true حين تعمل المزامنة بالدقة العالية (يخفّف الاستعلام عن التلفاز). */
    val precise: Boolean get() = preciseOk && locked

    private fun resetPrecise() {
        bases.clear()
        baseRtt.clear()
        preciseOk = false
    }

    private fun feedPrecise(ts: Long, rtt: Long, posMs: Long) {
        if (rtt > 600) return // قراءة بطيئة لا يُوثق بتوقيتها
        val b = ts - posMs
        if (bases.isNotEmpty()) {
            val cur = bases.sorted()[bases.size / 2]
            if (abs(b - cur) > 400) { // تعثّر أو قفزة في التلفاز: نبدأ القياس من جديد
                bases.clear()
                baseRtt.clear()
            }
        }
        bases.add(b)
        baseRtt.add(rtt)
        if (bases.size > 12) {
            bases.removeAt(0)
            baseRtt.removeAt(0)
        }
        if (bases.size < 5) {
            preciseOk = false
            return
        }
        // نعتمد على أسرع نصف من القراءات (أقل تأخرًا في الشبكة = أدق)
        val sel = bases.indices.sortedBy { baseRtt[it] }.take((bases.size + 1) / 2).map { bases[it] }.sorted()
        preciseBase = sel[sel.size / 2]
        preciseOk = (sel.last() - sel.first()) <= 160
    }

    /** يُستدعى حين فشلت قراءة موضع التلفاز تمامًا، كي لا يبقى الصوت صامتًا. */
    fun onNoPosition(now: Long) {
        val p = player ?: return
        if (!wantPlaying) return
        if (locked) {
            if (now - lastTick > STALL_MS) {
                log("tv stalled (no position)")
                p.pause()
                resetPhase()
                beginWait(STALL_GRACE)
            }
            return
        }
        checkFallback(p, now)
    }

    /**
     * يُستدعى مع كل قراءة لموضع التلفاز. ts = لحظة القراءة (elapsedRealtime) بالميلي ثانية.
     * posMs = الموضع بالميلي ثانية (أو -1)، وrtt = زمن الذهاب والإياب لطلب القراءة.
     */
    fun onTvPosition(ts: Long, pos: Int, posMs: Long = -1L, rtt: Long = 0L) {
        val p = player ?: return

        if (!wantPlaying) {
            // التلفاز متوقف: لا صوت على الهاتف مهما كانت القراءات
            if (p.isPlaying || p.playWhenReady) p.pause()
            if (locked || haveBase || fallback) resetPhase()
            resetPrecise()
            prevPos = -1
            return
        }

        // قراءة بطيئة جدًا: توقيتها غير موثوق، تجاهلها أفضل من أن تُربك المزامنة
        if (rtt > SLOW_RTT_MS) return

        if (posMs >= 0) {
            val f = (posMs % 1000).toInt()
            if (f != 0 && f != lastFrac) {
                fracDistinct++
                lastFrac = f
            }
        }

        val pp = prevPos
        val pt = prevTs
        prevPos = pos
        prevTs = ts

        if (pp >= 0 && (pos < pp || pos - pp > 3)) {
            // قفزة (تقديم أو تأخير): نعيد القفل
            resetPhase()
            p.pause()
            setClock(pos * 1000L)
            p.seekTo(maxOf(0L, pos * 1000L + calibMs))
            beginWait(SEEK_GRACE)
            return
        }

        // التلفاز يبلّغ بالميلي ثانية: لا ننتظر تغيّر الثانية، نقيس مباشرة
        if (posMs >= 0 && fracDistinct >= 4) {
            feedPrecise(ts, rtt, posMs)
            if (preciseOk) {
                lastTick = ts
                clockMs = posMs
                clockSince = ts
                if (!locked) lockOn(p, ts)
                correct(p, false)
                return
            }
        }

        if (pp < 0) {
            checkFallback(p, ts)
            return
        }

        if (pos == pp) {
            // لا تغيّر: إما بين ثانيتين، أو التلفاز متعثّر
            if (locked && ts - lastTick > STALL_MS) {
                log("tv stalled (position frozen)")
                p.pause()
                resetPhase()
                beginWait(STALL_GRACE)
            } else if (locked) {
                correct(p, false)
            } else {
                checkFallback(p, ts)
            }
            return
        }

        // تغيّر الرقم من pp إلى pos بين القراءتين السابقة والحالية
        // (نقبل قفزة حتى 3 ثوانٍ لأن بعض الأجهزة تبلّغ كل ثانيتين أو أكثر)
        lastTick = ts
        clockMs = pos * 1000L
        clockSince = ts
        val lo = pt - pos * 1000L
        val hi = ts - pos * 1000L
        if (!haveBase || lo > baseHi + 150 || hi < baseLo - 150) {
            baseLo = lo
            baseHi = hi
            haveBase = true
        } else {
            baseLo = maxOf(baseLo - 8, lo)
            baseHi = minOf(baseHi + 8, hi)
            if (baseLo > baseHi) {
                val m = (baseLo + baseHi) / 2
                baseLo = m
                baseHi = m
            }
        }

        if (!locked) lockOn(p, ts)
        correct(p, false)
    }

    /** تثبّتت المزامنة مع التلفاز: نبدأ الصوت (أو نكمله) دون قفزة لا داعي لها. */
    private fun lockOn(p: ExoPlayer, now: Long) {
        locked = true
        everLocked = true
        fallback = false
        val target = maxOf(0L, tvNowMs() + calibMs)
        val st = p.playbackState
        val far = abs(p.currentPosition - target) > (if (remote) 500L else 250L)
        if (far || st == Player.STATE_IDLE || st == Player.STATE_ENDED) {
            lastSeekAt = now
            p.seekTo(target)
        }
        p.play()
    }

    /**
     * لم تثبت المزامنة بعد: إن طالت المهلة نشغّل الصوت بالساعة الداخلية بدل أن يبقى صامتًا،
     * ونصحّحه بالساعة نفسها، وحين تثبت المزامنة الحقيقية تتولّى هي.
     */
    private fun checkFallback(p: ExoPlayer, now: Long) {
        if (locked) return
        if (fallback) {
            correct(p, false)
            return
        }
        if (now - waitSince < graceMs) return
        fallback = true
        log("fallback to internal clock")
        val target = maxOf(0L, clockNow() + calibMs)
        lastSeekAt = now
        if (abs(p.currentPosition - target) > 300) p.seekTo(target)
        p.play()
        if (!everLocked) onNoSync?.invoke()
    }

    private fun tvNowMs(): Long {
        val base = if (preciseOk || !haveBase) preciseBase else (baseLo + baseHi) / 2
        return SystemClock.elapsedRealtime() - base
    }

    /**
     * يصحّح انحراف صوت الهاتف عن التلفاز: قفزة إن كان الفرق كبيرًا، وإلا تسريع/إبطاء طفيف
     * متناسب مع الفرق (بدون تغيّر في طبقة الصوت). force = ما بعد انتهاء التحميل مباشرة.
     */
    private fun correct(p: ExoPlayer, force: Boolean) {
        if (p.playbackState != Player.STATE_READY || !p.isPlaying) return
        val ref = if (locked) tvNowMs() else clockNow()
        val target = maxOf(0L, ref + calibMs)
        val drift = p.currentPosition - target // موجب = الهاتف متقدم
        val ad = abs(drift)
        val now = SystemClock.elapsedRealtime()
        // نفضّل التسريع/الإبطاء الطفيف على القفز (القفز هو ما تسمعه كتقطّع)
        val seekLimit = if (remote) 1200L else 400L
        val cooldown = if (remote) 6000L else 3000L
        val maxAdj = if (remote) 0.08f else 0.06f
        if (ad > seekLimit) {
            if (force || now - lastSeekAt >= cooldown) {
                log("seek drift=${drift}ms force=$force")
                lastSeekAt = now
                p.seekTo(target)
                setSpeed(p, 1f)
                return
            }
            // أثناء فترة الانتظار نصحّح بأقصى تسريع مسموح بدل القفز المتكرر
            setSpeed(p, 1f - (if (drift > 0) 1f else -1f) * maxAdj)
            return
        }
        val dead = if (lastSpeed == 1f) 45L else 15L
        val speed = if (ad < dead) 1f
        else 1f - (drift.coerceIn(-200L, 200L) / 200f) * maxAdj
        setSpeed(p, speed)
    }

    private fun setSpeed(p: ExoPlayer, speed: Float) {
        if (abs(speed - lastSpeed) < 0.003f) return
        lastSpeed = speed
        try { p.playbackParameters = PlaybackParameters(speed, 1f) } catch (e: Exception) { }
    }

    private companion object {
        /** لو لم يتحرك موضع التلفاز هذه المدة اعتبرناه متعثرًا وأوقفنا الصوت مؤقتًا. */
        const val STALL_MS = 7000L

        /** مهل انتظار المزامنة قبل تشغيل الصوت بالساعة الداخلية. */
        const val START_GRACE_LOCAL = 2500L
        const val START_GRACE_REMOTE = 3500L
        const val SEEK_GRACE = 1500L
        const val STALL_GRACE = 3000L

        /** قراءات موضع التلفاز الأبطأ من هذا (ميلي ثانية) تُهمَل. */
        const val SLOW_RTT_MS = 900L
    }
}
