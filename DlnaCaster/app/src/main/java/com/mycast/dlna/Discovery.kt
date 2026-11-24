package com.mycast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import org.w3c.dom.Element
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

data class Renderer(
    val name: String,
    val location: String,
    val avTransportUrl: String,
    val avTransportType: String,
    val renderingControlUrl: String?,
    val renderingControlType: String?,
    val rcScpdUrl: String? = null,
    val avScpdUrl: String? = null
) {
    override fun toString(): String = name
}

object Discovery {
    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900

    /** يعمل في خيط خلفي (blocking). */
    fun search(context: Context, timeoutMs: Int = 4000): List<Renderer> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("dlnacaster")
        lock.setReferenceCounted(false)
        lock.acquire()
        val locations = LinkedHashSet<String>()
        try {
            val socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = 700
            val group = InetAddress.getByName(SSDP_ADDR)
            val targets = listOf(
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1",
                "ssdp:all"
            )
            repeat(2) {
                for (st in targets) {
                    val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $st\r\n\r\n").toByteArray()
                    socket.send(DatagramPacket(msg, msg.size, group, SSDP_PORT))
                }
            }
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val text = String(p.data, 0, p.length)
                    val loc = text.lineSequence()
                        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.substringAfter(":")?.trim()
                    if (!loc.isNullOrEmpty()) locations.add(loc)
                } catch (e: SocketTimeoutException) {
                    // نتابع الانتظار حتى انتهاء المهلة
                }
            }
            socket.close()
        } catch (e: Exception) {
            // نكتفي بما تم العثور عليه حتى الآن
        } finally {
            if (lock.isHeld) lock.release()
        }

        val result = mutableListOf<Renderer>()
        for (loc in locations) {
            val r = describe(loc)
            if (r != null && result.none { it.location == r.location }) result.add(r)
        }
        return result
    }

    /** يقرأ ملف وصف الجهاز ويستخرج خدمتي AVTransport وRenderingControl. */
    private fun describe(location: String): Renderer? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(location).openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 3000
            val doc = conn.inputStream.use {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
            }
            val name = doc.getElementsByTagName("friendlyName").item(0)?.textContent?.trim()
                ?: location
            val base = doc.getElementsByTagName("URLBase").item(0)?.textContent?.trim()
                ?.takeIf { it.isNotEmpty() } ?: location

            var avUrl: String? = null
            var avType: String? = null
            var rcUrl: String? = null
            var rcType: String? = null
            var rcScpd: String? = null
            var avScpd: String? = null
            val services = doc.getElementsByTagName("service")
            for (i in 0 until services.length) {
                val s = services.item(i) as? Element ?: continue
                val type = s.getElementsByTagName("serviceType").item(0)?.textContent?.trim() ?: continue
                val ctrl = s.getElementsByTagName("controlURL").item(0)?.textContent?.trim() ?: continue
                val abs = URL(URL(base), ctrl).toString()
                if (type.contains("AVTransport")) {
                    avUrl = abs; avType = type
                    avScpd = s.getElementsByTagName("SCPDURL").item(0)?.textContent?.trim()
                        ?.let { try { URL(URL(base), it).toString() } catch (e: Exception) { null } }
                } else if (type.contains("RenderingControl")) {
                    rcUrl = abs; rcType = type
                    rcScpd = s.getElementsByTagName("SCPDURL").item(0)?.textContent?.trim()
                        ?.let { try { URL(URL(base), it).toString() } catch (e: Exception) { null } }
                }
            }
            if (avUrl == null || avType == null) return null
            return Renderer(name, location, avUrl, avType, rcUrl, rcType, rcScpd, avScpd)
        } catch (e: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
    }
}
