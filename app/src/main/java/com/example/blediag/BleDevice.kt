package com.example.blediag

import kotlin.math.pow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

const val DEFAULT_TX_POWER_1M = -59

/**
 * Log-distance path loss model: `d = 10 ^ ((txPowerAt1m - rssi) / (10 * n))`,
 * where `n` is the path loss exponent (2.0 in free space).
 */
fun estimateDistanceMeters(
    rssi: Double,
    txPowerAt1m: Int = DEFAULT_TX_POWER_1M,
    pathLossExponent: Double = 2.0,
): Double = 10.0.pow((txPowerAt1m - rssi) / (10.0 * pathLossExponent))

data class BleDevice(
    val address: String,
    val oui: String,
    val name: String?,
    val rssi: Int,
    val rssiSmoothed: Double,
    val txPower: Int?,
    val distanceMeters: Double,
    val randomizedLikely: Boolean,
    val lastSeenMs: Long,
    val guess: DeviceGuess,
)

object BleDeviceRepository {
    private const val ALPHA = 0.3
    private const val STALE_AFTER_MS = 10_000L

    private val _devices = MutableStateFlow<Map<String, BleDevice>>(emptyMap())
    val devices: StateFlow<Map<String, BleDevice>> = _devices.asStateFlow()

    fun onObservation(obs: BleObservation) {
        _devices.update { current ->
            val previous = current[obs.address]
            val smoothed = if (previous == null) {
                obs.rssi.toDouble()
            } else {
                ALPHA * obs.rssi + (1 - ALPHA) * previous.rssiSmoothed
            }
            current + (obs.address to BleDevice(
                address = obs.address,
                oui = obs.oui,
                name = obs.name ?: previous?.name,
                rssi = obs.rssi,
                rssiSmoothed = smoothed,
                txPower = obs.txPower ?: previous?.txPower,
                distanceMeters = estimateDistanceMeters(smoothed),
                randomizedLikely = obs.randomizedLikely,
                lastSeenMs = obs.timestampMs,
                // A device alternates between packet types, so keep its most confident guess.
                guess = previous?.guess?.takeIf { it.confidence > obs.guess.confidence } ?: obs.guess,
            ))
        }
    }

    fun prune(nowMs: Long) {
        _devices.update { current ->
            current.filterValues { nowMs - it.lastSeenMs <= STALE_AFTER_MS }
        }
    }

    fun clear() {
        _devices.value = emptyMap()
    }
}
