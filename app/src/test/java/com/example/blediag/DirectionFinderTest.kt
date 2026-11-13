package com.example.blediag

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionFinderTest {

    private fun device(rssi: Int, seenMs: Long, address: String = "AA:BB:CC:00:00:01") = BleDevice(
        address = address,
        oui = address.take(8),
        name = null,
        rssi = rssi,
        rssiSmoothed = rssi.toDouble(),
        txPower = null,
        distanceMeters = 1.0,
        randomizedLikely = false,
        lastSeenMs = seenMs,
        guess = DeviceGuess.UNKNOWN,
    )

    /** Turns through [headings], with the signal strongest when facing [sourceDeg]. */
    private fun spin(
        tracker: DirectionTracker,
        sourceDeg: Double,
        headings: IntProgression,
        swingDb: Double = 10.0,
    ) {
        var clock = 0L
        repeat(2) {
            for (heading in headings) {
                val rssi = -70 + swingDb * cos(Math.toRadians(heading - sourceDeg))
                tracker.record(heading.toFloat(), listOf(device(rssi.roundToInt(), ++clock)))
            }
        }
    }

    private fun bearingError(actual: Double, expected: Double): Double {
        val diff = abs(actual - expected) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }

    @Test
    fun fullTurnFindsTheStrongestDirection() {
        for (source in listOf(0.0, 90.0, 200.0, 350.0)) {
            val tracker = DirectionTracker()
            spin(tracker, source, 0 until 360 step 5)
            val bearing = tracker.readings().values.single().bearingDeg
            assertNotNull("no bearing for source $source", bearing)
            assertTrue("bearing $bearing too far from $source", bearingError(bearing!!, source) < 15.0)
        }
    }

    @Test
    fun partialTurnGivesNoBearing() {
        val tracker = DirectionTracker()
        spin(tracker, 90.0, 0 until 120 step 5)
        val reading = tracker.readings().values.single()
        assertEquals(4, reading.sectorsSampled)
        assertNull(reading.bearingDeg)
    }

    @Test
    fun evenSignalGivesNoBearing() {
        val tracker = DirectionTracker()
        spin(tracker, 90.0, 0 until 360 step 5, swingDb = 1.0)
        assertNull(tracker.readings().values.single().bearingDeg)
    }

    @Test
    fun repeatedReportOfTheSamePacketIsCountedOnce() {
        val tracker = DirectionTracker()
        repeat(5) { tracker.record(10f, listOf(device(-60, seenMs = 1L))) }
        assertEquals(0, tracker.readings().values.single().sectorsSampled)
    }

    @Test
    fun devicesThatDisappearAreDropped() {
        val tracker = DirectionTracker()
        tracker.record(10f, listOf(device(-60, 1L)))
        tracker.record(10f, emptyList())
        assertTrue(tracker.readings().isEmpty())
    }

    @Test
    fun headingOfFlatPhone() {
        val topPointsNorth = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val topPointsEast = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        assertEquals(0f, headingFromRotationMatrix(topPointsNorth), 0.01f)
        assertEquals(90f, headingFromRotationMatrix(topPointsEast), 0.01f)
    }

    @Test
    fun headingOfUprightPhoneIsWhereItsBackFaces() {
        val backFacesNorth = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        assertEquals(0f, headingFromRotationMatrix(backFacesNorth), 0.01f)
    }

    @Test
    fun compassPointsWrapAroundNorth() {
        assertEquals("N", compassPoint(350.0))
        assertEquals("N", compassPoint(10.0))
        assertEquals("NE", compassPoint(48.0))
        assertEquals("W", compassPoint(-90.0))
    }
}
