package com.pn.zenify.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pn.zenify.ZenifyApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Re-arms the watcher after a reboot if auto-hibernation was enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val app = context.applicationContext as ZenifyApp
        val auto = runBlocking { app.prefs.autoHibernate.first() }
        if (auto) {
            HibernationService.start(context)
        }
    }
}
