package com.example.blediag

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.os.SystemClock

data class BleObservation(
    val address: String,
    val oui: String,
    val randomizedLikely: Boolean,
    val name: String?,
    val rssi: Int,
    val txPower: Int?,
    val timestampMs: Long,
    val guess: DeviceGuess,
)

object BleParser {

    // Permission is checked before scanning starts, so results only arrive once it is granted.
    @SuppressLint("MissingPermission")
    fun parse(result: ScanResult): BleObservation {
        val address = result.device.address.uppercase()
        val record = result.scanRecord
        val name = record?.deviceName
        return BleObservation(
            address = address,
            oui = address.take(8),
            randomizedLikely = isLocallyAdministered(address),
            name = name,
            rssi = result.rssi,
            txPower = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE },
            timestampMs = SystemClock.elapsedRealtime(),
            guess = DeviceClassifier.classify(record, name),
        )
    }

    fun isLocallyAdministered(address: String): Boolean {
        val firstOctet = address.take(2).toIntOrNull(16) ?: return false
        return (firstOctet and 0x02) != 0
    }
}
