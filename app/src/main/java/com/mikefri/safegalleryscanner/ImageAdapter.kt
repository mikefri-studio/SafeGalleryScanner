package com.mikefri.safegalleryscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        holder.img.tag = uri
        holder.img.setImageBitmap(null)
        pool.execute {
            val bmp = decodeSampled(uri, 200)
            holder.img.post {
                if (holder.img.tag == uri) holder.img.setImageBitmap(bmp)
            }
        }
    }

    private fun decodeSampled(uri: Uri, req: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            var sample = 1
            while (opts.outWidth / (sample * 2) >= req && opts.outHeight / (sample * 2) >= req) {
                sample *= 2
            }
            val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts2)
            }
        } catch (e: Exception) {
            null
        }
    }
}