package com.mikefri.safegalleryscanner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import kotlin.math.exp
import java.nio.FloatBuffer

class OnnxDetector(modelPath: String) : PhotoDetector {

    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(modelPath, OrtSession.SessionOptions())
    private val inputName = session.inputNames.first()

    override fun score(bmp: Bitmap): Float {
        val resized = Bitmap.createScaledBitmap(bmp, 224, 224, true)
        val pixels = IntArray(224 * 224)
        resized.getPixels(pixels, 0, 224, 0, 0, 224, 224)

        val n = 224 * 224
        val buf = FloatArray(3 * n)
        for (i in 0 until n) {
            val p = pixels[i]
            buf[i] = (((p shr 16) and 0xFF) / 127.5f) - 1f
            buf[n + i] = (((p shr 8) and 0xFF) / 127.5f) - 1f
            buf[2 * n + i] = ((p and 0xFF) / 127.5f) - 1f
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
                return exps[NSFW_INDEX] / sum
            }
        }
    }
}