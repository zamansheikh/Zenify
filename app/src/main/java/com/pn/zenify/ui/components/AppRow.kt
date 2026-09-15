package com.pn.zenify.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pn.zenify.data.AppInfo

/**
 * One app in any list: icon, name, live status chip, and a trailing lock
 * (excluded) or moon (managed) toggle. In selection mode a checkbox leads the
 * row and the whole row toggles.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRow(
    app: AppInfo,
    onClick: () -> Unit,
    onToggleWhitelist: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (selected) scheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent
    ListItem(
        modifier = modifier
            .background(container)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                    Spacer(Modifier.width(4.dp))
                }
                AsyncImage(
                    model = app.icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        },
        headlineContent = {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusPill(state = app.runState, text = app.detail)
                if (app.risky) {
                    Icon(
                        imageVector = Icons.Filled.WarningAmber,
                        contentDescription = app.riskReason ?: "Risky to force-stop",
                        tint = scheme.tertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        trailingContent = {
            // Lock = excluded (never force-stopped); moon = managed for
            // hibernation. Tapping toggles the exclude state.
            if (app.whitelisted || app.managed) {
                IconButton(onClick = onToggleWhitelist) {
                    Icon(
                        imageVector = if (app.whitelisted) Icons.Filled.Lock
                        else Icons.Outlined.Bedtime,
                        contentDescription = if (app.whitelisted) "Excluded — tap to include"
                        else "Managed — tap to exclude",
                        tint = if (app.whitelisted) scheme.error else scheme.primary,
                    )
                }
            }
        },
    )
}
