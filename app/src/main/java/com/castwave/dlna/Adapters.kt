package com.castwave.dlna

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MediaAdapter(
    private val scope: CoroutineScope,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    private var items: List<MediaItem> = emptyList()

    var playingId: String? = null
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    fun submit(list: List<MediaItem>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        var job: Job? = null
        var boundId: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle
        holder.subtitle.visibility = if (item.subtitle.isBlank()) View.GONE else View.VISIBLE

        val attr = if (item.id == playingId) com.google.android.material.R.attr.colorPrimary
        else com.google.android.material.R.attr.colorOnSurface
        holder.title.setTextColor(MaterialColors.getColor(holder.itemView, attr))

        holder.itemView.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }

        holder.thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
        holder.thumb.setImageResource(
            when (item.type) {
                MediaType.AUDIO -> R.drawable.ic_audio
                MediaType.VIDEO -> R.drawable.ic_video
                MediaType.IMAGE -> R.drawable.ic_photo
            }
        )
        holder.job?.cancel()
        holder.boundId = item.id
        holder.job = scope.launch {
            val bmp = Thumbs.load(ctx, item, 160)
            if (bmp != null && holder.boundId == item.id) {
                holder.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                holder.thumb.setImageBitmap(bmp)
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.job?.cancel()
        holder.boundId = null
    }
}

class DeviceAdapter(
    private val onPick: (DlnaDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private var items: List<DlnaDevice> = emptyList()
    private var selectedUdn: String? = null

    fun submit(list: List<DlnaDevice>, selected: String?) {
        items = list
        selectedUdn = selected
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.devName)
        val sub: TextView = view.findViewById(R.id.devSub)
        val check: ImageView = view.findViewById(R.id.devCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        holder.name.text = d.friendlyName
        holder.sub.text = listOf(d.manufacturer, d.modelName, d.host).filter { it.isNotBlank() }.joinToString(" • ")
        holder.check.visibility = if (d.udn == selectedUdn) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { onPick(d) }
    }
}
