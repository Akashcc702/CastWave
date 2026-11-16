package com.castwave.dlna

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Sends UPnP SOAP actions (AVTransport + RenderingControl) to a renderer. */
class DlnaController {

    // ------------------------------------------------------------------ AVTransport

    suspend fun setAvTransportUri(d: DlnaDevice, url: String, item: MediaItem) {
        withContext(Dispatchers.IO) {
            fun args(meta: String) =
                "<InstanceID>0</InstanceID><CurrentURI>${esc(url)}</CurrentURI>" +
                    "<CurrentURIMetaData>${esc(meta)}</CurrentURIMetaData>"
            try {
                soap(d.avTransportUrl, d.avTransportType, "SetAVTransportURI", args(buildDidl(item, url)))
            } catch (e: IOException) {
                // Some renderers reject DIDL metadata; retry bare.
                soap(d.avTransportUrl, d.avTransportType, "SetAVTransportURI", args(""))
            }
        }
    }

    suspend fun play(d: DlnaDevice) {
        withContext(Dispatchers.IO) {
            soap(d.avTransportUrl, d.avTransportType, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
        }
    }

    suspend fun pause(d: DlnaDevice) {
        withContext(Dispatchers.IO) {
            soap(d.avTransportUrl, d.avTransportType, "Pause", "<InstanceID>0</InstanceID>")
        }
    }

    suspend fun stop(d: DlnaDevice) {
        withContext(Dispatchers.IO) {
            soap(d.avTransportUrl, d.avTransportType, "Stop", "<InstanceID>0</InstanceID>")
        }
    }

    suspend fun seek(d: DlnaDevice, positionMs: Long) {
        withContext(Dispatchers.IO) {
            soap(
                d.avTransportUrl, d.avTransportType, "Seek",
                "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${Fmt.upnpTime(positionMs)}</Target>"
            )
        }
    }

    suspend fun getTransportInfo(d: DlnaDevice): TransportState = withContext(Dispatchers.IO) {
        val r = soap(d.avTransportUrl, d.avTransportType, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        when (extractTag(r, "CurrentTransportState")?.trim()?.uppercase(Locale.US)) {
            "PLAYING" -> TransportState.PLAYING
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> TransportState.PAUSED
            "STOPPED" -> TransportState.STOPPED
            "TRANSITIONING" -> TransportState.TRANSITIONING
            "NO_MEDIA_PRESENT" -> TransportState.NO_MEDIA
            else -> TransportState.UNKNOWN
        }
    }

    suspend fun getPositionInfo(d: DlnaDevice): PositionInfo = withContext(Dispatchers.IO) {
        val r = soap(d.avTransportUrl, d.avTransportType, "GetPositionInfo", "<InstanceID>0</InstanceID>")
        val duration = Fmt.parseUpnpTime(extractTag(r, "TrackDuration"))
        var position = Fmt.parseUpnpTime(extractTag(r, "RelTime"))
        if (position == 0L) position = Fmt.parseUpnpTime(extractTag(r, "AbsTime"))
        PositionInfo(duration, position)
    }

    // ------------------------------------------------------------------ RenderingControl

    suspend fun setVolume(d: DlnaDevice, volume: Int) {
        val url = d.renderingUrl ?: return
        val type = d.renderingType ?: return
        withContext(Dispatchers.IO) {
            soap(
                url, type, "SetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${volume.coerceIn(0, 100)}</DesiredVolume>"
            )
        }
    }

    suspend fun getVolume(d: DlnaDevice): Int {
        val url = d.renderingUrl ?: return -1
        val type = d.renderingType ?: return -1
        return withContext(Dispatchers.IO) {
            val r = soap(url, type, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
            extractTag(r, "CurrentVolume")?.trim()?.toIntOrNull() ?: -1
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun soap(controlUrl: String, serviceType: String, action: String, args: String): String {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$serviceType\">$args</u:$action></s:Body></s:Envelope>"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val c = URL(controlUrl).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 3000
            c.readTimeout = 6000
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPAction", "\"$serviceType#$action\"")
            c.setRequestProperty("User-Agent", "Android/UPnP/1.1 CastWave/1.0")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val why = extractTag(text, "errorDescription") ?: ""
                throw IOException("$action failed: HTTP $code $why".trim())
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun extractTag(xml: String, tag: String): String? {
        val re = Regex("<(?:[A-Za-z0-9_]+:)?$tag(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?$tag>", RegexOption.DOT_MATCHES_ALL)
        return re.find(xml)?.groupValues?.get(1)
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun buildDidl(item: MediaItem, url: String): String {
        val cls = when (item.type) {
            MediaType.VIDEO -> "object.item.videoItem"
            MediaType.AUDIO -> "object.item.audioItem.musicTrack"
            MediaType.IMAGE -> "object.item.imageItem.photo"
        }
        val protocol = "http-get:*:${item.mime}:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        val sb = StringBuilder()
        sb.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        sb.append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        sb.append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
        sb.append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
        sb.append("<dc:title>").append(esc(item.title)).append("</dc:title>")
        if (item.type == MediaType.AUDIO) {
            if (item.subtitle.isNotBlank()) sb.append("<upnp:artist>").append(esc(item.subtitle)).append("</upnp:artist>")
            if (item.album.isNotBlank()) sb.append("<upnp:album>").append(esc(item.album)).append("</upnp:album>")
        }
        sb.append("<upnp:class>").append(cls).append("</upnp:class>")
        sb.append("<res protocolInfo=\"").append(esc(protocol)).append("\"")
        if (item.durationMs > 0) sb.append(" duration=\"").append(Fmt.upnpTime(item.durationMs)).append("\"")
        if (item.size > 0) sb.append(" size=\"").append(item.size).append("\"")
        sb.append(">").append(esc(url)).append("</res>")
        sb.append("</item></DIDL-Lite>")
        return sb.toString()
    }
}
