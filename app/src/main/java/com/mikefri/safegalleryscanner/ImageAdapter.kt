package com.mikefri.safegalleryscanner

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.Executors

class ImageAdapter(
    private val context: Context,
    private val uris: List<Uri>
) : RecyclerView.Adapter<ImageAdapter.VH>() {

    var scores: FloatArray = FloatArray(0)
    var selected: Set<Int> = emptySet()
    var onClick: ((Int) -> Unit)? = null
    var onLongClick: ((Int) -> Unit)? = null

    companion object {
        private val pool = Executors.newFixedThreadPool(4)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view as MaterialCardView
        val img: ImageView = view.findViewById(R.id.imgThumb)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = uris.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val uri = uris[position]

        when {
            position in selected -> {
                // Mode selection : bordure bleue epaisse
                holder.card.strokeColor = Color.parseColor("#1976D2")
                holder.card.strokeWidth = (6 * context.resources.displayMetrics.density).toInt()
                holder.img.alpha = 0.7f
            }
            scores.size == uris.size && scores[position] >= NSFW_THRESHOLD -> {
                // Suspecte : bordure rouge fine
                holder.card.strokeColor = Color.RED
                holder.card.strokeWidth = (4 * context.resources.displayMetrics.density).toInt()
                holder.img.alpha = 1f
            }
            else -> {
                holder.card.strokeWidth = 0
                holder.img.alpha = 1f
            }
        }

        holder.card.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick?.invoke(pos)
        }
        holder.card.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLongClick?.invoke(pos)
            true
        }

        holder.img.tag = uri
        holder.img.setImageBitmap(null)
        pool.execute {
            val bmp = decodeSampled(context, uri, 200)
            holder.img.post {
                if (holder.img.tag == uri) holder.img.setImageBitmap(bmp)
            }
        }
    }
}