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
import com.pn.zenify.ui.screens.EngineSetupScreen
import com.pn.zenify.ui.screens.HomeScreen
import com.pn.zenify.ui.screens.SettingsScreen
import com.pn.zenify.ui.theme.ZenifyTheme

private enum class Screen { HOME, SETTINGS, ENGINE }

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ZenifyTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                var screen by remember { mutableStateOf(Screen.HOME) }

                when (screen) {
                    Screen.HOME -> HomeScreen(
                        state = state,
                        events = viewModel.events,
                        onQueryChange = viewModel::setQuery,
                        onEnterSelection = viewModel::enterSelection,
                        onToggleSelect = viewModel::toggleSelect,
                        onSelectAll = viewModel::selectAllRunning,
                        onExitSelection = viewModel::exitSelection,
                        onToggleManaged = viewModel::toggleManaged,
                        onToggleWhitelist = viewModel::toggleWhitelist,
                        onHibernate = viewModel::hibernateSelectedOrAll,
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onOpenEngineSetup = { screen = Screen.ENGINE },
                    )

                    Screen.SETTINGS -> SettingsScreen(
                        state = state,
                        onBack = { screen = Screen.HOME },
                        onToggleAuto = viewModel::setAutoHibernate,
                        onDelayChange = viewModel::setDelayMinutes,
                        onToggleShowSystem = viewModel::setShowSystem,
                        onToggleScreenOff = viewModel::setHibernateOnScreenOff,
                        onOpenEngineSetup = { screen = Screen.ENGINE },
                    )

                    Screen.ENGINE -> EngineSetupScreen(
                        state = state,
                        onBack = { screen = Screen.SETTINGS },
                        onRequestShizuku = viewModel::requestShizuku,
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
