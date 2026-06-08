package com.pn.zenify.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pn.zenify.core.AccessibilityUtil
import com.pn.zenify.shizuku.ShizukuManager
import com.pn.zenify.ui.UiState

private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineSetupScreen(
    state: UiState,
    onBack: () -> Unit,
    onRequestShizuku: () -> Unit,
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hibernation engine") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Zenify needs one way to stop apps. Pick whichever you prefer — neither is " +
                    "required to browse, and you can switch any time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- Accessibility (no root, like Greenify) ----
            EngineCard(
                icon = Icons.Filled.Accessibility,
                title = "Accessibility (no root)",
                enabled = state.accessibilityEnabled,
                body = "Zenify opens each app's info screen and taps \"Force stop\" for you — " +
                    "just like Greenify's root-free mode. You'll briefly see the system screens " +
                    "while it works.",
            ) {
                FilledTonalButton(onClick = { AccessibilityUtil.openSettings(context) }) {
                    Text(if (state.accessibilityEnabled) "Manage" else "Enable accessibility")
                }
            }

            // ---- Shizuku (silent, instant) ----
            val shizukuEnabled = state.shizukuState == ShizukuManager.State.READY
            EngineCard(
                icon = Icons.Filled.Terminal,
                title = "Shizuku (silent & instant)",
                enabled = shizukuEnabled,
                body = "The fastest, invisible method. Activate Shizuku once via wireless " +
                    "debugging or a PC, then grant Zenify access. Required for background " +
                    "auto-hibernation.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "1.  Install the Shizuku app.\n" +
                            "2.  Start it via Wireless debugging (Android 11+) or from a PC:\n" +
                            "      adb shell sh /sdcard/Android/data/$SHIZUKU_PKG/start.sh\n" +
                            "3.  Return here and grant access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { openShizuku(context) }) { Text("Open / Install") }
                        if (state.shizukuState == ShizukuManager.State.PERMISSION_REQUIRED) {
                            Button(onClick = onRequestShizuku) { Text("Grant access") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineCard(
    icon: ImageVector,
    title: String,
    enabled: Boolean,
    body: String,
    action: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
                Icon(
                    imageVector = if (enabled) Icons.Filled.CheckCircle
                    else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (enabled) "Active" else "Inactive",
                    tint = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            action()
        }
    }
}

private fun openShizuku(context: android.content.Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
    if (launch != null) {
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PKG"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PKG")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(market) }
        .onFailure { runCatching { context.startActivity(web) } }
}
