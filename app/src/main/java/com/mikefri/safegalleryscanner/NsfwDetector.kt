package com.mikefri.safegalleryscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

const val NSFW_THRESHOLD = 0.5f
const val NSFW_INDEX = 1

fun decodeSampled(context: Context, uri: Uri, req: Int): Bitmap? {
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

interface PhotoDetector {
    fun score(bmp: Bitmap): Float
}

class SkinDetector : PhotoDetector {
    override fun score(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        if (w == 0 || h == 0) return 0f
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var skin = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
            val cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
            if (cb >= 77 && cb <= 127 && cr >= 133 && cr <= 173) skin++
        }
        return skin.toFloat() / (w * h)
    }
}