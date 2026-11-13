package com.example.blediag

import android.bluetooth.le.ScanRecord
import androidx.core.util.forEach
import java.util.UUID

enum class DeviceCategory(val label: String) {
    AUDIO("Earbuds / audio"),
    PHONE("Phone / tablet"),
    COMPUTER("Computer"),
    WEARABLE("Watch / fitness"),
    TRACKER("Tracker tag"),
    CAMERA("Camera"),
    VEHICLE("Vehicle / road"),
    TV("TV / media box"),
    INPUT("Keyboard / mouse"),
    SMART_HOME("Smart home / sensor"),
    BEACON("Beacon"),
    UNKNOWN("Unknown"),
}

data class DeviceGuess(
    val category: DeviceCategory,
    val reason: String,
    val confidence: Int,
) {
    companion object {
        const val STRONG = 3
        const val MEDIUM = 2
        const val WEAK = 1

        val UNKNOWN = DeviceGuess(
            DeviceCategory.UNKNOWN,
            "Its broadcast carries nothing that identifies it",
            0,
        )
    }
}

/** Guesses what kind of device sent an advertisement. Every result is a heuristic, not a fact. */
object DeviceClassifier {

    fun classify(record: ScanRecord?, name: String?): DeviceGuess {
        if (record == null) return classify(emptyMap(), emptySet(), null, name)

        val manufacturerData = buildMap {
            record.manufacturerSpecificData?.forEach { id, data -> put(id, data) }
        }
        val serviceUuids = buildSet {
            record.serviceUuids?.forEach { parcelUuid -> parcelUuid.uuid.toShortUuid()?.let { add(it) } }
            record.serviceData?.keys?.forEach { parcelUuid -> parcelUuid.uuid.toShortUuid()?.let { add(it) } }
        }
        return classify(manufacturerData, serviceUuids, parseAppearance(record.bytes), name)
    }

    fun classify(
        manufacturerData: Map<Int, ByteArray>,
        serviceUuids: Set<Int>,
        appearance: Int?,
        name: String?,
    ): DeviceGuess {
        // Ordered most-specific first: maxByOrNull keeps the earliest of equally confident guesses.
        val candidates = listOfNotNull(
            fromApple(manufacturerData[APPLE]),
            serviceUuids.mapNotNull { SERVICE_RULES[it] }.maxByOrNull { it.confidence },
            fromAppearance(appearance),
            fromName(name),
            fromManufacturer(manufacturerData.keys),
        )
        return candidates.maxByOrNull { it.confidence } ?: DeviceGuess.UNKNOWN
    }

    /** Reads the 16-bit Appearance value (AD type 0x19) from raw advertisement bytes. */
    fun parseAppearance(bytes: ByteArray?): Int? {
        if (bytes == null) return null
        var i = 0
        while (i < bytes.size) {
            val length = bytes[i].toInt() and 0xFF
            if (length == 0 || i + length >= bytes.size) return null
            if ((bytes[i + 1].toInt() and 0xFF) == AD_TYPE_APPEARANCE && length >= 3) {
                return (bytes[i + 2].toInt() and 0xFF) or ((bytes[i + 3].toInt() and 0xFF) shl 8)
            }
            i += length + 1
        }
        return null
    }

    // Apple packs one or more [type, length, payload] messages into its manufacturer data.
    private fun fromApple(data: ByteArray?): DeviceGuess? {
        if (data == null) return null
        val types = buildSet {
            var i = 0
            while (i < data.size) {
                add(data[i].toInt() and 0xFF)
                val length = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
                i += 2 + length
            }
        }
        return when {
            0x07 in types -> DeviceGuess(
                DeviceCategory.AUDIO,
                "Apple audio pairing beacon, as sent by AirPods and Beats",
                DeviceGuess.STRONG,
            )
            0x02 in types -> DeviceGuess(
                DeviceCategory.BEACON,
                "iBeacon, a fixed location marker used by shops and venues",
                DeviceGuess.STRONG,
            )
            0x12 in types -> DeviceGuess(
                DeviceCategory.TRACKER,
                "Apple Find My beacon: an AirTag, or an Apple device away from its owner",
                DeviceGuess.MEDIUM,
            )
            0x09 in types || 0x0A in types -> DeviceGuess(
                DeviceCategory.TV,
                "AirPlay beacon, as sent by an Apple TV, HomePod or AirPlay speaker",
                DeviceGuess.MEDIUM,
            )
            types.any { it in APPLE_ACTIVE_TYPES } -> DeviceGuess(
                DeviceCategory.PHONE,
                "Apple nearby-device beacon, as sent by an iPhone, iPad, Mac or Watch",
                DeviceGuess.MEDIUM,
            )
            else -> brandOnly("Apple")
        }
    }

    private fun fromAppearance(appearance: Int?): DeviceGuess? {
        if (appearance == null) return null
        val (category, what) = when (appearance shr 6) {
            0x01 -> DeviceCategory.PHONE to "a phone"
            0x02 -> DeviceCategory.COMPUTER to "a computer"
            0x03 -> DeviceCategory.WEARABLE to "a watch"
            0x06 -> DeviceCategory.INPUT to "a remote control"
            0x08, 0x09 -> DeviceCategory.TRACKER to "a tag or keyring"
            0x0A, 0x05, 0x28 -> DeviceCategory.TV to "a media player or display"
            0x0D, 0x11 -> DeviceCategory.WEARABLE to "a heart-rate or running sensor"
            0x0F -> DeviceCategory.INPUT to "a keyboard, mouse or controller"
            0x12 -> DeviceCategory.VEHICLE to "a cycling sensor"
            in 0x15..0x20, 0x24 -> DeviceCategory.SMART_HOME to "a home sensor, light or appliance"
            0x21, 0x22 -> DeviceCategory.AUDIO to "a speaker or audio source"
            0x23 -> DeviceCategory.VEHICLE to "a motor vehicle"
            0x25 -> DeviceCategory.AUDIO to "earbuds, a headset or headphones"
            0x29 -> DeviceCategory.AUDIO to "a hearing aid"
            else -> return null
        }
        return DeviceGuess(category, "The device describes itself as $what", DeviceGuess.STRONG)
    }

    private fun fromName(name: String?): DeviceGuess? {
        if (name.isNullOrBlank()) return null
        val rule = NAME_RULES.firstOrNull { it.pattern.containsMatchIn(name) } ?: return null
        return DeviceGuess(rule.category, "Its name looks like ${rule.what}", DeviceGuess.MEDIUM)
    }

    private fun fromManufacturer(ids: Set<Int>): DeviceGuess? {
        ids.firstNotNullOfOrNull { MANUFACTURER_RULES[it] }?.let { return it }
        ids.firstNotNullOfOrNull { BRANDS[it] }?.let { return brandOnly(it) }
        ids.firstNotNullOfOrNull { CHIP_MAKERS[it] }?.let {
            return DeviceGuess(
                DeviceCategory.UNKNOWN,
                "Uses a generic $it chip: often a sensor, gadget or hobby device",
                DeviceGuess.WEAK,
            )
        }
        return null
    }

    private fun brandOnly(brand: String) = DeviceGuess(
        DeviceCategory.UNKNOWN,
        "Made by $brand, but the broadcast does not say which product",
        DeviceGuess.WEAK,
    )

    private fun UUID.toShortUuid(): Int? {
        val isBluetoothBase = leastSignificantBits == BASE_UUID.leastSignificantBits &&
            (mostSignificantBits and 0xFFFFFFFFL) == BASE_UUID.mostSignificantBits &&
            (mostSignificantBits ushr 48) == 0L
        return if (isBluetoothBase) ((mostSignificantBits ushr 32) and 0xFFFF).toInt() else null
    }

    private class NameRule(val category: DeviceCategory, val what: String, pattern: String) {
        val pattern = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    private const val APPLE = 0x004C
    private const val AD_TYPE_APPEARANCE = 0x19
    private val BASE_UUID = UUID.fromString("00000000-0000-1000-8000-00805F9B34FB")

    // AirDrop, Watch pairing, Handoff, hotspot, nearby action, nearby info.
    private val APPLE_ACTIVE_TYPES = setOf(0x05, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10)

    private val SERVICE_RULES: Map<Int, DeviceGuess> = buildMap {
        fun rule(category: DeviceCategory, confidence: Int, reason: String, vararg uuids: Int) {
            uuids.forEach { put(it, DeviceGuess(category, reason, confidence)) }
        }
        rule(DeviceCategory.AUDIO, DeviceGuess.MEDIUM,
            "Google Fast Pair beacon, sent by earbuds, headphones and speakers", 0xFE2C)
        rule(DeviceCategory.AUDIO, DeviceGuess.MEDIUM,
            "Advertises Bluetooth LE Audio: earbuds, hearing aids or a speaker", 0x184E, 0x1852, 0x1853)
        rule(DeviceCategory.AUDIO, DeviceGuess.STRONG, "Sonos speaker beacon", 0xFE07)
        rule(DeviceCategory.AUDIO, DeviceGuess.STRONG, "Bose audio product beacon", 0xFEBE)
        rule(DeviceCategory.PHONE, DeviceGuess.STRONG,
            "Exposure Notification beacon, which only phones send", 0xFD6F)
        rule(DeviceCategory.TRACKER, DeviceGuess.STRONG, "Tile tracker beacon", 0xFEED, 0xFEEC)
        rule(DeviceCategory.TRACKER, DeviceGuess.STRONG, "Samsung SmartTag beacon", 0xFD5A)
        rule(DeviceCategory.TRACKER, DeviceGuess.MEDIUM,
            "Location-tracker safety beacon, sent by tags separated from their owner", 0xFCB2)
        rule(DeviceCategory.CAMERA, DeviceGuess.STRONG, "GoPro camera control service", 0xFEA6)
        rule(DeviceCategory.BEACON, DeviceGuess.STRONG,
            "Eddystone beacon, a fixed marker used by shops, venues and transit", 0xFEAA)
        rule(DeviceCategory.SMART_HOME, DeviceGuess.STRONG, "Philips Hue lighting beacon", 0xFE0F)
        rule(DeviceCategory.SMART_HOME, DeviceGuess.STRONG,
            "Nest device: a thermostat, camera or smoke alarm", 0xFEAF, 0xFEB0)
        rule(DeviceCategory.SMART_HOME, DeviceGuess.MEDIUM,
            "Xiaomi smart-home beacon: a sensor, band, scooter or appliance", 0xFE95)
        rule(DeviceCategory.SMART_HOME, DeviceGuess.MEDIUM,
            "Amazon device such as an Echo or Fire TV", 0xFE03)
        rule(DeviceCategory.SMART_HOME, DeviceGuess.MEDIUM,
            "Advertises an environment-sensing service: a temperature or air sensor", 0x181A)
        rule(DeviceCategory.WEARABLE, DeviceGuess.MEDIUM,
            "Advertises a heart-rate or running service: a band, watch or chest strap", 0x180D, 0x1814)
        rule(DeviceCategory.VEHICLE, DeviceGuess.MEDIUM,
            "Advertises a cycling speed or power service: a bike sensor or bike computer", 0x1816, 0x1818)
        rule(DeviceCategory.INPUT, DeviceGuess.MEDIUM,
            "Advertises an input-device service: a keyboard, mouse, remote or controller", 0x1812)
    }

    // First match wins, so the more specific product words come first.
    private val NAME_RULES = listOf(
        NameRule(DeviceCategory.CAMERA, "a camera or dashcam",
            """gopro|insta360|camera|cam\b|wyze|\barlo\b|reolink|doorbell|70mai|viofo|\beos\b|nikon|fujifilm|lumix"""),
        NameRule(DeviceCategory.TRACKER, "a tracker tag",
            """airtag|smarttag|\btile\b|tracker|chipolo"""),
        NameRule(DeviceCategory.AUDIO, "earbuds, headphones or a speaker",
            """airpods|buds|headphones?|headset|\bw[hf]-\d|\bjbl\b|\bbeats\b|\bbose\b|\bjabra\b|soundcore|speaker|soundbar|sonos"""),
        NameRule(DeviceCategory.WEARABLE, "a watch or fitness band",
            """watch|\bband\b|fitbit|forerunner|fenix|\bvenu\b|amazfit|whoop|\boura\b|\bpolar\b|\bhrm\b"""),
        NameRule(DeviceCategory.TV, "a TV or streaming box",
            """\btv\b|bravia|\broku\b|chromecast|fire ?stick"""),
        NameRule(DeviceCategory.VEHICLE, "a car, bike or road accessory",
            """tesla|^S[0-9a-f]{16}[CDPR]$|\bbmw\b|\baudi\b|toyota|honda|\bford\b|\bvw\b|volkswagen|mercedes|hyundai|\bkia\b|nissan|mazda|subaru|volvo|porsche|lexus|chevrolet|\bjeep\b|carplay|android auto|\bobd|elm327|\btpms\b|scooter|ninebot|segway|e-?bike|wahoo|kickr"""),
        NameRule(DeviceCategory.INPUT, "a keyboard, mouse or controller",
            """keyboard|mouse|trackpad|remote|controller|gamepad|stylus|\bpen\b"""),
        NameRule(DeviceCategory.COMPUTER, "a computer",
            """macbook|\bimac\b|laptop|^desktop-|thinkpad|\bsurface\b"""),
        NameRule(DeviceCategory.PHONE, "a phone or tablet",
            """iphone|ipad|\bpixel \d|galaxy [sazn]\d|\bphone\b|oneplus|redmi"""),
        NameRule(DeviceCategory.SMART_HOME, "a smart-home device",
            """\block\b|bulb|\blight|lamp|\bhue\b|\bplug\b|thermo|sensor|govee|switchbot|\bscale\b|toothbrush|vacuum|roomba|ruuvi"""),
    )

    private val MANUFACTURER_RULES = mapOf(
        0x0006 to DeviceGuess(DeviceCategory.COMPUTER,
            "Microsoft beacon, as sent by Windows PCs and their accessories", DeviceGuess.MEDIUM),
        0x009E to DeviceGuess(DeviceCategory.AUDIO,
            "Made by Bose: headphones, earbuds or a speaker", DeviceGuess.MEDIUM),
        0x0057 to DeviceGuess(DeviceCategory.AUDIO,
            "Made by Harman (JBL, Harman Kardon): headphones or a speaker", DeviceGuess.MEDIUM),
        0x0067 to DeviceGuess(DeviceCategory.AUDIO,
            "Made by GN (Jabra): a headset or earbuds", DeviceGuess.MEDIUM),
        0x0087 to DeviceGuess(DeviceCategory.WEARABLE,
            "Made by Garmin: usually a watch or bike computer", DeviceGuess.MEDIUM),
        0x0157 to DeviceGuess(DeviceCategory.WEARABLE,
            "Made by Huami (Amazfit, Mi Band): a watch or fitness band", DeviceGuess.MEDIUM),
        0x02F2 to DeviceGuess(DeviceCategory.CAMERA,
            "Made by GoPro: an action camera", DeviceGuess.STRONG),
        0x0499 to DeviceGuess(DeviceCategory.SMART_HOME,
            "Ruuvi environment sensor", DeviceGuess.STRONG),
    )

    private val BRANDS = mapOf(
        0x0075 to "Samsung",
        0x00E0 to "Google",
        0x012D to "Sony",
        0x038F to "Xiaomi",
        0x027D to "Huawei",
        0x00C4 to "LG",
        0x0171 to "Amazon",
    )

    private val CHIP_MAKERS = mapOf(
        0x0059 to "Nordic Semiconductor",
        0x000D to "Texas Instruments",
        0x02E5 to "Espressif",
    )
}
