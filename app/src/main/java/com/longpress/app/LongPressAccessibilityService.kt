package com.longpress.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class LongPressAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LongPressService"
        const val ACTION_START           = "com.longpress.app.ACTION_START"
        const val ACTION_STOP            = "com.longpress.app.ACTION_STOP"
        const val ACTION_UPDATE_POSITION = "com.longpress.app.ACTION_UPDATE_POSITION"

        @Volatile var instance: LongPressAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isPerforming = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // KEY FIX: override service info to use FEEDBACK_GENERIC (not spoken),
        // and remove FLAG_REQUEST_FILTER_KEY_EVENTS which causes input to lock
        // in games and triggers TalkBack/screen readers.
        val info = AccessibilityServiceInfo().apply {
            eventTypes          = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags               = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        serviceInfo = info

        val filter = IntentFilter().apply {
            addAction(ACTION_START); addAction(ACTION_STOP); addAction(ACTION_UPDATE_POSITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(commandReceiver, filter)
        }

        // Never auto-resume — user must tap ▶ explicitly each time
        AppPreferences.setRunning(this, false)
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { stopGestureLoop(); Log.d(TAG, "Interrupted") }
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopGestureLoop()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_START -> startGestureLoop()
                ACTION_STOP  -> stopGestureLoop()
            }
        }
    }

    private fun startGestureLoop() {
        if (isPerforming) return
        isPerforming = true
        AppPreferences.setRunning(this, true)
        performGesture()
        Log.d(TAG, "Gesture loop started")
    }

    private fun stopGestureLoop() {
        isPerforming = false
        AppPreferences.setRunning(this, false)
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Gesture loop stopped")
    }

    private fun performGesture() {
        if (!isPerforming) return
        val holdMs   = AppPreferences.getHoldDurationMs(this).coerceIn(50L, 9999L)
        val pauseMs  = AppPreferences.getTapIntervalMs(this).coerceIn(50L, 9999L)
        val xPct     = AppPreferences.getTouchXPercent(this)
        val yPct     = AppPreferences.getTouchYPercent(this)
        val (sw, sh) = getScreenSize()
        val x = (sw * xPct / 100f).coerceIn(1f, sw - 1f)
        val y = (sh * yPct / 100f).coerceIn(1f, sh - 1f)

        // lineTo same point = stationary press, prevents any camera drift in games
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, holdMs))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) {
                if (isPerforming) handler.postDelayed({ performGesture() }, pauseMs)
            }
            override fun onCancelled(g: GestureDescription) {
                Log.w(TAG, "Gesture cancelled")
                if (isPerforming) handler.postDelayed({ performGesture() }, 300L)
            }
        }, handler)
    }

    private fun getScreenSize(): Pair<Float, Float> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width().toFloat(), b.height().toFloat())
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels.toFloat(), m.heightPixels.toFloat())
        }
    }
}
