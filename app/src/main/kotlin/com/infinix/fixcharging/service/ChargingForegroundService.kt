package com.infinix.fixcharging.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.infinix.fixcharging.MainActivity
import com.infinix.fixcharging.R
import com.infinix.fixcharging.data.ChargingController
import com.infinix.fixcharging.data.PreferencesManager
import com.infinix.fixcharging.model.ChargingSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChargingForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var prefs: PreferencesManager

    private var wasAutoBypassedGame = false
    private var wasAutoBypassed100 = false
    private var isChargerConnected = false
    private var currentBatteryLevel = 0

    private var gameMonitorJob: Job? = null
    private var lastEventTime = 0L
    @Volatile
    private var lastKnownForegroundPackage: String? = null

    private var lastNotifiedTitle: String? = null
    private var lastNotifiedContent: String? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    isChargerConnected = true
                    checkBypassStateOnBattery(currentBatteryLevel)
                    if (!prefs.isManualBypassEnabled && !wasAutoBypassed100 && !wasAutoBypassedGame) {
                        serviceScope.launch(Dispatchers.IO) {
                            ChargingController.applySpeedSettings(ChargingController.lastSelectedSpeed)
                        }
                    }
                    evaluateGameMonitoring()
                    updateNotification()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isChargerConnected = false
                    stopGameMonitoring()
                    wasAutoBypassedGame = false
                    wasAutoBypassed100 = false
                    prefs.isManualBypassEnabled = false
                    serviceScope.launch {
                        ChargingController.setBypassCharging(false)
                    }
                    if (!prefs.isNotificationEnabled) {
                        isRunning = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        updateNotification()
                    }
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    val pct = if (scale > 0) (level * 100) / scale else level

                    val connected = (plugged != 0)
                    val connectionChanged = (connected != isChargerConnected)
                    val levelChanged = (pct != currentBatteryLevel)
                    isChargerConnected = connected
                    currentBatteryLevel = pct

                    if (connected) {
                        checkBypassStateOnBattery(pct)
                        if (!prefs.isManualBypassEnabled && !wasAutoBypassed100 && !wasAutoBypassedGame && !ChargingController.isBypassActive) {
                            if (connectionChanged) {
                                serviceScope.launch(Dispatchers.IO) {
                                    ChargingController.applySpeedSettings(ChargingController.lastSelectedSpeed)
                                }
                            } else if (levelChanged) {
                                serviceScope.launch(Dispatchers.IO) {
                                    ChargingController.ensureSpeedSettingsApplied(ChargingController.lastSelectedSpeed)
                                }
                            }
                        }
                    }

                    if (connectionChanged) {
                        evaluateGameMonitoring()
                        updateNotification()
                    }
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "infinix_charging_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.infinix.fixcharging.action.START"
        const val ACTION_STOP = "com.infinix.fixcharging.action.STOP"
        const val ACTION_TOGGLE_BYPASS = "com.infinix.fixcharging.action.TOGGLE_BYPASS"
        const val ACTION_FORCE_FAST = "com.infinix.fixcharging.action.FORCE_FAST"
        const val ACTION_CYCLE_SPEED = "com.infinix.fixcharging.action.CYCLE_SPEED"
        const val ACTION_CHECK_BYPASS = "com.infinix.fixcharging.action.CHECK_BYPASS"

        var isRunning: Boolean = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, ChargingForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun triggerBypassCheck(context: Context) {
            val intent = Intent(context, ChargingForegroundService::class.java).apply {
                action = ACTION_CHECK_BYPASS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ChargingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(this)
        ChargingController.syncFromPreferences(prefs)
        createNotificationChannel()

        // Register power & battery receivers
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val stickyIntent = registerReceiver(batteryReceiver, filter)
        if (stickyIntent != null) {
            val level = stickyIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = stickyIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = stickyIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            currentBatteryLevel = if (scale > 0) (level * 100) / scale else level
            isChargerConnected = (plugged != 0)
            if (isChargerConnected) {
                checkBypassStateOnBattery(currentBatteryLevel)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CHECK_BYPASS -> {
                readBatteryAndCheckBypass()
            }
            ACTION_TOGGLE_BYPASS -> {
                serviceScope.launch {
                    val newManual = !prefs.isManualBypassEnabled
                    prefs.isManualBypassEnabled = newManual
                    ChargingController.setBypassCharging(newManual)
                    updateNotification()
                }
            }
            ACTION_FORCE_FAST -> {
                serviceScope.launch {
                    prefs.isManualBypassEnabled = false
                    prefs.lastSpeedProfile = ChargingSpeed.FAST.name
                    wasAutoBypassedGame = false
                    wasAutoBypassed100 = false
                    ChargingController.forceFastChargeReset()
                    updateNotification()
                }
            }
            ACTION_CYCLE_SPEED -> {
                serviceScope.launch {
                    val nextSpeed = when (ChargingController.lastSelectedSpeed) {
                        ChargingSpeed.SLOW -> ChargingSpeed.NORMAL
                        ChargingSpeed.NORMAL -> ChargingSpeed.FAST
                        ChargingSpeed.FAST -> ChargingSpeed.SLOW
                    }
                    prefs.isManualBypassEnabled = false
                    prefs.lastSpeedProfile = nextSpeed.name
                    ChargingController.setChargingSpeed(nextSpeed)
                    updateNotification()
                }
            }
        }

        if (!prefs.isNotificationEnabled && !prefs.isAutoBypassEnabled && !prefs.isAutoBypass100Enabled) {
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isRunning) {
            isRunning = true
            val initialNotification = buildNotification("⚡ Infinix Charge Controller", "Mode: Fast Charging (68W)")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        }

        readBatteryAndCheckBypass()
        return START_NOT_STICKY
    }

    private fun readBatteryAndCheckBypass() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: currentBatteryLevel
        if (level > 0) currentBatteryLevel = level

        val isPlugged = isBatteryPlugged()
        isChargerConnected = isPlugged

        checkBypassStateOnBattery(currentBatteryLevel)
        evaluateGameMonitoring()
        updateNotification()
    }

    private fun isBatteryPlugged(): Boolean {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = registerReceiver(null, filter)
            val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            plugged != 0
        } catch (_: Exception) {
            isChargerConnected
        }
    }

    private fun isBatteryStatusFull(): Boolean {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = registerReceiver(null, filter)
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) {
            false
        }
    }

    private fun checkBypassStateOnBattery(level: Int) {
        if (!isChargerConnected) {
            if (wasAutoBypassed100 || wasAutoBypassedGame) {
                wasAutoBypassed100 = false
                wasAutoBypassedGame = false
                serviceScope.launch {
                    ChargingController.setBypassCharging(false)
                    updateNotification()
                }
            }
            return
        }

        val isBatteryFull = level >= 100 || isBatteryStatusFull()
        if (prefs.isAutoBypass100Enabled && isBatteryFull) {
            if (!ChargingController.isBypassActive || !wasAutoBypassed100) {
                wasAutoBypassed100 = true
                serviceScope.launch {
                    ChargingController.setBypassCharging(true)
                    stopGameMonitoring()
                    updateNotification()
                }
            }
        } else if (wasAutoBypassed100 && level < 95) {
            wasAutoBypassed100 = false
            serviceScope.launch {
                if (!prefs.isManualBypassEnabled) {
                    ChargingController.setBypassCharging(false)
                }
                evaluateGameMonitoring()
                updateNotification()
            }
        } else if (!prefs.isAutoBypass100Enabled && wasAutoBypassed100) {
            wasAutoBypassed100 = false
            serviceScope.launch {
                if (!prefs.isManualBypassEnabled) {
                    ChargingController.setBypassCharging(false)
                }
                evaluateGameMonitoring()
                updateNotification()
            }
        }
    }

    private fun evaluateGameMonitoring() {
        if (isChargerConnected && prefs.isAutoBypassEnabled && prefs.selectedBypassPackages.isNotEmpty() && !wasAutoBypassed100) {
            startGameMonitoring()
        } else {
            stopGameMonitoring()
            if (wasAutoBypassedGame) {
                wasAutoBypassedGame = false
                serviceScope.launch {
                    if (!prefs.isManualBypassEnabled && !wasAutoBypassed100) {
                        ChargingController.setBypassCharging(false)
                    }
                    updateNotification()
                }
            }
        }
    }

    private fun startGameMonitoring() {
        if (gameMonitorJob?.isActive == true) return
        gameMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(2000)
                if (!isChargerConnected) {
                    if (wasAutoBypassedGame) {
                        wasAutoBypassedGame = false
                        ChargingController.setBypassCharging(false)
                        updateNotification()
                    }
                    break
                }
                if (wasAutoBypassed100) {
                    if (wasAutoBypassedGame) {
                        wasAutoBypassedGame = false
                    }
                    continue
                }
                checkForegroundGame(prefs.selectedBypassPackages)
            }
        }
    }

    private fun stopGameMonitoring() {
        gameMonitorJob?.cancel()
        gameMonitorJob = null
    }

    private suspend fun checkForegroundGame(selectedPackages: Set<String>) {
        if (selectedPackages.isEmpty()) {
            if (wasAutoBypassedGame) {
                wasAutoBypassedGame = false
                if (!prefs.isManualBypassEnabled && !wasAutoBypassed100) {
                    ChargingController.setBypassCharging(false)
                }
                updateNotification()
            }
            return
        }

        // 1. If screen is off, user is NOT playing games -> turn off bypass immediately
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenOn = powerManager?.isInteractive ?: true
        if (!isScreenOn) {
            lastKnownForegroundPackage = null
            if (wasAutoBypassedGame) {
                wasAutoBypassedGame = false
                if (!prefs.isManualBypassEnabled && !wasAutoBypassed100) {
                    ChargingController.setBypassCharging(false)
                }
                updateNotification()
            }
            return
        }

        // 2. Query UsageEvents to detect current active foreground app
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - 60000, now)

        var foregroundPackage: String? = null
        var sawLifecycleEvent = false
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    sawLifecycleEvent = true
                    foregroundPackage = event.packageName
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    sawLifecycleEvent = true
                    if (event.packageName == foregroundPackage ||
                        (foregroundPackage == null && event.packageName == lastKnownForegroundPackage)) {
                        foregroundPackage = null
                        lastKnownForegroundPackage = null
                    }
                }
            }
        }

        // 3. Fallback: check queryUsageStats, or preserve last known app if no lifecycle transitions occurred
        if (foregroundPackage == null) {
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60000, now)
            val mostRecent = stats?.maxByOrNull { it.lastTimeUsed }
            if (mostRecent != null && (now - mostRecent.lastTimeUsed) < 15000) {
                foregroundPackage = mostRecent.packageName
            } else if (!sawLifecycleEvent) {
                foregroundPackage = lastKnownForegroundPackage
            }
        }
        if (foregroundPackage != null) {
            lastKnownForegroundPackage = foregroundPackage
        }

        val isTarget = foregroundPackage != null && selectedPackages.contains(foregroundPackage)

        if (isTarget) {
            if (!ChargingController.isBypassActive) {
                wasAutoBypassedGame = true
                ChargingController.setBypassCharging(true)
                updateNotification()
            }
        } else {
            // User exited the game -> turn off bypass immediately
            if (wasAutoBypassedGame) {
                wasAutoBypassedGame = false
                if (!prefs.isManualBypassEnabled && !wasAutoBypassed100) {
                    ChargingController.setBypassCharging(false)
                }
                updateNotification()
            }
        }
    }

    private fun updateNotification() {
        val title: String
        val content: String

        val isBypass = ChargingController.isBypassActive
        if (isBypass) {
            if (wasAutoBypassed100) {
                title = "🛡️ Mode: Auto-Bypass 100%"
                content = "Battery full • Power routed directly to motherboard"
            } else {
                title = "🎮 Mode: Bypass Charging Active"
                content = "Power routed to motherboard • Device stays cool"
            }
        } else if (isChargerConnected) {
            title = when (ChargingController.lastSelectedSpeed) {
                ChargingSpeed.FAST -> "⚡ Mode: Fast Charge (68W)"
                ChargingSpeed.NORMAL -> "🔋 Mode: Normal Charge (10W)"
                ChargingSpeed.SLOW -> "🐢 Mode: Slow Charge (5W)"
            }
            content = "Charging active • Tap to open controls"
        } else {
            title = "🔌 Mode: Charger Disconnected"
            content = "Ready • Connect charger to activate features"
        }

        // Prevent redundant notification redraws (saves CPU & battery)
        if (title == lastNotifiedTitle && content == lastNotifiedContent) {
            return
        }
        lastNotifiedTitle = title
        lastNotifiedContent = content

        val notification = buildNotification(title, content)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 1: Toggle Bypass
        val bypassIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ChargingForegroundService::class.java).apply { action = ACTION_TOGGLE_BYPASS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 2: Force Fast Charge
        val fixFastIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ChargingForegroundService::class.java).apply { action = ACTION_FORCE_FAST },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 3: Cycle Speed Profile
        val cycleSpeedIntent = PendingIntent.getService(
            this, 3,
            Intent(this, ChargingForegroundService::class.java).apply { action = ACTION_CYCLE_SPEED },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "🎮 Bypass", bypassIntent)
            .addAction(0, "⚡ 68W", fixFastIntent)
            .addAction(0, "🔄 Speed", cycleSpeedIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Infinix Charging Mode & Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active charging mode and quick action controls"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!prefs.isNotificationEnabled) {
            stopGameMonitoring()
            if (wasAutoBypassedGame || wasAutoBypassed100) {
                wasAutoBypassedGame = false
                wasAutoBypassed100 = false
                serviceScope.launch {
                    ChargingController.setBypassCharging(false)
                }
            }
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopGameMonitoring()
        if (wasAutoBypassedGame || wasAutoBypassed100) {
            wasAutoBypassedGame = false
            wasAutoBypassed100 = false
            if (!prefs.isManualBypassEnabled) {
                serviceScope.launch {
                    ChargingController.setBypassCharging(false)
                }
            }
        }
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
