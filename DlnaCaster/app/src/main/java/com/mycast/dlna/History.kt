package com.mycast.dlna

import org.json.JSONArray
import org.json.JSONObject

/**
 * سجل المشاهدة: كل فيديو بُثّ (من الهاتف أو من موقع في المتصفح) مع مصدره وآخر موضع وصل إليه.
 * - فيديو الهاتف: يُستأنف من الدقيقة نفسها (إن بقي الملف موجودًا).
 * - فيديو الموقع: يُعاد تشغيله من نفس الرابط (الصفحة) الذي بُثّ منه سابقًا، وقد يفشل إن تغيّر الرابط.
 */
object History {

    const val LOCAL = "local"
    const val WEB = "web"

    class Item(
        val id: String,          // مفتاح فريد: مفتاح الملف للمحلي، أو رابط الصفحة للويب
        val type: String,        // LOCAL أو WEB
        val title: String,
        val uri: String,         // للمحلي: رابط الملف (content://)، للويب: فارغ
        val page: String,        // للويب: رابط الصفحة التي بُثّ منها
        val height: Int,         // الجودة المستعملة (للويب)
        val pos: Int,            // آخر موضع بالثواني
        val dur: Int,
        val done: Boolean,       // انتهى تقريبًا (يبدأ من الصفر)
        val ts: Long             // آخر مرة شوهد فيها
    ) {
        /** نقطة الاستئناف: قبل التوقف بـ 5 ثوانٍ، أو الصفر إن كان قد انتهى. */
        val resumeAt: Int get() = if (done) 0 else maxOf(0, pos - CastSession.RESUME_BACK)

        /** وصف المصدر للعرض. */
        val sourceLabel: String
            get() = if (type == LOCAL) "من الهاتف" else Thumbs.hostOf(page).ifBlank { "من المتصفح" }
    }

    private const val KEY = "history"
    private const val MAX = 100

    private var cache: MutableList<Item>? = null

    private fun load(): MutableList<Item> {
        cache?.let { return it }
        val out = ArrayList<Item>()
        try {
            val arr = JSONArray(CastSession.prefs.getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Item(
                        o.getString("id"), o.optString("ty", LOCAL), o.optString("t"),
                        o.optString("u"), o.optString("p"), o.optInt("h"),
                        o.optInt("pos"), o.optInt("dur"), o.optBoolean("done"), o.optLong("ts")
                    )
                )
            }
        } catch (e: Exception) {
        }
        cache = out
        return out
    }

    private fun persist(list: List<Item>, sync: Boolean) {
        val arr = JSONArray()
        for (it in list) {
            arr.put(
                JSONObject().put("id", it.id).put("ty", it.type).put("t", it.title).put("u", it.uri)
                    .put("p", it.page).put("h", it.height).put("pos", it.pos).put("dur", it.dur)
                    .put("done", it.done).put("ts", it.ts)
            )
        }
        val e = CastSession.prefs.edit().putString(KEY, arr.toString())
        if (sync) e.commit() else e.apply()
    }

    /** الأحدث أولًا. */
    @Synchronized
    fun all(): List<Item> = load().sortedByDescending { it.ts }

    @Synchronized
    fun get(id: String): Item? = load().firstOrNull { it.id == id }

    /** يضيف الفيديو أو يحدّث موضعه، ويرفعه إلى أعلى القائمة. */
    @Synchronized
    fun record(
        id: String, type: String, title: String, uri: String, page: String,
        height: Int, pos: Int, dur: Int, done: Boolean, sync: Boolean = false
    ) {
        val list = load()
        val old = list.indexOfFirst { it.id == id }
        val prev = if (old >= 0) list.removeAt(old) else null
        list.add(
            0,
            Item(
                id, type,
                title.ifBlank { prev?.title ?: "" },
                uri.ifBlank { prev?.uri ?: "" },
                page.ifBlank { prev?.page ?: "" },
                if (height > 0) height else (prev?.height ?: 0),
                pos, if (dur > 0) dur else (prev?.dur ?: 0), done, System.currentTimeMillis()
            )
        )
        while (list.size > MAX) list.removeAt(list.size - 1)
        persist(list, sync)
    }

    @Synchronized
    fun remove(id: String) {
        val list = load()
        if (list.removeAll { it.id == id }) persist(list, false)
    }

    @Synchronized
    fun clear() {
        load().clear()
        persist(emptyList(), false)
    }

    /** «منذ 5 د»، «أمس»… */
    fun ago(ts: Long): String {
        val d = System.currentTimeMillis() - ts
        val m = d / 60_000
        return when {
            m < 1 -> "الآن"
            m < 60 -> "منذ $m د"
            m < 60 * 24 -> "منذ ${m / 60} س"
            m < 60 * 48 -> "أمس"
            else -> "منذ ${m / (60 * 24)} يومًا"
        }
    }
}
