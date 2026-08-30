package com.mikefri.safegalleryscanner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp

class OnnxDetector(modelPath: String, configPath: String?, preprocPath: String?) : PhotoDetector {

    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(modelPath, OrtSession.SessionOptions())
    private val inputName = session.inputNames.first()

    private val mean: FloatArray
    private val std: FloatArray
    private val size: Int
    val nsfwIndex: Int

    init {
        var sz = 224
        try {
            val info = session.inputInfo[inputName]
            if (info != null && info.info is TensorInfo) {
                val tensorInfo = info.info as TensorInfo
                val shape = tensorInfo.shape
                if (shape.size == 4 && shape[2] > 0) sz = shape[2].toInt()
            }
        } catch (e: Exception) { }
        try {
            if (preprocPath != null && File(preprocPath).exists()) {
                val pc = JSONObject(File(preprocPath).readText())
                val s = pc.opt("size")
                if (s is Int) sz = s
                else if (s is JSONObject) {
                    val h = s.optInt("height", -1)
                    val w = s.optInt("width", -1)
                    val se = s.optInt("shortest_edge", -1)
                    if (h > 0) sz = h else if (w > 0) sz = w else if (se > 0) sz = se
                }
            }
        } catch (e: Exception) { }
        size = sz

        var idx = 1
        try {
            if (configPath != null && File(configPath).exists()) {
                val cfg = JSONObject(File(configPath).readText())
                val id2label = cfg.optJSONObject("id2label")
                if (id2label != null) {
                    val keys = id2label.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (id2label.optString(k, "").lowercase().contains("nsfw")) idx = k.toInt()
                    }
                }
            }
        } catch (e: Exception) { }
        nsfwIndex = idx

        var m = floatArrayOf(0.5f, 0.5f, 0.5f)
        var s = floatArrayOf(0.5f, 0.5f, 0.5f)
        try {
            if (preprocPath != null && File(preprocPath).exists()) {
                val pc = JSONObject(File(preprocPath).readText())
                val jm = pc.optJSONArray("image_mean")
                val js = pc.optJSONArray("image_std")
                if (jm != null && jm.length() == 3) m = floatArrayOf(jm.getDouble(0).toFloat(), jm.getDouble(1).toFloat(), jm.getDouble(2).toFloat())
                if (js != null && js.length() == 3) s = floatArrayOf(js.getDouble(0).toFloat(), js.getDouble(1).toFloat(), js.getDouble(2).toFloat())
            }
        } catch (e: Exception) { }
        mean = m
        std = s
        Log.i("NSFW", "modele charge. input=$inputName size=$size nsfwIndex=$idx")
    }

    override fun score(bmp: Bitmap): Float {
        try {
            val resized = Bitmap.createScaledBitmap(bmp, size, size, true)
            val pixels = IntArray(size * size)
            resized.getPixels(pixels, 0, size, 0, 0, size, size)

            val n = size * size
            val buf = FloatArray(3 * n)
            for (i in 0 until n) {
                val p = pixels[i]
                val r = ((p shr 16) and 0xFF) / 255.0f
                val g = ((p shr 8) and 0xFF) / 255.0f
                val b = (p and 0xFF) / 255.0f
                buf[i] = (r - mean[0]) / std[0]
                buf[n + i] = (g - mean[1]) / std[1]
                buf[2 * n + i] = (b - mean[2]) / std[2]
            }

            val buffer = FloatBuffer.wrap(buf)
            OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, size.toLong(), size.toLong())).use { t ->
                session.run(mapOf(inputName to t)).use { res ->
                    val logits = extractLogits(res)
                    if (logits.size < 2) return 0f
                    val mx = logits.max()
                    var sum = 0f
                    val exps = FloatArray(logits.size)
                    for (i in logits.indices) {
                        exps[i] = exp(logits[i] - mx)
                        sum += exps[i]
                    }
                    return exps[nsfwIndex.coerceAtMost(logits.size - 1)] / sum
                }
            }
        } catch (e: Exception) {
            Log.e("NSFW", "ERREUR score: " + e.javaClass.name + " : " + e.message)
            return 0f
        }
    }

    private fun extractLogits(res: OrtSession.Result): FloatArray {
        val v = res.get(0).value
        return when {
            v is FloatArray -> v
            v is Array<*> && v.isNotEmpty() && v[0] is FloatArray -> v[0] as FloatArray
            v is Array<*> && v.isNotEmpty() && v[0] is Array<*> -> {
                val inner = v[0] as Array<*>
                FloatArray(inner.size) { idx -> (inner[idx] as Number).toFloat() }
            }
            else -> floatArrayOf()
        }
    }
}