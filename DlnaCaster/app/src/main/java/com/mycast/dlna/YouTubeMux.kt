package com.mycast.dlna

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.StatFs
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * يحمّل مسار الصورة ومسار الصوت من يوتيوب بالتوازي ثم يدمجهما في ملف MP4 واحد
 * بدون إعادة ترميز (فلا يرهق المعالج ولا البطارية)، ليقدر التلفاز على تشغيله.
 */
object YouTubeMux {

    class Cancelled : Exception("cancelled")

    /** الخادم لا يدعم تحميل أجزاء (Range): نرجع للتحميل المتتابع. */
    private class NoRange : Exception("no range")

    private const val CHUNK = 6L * 1024 * 1024

    /** حجم الجزء في التحميل المتوازي، وعدد الاتصالات المتزامنة لكل ملف. */
    private const val SEG = 3L * 1024 * 1024
    private const val PAR_VIDEO = 8
    private const val PAR_AUDIO = 3

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private class Dl(val url: String, val file: File) {
        @Volatile var total = YouTubeResolver.clenOf(url)
        @Volatile var done = 0L
    }

    fun dir(ctx: Context): File = File(ctx.cacheDir, "yt").also { it.mkdirs() }

    /** يمسح ملفات التجهيز المؤقتة ما عدا الملف الجاري بثه. */
    fun clean(ctx: Context, keep: File?) {
        dir(ctx).listFiles()?.forEach { f ->
            if (keep == null || f.absolutePath != keep.absolutePath) f.delete()
        }
    }

    /** يرجع ملف MP4 المدمج. onPct تُستدعى بقيم 0..100 من خيط خلفي. */
    fun prepare(ctx: Context, o: YouTubeResolver.Option, cancel: AtomicBoolean, onPct: (Int) -> Unit): File {
        val audioUrl = o.audioUrl ?: throw IllegalStateException("لا يوجد مسار صوت")
        val d = dir(ctx)

        val need = if (o.sizeBytes > 0) o.sizeBytes * 2 + 50_000_000L else 400_000_000L
        val free = StatFs(d.absolutePath).availableBytes
        if (free < need) throw IllegalStateException("مساحة الهاتف غير كافية، تحتاج حوالي ${fmtSize(need)}")

        val stamp = System.currentTimeMillis()
        val v = Dl(o.videoUrl, File(d, "v$stamp.mp4"))
        val a = Dl(audioUrl, File(d, "a$stamp.m4a"))
        val out = File(d, "yt$stamp.mp4")
        val abort = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>(null)

        fun worker(dl: Dl, par: Int): Thread = Thread {
            try {
                download(dl, abort, cancel, par)
            } catch (e: Throwable) {
                error.compareAndSet(null, e)
                abort.set(true)
            }
        }.also {
            it.isDaemon = true
            it.start()
        }

        try {
            val tv = worker(v, PAR_VIDEO)
            val ta = worker(a, PAR_AUDIO)
            var last = -1
            while (tv.isAlive || ta.isAlive) {
                Thread.sleep(250)
                if (cancel.get()) abort.set(true)
                val tot = v.total + a.total
                if (v.total > 0 && a.total > 0 && tot > 0) {
                    val p = ((v.done + a.done) * 90L / tot).toInt().coerceIn(0, 90)
                    if (p != last) {
                        last = p
                        onPct(p)
                    }
                }
            }
            tv.join()
            ta.join()
            error.get()?.let { throw it }
            if (cancel.get()) throw Cancelled()
            onPct(90)
            mux(v.file, a.file, out, cancel) { p -> onPct(90 + p / 10) }
            if (cancel.get()) throw Cancelled()
            onPct(100)
            return out
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            v.file.delete()
            a.file.delete()
        }
    }

    /**
     * تحميل سريع: نقسّم الملف إلى أجزاء ونحمّلها بعدة اتصالات معًا (يوتيوب يحدّ سرعة كل اتصال
     * على حدة، فتتضاعف السرعة الكلية). إن لم يُعرف الحجم أو لم يدعم الخادم Range نرجع للتحميل المتتابع.
     */
    private fun download(d: Dl, abort: AtomicBoolean, cancel: AtomicBoolean, par: Int) {
        if (d.total > SEG) {
            try {
                downloadParallel(d, abort, cancel, par)
                return
            } catch (e: NoRange) {
                d.done = 0
            }
        }
        downloadSeq(d, abort, cancel)
    }

    private fun downloadParallel(d: Dl, abort: AtomicBoolean, cancel: AtomicBoolean, par: Int) {
        val total = d.total
        val stop = AtomicBoolean(false)
        val next = AtomicLong(0)
        val got = AtomicLong(0)
        val err = AtomicReference<Throwable?>(null)
        d.done = 0
        RandomAccessFile(d.file, "rw").use { raf ->
            raf.setLength(total)
            val ch = raf.channel
            val threads = (0 until par).map {
                Thread {
                    try {
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (stop.get() || abort.get() || cancel.get()) throw Cancelled()
                            val start = next.getAndAdd(SEG)
                            if (start >= total) break
                            val end = minOf(start + SEG, total) - 1
                            fetchRange(d.url, start, end, ch, buf, stop, abort, cancel) { n ->
                                d.done = got.addAndGet(n.toLong())
                            }
                        }
                    } catch (e: Throwable) {
                        err.compareAndSet(null, e)
                        stop.set(true)
                    }
                }.also {
                    it.isDaemon = true
                    it.start()
                }
            }
            threads.forEach { it.join() }
        }
        val e = err.get()
        if (e != null) {
            // نفضّل أول خطأ حقيقي على إلغاء ناتج عنه
            throw e
        }
        if (abort.get() || cancel.get()) throw Cancelled()
    }

    private fun fetchRange(
        url: String, start: Long, end: Long, ch: FileChannel, buf: ByteArray,
        stop: AtomicBoolean, abort: AtomicBoolean, cancel: AtomicBoolean, onBytes: (Int) -> Unit
    ) {
        var pos = start
        var fails = 0
        while (pos <= end) {
            if (stop.get() || abort.get() || cancel.get()) throw Cancelled()
            val req = Request.Builder().url(url)
                .header("User-Agent", OkHttpDownloader.USER_AGENT)
                .header("Range", "bytes=$pos-$end")
                .build()
            try {
                http.newCall(req).execute().use { resp ->
                    if (resp.code != 206) {
                        if (resp.code == 200) throw NoRange()
                        throw IOException("HTTP ${resp.code}")
                    }
                    val input = (resp.body ?: throw IOException("رد فارغ")).byteStream()
                    while (pos <= end) {
                        if (stop.get() || abort.get() || cancel.get()) throw Cancelled()
                        val want = minOf(buf.size.toLong(), end - pos + 1).toInt()
                        val n = input.read(buf, 0, want)
                        if (n < 0) break
                        ch.write(ByteBuffer.wrap(buf, 0, n), pos)
                        pos += n
                        onBytes(n)
                    }
                }
                if (pos <= end) throw IOException("انقطع الاتصال")
            } catch (e: Cancelled) {
                throw e
            } catch (e: NoRange) {
                throw e
            } catch (e: IOException) {
                fails++
                if (fails >= 4) throw IOException("فشل التحميل: ${e.message}")
                Thread.sleep(500L * fails)
            }
        }
    }

    private fun downloadSeq(d: Dl, abort: AtomicBoolean, cancel: AtomicBoolean) {
        FileOutputStream(d.file).use { out ->
            val buf = ByteArray(64 * 1024)
            var pos = 0L
            var fails = 0
            var whole = false
            while (!whole && (d.total < 0 || pos < d.total)) {
                if (abort.get() || cancel.get()) throw Cancelled()
                val req = Request.Builder().url(d.url)
                    .header("User-Agent", OkHttpDownloader.USER_AGENT)
                    .header("Range", "bytes=$pos-${pos + CHUNK - 1}")
                    .build()
                try {
                    http.newCall(req).execute().use { resp ->
                        when (resp.code) {
                            206 -> {
                                val t = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                                if (t != null && t > 0) d.total = t
                            }
                            200 -> {
                                if (pos != 0L) throw IOException("الخادم تجاهل التقسيم")
                                val len = resp.body?.contentLength() ?: -1L
                                if (len > 0) d.total = len
                                whole = true
                            }
                            416 -> {
                                if (pos > 0) {
                                    d.total = pos
                                    return@use
                                }
                                throw IOException("HTTP 416")
                            }
                            else -> throw IOException("HTTP ${resp.code}")
                        }
                        val input = (resp.body ?: throw IOException("رد فارغ")).byteStream()
                        while (true) {
                            if (abort.get() || cancel.get()) throw Cancelled()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            pos += n
                            d.done = pos
                        }
                    }
                    fails = 0
                } catch (e: Cancelled) {
                    throw e
                } catch (e: IOException) {
                    fails++
                    if (fails >= 4) throw IOException("فشل التحميل: ${e.message}")
                    Thread.sleep(700L * fails)
                }
            }
        }
    }

    private fun track(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun mux(video: File, audio: File, out: File, cancel: AtomicBoolean, onPct: (Int) -> Unit) {
        val ve = MediaExtractor()
        val ae = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ve.setDataSource(video.absolutePath)
            ae.setDataSource(audio.absolutePath)
            val vt = track(ve, "video/")
            val at = track(ae, "audio/")
            if (vt < 0 || at < 0) throw IOException("الملف المحمَّل لا يحتوي صورة أو صوتًا")
            ve.selectTrack(vt)
            ae.selectTrack(at)
            val vf = ve.getTrackFormat(vt)
            val durUs = if (vf.containsKey(MediaFormat.KEY_DURATION)) vf.getLong(MediaFormat.KEY_DURATION) else 0L

            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val vi = m.addTrack(vf)
            val ai = m.addTrack(ae.getTrackFormat(at))
            m.start()

            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var lastPct = -1
            while (true) {
                if (cancel.get()) throw Cancelled()
                val vTime = ve.sampleTime
                val aTime = ae.sampleTime
                if (vTime < 0 && aTime < 0) break
                val useVideo = vTime >= 0 && (aTime < 0 || vTime <= aTime)
                val ex = if (useVideo) ve else ae
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val sync = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                info.set(0, size, ex.sampleTime, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(if (useVideo) vi else ai, buf, info)
                ex.advance()
                if (useVideo && durUs > 0) {
                    val p = (vTime * 100L / durUs).toInt().coerceIn(0, 100)
                    if (p != lastPct) {
                        lastPct = p
                        onPct(p)
                    }
                }
            }
            m.stop()
        } finally {
            try { muxer?.release() } catch (e: Exception) { }
            ve.release()
            ae.release()
        }
    }
}
