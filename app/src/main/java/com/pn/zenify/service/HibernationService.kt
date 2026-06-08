package com.pn.zenify.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pn.zenify.R
import com.pn.zenify.ZenifyApp
import com.pn.zenify.core.Hibernator
import com.pn.zenify.core.HibernationEngine
import com.pn.zenify.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground watcher. Runs a hibernation pass on a timer and immediately when
 * the screen turns off (Greenify's signature behaviour). Persists across
 * reboots via [BootReceiver].
 */
class HibernationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var watchedCount = 0
    @Volatile private var lastHibernated = 0

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                scope.launch {
                    val app = applicationContext as ZenifyApp
                    if (app.prefs.hibernateOnScreenOff.first()) {
                        runPass(immediate = true)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RUN_NOW) {
            scope.launch { runPass(immediate = true) }
        }
        return START_STICKY
    }

    private fun startLoop() {
        scope.launch {
            val app = applicationContext as ZenifyApp
            while (isActive) {
                if (app.prefs.autoHibernate.first()) {
                    runPass(immediate = false)
                }
                val minutes = app.prefs.delayMinutes.first().coerceAtLeast(1)
                delay(minutes * 60_000L)
            }
        }
    }

    private suspend fun runPass(immediate: Boolean) {
        val app = applicationContext as ZenifyApp
        // Background auto-hibernation only runs silently via Shizuku. The
        // accessibility path drives visible system UI, which would be hostile
        // to fire unprompted, so we skip passes when Shizuku isn't ready.
        if (HibernationEngine.method(app) != HibernationEngine.Method.SHIZUKU) {
            watchedCount = 0
            lastHibernated = 0
            updateNotification()
            return
        }
        val managed = app.prefs.managed.first()
        val whitelist = app.prefs.whitelist.first()
        val showSystem = app.prefs.showSystem.first()
        val delayMin = app.prefs.delayMinutes.first()

        val apps = app.repository.loadApps(managed, whitelist, showSystem)
        watchedCount = apps.count { it.managed && !it.whitelisted }

        val idleThreshold = if (immediate) 0L else delayMin * 60_000L
        val eligible = Hibernator.eligible(apps, idleThreshold, System.currentTimeMillis())
        if (eligible.isNotEmpty()) {
            val result = Hibernator.hibernate(eligible.map { it.packageName })
            lastHibernated = result.succeeded.size
        } else {
            lastHibernated = 0
        }
        updateNotification()
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, ZenifyApp.CHANNEL_WATCHER)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_watching_title))
            .setContentText(getString(R.string.notif_watching_text, watchedCount, lastHibernated))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 1001
        const val ACTION_RUN_NOW = "com.pn.zenify.action.RUN_NOW"

        fun start(context: Context) {
            val intent = Intent(context, HibernationService::class.java)
            context.startForegroundService(intent)
        }

        fun runNow(context: Context) {
            val intent = Intent(context, HibernationService::class.java)
                .setAction(ACTION_RUN_NOW)
            context.startForegroundService(intent)
        }
    }
}
