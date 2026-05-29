package com.longpress.app

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {
    private const val PREFS_NAME           = "long_press_prefs"
    private const val KEY_TOUCH_X_PERCENT  = "touch_x_percent"
    private const val KEY_TOUCH_Y_PERCENT  = "touch_y_percent"
    private const val KEY_HOLD_DURATION_MS = "hold_duration_ms"
    private const val KEY_TAP_INTERVAL_MS  = "tap_interval_ms"
    private const val KEY_IS_RUNNING       = "is_running"
    private const val KEY_COUNTDOWN_SECS   = "countdown_secs"

    const val DEFAULT_TOUCH_X_PERCENT  = 50f
    const val DEFAULT_TOUCH_Y_PERCENT  = 50f
    const val DEFAULT_HOLD_DURATION_MS = 500L
    const val DEFAULT_TAP_INTERVAL_MS  = 200L
    const val DEFAULT_COUNTDOWN_SECS   = 3

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTouchXPercent(context: Context) =
        prefs(context).getFloat(KEY_TOUCH_X_PERCENT, DEFAULT_TOUCH_X_PERCENT)
    fun getTouchYPercent(context: Context) =
        prefs(context).getFloat(KEY_TOUCH_Y_PERCENT, DEFAULT_TOUCH_Y_PERCENT)
    fun setTouchPosition(context: Context, xPct: Float, yPct: Float) {
        prefs(context).edit()
            .putFloat(KEY_TOUCH_X_PERCENT, xPct.coerceIn(0f, 100f))
            .putFloat(KEY_TOUCH_Y_PERCENT, yPct.coerceIn(0f, 100f))
            .apply()
    }

    fun getHoldDurationMs(context: Context) =
        prefs(context).getLong(KEY_HOLD_DURATION_MS, DEFAULT_HOLD_DURATION_MS)
    fun setHoldDurationMs(context: Context, ms: Long) =
        prefs(context).edit().putLong(KEY_HOLD_DURATION_MS, ms).apply()

    fun getTapIntervalMs(context: Context) =
        prefs(context).getLong(KEY_TAP_INTERVAL_MS, DEFAULT_TAP_INTERVAL_MS)
    fun setTapIntervalMs(context: Context, ms: Long) =
        prefs(context).edit().putLong(KEY_TAP_INTERVAL_MS, ms).apply()

    fun isRunning(context: Context) =
        prefs(context).getBoolean(KEY_IS_RUNNING, false)
    fun setRunning(context: Context, running: Boolean) =
        prefs(context).edit().putBoolean(KEY_IS_RUNNING, running).apply()

    fun getCountdownSecs(context: Context) =
        prefs(context).getInt(KEY_COUNTDOWN_SECS, DEFAULT_COUNTDOWN_SECS)
    fun setCountdownSecs(context: Context, secs: Int) =
        prefs(context).edit().putInt(KEY_COUNTDOWN_SECS, secs.coerceIn(0, 10)).apply()
}
