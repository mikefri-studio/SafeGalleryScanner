package com.mikefri.safegalleryscanner

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var adapter: ImageAdapter
    private val imageUris = mutableListOf<Uri>()
    private val imageKeys = mutableListOf<String>()
    private var modelInfo = "v1-peau"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.all { it.value }) scanGallery()
        else tvStatus.text = "Permission refusee. Impossible de scanner la galerie."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        findViewById<Button>(R.id.btnScan).setOnClickListener { askPermission() }

        val recycler = findViewById<RecyclerView>(R.id.recyclerImages)
        recycler.layoutManager = GridLayoutManager(this, 3)
        adapter = ImageAdapter(this, imageUris)
        recycler.adapter = adapter
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun askPermission() {
        val perms = requiredPermissions()
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            scanGallery()
        } else {
            permissionLauncher.launch(perms)
        }
    }

    private fun cacheFile() = File(filesDir, "scores_cache_v3.txt")

    private fun downloadFile(url: String, dest: File): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            conn.connect()
            if (conn.responseCode == 200) {
                conn.inputStream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
                true
            } else false
        } catch (e: Exception) { false }
    }

    private fun ensureDetector(): PhotoDetector {
        val modelF = File(filesDir, "nsfw_v2.onnx")
        val cfgF = File(filesDir, "cfg_v2.json")
        val preF = File(filesDir, "pre_v2.json")
        try {
            if (!modelF.exists()) {
                runOnUiThread { tvStatus.text = "Telechargement du modele IA v2 (une seule fois)..." }
                val repos = listOf(
                    "https://huggingface.co/AdamCodd/vit-base-nsfw-detector",
                    "https://huggingface.co/Falconsai/nsfw_image_detection"
                )
                for (base in repos) {
                    val modelUrls = listOf("$base/resolve/main/onnx/model.onnx", "$base/resolve/main/model.onnx")
                    var ok = false
                    for (u in modelUrls) {
                        if (downloadFile(u, modelF) && modelF.length() > 5_000_000) { ok = true; break }
                    }
                    if (ok) {
                        modelInfo = if (base.contains("AdamCodd")) "AdamCodd" else "Falconsai"
                        downloadFile("$base/resolve/main/config.json", cfgF)
                        downloadFile("$base/resolve/main/preprocessor_config.json", preF)
                        break
                    } else {
                        modelF.delete()
                    }
                }
            } else {
                modelInfo = "AdamCodd-cache"
            }
            if (modelF.exists() && modelF.length() > 5_000_000) {
                return OnnxDetector(modelF.absolutePath, cfgF.absolutePath, preF.absolutePath)
            }
        } catch (e: Exception) { }
        modelInfo = "v1-peau"
        return SkinDetector()
    }

    private fun scanGallery() {
        tvStatus.text = "Scan en cours..."

        Thread {
            imageUris.clear()
            imageKeys.clear()
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_MODIFIED
            )

            contentResolver.query(
                collection,
                projection,
                null,
                null,
                MediaStore.Images.Media.DATE_MODIFIED + " DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val dm = cursor.getLong(dateColumn)
                    imageUris.add(ContentUris.withAppendedId(collection, id))
                    imageKeys.add(id.toString() + "|" + dm.toString())
                }
            }

            val detector = ensureDetector()

            val cache = ConcurrentHashMap<String, Float>()
            try {
                cacheFile().readLines().forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size == 2) cache[parts[0]] = parts[1].toFloatOrNull() ?: 0f
                }
            } catch (e: Exception) { }

            runOnUiThread { tvStatus.text = "Analyse IA en cours... 0/${imageUris.size}" }

            val scores = FloatArray(imageUris.size)
            val skin = SkinDetector()
            val pool = Executors.newFixedThreadPool(4)
            val latch = CountDownLatch(imageUris.size)
            val done = AtomicInteger(0)

            imageUris.forEachIndexed { i, uri ->
                pool.execute {
                    try {
                        val key = imageKeys[i]
                        val cached = cache[key]
                        if (cached != null) {
                            scores[i] = cached
                        } else {
                            val bmp = decodeSampled(this, uri, 384)
                            var sc = 0f
                            if (bmp != null) {
                                if (skin.score(bmp) >= 0.3f) sc = detector.score(bmp)
                                bmp.recycle()
                            }
                            scores[i] = sc
                            cache[key] = sc
                        }
                    } catch (e: Exception) {
                        scores[i] = 0f
                    }
                    val d = done.incrementAndGet()
                    if (d % 25 == 0) {
                        runOnUiThread { tvStatus.text = "Analyse IA en cours... $d/${imageUris.size}" }
                    }
                    latch.countDown()
                }
            }
            latch.await()
            pool.shutdown()

            try {
                val sb = StringBuilder()
                for (i in imageKeys.indices) {
                    sb.append(imageKeys[i]).append('\t').append(scores[i]).append('\n')
                }
                cacheFile().writeText(sb.toString())
            } catch (e: Exception) { }

            val flagged = scores.count { it >= NSFW_THRESHOLD }
            val maxScore = if (scores.isEmpty()) 0f else scores.max()

            runOnUiThread {
                adapter.scores = scores
                adapter.notifyDataSetChanged()
                tvStatus.text = "Photos : ${imageUris.size} | Suspectes : $flagged | max : " +
                    "%.2f".format(maxScore) + " | modele : $modelInfo"
            }
        }.start()
    }
}