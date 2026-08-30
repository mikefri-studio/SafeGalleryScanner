package com.mikefri.safegalleryscanner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer

class NudeNetDetector(modelPath: String) {

    data class Detection(val rect: RectF, val label: String, val score: Float)

    companion object {
        val CLASS_NAMES = listOf(
            "FEMALE_BREAST_EXPOSED", "FEMALE_BREAST_COVERED", "BUTTOCKS_EXPOSED",
            "ANUS_EXPOSED", "FEET_EXPOSED", "MALE_BREAST_EXPOSED", "ARMPITS_EXPOSED",
            "VAGINA_EXPOSED", "PENIS_EXPOSED"
        )
    }

    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(modelPath, OrtSession.SessionOptions())
    private val inputName = session.inputNames.first()
    private val inputSize: Int

    init {
        var s = 416
        try {
            val info = session.inputInfo[inputName]
            if (info != null && info.info is TensorInfo) {
                val shape = (info.info as TensorInfo).shape
                if (shape.size == 4 && shape[2] > 0) s = shape[2].toInt()
            }
        } catch (e: Exception) { }
        inputSize = s
        try {
            Log.i("NUDE", "input=$inputName size=$inputSize")
            for ((i, n) in session.outputNames.withIndex()) {
                val sh = (session.outputInfo[n]?.info as? TensorInfo)?.shape
                Log.i("NUDE", "output[$i]=$n shape=" + (sh?.joinToString() ?: "?"))
            }
        } catch (e: Exception) { Log.e("NUDE", "log shapes: " + e.message) }
    }

    fun detect(bmp: Bitmap): List<Detection> {
        val resized = Bitmap.createScaledBitmap(bmp, inputSize, inputSize, true)
        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val n = inputSize * inputSize
        val buf = FloatArray(3 * n)
        for (i in 0 until n) {
            val p = pixels[i]
            buf[i] = (((p shr 16) and 0xFF) / 255f)
            buf[n + i] = (((p shr 8) and 0xFF) / 255f)
            buf[2 * n + i] = ((p and 0xFF) / 255f)
        }

        OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                val matrix = toMatrix(res.get(0).value) ?: return emptyList()
                return decode(matrix)
            }
        }
    }

    private fun toMatrix(v: Any?): Array<FloatArray>? {
        if (v is Array<*> && v.isNotEmpty() && v[0] is FloatArray) return v as Array<FloatArray>
        if (v is Array<*> && v.isNotEmpty() && v[0] is Array<*> && (v[0] as Array<*>).isNotEmpty() && (v[0] as Array<*>)[0] is FloatArray) {
            return (v[0] as Array<*>) as Array<FloatArray>
        }
        return null
    }

    private fun decode(mIn: Array<FloatArray>): List<Detection> {
        var m = mIn
        if (m.size < m[0].size) {
            val rows = m.size
            val cols = m[0].size
            val t = Array(cols) { j -> FloatArray(rows) { i -> m[i][j] } }
            m = t
        }
        val cols = m[0].size
        if (cols < 5) return emptyList()
        val numClasses = cols - 4

        val candidates = mutableListOf<Detection>()
        for (row in m) {
            var best = -1
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val s = row[4 + c]
                if (s > bestScore) { bestScore = s; best = c }
            }
            if (bestScore >= 0.45f && best >= 0) {
                val cx = row[0] / inputSize
                val cy = row[1] / inputSize
                val w = row[2] / inputSize
                val h = row[3] / inputSize
                val label = if (numClasses == CLASS_NAMES.size) CLASS_NAMES[best] else "zone_$best"
                candidates.add(Detection(RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), label, bestScore))
            }
        }
        return nms(candidates)
    }

    private fun nms(dets: List<Detection>, iouThresh: Float = 0.5f): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }
        val keep = mutableListOf<Detection>()
        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            keep.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (!suppressed[j] && iou(sorted[i].rect, sorted[j].rect) > iouThresh) suppressed[j] = true
            }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val inter = RectF()
        if (!inter.setIntersect(a, b)) return 0f
        val interArea = inter.width() * inter.height()
        val aArea = a.width() * a.height()
        val bArea = b.width() * b.height()
        return interArea / (aArea + bArea - interArea)
    }
}

class SkinBlobDetector {
    fun detect(bmp: Bitmap): List<NudeNetDetector.Detection> {
        val w = 128
        val h = 128
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()

        val mask = BooleanArray(w * h)
        for (i in 0 until w * h) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
            val cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
            mask[i] = cb >= 77 && cb <= 127 && cr >= 133 && cr <= 173
        }

        val visited = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val results = mutableListOf<Triple<Int, RectF, Float>>()

        for (start in 0 until w * h) {
            if (!mask[start] || visited[start]) continue
            var top = 0
            stack[top++] = start
            visited[start] = true
            var count = 0
            var minX = w; var minY = h; var maxX = 0; var maxY = 0
            var head = 0
            while (head < top) {
                val idx = stack[head++]
                count++
                val x = idx % w
                val y = idx / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && mask[idx - 1] && !visited[idx - 1]) { visited[idx - 1] = true; stack[top++] = idx - 1 }
                if (x < w - 1 && mask[idx + 1] && !visited[idx + 1]) { visited[idx + 1] = true; stack[top++] = idx + 1 }
                if (y > 0 && mask[idx - w] && !visited[idx - w]) { visited[idx - w] = true; stack[top++] = idx - w }
                if (y < h - 1 && mask[idx + w] && !visited[idx + w]) { visited[idx + w] = true; stack[top++] = idx + w }
            }
            if (count >= w * h * 5 / 100) {
                val boxArea = ((maxX - minX + 1) * (maxY - minY + 1)).toFloat()
                val density = (count / boxArea).coerceIn(0f, 1f)
                results.add(Triple(count, RectF(minX.toFloat() / w, minY.toFloat() / h, (maxX + 1).toFloat() / w, (maxY + 1).toFloat() / h), density))
            }
        }

        return results.sortedByDescending { it.first }.take(3).map {
            NudeNetDetector.Detection(it.second, "ZONE_EXPOSEE", it.third)
        }
    }
}

object NudeNetProvider {
    @Volatile private var detector: NudeNetDetector? = null
    @Volatile private var failed = false
    private val skinFallback = SkinBlobDetector()

    fun detect(context: Context, bmp: Bitmap): List<NudeNetDetector.Detection> {
        detector?.let { d ->
            return try { d.detect(bmp) } catch (e: Exception) { emptyList() }
        }
        if (!failed) {
            val f = File(context.filesDir, "nudenet.onnx")
            try {
                if (!f.exists()) {
                    val urls = listOf(
                        "https://huggingface.co/qualcomm/NudeNet/resolve/main/NudeNet.onnx",
                        "https://huggingface.co/qualcomm/NudeNet/resolve/main/model.onnx",
                        "https://huggingface.co/notAI-tech/NudeNet/resolve/main/NudeNet_v3.onnx"
                    )
                    var ok = false
                    for (u in urls) {
                        Log.i("NUDE", "essai: $u")
                        if (download(u, f) && f.length() > 2_000_000) { ok = true; break }
                    }
                    if (!ok) { f.delete(); failed = true }
                }
                if (!failed) {
                    val nd = NudeNetDetector(f.absolutePath)
                    detector = nd
                    Log.i("NUDE", "mode: nudenet")
                    return try { nd.detect(bmp) } catch (e: Exception) { emptyList() }
                }
            } catch (e: Exception) {
                Log.e("NUDE", "init: " + e.message)
                failed = true
            }
        }
        Log.i("NUDE", "mode: skinblob (fallback)")
        return skinFallback.detect(bmp)
    }

    private fun download(url: String, dest: File): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 60000
            conn.connect()
            if (conn.responseCode == 200) {
                conn.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                true
            } else false
        } catch (e: Exception) { false }
    }
}