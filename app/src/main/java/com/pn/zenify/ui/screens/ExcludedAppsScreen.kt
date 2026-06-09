package com.pn.zenify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pn.zenify.data.AppInfo
import com.pn.zenify.ui.UiState
import com.pn.zenify.ui.components.AppRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcludedAppsScreen(
    state: UiState,
    onBack: () -> Unit,
    onIncludeApp: (AppInfo) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Excluded apps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val excluded = state.excluded
        if (excluded.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No excluded apps.\n\nLong-press an app on the home screen and tap the " +
                        "block icon to exclude it. Excluded apps are hidden from the list and " +
                        "are never force-stopped.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    "${excluded.size} app(s) are hidden from the list and never force-stopped. " +
                        "Tap the lock icon to include an app again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(excluded, key = { it.packageName }) { app ->
                // AppRow shows a lock icon for excluded apps; tapping it includes
                // the app again. The row body tap is a no-op here.
                AppRow(
                    app = app,
                    onClick = {},
                    onToggleWhitelist = { onIncludeApp(app) },
                )
            }
        }
    }
}
