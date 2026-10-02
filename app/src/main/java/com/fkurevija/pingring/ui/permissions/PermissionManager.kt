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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.*
import com.fkurevija.pingring.R

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
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionWrapper(
    content: @Composable () -> Unit,
) {
    val packageManager = LocalContext.current.packageManager
    val hasBluetooth = remember(packageManager) {
        packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) ||
                packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }
    val hasWifi = remember(packageManager) {
        packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI) ||
                packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    }
    val hasLocation = remember(packageManager) {
        packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION) ||
                packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS) ||
                packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_NETWORK)
    }

    val permissions = remember(hasBluetooth, hasWifi, hasLocation) {
        val list = mutableListOf<String>()
        if (hasLocation) {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (hasBluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (hasWifi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            isPermissionDeclaredOnDevice(packageManager, Manifest.permission.ACCESS_LOCAL_NETWORK)
        ) {
            list.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        list
    }

    if (permissions.isEmpty()) {
        content()
        return
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
            text = stringResource(R.string.permissions_required),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.permissions_rationale),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { permissionState.launchMultiplePermissionRequest() }) {
            Text(stringResource(if (permissionState.shouldShowRationale) R.string.allow_in_settings else R.string.grant_permissions))
        }
    }
}
