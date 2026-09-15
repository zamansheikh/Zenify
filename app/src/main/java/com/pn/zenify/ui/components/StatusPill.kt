package com.pn.zenify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pn.zenify.data.RunState

/**
 * Small tonal chip that conveys an app's run state, Greenify-style. Live
 * states get a coloured dot; asleep apps get plain muted text so the list
 * stays quiet where nothing is happening.
 */
@Composable
fun StatusPill(state: RunState, text: String) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (state) {
        RunState.FOREGROUND -> scheme.primaryContainer to scheme.onPrimaryContainer
        RunState.FOREGROUND_SERVICE -> scheme.errorContainer to scheme.onErrorContainer
        RunState.WORKING -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        RunState.CACHED -> scheme.secondaryContainer to scheme.onSecondaryContainer
        RunState.STOPPED -> Color.Transparent to scheme.onSurfaceVariant
    }
    val dot = when (state) {
        RunState.FOREGROUND -> scheme.primary
        RunState.FOREGROUND_SERVICE -> scheme.error
        RunState.WORKING -> scheme.tertiary
        RunState.CACHED -> scheme.secondary
        RunState.STOPPED -> null
    }
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.extraSmall) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(
                horizontal = if (dot != null) 8.dp else 0.dp,
                vertical = if (dot != null) 3.dp else 0.dp,
            ),
        ) {
            if (dot != null) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(dot, CircleShape)
                )
            }
            Text(text = text, style = MaterialTheme.typography.labelSmall)
        }
    }
}
