package com.pn.zenify

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.pn.zenify.core.HibernationTracker
import com.pn.zenify.data.AppRepository
import com.pn.zenify.data.ZenPrefs
import com.pn.zenify.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ZenifyApp : Application() {

    val prefs: ZenPrefs by lazy { ZenPrefs(this) }
    val repository: AppRepository by lazy { AppRepository(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        restoreHibernationState()
        ShizukuManager.init()
        createNotificationChannel()
    }

    /**
     * Load the persisted hibernation record into [HibernationTracker] before any
     * refresh runs, and re-persist on every change. The initial read is a tiny,
     * one-time blocking load so the first app list isn't classified with an empty
     * tracker (which would resurrect just-stopped apps as "running" after an
     * update).
     */
    private fun restoreHibernationState() {
        val initial = runCatching { runBlocking { prefs.loadHibernated() } }
            .getOrDefault(emptyMap())
        HibernationTracker.bind(initial) { snapshot ->
            appScope.launch { runCatching { prefs.saveHibernated(snapshot) } }
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_WATCHER,
            getString(R.string.notif_channel_watcher),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_watcher_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_WATCHER = "watcher"

        lateinit var instance: ZenifyApp
            private set
    }
}
