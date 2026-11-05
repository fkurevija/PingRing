package com.combustiblelemonlabs.pingring.ui.screens

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.combustiblelemonlabs.pingring.R
import com.combustiblelemonlabs.pingring.data.PendingJoinRequest

@Composable
internal fun JoinRequestOverlay(
    requests: List<PendingJoinRequest>,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        Column(
            modifier = Modifier.fillMaxSize().then(contentModifier),
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
        if (requests.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight / 2)
                    // Consume taps on the card background, not the covered screen.
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                ) {
                    Text(
                        pluralStringResource(R.plurals.join_request_count, requests.size, requests.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    requests.forEach { request ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(request.deviceName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { onDeny(request.endpointId) }) {
                                Text(stringResource(R.string.deny))
                            }
                            Spacer(Modifier.width(4.dp))
                            Button(onClick = { onApprove(request.endpointId) }) {
                                Text(stringResource(R.string.allow))
                            }
                        }
                    }
                }
            }
        }
    }
}
