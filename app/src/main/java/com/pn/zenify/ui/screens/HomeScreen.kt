package com.pn.zenify.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pn.zenify.data.AppInfo
import com.pn.zenify.shizuku.ShizukuManager
import com.pn.zenify.ui.UiState
import com.pn.zenify.ui.components.AppRow
import com.pn.zenify.ui.components.StatusBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    onQueryChange: (String) -> Unit,
    onToggleManaged: (AppInfo) -> Unit,
    onToggleWhitelist: (AppInfo) -> Unit,
    onHibernateNow: () -> Unit,
    onRequestShizuku: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    var searching by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Zenify", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                actions = {
                    IconButton(onClick = { searching = !searching }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onHibernateNow,
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
                text = { Text(if (state.hibernatingNow) "Hibernating…" else "Zzz") },
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
            // ----- Setup banners -----
            if (!state.usageAccessGranted) {
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
            if (state.shizukuState != ShizukuManager.State.READY) {
                item {
                    val (title, sub) = when (state.shizukuState) {
                        ShizukuManager.State.UNAVAILABLE ->
                            "Start Shizuku" to "Shizuku isn't running. Launch it, then come back to grant access."
                        ShizukuManager.State.PERMISSION_REQUIRED ->
                            "Connect Shizuku" to "Tap to grant Zenify access so it can hibernate apps."
                        else -> "" to ""
                    }
                    StatusBanner(
                        icon = Icons.Filled.Bolt,
                        title = title,
                        subtitle = sub,
                        actionable = state.shizukuState == ShizukuManager.State.PERMISSION_REQUIRED,
                        onClick = onRequestShizuku,
                    )
                }
            }

            if (searching) {
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

            // ----- Active / managed -----
            if (state.activeManaged.isNotEmpty()) {
                sectionHeader("ACTIVE & AWAKE")
                items(state.activeManaged, key = { "a_${it.packageName}" }) { app ->
                    AppRow(
                        app = app,
                        onClick = { onToggleManaged(app) },
                        onToggleWhitelist = { onToggleWhitelist(app) },
                    )
                }
            }

            // ----- Hibernated -----
            if (state.hibernated.isNotEmpty()) {
                sectionHeader("HIBERNATED")
                items(state.hibernated, key = { "h_${it.packageName}" }) { app ->
                    AppRow(
                        app = app,
                        onClick = { onToggleManaged(app) },
                        onToggleWhitelist = { onToggleWhitelist(app) },
                    )
                }
            }

            // ----- Candidates to add -----
            if (state.candidates.isNotEmpty()) {
                sectionHeader("TAP TO MANAGE")
                items(state.candidates, key = { "c_${it.packageName}" }) { app ->
                    AppRow(
                        app = app,
                        onClick = { onToggleManaged(app) },
                        onToggleWhitelist = { onToggleWhitelist(app) },
                    )
                }
            }

            if (state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Suppress("FunctionName")
private fun androidx.compose.foundation.lazy.LazyListScope.sectionHeader(text: String) {
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
