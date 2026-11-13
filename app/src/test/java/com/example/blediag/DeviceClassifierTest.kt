package com.example.blediag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceClassifierTest {

    private fun classify(
        manufacturerData: Map<Int, ByteArray> = emptyMap(),
        serviceUuids: Set<Int> = emptySet(),
        appearance: Int? = null,
        name: String? = null,
    ) = DeviceClassifier.classify(manufacturerData, serviceUuids, appearance, name)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun emptyAdvertisementIsUnknown() {
        assertEquals(DeviceGuess.UNKNOWN, classify())
    }

    @Test
    fun applePairingBeaconIsAudio() {
        val data = bytes(0x07, 0x02, 0x01, 0x02)
        assertEquals(DeviceCategory.AUDIO, classify(mapOf(0x004C to data)).category)
    }

    @Test
    fun appleMessagesAfterTheFirstAreRead() {
        val data = bytes(0x0C, 0x02, 0xAA, 0xBB, 0x10, 0x01, 0xCC)
        assertEquals(DeviceCategory.PHONE, classify(mapOf(0x004C to data)).category)
    }

    @Test
    fun unrecognisedAppleDataStaysUnknownButNamesTheBrand() {
        val guess = classify(mapOf(0x004C to bytes(0x7F, 0x00)))
        assertEquals(DeviceCategory.UNKNOWN, guess.category)
        assertEquals(DeviceGuess.WEAK, guess.confidence)
    }

    @Test
    fun tileServiceIsTracker() {
        assertEquals(DeviceCategory.TRACKER, classify(serviceUuids = setOf(0xFEED)).category)
    }

    @Test
    fun namesAreMatchedByProductWords() {
        assertEquals(DeviceCategory.AUDIO, classify(name = "Galaxy Buds2 Pro").category)
        assertEquals(DeviceCategory.CAMERA, classify(name = "GoPro 1234").category)
        assertEquals(DeviceCategory.CAMERA, classify(name = "70mai Dashcam").category)
        assertEquals(DeviceCategory.TV, classify(name = "[TV] Living Room").category)
        assertEquals(DeviceCategory.VEHICLE, classify(name = "S0123456789abcdefC").category)
    }

    @Test
    fun shortWordsDoNotMatchInsideOtherWords() {
        assertEquals(DeviceCategory.UNKNOWN, classify(name = "Nativity").category)
        assertEquals(DeviceCategory.UNKNOWN, classify(name = "Bedford").category)
    }

    @Test
    fun strongerSignalBeatsWeakerOne() {
        val guess = classify(
            manufacturerData = mapOf(0x0075 to bytes(0x01)),
            appearance = 0x0941,
        )
        assertEquals(DeviceCategory.AUDIO, guess.category)
        assertEquals(DeviceGuess.STRONG, guess.confidence)
    }

    @Test
    fun appearanceIsParsedFromRawBytes() {
        val raw = bytes(0x02, 0x01, 0x06, 0x03, 0x19, 0x41, 0x09)
        assertEquals(0x0941, DeviceClassifier.parseAppearance(raw))
    }

    @Test
    fun truncatedOrMissingAppearanceIsNull() {
        assertNull(DeviceClassifier.parseAppearance(bytes(0x02, 0x01, 0x06)))
        assertNull(DeviceClassifier.parseAppearance(bytes(0x03, 0x19, 0x41)))
        assertNull(DeviceClassifier.parseAppearance(null))
    }
}
