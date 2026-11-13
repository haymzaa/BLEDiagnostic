package com.example.blediag

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val RadarBackground = Color(0xFF06140A)
private val RadarLine = Color(0xFF2E7D32)
private val SweepColor = Color(0x5534C759)
private val SignalPetalColor = Color(0x3329B6F6)
private val NorthColor = Color(0xFFEF5350)

/**
 * Range mode (default): each dot sits at a stable angle derived from its address.
 * Direction mode ([directions] non-null): the radar is heading-up, and only devices
 * with an estimated bearing are drawn, at that bearing.
 */
@Composable
fun RadarView(
    devices: List<BleDevice>,
    modifier: Modifier = Modifier,
    maxRangeMeters: Double = 30.0,
    highlightedAddress: String? = null,
    directions: Map<String, DirectionReading>? = null,
    headingDeg: () -> Float = { 0f },
) {
    val transition = rememberInfiniteTransition(label = "radarSweep")
    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweepAngle",
    )

    Canvas(modifier = modifier) {
        val maxRadius = min(size.width, size.height) / 2f
        val ringStroke = Stroke(width = 1.dp.toPx())
        val heading = headingDeg()

        drawCircle(color = RadarBackground, radius = maxRadius, center = center)

        for (i in 1..4) {
            drawCircle(
                color = RadarLine,
                radius = maxRadius * i / 4f,
                center = center,
                style = ringStroke,
            )
        }

        drawLine(
            color = RadarLine,
            start = Offset(center.x - maxRadius, center.y),
            end = Offset(center.x + maxRadius, center.y),
            strokeWidth = ringStroke.width,
        )
        drawLine(
            color = RadarLine,
            start = Offset(center.x, center.y - maxRadius),
            end = Offset(center.x, center.y + maxRadius),
            strokeWidth = ringStroke.width,
        )

        if (directions != null) {
            directions[highlightedAddress]?.let { drawSignalPetals(it, heading, maxRadius) }
            drawNorthMarker(heading, maxRadius)
        }

        // Cosmetic only: the sweep does not correspond to any scan timing.
        drawArc(
            color = SweepColor,
            startAngle = sweepAngle,
            sweepAngle = 30f,
            useCenter = true,
            topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
            size = Size(maxRadius * 2, maxRadius * 2),
        )

        val dotRadius = 5.dp.toPx()
        devices.forEach { device ->
            val angleDeg = if (directions == null) {
                // Stable placement derived from the address, not a real bearing:
                // a single antenna's RSSI carries no direction information.
                Math.floorMod(device.address.hashCode(), 360).toDouble()
            } else {
                val bearing = directions[device.address]?.bearingDeg ?: return@forEach
                compassToCanvasDegrees(bearing, heading)
            }
            val fraction = (device.distanceMeters / maxRangeMeters).coerceIn(0.0, 1.0)
            val dotCenter = pointAt(angleDeg, (fraction * maxRadius).toFloat())
            val t = ((device.rssiSmoothed + 100) / 60).coerceIn(0.0, 1.0).toFloat()
            drawCircle(
                color = lerp(Color.Red, Color.Green, t),
                radius = dotRadius,
                center = dotCenter,
            )
            if (device.address == highlightedAddress) {
                drawCircle(
                    color = Color.White,
                    radius = dotRadius * 2,
                    center = dotCenter,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}

// Canvas angles start at 3 o'clock; compass bearings start at 12 o'clock, which is the user's heading.
private fun compassToCanvasDegrees(bearingDeg: Double, headingDeg: Float): Double =
    bearingDeg - headingDeg - 90.0

private fun DrawScope.pointAt(canvasDegrees: Double, radius: Float): Offset {
    val radians = Math.toRadians(canvasDegrees)
    return Offset(
        x = center.x + radius * cos(radians).toFloat(),
        y = center.y + radius * sin(radians).toFloat(),
    )
}

/** One wedge per sampled compass sector; longer wedges are where the signal was stronger. */
private fun DrawScope.drawSignalPetals(reading: DirectionReading, heading: Float, maxRadius: Float) {
    val known = reading.sectorMeans.filterNotNull()
    if (known.isEmpty()) return
    val weakest = known.min()
    val range = (known.max() - weakest).coerceAtLeast(1.0)
    reading.sectorMeans.forEachIndexed { i, mean ->
        if (mean == null) return@forEachIndexed
        val radius = maxRadius * (0.25f + 0.75f * ((mean - weakest) / range).toFloat())
        drawArc(
            color = SignalPetalColor,
            startAngle = compassToCanvasDegrees(i * SECTOR_DEGREES, heading).toFloat(),
            sweepAngle = SECTOR_DEGREES.toFloat(),
            useCenter = true,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
        )
    }
}

private fun DrawScope.drawNorthMarker(heading: Float, maxRadius: Float) {
    val north = compassToCanvasDegrees(0.0, heading)
    val base = maxRadius - 14.dp.toPx()
    val path = Path().apply {
        val tip = pointAt(north, maxRadius)
        val left = pointAt(north - 5.0, base)
        val right = pointAt(north + 5.0, base)
        moveTo(tip.x, tip.y)
        lineTo(left.x, left.y)
        lineTo(right.x, right.y)
        close()
    }
    drawPath(path, NorthColor)
}
