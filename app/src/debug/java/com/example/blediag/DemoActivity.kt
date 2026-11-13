package com.example.blediag

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Debug builds only. Feeds simulated devices into the app so the screen can be tried on an
 * emulator, which has no BLE radio. Nothing here is real scan data.
 */
class DemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DemoFeeder.start(applicationContext)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

private object DemoFeeder {
    private const val TICK_MS = 250L
    private const val BODY_SHADOW_DB = 8.0

    private class FakeDevice(
        val address: String,
        val name: String?,
        val baseRssi: Int,
        val bearingDeg: Double,
        manufacturerData: Map<Int, ByteArray> = emptyMap(),
        serviceUuids: Set<Int> = emptySet(),
    ) {
        val guess = DeviceClassifier.classify(manufacturerData, serviceUuids, null, name)
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val devices = listOf(
        FakeDevice("6E:3A:91:0C:44:D1", null, -52, 40.0,
            manufacturerData = mapOf(0x004C to bytes(0x07, 0x02, 0x01, 0x02))),
        FakeDevice("D4:9D:C0:11:22:33", "Galaxy Buds2 Pro", -63, 75.0,
            manufacturerData = mapOf(0x0075 to bytes(0x01))),
        FakeDevice("E2:15:00:AA:10:01", "Tile", -70, 200.0, serviceUuids = setOf(0xFEED)),
        FakeDevice("F4:DD:9E:48:21:07", "GoPro 4821", -66, 120.0,
            manufacturerData = mapOf(0x02F2 to bytes(0x01)), serviceUuids = setOf(0xFEA6)),
        FakeDevice("C8:2B:96:01:02:03", "S0123456789abcdefC", -84, 300.0),
        FakeDevice("7A:11:22:33:44:55", null, -75, 160.0,
            manufacturerData = mapOf(0x0006 to bytes(0x01, 0x09))),
        FakeDevice("8C:79:F5:10:20:30", "[TV] Samsung 7 Series", -72, 250.0),
        FakeDevice("4F:66:77:88:99:AA", null, -80, 10.0),
        FakeDevice("5B:01:02:03:04:05", null, -58, 330.0,
            manufacturerData = mapOf(0x004C to bytes(0x10, 0x01, 0x1C))),
        FakeDevice("A0:9E:1A:55:66:77", "Polar H10 8A2B", -68, 100.0, serviceUuids = setOf(0x180D)),
        FakeDevice("DC:A6:32:0F:0E:0D", "70mai Dashcam A810", -78, 280.0),
    )

    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var headingDeg: Float? = null

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val heading = headingDeg
            for (device in devices) {
                // Mimic the user's body blocking signals from behind: strongest when facing the device.
                val shadow = if (heading == null) {
                    0.0
                } else {
                    BODY_SHADOW_DB * cos(Math.toRadians(heading - device.bearingDeg))
                }
                BleDeviceRepository.onObservation(
                    BleObservation(
                        address = device.address,
                        oui = device.address.take(8),
                        randomizedLikely = BleParser.isLocallyAdministered(device.address),
                        name = device.name,
                        rssi = (device.baseRssi + shadow + Random.nextDouble(-2.0, 2.0)).roundToInt(),
                        txPower = null,
                        timestampMs = now,
                        guess = device.guess,
                    )
                )
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start(context: Context) {
        if (started) return
        started = true

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensor ->
            val matrix = FloatArray(9)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    headingDeg = headingFromRotationMatrix(matrix)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        handler.post(tick)
    }
}
