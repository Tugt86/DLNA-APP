package com.mycast.dlna

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.webkit.WebView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * صور الصفحات المحفوظة: لقطة للصفحة (shot) وشعار الموقع (icon).
 * تُحفظ في مجلد التطبيق وتُستعمل في مربعات الشاشة الرئيسية.
 */
object Thumbs {
    private fun dir(ctx: Context): File = File(ctx.filesDir, "thumbs").also { it.mkdirs() }

    private fun norm(u: String) = u.substringBefore('#').trimEnd('/')

    fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.").removePrefix("m.")

    private fun hash(s: String): String = Integer.toHexString(s.hashCode())

    private fun shotFile(ctx: Context, url: String) = File(dir(ctx), "s_" + hash(norm(url)) + ".jpg")

    // الشعار مشترك بين صفحات الموقع الواحد
    private fun iconFile(ctx: Context, url: String) = File(dir(ctx), "i_" + hash(hostOf(url)) + ".png")

    fun loadShot(ctx: Context, url: String): Bitmap? = decode(shotFile(ctx, url))

    fun loadIcon(ctx: Context, url: String): Bitmap? = decode(iconFile(ctx, url))

    private fun decode(f: File): Bitmap? =
        try {
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        } catch (e: Throwable) {
            null
        }

    /** هل اللقطة غير موجودة أو قديمة (أكثر من 12 ساعة)؟ */
    fun shotStale(ctx: Context, url: String): Boolean {
        val f = shotFile(ctx, url)
        return !f.exists() || System.currentTimeMillis() - f.lastModified() > 12L * 3600 * 1000
    }

    fun hasIcon(ctx: Context, url: String): Boolean = iconFile(ctx, url).exists()

    /** يلتقط الجزء العلوي من الصفحة الظاهرة حاليًا. يجب أن يُستدعى من الخيط الرئيسي. */
    fun saveShot(ctx: Context, url: String, web: WebView) {
        try {
            val w = web.width
            val h = web.height
            if (w <= 0 || h <= 0) return
            val outW = 480
            val outH = 360
            val scale = outW.toFloat() / w
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            canvas.scale(scale, scale)
            web.draw(canvas)
            if (isBlank(bmp)) {
                bmp.recycle()
                return
            }
            shotFile(ctx, url).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            bmp.recycle()
        } catch (e: Throwable) {
        }
    }

    /** صورة بلون واحد تقريبًا (لم تُرسم الصفحة بعد) لا تنفع. */
    private fun isBlank(b: Bitmap): Boolean {
        val first = b.getPixel(b.width / 2, b.height / 2)
        var diff = 0
        for (i in 1..12) {
            for (j in 1..12) {
                if (b.getPixel(b.width * i / 13, b.height * j / 13) != first) diff++
            }
        }
        return diff < 4
    }

    fun saveIcon(ctx: Context, url: String, icon: Bitmap) {
        try {
            if (icon.width < 8) return
            iconFile(ctx, url).outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Throwable) {
        }
    }

    /** يجلب شعار الموقع من الإنترنت (خيط خلفي فقط). يرجع true إن نجح. */
    fun fetchIcon(ctx: Context, url: String): Boolean {
        val host = hostOf(url)
        if (host.isEmpty()) return false
        val sources = listOf(
            "https://www.google.com/s2/favicons?domain=$host&sz=128",
            "https://$host/favicon.ico"
        )
        for (src in sources) {
            try {
                val c = URL(src).openConnection() as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (c.responseCode != 200) {
                    c.disconnect()
                    continue
                }
                val bmp = c.inputStream.use { BitmapFactory.decodeStream(it) }
                c.disconnect()
                if (bmp != null && bmp.width >= 16) {
                    saveIcon(ctx, url, bmp)
                    return true
                }
            } catch (e: Throwable) {
            }
        }
        return false
    }

    fun delete(ctx: Context, url: String) {
        try {
            shotFile(ctx, url).delete()
        } catch (e: Throwable) {
        }
    }
}
