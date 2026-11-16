package com.castwave.dlna

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

/**
 * Tiny HTTP/1.1 server that streams content:// media to DLNA renderers.
 * Supports GET + HEAD, byte ranges (seeking), proper MIME types and DLNA headers.
 */
class MediaServer(private val context: Context) {

    companion object {
        const val PREFERRED_PORT = 8200
        private const val DLNA_FLAGS = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
    }

    private val registry = ConcurrentHashMap<String, MediaItem>()

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null

    @Volatile
    var port: Int = 0
        private set

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        val ss: ServerSocket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PREFERRED_PORT))
            }
        } catch (e: IOException) {
            try {
                ServerSocket(0)
            } catch (e2: IOException) {
                return false
            }
        }
        serverSocket = ss
        port = ss.localPort
        val pool = Executors.newCachedThreadPool()
        executor = pool
        thread(name = "castwave-http-accept", isDaemon = true) {
            while (!ss.isClosed) {
                try {
                    val client = ss.accept()
                    pool.execute { handleClient(client) }
                } catch (e: IOException) {
                    break
                } catch (e: RejectedExecutionException) {
                    break
                }
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // ignore
        }
        serverSocket = null
        executor?.shutdownNow()
        executor = null
        registry.clear()
        port = 0
    }

    /** Registers the item and returns the URL a renderer on [host]'s network can fetch. */
    fun urlFor(item: MediaItem, host: String): String {
        val key = java.lang.Long.toHexString(item.id.hashCode().toLong() and 0xffffffffL)
        registry[key] = item
        return "http://$host:$port/media/$key/media.${extensionFor(item)}"
    }

    private fun extensionFor(item: MediaItem): String {
        MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mime)?.let { return it }
        return when (item.type) {
            MediaType.VIDEO -> "mp4"
            MediaType.AUDIO -> "mp3"
            MediaType.IMAGE -> "jpg"
        }
    }

    // ------------------------------------------------------------------ request handling

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendSimple(output, 400, "Bad Request")
                return
            }
            val method = parts[0].uppercase(Locale.US)
            val headers = HashMap<String, String>()
            var count = 0
            while (count++ < 100) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase(Locale.US)] = line.substring(i + 1).trim()
            }

            if (method != "GET" && method != "HEAD") {
                sendSimple(output, 405, "Method Not Allowed")
                return
            }
            val path = Uri.decode(parts[1].substringBefore('?'))
            val segs = path.split('/').filter { it.isNotEmpty() }
            if (segs.size < 2 || segs[0] != "media") {
                sendSimple(output, 404, "Not Found")
                return
            }
            val item = registry[segs[1]]
            if (item == null) {
                sendSimple(output, 404, "Not Found")
                return
            }
            serve(item, method == "HEAD", headers, output)
        } catch (e: IOException) {
            // Renderers routinely drop the connection when seeking; not an error.
        } catch (e: Exception) {
            // never crash the accept pool
        } finally {
            try {
                socket.close()
            } catch (e: IOException) {
                // ignore
            }
        }
    }

    private fun serve(item: MediaItem, headOnly: Boolean, headers: Map<String, String>, output: OutputStream) {
        val pfd: ParcelFileDescriptor? = try {
            context.contentResolver.openFileDescriptor(item.uri, "r")
        } catch (e: Exception) {
            null
        }
        if (pfd == null) {
            sendSimple(output, 404, "Not Found")
            return
        }
        var total = pfd.statSize
        if (total <= 0) total = item.size
        // Takes ownership of the descriptor and closes it with the stream.
        val fis = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        try {
            var start = 0L
            var end = if (total > 0) total - 1 else -1L
            var partial = false

            val rangeHeader = headers["range"]
            if (rangeHeader != null && total > 0 && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
                val spec = rangeHeader.substring(6).substringBefore(',').trim()
                val dash = spec.indexOf('-')
                if (dash >= 0) {
                    val a = spec.substring(0, dash).trim()
                    val b = spec.substring(dash + 1).trim()
                    try {
                        if (a.isEmpty()) {
                            val suffix = b.toLong()
                            start = maxOf(0L, total - suffix)
                            end = total - 1
                        } else {
                            start = a.toLong()
                            end = if (b.isEmpty()) total - 1 else minOf(b.toLong(), total - 1)
                        }
                        partial = true
                    } catch (e: NumberFormatException) {
                        start = 0L
                        end = total - 1
                        partial = false
                    }
                    if (partial && (start > end || start >= total)) {
                        val h = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n"
                        output.write(h.toByteArray(Charsets.ISO_8859_1))
                        output.flush()
                        return
                    }
                }
            }

            val sb = StringBuilder()
            sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            sb.append("Content-Type: ").append(item.mime).append("\r\n")
            if (total > 0) sb.append("Content-Length: ").append(end - start + 1).append("\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            if (partial) sb.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n")
            sb.append("transferMode.dlna.org: ").append(if (item.type == MediaType.IMAGE) "Interactive" else "Streaming").append("\r\n")
            sb.append("contentFeatures.dlna.org: ").append(DLNA_FLAGS).append("\r\n")
            sb.append("Date: ").append(httpDate()).append("\r\n")
            sb.append("Server: CastWave/1.0 UPnP/1.0\r\n")
            sb.append("Connection: close\r\n\r\n")
            output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (headOnly) {
                output.flush()
                return
            }

            if (start > 0) {
                try {
                    fis.channel.position(start)
                } catch (e: IOException) {
                    var skipped = 0L
                    while (skipped < start) {
                        val s = fis.skip(start - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                }
            }
            val buf = ByteArray(64 * 1024)
            var remaining = if (total > 0) end - start + 1 else Long.MAX_VALUE
            while (remaining > 0) {
                val n = fis.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                output.write(buf, 0, n)
                remaining -= n
            }
            output.flush()
        } finally {
            try {
                fis.close()
            } catch (e: IOException) {
                // ignore
            }
        }
    }

    private fun sendSimple(out: OutputStream, code: Int, text: String) {
        val body = "$code $text"
        val h = "HTTP/1.1 $code $text\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\n" +
            "Connection: close\r\n\r\n$body"
        out.write(h.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun httpDate(): String {
        val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("GMT")
        return f.format(Date())
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }
}
