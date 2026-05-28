package com.longpress.app

import android.accessibilityservice.AccessibilityService
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

/**
 * Accessibility Service that performs continuous long-press gestures.
 *
 * Architecture:
 * - Receives broadcast commands from MainActivity / OverlayService.
 * - On START: reads touch position + durations from AppPreferences, then schedules
 *   repeating gesture dispatches via a Handler.
 * - On STOP: cancels the pending callbacks and resets state.
 * - Each gesture is a single stroke from (x,y) to (x,y) with a duration equal to
 *   the hold duration setting, which Android interprets as a long press.
 */
class LongPressAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LongPressService"

        const val ACTION_START = "com.longpress.app.ACTION_START"
        const val ACTION_STOP  = "com.longpress.app.ACTION_STOP"
        const val ACTION_UPDATE_POSITION = "com.longpress.app.ACTION_UPDATE_POSITION"

        /** Singleton reference so MainActivity can query whether the service is running. */
        @Volatile var instance: LongPressAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isPerforming = false

    // ------------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Service connected")

        val filter = IntentFilter().apply {
            addAction(ACTION_START)
            addAction(ACTION_STOP)
            addAction(ACTION_UPDATE_POSITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(commandReceiver, filter)
        }

        // Resume if the app was killed while running
        if (AppPreferences.isRunning(this)) {
            startGestureLoop()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
        stopGestureLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopGestureLoop()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        Log.d(TAG, "Service destroyed")
    }

    // ------------------------------------------------------------------ commands

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_START  -> startGestureLoop()
                ACTION_STOP   -> stopGestureLoop()
                ACTION_UPDATE_POSITION -> { /* position read fresh each cycle */ }
            }
        }
    }

    // ------------------------------------------------------------------ gesture loop

    private fun startGestureLoop() {
        if (isPerforming) return
        isPerforming = true
        AppPreferences.setRunning(this, true)
        Log.d(TAG, "Gesture loop started")
        scheduleNextGesture()
    }

    private fun stopGestureLoop() {
        isPerforming = false
        AppPreferences.setRunning(this, false)
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Gesture loop stopped")
    }

    private fun scheduleNextGesture() {
        if (!isPerforming) return
        performLongPressGesture()
    }

    private fun performLongPressGesture() {
        val holdMs   = AppPreferences.getHoldDurationMs(this).coerceAtLeast(50L)
        val pauseMs  = AppPreferences.getTapIntervalMs(this).coerceAtLeast(50L)
        val xPercent = AppPreferences.getTouchXPercent(this)
        val yPercent = AppPreferences.getTouchYPercent(this)

        val (screenW, screenH) = getScreenSize()
        val x = (screenW * xPercent / 100f)
        val y = (screenH * yPercent / 100f)

        Log.v(TAG, "Gesture at (%.1f, %.1f) hold=${holdMs}ms pause=${pauseMs}ms".format(x, y))

        val path = Path().apply { moveTo(x, y) }

        val stroke = GestureDescription.StrokeDescription(
            path,
            /* startTime= */ 0L,
            /* duration=  */ holdMs
        )

        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                if (isPerforming) {
                    handler.postDelayed({ scheduleNextGesture() }, pauseMs)
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "Gesture cancelled")
                if (isPerforming) {
                    // Brief back-off before retrying
                    handler.postDelayed({ scheduleNextGesture() }, 200L)
                }
            }
        }, handler)
    }

    // ------------------------------------------------------------------ helpers

    private fun getScreenSize(): Pair<Float, Float> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width().toFloat(), bounds.height().toFloat())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Pair(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
        }
    }
}
