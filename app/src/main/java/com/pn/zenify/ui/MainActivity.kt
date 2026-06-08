package com.pn.zenify.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pn.zenify.ui.screens.HomeScreen
import com.pn.zenify.ui.screens.SettingsScreen
import com.pn.zenify.ui.theme.ZenifyTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ZenifyTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(false) }

                if (showSettings) {
                    SettingsScreen(
                        state = state,
                        onBack = { showSettings = false },
                        onToggleAuto = viewModel::setAutoHibernate,
                        onDelayChange = viewModel::setDelayMinutes,
                        onToggleShowSystem = viewModel::setShowSystem,
                        onToggleScreenOff = viewModel::setHibernateOnScreenOff,
                        onRequestShizuku = viewModel::requestShizuku,
                    )
                } else {
                    HomeScreen(
                        state = state,
                        onQueryChange = viewModel::setQuery,
                        onToggleManaged = viewModel::toggleManaged,
                        onToggleWhitelist = viewModel::toggleWhitelist,
                        onHibernateNow = viewModel::hibernateNow,
                        onRequestShizuku = viewModel::requestShizuku,
                        onOpenSettings = { showSettings = true },
                        onRefresh = viewModel::refresh,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}
