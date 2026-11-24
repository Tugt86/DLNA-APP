package com.mycast.dlna

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

fun fmtTime(sec: Int): String {
    val s = if (sec < 0) 0 else sec
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

object NetUtil {
    /** عنوان الهاتف على الشبكة المحلية (واي فاي أو نقطة اتصال). */
    fun localIp(): String? {
        val list = NetworkInterface.getNetworkInterfaces() ?: return null
        val candidates = mutableListOf<Pair<String, String>>()
        for (ni in Collections.list(list)) {
            if (!ni.isUp || ni.isLoopback) continue
            val name = ni.name ?: continue
            if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("dummy")) continue
            for (a in Collections.list(ni.inetAddresses)) {
                if (a is Inet4Address && !a.isLoopbackAddress) {
                    candidates.add(name to (a.hostAddress ?: continue))
                }
            }
        }
        return candidates.firstOrNull { it.first.startsWith("wlan") }?.second
            ?: candidates.firstOrNull { it.first.startsWith("ap") || it.first.startsWith("swlan") }?.second
            ?: candidates.firstOrNull()?.second
    }
}

fun extOf(name: String): String =
    name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 } ?: "mp4"

fun mimeOf(ext: String): String = when (ext) {
    "mkv" -> "video/x-matroska"
    "ts" -> "video/mp2t"
    "avi" -> "video/x-msvideo"
    else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/mp4"
}

fun fmtSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) String.format(Locale.US, "%.1f GB", bytes / 1e9)
    else "${maxOf(1L, bytes / 1_000_000L)} MB"

/** خروج كامل من التطبيق: يوقف البث ويحفظ مكانك ويُنهي العملية كي لا يبقى شيء يعمل في الخلفية. */
fun exitApp(ctx: android.content.Context, act: android.app.Activity? = null) {
    try { CastSession.shutdown() } catch (e: Exception) { }
    try { android.webkit.CookieManager.getInstance().flush() } catch (e: Exception) { }
    try {
        (ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancelAll()
    } catch (e: Exception) { }
    act?.finishAndRemoveTask()
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        android.os.Process.killProcess(android.os.Process.myPid())
    }, 400)
}
