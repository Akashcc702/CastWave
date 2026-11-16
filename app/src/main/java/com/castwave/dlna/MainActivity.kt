package com.castwave.dlna

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var castSwitch: MaterialSwitch
    private lateinit var btnDevice: MaterialButton
    private lateinit var btnRescan: MaterialButton
    private lateinit var serverBadge: TextView
    private lateinit var deviceCount: TextView
    private lateinit var tabs: TabLayout
    private lateinit var list: RecyclerView
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var btnEmptyAction: MaterialButton
    private lateinit var nowCard: MaterialCardView
    private lateinit var npArt: ImageView
    private lateinit var npTitle: TextView
    private lateinit var npSub: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var seek: SeekBar
    private lateinit var volSeek: SeekBar
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnNext: MaterialButton

    private lateinit var adapter: MediaAdapter

    private var audioItems: List<MediaItem> = emptyList()
    private var videoItems: List<MediaItem> = emptyList()
    private var photoItems: List<MediaItem> = emptyList()
    private val localFiles = ArrayList<MediaItem>()
    private var currentTab = 0
    private var emptyAction: () -> Unit = {}

    private var cast: CastService? = null
    private var bindRequested = false
    private var stateJob: Job? = null
    private var latest = CastState()
    private var wasEnabled = false
    private var pendingEnable = false
    private var uiUpdating = false
    private var seeking = false
    private var volTouching = false
    private var lastArtId: String? = null

    // ------------------------------------------------------------------ activity results

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadMedia() }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            lifecycleScope.launch {
                for (uri in uris) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) {
                        // not every provider allows persistable grants; the grant lasts for this process anyway
                    }
                    val item = MediaRepository.fromUri(this@MainActivity, uri)
                    if (item != null && localFiles.none { it.id == item.id }) localFiles.add(item)
                }
                currentTab = 3
                tabs.getTabAt(3)?.select()
                showTab()
            }
        }

    // ------------------------------------------------------------------ service binding

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? CastService.LocalBinder)?.service ?: return
            cast = svc
            stateJob?.cancel()
            stateJob = lifecycleScope.launch {
                svc.state.collect { render(it) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cast = null
            stateJob?.cancel()
            wasEnabled = false
            render(CastState())
        }
    }

    private fun releaseService() {
        stateJob?.cancel()
        stateJob = null
        if (bindRequested) {
            try {
                unbindService(connection)
            } catch (e: IllegalArgumentException) {
                // already unbound
            }
            bindRequested = false
        }
        cast = null
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupToolbar()
        setupTabs()
        setupList()
        setupControls()
        render(CastState())
        if (hasMediaPermission()) loadMedia() else showTab()
        if (!hasMediaPermission()) permLauncher.launch(requiredPermissions())
    }

    override fun onStart() {
        super.onStart()
        if (CastService.running && !bindRequested) {
            bindRequested = bindService(Intent(this, CastService::class.java), connection, 0)
        }
    }

    override fun onStop() {
        releaseService()
        super.onStop()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        castSwitch = findViewById(R.id.castSwitch)
        btnDevice = findViewById(R.id.btnDevice)
        btnRescan = findViewById(R.id.btnRescan)
        serverBadge = findViewById(R.id.serverBadge)
        deviceCount = findViewById(R.id.deviceCount)
        tabs = findViewById(R.id.tabs)
        list = findViewById(R.id.list)
        emptyView = findViewById(R.id.emptyView)
        emptyText = findViewById(R.id.emptyText)
        btnEmptyAction = findViewById(R.id.btnEmptyAction)
        nowCard = findViewById(R.id.nowCard)
        npArt = findViewById(R.id.npArt)
        npTitle = findViewById(R.id.npTitle)
        npSub = findViewById(R.id.npSub)
        tvPos = findViewById(R.id.tvPos)
        tvDur = findViewById(R.id.tvDur)
        seek = findViewById(R.id.seek)
        volSeek = findViewById(R.id.volSeek)
        btnPrev = findViewById(R.id.btnPrev)
        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)
        btnNext = findViewById(R.id.btnNext)
    }

    private fun setupToolbar() {
        toolbar.inflateMenu(R.menu.menu_main)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_battery -> {
                    Toast.makeText(this, R.string.battery_hint, Toast.LENGTH_LONG).show()
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e: Exception) {
                        openAppSettings()
                    }
                    true
                }
                R.id.action_add_files -> {
                    pickFiles.launch(arrayOf("audio/*", "video/*", "image/*"))
                    true
                }
                R.id.action_app_settings -> {
                    openAppSettings()
                    true
                }
                else -> false
            }
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        } catch (e: Exception) {
            // nothing else to do
        }
    }

    private fun setupTabs() {
        listOf(R.string.tab_audio, R.string.tab_videos, R.string.tab_photos, R.string.tab_files).forEach {
            tabs.addTab(tabs.newTab().setText(it))
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                showTab()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupList() {
        adapter = MediaAdapter(lifecycleScope) { pos -> onItemClicked(pos) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    private fun setupControls() {
        castSwitch.setOnCheckedChangeListener { _, checked ->
            if (uiUpdating) return@setOnCheckedChangeListener
            if (checked) enableCast() else disableCast()
        }
        btnDevice.setOnClickListener { showDevicePicker() }
        btnRescan.setOnClickListener {
            val c = cast
            if (c != null && latest.castEnabled) c.rescan() else snack(getString(R.string.turn_on_cast))
        }
        btnEmptyAction.setOnClickListener { emptyAction() }
        btnPlay.setOnClickListener { cast?.togglePlayPause() }
        btnStop.setOnClickListener { cast?.stopPlayback() }
        btnNext.setOnClickListener { cast?.next() }
        btnPrev.setOnClickListener { cast?.previous() }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvPos.text = Fmt.duration(progress * 1000L)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                cast?.seekTo((sb?.progress ?: 0) * 1000L)
            }
        })
        volSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}

            override fun onStartTrackingTouch(sb: SeekBar?) {
                volTouching = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                volTouching = false
                cast?.setVolume(sb?.progress ?: 0)
            }
        })
    }

    // ------------------------------------------------------------------ cast on / off

    private fun enableCast() {
        pendingEnable = true
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val intent = Intent(this, CastService::class.java).setAction(CastService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        if (!bindRequested) {
            bindRequested = bindService(Intent(this, CastService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun disableCast() {
        pendingEnable = false
        val c = cast
        if (c != null) {
            c.shutdown()
        } else if (CastService.running) {
            startService(Intent(this, CastService::class.java).setAction(CastService.ACTION_SHUTDOWN))
        }
    }

    // ------------------------------------------------------------------ media loading

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun hasMediaPermission(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            listOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES
            )
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms.any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun loadMedia() {
        lifecycleScope.launch {
            audioItems = MediaRepository.loadAudio(this@MainActivity)
            videoItems = MediaRepository.loadVideos(this@MainActivity)
            photoItems = MediaRepository.loadPhotos(this@MainActivity)
            showTab()
        }
    }

    private fun currentList(): List<MediaItem> = when (currentTab) {
        0 -> audioItems
        1 -> videoItems
        2 -> photoItems
        else -> localFiles
    }

    private fun showTab() {
        val items = currentList()
        adapter.submit(items)
        if (items.isNotEmpty()) {
            emptyView.visibility = View.GONE
            return
        }
        emptyView.visibility = View.VISIBLE
        if (currentTab == 3) {
            emptyText.setText(R.string.empty_files)
            btnEmptyAction.visibility = View.VISIBLE
            btnEmptyAction.setText(R.string.pick_files)
            emptyAction = { pickFiles.launch(arrayOf("audio/*", "video/*", "image/*")) }
        } else if (!hasMediaPermission()) {
            emptyText.setText(R.string.perm_needed)
            btnEmptyAction.visibility = View.VISIBLE
            btnEmptyAction.setText(R.string.grant_access)
            emptyAction = { permLauncher.launch(requiredPermissions()) }
        } else {
            emptyText.setText(R.string.empty_media)
            btnEmptyAction.visibility = View.GONE
        }
    }

    private fun onItemClicked(pos: Int) {
        val c = cast
        if (c == null || !latest.castEnabled) {
            snack(getString(R.string.turn_on_cast))
            return
        }
        if (latest.selected == null) {
            snack(getString(R.string.choose_device_first))
            showDevicePicker()
            return
        }
        c.playItems(currentList(), pos)
    }

    // ------------------------------------------------------------------ device picker

    private fun showDevicePicker() {
        val c = cast
        if (c == null || !latest.castEnabled) {
            snack(getString(R.string.turn_on_cast))
            return
        }
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_devices, null)
        val rv = view.findViewById<RecyclerView>(R.id.deviceList)
        val empty = view.findViewById<TextView>(R.id.deviceEmpty)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.deviceProgress)
        val deviceAdapter = DeviceAdapter { d ->
            c.selectDevice(d)
            dialog.dismiss()
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = deviceAdapter
        view.findViewById<View>(R.id.btnSheetRescan).setOnClickListener { c.rescan() }
        dialog.setContentView(view)
        val job = lifecycleScope.launch {
            c.state.collect { s ->
                deviceAdapter.submit(s.devices, s.selected?.udn)
                empty.visibility = if (s.devices.isEmpty()) View.VISIBLE else View.GONE
                progress.visibility = if (s.scanning) View.VISIBLE else View.INVISIBLE
            }
        }
        dialog.setOnDismissListener { job.cancel() }
        dialog.show()
    }

    // ------------------------------------------------------------------ rendering

    private fun render(s: CastState) {
        latest = s
        if (s.castEnabled) pendingEnable = false

        if (!(pendingEnable && !s.castEnabled)) {
            uiUpdating = true
            castSwitch.isChecked = s.castEnabled
            uiUpdating = false
        }

        when {
            s.castEnabled && s.serverRunning ->
                setBadge(serverBadge, "Server ON  ${s.serverUrl ?: ""}".trim(), ContextCompat.getColor(this, R.color.badge_on))
            s.castEnabled ->
                setBadge(serverBadge, "Server error", ContextCompat.getColor(this, R.color.badge_error))
            else ->
                setBadge(serverBadge, "Server OFF", ContextCompat.getColor(this, R.color.badge_off))
        }

        btnDevice.text = s.selected?.friendlyName ?: getString(R.string.select_device)
        deviceCount.text = when {
            !s.castEnabled -> ""
            s.scanning -> "${s.devices.size} online · scanning…"
            else -> "${s.devices.size} online"
        }

        val item = s.current
        npTitle.text = item?.title ?: getString(R.string.nothing_playing)
        npSub.text = when {
            s.error != null -> s.error
            item != null -> listOf(item.subtitle, item.album).filter { it.isNotBlank() }.joinToString(" • ")
            else -> getString(R.string.pick_hint)
        }
        if (item?.id != lastArtId) {
            lastArtId = item?.id
            loadArt(item)
        }

        val dur = s.durationMs
        seek.max = (dur / 1000).toInt().coerceAtLeast(0)
        seek.isEnabled = dur > 0 && item != null
        if (!seeking) {
            seek.progress = (s.positionMs / 1000).toInt().coerceAtLeast(0)
            tvPos.text = Fmt.duration(s.positionMs)
        }
        tvDur.text = Fmt.duration(dur)

        if (s.transport == TransportState.PLAYING) {
            btnPlay.setIconResource(R.drawable.ic_pause)
            btnPlay.contentDescription = getString(R.string.action_pause)
        } else {
            btnPlay.setIconResource(R.drawable.ic_play)
            btnPlay.contentDescription = getString(R.string.action_play)
        }
        val hasItem = item != null
        btnPlay.isEnabled = hasItem
        btnStop.isEnabled = hasItem
        btnNext.isEnabled = hasItem && s.queueIndex + 1 < s.queueSize
        btnPrev.isEnabled = hasItem && s.queueIndex > 0

        volSeek.isEnabled = s.volume >= 0
        if (!volTouching && s.volume >= 0) volSeek.progress = s.volume

        adapter.playingId = item?.id

        if (wasEnabled && !s.castEnabled) releaseService()
        wasEnabled = s.castEnabled
    }

    private fun loadArt(item: MediaItem?) {
        if (item == null) {
            npArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
            npArt.setImageResource(R.drawable.ic_cast)
            return
        }
        lifecycleScope.launch {
            val bmp = Thumbs.load(this@MainActivity, item, 300)
            if (lastArtId != item.id) return@launch
            if (bmp != null) {
                npArt.scaleType = ImageView.ScaleType.CENTER_CROP
                npArt.setImageBitmap(bmp)
            } else {
                npArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
                npArt.setImageResource(
                    when (item.type) {
                        MediaType.AUDIO -> R.drawable.ic_audio
                        MediaType.VIDEO -> R.drawable.ic_video
                        MediaType.IMAGE -> R.drawable.ic_photo
                    }
                )
            }
        }
    }

    private fun setBadge(tv: TextView, text: String, color: Int) {
        tv.text = text
        val bg = GradientDrawable()
        bg.cornerRadius = 14f * resources.displayMetrics.density
        bg.setColor(color)
        tv.background = bg
    }

    private fun snack(msg: String) {
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).setAnchorView(nowCard).show()
    }
}
