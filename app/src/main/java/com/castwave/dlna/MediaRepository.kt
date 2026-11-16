package com.castwave.dlna

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MediaRepository {

    suspend fun loadAudio(ctx: Context): List<MediaItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaItem>()
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE
        )
        try {
            ctx.contentResolver.query(
                base, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val artist = c.getString(iArtist) ?: ""
                    val album = c.getString(iAlbum) ?: ""
                    out.add(
                        MediaItem(
                            id = "audio:$id",
                            uri = ContentUris.withAppendedId(base, id),
                            title = c.getString(iTitle) ?: "Unknown",
                            subtitle = if (artist.contains("unknown", ignoreCase = true)) "" else artist,
                            album = if (album.contains("unknown", ignoreCase = true)) "" else album,
                            durationMs = c.getLong(iDur),
                            mime = c.getString(iMime) ?: "audio/mpeg",
                            size = c.getLong(iSize),
                            type = MediaType.AUDIO,
                            mediaId = id,
                            albumId = c.getLong(iAlbumId)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // permission denied or provider error: return what we have
        }
        out
    }

    suspend fun loadVideos(ctx: Context): List<MediaItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaItem>()
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.SIZE
        )
        try {
            ctx.contentResolver.query(base, proj, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val dur = c.getLong(iDur)
                    val size = c.getLong(iSize)
                    val name = c.getString(iName) ?: "Video"
                    out.add(
                        MediaItem(
                            id = "video:$id",
                            uri = ContentUris.withAppendedId(base, id),
                            title = name.substringBeforeLast('.'),
                            subtitle = listOf(Fmt.duration(dur), Fmt.size(size)).filter { it.isNotEmpty() }.joinToString(" • "),
                            durationMs = dur,
                            mime = c.getString(iMime) ?: "video/mp4",
                            size = size,
                            type = MediaType.VIDEO,
                            mediaId = id
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        out
    }

    suspend fun loadPhotos(ctx: Context): List<MediaItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaItem>()
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.SIZE
        )
        try {
            ctx.contentResolver.query(base, proj, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val size = c.getLong(iSize)
                    out.add(
                        MediaItem(
                            id = "image:$id",
                            uri = ContentUris.withAppendedId(base, id),
                            title = (c.getString(iName) ?: "Photo").substringBeforeLast('.'),
                            subtitle = Fmt.size(size),
                            mime = c.getString(iMime) ?: "image/jpeg",
                            size = size,
                            type = MediaType.IMAGE,
                            mediaId = id
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        out
    }

    /** Build a [MediaItem] from a Storage Access Framework URI. */
    suspend fun fromUri(ctx: Context, uri: Uri): MediaItem? = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        var name = "file"
        var size = 0L
        try {
            cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (n >= 0) name = c.getString(n) ?: name
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        var mime: String? = cr.getType(uri)
        if (mime == null || mime == "application/octet-stream") {
            val ext = name.substringAfterLast('.', "").lowercase()
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: mime
        }
        val m = mime ?: "application/octet-stream"
        val type = when {
            m.startsWith("video/") -> MediaType.VIDEO
            m.startsWith("audio/") -> MediaType.AUDIO
            m.startsWith("image/") -> MediaType.IMAGE
            else -> return@withContext null
        }
        var duration = 0L
        var title = name.substringBeforeLast('.')
        var artist = ""
        var album = ""
        if (type != MediaType.IMAGE) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            } catch (e: Exception) {
                // metadata is optional
            } finally {
                try {
                    r.release()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        val subtitle = when {
            artist.isNotBlank() -> artist
            else -> listOf(if (duration > 0) Fmt.duration(duration) else "", Fmt.size(size))
                .filter { it.isNotEmpty() }.joinToString(" • ")
        }
        MediaItem(
            id = "doc:$uri",
            uri = uri,
            title = title,
            subtitle = subtitle,
            album = album,
            durationMs = duration,
            mime = m,
            size = size,
            type = type
        )
    }
}

/** Small in-memory thumbnail loader. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(ctx: Context, item: MediaItem, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${item.id}@$px"
        val hit = cache.get(key)
        if (hit != null) return@withContext hit
        var bmp: Bitmap? = null
        try {
            bmp = if (Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.loadThumbnail(item.uri, Size(px, px), null)
            } else {
                legacy(ctx, item)
            }
        } catch (e: Exception) {
            bmp = null
        }
        if (bmp == null && item.type == MediaType.IMAGE) {
            bmp = decodeSampled(ctx, item.uri, px)
        }
        if (bmp != null) cache.put(key, bmp)
        bmp
    }

    @Suppress("DEPRECATION")
    private fun legacy(ctx: Context, item: MediaItem): Bitmap? {
        return try {
            when (item.type) {
                MediaType.VIDEO ->
                    if (item.mediaId > 0) MediaStore.Video.Thumbnails.getThumbnail(
                        ctx.contentResolver, item.mediaId, MediaStore.Video.Thumbnails.MINI_KIND, null
                    ) else null
                MediaType.IMAGE ->
                    if (item.mediaId > 0) MediaStore.Images.Thumbnails.getThumbnail(
                        ctx.contentResolver, item.mediaId, MediaStore.Images.Thumbnails.MINI_KIND, null
                    ) else null
                MediaType.AUDIO ->
                    if (item.albumId > 0) {
                        val art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), item.albumId)
                        ctx.contentResolver.openInputStream(art)?.use { BitmapFactory.decodeStream(it) }
                    } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeSampled(ctx: Context, uri: Uri, px: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        }
    }
}
