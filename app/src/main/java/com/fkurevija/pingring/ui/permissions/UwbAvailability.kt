package com.fkurevija.pingring.ui.permissions

import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.fkurevija.pingring.R

/**
 * Whether Ultra-wideband ranging can actually be used right now: the device
 * must report UWB hardware support AND the user must have granted the
 * UWB_RANGING permission. [onRequestAccess] is the single entry point for any
 * UWB-gated UI element to call when tapped while disabled: it shows an
 * explanatory toast on unsupported hardware, or prompts for the permission on
 * supported hardware that simply hasn't granted it yet.
 *
 * Deliberately *not* a data class. It used to be one, which is a trap: a
 * generated `equals` would compare [onRequestAccess] by identity, so two
 * instances describing identical state compared unequal and any caller using
 * `==` to decide whether to recompose would recompose every time. Equality here
 * means the two things that actually describe the state - hardware and
 * permission - and ignores the callback, which is a fresh lambda per call
 * anyway.
 */
class UwbAvailability(
    val hardwareSupported: Boolean,
    val permissionGranted: Boolean,
    val onRequestAccess: () -> Unit
) {
    val isUsable: Boolean get() = hardwareSupported && permissionGranted

    /** State equality, ignoring [onRequestAccess]. See the class comment. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UwbAvailability) return false
        return hardwareSupported == other.hardwareSupported &&
            permissionGranted == other.permissionGranted
    }

    /** Consistent with [equals]: derived from the two state fields only. */
    override fun hashCode(): Int =
        31 * hardwareSupported.hashCode() + permissionGranted.hashCode()

    override fun toString(): String =
        "UwbAvailability(hardwareSupported=$hardwareSupported, permissionGranted=$permissionGranted)"
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
    val unsupportedMessage = stringResource(R.string.uwb_not_supported)
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
                        unsupportedMessage,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    permissionState?.launchPermissionRequest()
                }
            }
        )
    }
}
