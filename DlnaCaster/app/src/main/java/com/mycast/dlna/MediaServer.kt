package com.mycast.dlna

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import fi.iki.elonen.NanoHTTPD
import java.io.FileInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** خادم HTTP داخل الهاتف يقدّم ملف الفيديو للرسيفر مع دعم Range (للتقديم والتأخير). */
class MediaServer(private val ctx: Context) : NanoHTTPD(0) {

    private class Entry(
        val uri: Uri,
        val mime: String,
        val headers: Map<String, String> = emptyMap(),
        val hls: Boolean = false,
        val bandwidth: Int = -1
    ) {
        val counter = AtomicInteger(0)
    }

    /** مورد فرعي (مقطع أو قائمة تشغيل داخل HLS) يجلبه الهاتف بالترويسات الصحيحة. */
    private class Sub(val url: String, val headers: Map<String, String>)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val subs = ConcurrentHashMap<String, Sub>()

    fun register(
        uri: Uri, mime: String,
        headers: Map<String, String> = emptyMap(), hls: Boolean = false, bandwidth: Int = -1
    ): String {
        entries.clear()
        subs.clear()
        val id = UUID.randomUUID().toString().substring(0, 8)
        entries[id] = Entry(uri, mime, headers, hls, bandwidth)
        return id
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri.startsWith("/h/")) {
            return try {
                serveSub(session)
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.toString())
            }
        }
        val id = session.uri.removePrefix("/m/").substringBefore('.')
        val entry = entries[id] ?: return notFound()
        return try {
            if (entry.hls) serveHlsMain(entry, id) else serveMedia(entry, session)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.toString())
        }
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

    private fun addDlnaHeaders(r: Response) {
        r.addHeader("Accept-Ranges", "bytes")
        r.addHeader("transferMode.dlna.org", "Streaming")
        r.addHeader(
            "contentFeatures.dlna.org",
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        )
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun buildRequest(url: String, headers: Map<String, String>, range: String?): Request {
        val rb = Request.Builder().url(url)
        var hasUa = false
        for ((k, v) in headers) {
            if (k.equals("User-Agent", true)) hasUa = true
            rb.header(k, v)
        }
        if (!hasUa) rb.header("User-Agent", OkHttpDownloader.USER_AGENT)
        if (range != null) rb.header("Range", range)
        return rb.build()
    }

    /** طلب إلى الخادم الأصلي مع إعادة المحاولة عند الأعطال المؤقتة (انقطاع، 5xx، 429). */
    private fun upstream(url: String, headers: Map<String, String>, range: String?): okhttp3.Response {
        var last: java.io.IOException? = null
        for (i in 0 until 3) {
            try {
                val r = http.newCall(buildRequest(url, headers, range)).execute()
                if ((r.code in 500..599 || r.code == 429) && i < 2) {
                    r.close()
                    Thread.sleep(250L * (i + 1))
                    continue
                }
                return r
            } catch (e: java.io.IOException) {
                last = e
                if (i < 2) Thread.sleep(250L * (i + 1))
            }
        }
        throw last ?: java.io.IOException("upstream")
    }

    // ───────────── HLS (m3u8) ─────────────

    private val uriAttr = Regex("""URI="([^"]*)"""")
    private val bwAttr = Regex("""[:,]BANDWIDTH=(\d+)""")
    private val frameRateAttr = Regex("""FRAME-RATE=[\d.]+,?""")

    private fun isPlaylistUrl(url: String) =
        url.substringBefore('#').substringBefore('?').lowercase().endsWith(".m3u8")

    private fun registerSub(entryId: String, e: Entry, absUrl: String, headers: Map<String, String>): String {
        val n = e.counter.incrementAndGet()
        subs["$entryId/$n"] = Sub(absUrl, headers)
        return "/h/$entryId/$n"
    }

    private fun resolveUrl(base: String, ref: String): String =
        base.toHttpUrlOrNull()?.resolve(ref)?.toString() ?: ref

    /**
     * يعيد كتابة قائمة HLS بحيث يمرّ كل مقطع ومفتاح عبر خادم الهاتف (فتصل الترويسات والكوكيز
     * التي يشترطها الموقع). إن كان bandwidth >= 0 تُبقى نسخة واحدة فقط من القائمة الرئيسية.
     */
    private fun rewritePlaylist(
        text: String, base: String, entryId: String, e: Entry, headers: Map<String, String>, bandwidth: Int
    ): String {
        val filter = bandwidth >= 0 && bwAttr.findAll(text).any { it.groupValues[1].toIntOrNull() == bandwidth }
        val out = StringBuilder()
        val lines = text.replace("\r", "").split('\n')
        var keepNext = true
        var picked = false
        fun rewriteAttrs(line: String): String = uriAttr.replace(line) { m ->
            val abs = resolveUrl(base, m.groupValues[1])
            "URI=\"" + registerSub(entryId, e, abs, headers) + "\""
        }
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                if (filter) {
                    val bw = bwAttr.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    keepNext = !picked && bw == bandwidth
                    if (keepNext) picked = true
                    if (!keepNext) continue
                }
                // بعض المواقع تعلن معدل إطارات غير واقعي (مثل 100)، فترفضه بعض التلفازات أو تتعلق عنده
                out.append(frameRateAttr.replace(line, "").trimEnd(',')).append('\n')
                continue
            }
            if (line.startsWith("#EXT-X-I-FRAME-STREAM-INF")) {
                if (!filter) out.append(rewriteAttrs(line)).append('\n')
                continue
            }
            if (line.startsWith("#")) {
                out.append(if (line.contains("URI=\"")) rewriteAttrs(line) else line).append('\n')
                continue
            }
            // سطر رابط
            if (filter && !keepNext) {
                keepNext = true
                continue
            }
            out.append(registerSub(entryId, e, resolveUrl(base, line), headers)).append('\n')
        }
        return out.toString()
    }

    private fun serveHlsMain(e: Entry, entryId: String): Response {
        val url = e.uri.toString()
        upstream(url, e.headers, null).use { resp ->
            if (!resp.isSuccessful) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "upstream ${resp.code}")
            }
            val text = resp.body?.string() ?: ""
            val base = resp.request.url.toString()
            val body = rewritePlaylist(text, base, entryId, e, e.headers, e.bandwidth)
            return playlistResponse(body)
        }
    }

    private fun playlistResponse(body: String): Response {
        val r = newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", body)
        r.addHeader("Cache-Control", "no-cache")
        return r
    }

    private fun serveSub(s: IHTTPSession): Response {
        val key = s.uri.removePrefix("/h/")
        val entryId = key.substringBefore('/')
        val sub = subs[key] ?: return notFound()
        val e = entries[entryId] ?: return notFound()
        val resp = upstream(sub.url, sub.headers, s.headers["range"])
        val body = resp.body
        if (body == null || !(resp.code == 200 || resp.code == 206)) {
            resp.close()
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "upstream ${resp.code}")
        }
        val ct = resp.header("Content-Type") ?: ""
        if (isPlaylistUrl(sub.url) || ct.contains("mpegurl", true)) {
            val text = body.string()
            resp.close()
            if (text.trimStart().startsWith("#EXTM3U")) {
                return playlistResponse(
                    rewritePlaylist(text, resp.request.url.toString(), entryId, e, sub.headers, -1)
                )
            }
            return newFixedLengthResponse(Response.Status.OK, ct.ifEmpty { MIME_PLAINTEXT }, text)
        }
        val path = sub.url.substringBefore('?').substringBefore('#').lowercase()
        val mime = when {
            ct.isNotEmpty() && !ct.contains("octet-stream") && !ct.contains("text/plain") -> ct
            path.endsWith(".ts") -> "video/mp2t"
            path.endsWith(".m4s") || path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
            path.endsWith(".aac") -> "audio/aac"
            path.endsWith(".vtt") -> "text/vtt"
            else -> "application/octet-stream"
        }
        val st = if (resp.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val len = body.contentLength()
        val r = if (len >= 0) newFixedLengthResponse(st, mime, body.byteStream(), len)
        else newChunkedResponse(st, mime, body.byteStream())
        resp.header("Content-Range")?.let { r.addHeader("Content-Range", it) }
        r.addHeader("Accept-Ranges", "bytes")
        return r
    }

    /** رابط إنترنت (يوتيوب أو أي موقع): نجلبه نحن ونمرّره للتلفاز مع دعم Range. */
    private fun proxy(e: Entry, s: IHTTPSession): Response {
        val resp = upstream(e.uri.toString(), e.headers, s.headers["range"])
        val body = resp.body
        if (body == null || !(resp.code == 200 || resp.code == 206)) {
            resp.close()
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "upstream ${resp.code}")
        }
        val st = if (resp.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val len = body.contentLength()
        val r = if (len >= 0) newFixedLengthResponse(st, e.mime, body.byteStream(), len)
        else newChunkedResponse(st, e.mime, body.byteStream())
        resp.header("Content-Range")?.let { r.addHeader("Content-Range", it) }
        addDlnaHeaders(r)
        return r
    }

    private fun serveMedia(e: Entry, session: IHTTPSession): Response {
        if (e.uri.scheme == "http" || e.uri.scheme == "https") return proxy(e, session)
        val pfd = ctx.contentResolver.openFileDescriptor(e.uri, "r") ?: return notFound()
        val total = pfd.statSize
        val fis = FileInputStream(pfd.fileDescriptor)

        if (total < 0) {
            val r = newChunkedResponse(Response.Status.OK, e.mime, PfdStream(pfd, fis, Long.MAX_VALUE))
            addDlnaHeaders(r)
            return r
        }

        var start = 0L
        var end = total - 1
        var partial = false
        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val a = spec.substring(0, dash)
                val b = spec.substring(dash + 1)
                try {
                    if (a.isEmpty()) {
                        start = maxOf(0L, total - b.toLong())
                    } else {
                        start = a.toLong()
                        if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1)
                    }
                    partial = true
                } catch (ex: NumberFormatException) {
                    start = 0L
                    end = total - 1
                    partial = false
                }
            }
        }

        if (start >= total || start > end) {
            PfdStream(pfd, fis, 0).close()
            val r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
            r.addHeader("Content-Range", "bytes */$total")
            return r
        }

        fis.channel.position(start)
        val len = end - start + 1
        val r = newFixedLengthResponse(
            if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
            e.mime,
            PfdStream(pfd, fis, len),
            len
        )
        if (partial) r.addHeader("Content-Range", "bytes $start-$end/$total")
        addDlnaHeaders(r)
        return r
    }

    private class PfdStream(
        private val pfd: ParcelFileDescriptor,
        private val fis: FileInputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = fis.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = fis.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun close() {
            try { fis.close() } catch (ex: Exception) { }
            try { pfd.close() } catch (ex: Exception) { }
        }
    }
}

object ServerHolder {
    private var server: MediaServer? = null

    @Synchronized
    fun get(ctx: Context): MediaServer {
        val s = server
        if (s != null && s.isAlive) return s
        val n = MediaServer(ctx.applicationContext)
        n.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        server = n
        return n
    }
}
