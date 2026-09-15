package com.pn.zenify.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pn.zenify.core.HibernationEngine
import com.pn.zenify.ui.UiState
import com.pn.zenify.ui.components.GroupCard
import com.pn.zenify.ui.components.SectionHeader

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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
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
                .padding(bottom = 24.dp)
        ) {
            SectionHeader("Hibernation")
            GroupCard {
                SwitchRow(
                    title = "Auto-hibernation",
                    subtitle = "Keep a background watcher running and hibernate idle apps automatically. Needs Shizuku.",
                    checked = state.autoHibernate,
                    onChange = onToggleAuto,
                )
                RowDivider()
                SwitchRow(
                    title = "Hibernate when screen turns off",
                    subtitle = "Run a pass immediately after the screen locks.",
                    checked = state.hibernateOnScreenOff,
                    onChange = onToggleScreenOff,
                )
                RowDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Idle delay before hibernating",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${state.delayMinutes} min",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        "How long an app must sit unused before the watcher puts it to sleep.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = state.delayMinutes.toFloat(),
                        onValueChange = { onDelayChange(it.toInt().coerceIn(1, 60)) },
                        valueRange = 1f..60f,
                    )
                }
            }

            SectionHeader("Apps")
            GroupCard {
                SwitchRow(
                    title = "Show system apps",
                    subtitle = "Include preinstalled/system packages in the list.",
                    checked = state.showSystem,
                    onChange = onToggleShowSystem,
                )
                RowDivider()
                NavRow(
                    title = "Excluded apps",
                    subtitle = if (state.excludedCount == 0)
                        "None yet — excluded apps are hidden from the list and never force-stopped."
                    else "${state.excludedCount} app(s) hidden and never force-stopped.",
                    badge = state.excludedCount.takeIf { it > 0 }?.toString(),
                    onClick = onOpenExcluded,
                )
            }

            SectionHeader("Engine")
            GroupCard {
                val (engineSub, active) = when (state.engineMethod) {
                    HibernationEngine.Method.SHIZUKU -> "Shizuku — silent & instant." to true
                    HibernationEngine.Method.ACCESSIBILITY -> "Accessibility — no root, taps Force stop for you." to true
                    HibernationEngine.Method.NONE -> "Not set up yet — tap to choose a method." to false
                }
                NavRow(
                    title = "Hibernation engine",
                    subtitle = engineSub,
                    badge = if (active) "Active" else "Set up",
                    badgeActive = active,
                    onClick = onOpenEngineSetup,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Zenify hibernates apps without root. Background auto-hibernation needs " +
                    "Shizuku; the accessibility method runs on demand and works on any device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun NavRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    badge: String? = null,
    badgeActive: Boolean = true,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (badge != null) {
                    val scheme = MaterialTheme.colorScheme
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = if (badgeActive) scheme.primaryContainer else scheme.tertiaryContainer,
                        contentColor = if (badgeActive) scheme.onPrimaryContainer else scheme.onTertiaryContainer,
                    ) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        },
    )
}
