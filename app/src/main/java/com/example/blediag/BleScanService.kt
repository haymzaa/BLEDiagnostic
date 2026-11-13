package com.example.blediag

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class BleScanService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false

    private val pruneRunnable = object : Runnable {
        override fun run() {
            BleDeviceRepository.prune(SystemClock.elapsedRealtime())
            handler.postDelayed(this, PRUNE_INTERVAL_MS)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            BleDeviceRepository.onObservation(BleParser.parse(result))
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { BleDeviceRepository.onObservation(BleParser.parse(it)) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code $errorCode")
            scanning = false
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) ||
            !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            Log.e(TAG, "Missing BLUETOOTH_SCAN or ACCESS_FINE_LOCATION")
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        if (!scanning) {
            startScan()
        }
        return START_NOT_STICKY
    }

    // Permission is checked in onStartCommand before this is called.
    @SuppressLint("MissingPermission")
    private fun startScan() {
        val leScanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (leScanner == null) {
            Log.e(TAG, "BluetoothLeScanner unavailable (no adapter, or Bluetooth is off)")
            stopSelf()
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .setReportDelay(0L)
            .build()

        try {
            leScanner.startScan(emptyList<ScanFilter>(), settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "startScan failed", e)
            stopSelf()
            return
        }

        scanner = leScanner
        scanning = true
        handler.postDelayed(pruneRunnable, PRUNE_INTERVAL_MS)
    }

    // stopScan is only reached after a scan was started with the permission granted.
    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        handler.removeCallbacks(pruneRunnable)
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan failed", e)
        }
        scanner = null
        scanning = false
        BleDeviceRepository.clear()
        super.onDestroy()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BLE scanning",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BleScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE Diagnostic")
            .setContentText("Scanning for BLE advertisements")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.example.blediag.action.STOP"
        private const val TAG = "BleScanService"
        private const val CHANNEL_ID = "ble_scan"
        private const val NOTIFICATION_ID = 1
        private const val PRUNE_INTERVAL_MS = 1_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BleScanService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleScanService::class.java))
        }
    }
}
