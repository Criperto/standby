package com.criperto.standby.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.criperto.standby.StandByApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ChargingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_POWER_CONNECTED &&
            action != Intent.ACTION_POWER_DISCONNECTED &&
            action != Intent.ACTION_BOOT_COMPLETED
        ) {
            return
        }

        val app = context.applicationContext as? StandByApplication ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = runCatching { app.settingsRepository.settingsFlow.first() }.getOrNull()
                if (settings?.autoTriggerServiceEnabled == true) {
                    StandByMonitorService.syncState(context.applicationContext, enabled = true)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
