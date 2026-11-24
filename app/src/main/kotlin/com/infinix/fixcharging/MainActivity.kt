package com.infinix.fixcharging

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.infinix.fixcharging.data.PreferencesManager
import com.infinix.fixcharging.service.ChargingForegroundService
import com.infinix.fixcharging.ui.theme.FixChargingTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    setContent {
      FixChargingTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }
  }

  override fun onStop() {
    super.onStop()
    val prefs = PreferencesManager(this)
    if (!prefs.isNotificationEnabled && !prefs.isAutoBypassEnabled && !prefs.isAutoBypass100Enabled) {
      ChargingForegroundService.stopService(this)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    val prefs = PreferencesManager(this)
    if (!prefs.isNotificationEnabled && !prefs.isAutoBypassEnabled && !prefs.isAutoBypass100Enabled) {
      ChargingForegroundService.stopService(this)
    }
  }
}
