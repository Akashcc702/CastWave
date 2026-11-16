package com.castwave.dlna

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

object Fmt {
    fun duration(ms: Long): String {
        val total = if (ms < 0) 0 else ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** UPnP relative time format, e.g. 0:03:25 */
    fun upnpTime(ms: Long): String {
        val total = if (ms < 0) 0 else ms / 1000
        return String.format(Locale.US, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }

    fun parseUpnpTime(s: String?): Long {
        if (s.isNullOrBlank() || s.contains("NOT_IMPL", ignoreCase = true)) return 0L
        val parts = s.trim().split(":")
        if (parts.size != 3) return 0L
        return try {
            val h = parts[0].toLong()
            val m = parts[1].toLong()
            val sec = parts[2].toDouble()
            (h * 3600 + m * 60) * 1000 + (sec * 1000).toLong()
        } catch (e: NumberFormatException) {
            0L
        }
    }

    fun size(bytes: Long): String {
        if (bytes <= 0) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }
}

data class LocalAddr(val ni: NetworkInterface, val addr: Inet4Address, val prefix: Int)

object NetUtils {

    fun localAddrs(): List<LocalAddr> {
        val out = ArrayList<LocalAddr>()
        try {
            val en = NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in Collections.list(en)) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        out.add(LocalAddr(ni, a, ia.networkPrefixLength.toInt()))
                    }
                }
            }
        } catch (e: Exception) {
            // ignore, return what we have
        }
        return out
    }

    /** Wi-Fi / hotspot / ethernet style interfaces that can carry multicast. */
    fun isLanInterface(ni: NetworkInterface): Boolean {
        val n = ni.name.lowercase(Locale.US)
        if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("tun") ||
            n.startsWith("dummy") || n.startsWith("p2p") || n.startsWith("ppp") || n.startsWith("v4-")
        ) return false
        return try {
            ni.supportsMulticast()
        } catch (e: Exception) {
            true
        }
    }

    fun primaryIpv4(): String? {
        val all = localAddrs().filter { isLanInterface(it.ni) && it.addr.isSiteLocalAddress }
        val preferred = all.firstOrNull {
            val n = it.ni.name.lowercase(Locale.US)
            n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("eth")
        } ?: all.firstOrNull()
        return preferred?.addr?.hostAddress
    }

    /** Pick the phone's IP that lives on the same subnet as [targetHost] (works for Wi-Fi and hotspot). */
    fun localIpFor(targetHost: String): String? {
        val addrs = localAddrs().filter { isLanInterface(it.ni) }
        val target = try {
            (InetAddress.getByName(targetHost) as? Inet4Address)?.let { toInt(it) }
        } catch (e: Exception) {
            null
        }
        if (target != null) {
            for (a in addrs) {
                val mask = if (a.prefix == 0) 0 else (-1 shl (32 - a.prefix))
                if ((toInt(a.addr) and mask) == (target and mask)) return a.addr.hostAddress
            }
        }
        return primaryIpv4()
    }

    private fun toInt(a: Inet4Address): Int {
        val b = a.address
        return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }
}
