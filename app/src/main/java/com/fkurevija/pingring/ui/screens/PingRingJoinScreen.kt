package com.fkurevija.pingring.ui.screens

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.fkurevija.pingring.data.TextInputPolicy
import com.fkurevija.pingring.data.session.MAX_PARTICIPANTS
import com.fkurevija.pingring.R
import com.fkurevija.pingring.ui.theme.PingRingTheme
import com.fkurevija.pingring.data.session.InviteSecret

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PingRingJoinScreen(
    savedUsername: String = "",
    existingMembers: List<String> = emptyList(),
    isConnecting: Boolean = false,
    onBack: () -> Unit,
    onJoinEvent: (eventCode: String, username: String, inviteSecret: String?) -> Unit
) {
    var eventCode by remember { mutableStateOf("") }
    var username by remember(savedUsername) { mutableStateOf(savedUsername) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Nothing is live yet on this screen, so back simply means "never mind" -
    // but say so explicitly instead of leaning on the navigation default, which
    // pops whatever is underneath and would take the user out of the app
    // entirely if this screen were ever the only entry on the stack.
    BackHandler { onBack() }

    fun attemptJoin(codeToUse: String, inviteSecret: String? = null) {
        val rawUsername = username.trim()
        val trimmedUsername = if (rawUsername.isBlank()) {
            savedUsername.ifBlank { "Guest" }
        } else {
            rawUsername
        }
        val usernameErr = TextInputPolicy.usernameError(trimmedUsername, existingMembers)
        if (usernameErr == TextInputPolicy.UsernameError.REQUIRED) {
            usernameError = context.getString(R.string.join_username_required)
            return
        }
        if (usernameErr == TextInputPolicy.UsernameError.TAKEN) {
            usernameError = context.getString(R.string.join_username_taken, trimmedUsername)
            return
        }
        if (existingMembers.size + 1 >= MAX_PARTICIPANTS) {
            inputError = context.getString(R.string.join_pingring_full, MAX_PARTICIPANTS)
            return
        }
        val parsedEventCode = PingRingCodeParser.parse(codeToUse)
        if (parsedEventCode == null) {
            inputError = context.getString(R.string.join_code_invalid)
            return
        }
        onJoinEvent(parsedEventCode, trimmedUsername, inviteSecret)
    }

    if (showScanner) {
        QrScannerScreen(
            onCancel = { showScanner = false },
            onJoinEvent = { invite ->
                eventCode = invite.eventCode
                showScanner = false
                inputError = null
                attemptJoin(invite.eventCode, invite.secret)
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.join_pingring), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.enter_pingring_code),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.ask_host_for_code),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(20.dp))

                    // Username Field
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            // Sanitized as typed rather than on save, so the host
                            // sees their name as it will actually be stored and
                            // shown to guests rather than discovering a stripped
                            // character on the radar after launching.
                            username = TextInputPolicy.sanitizeUsername(it)
                            usernameError = null
                        },
                        label = { Text(stringResource(R.string.username)) },
                        placeholder = { Text(stringResource(R.string.username_example)) },
                        isError = usernameError != null,
                        supportingText = {
                            usernameError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        },
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    // PingRing Code Field
                    OutlinedTextField(
                        value = eventCode,
                        onValueChange = {
                            // Upper case and hex-only are enforced here, not just
                            // by the keyboard: a pasted code or a hardware
                            // keyboard bypasses KeyboardCapitalization entirely,
                            // and accepting those characters only to refuse the
                            // code on submit is the worse failure.
                            eventCode = TextInputPolicy.sanitizeEventCode(it)
                            inputError = null
                        },
                        label = { Text(stringResource(R.string.pingring_code)) },
                        placeholder = { Text(stringResource(R.string.pingring_code_example)) },
                        isError = inputError != null,
                        supportingText = {
                            inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        },
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters
                        ),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { attemptJoin(eventCode) },
                        enabled = !isConnecting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.connecting), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text(stringResource(R.string.connect), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f))
                        Text(
                            stringResource(R.string.or),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("code-nfc-or"),
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))

                    // The two ways in: the host's QR code, or the six-character
                    // code they read out. There is no third list of nearby
                    // sessions any more - a ring is not advertised as findable,
                    // so a phone that has not been told the code or shown the
                    // QR code has no way to join, and pretending otherwise with
                    // an empty "Nearby PingRings" heading only made the code
                    // field harder to find.
                    val hasCamera = remember(context) {
                        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
                    }

                    Column(Modifier.testTag("nfc-join-option")) {
                        NfcJoinControl(
                            enabled = !isConnecting,
                            onInvite = { invite ->
                                eventCode = invite.eventCode
                                inputError = null
                                attemptJoin(invite.eventCode, invite.secret)
                            },
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f))
                        Text(
                            stringResource(R.string.or),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("join-methods-or"),
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { if (hasCamera) showScanner = true },
                        enabled = hasCamera,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("qr-join-option"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(if (hasCamera) R.string.scan_host_qr else R.string.camera_not_available),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PingRingJoinScreenPreview() {
    PingRingTheme {
        PingRingJoinScreen(onBack = {}, onJoinEvent = { _, _, _ -> })
    }
}
