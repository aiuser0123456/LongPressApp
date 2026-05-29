package com.longpress.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    companion object {
        private const val TAG              = "OverlayService"
        private const val CHANNEL_ID       = "overlay_channel"
        private const val NOTIFICATION_ID  = 1001
        const val ACTION_START_OVERLAY     = "com.longpress.app.START_OVERLAY"
        const val ACTION_STOP_OVERLAY      = "com.longpress.app.STOP_OVERLAY"
        const val ACTION_SET_ACTIVE        = "com.longpress.app.SET_ACTIVE"
        const val ACTION_BEGIN_GESTURES    = "com.longpress.app.BEGIN_GESTURES"
        const val EXTRA_IS_ACTIVE          = "is_active"
        const val EXTRA_COUNTDOWN_SECS     = "countdown_secs"

        @Volatile var isRunning = false
            private set
    }

    private var wm: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var screenW = 0; private var screenH = 0

    // ── target dot ───────────────────────────────────────────────────────────
    private var dotView: View? = null
    private lateinit var dotParams: WindowManager.LayoutParams
    private var dotX0=0; private var dotY0=0; private var dotTX=0f; private var dotTY=0f
    private var dotDragging = false

    // ── control panel ────────────────────────────────────────────────────────
    private var panelView: LinearLayout? = null
    private lateinit var panelParams: WindowManager.LayoutParams
    private var panelX0=0; private var panelY0=0; private var panelTX=0f; private var panelTY=0f
    private var panelDragging = false

    // ── mini restore dot (shown when panel is hidden) ────────────────────────
    private var miniView: View? = null
    private lateinit var miniParams: WindowManager.LayoutParams
    private var miniX0=0; private var miniY0=0; private var miniTX=0f; private var miniTY=0f
    private var miniDragging = false

    // ── panel child views ────────────────────────────────────────────────────
    private var tvStatus: TextView? = null
    private var tvCountdown: TextView? = null
    private var btnPlay: TextView? = null
    private var settingsPanel: LinearLayout? = null
    private var settingsVisible = false
    private var tvHoldVal: TextView? = null
    private var tvIntervalVal: TextView? = null
    private var tvDelayVal: TextView? = null

    private var gesturesActive = false
    private var countDownTimer: CountDownTimer? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        measureScreen()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        showDot()
        showPanel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_OVERLAY -> killEverything()
            ACTION_SET_ACTIVE   -> {
                gesturesActive = intent.getBooleanExtra(EXTRA_IS_ACTIVE, false)
                refreshPanel()
            }
        }
        // START_NOT_STICKY = service will NOT restart automatically after being killed
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        countDownTimer?.cancel()
        removeDot(); removePanel(); removeMini()
        Log.d(TAG, "OverlayService destroyed")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Target dot
    // ─────────────────────────────────────────────────────────────────────────

    private fun showDot() {
        val dp   = resources.displayMetrics.density
        val size = (60 * dp).toInt()
        dotView  = View(this).apply { background = dotBg(false) }

        val xPct = AppPreferences.getTouchXPercent(this)
        val yPct = AppPreferences.getTouchYPercent(this)
        val px = ((screenW * xPct / 100f) - size / 2f).toInt().coerceIn(0, maxOf(0, screenW - size))
        val py = ((screenH * yPct / 100f) - size / 2f).toInt().coerceIn(0, maxOf(0, screenH - size))

        dotParams = mkParams(size, size).apply { x = px; y = py }
        dotView!!.setOnTouchListener { v, e -> onDotTouch(v, e) }
        try { wm?.addView(dotView, dotParams) } catch (e: Exception) { Log.e(TAG, "dot: $e") }
    }

    private fun removeDot() {
        dotView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }; dotView = null
    }

    private fun onDotTouch(view: View, e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                dotX0 = dotParams.x; dotY0 = dotParams.y
                dotTX = e.rawX; dotTY = e.rawY; dotDragging = false; return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - dotTX).toInt(); val dy = (e.rawY - dotTY).toInt()
                if (!dotDragging && (Math.abs(dx) > 4 || Math.abs(dy) > 4)) dotDragging = true
                if (dotDragging) {
                    // Full screen range — no artificial cap
                    dotParams.x = (dotX0 + dx).coerceIn(0, maxOf(0, screenW - view.width))
                    dotParams.y = (dotY0 + dy).coerceIn(0, maxOf(0, screenH - view.height))
                    try { wm?.updateViewLayout(dotView, dotParams) } catch (_: Exception) {}
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dotDragging) {
                    val cx = dotParams.x + view.width / 2f
                    val cy = dotParams.y + view.height / 2f
                    AppPreferences.setTouchPosition(this,
                        (cx / screenW * 100f).coerceIn(0f, 100f),
                        (cy / screenH * 100f).coerceIn(0f, 100f))
                    sendBroadcast(Intent(MainActivity.ACTION_POSITION_CHANGED).apply { `package` = packageName })
                }
                dotDragging = false; return true
            }
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Control panel
    // ─────────────────────────────────────────────────────────────────────────

    private fun showPanel() {
        val dp     = resources.displayMetrics.density
        val panelW = (175 * dp).toInt()
        panelView  = buildPanel(dp)
        panelParams = mkParams(panelW, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = maxOf(0, screenW - panelW - (8 * dp).toInt())
            y = (screenH * 0.35f).toInt()
        }
        panelView!!.setOnTouchListener { _, e -> onPanelTouch(e) }
        try { wm?.addView(panelView, panelParams) } catch (e: Exception) { Log.e(TAG, "panel: $e") }
    }

    private fun buildPanel(dp: Float): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10*dp).toInt(), (8*dp).toInt(), (10*dp).toInt(), (10*dp).toInt())
            background = roundRect(Color.parseColor("#F2111111"), (16*dp).toInt())
        }

        // Drag handle bar
        root.addView(View(this).apply {
            background = roundRect(Color.parseColor("#555555"), (3*dp).toInt())
            layoutParams = lp((40*dp).toInt(), (4*dp).toInt(), Gravity.CENTER_HORIZONTAL).also {
                it.bottomMargin = (7*dp).toInt()
            }
        })

        // Title row  [LONG PRESS BOT]  [⚙]  [–]  [⊗]
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = (4*dp).toInt() }
        }
        val tvTitle = tv("LONG PRESS BOT", "#BB86FC", 9f, dp).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(tvTitle)

        // ⚙ settings toggle
        val btnGear = tv("⚙", "#888888", 15f, dp).apply {
            setPadding((5*dp).toInt(), (2*dp).toInt(), (5*dp).toInt(), (2*dp).toInt())
            setOnClickListener { toggleSettings() }
        }
        titleRow.addView(btnGear)

        // – hide (keeps service alive, shows mini restore dot)
        val btnHide = tv("–", "#888888", 18f, dp).apply {
            setPadding((5*dp).toInt(), (2*dp).toInt(), (5*dp).toInt(), (2*dp).toInt())
            setOnClickListener { hidePanel() }
        }
        titleRow.addView(btnHide)

        // ⊗ EXIT (kills everything — no more background service)
        val btnExit = tv("⊗", "#FF5555", 15f, dp).apply {
            setPadding((5*dp).toInt(), (2*dp).toInt(), (2*dp).toInt(), (2*dp).toInt())
            setOnClickListener { killEverything() }
        }
        titleRow.addView(btnExit)
        root.addView(titleRow)

        // Status
        tvStatus = tv("● IDLE", "#666666", 12f, dp).apply {
            gravity = Gravity.CENTER
            layoutParams = lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = (4*dp).toInt() }
        }
        root.addView(tvStatus)

        // Countdown digit
        tvCountdown = tv("", "#FF9800", 30f, dp).apply {
            gravity = Gravity.CENTER
            layoutParams = lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = (4*dp).toInt() }
        }
        root.addView(tvCountdown)

        // ── Inline settings panel (hidden by default) ──
        settingsPanel = buildSettingsPanel(dp)
        settingsPanel!!.visibility = View.GONE
        root.addView(settingsPanel)

        root.addView(divider(dp))

        // Button row: ▶ START | ■ STOP
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = (8*dp).toInt() }
        }

        btnPlay = tv("▶  START", "#FFFFFF", 12f, dp).apply {
            gravity = Gravity.CENTER
            background = roundRect(Color.parseColor("#4CAF50"), (10*dp).toInt())
            setPadding((6*dp).toInt(), (10*dp).toInt(), (6*dp).toInt(), (10*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginEnd = (5*dp).toInt() }
            setOnClickListener { onPlayTapped() }
        }
        btnRow.addView(btnPlay)

        val btnStop = tv("■  STOP", "#FFFFFF", 12f, dp).apply {
            gravity = Gravity.CENTER
            background = roundRect(Color.parseColor("#C62828"), (10*dp).toInt())
            setPadding((6*dp).toInt(), (10*dp).toInt(), (6*dp).toInt(), (10*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onStopTapped() }
        }
        btnRow.addView(btnStop)
        root.addView(btnRow)

        return root
    }

    private fun buildSettingsPanel(dp: Float): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = (8*dp).toInt(); it.bottomMargin = (4*dp).toInt()
            }
        }

        fun addSliderRow(labelFn: () -> String, max: Int, progress: Int,
                         tintHex: String, onChanged: (Int) -> Unit): TextView {
            val tvLabel = tv(labelFn(), "#AAAAAA", 10f, dp)
            panel.addView(tvLabel)
            panel.addView(SeekBar(this).apply {
                this.max = max; this.progress = progress
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(tintHex))
                thumbTintList    = android.content.res.ColorStateList.valueOf(Color.parseColor(tintHex))
                layoutParams = lp(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).also {
                    it.topMargin = (2*dp).toInt(); it.bottomMargin = (6*dp).toInt()
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser) { onChanged(p); tvLabel.text = labelFn() }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar) = Unit
                })
            })
            return tvLabel
        }

        panel.addView(tv("SETTINGS", "#444444", 9f, dp).apply {
            letterSpacing = 0.12f
            layoutParams = lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = (6*dp).toInt() }
        })

        tvHoldVal = addSliderRow(
            labelFn  = { "Hold: ${AppPreferences.getHoldDurationMs(this)}ms" },
            max      = 4500,
            progress = (AppPreferences.getHoldDurationMs(this) - 50).toInt().coerceIn(0, 4500),
            tintHex  = "#6200EE"
        ) { p -> AppPreferences.setHoldDurationMs(this, (p + 50).toLong()) }

        tvIntervalVal = addSliderRow(
            labelFn  = { "Interval: ${AppPreferences.getTapIntervalMs(this)}ms" },
            max      = 2950,
            progress = (AppPreferences.getTapIntervalMs(this) - 50).toInt().coerceIn(0, 2950),
            tintHex  = "#6200EE"
        ) { p -> AppPreferences.setTapIntervalMs(this, (p + 50).toLong()) }

        tvDelayVal = addSliderRow(
            labelFn  = { "Delay: ${AppPreferences.getCountdownSecs(this)}s" },
            max      = 10,
            progress = AppPreferences.getCountdownSecs(this),
            tintHex  = "#FF9800"
        ) { p -> AppPreferences.setCountdownSecs(this, p) }

        return panel
    }

    private fun toggleSettings() {
        settingsVisible = !settingsVisible
        settingsPanel?.visibility = if (settingsVisible) View.VISIBLE else View.GONE
        handler.post { try { wm?.updateViewLayout(panelView, panelParams) } catch (_: Exception) {} }
    }

    private fun removePanel() {
        panelView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        panelView = null
    }

    private fun refreshPanel() {
        handler.post {
            val dp = resources.displayMetrics.density
            if (gesturesActive) {
                tvStatus?.text = "● RUNNING"; tvStatus?.setTextColor(Color.parseColor("#4CAF50"))
                tvCountdown?.text = ""
                btnPlay?.background = roundRect(Color.parseColor("#2E7D32"), (10*dp).toInt())
                dotView?.background = dotBg(true)
            } else {
                tvStatus?.text = "● IDLE"; tvStatus?.setTextColor(Color.parseColor("#666666"))
                tvCountdown?.text = ""
                btnPlay?.background = roundRect(Color.parseColor("#4CAF50"), (10*dp).toInt())
                dotView?.background = dotBg(false)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hide / restore panel
    // ─────────────────────────────────────────────────────────────────────────

    private fun hidePanel() {
        panelView?.visibility = View.GONE
        showMini()
    }

    private fun showMini() {
        if (miniView != null) return
        val dp   = resources.displayMetrics.density
        val size = (40 * dp).toInt()

        miniView = View(this).apply {
            background = roundRect(Color.parseColor("#CC6200EE"), (20*dp).toInt())
        }
        miniParams = mkParams(size, size).apply {
            x = maxOf(0, panelParams.x)
            y = maxOf(0, panelParams.y)
        }
        miniView!!.setOnTouchListener { _, e -> onMiniTouch(e) }
        try { wm?.addView(miniView, miniParams) } catch (e: Exception) { Log.e(TAG, "mini: $e") }
    }

    private fun removeMini() {
        miniView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }; miniView = null
    }

    private fun onMiniTouch(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                miniX0 = miniParams.x; miniY0 = miniParams.y
                miniTX = e.rawX; miniTY = e.rawY; miniDragging = false; return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - miniTX).toInt(); val dy = (e.rawY - miniTY).toInt()
                if (!miniDragging && (Math.abs(dx) > 4 || Math.abs(dy) > 4)) miniDragging = true
                if (miniDragging) {
                    miniView?.let { v ->
                        miniParams.x = (miniX0 + dx).coerceIn(0, maxOf(0, screenW - v.width))
                        miniParams.y = (miniY0 + dy).coerceIn(0, maxOf(0, screenH - v.height))
                        try { wm?.updateViewLayout(miniView, miniParams) } catch (_: Exception) {}
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!miniDragging) {
                    // Tap (not drag) = restore the panel
                    removeMini()
                    // Reposition panel to where mini was
                    panelParams.x = miniParams.x
                    panelParams.y = miniParams.y
                    panelView?.visibility = View.VISIBLE
                    try { wm?.updateViewLayout(panelView, panelParams) } catch (_: Exception) {}
                }
                miniDragging = false; return true
            }
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Panel drag
    // ─────────────────────────────────────────────────────────────────────────

    private fun onPanelTouch(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                panelX0 = panelParams.x; panelY0 = panelParams.y
                panelTX = e.rawX; panelTY = e.rawY; panelDragging = false; return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - panelTX).toInt(); val dy = (e.rawY - panelTY).toInt()
                if (!panelDragging && (Math.abs(dx) > 6 || Math.abs(dy) > 6)) panelDragging = true
                if (panelDragging) {
                    panelView?.let { v ->
                        panelParams.x = (panelX0 + dx).coerceIn(0, maxOf(0, screenW - v.width))
                        panelParams.y = (panelY0 + dy).coerceIn(0, maxOf(0, screenH - v.height))
                        try { wm?.updateViewLayout(panelView, panelParams) } catch (_: Exception) {}
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> { panelDragging = false; return true }
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Button actions
    // ─────────────────────────────────────────────────────────────────────────

    private fun onPlayTapped() {
        if (gesturesActive) return
        startCountdown(AppPreferences.getCountdownSecs(this))
        sendBroadcast(Intent(MainActivity.ACTION_STATE_CHANGED).apply {
            `package` = packageName; putExtra("running", true)
        })
    }

    private fun onStopTapped() {
        countDownTimer?.cancel(); countDownTimer = null
        gesturesActive = false; AppPreferences.setRunning(this, false)
        sendBroadcast(Intent(LongPressAccessibilityService.ACTION_STOP).apply { `package` = packageName })
        sendBroadcast(Intent(MainActivity.ACTION_STATE_CHANGED).apply {
            `package` = packageName; putExtra("running", false)
        })
        refreshPanel()
    }

    private fun killEverything() {
        // 1. Stop gestures
        onStopTapped()
        // 2. Notify MainActivity
        sendBroadcast(Intent(MainActivity.ACTION_STATE_CHANGED).apply {
            `package` = packageName; putExtra("running", false); putExtra("killed", true)
        })
        // 3. Stop this service — removes all overlays via onDestroy
        stopSelf()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Countdown
    // ─────────────────────────────────────────────────────────────────────────

    private fun startCountdown(secs: Int) {
        countDownTimer?.cancel()
        if (secs <= 0) { launchGestures(); return }
        handler.post {
            tvStatus?.text = "● STARTING…"; tvStatus?.setTextColor(Color.parseColor("#FF9800"))
            tvCountdown?.text = secs.toString()
        }
        countDownTimer = object : CountDownTimer(secs * 1000L, 1000L) {
            override fun onTick(ms: Long) {
                handler.post { tvCountdown?.text = ((ms + 999) / 1000).toString() }
            }
            override fun onFinish() { handler.post { tvCountdown?.text = ""; launchGestures() } }
        }.start()
    }

    private fun launchGestures() {
        gesturesActive = true; AppPreferences.setRunning(this, true)
        refreshPanel()
        sendBroadcast(Intent(LongPressAccessibilityService.ACTION_START).apply { `package` = packageName })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun measureScreen() {
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = w.currentWindowMetrics.bounds; screenW = b.width(); screenH = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") w.defaultDisplay.getRealMetrics(m)
            screenW = m.widthPixels; screenH = m.heightPixels
        }
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun mkParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, overlayType(),
        // FLAG_NOT_FOCUSABLE: overlay never steals focus/input from games
        // FLAG_NOT_TOUCH_MODAL: touches outside overlay pass through to game
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun dotBg(active: Boolean) = GradientDrawable().apply {
        val dp = resources.displayMetrics.density
        shape = GradientDrawable.OVAL
        setColor(if (active) Color.parseColor("#994CAF50") else Color.parseColor("#996200EE"))
        setStroke((2*dp).toInt(), if (active) Color.parseColor("#81C784") else Color.parseColor("#BB86FC"))
    }

    private fun roundRect(color: Int, r: Int) =
        GradientDrawable().apply { setColor(color); cornerRadius = r.toFloat() }

    private fun tv(text: String, color: String, sp: Float, @Suppress("UNUSED_PARAMETER") dp: Float) =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(color))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }

    private fun divider(dp: Float) = View(this).apply {
        background = roundRect(Color.parseColor("#2A2A2A"), 0)
        layoutParams = lp(LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
            it.topMargin = (6*dp).toInt()
        }
    }

    private fun lp(w: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
                   h: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
                   gravity: Int = Gravity.NO_GRAVITY) =
        LinearLayout.LayoutParams(w, h).also {
            if (gravity != Gravity.NO_GRAVITY) it.gravity = gravity
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Long Press Bot",
                NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Long Press Bot Active")
            .setContentText("▶ START  |  ■ STOP  |  ⊗ EXIT to close completely")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi).setOngoing(true).build()
    }
}
