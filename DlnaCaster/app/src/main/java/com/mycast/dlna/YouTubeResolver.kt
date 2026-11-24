package com.mycast.dlna

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

/** يحوّل رابط صفحة يوتيوب إلى قائمة جودات صالحة للتلفاز. يُستدعى من خيط خلفي فقط. */
object YouTubeResolver {

    /**
     * جودة واحدة. إن كان audioUrl فارغًا فالمسار جاهز (صورة + صوت معًا) ويبدأ فورًا،
     * وإلا فالصورة والصوت منفصلان ويُدمجان على الهاتف قبل البث.
     */
    class Option(
        val height: Int,
        val fps: Int,
        val videoUrl: String,
        val audioUrl: String?,
        val sizeBytes: Long,
        /** اسم مخصص للعرض (للمواقع العامة)، وإلا يُحسب من الارتفاع. */
        val custom: String? = null,
        /** نوع الملف إن كان معروفًا (غير mp4)، مثل application/vnd.apple.mpegurl. */
        val mime: String? = null,
        val ext: String? = null,
        /** ترويسات يحتاجها الخادم الأصلي (Referer/Cookie/User-Agent) يضيفها الهاتف عند الجلب. */
        val headers: Map<String, String> = emptyMap(),
        /** قائمة HLS (m3u8) يعيد الهاتف كتابتها ويمرّر مقاطعها. */
        val hls: Boolean = false,
        /** في HLS الرئيسية: BANDWIDTH للنسخة المختارة (-1 = تلقائي). */
        val bandwidth: Int = -1,
        /**
         * خيار إضافي بلا تجهيز: يُرسل للتلفاز الصورة وحدها (videoUrl) ويشغّل الهاتف الصوت (audioUrl).
         * لا يُدمج شيء ولا يُحمَّل شيء قبل البدء.
         */
        val phoneAudio: Boolean = false
    ) {
        val muxed: Boolean get() = audioUrl == null || phoneAudio
        val label: String
            get() = custom ?: if (fps > 30) "${height}p$fps" else "${height}p"
    }

    class Source(val id: String, val title: String, val options: List<Option>)

    @Volatile
    private var inited = false

    @Synchronized
    private fun init() {
        if (inited) return
        NewPipe.init(OkHttpDownloader())
        inited = true
    }

    private val itagRe = Regex("""[?&]itag=(\d+)""")
    private val clenRe = Regex("""[?&]clen=(\d+)""")

    private fun itagOf(url: String): Int =
        itagRe.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    /** حجم الملف من الرابط إن كان معروفًا، وإلا -1. */
    fun clenOf(url: String): Long =
        clenRe.find(url)?.groupValues?.get(1)?.toLongOrNull() ?: -1L

    /** صيغ AV1 داخل MP4 لا تدعمها أغلب التلفازات ولا يدمجها الهاتف، فنستبعدها. */
    private fun isH264(url: String): Boolean {
        val t = itagOf(url)
        return !(t in 394..402 || t == 571 || t in 694..702)
    }

    private fun is60fps(url: String): Boolean = itagOf(url) in intArrayOf(298, 299, 304, 305)

    private fun pickAudio(list: List<AudioStream>): AudioStream? {
        val m4a = list.filter {
            it.format == MediaFormat.M4A && it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        // نتجنب المسارات المدبلجة والوصفية ونفضّل الصوت الأصلي
        val original = m4a.filter { !it.content.contains("dubbed") && !it.content.contains("descriptive") }
        return (if (original.isNotEmpty()) original else m4a).maxByOrNull { it.averageBitrate }
    }

    fun load(pageUrl: String): Source {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, pageUrl)
        val prog = DeliveryMethod.PROGRESSIVE_HTTP

        // 1) مسارات جاهزة (صورة + صوت) وعادةً تكون 360p
        val muxed = info.videoStreams
            .filter { !it.isVideoOnly && it.format == MediaFormat.MPEG_4 && it.isUrl && it.deliveryMethod == prog }
            .distinctBy { it.height }

        val options = ArrayList<Option>()
        for (s in muxed) options.add(Option(s.height, 30, s.content, null, clenOf(s.content)))
        val muxedHeights = muxed.map { it.height }.toSet()

        // 2) جودات أعلى: صورة بدون صوت + أفضل صوت M4A، تُدمج على الهاتف
        val audio = pickAudio(info.audioStreams)
        if (audio != null) {
            val audioSize = clenOf(audio.content)
            val adaptive = info.videoOnlyStreams.filter {
                it.isVideoOnly && it.format == MediaFormat.MPEG_4 && it.isUrl &&
                    it.deliveryMethod == prog && isH264(it.content)
            }
            for ((key, list) in adaptive.groupBy { it.height to is60fps(it.content) }) {
                val best = list.maxByOrNull { clenOf(it.content) } ?: continue
                val fps = if (key.second) 60 else 30
                if (fps <= 30 && key.first in muxedHeights) continue // الجاهز أسرع
                val v = clenOf(best.content)
                val total = if (v > 0 && audioSize > 0) v + audioSize else -1L
                options.add(Option(key.first, fps, best.content, audio.content, total))
                // خيار إضافي: صورة فقط للتلفاز والصوت من الهاتف (يبدأ فورًا بلا دمج)
                val tag = if (fps > 30) "${key.first}p$fps" else "${key.first}p"
                options.add(
                    Option(
                        key.first, fps, best.content, audio.content, v,
                        custom = "$tag · صورة فقط والصوت من الهاتف", phoneAudio = true
                    )
                )
            }
        }

        if (options.isEmpty()) throw IllegalStateException("لا يوجد مسار مناسب للتلفاز لهذا الفيديو")
        options.sortWith(
            compareByDescending<Option> { it.height }
                .thenByDescending { it.fps }
                .thenBy { if (it.phoneAudio) 2 else if (it.muxed) 0 else 1 }
        )
        return Source(info.id ?: "", info.name ?: "YouTube", options)
    }

    fun describe(o: Option): String {
        val size = if (o.sizeBytes > 0) " · ${fmtSize(o.sizeBytes)}" else ""
        // خيارات المواقع لها وصف جاهز؛ نضيف فقط ما يخص التجهيز
        if (o.custom != null) return o.label + (if (o.muxed) "" else " — يحتاج تجهيزًا") + size
        return o.label + (if (o.muxed) " — يبدأ فورًا" else " — يحتاج تجهيزًا") + size
    }

    private val idRegexes = listOf(
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""/shorts/([A-Za-z0-9_-]{11})"""),
        Regex("""/live/([A-Za-z0-9_-]{11})""")
    )

    /** يرجع معرّف الفيديو إن كان الرابط صفحة مشاهدة، وإلا null. */
    fun videoId(url: String?): String? {
        if (url == null) return null
        if (!url.contains("youtube.com") && !url.contains("youtu.be")) return null
        if (url.contains("youtube.com") && !url.contains("/watch") &&
            !url.contains("/shorts/") && !url.contains("/live/")
        ) return null
        for (r in idRegexes) r.find(url)?.let { return it.groupValues[1] }
        return null
    }
}
