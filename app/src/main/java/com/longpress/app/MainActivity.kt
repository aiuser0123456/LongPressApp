package com.longpress.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.longpress.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_OVERLAY     = 1001
        const val ACTION_POSITION_CHANGED = "com.longpress.app.POSITION_CHANGED"
        const val ACTION_STATE_CHANGED    = "com.longpress.app.STATE_CHANGED"
    }

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSliders()
        setupButtons()
        registerReceivers()
    }

    override fun onResume() {
        super.onResume()
        isRunning = AppPreferences.isRunning(this) &&
                LongPressAccessibilityService.instance != null &&
                OverlayService.isRunning
        if (!isRunning) AppPreferences.setRunning(this, false)
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(broadcastReceiver) } catch (_: Exception) {}
    }

    // ── permissions ───────────────────────────────────────────────────────────

    private fun hasOverlayPermission() = Settings.canDrawOverlays(this)

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) updateUI()
    }

    // ── sliders ───────────────────────────────────────────────────────────────

    private fun setupSliders() {
        val savedHold = AppPreferences.getHoldDurationMs(this)
        binding.seekHoldDuration.progress = (savedHold - 50).toInt().coerceIn(0, 4500)
        updateHoldLabel(savedHold)
        binding.seekHoldDuration.setOnSeekBarChangeListener(sl { p ->
            val ms = (p + 50).toLong(); updateHoldLabel(ms)
            AppPreferences.setHoldDurationMs(this, ms)
        })

        val savedPause = AppPreferences.getTapIntervalMs(this)
        binding.seekTapInterval.progress = (savedPause - 50).toInt().coerceIn(0, 2950)
        updateIntervalLabel(savedPause)
        binding.seekTapInterval.setOnSeekBarChangeListener(sl { p ->
            val ms = (p + 50).toLong(); updateIntervalLabel(ms)
            AppPreferences.setTapIntervalMs(this, ms)
        })

        val savedCD = AppPreferences.getCountdownSecs(this)
        binding.seekCountdown.progress = savedCD.coerceIn(0, 10)
        updateCountdownLabel(savedCD)
        binding.seekCountdown.setOnSeekBarChangeListener(sl { p ->
            updateCountdownLabel(p); AppPreferences.setCountdownSecs(this, p)
        })
    }

    private fun sl(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
        override fun onStartTrackingTouch(sb: SeekBar) = Unit
        override fun onStopTrackingTouch(sb: SeekBar) = Unit
    }

    private fun updateHoldLabel(ms: Long)       { binding.tvHoldDuration.text = "Hold Duration: $ms ms" }
    private fun updateIntervalLabel(ms: Long)   { binding.tvTapInterval.text  = "Repeat Interval: $ms ms" }
    private fun updateCountdownLabel(secs: Int) {
        binding.tvCountdown.text = if (secs == 0) "Start Delay: OFF" else "Start Delay: ${secs}s"
    }

    // ── buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnGrantOverlay.setOnClickListener {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY)
        }
        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find \"Long Press Bot\" and enable it", Toast.LENGTH_LONG).show()
        }
        binding.btnToggle.setOnClickListener { onToggle() }
    }

    private fun onToggle() {
        if (!hasOverlayPermission()) {
            Toast.makeText(this, "Please grant overlay permission first", Toast.LENGTH_SHORT).show()
            startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")), REQ_OVERLAY); return
        }
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "Please enable the Accessibility Service first", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        if (OverlayService.isRunning) {
            moveTaskToBack(true)   // overlay already up — just go back to it
        } else {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START_OVERLAY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            moveTaskToBack(true)
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private fun updateUI() {
        val overlayOk = hasOverlayPermission()
        val accessOk  = isAccessibilityEnabled()

        binding.tvOverlayStatus.text = if (overlayOk) "✓ Overlay Permission: Granted"
                                       else "⚠ Overlay Permission: Not Granted"
        binding.tvOverlayStatus.setTextColor(
            if (overlayOk) getColor(R.color.teal_200) else getColor(android.R.color.holo_orange_light))

        binding.tvAccessibilityStatus.text = if (accessOk) "✓ Accessibility Service: Enabled"
                                             else "⚠ Accessibility Service: Disabled"
        binding.tvAccessibilityStatus.setTextColor(
            if (accessOk) getColor(R.color.teal_200) else getColor(android.R.color.holo_orange_light))

        binding.tvTouchPosition.text = "Touch: X %.1f%% / Y %.1f%%"
            .format(AppPreferences.getTouchXPercent(this), AppPreferences.getTouchYPercent(this))

        val overlayUp = OverlayService.isRunning
        binding.btnToggle.text = if (overlayUp) "GO TO OVERLAY" else "SHOW OVERLAY"
        binding.btnToggle.backgroundTintList =
            android.content.res.ColorStateList.valueOf(getColor(R.color.purple_500))
        binding.tvStatus.text = if (isRunning) "● Gestures Running" else
                                if (overlayUp) "● Overlay Active"   else "● Idle"
        binding.tvStatus.setTextColor(when {
            isRunning -> getColor(R.color.green_500)
            overlayUp -> getColor(R.color.teal_200)
            else      -> getColor(android.R.color.darker_gray)
        })

        binding.btnToggle.isEnabled = overlayOk && accessOk
        binding.btnGrantOverlay.isEnabled = !overlayOk
        binding.btnEnableAccessibility.isEnabled = !accessOk
    }

    // ── receivers ─────────────────────────────────────────────────────────────

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_POSITION_CHANGED -> handler.post { updateUI() }
                ACTION_STATE_CHANGED -> {
                    if (intent.getBooleanExtra("killed", false)) {
                        isRunning = false
                        AppPreferences.setRunning(this@MainActivity, false)
                    } else {
                        isRunning = intent.getBooleanExtra("running", false)
                    }
                    handler.post { updateUI() }
                }
            }
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(ACTION_POSITION_CHANGED); addAction(ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(broadcastReceiver, filter)
        }
    }
}
