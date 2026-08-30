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
    var onClick: ((Int) -> Unit)? = null

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

        if (scores.size == uris.size && scores[position] >= NSFW_THRESHOLD) {
            holder.card.strokeColor = Color.RED
            holder.card.strokeWidth = (4 * context.resources.displayMetrics.density).toInt()
        } else {
            holder.card.strokeWidth = 0
        }

        holder.card.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick?.invoke(pos)
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