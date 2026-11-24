package com.mycast.dlna

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * كاشف الفيديو داخل المتصفح. يجمع الروابط من ثلاثة مصادر:
 *  1) اعتراض طلبات الشبكة في WebView (ملفات mp4 / m3u8 / webm ...).
 *  2) سكربت JavaScript يُحقن في كل صفحة وإطار: عناصر video وsource وروابط og:video وJSON-LD
 *     ونص الصفحة، ويراقب fetch وXHR (بما فيها ردود JSON التي تحمل رابط الفيديو).
 *  3) إطارات يوتيوب المضمّنة في المواقع (تُحوَّل إلى رابط مشاهدة).
 * ثم يتحقق منها WebVideoResolver عند ضغط زر البث.
 */
object VideoSniffer {

    class Found(
        val url: String,
        val kind: String,        // video | net | xhr | api | meta | link | page | yt
        val referer: String?,
        val title: String?,
        /** جودة الفيديو (الارتفاع) إن استُنتجت من الصفحة، وإلا 0. */
        var q: Int = 0
    ) {
        val rank: Int get() = rankOf(kind)
    }

    fun rankOf(kind: String) = when (kind) {
        "video" -> 0
        "net", "xhr", "yt" -> 1
        "meta", "api" -> 2
        "link" -> 3
        else -> 4
    }

    private val list = CopyOnWriteArrayList<Found>()
    private val main = Handler(Looper.getMainLooper())

    @Volatile var onChange: (() -> Unit)? = null

    /** رأينا عنصر فيديو بصيغة blob: (بث مجزّأ/مشفّر لا يمكن قراءة رابطه مباشرة). */
    @Volatile var blobSeen = false
        private set

    private val mediaRe = Regex(
        """\.(mp4|m4v|webm|mkv|mov|flv|m3u8|mpd|ogv|3gp)(?:[?#&/"']|$)""", RegexOption.IGNORE_CASE
    )
    private val noiseRe = Regex(
        """(doubleclick|googlesyndication|googleads|adservice|imasdk|/ads?/|analytics|googlevideo\.com|ytimg\.com)""",
        RegexOption.IGNORE_CASE
    )
    private val segRe = Regex(
        """(?:seg|segment|chunk|frag|fragment|init|part)[-_]?\d*\.(?:mp4|m4s)""", RegexOption.IGNORE_CASE
    )

    fun looksLikeMedia(url: String): Boolean =
        mediaRe.containsMatchIn(url) && !noiseRe.containsMatchIn(url) && !segRe.containsMatchIn(url)

    fun isNoise(url: String): Boolean = noiseRe.containsMatchIn(url)

    fun snapshot(): List<Found> = list.toList()

    fun count(): Int = list.size

    fun clear() {
        list.clear()
        blobSeen = false
        main.post { onChange?.invoke() }
    }

    /** من اعتراض الشبكة (خيط خلفي). */
    fun onRequest(url: String, referer: String?) {
        if (looksLikeMedia(url)) add(url, "net", referer, null)
    }

    fun add(url: String, kind: String, referer: String?, title: String?, q: Int = 0) {
        if (kind == "blob") {
            blobSeen = true
            return
        }
        if (url.length > 4096) return
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        if (kind != "yt" && kind != "video" && isNoise(url)) return
        val f = Found(url, kind, referer, title, q)
        var changed = true
        synchronized(this) {
            val old = list.firstOrNull { it.url == url }
            if (old != null) {
                if (f.rank < old.rank) {
                    f.q = maxOf(f.q, old.q)
                    list.remove(old)
                    list.add(f)
                } else if (q > old.q) {
                    old.q = q
                } else changed = false
            } else {
                if (list.size >= 80) list.removeAt(0)
                list.add(f)
            }
        }
        if (changed) main.post { onChange?.invoke() }
    }

    /** الجسر الذي يستدعيه السكربت من داخل الصفحة. يسجّل الروابط فقط ولا يفعل شيئًا آخر. */
    class Bridge {
        @JavascriptInterface
        fun report(url: String?, kind: String?, referer: String?, title: String?) {
            if (url == null || kind == null) return
            add(url, kind.take(12), referer?.take(2048), title?.take(200))
        }

        @JavascriptInterface
        fun reportQ(url: String?, kind: String?, referer: String?, title: String?, q: Int) {
            if (url == null || kind == null) return
            add(url, kind.take(12), referer?.take(2048), title?.take(200), q)
        }
    }

    /** يُحقن عند بدء كل مستند (وفي الإطارات الفرعية) وكذلك عند اكتمال تحميل الصفحة. */
    const val SCRIPT = """
(function(){
  if (window.__dlnaHook) return;
  var B = window.DlnaBridge;
  if (!B) return;
  var hn = location.hostname || '';
  if (hn.indexOf('youtube.com') >= 0 || hn.indexOf('youtu.be') >= 0 || hn.indexOf('youtube-nocookie.com') >= 0) return;
  window.__dlnaHook = true;
  var seen = {};
  var seenQ = {};
  var MEDIA = /\.(mp4|m4v|webm|mkv|mov|flv|m3u8|mpd|ogv|3gp)(?:[?#&\/"']|(?![\s\S]))/i;
  var NOISE = /(doubleclick|googlesyndication|googleads|adservice|imasdk|\/ads?\/|analytics|googlevideo\.com|ytimg\.com)/i;
  var SEG = /(?:seg|segment|chunk|frag|fragment|init|part)[-_]?\d*\.(?:mp4|m4s)/i;
  function abs(u){ try { return new URL(u, location.href).href; } catch(e){ return null; } }
  function rep(u, kind, q){
    if (!u || typeof u !== 'string') return;
    u = u.replace(/\\\//g, '/').replace(/&amp;/g, '&');
    if (/^blob:/i.test(u)) { try { B.report('blob:', 'blob', location.href, document.title || ''); } catch(e){} return; }
    if (/^(data|javascript|about):/i.test(u)) return;
    var a = abs(u);
    if (!a || !/^https?:/i.test(a)) return;
    if (kind !== 'video' && kind !== 'yt' && (NOISE.test(a) || SEG.test(a))) return;
    q = q | 0;
    if (seen[a] && !(kind === 'video' && seen[a] !== 'video') && !(q && !seenQ[a])) return;
    seen[a] = kind;
    if (q) seenQ[a] = q;
    try {
      if (B.reportQ) B.reportQ(a, kind, location.href, document.title || '', q);
      else B.report(a, kind, location.href, document.title || '');
    } catch(e){}
  }
  function qNum(s){
    if (s == null) return 0;
    s = String(s);
    if (/^\s*\d{3,4}\s*(?![\s\S])/.test(s)) { var n0 = parseInt(s, 10); return (n0 >= 120 && n0 <= 4400) ? n0 : 0; }
    var m = /(?:^|[^\d.])(\d{3,4})\s*p(?![a-z])/i.exec(s);
    if (m) { var n = parseInt(m[1], 10); if (n >= 120 && n <= 4400) return n; }
    if (/\b(4k|uhd)\b/i.test(s)) return 2160;
    if (/full\s*hd|\bfhd\b/i.test(s)) return 1080;
    if (/\bhd\b/i.test(s)) return 720;
    if (/\bsd\b/i.test(s)) return 480;
    return 0;
  }
  function qUrl(u){
    var m = /(?:^|[\/_.\-=])(2160|1440|1080|720|576|540|480|360|240|144)p?(?=[\/_.\-?&]|(?![\s\S]))/i.exec(String(u).split('#')[0]);
    return m ? parseInt(m[1], 10) : 0;
  }
  function ctxQ(t, i, len){
    try {
      var a = t.lastIndexOf('{', i); if (a < 0 || i - a > 400 || t.lastIndexOf('}', i) > a) a = -1;
      var b = t.indexOf('}', i + len); if (b < 0 || b - i > 400) b = -1;
      var nb = t.indexOf('{', i + len); if (b >= 0 && nb >= 0 && nb < b) b = -1;
      var w;
      if (a >= 0 && b >= 0) { w = t.slice(a, i) + ' ' + t.slice(i + len, b); }
      else {
        var s0 = i, lim = Math.max(0, i - 60);
        while (s0 > lim && '{}[]<>\n'.indexOf(t.charAt(s0 - 1)) < 0) s0--;
        var e0 = i + len, lim2 = Math.min(t.length, i + len + 60);
        while (e0 < lim2 && '{}[]<\n'.indexOf(t.charAt(e0)) < 0) e0++;
        w = t.slice(s0, i) + ' ' + t.slice(i + len, e0);
      }
      var r = /["']?(?:quality|label|res|resolution|height|size|name|title)["']?\s*[:=]\s*["']?(\d{3,4})p?(?!\d)/i.exec(w);
      if (r) { var n = parseInt(r[1], 10); if (n >= 120 && n <= 4400) return n; }
      return qNum(w);
    } catch (e) { return 0; }
  }
  function ytId(u){
    var m = /(?:youtube(?:-nocookie)?\.com\/(?:embed|v)\/|youtu\.be\/)([A-Za-z0-9_-]{11})/.exec(u || '');
    return m ? m[1] : null;
  }
  function scanText(t, kind){
    if (!t) return;
    t = t.replace(/\\\//g, '/').replace(/\\u0026/g, '&').replace(/&amp;/g, '&');
    var re = /https?:\/\/[^\s"'<>\\()]+?\.(?:mp4|m4v|webm|mkv|mov|flv|m3u8|mpd|ogv)(?:\?[^\s"'<>\\()]*)?/gi;
    var m, n = 0;
    while ((m = re.exec(t)) && n++ < 40) rep(m[0], kind, ctxQ(t, m.index, m[0].length) || qUrl(m[0]));
    var kre = /["'](?:file|src|source|url|video_url|videoUrl|download|link|stream|hls|mp4)["']\s*[:=]\s*["'](https?:\/\/[^"'\s<>\\]+)["']/gi;
    var JUNK = /\.(?:jpe?g|png|gif|webp|svg|css|js|json|html?|ico|woff2?|ttf|vtt|srt|php|xml|txt)(?:[?#]|(?![\s\S]))/i;
    var n2 = 0;
    while ((m = kre.exec(t)) && n2++ < 20) {
      if (!JUNK.test(m[1])) rep(m[1], kind === 'meta' ? 'meta' : 'api', ctxQ(t, m.index, m[0].length) || qUrl(m[1]));
    }
  }
  function scan(){
    try {
      var vs = document.querySelectorAll('video');
      for (var i = 0; i < vs.length; i++) {
        var v = vs[i];
        var s = v.currentSrc || v.src;
        if (s) rep(s, 'video', (v.videoHeight | 0) || qUrl(s));
        var ss = v.querySelectorAll('source');
        for (var j = 0; j < ss.length; j++) if (ss[j].src) {
          var sa = ss[j].getAttribute('size') || ss[j].getAttribute('label') || ss[j].getAttribute('title') ||
            ss[j].getAttribute('res') || ss[j].getAttribute('data-res') || ss[j].getAttribute('data-quality') || '';
          rep(ss[j].src, 'video', qNum(sa) || qUrl(ss[j].src));
        }
        var d = v.getAttribute('data-src');
        if (d) rep(d, 'video');
      }
      var ms = document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"],meta[name="twitter:player:stream"],meta[itemprop="contentUrl"]');
      for (var k = 0; k < ms.length; k++) rep(ms[k].content, 'meta');
      var fr = document.querySelectorAll('iframe[src]');
      for (var f = 0; f < fr.length; f++) {
        var id = ytId(fr[f].src);
        if (id) rep('https://www.youtube.com/watch?v=' + id, 'yt');
      }
      var as = document.querySelectorAll('a[href]');
      for (var q = 0; q < as.length && q < 400; q++) if (MEDIA.test(as[q].href)) {
        var at = (as[q].textContent || '') + ' ' + (as[q].title || '') + ' ' + (as[q].getAttribute('data-quality') || '');
        rep(as[q].href, 'link', qNum(at) || qUrl(as[q].href));
      }
      var js = document.querySelectorAll('script[type="application/ld+json"]');
      for (var z = 0; z < js.length; z++) scanText(js[z].textContent, 'meta');
    } catch(e){}
  }
  function scanPage(){
    try {
      var h = document.documentElement ? document.documentElement.innerHTML : '';
      scanText(h.length > 3000000 ? h.slice(0, 3000000) : h, 'page');
    } catch(e){}
  }
  try {
    var oo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(m, u){
      try { if (u && MEDIA.test(String(u))) rep(String(u), 'xhr'); } catch(e){}
      try {
        this.addEventListener('load', function(){
          try {
            var ct = this.getResponseHeader('content-type') || '';
            if ((this.responseType === '' || this.responseType === 'text') && /json|text\/html|xml/i.test(ct)
                && this.responseText && this.responseText.length < 600000) scanText(this.responseText, 'api');
          } catch(e){}
        });
      } catch(e){}
      return oo.apply(this, arguments);
    };
  } catch(e){}
  try {
    var of = window.fetch;
    if (of) window.fetch = function(inp, init){
      var u = (typeof inp === 'string') ? inp : (inp && inp.url);
      try { if (u && MEDIA.test(String(u))) rep(String(u), 'xhr'); } catch(e){}
      var p = of.apply(this, arguments);
      try {
        p.then(function(r){
          try {
            var ct = r.headers.get('content-type') || '';
            if (/json|xml/i.test(ct)) r.clone().text().then(function(t){
              if (t && t.length < 600000) scanText(t, 'api');
            }).catch(function(){});
          } catch(e){}
        }).catch(function(){});
      } catch(e){}
      return p;
    };
  } catch(e){}
  function onEv(e){ if (e.target && e.target.tagName === 'VIDEO') scan(); }
  document.addEventListener('loadedmetadata', onEv, true);
  document.addEventListener('play', onEv, true);
  setInterval(scan, 1500);
  setTimeout(scanPage, 2500);
  setTimeout(scanPage, 7000);
  window.__dlnaScan = function(){ scan(); scanPage(); };
  scan();
})();
"""
}
