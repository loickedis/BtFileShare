package com.example.btfileshare

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

class TransferManager(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onProgress: (fileName: String, sent: Int, total: Int) -> Unit,
    private val onFileReceived: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: BluetoothServerSocket? = null
    private var serverJob: Job? = null

    @Volatile
    private var running = false

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    fun startServer() {
        if (running) return
        val a = adapter ?: return
        running = true
        serverJob = scope.launch {
            try {
                val server = a.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                serverSocket = server
                while (isActive && running) {
                    val socket = server.accept()
                    launch { handleConnection(socket) }
                }
            } catch (e: SecurityException) {
                onStatus("Permission Bluetooth refusée")
            } catch (e: IOException) {
                if (running) onStatus("Serveur Bluetooth arrêté")
            } finally {
                serverSocket?.close()
                serverSocket = null
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
        }
        serverSocket = null
    }

    @SuppressLint("MissingPermission")
    suspend fun send(device: BluetoothDevice, uri: Uri, fileName: String, fileSize: Long): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                adapter?.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                socket.connect()
                socket.use { s ->
                    val out = BufferedOutputStream(s.outputStream)
                    out.write("FILE|$fileName|$fileSize\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        copyWithProgress(input, out, fileName, fileSize)
                    } ?: error("Fichier illisible")
                    out.flush()
                }
            }.onFailure { e ->
                if (e is SecurityException) onStatus("Permission refusée")
            }
        }

    private fun handleConnection(socket: BluetoothSocket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
                val header = reader.readLine() ?: return
                val parts = header.split("|")
                if (parts.size < 3 || parts[0] != "FILE") {
                    onStatus("Transfert inconnu refusé")
                    return
                }
                val fileName = sanitizeFileName(parts[1])
                val fileSize = parts[2].toLongOrNull() ?: return
                if (fileSize <= 0) {
                    onStatus("Taille de fichier invalide")
                    return
                }
                val output = FileSaver.openOutputStream(context, fileName) ?: run {
                    onStatus("Impossible d'enregistrer le fichier")
                    return
                }
                output.use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var remaining = fileSize
                    var sent = 0L
                    while (remaining > 0) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val n = s.inputStream.read(buf, 0, toRead)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        sent += n
                        remaining -= n
                        onProgress(
                            fileName,
                            sent.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            fileSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        )
                    }
                    out.flush()
                }
                if (remaining == 0L) onFileReceived(fileName)
                else onStatus("Transfert incomplet : $fileName")
            }
        } catch (e: IOException) {
            onStatus("Erreur de réception : ${e.message ?: "IO"}")
        } catch (e: SecurityException) {
            onStatus("Permission refusée pour la réception")
        }
    }

    private fun copyWithProgress(input: InputStream, out: OutputStream, fileName: String, fileSize: Long) {
        val buf = ByteArray(BUFFER_SIZE)
        var sent = 0L
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            out.write(buf, 0, n)
            sent += n
            onProgress(
                fileName,
                sent.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                fileSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
        }
    }

    private fun sanitizeFileName(name: String): String = FileSaver.sanitizeFileName(name)

    companion object {
        val APP_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        private const val SERVICE_NAME = "BtFileShare"
        private const val BUFFER_SIZE = 8192
    }
}
