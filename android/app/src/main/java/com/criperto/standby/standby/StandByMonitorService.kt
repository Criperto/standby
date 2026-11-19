package com.criperto.standby.standby

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.criperto.standby.MainActivity
import com.criperto.standby.R
import com.criperto.standby.StandByApplication
import com.criperto.standby.core.AppJson
import com.criperto.standby.core.StandByDecisionDto
import com.criperto.standby.core.TriggerSnapshotDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import uniffi.standby_core.evaluateStandbyRulesJson

class StandByMonitorService : Service() {
    companion object {
        private const val CHANNEL_ID = "standby_monitor_channel"
        private const val NOTIF_ID = 2042

        @Volatile
        private var dismissedDuringActiveSession: Boolean = false

        fun notifyManualDismiss(wasManualLaunch: Boolean) {
            if (!wasManualLaunch) {
                dismissedDuringActiveSession = true
            }
        }

        fun syncState(context: Context, enabled: Boolean) {
            val intent = Intent(context, StandByMonitorService::class.java)
            if (enabled) {
                runCatching { context.startForegroundService(intent) }
            } else {
                runCatching { context.stopService(intent) }
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private var prevDarkRoom = false
    private var prevShouldBeActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundSafely()
        val app = application as StandByApplication
        app.signalProvider.setSensorsActive(true)
        startMonitoring(app)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()
        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        monitorJob = null
        val app = application as? StandByApplication
        app?.signalProvider?.setSensorsActive(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startMonitoring(app: StandByApplication) {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            combine(app.settingsRepository.settingsFlow, app.signalProvider.signals) { s, sig -> s to sig }
                .collect { (settings, sig) ->
                    if (!settings.autoTriggerServiceEnabled) {
                        stopSelf()
                        return@collect
                    }
                    val rulesJson = runCatching {
                        AppJson.encodeToString(settings.toTriggerRulesDto())
                    }.getOrNull() ?: return@collect

                    val snapshot = TriggerSnapshotDto(
                        isCharging = sig.isCharging,
                        chargeSource = sig.chargeSource,
                        isLandscape = sig.isLandscape,
                        nowEpochMs = sig.nowEpochMs,
                        localMinuteOfDay = sig.localMinuteOfDay,
                        localWeekdayMon0 = sig.localWeekdayMon0,
                        localYyyyMmDd = sig.localYyyyMmDd,
                        ambientLux = sig.ambientLux,
                        faceDetected = null,
                        lastPresenceEpochMs = sig.nowEpochMs,
                        hubOpen = false,
                        manualLaunch = false,
                        prevDarkRoom = prevDarkRoom,
                    )
                    val snapJson = runCatching { AppJson.encodeToString(snapshot) }.getOrNull()
                        ?: return@collect

                    val decision = runCatching {
                        AppJson.decodeFromString<StandByDecisionDto>(
                            evaluateStandbyRulesJson(rulesJson, snapJson)
                        )
                    }.getOrNull() ?: return@collect

                    prevDarkRoom = decision.isDarkRoom

                    if (!decision.shouldBeActive) {
                        dismissedDuringActiveSession = false
                        if (prevShouldBeActive && StandByActivity.isCurrentlyVisible.value) {
                            sendBroadcast(
                                Intent(StandByActivity.ACTION_CLOSE_STANDBY).apply {
                                    setPackage(packageName)
                                }
                            )
                        }
                    } else if (!prevShouldBeActive &&
                        !dismissedDuringActiveSession &&
                        !StandByActivity.isCurrentlyVisible.value &&
                        Settings.canDrawOverlays(this@StandByMonitorService)
                    ) {
                        runCatching {
                            val launchIntent = StandByActivity.createIntent(
                                this@StandByMonitorService,
                                manualLaunch = false,
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(launchIntent)
                        }
                    }

                    prevShouldBeActive = decision.shouldBeActive
                }
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.monitor_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun startForegroundSafely() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openStandby = PendingIntent.getActivity(
            this,
            1,
            StandByActivity.createIntent(this, manualLaunch = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openSettings = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("StandBy activo")
            .setContentText("Esperando carga y orientación configuradas")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openStandby)
            .addAction(0, "Ajustes", openSettings)
            .build()
    }
}
