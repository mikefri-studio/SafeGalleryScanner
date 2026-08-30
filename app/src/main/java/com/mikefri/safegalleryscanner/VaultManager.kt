package com.mikefri.safegalleryscanner

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

object VaultManager {

    fun vaultDir(context: Context): File {
        val d = File(context.filesDir, "vault")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun saveToVault(context: Context, uri: Uri): File? {
        return try {
            val dest = File(vaultDir(context), "vault_" + System.currentTimeMillis() + ".jpg")
            Log.i("VAULT", "Copie vers: " + dest.absolutePath)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output ->
                    val bytes = input.copyTo(output)
                    Log.i("VAULT", "Bytes copies: " + bytes)
                }
            }
            
            if (dest.exists() && dest.length() > 1000) {
                Log.i("VAULT", "Succes, taille: " + dest.length())
                dest
            } else {
                Log.e("VAULT", "Echec: fichier trop petit ou inexistant")
                dest.delete()
                null
            }
        } catch (e: Exception) {
            Log.e("VAULT", "Exception: " + e.message)
            null
        }
    }

    fun listVault(context: Context): List<File> {
        return vaultDir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun restoreToGallery(context: Context, f: File): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SafeGallery")
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri).use { out ->
                    f.inputStream().use { input -> input.copyTo(out!!) }
                }
            }
            uri
        } catch (e: Exception) { null }
    }
}