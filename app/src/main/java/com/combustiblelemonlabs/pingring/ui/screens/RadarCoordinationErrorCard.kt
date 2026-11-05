package com.combustiblelemonlabs.pingring.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun RadarCoordinationErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}
