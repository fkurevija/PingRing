package com.fkurevija.pingring.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.BleRangePolicy
import com.fkurevija.pingring.data.DiagnosticsFacts
import com.fkurevija.pingring.data.DiagnosticsPolicy
import com.fkurevija.pingring.data.DiagnosticsPolicy.Key
import com.fkurevija.pingring.data.DiagnosticsProbe
import kotlinx.coroutines.launch

/**
 * Build version and the device's own capabilities.
 *
 * Two audiences. A user reporting a bug needs to say what they are running
 * without guessing; the most useful thing an app can hand a triager is a build
 * number, a device, and the list of capabilities that silently change its
 * behaviour.
 *
 * **Nothing here identifies the member.** No username, no event code, no
 * coordinates, no peer. Not because those are filtered out, but because
 * [DiagnosticsFacts] has no field they could occupy - see
 * [DiagnosticsPolicy] for why that is enforced at the type rather than in a
 * filter that could be forgotten.
 *
 * [facts] is passed in rather than probed here so the screen composes in a test
 * with no Context, package manager or vibrator, the same reason `PeerList` and
 * `ActiveBeaconEntry` are `internal`. See [rememberDiagnosticsFacts] for the
 * probing half.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    facts: DiagnosticsFacts,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val rows = remember(facts) { DiagnosticsPolicy.rows(facts) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        AboutBody(
            rows = rows,
            onCopy = {
                copyReportToClipboard(context, facts)
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.about_report_copied)
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}
/**
 * The scrolling contents, split out so the copy button can be exercised without
 * the clipboard or the snackbar.
 */
@Composable
private fun AboutBody(
    rows: List<DiagnosticsPolicy.Row>,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.about_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(row.key.labelRes()),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        // The value carries the emphasis: someone reading a
                        // version number off a screen and typing it into a bug
                        // report is exactly where 1.0/1.O and 0/8 mix up.
                        Text(
                            text = row.value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onCopy,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.about_copy_report))
        }

        Text(
            stringResource(R.string.about_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The localized label for a diagnostic row. */
private fun Key.labelRes(): Int = when (this) {
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

/**
 * Puts the plain-text report on the clipboard.
 *
 * Values are language-neutral on purpose - see [DiagnosticsPolicy.report] - so a
 * report pasted into an issue reads the same whatever the reporter's phone is
 * set to.
 */
private fun copyReportToClipboard(context: Context, facts: DiagnosticsFacts) {
    val report = DiagnosticsPolicy.report(facts) { key -> context.getString(key.labelRes()) }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("PingRing diagnostics", report))
}
/**
 * Reads the facts off this device, once.
 *
 * The probing half, kept out of [AboutScreen] so the screen itself needs no
 * Context, package manager or vibrator to be composed in a test.
 *
 * Remembered on the inputs rather than sampled per recomposition, so a
 * capability that flickers while the screen is open cannot make two lines of the
 * same report disagree with each other.
 */
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
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        }.getOrNull()

        val hasVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator?.hasVibrator() == true
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                ?.hasVibrator() == true
        }

        DiagnosticsProbe.read(
            applicationId = context.packageName,
            versionName = packageInfo?.versionName ?: UNKNOWN,
            versionCode = packageInfo?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode
                else @Suppress("DEPRECATION") it.versionCode.toLong()
            }?.toInt() ?: 0,
            isDebugBuild = isDebugBuild,
            uwbSupported = uwbSupported,
            signedBleIdentitySupported = BleRangePolicy.supportsSignedBleIdentityAdvertising(context),
            bluetoothEnabled = bluetoothEnabled,
            wifiEnabled = wifiEnabled,
            hasVibrator = hasVibrator,
        )
    }
}

/**
 * Stands in for anything the platform would not tell us.
 *
 * A stated unknown is worth more in a bug report than a blank or a zero: a
 * blank reads as "not relevant" and a zero reads as a real measurement.
 */
private const val UNKNOWN = "unknown"