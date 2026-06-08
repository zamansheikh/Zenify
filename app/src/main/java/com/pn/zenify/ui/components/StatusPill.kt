package com.pn.zenify.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pn.zenify.data.RunState

/** Small tonal chip that conveys an app's run state, Greenify-style. */
@Composable
fun StatusPill(state: RunState, text: String) {
    val (bg, fg) = when (state) {
        RunState.FOREGROUND ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        RunState.FOREGROUND_SERVICE ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        RunState.WORKING ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        RunState.CACHED ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        RunState.STOPPED ->
            Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = androidx.compose.ui.Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
