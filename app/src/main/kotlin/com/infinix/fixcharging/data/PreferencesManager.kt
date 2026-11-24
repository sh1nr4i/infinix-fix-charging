package com.infinix.fixcharging.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("infinix_charging_prefs", Context.MODE_PRIVATE)

    init {
        // Reset legacy defaults: do NOT run in background unless user explicitly enables
        if (!prefs.contains("prefs_v2_initialized")) {
            prefs.edit()
                .putBoolean("prefs_v2_initialized", true)
                .putBoolean("notification_enabled", false)
                .putBoolean("auto_bypass_enabled", false)
                .putBoolean("auto_bypass_100_enabled", false)
                .putBoolean("manual_bypass_enabled", false)
                .apply()
        }
    }

    var isManualBypassEnabled: Boolean
        get() = prefs.getBoolean("manual_bypass_enabled", false)
        set(value) = prefs.edit().putBoolean("manual_bypass_enabled", value).apply()

    var isNotificationEnabled: Boolean
        get() = prefs.getBoolean("notification_enabled", false)
        set(value) = prefs.edit().putBoolean("notification_enabled", value).apply()

    var isAutoBypassEnabled: Boolean
        get() = prefs.getBoolean("auto_bypass_enabled", false)
        set(value) = prefs.edit().putBoolean("auto_bypass_enabled", value).apply()

    var isAutoBypass100Enabled: Boolean
        get() = prefs.getBoolean("auto_bypass_100_enabled", false)
        set(value) = prefs.edit().putBoolean("auto_bypass_100_enabled", value).apply()

    var selectedBypassPackages: Set<String>
        get() = prefs.getStringSet("selected_bypass_pkgs", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("selected_bypass_pkgs", value).apply()

    var lastSpeedProfile: String
        get() = prefs.getString("last_speed_profile", "FAST") ?: "FAST"
        set(value) = prefs.edit().putString("last_speed_profile", value).apply()
}
