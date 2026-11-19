package com.criperto.standby.standby

import android.view.Window
import android.view.WindowManager

object StandByBrightnessController {
    /**
     * Applies independent brightness strictly to the given StandBy/Dream window.
     * Passing `targetBrightness < 0f` restores system-controlled brightness (`BRIGHTNESS_OVERRIDE_NONE`).
     * Never writes to `Settings.System`.
     */
    fun applyToWindow(window: Window?, targetBrightness: Float) {
        val w = window ?: return
        val lp = w.attributes
        val desired = if (targetBrightness < 0f) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            targetBrightness.coerceIn(0.01f, 1.0f)
        }
        if (kotlin.math.abs(lp.screenBrightness - desired) > 0.002f) {
            lp.screenBrightness = desired
            w.attributes = lp
        }
    }
}
