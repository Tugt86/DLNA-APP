package com.mycast.dlna

/** يستنتج جودة الفيديو (الارتفاع بالبكسل) من النص أو الرابط أو ما يحيط بالرابط في الصفحة. */
object QHint {
    private val pRe = Regex("""(?<![\d.])(\d{3,4})\s*[pP](?![a-zA-Z])""")
    private val keyRe = Regex(
        """(?:quality|label|res|resolution|height|size|name|title)["']?\s*[:=]\s*["']?(\d{3,4})[pP]?(?!\d)""",
        RegexOption.IGNORE_CASE
    )
    private val urlRe = Regex("""(?:^|[/_.\-=])(2160|1440|1080|720|576|540|480|360|240|144)[pP]?(?=[/_.\-?&]|$)""")
    private val k4 = Regex("""\b(4k|uhd)\b""")
    private val fhd = Regex("""\bfhd\b|full\s*hd""")
    private val hd = Regex("""\bhd\b""")
    private val sd = Regex("""\bsd\b""")

    private fun ok(n: Int) = n in 120..4400

    fun fromText(s: String?): Int {
        if (s.isNullOrEmpty()) return 0
        s.trim().toIntOrNull()?.let { if (ok(it)) return it }
        pRe.find(s)?.let { val n = it.groupValues[1].toInt(); if (ok(n)) return n }
        keyRe.find(s)?.let { val n = it.groupValues[1].toInt(); if (ok(n)) return n }
        val l = s.lowercase()
        if (k4.containsMatchIn(l)) return 2160
        if (fhd.containsMatchIn(l)) return 1080
        if (hd.containsMatchIn(l)) return 720
        if (sd.containsMatchIn(l)) return 480
        return 0
    }

    fun fromUrl(u: String): Int {
        val path = u.substringBefore('#')
        urlRe.find(path)?.let { return it.groupValues[1].toInt() }
        pRe.find(path)?.let { val n = it.groupValues[1].toInt(); if (ok(n)) return n }
        return 0
    }

    /** يقرأ الجودة من كائن JSON أو من النص المجاور للرابط (من idx بطول len داخل t). */
    fun context(t: String, idx: Int, len: Int): Int {
        if (idx < 0 || idx + len > t.length) return 0
        // الكائن الذي يحوي الرابط فعلًا: لا يُغلق قبله ولا يبدأ كائن آخر قبل إغلاقه
        val a = t.lastIndexOf('{', idx).let { if (it >= 0 && idx - it < 400 && t.lastIndexOf('}', idx) <= it) it else -1 }
        val b = t.indexOf('}', idx + len).let { r ->
            val nb = t.indexOf('{', idx + len)
            if (r >= 0 && r - idx < 400 && !(nb in 0 until r)) r else -1
        }
        val w: String
        if (a >= 0 && b >= 0) {
            w = t.substring(a, idx) + " " + t.substring(idx + len, b)
        } else {
            // خارج كائن JSON: نقرأ فقط النص المتصل بالرابط حتى أقرب فاصل (وسم أو قوس)
            var s0 = idx
            val lim = maxOf(0, idx - 60)
            while (s0 > lim && t[s0 - 1] !in "{}[]<>\n") s0--
            var e0 = idx + len
            val lim2 = minOf(t.length, idx + len + 60)
            while (e0 < lim2 && t[e0] !in "{}[]<\n") e0++
            w = t.substring(s0, idx) + " " + t.substring(idx + len, e0)
        }
        return fromText(w)
    }
}
