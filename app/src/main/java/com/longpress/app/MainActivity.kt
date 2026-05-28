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
        private const val REQ_OVERLAY = 1001
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
                LongPressAccessibilityService.instance != null
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
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun requestOverlayPermission() {
        startActivityForResult(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            REQ_OVERLAY
        )
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Find \"Long Press Bot\" and enable it", Toast.LENGTH_LONG).show()
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
        binding.seekHoldDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val ms = (progress + 50).toLong()
                updateHoldLabel(ms)
                AppPreferences.setHoldDurationMs(this@MainActivity, ms)
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })

        val savedPause = AppPreferences.getTapIntervalMs(this)
        binding.seekTapInterval.progress = (savedPause - 50).toInt().coerceIn(0, 2950)
        updateIntervalLabel(savedPause)
        binding.seekTapInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val ms = (progress + 50).toLong()
                updateIntervalLabel(ms)
                AppPreferences.setTapIntervalMs(this@MainActivity, ms)
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })

        val savedCountdown = AppPreferences.getCountdownSecs(this)
        binding.seekCountdown.progress = savedCountdown.coerceIn(0, 10)
        updateCountdownLabel(savedCountdown)
        binding.seekCountdown.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                updateCountdownLabel(progress)
                AppPreferences.setCountdownSecs(this@MainActivity, progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
    }

    private fun updateHoldLabel(ms: Long)       { binding.tvHoldDuration.text = "Hold Duration: $ms ms" }
    private fun updateIntervalLabel(ms: Long)   { binding.tvTapInterval.text  = "Repeat Interval: $ms ms" }
    private fun updateCountdownLabel(secs: Int) {
        binding.tvCountdown.text =
            if (secs == 0) "Start Delay: OFF (instant)" else "Start Delay: ${secs}s"
    }

    // ── buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnGrantOverlay.setOnClickListener { requestOverlayPermission() }
        binding.btnEnableAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.btnToggle.setOnClickListener { onToggle() }
    }

    private fun onToggle() {
        if (!hasOverlayPermission()) {
            Toast.makeText(this, "Please grant overlay permission first", Toast.LENGTH_SHORT).show()
            requestOverlayPermission(); return
        }
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "Please enable the Accessibility Service first", Toast.LENGTH_SHORT).show()
            openAccessibilitySettings(); return
        }
        if (isRunning) stopSession() else startSession()
    }

    private fun startSession() {
        // Just launch the overlay (target dot + control panel) and get out of the way.
        // The user taps ▶ on the floating panel whenever they are ready — even minutes later.
        val overlayIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START_OVERLAY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(overlayIntent)
        else
            startService(overlayIntent)

        // Minimize immediately so the user can open their game
        moveTaskToBack(true)
    }

    private fun stopSession() {
        isRunning = false
        AppPreferences.setRunning(this, false)
        sendBroadcast(Intent(LongPressAccessibilityService.ACTION_STOP).apply { `package` = packageName })
        stopService(Intent(this, OverlayService::class.java))
        updateUI()
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private fun updateUI() {
        val overlayOk       = hasOverlayPermission()
        val accessibilityOk = isAccessibilityEnabled()

        binding.tvOverlayStatus.text = if (overlayOk) "✓ Overlay Permission: Granted"
                                       else           "⚠ Overlay Permission: Not Granted"
        binding.tvOverlayStatus.setTextColor(
            if (overlayOk) getColor(R.color.teal_200) else getColor(android.R.color.holo_orange_light))

        binding.tvAccessibilityStatus.text = if (accessibilityOk) "✓ Accessibility Service: Enabled"
                                             else                  "⚠ Accessibility Service: Disabled"
        binding.tvAccessibilityStatus.setTextColor(
            if (accessibilityOk) getColor(R.color.teal_200) else getColor(android.R.color.holo_orange_light))

        val xPct = AppPreferences.getTouchXPercent(this)
        val yPct = AppPreferences.getTouchYPercent(this)
        binding.tvTouchPosition.text = "Touch: X %.1f%% / Y %.1f%%".format(xPct, yPct)

        if (isRunning) {
            binding.btnToggle.text = "STOP"
            binding.btnToggle.backgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.red_500))
            binding.tvStatus.text = "● Status: Running"
            binding.tvStatus.setTextColor(getColor(R.color.green_500))
        } else {
            binding.btnToggle.text = "SHOW OVERLAY"
            binding.btnToggle.backgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.purple_500))
            binding.tvStatus.text = "● Status: Idle"
            binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
        }

        binding.btnToggle.isEnabled = overlayOk && accessibilityOk || isRunning
        binding.btnGrantOverlay.isEnabled = !overlayOk
        binding.btnEnableAccessibility.isEnabled = !accessibilityOk
    }

    // ── receivers ─────────────────────────────────────────────────────────────

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_POSITION_CHANGED -> handler.post { updateUI() }
                ACTION_STATE_CHANGED -> {
                    isRunning = intent.getBooleanExtra("running", false)
                    handler.post { updateUI() }
                }
            }
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(ACTION_POSITION_CHANGED)
            addAction(ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(broadcastReceiver, filter)
        }
    }
}
