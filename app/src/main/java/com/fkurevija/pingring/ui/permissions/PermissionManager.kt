package com.fkurevija.pingring.ui.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.*

/**
 * Whether [permission] is actually a recognized, requestable permission on
 * this device. Some permissions (e.g. android.permission.ACCESS_LOCAL_NETWORK)
 * are gated on [Build.VERSION.SDK_INT] in code, but individual OEM builds can
 * report a matching SDK level while still lacking the permission itself
 * (it may only exist on certain preview/canary builds of that API level).
 * Requesting an unrecognized permission can never be granted by the system,
 * which would permanently strand the user on the permissions gate - so any
 * such permission must be verified to exist before it's required upfront.
 */
private fun isPermissionDeclaredOnDevice(packageManager: PackageManager, permission: String): Boolean {
    return try {
        packageManager.getPermissionInfo(permission, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionWrapper(
    content: @Composable () -> Unit
) {
    val packageManager = LocalContext.current.packageManager
    val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        // UWB_RANGING is deliberately NOT requested here: on devices without a
        // UWB radio, the permission can never be granted, which would
        // permanently strand the user on this screen. UWB is an optional
        // enhancement, so its permission is requested lazily, only on
        // hardware that actually reports support, via rememberUwbAvailability()
        // at the point of use (see HostSetupScreen/PingRingSettingsScreen).
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
        isPermissionDeclaredOnDevice(packageManager, Manifest.permission.ACCESS_LOCAL_NETWORK)
    ) {
        permissions.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    val permissionState = rememberMultiplePermissionsState(permissions)

    if (permissionState.allPermissionsGranted) {
        content()
    } else {
        PermissionRationale(permissionState)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionRationale(permissionState: MultiplePermissionsState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Permissions Required",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "PingRing needs Bluetooth and Location permissions to discover and connect with your friends nearby. Devices with Ultra-wideband hardware will be asked for an extra UWB permission when you enable precision ranging.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { permissionState.launchMultiplePermissionRequest() }) {
            Text(if (permissionState.shouldShowRationale) "Allow in Settings" else "Grant Permissions")
        }
    }
}
