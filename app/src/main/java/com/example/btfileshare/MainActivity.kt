package com.example.btfileshare

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: BluetoothAdapter?
    private lateinit var scanner: DeviceScanner
    private lateinit var transfer: TransferManager
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var statusText: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var logText: TextView
    private lateinit var btnScan: MaterialButton

    private val devices = LinkedHashMap<String, DeviceInfo>()
    private var pendingFile: PendingFile? = null
    private var lastProgressUpdate = 0L
    private val uiScope = CoroutineScope(Dispatchers.Main)

    private data class PendingFile(val uri: Uri, val name: String, val size: Long)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) {
                onPermissionsGranted()
            } else {
                statusText.text = getString(R.string.status_permission_denied)
                Toast.makeText(this, "Permissions Bluetooth refusées", Toast.LENGTH_LONG).show()
            }
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) onPermissionsGranted()
            else statusText.text = getString(R.string.status_bt_disabled)
        }

    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onFilePicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = getSystemService(BluetoothManager::class.java)?.adapter

        statusText = findViewById(R.id.status_text)
        progress = findViewById(R.id.progress)
        logText = findViewById(R.id.log_text)
        btnScan = findViewById(R.id.btn_scan)
        val btnVisible = findViewById<MaterialButton>(R.id.btn_visible)
        val btnPick = findViewById<MaterialButton>(R.id.btn_pick)
        val list = findViewById<RecyclerView>(R.id.devices_list)

        deviceAdapter = DeviceAdapter(::onDeviceClicked)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = deviceAdapter

        transfer = TransferManager(
            context = this,
            onStatus = { postStatus(it) },
            onProgress = { name, sent, total -> postProgress(name, sent, total) },
            onFileReceived = { name -> postReceived(name) }
        )

        scanner = DeviceScanner(
            activity = this,
            onDevice = { postDevice(it) },
            onBleAppleFound = { address, name -> postAppleDevice(address, name) },
            onFinished = { runOnUiThread { statusText.text = getString(R.string.status_scan_done) } }
        )

        btnScan.setOnClickListener { startScan() }
        btnVisible.setOnClickListener { makeVisible() }
        btnPick.setOnClickListener { pickFile() }

        requestPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.stop()
        transfer.stop()
    }

    private fun requiredPermissions(): List<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
            if (Build.VERSION.SDK_INT <= 28) {
                perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }
        return perms
    }

    private fun requestPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) onPermissionsGranted()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun onPermissionsGranted() {
        val a = adapter
        if (a == null) {
            statusText.text = getString(R.string.status_no_bt)
            return
        }
        if (!a.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        statusText.text = getString(R.string.status_idle)
        transfer.startServer()
        loadPairedDevices()
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        val a = adapter ?: return
        try {
            a.bondedDevices?.forEach { device ->
                postDevice(
                    DeviceInfo(
                        name = device.name ?: "",
                        address = device.address,
                        isApple = DeviceInfo.isAppleName(device.name),
                        paired = true
                    )
                )
            }
        } catch (e: SecurityException) {
        }
    }

    private fun startScan() {
        if (!scanner.hasPermission) {
            requestPermissions()
            return
        }
        statusText.text = getString(R.string.status_scanning)
        scanner.start()
    }

    private fun makeVisible() {
        val discoverable = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        discoverable.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        discoverableLauncher.launch(discoverable)
        postStatus("Visible par les autres appareils pendant 5 minutes")
    }

    private fun pickFile() {
        pickFileLauncher.launch(arrayOf("*/*"))
    }

    private fun onFilePicked(uri: Uri) {
        val name = queryFileName(uri) ?: "fichier-${System.currentTimeMillis()}"
        val size = queryFileSize(uri) ?: -1L
        if (size <= 0) {
            postStatus("Impossible de lire ce fichier")
            return
        }
        pendingFile = PendingFile(uri, name, size)
        statusText.text = "Fichier prêt : $name (${formatSize(size)}). Tape un appareil pour l'envoyer."
    }

    private fun onDeviceClicked(device: DeviceInfo) {
        val file = pendingFile
        if (file == null) {
            Toast.makeText(this, "Choisis d'abord un fichier à envoyer", Toast.LENGTH_SHORT).show()
            return
        }
        if (device.isApple) {
            Toast.makeText(
                this,
                "iPhone/iPad détecté : iOS bloque le transfert de fichiers en Bluetooth classique. Utilise l'app avec un Android.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val a = adapter ?: return
        @Suppress("DEPRECATION")
        val remote = a.getRemoteDevice(device.address)
        postStatus("Connexion à ${device.displayName}…")
        uiScope.launch {
            val result = transfer.send(remote, file.uri, file.name, file.size)
            result.fold(
                onSuccess = {
                    postStatus("✅ $name envoyé à ${device.displayName}")
                    log("Envoyé $name → ${device.displayName}")
                },
                onFailure = { postStatus("❌ Échec vers ${device.displayName} : ${it.message ?: "erreur"}") }
            )
        }
    }

    private fun queryFileName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun queryFileSize(uri: Uri): Long? =
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    private fun postDevice(info: DeviceInfo) = runOnUiThread {
        val existing = devices[info.address]
        if (existing != null) {
            existing.isApple = existing.isApple || info.isApple
            existing.paired = existing.paired || info.paired
            if (info.rssi != Int.MIN_VALUE) {
                val updated = existing.copy(rssi = info.rssi)
                devices[info.address] = updated
            }
        } else {
            devices[info.address] = info
        }
        refreshList()
    }

    private fun postAppleDevice(address: String, name: String) = runOnUiThread {
        val existing = devices[address]
        if (existing != null) {
            if (!existing.isApple) {
                existing.isApple = true
                refreshList()
            }
        } else {
            devices[address] = DeviceInfo(name = name, address = address, isApple = true)
            refreshList()
        }
    }

    private fun refreshList() {
        val sorted = devices.values.sortedWith(
            compareByDescending<DeviceInfo> { it.paired }.thenByDescending { it.rssi }
        )
        deviceAdapter.submitList(sorted)
    }

    private fun postStatus(message: String) = runOnUiThread {
        statusText.text = message
    }

    private fun postProgress(fileName: String, sent: Int, total: Int) = runOnUiThread {
        val now = System.currentTimeMillis()
        if (now - lastProgressUpdate > 200 || sent >= total) {
            lastProgressUpdate = now
            progress.visibility = View.VISIBLE
            progress.max = total
            progress.setProgressCompat(sent, true)
            statusText.text = "$fileName : ${formatSize(sent.toLong())} / ${formatSize(total.toLong())}"
        }
    }

    private fun postReceived(fileName: String) = runOnUiThread {
        progress.visibility = View.GONE
        statusText.text = "📥 $fileName reçu (dossier Téléchargements)"
        log("Reçu $fileName → Téléchargements")
        Toast.makeText(this, "Fichier reçu : $fileName", Toast.LENGTH_LONG).show()
    }

    private fun log(line: String) = runOnUiThread {
        logText.append("• $line\n")
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format("%.1f Mo", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format("%.1f Ko", bytes / 1024.0)
        else -> "$bytes o"
    }
}
