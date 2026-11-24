package com.infinix.fixcharging.data

import com.infinix.fixcharging.model.ChargingSpeed
import com.infinix.fixcharging.model.ChargingTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object ChargingController {
    // Exact Infinix X678B / MT6789 kernel sysfs paths
    private const val NODE_BYPASS = "/sys/devices/platform/charger/bypass_charger"
    private const val NODE_BYPASS_ENERGY = "/sys/devices/platform/charger/bypass_energy"
    private const val NODE_CMD_DISCHARGING = "/sys/devices/platform/charger/cmd_discharging"
    private const val NODE_AICHG_DISABLE = "/sys/devices/platform/charger/tran_aichg_disable_charger"
    private const val NODE_ADAPTER_CAPACITY = "/sys/devices/platform/charger/adapter_capacity"
    private const val NODE_HV_ENABLE = "/sys/devices/platform/charger/High_voltage_chg_enable"
    private const val NODE_ENABLE_SC = "/sys/devices/platform/charger/enable_sc"
    private const val NODE_SC_IBAT_LIMIT = "/sys/devices/platform/charger/sc_ibat_limit"
    private const val NODE_INPUT_CURRENT = "/sys/devices/platform/charger/input_current"
    private const val NODE_THERMAL_THROTTLE = "/sys/devices/platform/charger/Thermal_throttle"
    private const val NODE_FAST_INDICATOR = "/sys/devices/platform/charger/fast_chg_indicator"
    private const val NODE_PUMP_EXPRESS = "/sys/devices/platform/charger/Pump_Express"
    private const val NODE_SC_TUISOC = "/sys/devices/platform/charger/sc_tuisoc"
    private const val NODE_TRAN_PROC_LIMIT = "/proc/tran_chg_limit_current/limit_current"
    private const val NODE_CHIHIRO_LIMIT = "/proc/chihiro_charge_limit"

    // Transsion Proprietary Auto-Test / Game Mode Nodes
    private const val NODE_TRAN_GAME_MODE = "/sys/devices/platform/odm/odm:tran_auto_test/power_supply/tran-auto-test/game_mode"
    private const val NODE_TRAN_STOP_CHG = "/sys/devices/platform/odm/odm:tran_auto_test/power_supply/tran-auto-test/stop_chg"
    private const val NODE_TRAN_CHG_SPEED = "/sys/devices/platform/odm/odm:tran_auto_test/power_supply/tran-auto-test/chg_speed"
    private const val NODE_TRAN_QUICK_CHG_BACK = "/sys/devices/platform/odm/odm:tran_auto_test/power_supply/tran-auto-test/quick_chg_back"

    private const val PATH_BATT_CURRENT = "/sys/class/power_supply/battery/current_now"
    private const val PATH_BATT_VOLTAGE = "/sys/class/power_supply/battery/voltage_now"
    private const val PATH_BATT_CAPACITY = "/sys/class/power_supply/battery/capacity"
    private const val PATH_BATT_TEMP = "/sys/class/power_supply/battery/temp"
    private const val PATH_BATT_STATUS = "/sys/class/power_supply/battery/status"
    private const val PATH_CHARGER_ONLINE = "/sys/class/power_supply/charger/online"
    private const val PATH_CHARGER_UEVENT = "/sys/class/power_supply/charger/uevent"

    private var cachedRootAvailable: Boolean? = null
    private var canWriteDirectCached: Boolean? = null
    private var cachedChargerType: String? = null
    private var lastOnlineState: Boolean? = null
    private var telemetryTick: Long = 0
    var lastSelectedSpeed: ChargingSpeed = ChargingSpeed.FAST
        private set
    var isBypassActive: Boolean = false
        private set
    private var isInitializedFromPrefs: Boolean = false

    fun syncFromPreferences(prefs: PreferencesManager) {
        val savedSpeed = try {
            ChargingSpeed.valueOf(prefs.lastSpeedProfile)
        } catch (_: Exception) {
            ChargingSpeed.FAST
        }
        lastSelectedSpeed = savedSpeed
        isInitializedFromPrefs = true
    }

    suspend fun checkRoot(): Boolean = withContext(Dispatchers.IO) {
        if (cachedRootAvailable != null) return@withContext cachedRootAvailable!!
        val isRooted = executeShellCommand("id")?.contains("uid=0") == true
        cachedRootAvailable = isRooted
        isRooted
    }

    private fun executeShellCommand(cmd: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            process.waitFor()
            output.trim()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Writes to sysfs/procfs node. First checks existence, tries direct file write
     * (supported by Chihiro kernel 0666 permissions + SELinux bypass), and only falls
     * back to su if root is actually available.
     */
    fun writeNode(path: String, value: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false

        // 1. Direct writeText
        try {
            file.writeText(value)
            return true
        } catch (_: Exception) {}

        // 2. Direct FileOutputStream stream write
        try {
            file.outputStream().use { os ->
                os.write(value.toByteArray())
                os.flush()
            }
            return true
        } catch (_: Exception) {}

        // 3. Fallback to su ONLY if root is confirmed available (avoids costly process forks)
        if (cachedRootAvailable == true) {
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo $value > $path"))
                process.waitFor() == 0
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    suspend fun setBypassCharging(enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (enable) {
            writeNode(NODE_CHIHIRO_LIMIT, "99")
            writeNode(NODE_HV_ENABLE, "0")
            writeNode(NODE_BYPASS, "1")
            writeNode(NODE_AICHG_DISABLE, "1")
            writeNode(NODE_TRAN_GAME_MODE, "1")
            writeNode(NODE_TRAN_STOP_CHG, "1")
            isBypassActive = true
        } else {
            isBypassActive = false
            applySpeedSettings(lastSelectedSpeed, forceClearBypass = true)
        }
        true
    }

    private fun clearBypassNodes() {
        writeNode(NODE_BYPASS, "0")
        writeNode(NODE_CMD_DISCHARGING, "0")
        writeNode(NODE_AICHG_DISABLE, "0")
        writeNode(NODE_BYPASS_ENERGY, "100")
        writeNode(NODE_TRAN_GAME_MODE, "0")
        writeNode(NODE_TRAN_STOP_CHG, "0")
    }

    /**
     * Strictly enforces hardware charging power across all hardware controllers:
     * - Dedicated kernel limit node (/proc/chihiro_charge_limit)
     * - Transsion JEITA proc limit (/proc/tran_chg_limit_current/limit_current in mA, 0 = unlimited)
     * - High-voltage charge pump control (High_voltage_chg_enable: 0 for 5W/10W buck, 1 for 68W HV)
     */
    fun applySpeedSettings(
        speed: ChargingSpeed,
        forceClearBypass: Boolean = false,
        forceRenegotiate: Boolean = false
    ) {
        lastSelectedSpeed = speed
        val shouldClearBypass = forceClearBypass || isBypassActive ||
            readFileDirect(NODE_BYPASS)?.trim() == "1"
        // Always keep MediaTek SmartCharging (enable_sc) disabled so it never throttles at 80% SoC
        writeNode(NODE_ENABLE_SC, "0")
        when (speed) {
            ChargingSpeed.SLOW -> {
                writeNode(NODE_HV_ENABLE, "0")
                writeNode(NODE_TRAN_PROC_LIMIT, "1000")
                writeNode(NODE_CHIHIRO_LIMIT, "1000000")
                if (shouldClearBypass) clearBypassNodes()
            }
            ChargingSpeed.NORMAL -> {
                writeNode(NODE_HV_ENABLE, "0")
                writeNode(NODE_TRAN_PROC_LIMIT, "2000")
                writeNode(NODE_CHIHIRO_LIMIT, "2000000")
                if (shouldClearBypass) clearBypassNodes()
            }
            ChargingSpeed.FAST -> {
                if (shouldClearBypass) clearBypassNodes()
                writeNode(NODE_SC_IBAT_LIMIT, "0")
                writeNode(NODE_HV_ENABLE, "1")
                writeNode(NODE_THERMAL_THROTTLE, "0")
                writeNode(NODE_TRAN_PROC_LIMIT, "0")
                writeNode(NODE_CHIHIRO_LIMIT, if (forceRenegotiate) "68" else "0")
            }
        }
        isBypassActive = false
    }

    fun ensureSpeedSettingsApplied(speed: ChargingSpeed) {
        val expectedKernelLimit = when (speed) {
            ChargingSpeed.SLOW -> "1000000"
            ChargingSpeed.NORMAL -> "2000000"
            ChargingSpeed.FAST -> "0"
        }
        val expectedHv = if (speed == ChargingSpeed.FAST) "1" else "0"
        val currentLimit = readFileDirect(NODE_CHIHIRO_LIMIT)?.trim()
        val bypassNow = readFileDirect(NODE_BYPASS)?.trim() == "1"
        val scEnabled = readFileDirect(NODE_ENABLE_SC)?.trim() == "1"
        val currentHv = readFileDirect(NODE_HV_ENABLE)?.trim()
        if (currentLimit != expectedKernelLimit || bypassNow || scEnabled ||
            (currentHv != null && currentHv != expectedHv)) {
            applySpeedSettings(speed)
        }
    }

    suspend fun setChargingSpeed(speed: ChargingSpeed): Boolean = withContext(Dispatchers.IO) {
        isBypassActive = false
        applySpeedSettings(
            speed,
            forceClearBypass = true,
            forceRenegotiate = (speed == ChargingSpeed.FAST)
        )
        true
    }

    suspend fun forceFastChargeReset(): Boolean = withContext(Dispatchers.IO) {
        isBypassActive = false
        applySpeedSettings(ChargingSpeed.FAST, forceClearBypass = true, forceRenegotiate = true)
        true
    }

    suspend fun readTelemetry(): ChargingTelemetry = withContext(Dispatchers.IO) {
        val capacity = readFileDirect(PATH_BATT_CAPACITY)?.toIntOrNull() ?: 0
        val rawCurrent = readFileDirect(PATH_BATT_CURRENT)?.toIntOrNull() ?: 0
        val currentMa = rawCurrent / 1000 // Convert uA to mA
        val rawVoltage = readFileDirect(PATH_BATT_VOLTAGE)?.toIntOrNull() ?: 0
        val voltageMv = rawVoltage / 1000 // Convert uV to mV
        val rawTemp = readFileDirect(PATH_BATT_TEMP)?.toFloatOrNull() ?: 0f
        val tempCelsius = rawTemp / 10f // e.g. 360 -> 36.0°C

        val isOnline = (readFileDirect(PATH_CHARGER_ONLINE)?.toIntOrNull() ?: 0) == 1
        val rawStatus = readFileDirect(PATH_BATT_STATUS)?.trim() ?: if (isOnline) "Charging" else "Discharging"

        val bypassVal = readFileDirect(NODE_BYPASS)
        val chihiroLimitRaw = readFileDirect(NODE_CHIHIRO_LIMIT)?.trim()
        val bypassActive = if (bypassVal != null || chihiroLimitRaw != null) {
            bypassVal?.trim() == "1" || chihiroLimitRaw == "99"
        } else {
            isBypassActive
        }
        isBypassActive = bypassActive

        // Calculate power in Watts:
        // When discharging, Watts must have a minus sign (e.g. -1.8 W)
        // When bypass is active, power into battery is 0.0 W
        val rawWatts = (currentMa * (voltageMv / 1000f)) / 1000f
        val powerWatts = when {
            bypassActive -> 0.0f
            !isOnline || rawStatus.equals("Discharging", ignoreCase = true) || rawStatus.equals("Not charging", ignoreCase = true) -> {
                val absW = kotlin.math.abs(rawWatts)
                -kotlin.math.max(0.1f, absW)
            }
            rawStatus.equals("Charging", ignoreCase = true) -> {
                kotlin.math.abs(rawWatts)
            }
            else -> if (!isOnline) -kotlin.math.abs(rawWatts) else rawWatts
        }
        val roundedWatts = kotlin.math.round(powerWatts * 10) / 10f

        val chargerJustConnected = isOnline && (lastOnlineState != true)
        val chargerType = if (isOnline == lastOnlineState && cachedChargerType != null) {
            cachedChargerType!!
        } else {
            val t = extractChargerType()
            cachedChargerType = t
            lastOnlineState = isOnline
            t
        }

        // Can we write directly to sysfs/proc? In our patched kernel, yes!
        val canWriteDirect = canWriteDirectCached ?: run {
            val w = try {
                File(NODE_CHIHIRO_LIMIT).canWrite() || File(NODE_BYPASS).canWrite()
            } catch (_: Exception) {
                false
            }
            canWriteDirectCached = w
            w
        }

        telemetryTick++
        if (!isInitializedFromPrefs) {
            val kernelLimit = chihiroLimitRaw?.toIntOrNull()
                ?: readFileDirect(NODE_TRAN_PROC_LIMIT)?.toIntOrNull()
            if (kernelLimit != null && kernelLimit != 99) {
                lastSelectedSpeed = when {
                    kernelLimit == 1000000 || kernelLimit in 1..1200 -> ChargingSpeed.SLOW
                    kernelLimit == 2000000 || kernelLimit in 1201..2500 -> ChargingSpeed.NORMAL
                    else -> ChargingSpeed.FAST
                }
            }
            isInitializedFromPrefs = true
        }
        if (isOnline && !bypassActive) {
            if (chargerJustConnected) {
                applySpeedSettings(lastSelectedSpeed)
            } else if (telemetryTick % 4 == 0L) {
                ensureSpeedSettingsApplied(lastSelectedSpeed)
            }
        }

        ChargingTelemetry(
            batteryLevel = capacity,
            batteryTemp = tempCelsius,
            currentMa = currentMa,
            voltageMv = voltageMv,
            powerWatts = roundedWatts,
            chargerType = chargerType,
            isBypassActive = bypassActive,
            currentSpeed = lastSelectedSpeed,
            isRootAvailable = canWriteDirect || (cachedRootAvailable == true),
            isChargerConnected = isOnline,
            batteryStatus = rawStatus
        )
    }

    private fun extractChargerType(): String {
        return try {
            val uevent = readFileDirect(PATH_CHARGER_UEVENT) ?: return "Disconnected"
            when {
                uevent.contains("POWER_SUPPLY_USB_TYPE=PD") || uevent.contains("POWER_SUPPLY_TYPE=USB_PD") -> "USB Power Delivery (PD)"
                uevent.contains("POWER_SUPPLY_USB_TYPE=BrickID") -> "Infinix 68W/45W All-Round FastCharge"
                uevent.contains("POWER_SUPPLY_TYPE=USB_DCP") || uevent.contains("POWER_SUPPLY_USB_TYPE=[DCP]") -> "USB DCP (Standard 5V)"
                uevent.contains("POWER_SUPPLY_TYPE=USB_CDP") -> "USB CDP (1.5A)"
                uevent.contains("POWER_SUPPLY_TYPE=USB") -> "Standard USB Port"
                else -> "Connected"
            }
        } catch (_: Exception) {
            "Unknown"
        }
    }

    private fun readFileDirect(path: String): String? {
        return try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                file.readText().trim()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
