package com.pn.zenify.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pn.zenify.ui.screens.EngineSetupScreen
import com.pn.zenify.ui.screens.ExcludedAppsScreen
import com.pn.zenify.ui.screens.HomeScreen
import com.pn.zenify.ui.screens.SettingsScreen
import com.pn.zenify.ui.theme.ZenifyTheme

private enum class Screen { HOME, SETTINGS, ENGINE, EXCLUDED }

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ZenifyTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()

                // Simple in-app back stack so the system Back button / gesture
                // navigates between screens and only exits the app from Home.
                val backStack = remember { mutableStateListOf(Screen.HOME) }
                val current = backStack.last()
                fun navigate(screen: Screen) {
                    if (backStack.last() != screen) backStack.add(screen)
                }
                fun pop() {
                    if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                }

                // Handle system Back for any non-Home screen.
                BackHandler(enabled = backStack.size > 1) { pop() }

                when (current) {
                    Screen.HOME -> HomeScreen(
                        state = state,
                        events = viewModel.events,
                        onQueryChange = viewModel::setQuery,
                        onRefresh = viewModel::refresh,
                        onEnterSelection = viewModel::enterSelection,
                        onToggleSelect = viewModel::toggleSelect,
                        onSelectFilter = viewModel::selectByFilter,
                        onExcludeSelected = viewModel::excludeSelected,
                        onRemoveFromHibernated = viewModel::removeFromHibernated,
                        onExitSelection = viewModel::exitSelection,
                        onToggleManaged = viewModel::toggleManaged,
                        onToggleWhitelist = viewModel::toggleWhitelist,
                        onHibernate = viewModel::hibernateSelectedOrAll,
                        onOpenSettings = { navigate(Screen.SETTINGS) },
                        onOpenEngineSetup = { navigate(Screen.ENGINE) },
                    )

                    Screen.SETTINGS -> SettingsScreen(
                        state = state,
                        onBack = { pop() },
                        onToggleAuto = viewModel::setAutoHibernate,
                        onDelayChange = viewModel::setDelayMinutes,
                        onToggleShowSystem = viewModel::setShowSystem,
                        onToggleScreenOff = viewModel::setHibernateOnScreenOff,
                        onOpenExcluded = { navigate(Screen.EXCLUDED) },
                        onOpenEngineSetup = { navigate(Screen.ENGINE) },
                    )

                    Screen.ENGINE -> EngineSetupScreen(
                        state = state,
                        onBack = { pop() },
                        onRequestShizuku = viewModel::requestShizuku,
                    )

                    Screen.EXCLUDED -> ExcludedAppsScreen(
                        state = state,
                        onBack = { pop() },
                        onIncludeApp = viewModel::toggleWhitelist,
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
