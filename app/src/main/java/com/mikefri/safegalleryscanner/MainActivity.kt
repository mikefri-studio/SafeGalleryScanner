package com.mikefri.safegalleryscanner

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
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

    private val REQUEST_DETAIL = 78
    private val REQUEST_DELETE_QUICK = 89
    private val REQUEST_DELETE_MULTI = 90
    private lateinit var tvStatus: TextView
    private lateinit var adapter: ImageAdapter
    private lateinit var barSelection: LinearLayout
    private lateinit var tvSelCount: TextView
    private val imageUris = mutableListOf<Uri>()
    private val imageKeys = mutableListOf<String>()
    private var scores = FloatArray(0)
    private var modelInfo = "v1-peau"
    private var pendingQuickVault: File? = null
    private var pendingQuickVaultIndex = -1
    private var pendingMultiCopies = mutableListOf<File>()
    private var pendingMultiIndices = mutableListOf<Int>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.all { it.value }) {
            loadPhotos()
        } else {
            tvStatus.text = "Permission refusee. Impossible de charger la galerie."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        barSelection = findViewById(R.id.barSelection)
        tvSelCount = findViewById(R.id.tvSelCount)

        findViewById<Button>(R.id.btnScan).setOnClickListener { askPermissionForScan() }
        findViewById<Button>(R.id.btnVault).setOnClickListener { startActivity(Intent(this, VaultActivity::class.java)) }
        findViewById<Button>(R.id.btnCancelSel).setOnClickListener { exitSelectionMode() }
        findViewById<Button>(R.id.btnVaultMulti).setOnClickListener { vaultSelection() }

        val recycler = findViewById<RecyclerView>(R.id.recyclerImages)
        recycler.layoutManager = GridLayoutManager(this, 3)
        adapter = ImageAdapter(this, imageUris)
        recycler.adapter = adapter

        adapter.onClick = { pos ->
            if (pos in imageUris.indices) {
                if (adapter.selected.isNotEmpty()) {
                    toggleSelection(pos)
                } else if (scores.size == imageUris.size && scores[pos] >= NSFW_THRESHOLD) {
                    val intent = Intent(this, DetailActivity::class.java)
                    intent.putExtra("uri", imageUris[pos].toString())
                    intent.putExtra("score", scores[pos])
                    startActivityForResult(intent, REQUEST_DETAIL)
                } else {
                    showQuickVaultDialog(pos)
                }
            }
        }

        adapter.onLongClick = { pos ->
            if (adapter.selected.isEmpty()) enterSelectionMode(pos)
            else toggleSelection(pos)
        }

        askPermissionForLoad()
    }

    private fun enterSelectionMode(pos: Int) {
        adapter.selected = setOf(pos)
        adapter.notifyItemChanged(pos)
        updateSelectionBar()
        barSelection.visibility = View.VISIBLE
    }

    private fun toggleSelection(pos: Int) {
        val newSel = adapter.selected.toMutableSet()
        if (pos in newSel) newSel.remove(pos) else newSel.add(pos)
        adapter.selected = newSel
        adapter.notifyItemChanged(pos)
        if (newSel.isEmpty()) exitSelectionMode()
        else updateSelectionBar()
    }

    private fun exitSelectionMode() {
        val old = adapter.selected
        adapter.selected = emptySet()
        old.forEach { adapter.notifyItemChanged(it) }
        barSelection.visibility = View.GONE
    }

    private fun updateSelectionBar() {
        tvSelCount.text = "${adapter.selected.size} selectionnee(s)"
    }

    private fun vaultSelection() {
        val positions = adapter.selected.sorted()
        if (positions.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("Mettre au coffre ?")
            .setMessage("${positions.size} photo(s) vont etre cachees dans le coffre et retirees de la galerie.")
            .setPositiveButton("Oui") { _, _ -> doVaultMulti(positions) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun doVaultMulti(positions: List<Int>) {
        pendingMultiCopies.clear()
        pendingMultiIndices.clear()
        val deleteTargets = mutableListOf<Uri>()

        for (pos in positions) {
            if (pos !in imageUris.indices) continue
            val uri = imageUris[pos]
            val f = VaultManager.saveToVault(this, uri)
            if (f != null) {
                pendingMultiCopies.add(f)
                pendingMultiIndices.add(pos)
                deleteTargets.add(uri)
            }
        }

        if (deleteTargets.isEmpty()) {
            exitSelectionMode()
            return
        }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val pending = MediaStore.createDeleteRequest(contentResolver, deleteTargets)
                startIntentSenderForResult(pending.intentSender, REQUEST_DELETE_MULTI, null, 0, 0, 0)
                return
            } catch (e: Exception) {
                Log.e("MAIN", "createDeleteRequest: " + e.message)
            }
        }
        // Fallback Android < 11 : suppression une par une
        for (u in deleteTargets) {
            try { contentResolver.delete(u, null, null) } catch (e: Exception) { }
        }
        finishMultiVault(true)
    }

    private fun rollbackMultiVault() {
        pendingMultiCopies.forEach { it.delete() }
        pendingMultiCopies.clear()
        pendingMultiIndices.clear()
    }

    private fun finishMultiVault(deleted: Boolean) {
        if (!deleted) {
            rollbackMultiVault()
            exitSelectionMode()
            return
        }
        // Retirer de la liste (du plus grand index au plus petit pour ne pas decaler)
        val sorted = pendingMultiIndices.sortedDescending()
        for (idx in sorted) {
            if (idx in imageUris.indices) {
                imageUris.removeAt(idx)
                imageKeys.removeAt(idx)
                if (scores.size > idx) {
                    scores = scores.filterIndexed { i, _ -> i != idx }.toFloatArray()
                }
            }
        }
        pendingMultiCopies.clear()
        pendingMultiIndices.clear()
        adapter.scores = scores
        adapter.selected = emptySet()
        adapter.notifyDataSetChanged()
        barSelection.visibility = View.GONE
        updateStatus()
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun askPermissionForLoad() {
        val perms = requiredPermissions()
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            loadPhotos()
        } else {
            permissionLauncher.launch(perms)
        }
    }

    private fun askPermissionForScan() {
        val perms = requiredPermissions()
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            scanGallery()
        } else {
            permissionLauncher.launch(perms)
        }
    }

    private fun loadPhotos() {
        tvStatus.text = "Chargement de la galerie..."

        Thread {
            imageUris.clear()
            imageKeys.clear()
            scores = FloatArray(0)
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

            runOnUiThread {
                adapter.scores = FloatArray(0)
                adapter.selected = emptySet()
                adapter.notifyDataSetChanged()
                tvStatus.text = "Photos : ${imageUris.size} | Appui long pour selection multiple"
            }
        }.start()
    }

    private fun showQuickVaultDialog(pos: Int) {
        AlertDialog.Builder(this)
            .setTitle("Mettre au coffre-fort ?")
            .setMessage("Cette photo sera cachee dans le coffre et retiree de la galerie.")
            .setPositiveButton("Oui, mettre au coffre") { _, _ -> quickVault(pos) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun quickVault(pos: Int) {
        val uri = imageUris[pos]
        val f = VaultManager.saveToVault(this, uri)
        if (f == null) return
        pendingQuickVault = f
        pendingQuickVaultIndex = pos

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val pending = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                startIntentSenderForResult(pending.intentSender, REQUEST_DELETE_QUICK, null, 0, 0, 0)
            } catch (e: Exception) {
                rollbackQuickVault()
            }
        } else {
            try {
                contentResolver.delete(uri, null, null)
                finishQuickVault(true)
            } catch (e: Exception) {
                rollbackQuickVault()
            }
        }
    }

    private fun rollbackQuickVault() {
        pendingQuickVault?.delete()
        pendingQuickVault = null
        pendingQuickVaultIndex = -1
    }

    private fun finishQuickVault(deleted: Boolean) {
        if (!deleted) {
            rollbackQuickVault()
        } else if (pendingQuickVaultIndex >= 0 && pendingQuickVaultIndex < imageUris.size) {
            imageUris.removeAt(pendingQuickVaultIndex)
            imageKeys.removeAt(pendingQuickVaultIndex)
            if (scores.size == imageUris.size + 1) {
                scores = scores.filterIndexed { i, _ -> i != pendingQuickVaultIndex }.toFloatArray()
            }
            adapter.scores = scores
            adapter.notifyDataSetChanged()
            updateStatus()
        }
        pendingQuickVault = null
        pendingQuickVaultIndex = -1
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DETAIL && resultCode == RESULT_OK && data != null) {
            val uriStr = data.getStringExtra("uri") ?: return
            val deleted = data.getBooleanExtra("deleted", false)
            val keep = data.getBooleanExtra("keep", false)
            val idx = imageUris.indexOfFirst { it.toString() == uriStr }
            if (idx < 0) return
            if (keep) {
                scores[idx] = 0f
                try { cacheFile().appendText(imageKeys[idx] + "\t0.0\n") } catch (e: Exception) { }
                adapter.scores = scores
                adapter.notifyItemChanged(idx)
                updateStatus()
            } else if (deleted) {
                imageUris.removeAt(idx)
                imageKeys.removeAt(idx)
                scores = scores.filterIndexed { i, _ -> i != idx }.toFloatArray()
                adapter.scores = scores
                adapter.notifyDataSetChanged()
                updateStatus()
            }
        } else if (requestCode == REQUEST_DELETE_QUICK) {
            finishQuickVault(resultCode == RESULT_OK)
        } else if (requestCode == REQUEST_DELETE_MULTI) {
            finishMultiVault(resultCode == RESULT_OK)
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

    private fun updateStatus() {
        val flagged = scores.count { it >= NSFW_THRESHOLD }
        if (scores.isEmpty()) {
            tvStatus.text = "Photos : ${imageUris.size} | Appui long pour selection multiple"
        } else {
            tvStatus.text = "Photos : ${imageUris.size} | Suspectes : $flagged | modele : $modelInfo"
        }
    }

    private fun scanGallery() {
        tvStatus.text = "Scan en cours..."

        Thread {
            val detector = ensureDetector()

            val cache = ConcurrentHashMap<String, Float>()
            try {
                cacheFile().readLines().forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size == 2) cache[parts[0]] = parts[1].toFloatOrNull() ?: 0f
                }
            } catch (e: Exception) { }

            runOnUiThread { tvStatus.text = "Analyse IA en cours... 0/${imageUris.size}" }

            scores = FloatArray(imageUris.size)
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

            runOnUiThread {
                adapter.scores = scores
                adapter.notifyDataSetChanged()
                updateStatus()
            }
        }.start()
    }
}