package com.mycast.dlna

import java.net.HttpURLConnection
import java.net.URL

/** يرسل أوامر UPnP AVTransport / RenderingControl إلى جهاز العرض (الرسيفر). */
class Controller(val r: Renderer) {

    companion object {
        /** "1/2" → 0.5، "2" → 2.0، "1.5" → 1.5 */
        fun speedValue(s: String): Double {
            val t = s.trim()
            return try {
                if (t.contains('/')) t.substringBefore('/').toDouble() / t.substringAfter('/').toDouble()
                else t.toDouble()
            } catch (e: Exception) {
                1.0
            }
        }

        fun speedLabel(s: String): String {
            val v = speedValue(s)
            val txt = if (v == Math.floor(v)) v.toInt().toString() else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
            return "${txt}x"
        }
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun soap(url: String, type: String, action: String, args: List<Pair<String, String>>): String? {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        sb.append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        sb.append("<u:").append(action).append(" xmlns:u=\"").append(type).append("\">")
        for ((k, v) in args) sb.append("<$k>").append(esc(v)).append("</$k>")
        sb.append("</u:").append(action).append("></s:Body></s:Envelope>")
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)

        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPAction", "\"$type#$action\"")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) text else null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun avt(action: String, vararg args: Pair<String, String>): String? =
        soap(r.avTransportUrl, r.avTransportType, action, args.toList())

    private fun rc(action: String, vararg args: Pair<String, String>): String? {
        val u = r.renderingControlUrl ?: return null
        val t = r.renderingControlType ?: return null
        return soap(u, t, action, args.toList())
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

    private fun parseTime(t: String?): Int {
        val p = t?.split(":") ?: return 0
        if (p.size != 3) return 0
        return try {
            p[0].toInt() * 3600 + p[1].toInt() * 60 + p[2].substringBefore('.').toInt()
        } catch (e: NumberFormatException) {
            0
        }
    }

    private fun didl(url: String, title: String, mime: String): String {
        val protocol = if (mime.contains("mpegurl"))
            "http-get:*:$mime:*"
        else
            "http-get:*:$mime:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${esc(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"${esc(protocol)}\">${esc(url)}</res>" +
            "</item></DIDL-Lite>"
    }

    /** يرسل الفيديو إلى الجهاز ويبدأ التشغيل. */
    fun cast(mediaUrl: String, title: String, mime: String): Boolean {
        avt("Stop", "InstanceID" to "0")
        Thread.sleep(300)

        var ok = avt(
            "SetAVTransportURI",
            "InstanceID" to "0",
            "CurrentURI" to mediaUrl,
            "CurrentURIMetaData" to didl(mediaUrl, title, mime)
        ) != null
        if (!ok) {
            // بعض الأجهزة ترفض البيانات الوصفية، فنعيد المحاولة بدونها
            ok = avt(
                "SetAVTransportURI",
                "InstanceID" to "0",
                "CurrentURI" to mediaUrl,
                "CurrentURIMetaData" to ""
            ) != null
        }
        if (!ok) return false

        Thread.sleep(500)
        repeat(3) {
            if (play()) return true
            Thread.sleep(800)
        }
        return false
    }

    /** سرعة التشغيل بصيغة UPnP ("1" أو "2" أو "1/2" ...). */
    @Volatile
    var speed: String = "1"

    fun play(): Boolean = avt("Play", "InstanceID" to "0", "Speed" to speed) != null
    fun pause(): Boolean = avt("Pause", "InstanceID" to "0") != null
    fun stop(): Boolean = avt("Stop", "InstanceID" to "0") != null

    fun seek(sec: Int): Boolean =
        avt("Seek", "InstanceID" to "0", "Unit" to "REL_TIME", "Target" to fmtTime(sec)) != null

    /** موضع التشغيل: بالثواني، وبالميلي ثانية إن أبلغ التلفاز كسور الثانية (frac). */
    class Pos(val sec: Int, val dur: Int, val ms: Long, val frac: Boolean)

    private fun parseTimeMs(t: String?): Pair<Long, Boolean> {
        val p = t?.trim()?.split(":") ?: return 0L to false
        if (p.size != 3) return 0L to false
        return try {
            val whole = p[2].substringBefore('.').toInt()
            val fs = p[2].substringAfter('.', "")
            val fracMs = if (fs.isEmpty()) 0L else (("0.$fs").toDouble() * 1000).toLong()
            ((p[0].toLong() * 3600 + p[1].toLong() * 60 + whole) * 1000 + fracMs) to fs.any { it != '0' }
        } catch (e: Exception) {
            0L to false
        }
    }

    /** مثل position() لكن مع الميلي ثانية إن وُجدت (يستعملها صوت الهاتف للمزامنة الدقيقة). */
    fun positionDetailed(): Pos? {
        val x = avt("GetPositionInfo", "InstanceID" to "0") ?: return null
        val rel = tag(x, "RelTime")
        val (ms, frac) = parseTimeMs(rel)
        return Pos(parseTime(rel), parseTime(tag(x, "TrackDuration")), ms, frac)
    }

    /** (الموضع الحالي، المدة الكلية) بالثواني. */
    fun position(): Pair<Int, Int>? {
        val x = avt("GetPositionInfo", "InstanceID" to "0") ?: return null
        return Pair(parseTime(tag(x, "RelTime")), parseTime(tag(x, "TrackDuration")))
    }

    /** PLAYING / PAUSED_PLAYBACK / STOPPED / TRANSITIONING / NO_MEDIA_PRESENT */
    fun transportState(): String? =
        avt("GetTransportInfo", "InstanceID" to "0")?.let { tag(it, "CurrentTransportState")?.uppercase() }

    @Volatile
    private var vmax: Int? = null

    /** أعلى قيمة للصوت يقبلها الجهاز (من وصف خدمته)، وإلا 100. */
    fun volumeMax(): Int {
        vmax?.let { return it }
        var m = 100
        val u = r.rcScpdUrl
        if (u != null) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 4000
                val xml = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                val part = xml.split("<stateVariable").firstOrNull { it.contains("<name>Volume</name>") }
                val mx = part?.let { Regex("<maximum>\\s*(\\d+)\\s*</maximum>").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                if (mx != null && mx in 1..1000) m = mx
            } catch (e: Exception) {
            }
        }
        vmax = m
        return m
    }

    /** القناة التي قبلها الجهاز فعلًا (بعض الأجهزة ترفض Master). */
    @Volatile
    private var volChannel: String? = null

    fun setVolume(v: Int): Boolean {
        val cached = volChannel
        if (cached != null) {
            if (rc("SetVolume", "InstanceID" to "0", "Channel" to cached, "DesiredVolume" to v.toString()) != null) return true
            volChannel = null
        }
        for (ch in arrayOf("Master", "LF", "RF")) {
            if (rc("SetVolume", "InstanceID" to "0", "Channel" to ch, "DesiredVolume" to v.toString()) != null) {
                volChannel = ch
                return true
            }
        }
        return false
    }

    // ───────────── سرعة التشغيل ─────────────

    @Volatile
    private var speedList: List<String>? = null

    /** قيم السرعة التي يعلنها التلفاز (TransportPlaySpeed) أو قائمة افتراضية إن لم يعلن شيئًا. */
    fun supportedSpeeds(): List<String> {
        speedList?.let { return it }
        var out: List<String>? = null
        val u = r.avScpdUrl
        if (u != null) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 4000
                val xml = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                val part = xml.split("<stateVariable").firstOrNull { it.contains("<name>TransportPlaySpeed</name>") }
                if (part != null) {
                    val vals = Regex("<allowedValue>\\s*([^<]+?)\\s*</allowedValue>").findAll(part)
                        .map { it.groupValues[1] }.toList()
                    val pos = vals.filter { speedValue(it) > 0.0 }
                    if (pos.isNotEmpty()) out = pos
                }
            } catch (e: Exception) {
            }
        }
        val res = (out ?: listOf("1/2", "1", "5/4", "3/2", "2"))
            .distinct().sortedBy { speedValue(it) }
        speedList = res
        return res
    }

    /** هل أعلن التلفاز سرعات غير 1 فعلًا؟ */
    fun speedAdvertised(): Boolean = supportedSpeeds().any { speedValue(it) != 1.0 }

    fun setSpeed(sp: String): Boolean {
        val ok = avt("Play", "InstanceID" to "0", "Speed" to sp) != null
        if (ok) speed = sp
        return ok
    }

    private var savedVolume: Int? = null

    /** يكتم صوت التلفاز (أو يعيده). إن لم يدعم الكتم نخفض الصوت إلى 0 ونعيده لاحقًا. */
    fun muteTv(on: Boolean) {
        val ok = rc(
            "SetMute", "InstanceID" to "0", "Channel" to "Master",
            "DesiredMute" to if (on) "1" else "0"
        ) != null
        if (ok) return
        if (on) {
            if (savedVolume == null) savedVolume = getVolume()
            setVolume(0)
        } else {
            savedVolume?.let { setVolume(it) }
            savedVolume = null
        }
    }

    fun getVolume(): Int? =
        rc("GetVolume", "InstanceID" to "0", "Channel" to "Master")
            ?.let { tag(it, "CurrentVolume")?.toIntOrNull() }
}
