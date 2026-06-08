package com.pn.zenify.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pn.zenify.data.AppInfo
import com.pn.zenify.ui.UiEvent
import com.pn.zenify.ui.UiState
import com.pn.zenify.ui.components.AppRow
import com.pn.zenify.ui.components.StatusBanner
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    events: SharedFlow<UiEvent>,
    onQueryChange: (String) -> Unit,
    onEnterSelection: (AppInfo) -> Unit,
    onToggleSelect: (AppInfo) -> Unit,
    onSelectAll: () -> Unit,
    onExitSelection: () -> Unit,
    onToggleManaged: (AppInfo) -> Unit,
    onToggleWhitelist: (AppInfo) -> Unit,
    onHibernate: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEngineSetup: () -> Unit,
) {
    val context = LocalContext.current
    var searching by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        events.collect { e ->
            when (e) {
                is UiEvent.Message -> scope.launch { snackbar.showSnackbar(e.text) }
                is UiEvent.OpenEngineSetup -> onOpenEngineSetup()
            }
        }
    }

    val selecting = state.selectionMode
    BackHandler(enabled = selecting) { onExitSelection() }

    // Shared row renderer so every section behaves identically.
    val row: @Composable (AppInfo) -> Unit = { app ->
        AppRow(
            app = app,
            selectionMode = state.selectionMode,
            selected = app.packageName in state.selection,
            onClick = { if (state.selectionMode) onToggleSelect(app) else onToggleManaged(app) },
            onLongClick = { if (!state.selectionMode) onEnterSelection(app) },
            onToggleWhitelist = { onToggleWhitelist(app) },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selecting) "${state.selectionCount} selected" else "Zenify",
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = onExitSelection) {
                            Icon(Icons.Filled.Close, contentDescription = "Exit selection")
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = onSelectAll) {
                            Icon(Icons.Filled.DoneAll, contentDescription = "Select all running")
                        }
                    } else {
                        IconButton(onClick = { searching = !searching }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onHibernate,
                icon = {
                    if (state.hibernatingNow) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(2.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.Bedtime, contentDescription = null)
                    }
                },
                text = {
                    Text(
                        when {
                            state.hibernatingNow -> "Hibernating…"
                            selecting && state.selectionCount > 0 -> "Hibernate ${state.selectionCount}"
                            else -> "Hibernate all"
                        }
                    )
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            if (!state.usageAccessGranted && !selecting) {
                item {
                    StatusBanner(
                        icon = Icons.Filled.Bolt,
                        title = "Grant usage access",
                        subtitle = "Zenify needs this to detect which apps are awake.",
                        actionable = true,
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                    )
                }
            }

            if (searching && !selecting) {
                item {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        placeholder = { Text("Search apps") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        singleLine = true,
                    )
                }
            }

            if (state.running.isNotEmpty()) {
                sectionHeader(
                    "RUNNING NOW · ${state.running.size}" +
                        if (!selecting) "   ·   long-press to select" else ""
                )
                items(state.running, key = { "r_${it.packageName}" }) { row(it) }
            }

            if (state.hibernated.isNotEmpty()) {
                sectionHeader("HIBERNATED")
                items(state.hibernated, key = { "h_${it.packageName}" }) { row(it) }
            }

            if (state.others.isNotEmpty()) {
                sectionHeader("ALL APPS · TAP TO MANAGE")
                items(state.others, key = { "o_${it.packageName}" }) { row(it) }
            }

            if (state.loading) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            }
        }
    }
}

private fun LazyListScope.sectionHeader(text: String) {
    item {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp),
        )
    }
}
