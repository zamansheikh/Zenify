package com.pn.zenify.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import com.pn.zenify.accessibility.ForceStopService

/** Helpers for checking and opening Zenify's accessibility service. */
object AccessibilityUtil {

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ForceStopService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val component = splitter.next()
            if (component.equals(expected, ignoreCase = true)) return true
            // Some ROMs store the short form without the leading package separator.
            if (component.endsWith(ForceStopService::class.java.name)) return true
        }
        return false
    }

    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
