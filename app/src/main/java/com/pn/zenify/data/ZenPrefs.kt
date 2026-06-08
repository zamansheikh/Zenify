package com.pn.zenify.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zen_prefs")

/**
 * User settings + the managed/whitelist sets. Backed by DataStore so reads are
 * a cold [Flow] and writes are atomic.
 */
class ZenPrefs(private val context: Context) {

    private object Keys {
        val MANAGED = stringSetPreferencesKey("managed_packages")
        val WHITELIST = stringSetPreferencesKey("whitelisted_packages")
        val AUTO_HIBERNATE = booleanPreferencesKey("auto_hibernate")
        val DELAY_MINUTES = intPreferencesKey("hibernate_delay_minutes")
        val HIBERNATE_ON_SCREEN_OFF = booleanPreferencesKey("hibernate_on_screen_off")
        val SHOW_SYSTEM = booleanPreferencesKey("show_system_apps")
    }

    val managed: Flow<Set<String>> = context.dataStore.data.map { it[Keys.MANAGED] ?: emptySet() }
    val whitelist: Flow<Set<String>> = context.dataStore.data.map { it[Keys.WHITELIST] ?: emptySet() }
    val autoHibernate: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_HIBERNATE] ?: false }
    val delayMinutes: Flow<Int> = context.dataStore.data.map { it[Keys.DELAY_MINUTES] ?: 5 }
    val hibernateOnScreenOff: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.HIBERNATE_ON_SCREEN_OFF] ?: true }
    val showSystem: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHOW_SYSTEM] ?: false }

    suspend fun setManaged(pkg: String, managed: Boolean) = toggle(Keys.MANAGED, pkg, managed)
    suspend fun setWhitelisted(pkg: String, on: Boolean) = toggle(Keys.WHITELIST, pkg, on)

    suspend fun setAutoHibernate(on: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_HIBERNATE] = on }

    suspend fun setDelayMinutes(minutes: Int) =
        context.dataStore.edit { it[Keys.DELAY_MINUTES] = minutes }

    suspend fun setHibernateOnScreenOff(on: Boolean) =
        context.dataStore.edit { it[Keys.HIBERNATE_ON_SCREEN_OFF] = on }

    suspend fun setShowSystem(on: Boolean) =
        context.dataStore.edit { it[Keys.SHOW_SYSTEM] = on }

    private suspend fun toggle(key: Preferences.Key<Set<String>>, pkg: String, on: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.toMutableSet() ?: mutableSetOf()
            if (on) current.add(pkg) else current.remove(pkg)
            prefs[key] = current
        }
    }
}
