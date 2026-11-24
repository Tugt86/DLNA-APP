package com.mycast.dlna

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mlm.adblock.AdblockEngine
import org.mlm.adblock.RequestTypeMapper
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * مانع إعلانات يعتمد على محرك Brave الحقيقي (adblock-rust)
 * عبر الغلاف: io.github.mlm-games:adblock
 *
 * - قوائم: EasyList + EasyPrivacy (تُحمَّل وتُحدَّث تلقائيًا)
 * - حظر الشبكة + قواعد تجميلية عند التفعيل
 * - لا يحظر وسائط الفيديو/الصوت / m3u8 / googlevideo
 * - إن فشل المحرك يُستخدم احتياطي نطاقات
 */
object AdBlocker {

    private const val TAG = "AdBlocker"
    private const val PREF_STRICT = "adblock_strict"
    private const val PREF_LISTS_TS = "adblock_lists_ts"
    private const val CACHE_BIN = "adblock_engine.bin"
    private const val CACHE_LISTS = "adblock_lists.txt"
    private const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000

    private val LIST_URLS = listOf(
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt"
    )

    private var prefs: SharedPreferences? = null
    private var appCtx: Context? = null
    private var engine: AdblockEngine? = null
    private val ready = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)
    private val bg = Executors.newSingleThreadExecutor()
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    var strict: Boolean
        get() = prefs?.getBoolean(PREF_STRICT, false) ?: false
        set(v) {
            prefs?.edit()?.putBoolean(PREF_STRICT, v)?.apply()
            if (v) ensureEngineAsync()
        }

    fun init(context: Context, p: SharedPreferences) {
        appCtx = context.applicationContext
        prefs = p
        if (strict) ensureEngineAsync()
    }

    fun init(p: SharedPreferences) {
        prefs = p
    }

    fun clearCache() {}

    fun isReady(): Boolean = ready.get()

    fun shouldBlock(url: String?, isMainFrame: Boolean = false): Boolean {
        if (!strict || url.isNullOrBlank() || isMainFrame) return false
        if (isMediaUrl(url)) return false
        val eng = engine
        if (eng != null && ready.get()) {
            return try {
                val type = RequestTypeMapper.from(Uri.parse(url), null)
                val decision = eng.checkNetworkRequest(url, "", type)
                decision.matched && !decision.exception
            } catch (e: Exception) {
                Log.w(TAG, "engine check failed", e)
                fallbackBlock(url)
            }
        }
        return fallbackBlock(url)
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!strict) return null
        val url = request.url?.toString() ?: return null
        if (request.isForMainFrame || isMediaUrl(url)) return null

        val eng = engine
        if (eng != null && ready.get()) {
            try {
                val accept = request.requestHeaders?.get("Accept")
                val type = RequestTypeMapper.from(request.url, accept)
                val source = request.requestHeaders?.get("Referer") ?: ""
                val decision = eng.checkNetworkRequest(url, source, type)
                if (decision.exception) return null
                if (decision.matched) return emptyResponse()
            } catch (e: Exception) {
                Log.w(TAG, "intercept failed", e)
            }
        }
        return if (fallbackBlock(url)) emptyResponse() else null
    }

    fun injectCosmetics(webView: WebView, pageUrl: String?) {
        if (!strict || pageUrl.isNullOrBlank() || isMediaUrl(pageUrl)) return
        val eng = engine ?: return
        if (!ready.get()) return
        try {
            val cos = eng.cosmeticResources(pageUrl) ?: return
            val css = cos.css()
            val js = cos.js
            val script = StringBuilder()
            if (!css.isNullOrBlank()) {
                script.append(
                    "(function(){try{" +
                        "var s=document.getElementById('__dlna_abp_css');" +
                        "if(!s){s=document.createElement('style');s.id='__dlna_abp_css';" +
                        "(document.head||document.documentElement).appendChild(s);}" +
                        "s.textContent=" + jsonString(css) + ";" +
                        "}catch(e){}})();"
                )
            }
            if (!js.isNullOrBlank()) script.append('\n').append(js)
            if (script.isNotEmpty()) webView.evaluateJavascript(script.toString(), null)
        } catch (e: Exception) {
            Log.w(TAG, "cosmetics failed", e)
        }
    }

    fun ensureEngineAsync() {
        val ctx = appCtx ?: return
        if (loading.getAndSet(true)) return
        bg.execute {
            try {
                loadEngine(ctx)
            } catch (e: Exception) {
                Log.e(TAG, "failed to load Brave engine", e)
                ready.set(false)
            } finally {
                loading.set(false)
            }
        }
    }

    private fun loadEngine(ctx: Context) {
        val eng = AdblockEngine.create()
        val bin = File(ctx.cacheDir, CACHE_BIN)
        val listsFile = File(ctx.cacheDir, CACHE_LISTS)
        val lastTs = prefs?.getLong(PREF_LISTS_TS, 0L) ?: 0L
        val stale = System.currentTimeMillis() - lastTs > REFRESH_MS

        var listsText: String? = null
        if (listsFile.exists() && !stale) listsText = listsFile.readText()
        if (listsText.isNullOrBlank() || stale) {
            listsText = downloadLists()
            if (!listsText.isNullOrBlank()) {
                listsFile.writeText(listsText)
                prefs?.edit()?.putLong(PREF_LISTS_TS, System.currentTimeMillis())?.apply()
            } else if (listsFile.exists()) {
                listsText = listsFile.readText()
            }
        }

        var loadedFromBin = false
        if (bin.exists() && !stale && listsFile.exists()) {
            try {
                eng.deserializeFrom(bin)
                loadedFromBin = true
            } catch (e: Exception) {
                Log.w(TAG, "deserialize failed", e)
            }
        }

        if (!loadedFromBin) {
            val text = listsText
            if (text.isNullOrBlank()) {
                eng.close()
                ready.set(false)
                return
            }
            eng.loadFilterList(text)
            try {
                eng.serializeTo(bin)
            } catch (e: Exception) {
                Log.w(TAG, "serialize failed", e)
            }
        }

        try {
            val res = ctx.assets.open("adblock/resources.json").bufferedReader().use { it.readText() }
            eng.loadResources(res)
        } catch (_: Exception) {
        }

        engine?.close()
        engine = eng
        ready.set(true)
        Log.i(TAG, "Brave adblock-rust ready")
    }

    private fun downloadLists(): String? {
        val sb = StringBuilder()
        for (u in LIST_URLS) {
            try {
                val req = Request.Builder().url(u).header("User-Agent", "DlnaCaster/3.0").build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) sb.append(body).append('\n')
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "download list failed: $u", e)
            }
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun jsonString(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> b.append("\\\\")
                '"' -> b.append("\\\"")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> b.append(c)
            }
        }
        return b.append('"').toString()
    }

    fun isMediaUrl(raw: String): Boolean {
        val u = raw.lowercase(Locale.US)
        if (u.contains("googlevideo.com") || u.contains("youtube.com/videoplayback")) return true
        if (u.contains("m3u8") || u.contains(".mpd") || u.contains("mime=video") || u.contains("mime=audio")) return true
        return MEDIA_EXT.any { u.contains(it) }
    }

    private fun fallbackBlock(raw: String): Boolean {
        val host = try {
            Uri.parse(raw).host?.lowercase(Locale.US)?.removePrefix("www.") ?: return false
        } catch (_: Exception) {
            return false
        }
        if (FALLBACK_HOSTS.contains(host)) return true
        for (d in FALLBACK_SUFFIX) {
            if (host == d || host.endsWith(".$d")) return true
        }
        val path = try {
            Uri.parse(raw).path?.lowercase(Locale.US) ?: ""
        } catch (_: Exception) {
            ""
        }
        return FALLBACK_PATH.any { path.contains(it) }
    }

    private val MEDIA_EXT = arrayOf(
        ".mp4", ".webm", ".m4v", ".mkv", ".mov", ".mp3", ".m4a", ".aac", ".ogg",
        ".ts", ".m4s", ".mpd", ".m3u8"
    )

    private val FALLBACK_HOSTS = setOf(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "pagead2.googlesyndication.com", "adservice.google.com",
        "securepubads.g.doubleclick.net", "googleads.g.doubleclick.net",
        "tpc.googlesyndication.com", "partner.googleadservices.com",
        "ads.youtube.com", "ad.youtube.com",
        "amazon-adsystem.com", "c.amazon-adsystem.com",
        "taboola.com", "cdn.taboola.com", "trc.taboola.com",
        "outbrain.com", "widgets.outbrain.com",
        "criteo.com", "criteo.net", "static.criteo.net",
        "adnxs.com", "ib.adnxs.com", "scorecardresearch.com",
        "moatads.com", "adsafeprotected.com", "pubmatic.com",
        "rubiconproject.com", "openx.net", "casalemedia.com",
        "googletagmanager.com", "googletagservices.com",
        "google-analytics.com", "ssl.google-analytics.com",
        "hotjar.com", "fullstory.com", "mixpanel.com"
    )

    private val FALLBACK_SUFFIX = arrayOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "amazon-adsystem.com", "taboola.com", "outbrain.com", "criteo.com",
        "criteo.net", "adnxs.com", "scorecardresearch.com", "moatads.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "googletagmanager.com",
        "google-analytics.com", "hotjar.com"
    )

    private val FALLBACK_PATH = arrayOf(
        "/pagead/", "/pagead2/", "/ads/", "/adsense/", "/adserver/",
        "adsbygoogle", "googleads", "doubleclick", "/gpt/", "/dfp/"
    )
}
