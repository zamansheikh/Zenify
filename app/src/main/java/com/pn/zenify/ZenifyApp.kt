package com.pn.zenify

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.pn.zenify.data.AppRepository
import com.pn.zenify.data.ZenPrefs
import com.pn.zenify.shizuku.ShizukuManager

class ZenifyApp : Application() {

    val prefs: ZenPrefs by lazy { ZenPrefs(this) }
    val repository: AppRepository by lazy { AppRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ShizukuManager.init()
        createNotificationChannel()
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
