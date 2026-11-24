package com.infinix.fixcharging.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.infinix.fixcharging.data.ChargingController
import com.infinix.fixcharging.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BypassTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var isBypassActive = false

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val telemetry = ChargingController.readTelemetry()
            isBypassActive = telemetry.isBypassActive
            updateTileState()
        }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            isBypassActive = !isBypassActive
            PreferencesManager(this@BypassTileService).isManualBypassEnabled = isBypassActive
            ChargingController.setBypassCharging(isBypassActive)
            updateTileState()
        }
    }

    private fun updateTileState() {
        qsTile?.let { tile ->
            tile.state = if (isBypassActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = "Bypass Charge"
            tile.subtitle = if (isBypassActive) "Active (Mains Only)" else "Off"
            tile.updateTile()
        }
    }
}
