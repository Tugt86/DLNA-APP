package com.mycast.dlna

import org.json.JSONArray
import org.json.JSONObject

/** الصفحات التي يحفظها المستخدم في المتصفح (تُحفظ في إعدادات التطبيق). */
object Bookmarks {
    class Item(val title: String, val url: String)

    private const val KEY = "bookmarks"

    private fun norm(u: String) = u.substringBefore('#').trimEnd('/')

    fun all(): List<Item> {
        val out = ArrayList<Item>()
        try {
            val arr = JSONArray(CastSession.prefs.getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Item(o.optString("t"), o.getString("u")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun save(list: List<Item>) {
        val arr = JSONArray()
        for (b in list) arr.put(JSONObject().put("t", b.title).put("u", b.url))
        CastSession.prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun has(url: String?): Boolean = url != null && all().any { norm(it.url) == norm(url) }

    /** يحفظ الصفحة إن لم تكن محفوظة وإلا يزيلها. يرجع true إن صارت محفوظة. */
    fun toggle(title: String?, url: String): Boolean {
        val list = all().toMutableList()
        val i = list.indexOfFirst { norm(it.url) == norm(url) }
        if (i >= 0) {
            list.removeAt(i)
            save(list)
            return false
        }
        list.add(0, Item(title?.takeIf { it.isNotBlank() } ?: url, url))
        save(list)
        return true
    }

    fun remove(url: String) {
        save(all().filter { norm(it.url) != norm(url) })
    }
}
