package com.mikefri.safegalleryscanner

import android.app.AlertDialog
import android.content.ContentUris
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.security.MessageDigest

class VaultActivity : AppCompatActivity() {

    private val REQUEST_DELETE_IMPORT = 88
    private lateinit var prefs: SharedPreferences
    private lateinit var etPin: EditText
    private lateinit var etPin2: EditText
    private lateinit var btnPin: Button
    private lateinit var btnAdd: Button
    private lateinit var rv: RecyclerView
    private lateinit var tvTitle: TextView
    private var setupMode = false
    private val pendingCopies = mutableListOf<File>()

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) importToVault(uris.toList())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vault)
        prefs = getSharedPreferences("vault", MODE_PRIVATE)
        etPin = findViewById(R.id.etPin)
        etPin2 = findViewById(R.id.etPin2)
        btnPin = findViewById(R.id.btnPin)
        btnAdd = findViewById(R.id.btnAdd)
        rv = findViewById(R.id.rvVault)
        tvTitle = findViewById(R.id.tvVaultTitle)

        setupMode = !prefs.contains("pin_hash")
        etPin2.visibility = if (setupMode) View.VISIBLE else View.GONE
        tvTitle.text = if (setupMode) "Creez un code PIN pour le coffre" else "Entrez votre code PIN"
        btnPin.text = if (setupMode) "Creer le coffre" else "Ouvrir"

        btnAdd.setOnClickListener {
            pickLauncher.launch(arrayOf("image/*"))
        }

        btnPin.setOnClickListener {
            val p1 = etPin.text.toString()
            if (setupMode) {
                val p2 = etPin2.text.toString()
                if (p1.length < 4) { tvTitle.text = "PIN trop court (4 chiffres minimum)"; return@setOnClickListener }
                if (p1 != p2) { tvTitle.text = "Les deux codes ne correspondent pas"; return@setOnClickListener }
                prefs.edit().putString("pin_hash", sha256(p1)).apply()
                unlock()
            } else {
                if (sha256(p1) == prefs.getString("pin_hash", "")) unlock()
                else tvTitle.text = "Code incorrect"
            }
        }
    }

    private fun unlock() {
        etPin.visibility = View.GONE
        etPin2.visibility = View.GONE
        btnPin.visibility = View.GONE
        btnAdd.visibility = View.VISIBLE
        rv.visibility = View.VISIBLE
        loadVault()
    }

    private fun importToVault(uris: List<Uri>) {
        pendingCopies.clear()
        val deleteTargets = mutableListOf<Uri>()

        for (u in uris) {
            val f = VaultManager.saveToVault(this, u)
            if (f != null) {
                pendingCopies.add(f)
                try {
                    val docId = DocumentsContract.getDocumentId(u)
                    val id = docId.substringAfter(':').toLongOrNull()
                    if (id != null) {
                        deleteTargets.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
                    }
                } catch (e: Exception) { }
            }
        }

        if (deleteTargets.isEmpty()) {
            loadVault()
            return
        }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val pending = MediaStore.createDeleteRequest(contentResolver, deleteTargets)
                startIntentSenderForResult(pending.intentSender, REQUEST_DELETE_IMPORT, null, 0, 0, 0)
                return
            } catch (e: Exception) { }
        }
        for (t in deleteTargets) {
            try { contentResolver.delete(t, null, null) } catch (e: Exception) { }
        }
        Toast.makeText(this, "${pendingCopies.size} photo(s) mise(s) au coffre", Toast.LENGTH_SHORT).show()
        loadVault()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DELETE_IMPORT) {
            if (resultCode != RESULT_OK) {
                pendingCopies.forEach { it.delete() }
                pendingCopies.clear()
                Toast.makeText(this, "Ajout annule", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "${pendingCopies.size} photo(s) mise(s) au coffre", Toast.LENGTH_SHORT).show()
                pendingCopies.clear()
            }
            loadVault()
        }
    }

    private fun loadVault() {
        val files = VaultManager.listVault(this)
        tvTitle.text = "Coffre-fort : ${files.size} photo(s)"
        rv.layoutManager = GridLayoutManager(this, 3)
        rv.adapter = VaultAdapter(files)
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    inner class VaultAdapter(private val files: List<File>) : RecyclerView.Adapter<VaultAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val img: ImageView = view as ImageView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_image, parent, false))
        }

        override fun getItemCount() = files.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = files[position]
            holder.img.setBackgroundColor(0xFF333333.toInt())
            holder.img.setImageBitmap(null)
            holder.img.setOnClickListener { showViewer(f) }
            Thread {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bmp = BitmapFactory.decodeFile(f.absolutePath, opts)
                holder.img.post { holder.img.setImageBitmap(bmp) }
            }.start()
        }
    }

    private fun showViewer(f: File) {
        val view = layoutInflater.inflate(R.layout.activity_detail, null)
        view.findViewById<TextView>(R.id.tvScore).text = f.name
        val img = view.findViewById<ImageView>(R.id.imgFull)
        Thread {
            val bmp = BitmapFactory.decodeFile(f.absolutePath)
            img.post { img.setImageBitmap(bmp) }
        }.start()

        val dlg = AlertDialog.Builder(this).setView(view).create()
        val btnRestore = view.findViewById<Button>(R.id.btnKeep)
        val btnDel = view.findViewById<Button>(R.id.btnDelete)
        view.findViewById<Button>(R.id.btnVault).visibility = View.GONE
        btnRestore.text = "Restaurer"
        btnDel.text = "Supprimer du coffre"

        btnRestore.setOnClickListener {
            VaultManager.restoreToGallery(this, f)
            f.delete()
            dlg.dismiss()
            loadVault()
        }
        btnDel.setOnClickListener {
            f.delete()
            dlg.dismiss()
            loadVault()
        }
        dlg.show()
    }
}