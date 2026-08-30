package com.mikefri.safegalleryscanner

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class DetailActivity : AppCompatActivity() {

    private val REQUEST_DELETE = 77
    private val REQUEST_WRITE = 79
    private lateinit var uri: Uri
    private lateinit var overlay: BoxOverlayView
    private var score = 0f
    private var pendingVault: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        applySystemInsets(this)

        val uriStr = intent.getStringExtra("uri") ?: ""
        if (uriStr.isEmpty()) { finish(); return }
        uri = Uri.parse(uriStr)
        score = intent.getFloatExtra("score", 0f)

        findViewById<TextView>(R.id.tvScore).text = "Score NSFW : %.2f".format(score)
        overlay = findViewById(R.id.boxOverlay)

        val img = findViewById<ImageView>(R.id.imgFull)
        Thread {
            val bmp = decodeSampled(this, uri, 1024)
            val dets = if (bmp != null) NudeNetProvider.detect(this, bmp) else emptyList()
            Log.i("NUDE", "detections: " + dets.size)
            img.post {
                img.setImageBitmap(bmp)
                if (bmp != null) {
                    overlay.imageWidth = bmp.width
                    overlay.imageHeight = bmp.height
                }
                overlay.detections = dets
            }
        }.start()

        findViewById<Button>(R.id.btnKeep).setOnClickListener { keep() }
        findViewById<Button>(R.id.btnVault).setOnClickListener { toVault() }
        findViewById<Button>(R.id.btnDelete).setOnClickListener { askDelete() }
    }

    private fun toVault() {
        val f = VaultManager.saveToVault(this, uri)
        if (f == null) { finishWith(false, false); return }
        pendingVault = f
        askDelete()
    }

    private fun askDelete() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val pending = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                startIntentSenderForResult(pending.intentSender, REQUEST_DELETE, null, 0, 0, 0)
            } catch (e: Exception) {
                rollbackVault(); finishWith(false, false)
            }
        } else if (Build.VERSION.SDK_INT == 29) {
            try {
                contentResolver.delete(uri, null, null)
                finishDelete(true)
            } catch (e: RecoverableSecurityException) {
                startIntentSenderForResult(e.userAction.actionIntent.intentSender, REQUEST_DELETE, null, 0, 0, 0)
            } catch (e: Exception) {
                rollbackVault(); finishWith(false, false)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_WRITE)
                return
            }
            try {
                contentResolver.delete(uri, null, null)
                finishDelete(true)
            } catch (e: Exception) {
                rollbackVault(); finishWith(false, false)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) askDelete()
            else { rollbackVault(); finishWith(false, false) }
        }
    }

    private fun rollbackVault() {
        pendingVault?.delete()
        pendingVault = null
    }

    private fun finishDelete(ok: Boolean) {
        if (!ok) rollbackVault()
        finishWith(ok, ok && pendingVault != null)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DELETE) finishDelete(resultCode == RESULT_OK)
    }

    private fun keep() {
        val r = Intent()
        r.putExtra("uri", uri.toString())
        r.putExtra("deleted", false)
        r.putExtra("vaulted", false)
        r.putExtra("keep", true)
        setResult(RESULT_OK, r)
        finish()
    }

    private fun finishWith(deleted: Boolean, vaulted: Boolean) {
        val r = Intent()
        r.putExtra("uri", uri.toString())
        r.putExtra("deleted", deleted)
        r.putExtra("vaulted", vaulted)
        r.putExtra("keep", false)
        setResult(RESULT_OK, r)
        finish()
    }
}