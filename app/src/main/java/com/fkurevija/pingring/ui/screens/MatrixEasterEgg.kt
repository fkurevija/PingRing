package com.fkurevija.pingring.ui.screens

import android.graphics.Paint
import android.graphics.Typeface
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.fkurevija.pingring.R
import com.fkurevija.pingring.ui.accessibility.rememberAccessibilityEnvironment
import kotlinx.coroutines.isActive

internal class VersionTapSequence {
    private var count = 0

    /**
     * Records a tap and reports how many more are needed.
     *
     * Returns 0 exactly when this tap completed the sequence, which is the
     * caller's signal to open the prompt; the internal count is reset at the
     * same moment, so a following run starts from [REQUIRED_TAPS] again
     * rather than reporting a sequence that has already fired.
     *
     * There is deliberately no clock in here. A gap between taps used to
     * restart the count, which punished somebody tapping deliberately and
     * pausing to read the screen. The count now survives any amount of
     * hesitation; what resets it is either the sequence completing or the
     * About screen going away, which drops this object along with every tap
     * it held.
     */
    fun tap(): Int {
        count++
        if (count < REQUIRED_TAPS) return REQUIRED_TAPS - count
        count = 0
        return 0
    }

    companion object {
        const val REQUIRED_TAPS = 7
    }
}

@Composable
internal fun MatrixEasterEgg(onDismiss: () -> Unit, reduceMotionEnabled: Boolean) {
    val reduceMotion = rememberAccessibilityEnvironment(reduceMotionEnabled).reduceMotion
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            val controller = window?.let { WindowInsetsControllerCompat(it, view) }
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .semantics {
                    contentDescription = view.context.getString(R.string.matrix_exit)
                }
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.matrix_exit), onClick = onDismiss)
        ) {
            MatrixRain(Modifier.fillMaxSize(), reduceMotion)
        }
    }
}

@Composable
internal fun MatrixRain(modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var elapsedSeconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduceMotion, lifecycleOwner) {
        if (reduceMotion) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var startFrameNanos = 0L
            while (isActive) {
                withFrameNanos { frameNanos ->
                    if (startFrameNanos == 0L) startFrameNanos = frameNanos
                    elapsedSeconds = (frameNanos - startFrameNanos) / 1_000_000_000f
                }
            }
        }
    }
    val glyphSizePx = with(LocalDensity.current) { 18.sp.toPx() }
    val paint = remember(glyphSizePx) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = glyphSizePx
        }
    }
    Canvas(modifier.background(Color.Black)) {
        val columnStep = glyphSizePx * 0.85f
        val rowStep = glyphSizePx * 1.2f
        val columns = (size.width / columnStep).toInt().coerceIn(1, 120)
        val rows = (size.height / rowStep).toInt().coerceAtLeast(1)
        val glyphs = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ<>:+*/"
        repeat(columns) { column ->
            val speed = 5f + (column * 17 % 11)
            val trailLength = 10 + column * 13 % 15
            val head = if (reduceMotion) {
                (column * 7) % (rows + trailLength)
            } else {
                ((elapsedSeconds * speed + column * 7) % (rows + trailLength)).toInt()
            }
            repeat(trailLength) { trailIndex ->
                val row = head - trailIndex
                if (row in 0..rows) {
                    val glyphIndex = (column * 31 + row * 17 + if (reduceMotion) 0 else (elapsedSeconds * 8).toInt()) % glyphs.length
                    paint.color = if (trailIndex == 0) android.graphics.Color.rgb(170, 255, 190)
                    else android.graphics.Color.rgb(0, 220, 65)
                    paint.alpha = if (trailIndex == 0) 255 else (220f * (1f - trailIndex.toFloat() / trailLength)).toInt()
                    drawContext.canvas.nativeCanvas.drawText(
                        glyphs[glyphIndex].toString(),
                        column * columnStep,
                        row * rowStep,
                        paint,
                    )
                }
            }
        }
    }
}