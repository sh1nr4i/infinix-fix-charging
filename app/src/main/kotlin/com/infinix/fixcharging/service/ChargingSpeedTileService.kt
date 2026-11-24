package com.infinix.fixcharging.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.infinix.fixcharging.data.ChargingController
import com.infinix.fixcharging.data.PreferencesManager
import com.infinix.fixcharging.model.ChargingSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ChargingSpeedTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var currentSpeed: ChargingSpeed = ChargingSpeed.FAST

    override fun onStartListening() {
        super.onStartListening()
        val prefs = PreferencesManager(this)
        ChargingController.syncFromPreferences(prefs)
        scope.launch {
            val telemetry = ChargingController.readTelemetry()
            currentSpeed = telemetry.currentSpeed
            updateTileState()
        }
    }

    override fun onClick() {
        super.onClick()
        // Cycle between SLOW -> NORMAL -> FAST
        currentSpeed = when (currentSpeed) {
            ChargingSpeed.SLOW -> ChargingSpeed.NORMAL
            ChargingSpeed.NORMAL -> ChargingSpeed.FAST
            ChargingSpeed.FAST -> ChargingSpeed.SLOW
        }
        val prefs = PreferencesManager(this)
        prefs.isManualBypassEnabled = false
        prefs.lastSpeedProfile = currentSpeed.name
        scope.launch {
            ChargingController.setChargingSpeed(currentSpeed)
            updateTileState()
        }
    }

    private fun updateTileState() {
        qsTile?.let { tile ->
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Charge Speed"
            tile.subtitle = currentSpeed.label
            tile.updateTile()
        }
    }
}
