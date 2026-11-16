package com.castwave.dlna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the HTTP media server, SSDP discovery and the DLNA controller.
 * Keeps streaming alive while the app is in the background (wake lock + Wi-Fi lock + ongoing notification).
 */
class CastService : Service() {

    companion object {
        const val ACTION_START = "com.castwave.dlna.action.START"
        const val ACTION_TOGGLE = "com.castwave.dlna.action.TOGGLE"
        const val ACTION_STOP_PLAYBACK = "com.castwave.dlna.action.STOP_PLAYBACK"
        const val ACTION_SHUTDOWN = "com.castwave.dlna.action.SHUTDOWN"
        private const val CHANNEL_ID = "castwave_playback"
        private const val NOTIF_ID = 4101

        @Volatile
        var running: Boolean = false
            private set
    }

    inner class LocalBinder : Binder() {
        val service: CastService get() = this@CastService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var server: MediaServer
    private lateinit var discovery: SsdpDiscovery
    private val controller = DlnaController()

    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()

    @Volatile private var queue: List<MediaItem> = emptyList()
    @Volatile private var queueIndex = -1
    @Volatile private var sawPlaying = false
    @Volatile private var userStopped = false
    @Volatile private var started = false

    private var pollJob: Job? = null
    private var scanJob: Job? = null
    private var playJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        server = MediaServer(applicationContext)
        discovery = SsdpDiscovery(applicationContext)
        createChannel()
        scope.launch {
            discovery.devices.collect { list ->
                _state.update { s ->
                    val sel = s.selected
                    val refreshed = if (sel == null) null else (list.firstOrNull { it.udn == sel.udn } ?: sel)
                    s.copy(devices = list, selected = refreshed)
                }
            }
        }
        scope.launch {
            discovery.scanning.collect { sc -> _state.update { it.copy(scanning = sc) } }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_STOP_PLAYBACK -> stopPlayback()
            ACTION_SHUTDOWN -> shutdown()
            else -> {
                // Must always call startForeground after startForegroundService().
                startForegroundCompat(buildNotification())
                startCasting()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    private fun startCasting() {
        if (started) return
        started = true
        running = true
        acquireLocks()
        discovery.acquireMulticastLock()
        val ok = server.start()
        _state.update { it.copy(castEnabled = true, serverRunning = ok, serverUrl = serverUrl(ok)) }
        scanJob?.cancel()
        scanJob = scope.launch {
            while (isActive) {
                discovery.scan()
                val running = server.isRunning
                _state.update { it.copy(serverRunning = running, serverUrl = serverUrl(running)) }
                delay(20_000)
            }
        }
        updateNotification()
    }

    private fun serverUrl(ok: Boolean): String? {
        if (!ok) return null
        val ip = NetUtils.primaryIpv4() ?: return null
        return "http://$ip:${server.port}"
    }

    private fun teardown() {
        pollJob?.cancel()
        scanJob?.cancel()
        playJob?.cancel()
        server.stop()
        discovery.releaseMulticastLock()
        discovery.clear()
        releaseLocks()
        started = false
        running = false
    }

    // ------------------------------------------------------------------ public controls (called by UI)

    fun rescan() {
        scope.launch { discovery.scan() }
    }

    fun selectDevice(d: DlnaDevice) {
        scope.launch {
            val prev = _state.value.selected
            if (prev != null && prev.udn != d.udn && _state.value.current != null) {
                try {
                    controller.stop(prev)
                } catch (e: Exception) {
                    // old renderer may be gone
                }
            }
            _state.update { it.copy(selected = d, volume = -1, error = null) }
            updateNotification()
            try {
                val v = controller.getVolume(d)
                _state.update { it.copy(volume = v) }
            } catch (e: Exception) {
                // volume control is optional
            }
        }
    }

    fun playItems(items: List<MediaItem>, index: Int) {
        queue = items
        queueIndex = index
        playJob?.cancel()
        playJob = scope.launch { playCurrent() }
    }

    fun next() {
        if (queueIndex + 1 < queue.size) {
            queueIndex++
            playJob?.cancel()
            playJob = scope.launch { playCurrent() }
        }
    }

    fun previous() {
        if (queueIndex - 1 >= 0) {
            queueIndex--
            playJob?.cancel()
            playJob = scope.launch { playCurrent() }
        }
    }

    fun togglePlayPause() {
        scope.launch {
            val s = _state.value
            val d = s.selected ?: return@launch
            if (s.current == null) return@launch
            try {
                if (s.transport == TransportState.PLAYING) {
                    controller.pause(d)
                    _state.update { it.copy(transport = TransportState.PAUSED) }
                } else {
                    userStopped = false
                    controller.play(d)
                    _state.update { it.copy(transport = TransportState.PLAYING, error = null) }
                    startPolling()
                }
                updateNotification()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError("Command failed: ${e.message}")
            }
        }
    }

    fun stopPlayback() {
        scope.launch {
            val d = _state.value.selected ?: return@launch
            userStopped = true
            pollJob?.cancel()
            try {
                controller.stop(d)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError("Stop failed: ${e.message}")
            }
            _state.update { it.copy(transport = TransportState.STOPPED, positionMs = 0L) }
            updateNotification()
        }
    }

    fun seekTo(ms: Long) {
        scope.launch {
            val d = _state.value.selected ?: return@launch
            try {
                controller.seek(d, ms)
                _state.update { it.copy(positionMs = ms) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError("Seek not supported: ${e.message}")
            }
        }
    }

    fun setVolume(v: Int) {
        scope.launch {
            val d = _state.value.selected ?: return@launch
            try {
                controller.setVolume(d, v)
                _state.update { it.copy(volume = v) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError("Volume failed: ${e.message}")
            }
        }
    }

    fun shutdown() {
        scope.launch {
            val s = _state.value
            val d = s.selected
            if (d != null && s.current != null) {
                try {
                    controller.stop(d)
                } catch (e: Exception) {
                    // ignore
                }
            }
            teardown()
            _state.value = CastState()
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ------------------------------------------------------------------ playback internals

    private suspend fun playCurrent() {
        val item = queue.getOrNull(queueIndex) ?: return
        val dev = _state.value.selected
        if (dev == null) {
            setError("Select a target device first")
            return
        }
        if (!server.isRunning) {
            setError("Media server is not running")
            return
        }
        val ip = NetUtils.localIpFor(dev.host)
        if (ip == null) {
            setError("No local network address. Connect to Wi-Fi or enable the hotspot.")
            return
        }
        val url = server.urlFor(item, ip)
        sawPlaying = false
        userStopped = false
        pollJob?.cancel()
        _state.update {
            it.copy(
                current = item, transport = TransportState.TRANSITIONING, positionMs = 0L,
                durationMs = item.durationMs, error = null, queueIndex = queueIndex, queueSize = queue.size
            )
        }
        updateNotification()
        try {
            try {
                controller.stop(dev)
            } catch (e: Exception) {
                // renderer may already be idle
            }
            controller.setAvTransportUri(dev, url, item)
            controller.play(dev)
            _state.update { it.copy(transport = TransportState.PLAYING) }
            startPolling()
            updateNotification()
            try {
                val v = controller.getVolume(dev)
                _state.update { it.copy(volume = v) }
            } catch (e: Exception) {
                // optional
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError("Playback failed: ${e.message}")
            _state.update { it.copy(transport = TransportState.STOPPED) }
            updateNotification()
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var tick = 0
            var failures = 0
            while (isActive) {
                val dev = _state.value.selected ?: break
                try {
                    val pos = controller.getPositionInfo(dev)
                    if (tick % 2 == 0) handleTransport(controller.getTransportInfo(dev))
                    _state.update {
                        it.copy(
                            positionMs = pos.positionMs,
                            durationMs = if (pos.durationMs > 0) pos.durationMs else it.durationMs
                        )
                    }
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    if (failures >= 6) {
                        setError("Lost connection to ${dev.friendlyName}")
                        break
                    }
                }
                tick++
                delay(1000)
            }
        }
    }

    private fun handleTransport(ts: TransportState) {
        val prev = _state.value.transport
        if (ts == TransportState.PLAYING) sawPlaying = true
        val ended = sawPlaying && !userStopped && (ts == TransportState.STOPPED || ts == TransportState.NO_MEDIA)
        if (ended) {
            sawPlaying = false
            val cur = _state.value.current
            if (cur != null && cur.type != MediaType.IMAGE && queueIndex + 1 < queue.size) {
                queueIndex++
                playJob?.cancel()
                playJob = scope.launch { playCurrent() }
                return
            }
        }
        if (ts != TransportState.UNKNOWN && ts != prev) {
            _state.update { it.copy(transport = ts) }
            updateNotification()
        }
    }

    private fun setError(msg: String) {
        _state.update { it.copy(error = msg) }
    }

    // ------------------------------------------------------------------ locks

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "castwave:cast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "castwave:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            // locks are best effort
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            // ignore
        }
        wakeLock = null
        wifiLock = null
    }

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Ongoing casting session"
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        if (!started) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun servicePi(action: String, code: Int): PendingIntent {
        val i = Intent(this, CastService::class.java).setAction(action)
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildNotification(): Notification {
        val s = _state.value
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val status = when (s.transport) {
            TransportState.PLAYING -> "Playing"
            TransportState.PAUSED -> "Paused"
            TransportState.TRANSITIONING -> "Loading…"
            else -> "Ready"
        }
        val target = s.selected?.friendlyName ?: "no device selected"
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.current?.title ?: getString(R.string.app_name))
            .setContentText("$status · $target")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (s.current != null) {
            if (s.transport == TransportState.PLAYING) {
                b.addAction(R.drawable.ic_notif_pause, getString(R.string.action_pause), servicePi(ACTION_TOGGLE, 1))
            } else {
                b.addAction(R.drawable.ic_notif_play, getString(R.string.action_play), servicePi(ACTION_TOGGLE, 1))
            }
            b.addAction(R.drawable.ic_notif_stop, getString(R.string.action_stop), servicePi(ACTION_STOP_PLAYBACK, 2))
        }
        b.addAction(R.drawable.ic_notif_off, getString(R.string.action_off), servicePi(ACTION_SHUTDOWN, 3))
        return b.build()
    }
}
