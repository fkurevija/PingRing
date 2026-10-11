package com.fkurevija.pingring.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fkurevija.pingring.R
import com.fkurevija.pingring.data.nfc.NfcInviteApdu
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import com.fkurevija.pingring.data.session.Event

@Composable
internal fun NfcJoinControl(
    enabled: Boolean,
    onInvite: (PingRingInvite) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val adapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    val hasNfc = remember(context) {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC)
    }
    var reading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    var nfcEnabled by remember(adapter) { mutableStateOf(adapter?.isEnabled == true) }
    var activityResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val latestOnInvite by rememberUpdatedState(onInvite)
    val processingTag = remember { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner, adapter) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                nfcEnabled = adapter?.isEnabled == true
            }
            activityResumed = lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(enabled, nfcEnabled) {
        if (!enabled || !nfcEnabled) reading = false
    }

    DisposableEffect(reading, enabled, nfcEnabled, activityResumed, activity, adapter) {
        val activeAdapter = adapter
        val activeActivity = activity
        val readerEnabled = reading && enabled && nfcEnabled && activityResumed &&
            activeAdapter != null && activeActivity != null
        if (readerEnabled) {
            val mainHandler = Handler(Looper.getMainLooper())
            activeAdapter.enableReaderMode(
                activeActivity,
                { tag ->
                    if (processingTag.compareAndSet(false, true)) {
                        val invite = readInvite(tag)
                        mainHandler.post {
                            processingTag.set(false)
                            if (invite != null) {
                                reading = false
                                failure = false
                                latestOnInvite(invite)
                            } else {
                                failure = true
                            }
                        }
                    }
                },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null,
            )
        }
        onDispose {
            if (readerEnabled) activeAdapter.disableReaderMode(activeActivity)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                failure = false
                reading = !reading
            },
            enabled = enabled && hasNfc && nfcEnabled && activity != null,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                stringResource(
                    when {
                        reading -> R.string.nfc_cancel
                        else -> R.string.nfc_join_button
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Spacer(Modifier.height(6.dp))
        val guidance = when {
            !hasNfc || adapter == null -> stringResource(R.string.nfc_not_available)
            !nfcEnabled -> stringResource(R.string.nfc_not_enabled)
            failure -> stringResource(R.string.nfc_read_failed)
            reading -> stringResource(R.string.nfc_join_prompt)
            else -> null
        }
        guidance?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun readInvite(tag: Tag): PingRingInvite? {
    val isoDep = IsoDep.get(tag) ?: return null
    return try {
        isoDep.connect()
        val payload = NfcInviteApdu.decodeResponse(isoDep.transceive(NfcInviteApdu.selectCommand))
            ?: return null
        PingRingCodeParser.parseInvite(payload)?.takeIf { it.secret != null }
    } catch (_: IOException) {
        null
    } finally {
        runCatching { isoDep.close() }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
