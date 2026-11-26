package com.example.btfileshare

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class DeviceScanner(
    private val activity: AppCompatActivity,
    private val onDevice: (DeviceInfo) -> Unit,
    private val onBleAppleFound: (address: String, name: String) -> Unit,
    private val onFinished: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var classicReceiver: BroadcastReceiver? = null
    private var bleCallback: ScanCallback? = null
    private var scanning = false

    private val adapter: BluetoothAdapter?
        get() = activity.getSystemService(BluetoothManager::class.java)?.adapter

    val hasPermission: Boolean
        get() = if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission")
    fun start() {
        if (scanning) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            onFinished()
            return
        }
        scanning = true
        registerClassicReceiver()
        if (!a.startDiscovery()) {
            stop()
            return
        }
        startBleScan(a)
        handler.postDelayed({ stop() }, DISCOVERY_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacksAndMessages(null)
        adapter?.let { a ->
            if (a.isDiscovering) a.cancelDiscovery()
            bleCallback?.let { cb -> a.bluetoothLeScanner?.stopScan(cb) }
        }
        bleCallback = null
        classicReceiver?.let { activity.unregisterReceiver(it) }
        classicReceiver = null
        onFinished()
    }

    private fun registerClassicReceiver() {
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        classicReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.bluetoothDevice() ?: return
                        val name = device.name
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        onDevice(
                            DeviceInfo(
                                name = name ?: "",
                                address = device.address,
                                rssi = rssi,
                                isApple = DeviceInfo.isAppleName(name),
                                paired = device.bondState == BluetoothDevice.BOND_BONDED
                            )
                        )
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> stop()
                }
            }
        }
        ContextCompat.registerReceiver(activity, classicReceiver!!, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan(a: BluetoothAdapter) {
        val scanner = a.bluetoothLeScanner ?: return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val appleData = result.scanRecord?.manufacturerSpecificData?.get(APPLE_COMPANY_ID) != null
                val appleName = DeviceInfo.isAppleName(result.device.name)
                if (appleData || appleName) {
                    onBleAppleFound(result.device.address, result.device.name ?: "Appareil Apple")
                }
            }
        }
        bleCallback = callback
        try {
            scanner.startScan(callback)
        } catch (e: Exception) {
            bleCallback = null
        }
    }

    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    companion object {
        private const val APPLE_COMPANY_ID = 0x004C
        private const val DISCOVERY_TIMEOUT_MS = 25_000L
    }
}
