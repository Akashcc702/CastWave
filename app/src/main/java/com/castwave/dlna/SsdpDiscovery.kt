package com.castwave.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SSDP (UDP multicast 239.255.255.250:1900) discovery of UPnP MediaRenderers.
 * Searches on every LAN interface, so it also works when the phone is the hotspot.
 */
class SsdpDiscovery(context: Context) {

    companion object {
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "upnp:rootdevice"
        )
        private const val DEVICE_TTL_MS = 90_000L
        private const val IGNORE_MS = 5 * 60_000L
    }

    private val appContext = context.applicationContext
    private val _devices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val devices: StateFlow<List<DlnaDevice>> = _devices.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val scanLock = Mutex()
    private val descCache = ConcurrentHashMap<String, DlnaDevice>()
    private val ignored = ConcurrentHashMap<String, Long>()
    private var multicastLock: WifiManager.MulticastLock? = null

    // ------------------------------------------------------------------ multicast lock

    @Synchronized
    fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("castwave-ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            // keep going; some devices still deliver unicast replies without the lock
        }
    }

    @Synchronized
    fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (e: Exception) {
            // ignore
        }
        multicastLock = null
    }

    fun clear() {
        descCache.clear()
        ignored.clear()
        _devices.value = emptyList()
        _scanning.value = false
    }

    // ------------------------------------------------------------------ scanning

    suspend fun scan(durationMs: Long = 4500L) {
        if (!scanLock.tryLock()) return
        try {
            _scanning.value = true
            withContext(Dispatchers.IO) {
                val locations = ConcurrentHashMap.newKeySet<String>()
                val addrs = NetUtils.localAddrs().filter { NetUtils.isLanInterface(it.ni) }
                val targets: List<LocalAddr?> = if (addrs.isEmpty()) listOf(null) else addrs
                coroutineScope {
                    targets.map { la -> async { searchOn(la, durationMs, locations) } }.awaitAll()
                }

                val now = System.currentTimeMillis()
                val fresh = locations.filter { loc ->
                    !descCache.containsKey(loc) && (now - (ignored[loc] ?: 0L) > IGNORE_MS)
                }
                val fetched = coroutineScope {
                    fresh.map { loc -> async { loc to fetchDevice(loc) } }.awaitAll()
                }
                for ((loc, dev) in fetched) {
                    if (dev != null) descCache[loc] = dev else ignored[loc] = now
                }

                val map = LinkedHashMap<String, DlnaDevice>()
                _devices.value.forEach { map[it.udn] = it }
                for (loc in locations) {
                    val d = descCache[loc] ?: continue
                    map[d.udn] = d.copy(lastSeen = now)
                }
                _devices.value = map.values
                    .filter { now - it.lastSeen < DEVICE_TTL_MS }
                    .sortedBy { it.friendlyName.lowercase() }
            }
        } finally {
            _scanning.value = false
            scanLock.unlock()
        }
    }

    private fun buildSearch(st: String): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $st\r\n" +
            "USER-AGENT: Android/UPnP/1.1 CastWave/1.0\r\n\r\n"

    private fun searchOn(la: LocalAddr?, durationMs: Long, out: MutableSet<String>) {
        var socket: MulticastSocket? = null
        try {
            val s = if (la != null) MulticastSocket(InetSocketAddress(la.addr, 0)) else MulticastSocket()
            socket = s
            s.timeToLive = 4
            if (la != null) {
                try {
                    s.networkInterface = la.ni
                } catch (e: Exception) {
                    // fall back to default route
                }
            }
            s.soTimeout = 600
            val group = InetAddress.getByName(SSDP_ADDR)
            val deadline = SystemClock.elapsedRealtime() + durationMs
            var nextSend = 0L
            val buf = ByteArray(4096)
            while (SystemClock.elapsedRealtime() < deadline) {
                val now = SystemClock.elapsedRealtime()
                if (now >= nextSend) {
                    for (st in SEARCH_TARGETS) {
                        val data = buildSearch(st).toByteArray(Charsets.UTF_8)
                        try {
                            s.send(DatagramPacket(data, data.size, group, SSDP_PORT))
                        } catch (e: Exception) {
                            // interface may not route multicast
                        }
                    }
                    nextSend = now + 1500L
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    parseLocation(String(p.data, 0, p.length, Charsets.UTF_8))?.let { out.add(it) }
                } catch (e: SocketTimeoutException) {
                    // loop again
                }
            }
        } catch (e: Exception) {
            // socket problems on one interface must not break the others
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun parseLocation(response: String): String? {
        for (line in response.split("\r\n", "\n")) {
            if (line.startsWith("location:", ignoreCase = true)) {
                val v = line.substringAfter(':').trim()
                if (v.startsWith("http", ignoreCase = true)) return v
            }
        }
        return null
    }

    // ------------------------------------------------------------------ device description

    private fun fetchDevice(location: String): DlnaDevice? {
        val xml = httpGet(location) ?: return null
        val doc = try {
            val f = DocumentBuilderFactory.newInstance()
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (e: Exception) {
                // not supported on every parser
            }
            f.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            return null
        }
        val urlBase = doc.getElementsByTagName("URLBase").item(0)?.textContent?.trim().orEmpty()
        val base = if (urlBase.isNotEmpty()) urlBase else location
        val host = try {
            URL(location).host
        } catch (e: Exception) {
            return null
        }

        val devs = doc.getElementsByTagName("device")
        for (i in 0 until devs.length) {
            val dev = devs.item(i) as? Element ?: continue
            if (!childText(dev, "deviceType").contains("MediaRenderer", ignoreCase = true)) continue

            var avUrl: String? = null
            var avType = ""
            var rcUrl: String? = null
            var rcType: String? = null
            val services = dev.getElementsByTagName("service")
            for (j in 0 until services.length) {
                val sv = services.item(j) as? Element ?: continue
                val st = childText(sv, "serviceType")
                val cu = childText(sv, "controlURL")
                if (cu.isEmpty()) continue
                val abs = try {
                    URL(URL(base), cu).toString()
                } catch (e: Exception) {
                    continue
                }
                if (st.contains(":AVTransport:") && avUrl == null) {
                    avUrl = abs
                    avType = st
                } else if (st.contains(":RenderingControl:") && rcUrl == null) {
                    rcUrl = abs
                    rcType = st
                }
            }
            if (avUrl == null) continue
            val model = childText(dev, "modelName")
            val name = childText(dev, "friendlyName").ifEmpty { model.ifEmpty { host } }
            return DlnaDevice(
                udn = childText(dev, "UDN").ifEmpty { location },
                friendlyName = name,
                manufacturer = childText(dev, "manufacturer"),
                modelName = model,
                host = host,
                location = location,
                avTransportUrl = avUrl,
                avTransportType = avType,
                renderingUrl = rcUrl,
                renderingType = rcType
            )
        }
        return null
    }

    private fun childText(parent: Element, tag: String): String {
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && n.nodeName.substringAfter(':') == tag) {
                return n.textContent?.trim().orEmpty()
            }
        }
        return ""
    }

    private fun httpGet(urlStr: String): String? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(urlStr).openConnection() as HttpURLConnection
            c.connectTimeout = 2500
            c.readTimeout = 3500
            c.setRequestProperty("User-Agent", "Android/UPnP/1.1 CastWave/1.0")
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }
}
