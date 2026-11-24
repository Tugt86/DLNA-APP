package com.mycast.dlna

import android.media.MediaMetadataRetriever
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * يحوّل ما كشفه المتصفح (VideoSniffer) في أي موقع إلى قائمة خيارات صالحة للبث.
 * يعمل في خيط خلفي. المراحل:
 *  1) جمع المرشّحين: ما كشفه المتصفح + (إن لم يوجد شيء) قراءة HTML الصفحة والإطارات بنفسه.
 *  2) فحص كل مرشّح فعليًا (طلب Range صغير): نوع الملف الحقيقي من الترويسة والبايتات الأولى وحجمه،
 *     واستبعاد صفحات HTML والبيانات الوهمية والمقاطع الصغيرة.
 *  3) HLS: قراءة القائمة، استخراج الجودات، ورفض ما فيه حماية DRM.
 *  4) إطارات يوتيوب المضمّنة تمرّ على مستخرج يوتيوب نفسه.
 */
object WebVideoResolver {

    class Env(
        val pageUrl: String,
        val title: String,
        val ua: String,
        val cookies: (String) -> String?
    )

    private class Cand(val url: String, var kind: String, var referer: String?, var q: Int = 0) {
        val rank: Int get() = VideoSniffer.rankOf(kind)
    }

    private class Variant(val bandwidth: Int, val height: Int, val fps: Int)

    private class Probed(
        val cand: Cand,
        val hls: Boolean,
        val mime: String?,
        val ext: String?,
        val size: Long,
        val variants: List<Variant>,
        val live: Boolean,
        val height: Int = 0
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile private var protectedSeen = false
    @Volatile private var dashSeen = false

    // ───────────── الترويسات ─────────────

    private fun originOf(url: String): String? {
        val h = url.toHttpUrlOrNull() ?: return null
        return h.scheme + "://" + h.host + (if (h.port != defaultPortOf(h.scheme)) ":${h.port}" else "")
    }

    private fun defaultPortOf(scheme: String) = if (scheme == "https") 443 else 80

    private fun headersFor(env: Env, url: String, referer: String?, withOrigin: Boolean): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["User-Agent"] = env.ua
        val ref = referer ?: env.pageUrl
        m["Referer"] = ref
        if (withOrigin) originOf(ref)?.let { m["Origin"] = it }
        try {
            env.cookies(url)?.takeIf { it.isNotEmpty() }?.let { m["Cookie"] = it }
        } catch (e: Exception) {
        }
        return m
    }

    private fun req(url: String, headers: Map<String, String>, range: String?): Request {
        val b = Request.Builder().url(url)
        for ((k, v) in headers) b.header(k, v)
        if (range != null) b.header("Range", range)
        return b.build()
    }

    // ───────────── قراءة HTML الصفحة (احتياط إن لم يكشف المتصفح شيئًا) ─────────────

    private val urlRe = Regex(
        """https?://[^\s"'<>\\()]+?\.(?:mp4|m4v|webm|mkv|mov|flv|m3u8|mpd|ogv)(?:\?[^\s"'<>\\()]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val tagSrcRe = Regex("""<(?:video|source)\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val iframeRe = Regex("""<iframe\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ytRe = Regex("""(?:youtube(?:-nocookie)?\.com/(?:embed|v)/|youtu\.be/)([A-Za-z0-9_-]{11})""")

    private fun normalizeText(t: String): String =
        t.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")

    private fun fetchHtml(env: Env, url: String, referer: String?): String? = try {
        http.newCall(req(url, headersFor(env, url, referer, false), null)).execute().use { r ->
            if (!r.isSuccessful) null
            else {
                val ct = r.header("Content-Type") ?: ""
                if (ct.isNotEmpty() && !ct.contains("html", true) && !ct.contains("xml", true) &&
                    !ct.contains("json", true) && !ct.contains("text", true)
                ) null
                else readLimited(r.body?.byteStream(), 3_000_000)
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun readLimited(s: InputStream?, max: Int): String? {
        if (s == null) return null
        s.use {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16384)
            while (out.size() < max) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        }
    }

    private val tagRe = Regex("""<(?:video|source)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val srcAttr = Regex("""\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val qAttr = Regex(
        """\b(?:size|label|title|res|data-res|data-quality)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE
    )
    private val keyUrlRe = Regex(
        """["'](?:file|src|source|url|video_url|videoUrl|download|link|stream|hls|mp4)["']\s*[:=]\s*["'](https?://[^"'\s<>\\]+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val junkRe = Regex(
        """\.(?:jpe?g|png|gif|webp|svg|css|js|json|html?|ico|woff2?|ttf|vtt|srt|php|xml|txt)(?:[?#]|$)""",
        RegexOption.IGNORE_CASE
    )

    private fun scanHtml(html: String, base: String, add: (String, String, String?, Int) -> Unit) {
        val t = normalizeText(html)
        for (m in tagRe.findAll(t)) {
            val src = srcAttr.find(m.value)?.groupValues?.get(1) ?: continue
            val u = resolve(base, src)
            val q = qAttr.findAll(m.value).map { QHint.fromText(it.groupValues[1]) }.firstOrNull { it > 0 }
                ?: QHint.fromUrl(u)
            add(u, "meta", base, q)
        }
        var n = 0
        for (m in urlRe.findAll(t)) {
            if (n++ > 40) break
            val q = QHint.context(t, m.range.first, m.value.length).takeIf { it > 0 } ?: QHint.fromUrl(m.value)
            add(m.value, "page", base, q)
        }
        // روابط بلا امتداد تأتي داخل JSON (file / src / url ...): يتحقق منها الفحص الفعلي لاحقًا
        var k = 0
        for (m in keyUrlRe.findAll(t)) {
            if (k++ > 20) break
            val u = m.groupValues[1]
            if (junkRe.containsMatchIn(u) || VideoSniffer.isNoise(u)) continue
            val q = QHint.context(t, m.range.first, m.value.length).takeIf { it > 0 } ?: QHint.fromUrl(u)
            add(u, "page", base, q)
        }
        for (m in ytRe.findAll(t)) add("https://www.youtube.com/watch?v=" + m.groupValues[1], "yt", base, 0)
    }

    private fun resolve(base: String, ref: String): String =
        base.toHttpUrlOrNull()?.resolve(ref)?.toString() ?: ref

    private fun frameScore(src: String, page: String): Int {
        var sc = 0
        val l = src.lowercase()
        if (Regex("player|embed|video|stream|vid|play|watch|cdn").containsMatchIn(l)) sc += 2
        if (hostOf(src) != hostOf(page)) sc += 1
        return sc
    }

    private fun collectFromPage(env: Env, add: (String, String, String?, Int) -> Unit) {
        val html = fetchHtml(env, env.pageUrl, null) ?: return
        scanHtml(html, env.pageUrl, add)
        // إطارات الصفحة (مشغّلات مضمّنة): مستوى واحد، الأرجح أولًا، وتُجلب بالتوازي
        val frames = iframeRe.findAll(html)
            .map { resolve(env.pageUrl, it.groupValues[1].replace("&amp;", "&")) }
            .filter { (it.startsWith("http://") || it.startsWith("https://")) && !VideoSniffer.isNoise(it) && !it.contains("youtube") }
            .distinct()
            .sortedByDescending { frameScore(it, env.pageUrl) }
            .take(5)
            .toList()
        if (frames.isEmpty()) return
        val pool = Executors.newFixedThreadPool(frames.size)
        try {
            val futures = pool.invokeAll(frames.map { f -> Callable<String?> { fetchHtml(env, f, env.pageUrl) } }, 15, TimeUnit.SECONDS)
            for ((idx, fu) in futures.withIndex()) {
                if (fu.isCancelled) continue
                val h = try { fu.get() } catch (e: Exception) { null } ?: continue
                scanHtml(h, frames[idx], add)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private class PageHit(val url: String, val kind: String, val ref: String?, val q: Int)
    private class PageEntry(val hits: List<PageHit>, val at: Long)

    private val pageCache = ConcurrentHashMap<String, PageEntry>()

    /** روابط الفيديو من HTML الصفحة (مع تخزين مؤقت قصير كي لا تتكرر الطلبات). */
    private fun pageHits(env: Env): List<PageHit> {
        val now = System.currentTimeMillis()
        pageCache[env.pageUrl]?.let { if (now - it.at < 3 * 60_000L) return it.hits }
        val out = ArrayList<PageHit>()
        try {
            collectFromPage(env) { u, k, r, q -> out.add(PageHit(u, k, r, q)) }
        } catch (e: Exception) {
        }
        if (pageCache.size > 30) pageCache.clear()
        pageCache[env.pageUrl] = PageEntry(out, now)
        return out
    }

    // ───────────── فحص المرشّحين ─────────────

    private fun isHlsUrl(u: String) = u.substringBefore('#').substringBefore('?').lowercase().endsWith(".m3u8") ||
        u.lowercase().contains(".m3u8")

    private fun isDashUrl(u: String) = u.substringBefore('#').substringBefore('?').lowercase().endsWith(".mpd")

    private fun b(x: ByteArray, i: Int) = x[i].toInt() and 0xFF

    private fun sniffType(x: ByteArray, n: Int): String? {
        if (n >= 7 && String(x, 0, 7, Charsets.ISO_8859_1) == "#EXTM3U") return "hls"
        if (n >= 12 && b(x, 4) == 0x66 && b(x, 5) == 0x74 && b(x, 6) == 0x79 && b(x, 7) == 0x70) return "mp4"
        if (n >= 4 && b(x, 0) == 0x1A && b(x, 1) == 0x45 && b(x, 2) == 0xDF && b(x, 3) == 0xA3) return "mkv"
        if (n >= 3 && b(x, 0) == 0x46 && b(x, 1) == 0x4C && b(x, 2) == 0x56) return "flv"
        if (n >= 4 && b(x, 0) == 0x4F && b(x, 1) == 0x67 && b(x, 2) == 0x67 && b(x, 3) == 0x53) return "ogg"
        if (n >= 189 && b(x, 0) == 0x47 && b(x, 188) == 0x47) return "ts"
        val s = String(x, 0, minOf(n, 64), Charsets.ISO_8859_1).trimStart().lowercase()
        if (s.startsWith("<") || s.startsWith("{") || s.startsWith("[")) return "text"
        return null
    }

    private fun mimeForExt(url: String): Pair<String, String>? {
        val p = url.substringBefore('#').substringBefore('?').lowercase()
        return when {
            p.endsWith(".mp4") || p.endsWith(".m4v") -> "video/mp4" to "mp4"
            p.endsWith(".webm") -> "video/webm" to "webm"
            p.endsWith(".mkv") -> "video/x-matroska" to "mkv"
            p.endsWith(".mov") -> "video/quicktime" to "mov"
            p.endsWith(".flv") -> "video/x-flv" to "flv"
            p.endsWith(".ogv") -> "video/ogg" to "ogv"
            p.endsWith(".3gp") -> "video/3gpp" to "3gp"
            else -> null
        }
    }

    private fun extForMime(m: String) = when {
        m.contains("webm") -> "webm"
        m.contains("matroska") -> "mkv"
        m.contains("quicktime") -> "mov"
        m.contains("flv") -> "flv"
        m.contains("ogg") -> "ogv"
        m.contains("mp2t") -> "ts"
        m.contains("3gpp") -> "3gp"
        else -> "mp4"
    }

    private fun readHead(s: InputStream?, max: Int): Pair<ByteArray, Int> {
        val buf = ByteArray(max)
        var n = 0
        if (s != null) s.use {
            while (n < max) {
                val r = it.read(buf, n, max - n)
                if (r < 0) break
                n += r
            }
        }
        return buf to n
    }

    private fun probeRaw(env: Env, c: Cand): Probed? {
        try {
            if (isHlsUrl(c.url)) return probeHls(env, c)
            val hdr = headersFor(env, c.url, c.referer, false)
            http.newCall(req(c.url, hdr, "bytes=0-2047")).execute().use { r ->
                if (!r.isSuccessful) return if (c.kind == "video") Probed(c, false, null, null, -1, emptyList(), false) else null
                val ct = (r.header("Content-Type") ?: "").lowercase().substringBefore(';').trim()
                val (head, n) = readHead(r.body?.byteStream(), 2048)
                val sniff = sniffType(head, n)
                if (sniff == "text") return null
                if (sniff == "hls" || ct.contains("mpegurl")) return probeHls(env, c)

                val total = r.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
                    ?: if (r.code == 200) r.header("Content-Length")?.toLongOrNull() ?: -1L else -1L

                val extPair = mimeForExt(c.url)
                val mime: String = when {
                    ct.startsWith("video/") -> ct
                    sniff == "mp4" -> "video/mp4"
                    sniff == "mkv" -> if (c.url.lowercase().contains(".webm")) "video/webm" else "video/x-matroska"
                    sniff == "flv" -> "video/x-flv"
                    sniff == "ogg" -> "video/ogg"
                    sniff == "ts" -> "video/mp2t"
                    extPair != null && (ct.isEmpty() || ct.contains("octet-stream")) -> extPair.first
                    c.kind == "video" -> "video/mp4"
                    else -> return null
                }
                // مقاطع صغيرة (إعلان/مقدمة/مقطع جزئي) لا تُعرض إلا إن كانت من مشغّل الصفحة نفسه
                if (c.kind != "video" && total >= 0 && total <= 200_000L) return null
                return Probed(c, false, mime, extForMime(mime), total, emptyList(), false)
            }
        } catch (e: Exception) {
            return if (c.kind == "video") Probed(c, false, "video/mp4", "mp4", -1, emptyList(), false) else null
        }
    }

    /** ارتفاع الفيديو الحقيقي من الملف نفسه عند غياب أي إشارة للجودة (بمهلة قصيرة). */
    private fun probeHeight(url: String, headers: Map<String, String>): Int {
        var h = 0
        val t = Thread {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(url, HashMap(headers))
                val hh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val ww = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                h = if (rot == 90 || rot == 270) ww else hh
            } catch (e: Exception) {
            } finally {
                try { r.release() } catch (e: Exception) { }
            }
        }
        t.isDaemon = true
        t.start()
        try { t.join(7000) } catch (e: InterruptedException) { }
        return h
    }

    private fun probe(env: Env, c: Cand): Probed? {
        val p = probeRaw(env, c) ?: return null
        if (p.hls) return p
        var h = if (c.q > 0) c.q else QHint.fromUrl(c.url)
        if (h == 0 && p.size != 0L && p.mime?.startsWith("video/") == true) {
            h = probeHeight(c.url, headersFor(env, c.url, c.referer, false))
        }
        return if (h == p.height) p else Probed(p.cand, false, p.mime, p.ext, p.size, p.variants, p.live, h)
    }

    private class CacheEntry(val p: Probed?, val at: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private const val OK_TTL = 5 * 60_000L
    private const val BAD_TTL = 45_000L

    /** الفحص مع تخزين مؤقت: ما فُحص مسبقًا (في الخلفية أو بضغطة سابقة) لا يُفحص ثانية. */
    private fun probeCached(env: Env, c: Cand): Probed? {
        val now = System.currentTimeMillis()
        val hit = cache[c.url]
        if (hit != null && now - hit.at < (if (hit.p != null) OK_TTL else BAD_TTL)) {
            val o = hit.p ?: return null
            return Probed(c, o.hls, o.mime, o.ext, o.size, o.variants, o.live, o.height)
        }
        val r = probe(env, c)
        if (cache.size > 200) cache.clear()
        cache[c.url] = CacheEntry(r, now)
        return r
    }

    private val warming = AtomicBoolean(false)

    /** يفحص في الخلفية ما كشفه المتصفح مبكرًا، فيصير زر البث فوريًا تقريبًا. */
    fun prewarm(env: Env, found: List<VideoSniffer.Found>) {
        if (!warming.compareAndSet(false, true)) return
        try {
            val strong = found.filter { it.kind != "yt" && it.kind != "blob" && it.rank <= 2 && !isDashUrl(it.url) }
            if (strong.size < 3 && strong.map { it.q }.filter { it > 0 }.toSet().size < 2) pageHits(env)
            val cs = strong.sortedBy { it.rank }.take(8).map { Cand(it.url, it.kind, it.referer, it.q) }
            if (cs.isEmpty()) return
            val pool = Executors.newFixedThreadPool(3)
            try {
                pool.invokeAll(cs.map { c -> Callable<Probed?> { probeCached(env, c) } }, 20, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }
        } catch (e: Exception) {
        } finally {
            warming.set(false)
        }
    }

    private val streamInf = Regex("""#EXT-X-STREAM-INF:(.*)""")
    private val bwRe = Regex("""(?:^|[,:])BANDWIDTH=(\d+)""")
    private val resRe = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val fpsRe = Regex("""FRAME-RATE=([\d.]+)""")

    private fun probeHls(env: Env, c: Cand): Probed? {
        val hdr = headersFor(env, c.url, c.referer, true)
        val text = http.newCall(req(c.url, hdr, null)).execute().use { r ->
            if (!r.isSuccessful) return null
            readLimited(r.body?.byteStream(), 1_500_000)
        } ?: return null
        if (!text.trimStart().startsWith("#EXTM3U")) return null

        if (isProtectedHls(text)) {
            protectedSeen = true
            return null
        }
        val variants = ArrayList<Variant>()
        for (m in streamInf.findAll(text)) {
            val attrs = m.groupValues[1]
            val bw = bwRe.find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val res = resRe.find(attrs)
            val h = res?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val fps = fpsRe.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt() ?: 30
            variants.add(Variant(bw, h, fps))
        }
        val isMaster = variants.isNotEmpty()
        val live = !isMaster && !text.contains("#EXT-X-ENDLIST")
        return Probed(c, true, "application/vnd.apple.mpegurl", "m3u8", -1, variants, live)
    }

    private fun isProtectedHls(t: String): Boolean {
        val keyLines = t.lineSequence().filter { it.startsWith("#EXT-X-KEY") || it.startsWith("#EXT-X-SESSION-KEY") }
        for (l in keyLines) {
            val u = l.uppercase()
            if (u.contains("SAMPLE-AES") || u.contains("WIDEVINE") || u.contains("PLAYREADY") ||
                u.contains("FAIRPLAY") || u.contains("STREAMINGKEYDELIVERY") || u.contains("EDEF8BA9")
            ) return true
        }
        return false
    }

    // ───────────── التجميع ─────────────

    private fun hostOf(url: String) = url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: "?"

    fun resolve(env: Env, found: List<VideoSniffer.Found>): YouTubeResolver.Source {
        protectedSeen = false
        dashSeen = false

        val cands = LinkedHashMap<String, Cand>()
        fun addC(url: String, kind: String, ref: String?, q: Int) {
            if (!(url.startsWith("http://") || url.startsWith("https://"))) return
            val old = cands[url]
            if (old == null) cands[url] = Cand(url, kind, ref, q)
            else {
                if (VideoSniffer.rankOf(kind) < old.rank) {
                    old.kind = kind
                    if (ref != null) old.referer = ref
                }
                if (q > old.q) old.q = q
            }
        }
        for (f in found) addC(f.url, f.kind, f.referer, f.q)

        // ما كشفه المتصفح قد يكون الجودة الجارية فقط: نقرأ الصفحة أيضًا لنعرض بقية الجودات والسيرفرات
        val strong = cands.values.filter { it.kind != "page" && it.kind != "link" }
        val knownHeights = strong.map { it.q }.filter { it > 0 }.toSet()
        if (strong.isEmpty() || (strong.size < 3 && knownHeights.size < 2)) {
            for (h in pageHits(env)) addC(h.url, h.kind, h.ref, h.q)
        }

        val ytUrls = cands.values.filter { it.kind == "yt" }.map { it.url }.distinct().take(2)
        val direct = ArrayList<Cand>()
        for (c in cands.values.sortedBy { it.rank }) {
            if (c.kind == "yt") continue
            if (isDashUrl(c.url)) {
                dashSeen = true
                continue
            }
            direct.add(c)
            if (direct.size >= 14) break
        }

        // فحص متوازٍ
        val pool = Executors.newFixedThreadPool(6)
        val probed = ArrayList<Probed>()
        try {
            val futures = pool.invokeAll(direct.map { c -> Callable<Probed?> { probeCached(env, c) } }, 22, TimeUnit.SECONDS)
            for (f in futures) {
                if (f.isCancelled) continue
                try {
                    f.get()?.let { probed.add(it) }
                } catch (e: Exception) {
                }
            }
        } finally {
            pool.shutdownNow()
        }
        // ترتيب ثابت: حسب الأفضلية ثم حسب ترتيب الكشف
        probed.sortBy { it.cand.rank }

        val options = ArrayList<YouTubeResolver.Option>()
        var n = 0
        for (p in probed) {
            n++
            val host = hostOf(p.cand.url)
            val hdr = headersFor(env, p.cand.url, p.cand.referer, p.hls)
            if (!p.hls) {
                val fmt = (p.ext ?: "mp4").uppercase()
                val q = if (p.height > 0) "${p.height}p" else "فيديو $n"
                options.add(
                    YouTubeResolver.Option(
                        p.height, 30, p.cand.url, null, p.size,
                        custom = "$q · $fmt · $host",
                        mime = p.mime, ext = p.ext, headers = hdr
                    )
                )
            } else {
                val live = if (p.live) " · مباشر" else ""
                if (p.variants.size > 1) {
                    options.add(
                        YouTubeResolver.Option(
                            0, 30, p.cand.url, null, -1,
                            custom = "تلقائي · HLS$live · $host",
                            mime = p.mime, ext = "m3u8", headers = hdr, hls = true, bandwidth = -1
                        )
                    )
                    // أعلى نطاق لكل ارتفاع
                    val best = p.variants.groupBy { if (it.height > 0) it.height else -it.bandwidth }
                        .map { e -> e.value.maxByOrNull { it.bandwidth }!! }
                        .sortedWith(compareByDescending<Variant> { it.height }.thenByDescending { it.bandwidth })
                        .take(8)
                    for (v in best) {
                        val q = if (v.height > 0) "${v.height}p" + (if (v.fps > 30) "${v.fps}" else "")
                        else "${v.bandwidth / 1000} kbps"
                        options.add(
                            YouTubeResolver.Option(
                                v.height, v.fps, p.cand.url, null, -1,
                                custom = "$q · HLS · $host",
                                mime = p.mime, ext = "m3u8", headers = hdr, hls = true, bandwidth = v.bandwidth
                            )
                        )
                    }
                } else {
                    val v = p.variants.firstOrNull()
                    val h = v?.height?.takeIf { it > 0 } ?: p.cand.q.takeIf { it > 0 } ?: QHint.fromUrl(p.cand.url)
                    val q = if (h > 0) "${h}p" else "فيديو $n"
                    options.add(
                        YouTubeResolver.Option(
                            h, v?.fps ?: 30, p.cand.url, null, -1,
                            custom = "$q · HLS$live · $host",
                            mime = p.mime, ext = "m3u8", headers = hdr, hls = true,
                            bandwidth = v?.bandwidth ?: -1
                        )
                    )
                }
            }
        }

        // فيديوهات يوتيوب المضمّنة في الصفحة
        for (u in ytUrls) {
            try {
                val ys = YouTubeResolver.load(u)
                n++
                for (o in ys.options) {
                    if (o.phoneAudio) continue
                    options.add(
                        YouTubeResolver.Option(
                            o.height, o.fps, o.videoUrl, o.audioUrl, o.sizeBytes,
                            custom = "فيديو $n · يوتيوب ${o.label}"
                        )
                    )
                }
            } catch (e: Exception) {
            }
        }

        if (options.isEmpty()) {
            throw IllegalStateException(
                when {
                    protectedSeen -> "الفيديو محمي (DRM) ولا يمكن بثه"
                    dashSeen -> "الموقع يستخدم بثًا بصيغة DASH لا يدعمها البث عبر DLNA"
                    VideoSniffer.blobSeen -> "الموقع يشغّل الفيديو بتقنية مجزّأة (blob). شغّل الفيديو ثوانيَ ثم اضغط الزر مرة أخرى"
                    else -> "لم يُعثر على فيديو في هذه الصفحة. شغّل الفيديو ثم اضغط الزر مرة أخرى"
                }
            )
        }
        val id = "w" + Integer.toHexString(env.pageUrl.hashCode())
        val title = env.title.ifBlank { hostOf(env.pageUrl) }
        // الأعلى جودة أولًا، وعند التساوي الملف المباشر قبل HLS (أسرع وأضمن على التلفاز)، ثم الأكبر حجمًا
        options.sortWith(
            compareByDescending<YouTubeResolver.Option> { it.height }
                .thenBy { if (it.hls) 1 else 0 }
                .thenByDescending { it.sizeBytes }
        )
        // نحذف التكرار (نفس الملف بروابط مختلفة الرموز)
        val keys = HashSet<String>()
        val unique = options.filter { o ->
            val key = if (o.sizeBytes > 0) "${o.height}|${o.sizeBytes}|${hostOf(o.videoUrl)}|${o.hls}"
            else o.videoUrl.substringBefore('?') + "|" + o.bandwidth
            keys.add(key)
        }
        return YouTubeResolver.Source(id, title, unique.take(24))
    }
}
