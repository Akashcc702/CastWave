package com.castwave.dlna

import android.net.Uri

enum class MediaType { AUDIO, VIDEO, IMAGE }

data class MediaItem(
    val id: String,
    val uri: Uri,
    val title: String,
    val subtitle: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val mime: String,
    val size: Long = 0L,
    val type: MediaType,
    val mediaId: Long = 0L,
    val albumId: Long = 0L
)

/** A UPnP/DLNA MediaRenderer discovered on the network. */
data class DlnaDevice(
    val udn: String,
    val friendlyName: String,
    val manufacturer: String,
    val modelName: String,
    val host: String,
    val location: String,
    val avTransportUrl: String,
    val avTransportType: String,
    val renderingUrl: String?,
    val renderingType: String?,
    val lastSeen: Long = System.currentTimeMillis()
)

enum class TransportState { STOPPED, PLAYING, PAUSED, TRANSITIONING, NO_MEDIA, UNKNOWN }

data class PositionInfo(val durationMs: Long, val positionMs: Long)

/** Everything the UI needs to render, published by [CastService]. */
data class CastState(
    val castEnabled: Boolean = false,
    val serverRunning: Boolean = false,
    val serverUrl: String? = null,
    val scanning: Boolean = false,
    val devices: List<DlnaDevice> = emptyList(),
    val selected: DlnaDevice? = null,
    val current: MediaItem? = null,
    val transport: TransportState = TransportState.UNKNOWN,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Int = -1,
    val error: String? = null,
    val queueIndex: Int = -1,
    val queueSize: Int = 0
)
