package com.example.blediag

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BleDiagnosticScreen()
                }
            }
        }
    }
}

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.BLUETOOTH_SCAN)
    // Android 12+ ignores a FINE location request unless COARSE is requested with it.
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private fun isBluetoothEnabled(context: Context): Boolean =
    context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

private fun DeviceCategory.color(): Color = when (this) {
    DeviceCategory.AUDIO -> Color(0xFF4FC3F7)
    DeviceCategory.PHONE -> Color(0xFF81C784)
    DeviceCategory.COMPUTER -> Color(0xFF9575CD)
    DeviceCategory.WEARABLE -> Color(0xFFFFB74D)
    DeviceCategory.TRACKER -> Color(0xFFE57373)
    DeviceCategory.CAMERA -> Color(0xFFF06292)
    DeviceCategory.VEHICLE -> Color(0xFFFFF176)
    DeviceCategory.TV -> Color(0xFF4DB6AC)
    DeviceCategory.INPUT -> Color(0xFFA1887F)
    DeviceCategory.SMART_HOME -> Color(0xFFAED581)
    DeviceCategory.BEACON -> Color(0xFF90A4AE)
    DeviceCategory.UNKNOWN -> Color(0xFF757575)
}

private fun DeviceGuess.headline(): String = when {
    category == DeviceCategory.UNKNOWN -> "Unidentified device"
    confidence >= DeviceGuess.STRONG -> "Very likely: ${category.label}"
    confidence == DeviceGuess.MEDIUM -> "Likely: ${category.label}"
    else -> "Possibly: ${category.label}"
}

@Composable
fun BleDiagnosticScreen() {
    val context = LocalContext.current
    val devices by BleDeviceRepository.devices.collectAsState()
    var status by remember { mutableStateOf("Idle") }
    var scanning by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<DeviceCategory?>(null) }
    var selectedAddress by remember { mutableStateOf<String?>(null) }

    var directionMode by remember { mutableStateOf(false) }
    val heading = rememberHeadingDegrees(enabled = directionMode)
    val hasHeading by remember { derivedStateOf { heading.value != null } }
    val tracker = remember { DirectionTracker() }
    var directions by remember { mutableStateOf<Map<String, DirectionReading>>(emptyMap()) }

    LaunchedEffect(directionMode) {
        tracker.reset()
        directions = emptyMap()
        if (directionMode) {
            BleDeviceRepository.devices.collect { current ->
                val headingNow = heading.value ?: return@collect
                tracker.record(headingNow, current.values)
                directions = tracker.readings()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val scanGranted = grants[Manifest.permission.BLUETOOTH_SCAN] == true
        val locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        when {
            !scanGranted || !locationGranted -> status = "Permission denied"
            !isBluetoothEnabled(context) -> status = "Bluetooth is off"
            else -> {
                BleScanService.start(context)
                status = "Scanning"
                scanning = true
            }
        }
    }

    val sortedDevices = remember(devices) { devices.values.sortedBy { it.distanceMeters } }
    val counts = remember(sortedDevices) {
        sortedDevices.groupingBy { it.guess.category }.eachCount().toSortedMap()
    }
    val activeFilter = filter?.takeIf { it in counts }
    val visibleDevices = if (activeFilter == null) {
        sortedDevices
    } else {
        sortedDevices.filter { it.guess.category == activeFilter }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "BLE Diagnostic", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "$status · ${sortedDevices.size} devices",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = {
                    if (scanning) {
                        BleScanService.stop(context)
                        status = "Stopped"
                        scanning = false
                    } else {
                        permissionLauncher.launch(requiredPermissions())
                    }
                },
            ) {
                Text(if (scanning) "Stop" else "Start")
            }
        }

        // One scrolling page: with the radar pinned, the device list had room for only two cards.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "closest") {
                ClosestSignalCard(nearest = sortedDevices.firstOrNull(), scanning = scanning)
            }

            item(key = "mode") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = !directionMode,
                        onClick = { directionMode = false },
                        label = { Text("Range") },
                    )
                    FilterChip(
                        selected = directionMode,
                        onClick = { directionMode = true },
                        label = { Text("Direction") },
                    )
                    if (directionMode) {
                        TextButton(
                            onClick = {
                                tracker.reset()
                                directions = emptyMap()
                            },
                        ) {
                            Text("Start over")
                        }
                    }
                }
            }

            item(key = "radar") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RadarView(
                        devices = visibleDevices,
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .aspectRatio(1f),
                        highlightedAddress = selectedAddress,
                        directions = if (directionMode) directions else null,
                        headingDeg = { heading.value ?: 0f },
                    )
                }
            }

            if (directionMode) {
                item(key = "directionHelp") {
                    Text(
                        text = if (hasHeading) {
                            val found = sortedDevices.count { directions[it.address]?.bearingDeg != null }
                            "Hold the phone against your chest and turn slowly through a full circle. " +
                                "The top of the radar is the way you are facing; the red marker is north. " +
                                "Direction found for $found of ${sortedDevices.size} devices."
                        } else {
                            "No compass reading yet. This device may not have a rotation sensor."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (counts.isNotEmpty()) {
                item(key = "categories") {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        counts.forEach { (category, count) ->
                            FilterChip(
                                selected = category == activeFilter,
                                onClick = { filter = if (activeFilter == category) null else category },
                                label = { Text("${category.label} · $count") },
                                leadingIcon = { CategoryDot(category) },
                            )
                        }
                    }
                }
            }

            items(visibleDevices, key = { it.address }) { device ->
                DeviceRow(
                    device = device,
                    selected = device.address == selectedAddress,
                    direction = if (directionMode) directions[device.address] else null,
                    onClick = {
                        selectedAddress = if (selectedAddress == device.address) null else device.address
                    },
                )
            }
        }
    }
}

@Composable
private fun ClosestSignalCard(nearest: BleDevice?, scanning: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Closest signal (best guess)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (nearest == null) {
                Text(
                    text = if (scanning) {
                        "Listening. Nothing picked up yet."
                    } else {
                        "Press Start to see what is broadcasting near you."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryDot(nearest.guess.category)
                    Text(
                        text = nearest.guess.headline(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(text = nearest.guess.reason, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = String.format(
                        Locale.US,
                        "%s · about %.1f m away",
                        nearest.name ?: "No name advertised",
                        nearest.distanceMeters,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CategoryDot(category: DeviceCategory) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(category.color(), CircleShape),
    )
}

@Composable
private fun DeviceRow(
    device: BleDevice,
    selected: Boolean,
    direction: DirectionReading?,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryDot(device.guess.category)
                Text(
                    text = device.guess.headline(),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = device.guess.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = device.name ?: "(no name advertised)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = buildString {
                    append(device.address)
                    append(" · OUI ")
                    append(device.oui)
                    if (device.randomizedLikely) append(" · likely randomized")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = String.format(
                    Locale.US,
                    "%.0f dBm · %.1f m",
                    device.rssiSmoothed,
                    device.distanceMeters,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (direction != null) {
                val bearing = direction.bearingDeg
                Text(
                    text = if (bearing != null) {
                        String.format(
                            Locale.US,
                            "Direction: %s (%.0f°), rough estimate",
                            compassPoint(bearing),
                            bearing,
                        )
                    } else if (direction.sectorsSampled < DirectionTracker.MIN_SECTORS) {
                        "Direction: keep turning, ${direction.sectorsSampled} of $SECTOR_COUNT headings sampled"
                    } else {
                        "Direction: no clear direction, signal is similar all round"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
