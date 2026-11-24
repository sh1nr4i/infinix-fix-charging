package com.infinix.fixcharging.model

enum class ChargingSpeed(val label: String, val powerDescription: String, val currentLimitMa: Int) {
    SLOW("Slow", "5V / 1A (~5W) • Ultra Cool", 1000),
    NORMAL("Normal", "5V / 2A (~10W) • Balanced", 2000),
    FAST("Fast 68W", "Up to 11V / 6.2A (~68W Max) • Full Speed", 6800)
}

data class ChargingTelemetry(
    val batteryLevel: Int = 0,
    val batteryTemp: Float = 0.0f,
    val currentMa: Int = 0,
    val voltageMv: Int = 0,
    val powerWatts: Float = 0.0f,
    val chargerType: String = "Unknown",
    val isBypassActive: Boolean = false,
    val currentSpeed: ChargingSpeed = ChargingSpeed.FAST,
    val isRootAvailable: Boolean = false,
    val isChargerConnected: Boolean = false,
    val batteryStatus: String = "Discharging"
)
