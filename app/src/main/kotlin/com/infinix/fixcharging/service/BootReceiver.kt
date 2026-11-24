package com.infinix.fixcharging.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.infinix.fixcharging.data.ChargingController
import com.infinix.fixcharging.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = PreferencesManager(context)
            ChargingController.syncFromPreferences(prefs)
            CoroutineScope(Dispatchers.IO).launch {
                if (!prefs.isManualBypassEnabled) {
                    ChargingController.applySpeedSettings(ChargingController.lastSelectedSpeed)
                }
            }
            if (prefs.isNotificationEnabled || prefs.isAutoBypass100Enabled || (prefs.isAutoBypassEnabled && prefs.selectedBypassPackages.isNotEmpty())) {
                ChargingForegroundService.startService(context)
            }
        }
    }
}
