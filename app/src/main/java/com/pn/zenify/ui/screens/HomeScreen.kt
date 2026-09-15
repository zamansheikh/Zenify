package com.pn.zenify.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pn.zenify.core.HibernationEngine
import com.pn.zenify.data.AppInfo
import com.pn.zenify.ui.SelectFilter
import com.pn.zenify.ui.UiEvent
import com.pn.zenify.ui.UiState
import com.pn.zenify.ui.components.AppRow
import com.pn.zenify.ui.components.GroupCard
import com.pn.zenify.ui.components.SectionHeader
import com.pn.zenify.ui.components.StatusBanner
import com.pn.zenify.ui.components.groupedItems
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
    onSelectFilter: (SelectFilter) -> Unit,
    onExcludeSelected: () -> Unit,
    onRemoveFromHibernated: () -> Unit,
    onExitSelection: () -> Unit,
    onToggleManaged: (AppInfo) -> Unit,
    onToggleWhitelist: (AppInfo) -> Unit,
    onHibernate: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEngineSetup: () -> Unit,
) {
    val context = LocalContext.current
    var searching by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
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
    BackHandler(enabled = searching && !selecting) {
        searching = false
        onQueryChange("")
    }

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
                        Text("${state.selectionCount} selected", fontWeight = FontWeight.SemiBold)
                    } else {
                        Text(
                            "Zenify",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
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
                        val hasSelection = state.selectionCount > 0
                        IconButton(onClick = onRemoveFromHibernated, enabled = hasSelection) {
                            Icon(Icons.Outlined.Bedtime, contentDescription = "Remove from hibernated")
                        }
                        IconButton(onClick = onExcludeSelected, enabled = hasSelection) {
                            Icon(Icons.Filled.Block, contentDescription = "Exclude selected")
                        }
                        Box {
                            IconButton(onClick = { filterMenu = true }) {
                                Icon(Icons.Filled.FilterList, contentDescription = "Select by category")
                            }
                            DropdownMenu(
                                expanded = filterMenu,
                                onDismissRequest = { filterMenu = false },
                            ) {
                                SelectFilter.entries.forEach { f ->
                                    DropdownMenuItem(
                                        text = { Text("Select ${f.label.lowercase()}") },
                                        onClick = {
                                            filterMenu = false
                                            onSelectFilter(f)
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        IconButton(onClick = {
                            searching = !searching
                            if (!searching) onQueryChange("")
                        }) {
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
            val count = if (selecting) state.selectionCount else 0
            ExtendedFloatingActionButton(
                onClick = onHibernate,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = {
                    if (state.hibernatingNow) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.DarkMode, contentDescription = null)
                    }
                },
                text = { Text(if (count > 0) "Hibernate $count" else "Hibernate all") },
            )
        },
    ) { padding ->
        val listState = rememberLazyListState()
        val focusRequester = remember { FocusRequester() }

        // Opening search: jump to top and focus the field so it's always visible
        // (it's a pinned header, not a list item that scrolls away).
        LaunchedEffect(searching) {
            if (searching) {
                listState.scrollToItem(0)
                runCatching { focusRequester.requestFocus() }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (searching && !selecting) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search apps") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(focusRequester),
                    singleLine = true,
                )
            }

            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = onRefresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (state.loading && state.apps.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp),
                    ) {
                        // Kept in selection mode on purpose: hiding it would shift
                        // every row under the finger the moment a long-press starts.
                        if (!searching) {
                            item(key = "overview") { OverviewCard(state, onOpenEngineSetup) }
                        }

                        if (!state.usageAccessGranted && !selecting) {
                            item(key = "banner_usage") {
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
                        if (!state.hasEngine && !selecting) {
                            item(key = "banner_engine") {
                                StatusBanner(
                                    icon = Icons.Filled.PowerSettingsNew,
                                    title = "Choose a hibernation engine",
                                    subtitle = "Shizuku (silent) or Accessibility (no root) — needed to put apps to sleep.",
                                    actionable = true,
                                    onClick = onOpenEngineSetup,
                                )
                            }
                        }

                        // ---- Running now ----
                        val showCalm = state.running.isEmpty() && state.query.isBlank()
                        if (state.running.isNotEmpty() || showCalm) {
                            item(key = "h_running") {
                                SectionHeader(
                                    title = "Running now · ${state.running.size}",
                                    hint = if (!selecting) "long-press to select" else null,
                                )
                            }
                        }
                        if (showCalm) {
                            item(key = "calm") {
                                GroupCard {
                                    ListItem(
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        leadingContent = {
                                            Icon(
                                                Icons.Outlined.Bedtime,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        },
                                        headlineContent = { Text("Everything is calm") },
                                        supportingContent = {
                                            Text("No apps are awake in the background right now.")
                                        },
                                    )
                                }
                            }
                        } else {
                            groupedItems(state.running, "r", { it.packageName }, row)
                        }

                        // ---- Cached ----
                        if (state.cached.isNotEmpty()) {
                            item(key = "h_cached") {
                                SectionHeader(
                                    title = "No need to hibernate · ${state.cached.size}",
                                    hint = "background-free",
                                )
                            }
                            groupedItems(state.cached, "c", { it.packageName }, row)
                        }

                        // ---- Hibernated ----
                        if (state.hibernated.isNotEmpty()) {
                            item(key = "h_hibernated") {
                                SectionHeader(title = "Hibernated · ${state.hibernated.size}")
                            }
                            groupedItems(state.hibernated, "h", { it.packageName }, row)
                        }

                        // ---- Everything else ----
                        if (state.others.isNotEmpty()) {
                            item(key = "h_others") {
                                SectionHeader(
                                    title = "All apps · ${state.others.size}",
                                    hint = "tap to manage",
                                )
                            }
                            groupedItems(state.others, "o", { it.packageName }, row)
                        }
                    }
                }
            }
        }
    }
}

/** Top-of-list summary: three counters and the active engine, one tap from setup. */
@Composable
private fun OverviewCard(state: UiState, onOpenEngineSetup: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Awake", state.activeCount, Modifier.weight(1f))
                StatTile("Cached", state.cachedCount, Modifier.weight(1f))
                StatTile("Asleep", state.asleepCount, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            val (icon, line) = when (state.engineMethod) {
                HibernationEngine.Method.SHIZUKU ->
                    Icons.Filled.Terminal to "Shizuku engine · silent & instant"
                HibernationEngine.Method.ACCESSIBILITY ->
                    Icons.Filled.Accessibility to "Accessibility engine · no root"
                HibernationEngine.Method.NONE ->
                    Icons.Filled.PowerSettingsNew to "No engine yet · tap to set one up"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onOpenEngineSetup)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    line,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                )
                Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            "$value",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = LocalContentColor.current.copy(alpha = 0.8f),
        )
    }
}
