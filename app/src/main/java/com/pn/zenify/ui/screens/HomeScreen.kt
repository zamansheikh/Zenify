package com.pn.zenify.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.pn.zenify.ui.theme.OnZenBrand
import com.pn.zenify.ui.theme.ZenBrand
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    events: SharedFlow<UiEvent>,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
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
                    if (selecting) {
                        Text("${state.selectionCount} selected", fontWeight = FontWeight.Bold)
                    } else {
                        Column {
                            Text("Zenify", fontWeight = FontWeight.Bold)
                            Text(
                                state.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = OnZenBrand.copy(alpha = 0.85f),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ZenBrand,
                    titleContentColor = OnZenBrand,
                    actionIconContentColor = OnZenBrand,
                    navigationIconContentColor = OnZenBrand,
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
            val showCount = selecting && state.selectionCount > 0
            BadgedBox(
                badge = {
                    if (showCount) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ) { Text("${state.selectionCount}") }
                    }
                }
            ) {
                FloatingActionButton(
                    onClick = onHibernate,
                    containerColor = ZenBrand,
                    contentColor = OnZenBrand,
                ) {
                    if (state.hibernatingNow) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = OnZenBrand,
                        )
                    } else {
                        Icon(
                            Icons.Filled.DarkMode,
                            contentDescription = if (showCount)
                                "Hibernate ${state.selectionCount}" else "Hibernate all",
                        )
                    }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 110.dp),
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

                groupCard(
                    title = "RUNNING NOW · ${state.running.size}",
                    hint = if (!selecting) "long-press to select" else null,
                    apps = state.running,
                    row = row,
                )
                groupCard(
                    title = "NO NEED TO HIBERNATE · ${state.cached.size}",
                    hint = "background-free",
                    apps = state.cached,
                    row = row,
                )
                groupCard(
                    title = "HIBERNATED · ${state.hibernated.size}",
                    hint = null,
                    apps = state.hibernated,
                    row = row,
                )

                if (state.others.isNotEmpty()) {
                    item { SectionLabel("ALL APPS · TAP TO MANAGE", null) }
                    items(state.others, key = { "o_${it.packageName}" }) { row(it) }
                }
            }
        }
    }
}

/** A short section rendered as a rounded grouped card. */
private fun LazyListScope.groupCard(
    title: String,
    hint: String?,
    apps: List<AppInfo>,
    row: @Composable (AppInfo) -> Unit,
) {
    if (apps.isEmpty()) return
    item {
        Column {
            SectionLabel(title, hint)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                ),
            ) {
                Column { apps.forEach { row(it) } }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, hint: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
