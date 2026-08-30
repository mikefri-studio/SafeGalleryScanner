package com.mikefri.safegalleryscanner

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class ImageAdapter(
    private val context: Context,
    private val uris: List<Uri>
) : RecyclerView.Adapter<ImageAdapter.VH>() {

    var scores: FloatArray = FloatArray(0)

    companion object {
        private val pool = Executors.newFixedThreadPool(4)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val img: ImageView = view as ImageView
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
            holder.img.setBackgroundColor(Color.RED)
            holder.img.setPadding(6, 6, 6, 6)
        } else {
            holder.img.setBackgroundColor(Color.TRANSPARENT)
            holder.img.setPadding(0, 0, 0, 0)
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