package com.fkurevija.pingring.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.BeaconInfo
import com.fkurevija.pingring.data.RadarAccessibilityProbe
import com.fkurevija.pingring.data.RadarDirectionPolicy
import com.fkurevija.pingring.data.RadarPeerUpdate
import com.fkurevija.pingring.data.RangingSourcePolicy

@Composable
internal fun RadarContent(
    peer: RadarPeerUpdate?,
    hasPeers: Boolean,
    beacon: BeaconInfo?,
    radarScreenReaderSummaryEnabled: Boolean,
    reduceMotion: Boolean,
    spokenUpdateText: String?,
    modifier: Modifier,
    directionAlpha: Float = 1f,
    directionIsLastKnown: Boolean = false,
) {
    val resources = LocalContext.current.resources
    val proximityLabel = when (peer?.proximity) {
        "Hot" -> stringResource(R.string.proximity_hot)
        "Cold" -> stringResource(R.string.proximity_cold)
        else -> stringResource(R.string.proximity_unknown)
    }
    val hotness = ((peer?.rssi ?: -100) + 100).coerceIn(0, 100) / 100f
    // Signal strength still tints the ring, from cold blue to warm green. It no
    // longer drives any lit segment: the ring only lights up for a bearing.
    val defaultRingColor = Color.lerp(Color(0xFF3F51B5), Color(0xFF00C853), hotness)
    val ringColor = beacon?.color ?: defaultRingColor

    // Fill the width, but stay inside it: the caller measures this composable
    // before it is laid out, so onSizeChanged would give 0 for the first frame.
    // Constraining to the parent width and deriving the diameter from it avoids
    // a fixed height that the surrounding text can overflow.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = maxWidth
        val radarDiameter = if (available == Dp.Unspecified || available <= 0.dp) {
            240.dp
        } else {
            // Leave a margin so the ring never touches the screen edge, and cap
            // it so the radar does not become enormous on a tablet.
            (available * 0.82f).coerceIn(160.dp, 320.dp)
        }

        // The radar is sized from the width, so on most phones the ring's square
        // is shorter than the room the column has, and whatever is left over
        // lands all on one side. That is the asymmetry this fixes: with the gap
        // only below the ring - the spacer here plus the button row's own top
        // padding - the ring sat pressed up under the app bar while an equal
        // amount of air showed underneath it.
        //
        // Derived from the ring's own diameter rather than fixed, so the two
        // gaps stay equal to each other *and* keep their proportion when the
        // radar grows on a tablet. A fixed spacer would match on one screen size
        // and drift on every other.
        val verticalBuffer = (radarDiameter * 0.05f).coerceIn(8.dp, 20.dp)

        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            // The beacon's title used to be repeated here in a chip above the ring.
            // A beacon is already named in its chat entry and the ring is tinted,
            // so another chip above the ring would repeat the same information.
            // A remote peer's beacon is still legible: the ring carries its colour.

            Spacer(Modifier.height(verticalBuffer))
            ProximityRing(
                color = ringColor,
                arrowDegrees = peer?.radarAzimuth,
                directionUncertaintyDegrees = peer?.displayedEstimatedDirection?.uncertaintyDegrees,
                distanceMeters = peer?.distance,
                directionAlpha = directionAlpha,
                directionIsLastKnown = directionIsLastKnown,
                trackedPeerName = peer?.takeIf { hasPeers }?.name,
                trackedPeerDistance = peer?.takeIf { hasPeers }?.let {
                    it.distance?.let { distance ->
                        stringResource(R.string.distance_away, distance)
                    } ?: stringResource(R.string.proximity_signal, proximityLabel)
                },
                beacon = beacon,
                accessibilityDescription = RadarAccessibilityProbe.describeLocalized(
                    resources = resources,
                    peerName = peer?.name,
                    distanceMeters = peer?.distance,
                    azimuthDegrees = peer?.radarAzimuth,
                    directionUncertaintyDegrees = peer?.displayedEstimatedDirection?.uncertaintyDegrees,
                    proximityLabel = peer?.let { proximityLabel },
                    beaconTitle = beacon?.title,
                    distanceIsEstimate = peer?.distanceSource == RangingSourcePolicy.RangingSource.GNSS,
                    distanceAccuracyMeters = peer?.distanceAccuracyMeters,
                ),
                accessibilityEnabled = radarScreenReaderSummaryEnabled,
                reduceMotion = reduceMotion,
                spokenUpdateText = spokenUpdateText,
                // Sized from the available width rather than fixed. The caller used
                // to pin this column to 260.dp while the ring inside it was also
                // 260.dp, so the name and distance heading above had
                // nowhere to go: the Column overflowed and the ring was clipped top
                // and bottom. Deriving the diameter from the width leaves room for
                // the surrounding text and still fits a narrow phone.
                modifier = Modifier.size(radarDiameter)
            )
            Spacer(Modifier.height(verticalBuffer))
        }
    }
}

@Composable
internal fun rememberBeaconPulse(reduceMotion: Boolean, active: Boolean): Pair<Float, Float> {
    if (reduceMotion || !active) return 0.9f to 0.35f
    val infiniteTransition = rememberInfiniteTransition(label = "beaconPulse")
    val beaconPulseRadius by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "beaconPulseRadius"
    )
    val beaconPulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "beaconPulseAlpha"
    )
    return beaconPulseRadius to beaconPulseAlpha
}

@Composable
private fun ProximityRing(
    color: Color,
    arrowDegrees: Float?,
    directionUncertaintyDegrees: Float?,
    distanceMeters: Float?,
    directionAlpha: Float,
    directionIsLastKnown: Boolean,
    trackedPeerName: String?,
    trackedPeerDistance: String?,
    beacon: BeaconInfo?,
    accessibilityDescription: String,
    accessibilityEnabled: Boolean,
    reduceMotion: Boolean,
    spokenUpdateText: String?,
    modifier: Modifier
) {
    val (beaconPulseRadius, beaconPulseAlpha) = rememberBeaconPulse(reduceMotion, beacon != null)
    val drawColor = rememberUpdatedState(color)
    val drawBearing = rememberUpdatedState(arrowDegrees)
    val drawDirectionUncertainty = rememberUpdatedState(directionUncertaintyDegrees)
    val drawDistance = rememberUpdatedState(distanceMeters)
    val drawDirectionAlpha = rememberUpdatedState(directionAlpha)
    Box(modifier, contentAlignment = Alignment.Center) {
        spokenUpdateText?.let { announcement ->
            Box(Modifier.size(1.dp).semantics {
                contentDescription = announcement
                liveRegion = LiveRegionMode.Polite
            })
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .then(
                    if (accessibilityEnabled) Modifier.semantics { contentDescription = accessibilityDescription }
                    else Modifier
                )
                // The beacon pulse is deliberately drawn larger than the ring
                // (see beaconPulseRadius above), so without this it escapes the
                // composable's bounds and paints over whatever sits above the
                // radar - the top bar, in this layout. Clipping keeps the
                // overspill as a visual fade at the edge rather than an
                // overlay across the app bar.
                .clipToBounds()
        ) {
            val color = drawColor.value
            val arrowDegrees = drawBearing.value
            val directionUncertaintyDegrees = drawDirectionUncertainty.value
            val distanceMeters = drawDistance.value
            val directionAlpha = drawDirectionAlpha.value

            // One source of truth for the ring's stroke, radius and origin. The
            // lit arc below is inscribed in the very same square, so the two
            // cannot drift apart. Half the stroke is inset from the canvas edge
            // so the whole band fits inside it.
            val ring = RadarDirectionPolicy.ringGeometry(size.width, size.height)
            val stroke = ring.strokePx
            val ringRadius = ring.radiusPx

            // Pulsing beacon wave if active. The animated radius is clamped to
            // the drawable extent: the wave still grows as it fades, but it
            // reaches the edge rather than passing through it, so the pulse
            // reads as expanding outwards without leaving the canvas.
            if (beacon != null) {
                drawCircle(
                    color = color.copy(alpha = beaconPulseAlpha),
                    radius = ringRadius * beaconPulseRadius.coerceAtMost(1f),
                    style = Stroke(stroke * 0.7f)
                )
            }

            // The base ring. radius must be given explicitly: drawCircle
            // defaults it to half the smaller canvas dimension, which puts the
            // centre line of the stroke exactly on the canvas edge and clips
            // the outer half of it away - leaving the chamfered-square shape
            // rather than a circle. Subtracting the stroke width pulls the
            // centre line in by half a stroke, so the full width fits.
            drawCircle(
                color = color.copy(alpha = if (beacon != null) 0.25f else .12f),
                radius = ringRadius,
                style = Stroke(stroke)
            )

            // The directional segment: a lit arc of the ring pointing at the
            // tracked member, widening as they approach.
            //
            // There is deliberately no fallback for a missing bearing. An earlier
            // version drew the old signal-strength arc instead, which put a lit
            // highlight on the ring whenever nobody had joined - and because the
            // sweep was floored at 8%, it drew one even at zero signal. With a
            // stroke 8% of the canvas and a round cap, that read as a stray blob
            // rather than as "no direction known". An unlit ring is the honest
            // state: no bearing means there is nothing to point at.
            val segment = RadarDirectionPolicy.segment(arrowDegrees, distanceMeters)
            val directionColor = color.copy(
                alpha = (if (directionUncertaintyDegrees != null) 0.6f else 1f) * directionAlpha
            )
            if (segment != null) {
                drawArc(
                    color = directionColor,
                    startAngle = segment.startAngleDegrees,
                    sweepAngle = segment.sweepAngleDegrees,
                    useCenter = false,
                    // Inscribed in the ring's own square. An oval's centre
                    // is its top-left plus half its size, so passing
                    // (ringRadius, ringRadius) - as this did, and as the
                    // canvas-sized default would have too - puts the arc's
                    // centre at the canvas' bottom-right corner region
                    // instead of its middle: the highlight landed beside
                    // the ring and was clipped by the canvas edge.
                    topLeft = Offset(ring.topLeftXPx, ring.topLeftYPx),
                    size = Size(ring.arcSizePx, ring.arcSizePx),
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }

        }
        if (trackedPeerName != null && trackedPeerDistance != null) {
            Column(
                modifier = Modifier.fillMaxWidth(0.62f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = trackedPeerName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = trackedPeerDistance,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (directionIsLastKnown) {
                    Text(
                        text = stringResource(R.string.radar_last_known_indicator),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = directionAlpha),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun Color.Companion.lerp(start: Color, end: Color, fraction: Float): Color {
    return Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction,
        alpha = 1f
    )
}
