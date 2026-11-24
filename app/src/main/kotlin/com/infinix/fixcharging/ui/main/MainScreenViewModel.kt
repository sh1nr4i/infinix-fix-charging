package com.infinix.fixcharging.ui.main

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.infinix.fixcharging.data.ChargingController
import com.infinix.fixcharging.data.PreferencesManager
import com.infinix.fixcharging.model.AppInfo
import com.infinix.fixcharging.model.AutoBypassSettings
import com.infinix.fixcharging.model.ChargingSpeed
import com.infinix.fixcharging.model.ChargingTelemetry
import com.infinix.fixcharging.service.ChargingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PreferencesManager(application)

    private val _telemetry = MutableStateFlow(ChargingTelemetry())
    val telemetry: StateFlow<ChargingTelemetry> = _telemetry.asStateFlow()

    private val _actionStatus = MutableStateFlow<String?>(null)
    val actionStatus: StateFlow<String?> = _actionStatus.asStateFlow()

    private val _autoBypassSettings = MutableStateFlow(
        AutoBypassSettings(
            isEnabled = prefs.isAutoBypassEnabled,
            selectedPackages = prefs.selectedBypassPackages,
            hasUsagePermission = checkUsageStatsPermission()
        )
    )
    val autoBypassSettings: StateFlow<AutoBypassSettings> = _autoBypassSettings.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _selectedPackages = MutableStateFlow<Set<String>>(prefs.selectedBypassPackages)
    val selectedPackages: StateFlow<Set<String>> = _selectedPackages.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private var isAppsLoaded = false

    private val _isNotificationEnabled = MutableStateFlow(prefs.isNotificationEnabled)
    val isNotificationEnabled: StateFlow<Boolean> = _isNotificationEnabled.asStateFlow()

    private val _isAutoBypass100Enabled = MutableStateFlow(prefs.isAutoBypass100Enabled)
    val isAutoBypass100Enabled: StateFlow<Boolean> = _isAutoBypass100Enabled.asStateFlow()

    private val _isManualBypassEnabled = MutableStateFlow(prefs.isManualBypassEnabled)
    val isManualBypassEnabled: StateFlow<Boolean> = _isManualBypassEnabled.asStateFlow()

    private var pollingJob: Job? = null

    init {
        ChargingController.syncFromPreferences(prefs)
        // Start foreground service if the user explicitly enabled notification or auto-bypass
        if (prefs.isNotificationEnabled || prefs.isAutoBypass100Enabled || (prefs.isAutoBypassEnabled && prefs.selectedBypassPackages.isNotEmpty())) {
            ChargingForegroundService.startService(application)
        }
    }

    fun startTelemetryPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                val data = ChargingController.readTelemetry()
                _telemetry.value = data

                // Automatically activate bypass if battery is 100% and auto-bypass 100% is enabled
                if (prefs.isAutoBypass100Enabled && data.isChargerConnected && data.batteryLevel >= 100 && !data.isBypassActive) {
                    ChargingController.setBypassCharging(true)
                    _telemetry.value = ChargingController.readTelemetry()
                }

                // If charger is disconnected, reset manual bypass if it was enabled
                if (!data.isChargerConnected && _isManualBypassEnabled.value) {
                    _isManualBypassEnabled.value = false
                    prefs.isManualBypassEnabled = false
                }

                delay(2000)
            }
        }
    }

    fun stopTelemetryPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun refreshUsagePermission() {
        val hasUsage = checkUsageStatsPermission()
        if (hasUsage != _autoBypassSettings.value.hasUsagePermission) {
            _autoBypassSettings.value = _autoBypassSettings.value.copy(hasUsagePermission = hasUsage)
        }
    }

    fun toggleManualBypass(enabled: Boolean) {
        prefs.isManualBypassEnabled = enabled
        _isManualBypassEnabled.value = enabled
        viewModelScope.launch {
            if (enabled) {
                val success = ChargingController.setBypassCharging(true)
                _actionStatus.value = if (success) {
                    "Manual Bypass Charging Enabled (0mA into battery)"
                } else {
                    "Failed to write bypass node (Kernel permission issue)"
                }
            } else {
                // If user turns manual bypass off, only disable hardware bypass
                // if Auto-Bypass 100% is NOT active
                val telem = ChargingController.readTelemetry()
                val isAuto100StillActive = (prefs.isAutoBypass100Enabled && telem.isChargerConnected && telem.batteryLevel >= 100)
                if (!isAuto100StillActive) {
                    ChargingController.setBypassCharging(false)
                    _actionStatus.value = "Manual Bypass Charging Disabled"
                } else {
                    _actionStatus.value = "Manual bypass turned off (Auto-Bypass 100% remains active)"
                }
            }
            _telemetry.value = ChargingController.readTelemetry()
        }
    }

    fun setSpeed(speed: ChargingSpeed) {
        prefs.isManualBypassEnabled = false
        _isManualBypassEnabled.value = false
        prefs.lastSpeedProfile = speed.name
        viewModelScope.launch {
            val success = ChargingController.setChargingSpeed(speed)
            _actionStatus.value = if (success) {
                "Switched to ${speed.label} mode"
            } else {
                "Failed to change speed profile"
            }
            _telemetry.value = ChargingController.readTelemetry()
        }
    }

    fun forceFastChargeReset() {
        prefs.isManualBypassEnabled = false
        _isManualBypassEnabled.value = false
        prefs.lastSpeedProfile = ChargingSpeed.FAST.name
        viewModelScope.launch {
            val success = ChargingController.forceFastChargeReset()
            _actionStatus.value = if (success) {
                "⚡ High Voltage & 68W SuperCharge Re-negotiated!"
            } else {
                "Failed to reset fast charge"
            }
            _telemetry.value = ChargingController.readTelemetry()
        }
    }

    fun toggleAutoBypass(enabled: Boolean) {
        prefs.isAutoBypassEnabled = enabled
        _autoBypassSettings.value = _autoBypassSettings.value.copy(isEnabled = enabled)
        if (enabled) {
            ChargingForegroundService.startService(getApplication())
            ChargingForegroundService.triggerBypassCheck(getApplication())
            _actionStatus.value = "🎮 Game Auto-Bypass Enabled"
        } else {
            ChargingForegroundService.triggerBypassCheck(getApplication())
            if (!prefs.isNotificationEnabled && !prefs.isAutoBypass100Enabled) {
                ChargingForegroundService.stopService(getApplication())
            }
            _actionStatus.value = "🎮 Game Auto-Bypass Disabled"
        }
    }

    fun toggleAutoBypass100(enabled: Boolean) {
        prefs.isAutoBypass100Enabled = enabled
        _isAutoBypass100Enabled.value = enabled
        viewModelScope.launch {
            if (enabled) {
                val telem = ChargingController.readTelemetry()
                if (telem.batteryLevel >= 100 && telem.isChargerConnected) {
                    ChargingController.setBypassCharging(true)
                    _actionStatus.value = "🛡️ Auto-Bypass 100% Active (Power routed to hardware)"
                }
                ChargingForegroundService.startService(getApplication())
                ChargingForegroundService.triggerBypassCheck(getApplication())
            } else {
                if (!prefs.isManualBypassEnabled && ChargingController.isBypassActive) {
                    ChargingController.setBypassCharging(false)
                    _actionStatus.value = "🛡️ Auto-Bypass 100% Disabled"
                }
                if (!prefs.isNotificationEnabled && !prefs.isAutoBypassEnabled) {
                    ChargingForegroundService.stopService(getApplication())
                } else {
                    ChargingForegroundService.triggerBypassCheck(getApplication())
                }
            }
            _telemetry.value = ChargingController.readTelemetry()
        }
    }

    fun toggleAppSelection(packageName: String) {
        val current = _selectedPackages.value.toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        prefs.selectedBypassPackages = current
        _selectedPackages.value = current
        _autoBypassSettings.value = _autoBypassSettings.value.copy(selectedPackages = current)
    }

    fun selectAllApps(packageNames: Collection<String>) {
        val current = _selectedPackages.value.toMutableSet()
        current.addAll(packageNames)
        prefs.selectedBypassPackages = current
        _selectedPackages.value = current
        _autoBypassSettings.value = _autoBypassSettings.value.copy(selectedPackages = current)
    }

    fun clearAllAppSelection() {
        val empty = emptySet<String>()
        prefs.selectedBypassPackages = empty
        _selectedPackages.value = empty
        _autoBypassSettings.value = _autoBypassSettings.value.copy(selectedPackages = empty)
    }

    fun toggleNotification(enabled: Boolean) {
        prefs.isNotificationEnabled = enabled
        _isNotificationEnabled.value = enabled
        if (enabled) {
            ChargingForegroundService.startService(getApplication())
            _actionStatus.value = "🔔 Quick Control Notification Enabled"
        } else {
            ChargingForegroundService.stopService(getApplication())
            _actionStatus.value = "🔕 Quick Control Notification Disabled"
        }
    }

    private fun loadSafeAppIcon(pm: PackageManager, resolveInfo: ResolveInfo): ImageBitmap? {
        return try {
            val drawable = resolveInfo.loadIcon(pm)
            val size = 96
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                val bmp = Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
                return bmp.asImageBitmap()
            }
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    fun loadInstalledApps(forceRefresh: Boolean = false) {
        if (!forceRefresh && isAppsLoaded && _installedApps.value.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingApps.value = true
            try {
                val pm = getApplication<Application>().packageManager
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
                val myPackage = getApplication<Application>().packageName

                // Phase 1: Fast metadata extraction without blocking on heavy bitmap conversions
                val baseApps = resolveInfos.mapNotNull { resolveInfo ->
                    val pkgName = resolveInfo.activityInfo.packageName
                    if (pkgName == myPackage) return@mapNotNull null

                    val appName = try {
                        resolveInfo.loadLabel(pm).toString()
                    } catch (_: Exception) {
                        pkgName
                    }

                    val appInfoObj = resolveInfo.activityInfo.applicationInfo
                    val isSystem = (appInfoObj.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appInfoObj.category == ApplicationInfo.CATEGORY_GAME ||
                        (appInfoObj.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                    } else {
                        (appInfoObj.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                    }

                    AppInfo(
                        packageName = pkgName,
                        appName = appName,
                        iconBitmap = null,
                        isSystemApp = isSystem,
                        isGame = isGame
                    )
                }.sortedWith(
                    compareByDescending<AppInfo> { it.isGame }
                        .thenBy { it.appName.lowercase() }
                )

                isAppsLoaded = true
                withContext(Dispatchers.Main) {
                    _installedApps.value = baseApps
                    _isLoadingApps.value = false
                }

                // Phase 2: Asynchronously populate sharp App Icons in background
                val resolveMap = resolveInfos.associateBy { it.activityInfo.packageName }
                val appsWithIcons = baseApps.map { app ->
                    val rInfo = resolveMap[app.packageName]
                    val icon = if (rInfo != null) loadSafeAppIcon(pm, rInfo) else null
                    app.copy(iconBitmap = icon)
                }
                withContext(Dispatchers.Main) {
                    _installedApps.value = appsWithIcons
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isLoadingApps.value = false
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        if (!prefs.isNotificationEnabled && !prefs.isAutoBypassEnabled && !prefs.isAutoBypass100Enabled) {
            ChargingForegroundService.stopService(getApplication())
        }
    }

    private fun checkUsageStatsPermission(): Boolean {
        val appOps = getApplication<Application>().getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            getApplication<Application>().packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun clearStatus() {
        _actionStatus.value = null
    }
}
