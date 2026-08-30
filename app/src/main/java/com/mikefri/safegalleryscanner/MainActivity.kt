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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var adapter: ImageAdapter
    private val imageUris = mutableListOf<Uri>()

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

    private fun scanGallery() {
        tvStatus.text = "Scan en cours..."

        Thread {
            imageUris.clear()
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Images.Media._ID)

            contentResolver.query(
                collection,
                projection,
                null,
                null,
                MediaStore.Images.Media.DATE_MODIFIED + " DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    imageUris.add(ContentUris.withAppendedId(collection, id))
                }
            }

            runOnUiThread { tvStatus.text = "Analyse en cours... 0/${imageUris.size}" }

            val detector = SkinDetector()
            val scores = FloatArray(imageUris.size)
            val pool = Executors.newFixedThreadPool(4)
            val latch = CountDownLatch(imageUris.size)
            val done = AtomicInteger(0)

            imageUris.forEachIndexed { i, uri ->
                pool.execute {
                    try {
                        val bmp = decodeSampled(this, uri, 96)
                        scores[i] = if (bmp != null) detector.score(bmp) else 0f
                    } catch (e: Exception) {
                        scores[i] = 0f
                    }
                    val d = done.incrementAndGet()
                    if (d % 100 == 0) {
                        runOnUiThread { tvStatus.text = "Analyse en cours... $d/${imageUris.size}" }
                    }
                    latch.countDown()
                }
            }
            latch.await()
            pool.shutdown()

            val flagged = scores.count { it >= NSFW_THRESHOLD }

            runOnUiThread {
                adapter.scores = scores
                adapter.notifyDataSetChanged()
                tvStatus.text = "Photos : ${imageUris.size} | Suspectes : $flagged"
            }
        }.start()
    }
}