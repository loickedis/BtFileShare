package com.example.btfileshare

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLDecoder
import java.net.URLEncoder

class WifiShareServer(
    private val context: Context,
    private val sharedFiles: Map<String, Uri>,
    private val onFileReceived: (String) -> Unit
) : NanoHTTPD(DEFAULT_PORT) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.POST && session.uri == "/upload" -> handleUpload(session)
                session.uri == "/" || session.uri == "/index.html" ->
                    newFixedLengthResponse(Response.Status.OK, MIME_HTML, buildIndex())
                session.uri.startsWith("/file/") -> serveSharedFile(session.uri.removePrefix("/file/"))
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Erreur : ${e.message ?: e}")
        }
    }

    private fun buildIndex(): String {
        val items = if (sharedFiles.isEmpty()) {
            "<p>Aucun fichier partagé pour l'instant. Ajoute-en depuis l'app Android.</p>"
        } else {
            sharedFiles.entries.joinToString("\n") { (name, _) ->
                val encoded = URLEncoder.encode(name, "UTF-8")
                val safe = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                "<a href=\"/file/$encoded\">📄 $safe</a>"
            }
        }
        return """<!DOCTYPE html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>BtFileShare</title>
<style>body{font-family:-apple-system,sans-serif;margin:24px;max-width:600px;color:#111}
h2{margin-top:24px}a{display:block;padding:14px;border:1px solid #ddd;border-radius:10px;margin:8px 0;text-decoration:none;color:#1B6EF3;font-size:16px}
.up{margin-top:32px;padding:16px;border:2px dashed #bbb;border-radius:12px}
button{background:#1B6EF3;color:#fff;border:0;border-radius:8px;padding:12px 20px;font-size:16px;margin-top:8px}
input{margin-top:8px;font-size:15px}</style></head><body>
<h2>📥 Télécharger</h2>$items
<div class="up"><h3>📤 Envoyer vers Android</h3>
<form action="/upload" method="POST" enctype="multipart/form-data">
<input type="file" name="file" required>
<br><button type="submit">Envoyer</button></form></div>
</body></html>"""
    }

    private fun serveSharedFile(encodedName: String): Response {
        val name = URLDecoder.decode(encodedName, "UTF-8")
        val uri = sharedFiles[name]
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Fichier introuvable")
        val stream = context.contentResolver.openInputStream(uri)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Fichier illisible")
        val size = sizeOf(uri)
        val response = if (size > 0) {
            newFixedLengthResponse(Response.Status.OK, FileSaver.mimeTypeFor(name), stream, size)
        } else {
            newChunkedResponse(Response.Status.OK, FileSaver.mimeTypeFor(name), stream)
        }
        response.addHeader("Content-Disposition", "attachment; filename=\"${FileSaver.sanitizeFileName(name)}\"")
        return response
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val tmpFiles = HashMap<String, String>()
        session.parseBody(tmpFiles)
        val tmpPath = tmpFiles["file"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Aucun fichier reçu")
        val rawName = session.parameters["file"]?.firstOrNull()
        val saved = FileSaver.save(context, rawName ?: "upload-${System.currentTimeMillis()}", FileInputStream(File(tmpPath)))
        onFileReceived(saved)
        return newFixedLengthResponse(
            Response.Status.OK, MIME_HTML,
            """<!DOCTYPE html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="font-family:sans-serif;text-align:center;margin-top:60px">
<h2>✅ Fichier reçu</h2><p>$saved</p>
<p><a href="/">⬅️ Retour</a></p></body></html>"""
        )
    }

    private fun sizeOf(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val size = c.getLong(0)
                if (size > 0) return size
            }
        }
        return -1L
    }

    companion object {
        const val DEFAULT_PORT = 8080

        fun primaryIp(): String? {
            val addresses = mutableListOf<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        addresses.add(addr.hostAddress ?: continue)
                    }
                }
            }
            return addresses.firstOrNull { it.startsWith("192.168.43.") }
                ?: addresses.firstOrNull { it.startsWith("192.168.") }
                ?: addresses.firstOrNull()
        }
    }
}
