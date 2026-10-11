package com.fkurevija.pingring.ui.accessibility

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.fkurevija.pingring.data.radar.RadarFeedbackPolicy

internal data class AccessibilityEnvironment(val reduceMotion: Boolean, val screenReaderActive: Boolean)

@Composable
internal fun rememberAccessibilityEnvironment(reduceMotionEnabled: Boolean): AccessibilityEnvironment {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var animationsEnabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    var screenReaderActive by remember(manager) {
        mutableStateOf(manager?.isEnabled == true && manager.isTouchExplorationEnabled)
    }
    DisposableEffect(context, manager) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { animationsEnabled = ValueAnimator.areAnimatorsEnabled() }
        }
        val stateListener = AccessibilityManager.AccessibilityStateChangeListener {
            screenReaderActive = manager?.isEnabled == true && manager.isTouchExplorationEnabled
        }
        val explorationListener = AccessibilityManager.TouchExplorationStateChangeListener {
            screenReaderActive = manager?.isEnabled == true && manager.isTouchExplorationEnabled
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        manager?.addAccessibilityStateChangeListener(stateListener)
        manager?.addTouchExplorationStateChangeListener(explorationListener)
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            manager?.removeAccessibilityStateChangeListener(stateListener)
            manager?.removeTouchExplorationStateChangeListener(explorationListener)
        }
    }
    return AccessibilityEnvironment(RadarFeedbackPolicy.reduceMotion(reduceMotionEnabled, animationsEnabled), screenReaderActive)
}