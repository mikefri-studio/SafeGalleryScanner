package com.mikefri.safegalleryscanner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
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
    val nsfwIndex: Int

    init {
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
    }

    override fun score(bmp: Bitmap): Float {
        val resized = Bitmap.createScaledBitmap(bmp, 224, 224, true)
        val pixels = IntArray(224 * 224)
        resized.getPixels(pixels, 0, 224, 0, 0, 224, 224)

        val n = 224 * 224
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
        val tensor = OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, 224, 224))
        tensor.use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                val logits = (res.get(0).value as Array<FloatArray>)[0]
                val max = logits.max()
                var sum = 0f
                val exps = FloatArray(logits.size)
                for (i in logits.indices) {
                    exps[i] = exp(logits[i] - max)
                    sum += exps[i]
                }
                return exps[nsfwIndex] / sum
            }
        }
    }
}