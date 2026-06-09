package com.pn.zenify.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pn.zenify.core.HibernationEngine
import com.pn.zenify.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onToggleAuto: (Boolean) -> Unit,
    onDelayChange: (Int) -> Unit,
    onToggleShowSystem: (Boolean) -> Unit,
    onToggleScreenOff: (Boolean) -> Unit,
    onOpenExcluded: () -> Unit,
    onOpenEngineSetup: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionTitle("Hibernation")

            SwitchRow(
                title = "Auto-hibernation",
                subtitle = "Keep a background watcher running and hibernate idle apps automatically.",
                checked = state.autoHibernate,
                onChange = onToggleAuto,
            )
            SwitchRow(
                title = "Hibernate when screen turns off",
                subtitle = "Run a pass immediately after the screen locks.",
                checked = state.hibernateOnScreenOff,
                onChange = onToggleScreenOff,
            )

            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Idle delay before hibernating", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${state.delayMinutes} minute(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = state.delayMinutes.toFloat(),
                    onValueChange = { onDelayChange(it.toInt().coerceIn(1, 60)) },
                    valueRange = 1f..60f,
                )
            }

            Divider()
            SectionTitle("Apps")
            SwitchRow(
                title = "Show system apps",
                subtitle = "Include preinstalled/system packages in the list.",
                checked = state.showSystem,
                onChange = onToggleShowSystem,
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenExcluded)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Excluded apps", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.excludedCount == 0)
                            "None yet — excluded apps are hidden from the list and never force-stopped."
                        else "${state.excludedCount} app(s) hidden and never force-stopped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }

            Divider()
            SectionTitle("Engine")
            val engineSub = when (state.engineMethod) {
                HibernationEngine.Method.SHIZUKU -> "Using Shizuku — silent & instant."
                HibernationEngine.Method.ACCESSIBILITY -> "Using Accessibility — no root."
                HibernationEngine.Method.NONE -> "Not set up yet — tap to choose a method."
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenEngineSetup)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Hibernation engine", style = MaterialTheme.typography.titleSmall)
                    Text(engineSub, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }

            Divider()
            Text(
                "Zenify hibernates apps without root. Background auto-hibernation needs " +
                    "Shizuku; the accessibility method runs on demand and works on any device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
