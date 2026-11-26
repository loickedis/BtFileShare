package com.example.btfileshare

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

object FileSaver {

    fun save(context: Context, fileName: String, input: InputStream): String {
        val safeName = sanitizeFileName(fileName)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(safeName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Impossible d'enregistrer le fichier")
            context.contentResolver.openOutputStream(uri)?.use { out -> input.copyTo(out) }
                ?: error("Flux d'écriture indisponible")
            return queryDisplayName(context, uri) ?: safeName
        }
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: error("Dossier Téléchargements indisponible")
        dir.mkdirs()
        var file = File(dir, safeName)
        if (file.exists()) {
            val base = safeName.substringBeforeLast('.')
            val ext = safeName.substringAfterLast('.', "")
            file = File(dir, "$base-${System.currentTimeMillis()}${if (ext.isEmpty()) "" else ".$ext"}")
        }
        FileOutputStream(file).use { out -> input.copyTo(out) }
        return file.name
    }

    fun openOutputStream(context: Context, fileName: String): OutputStream? {
        val safeName = sanitizeFileName(fileName)
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(safeName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            context.contentResolver.openOutputStream(uri)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?: return null
            dir.mkdirs()
            var file = File(dir, safeName)
            if (file.exists()) {
                val base = safeName.substringBeforeLast('.')
                val ext = safeName.substringAfterLast('.', "")
                file = File(dir, "$base-${System.currentTimeMillis()}${if (ext.isEmpty()) "" else ".$ext"}")
            }
            FileOutputStream(file)
        }
    }

    fun mimeTypeFor(fileName: String): String {
        val ext = MimeTypeMap.getFileExtensionFromUrl(fileName).lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun sanitizeFileName(name: String): String {
        val cleaned = name.substringAfterLast('/').replace(Regex("[/\\\\\n\r\"?]"), "_").trim()
        return cleaned.ifEmpty { "fichier-${System.currentTimeMillis()}" }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
