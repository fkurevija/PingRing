package com.combustiblelemonlabs.pingring.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

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
