package com.criperto.standby.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.criperto.standby.StandByApplication
import com.criperto.standby.StandByTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StandByActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MANUAL_LAUNCH = "extra_manual_launch"
        const val ACTION_CLOSE_STANDBY = "com.criperto.standby.ACTION_CLOSE_STANDBY"

        private val _isCurrentlyVisible = MutableStateFlow(false)
        val isCurrentlyVisible: StateFlow<Boolean> = _isCurrentlyVisible.asStateFlow()

        fun createIntent(context: Context, manualLaunch: Boolean): Intent {
            return Intent(context, StandByActivity::class.java).apply {
                putExtra(EXTRA_MANUAL_LAUNCH, manualLaunch)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private var manualLaunch: Boolean = false
    private var receiverRegistered = false

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CLOSE_STANDBY && !manualLaunch) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manualLaunch = intent?.getBooleanExtra(EXTRA_MANUAL_LAUNCH, false) ?: false

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        configureImmersiveMode()

        val app = application as StandByApplication
        lifecycleScope.launch {
            app.settingsRepository.settingsFlow.collect { settings ->
                applyOrientationMode(settings.orientationMode)
            }
        }

        ContextCompat.registerReceiver(
            this,
            closeReceiver,
            IntentFilter(ACTION_CLOSE_STANDBY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true

        setContent {
            StandByTheme(darkTheme = true) {
                StandByHostScreen(
                    app = app,
                    window = window,
                    manualLaunch = manualLaunch,
                    onExitStandBy = {
                        StandByMonitorService.notifyManualDismiss(manualLaunch)
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        manualLaunch = intent.getBooleanExtra(EXTRA_MANUAL_LAUNCH, false)
    }

    override fun onResume() {
        super.onResume()
        _isCurrentlyVisible.value = true
        configureImmersiveMode()
    }

    override fun onPause() {
        super.onPause()
        _isCurrentlyVisible.value = false
    }

    override fun onDestroy() {
        _isCurrentlyVisible.value = false
        if (receiverRegistered) {
            runCatching { unregisterReceiver(closeReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun configureImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun applyOrientationMode(mode: String) {
        val desired = when (mode) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "reverse_landscape" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            "auto" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (requestedOrientation != desired) {
            requestedOrientation = desired
        }
    }
}
