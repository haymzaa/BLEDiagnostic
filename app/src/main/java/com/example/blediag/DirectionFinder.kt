package com.example.blediag

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

const val SECTOR_COUNT = 12
const val SECTOR_DEGREES = 360.0 / SECTOR_COUNT

/**
 * What one device's signal looked like as the phone turned.
 * [sectorMeans] is the mean RSSI per compass sector, null where too few samples were taken.
 * [bearingDeg] is the estimated compass bearing, or null while there is no clear direction yet.
 */
data class DirectionReading(
    val sectorMeans: List<Double?>,
    val bearingDeg: Double?,
) {
    val sectorsSampled: Int get() = sectorMeans.count { it != null }
}

/**
 * Estimates where each device is by recording signal strength against compass heading.
 *
 * One antenna cannot sense direction on its own. This relies on the user's body blocking
 * signals from behind, so a device reads strongest while the user faces it.
 */
class DirectionTracker {
    private class Sectors {
        val sums = DoubleArray(SECTOR_COUNT)
        val counts = IntArray(SECTOR_COUNT)
        var lastSeenMs = Long.MIN_VALUE
    }

    private val samples = HashMap<String, Sectors>()

    fun record(headingDeg: Float, devices: Collection<BleDevice>) {
        val sector = (normalizeDegrees(headingDeg.toDouble()) / SECTOR_DEGREES).toInt() % SECTOR_COUNT
        samples.keys.retainAll(devices.mapTo(HashSet()) { it.address })
        for (device in devices) {
            val sectors = samples.getOrPut(device.address) { Sectors() }
            if (device.lastSeenMs == sectors.lastSeenMs) continue
            sectors.lastSeenMs = device.lastSeenMs
            sectors.sums[sector] += device.rssi
            sectors.counts[sector]++
        }
    }

    fun readings(): Map<String, DirectionReading> = samples.mapValues { (_, sectors) ->
        val means = List(SECTOR_COUNT) { i ->
            if (sectors.counts[i] >= MIN_SAMPLES_PER_SECTOR) sectors.sums[i] / sectors.counts[i] else null
        }
        DirectionReading(means, estimateBearing(means))
    }

    fun reset() = samples.clear()

    private fun estimateBearing(means: List<Double?>): Double? {
        val known = means.filterNotNull()
        if (known.size < MIN_SECTORS) return null
        val weakest = known.min()
        if (known.max() - weakest < MIN_CONTRAST_DB) return null

        // Circular mean of the sector centres, weighted towards the strongest sectors.
        var x = 0.0
        var y = 0.0
        means.forEachIndexed { i, mean ->
            if (mean != null) {
                val weight = (mean - weakest).pow(2)
                val angle = Math.toRadians((i + 0.5) * SECTOR_DEGREES)
                x += weight * cos(angle)
                y += weight * sin(angle)
            }
        }
        if (hypot(x, y) < 1e-6) return null
        return normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }

    companion object {
        const val MIN_SECTORS = SECTOR_COUNT / 2
        private const val MIN_SAMPLES_PER_SECTOR = 2
        private const val MIN_CONTRAST_DB = 6.0
    }
}

fun normalizeDegrees(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0

fun compassPoint(bearingDeg: Double): String {
    val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return points[((normalizeDegrees(bearingDeg) + 22.5) / 45.0).toInt() % points.size]
}

/**
 * Compass heading the user is facing, from a 3x3 device-to-world rotation matrix.
 * Flat phone: the direction its top edge points. Upright phone: the direction its back faces.
 */
fun headingFromRotationMatrix(r: FloatArray): Float {
    val flat = abs(r[8]) > 0.7f
    val east = if (flat) r[1] else -r[2]
    val north = if (flat) r[4] else -r[5]
    return normalizeDegrees(Math.toDegrees(atan2(east.toDouble(), north.toDouble()))).toFloat()
}

/** Live compass heading in degrees while [enabled], or null if there is no rotation sensor or no reading yet. */
@Composable
fun rememberHeadingDegrees(enabled: Boolean): State<Float?> {
    val context = LocalContext.current
    val heading = remember { mutableStateOf<Float?>(null) }

    DisposableEffect(enabled) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (!enabled || sensor == null) {
            heading.value = null
            return@DisposableEffect onDispose {}
        }

        val matrix = FloatArray(9)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                heading.value = headingFromRotationMatrix(matrix)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensorManager.unregisterListener(listener) }
    }
    return heading
}
