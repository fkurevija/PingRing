// File: AboutScreenContent.kt
package com.combustiblelemonlabs.pingring.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.combustiblelemonlabs.pingring.R
import com.combustiblelemonlabs.pingring.data.ranging.BleRangeProbe
import com.combustiblelemonlabs.pingring.data.DiagnosticsFacts
import com.combustiblelemonlabs.pingring.data.DiagnosticsPolicy
import com.combustiblelemonlabs.pingring.data.DiagnosticsPolicy.Key
import com.combustiblelemonlabs.pingring.data.DiagnosticsProbe
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreenContent(
    facts: DiagnosticsFacts,
    onBack: () -> Unit,
    onReportAProblem: () -> Unit = {},
    reduceMotionEnabled: Boolean = false,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val rows = remember(facts) { DiagnosticsPolicy.rows(facts) }
    // tap‑sequence state
    val versionTapSequence = remember { VersionTapSequence() }
    var showMatrixPrompt by rememberSaveable { mutableStateOf(false) }
    var showMatrix by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        AboutBody(
            rows = rows,
            onVersionTap = {
                if (versionTapSequence.tap() == 0) showMatrixPrompt = true
            },
            onCopy = {
                copyReportToClipboard(context, facts)
                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.about_report_copied)) }
            },
            onReportAProblem = onReportAProblem,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
    if (showMatrixPrompt) {
        AlertDialog(
            onDismissRequest = { showMatrixPrompt = false },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
            text = { Text(stringResource(R.string.matrix_invitation), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                Button(
                    onClick = { showMatrixPrompt = false; showMatrix = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(R.string.matrix_accept)) }
            },
        )
    }
    if (showMatrix) {
        MatrixEasterEgg(onDismiss = { showMatrix = false }, reduceMotionEnabled = reduceMotionEnabled)
    }
}

@Composable
private fun AboutBody(
    rows: List<DiagnosticsPolicy.Row>,
    onVersionTap: () -> Unit,
    onCopy: () -> Unit,
    onReportAProblem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .let { if (row.key == Key.APP_VERSION) it.clickable(role = Role.Button, onClick = onVersionTap) else it }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = stringResource(row.key.diagnosticLabelRes()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Text(text = row.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.ContentCopy, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.about_copy_report))
        }
        OutlinedButton(onClick = onReportAProblem, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.report_a_problem))
        }
        Text(stringResource(R.string.about_privacy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun Key.diagnosticLabelRes(): Int = when (this) {
    Key.APP_VERSION -> R.string.diag_app_version
    Key.BUILD_TYPE -> R.string.diag_build_type
    Key.APPLICATION_ID -> R.string.diag_application_id
    Key.DEVICE -> R.string.diag_device
    Key.ANDROID_VERSION -> R.string.diag_android_version
    Key.BUILD -> R.string.diag_build
    Key.UWB_SUPPORTED -> R.string.diag_uwb_supported
    Key.SIGNED_BLE_IDENTITY -> R.string.diag_signed_ble_identity
    Key.BLUETOOTH_ENABLED -> R.string.diag_bluetooth_enabled
    Key.WIFI_ENABLED -> R.string.diag_wifi_enabled
    Key.VIBRATOR -> R.string.diag_vibrator
}

private fun copyReportToClipboard(context: Context, facts: DiagnosticsFacts) {
    val report = DiagnosticsPolicy.report(facts) { key -> context.getString(key.diagnosticLabelRes()) }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("PingRing diagnostics", report))
}

@Composable
fun rememberDiagnosticsFacts(
    isDebugBuild: Boolean,
    uwbSupported: Boolean,
    bluetoothEnabled: Boolean,
    wifiEnabled: Boolean,
): DiagnosticsFacts {
    val context = LocalContext.current
    return remember(isDebugBuild, uwbSupported, bluetoothEnabled, wifiEnabled, context) {
        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        }.getOrNull()
        val hasVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator?.hasVibrator() == true
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.hasVibrator() == true
        }
        DiagnosticsProbe.read(
            applicationId = context.packageName,
            versionName = packageInfo?.versionName ?: UNKNOWN,
            versionCode = packageInfo?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }?.toInt() ?: 0,
            isDebugBuild = isDebugBuild,
            uwbSupported = uwbSupported,
            signedBleIdentitySupported = BleRangeProbe.supportsSignedBleIdentityAdvertising(context),
            bluetoothEnabled = bluetoothEnabled,
            wifiEnabled = wifiEnabled,
            hasVibrator = hasVibrator,
        )
    }
}

private const val UNKNOWN = "unknown"
