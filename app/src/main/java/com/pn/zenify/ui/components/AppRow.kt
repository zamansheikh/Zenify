package com.pn.zenify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pn.zenify.data.AppInfo
import com.pn.zenify.data.RunState

@Composable
fun AppRow(
    app: AppInfo,
    onClick: () -> Unit,
    onToggleWhitelist: () -> Unit,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    selected: Boolean = false,
) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    ListItem(
        modifier = modifier
            .background(container)
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectable) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onClick() },
                    )
                }
                AsyncImage(
                    model = app.icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape),
                )
            }
        },
        headlineContent = { Text(app.label) },
        supportingContent = { Text(stateLabel(app), style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (app.managed) {
                    IconButton(onClick = onToggleWhitelist) {
                        Icon(
                            imageVector = if (app.whitelisted) Icons.Filled.Lock
                            else Icons.Outlined.Bedtime,
                            contentDescription = if (app.whitelisted) "Whitelisted" else "Hibernates",
                            tint = if (app.whitelisted) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                StateDot(app.runState)
                Spacer(Modifier.width(4.dp))
            }
        },
    )
}

@Composable
private fun StateDot(state: RunState) {
    val color = when (state) {
        RunState.FOREGROUND -> MaterialTheme.colorScheme.primary
        RunState.RUNNING -> MaterialTheme.colorScheme.tertiary
        RunState.STOPPED -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        Modifier
            .padding(end = 4.dp)
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

private fun stateLabel(app: AppInfo): String = when {
    app.whitelisted -> "Won't hibernate"
    app.runState == RunState.FOREGROUND -> "In use right now"
    app.runState == RunState.RUNNING -> "Running in background"
    app.managed -> "Hibernated"
    app.lastUsed == 0L -> "Idle · not used recently"
    else -> "Idle"
}
