// File: AboutScreen.kt (wrapper)
package com.combustiblelemonlabs.pingring.ui.screens

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import com.combustiblelemonlabs.pingring.data.DiagnosticsFacts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    facts: DiagnosticsFacts,
    onBack: () -> Unit,
    onReportAProblem: () -> Unit = {},
    reduceMotionEnabled: Boolean = false,
) {
    AboutScreenContent(
        facts = facts,
        onBack = onBack,
        onReportAProblem = onReportAProblem,
        reduceMotionEnabled = reduceMotionEnabled,
    )
}
