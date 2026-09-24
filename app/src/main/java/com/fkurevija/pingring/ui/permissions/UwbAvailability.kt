package com.fkurevija.pingring.ui.permissions

import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

/**
 * Whether Ultra-wideband ranging can actually be used right now: the device
 * must report UWB hardware support AND the user must have granted the
 * UWB_RANGING permission. [onRequestAccess] is the single entry point for any
 * UWB-gated UI element to call when tapped while disabled: it shows an
 * explanatory toast on unsupported hardware, or prompts for the permission on
 * supported hardware that simply hasn't granted it yet.
 */
data class UwbAvailability(
    val hardwareSupported: Boolean,
    val permissionGranted: Boolean,
    val onRequestAccess: () -> Unit
) {
    val isUsable: Boolean get() = hardwareSupported && permissionGranted
}

/**
 * Builds a [UwbAvailability] for the given hardware support flag (typically
 * `discoveryRepository.isUwbSupported()`). The UWB_RANGING permission is only
 * ever requested when [hardwareSupported] is true, since requesting it on
 * hardware that lacks a UWB radio can leave the permission permanently
 * ungrantable.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberUwbAvailability(hardwareSupported: Boolean): UwbAvailability {
    val context = LocalContext.current
    val permissionState = if (hardwareSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        rememberPermissionState("android.permission.UWB_RANGING")
    } else {
        null
    }
    val permissionGranted = permissionState?.status?.isGranted ?: !hardwareSupported

    return remember(hardwareSupported, permissionGranted) {
        UwbAvailability(
            hardwareSupported = hardwareSupported,
            permissionGranted = permissionGranted,
            onRequestAccess = {
                if (!hardwareSupported) {
                    Toast.makeText(
                        context,
                        "This device doesn't support Ultra-wideband (UWB) ranging.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    permissionState?.launchPermissionRequest()
                }
            }
        )
    }
}
