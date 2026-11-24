package com.infinix.fixcharging.model

import androidx.compose.ui.graphics.ImageBitmap

data class AppInfo(
    val packageName: String,
    val appName: String,
    val iconBitmap: ImageBitmap? = null,
    val isSystemApp: Boolean = false,
    val isGame: Boolean = false
)

data class AutoBypassSettings(
    val isEnabled: Boolean = false,
    val selectedPackages: Set<String> = emptySet(),
    val currentForegroundApp: String? = null,
    val isBypassAutoEngaged: Boolean = false,
    val hasUsagePermission: Boolean = false
)
