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
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_OVERLAY  = "com.longpress.app.START_OVERLAY"
        const val ACTION_STOP_OVERLAY   = "com.longpress.app.STOP_OVERLAY"
        const val ACTION_SET_ACTIVE     = "com.longpress.app.SET_ACTIVE"
        const val ACTION_BEGIN_GESTURES = "com.longpress.app.BEGIN_GESTURES"
        const val EXTRA_IS_ACTIVE       = "is_active"
        const val EXTRA_COUNTDOWN_SECS  = "countdown_secs"

        @Volatile var isRunning = false
            private set
    }

    private var wm: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())

    // Target dot
    private var dotView: View? = null
    private lateinit var dotParams: WindowManager.LayoutParams
    private var dotDragX0 = 0; private var dotDragY0 = 0
    private var dotTouchX0 = 0f; private var dotTouchY0 = 0f
    private var dotDragging = false

    // Control panel
    private var panelView: LinearLayout? = null
    private lateinit var panelParams: WindowManager.LayoutParams
    private var panelDragX0 = 0; private var panelDragY0 = 0
    private var panelTouchX0 = 0f; private var panelTouchY0 = 0f
    private var panelDragging = false

    // Panel child views
    private var tvStatus: TextView? = null
    private var tvCountdown: TextView? = null
    private var btnPlay: TextView? = null
    private var btnStop: TextView? = null

    private var screenW = 0; private var screenH = 0
    private var gesturesActive = false
    private var countDownTimer: CountDownTimer? = null

    // ── lifecycle ─────────────────────────────────────────────────────────────

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
        Log.d(TAG, "OverlayService created ${screenW}x${screenH}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_OVERLAY   -> stopSelf()
            ACTION_SET_ACTIVE     -> {
                gesturesActive = intent.getBooleanExtra(EXTRA_IS_ACTIVE, false)
                refreshPanel()
            }
            ACTION_BEGIN_GESTURES -> {
                val secs = intent.getIntExtra(EXTRA_COUNTDOWN_SECS, 3)
                startCountdown(secs)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        countDownTimer?.cancel()
        removeDot()
        removePanel()
        Log.d(TAG, "OverlayService destroyed")
    }

    // ── target dot ────────────────────────────────────────────────────────────

    private fun showDot() {
        val dp = resources.displayMetrics.density
        val size = (56 * dp).toInt()

        dotView = View(this).apply { background = dotDrawable(false) }

        val xPct = AppPreferences.getTouchXPercent(this)
        val yPct = AppPreferences.getTouchYPercent(this)
        val px = ((screenW * xPct / 100f) - size / 2f).toInt().coerceIn(0, screenW - size)
        val py = ((screenH * yPct / 100f) - size / 2f).toInt().coerceIn(0, screenH - size)

        dotParams = baseParams(size, size).apply { x = px; y = py }
        dotView!!.setOnTouchListener { v, e -> onDotTouch(v, e) }
        try { wm?.addView(dotView, dotParams) } catch (e: Exception) { Log.e(TAG, "dot: $e") }
    }

    private fun removeDot() {
        dotView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        dotView = null
    }

    private fun onDotTouch(view: View, e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                dotDragX0 = dotParams.x; dotDragY0 = dotParams.y
                dotTouchX0 = e.rawX;   dotTouchY0 = e.rawY
                dotDragging = false; return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - dotTouchX0).toInt()
                val dy = (e.rawY - dotTouchY0).toInt()
                if (!dotDragging && (Math.abs(dx) > 4 || Math.abs(dy) > 4)) dotDragging = true
                if (dotDragging) {
                    dotParams.x = (dotDragX0 + dx).coerceIn(0, screenW - view.width)
                    dotParams.y = (dotDragY0 + dy).coerceIn(0, screenH - view.height)
                    try { wm?.updateViewLayout(dotView, dotParams) } catch (_: Exception) {}
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dotDragging) saveDotPosition(view)
                dotDragging = false; return true
            }
        }
        return false
    }

    private fun saveDotPosition(view: View) {
        val cx = dotParams.x + view.width  / 2f
        val cy = dotParams.y + view.height / 2f
        val xPct = (cx / screenW * 100f).coerceIn(0f, 100f)
        val yPct = (cy / screenH * 100f).coerceIn(0f, 100f)
        AppPreferences.setTouchPosition(this, xPct, yPct)
        Log.d(TAG, "Saved dot %.1f%% %.1f%%".format(xPct, yPct))
        sendBroadcast(Intent(MainActivity.ACTION_POSITION_CHANGED).apply { `package` = packageName })
    }

    // ── control panel ─────────────────────────────────────────────────────────

    private fun showPanel() {
        val dp = resources.displayMetrics.density
        val panelW = (158 * dp).toInt()

        panelView = buildPanel(dp)
        panelParams = baseParams(panelW, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = (screenW - panelW - (12 * dp).toInt()).coerceAtLeast(0)
            y = (screenH * 0.38f).toInt()
        }

        panelView!!.setOnTouchListener { _, e -> onPanelTouch(e) }
        try { wm?.addView(panelView, panelParams) } catch (e: Exception) { Log.e(TAG, "panel: $e") }
    }

    private fun buildPanel(dp: Float): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10*dp).toInt(), (8*dp).toInt(), (10*dp).toInt(), (12*dp).toInt())
            background = roundRect(Color.parseColor("#EE111111"), (16*dp).toInt())
        }

        // Drag handle
        root.addView(View(this).apply {
            background = roundRect(Color.parseColor("#444444"), (3*dp).toInt())
            layoutParams = lp(w = (36*dp).toInt(), h = (4*dp).toInt(),
                gravity = Gravity.CENTER_HORIZONTAL, bottom = (8*dp).toInt())
        })

        // App title
        root.addView(tv("LONG PRESS BOT", "#BB86FC", 9f, dp,
            bottom = (6*dp).toInt(), gravity = Gravity.CENTER))

        // Status
        tvStatus = tv("● IDLE", "#666666", 12f, dp,
            bottom = (4*dp).toInt(), gravity = Gravity.CENTER)
        root.addView(tvStatus)

        // Countdown digit
        tvCountdown = tv("", "#FF9800", 26f, dp,
            bottom = (2*dp).toInt(), gravity = Gravity.CENTER)
        root.addView(tvCountdown)

        // Divider
        root.addView(View(this).apply {
            background = roundRect(Color.parseColor("#2A2A2A"), 0)
            layoutParams = lp(w = LinearLayout.LayoutParams.MATCH_PARENT,
                h = 1, top = (4*dp).toInt(), bottom = (10*dp).toInt())
        })

        // Button row
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(w = LinearLayout.LayoutParams.MATCH_PARENT,
                h = LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        btnPlay = tv("▶  START", "#FFFFFF", 11f, dp, gravity = Gravity.CENTER).apply {
            background = roundRect(Color.parseColor("#4CAF50"), (8*dp).toInt())
            setPadding((6*dp).toInt(), (9*dp).toInt(), (6*dp).toInt(), (9*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (5*dp).toInt()
            }
            setOnClickListener { onPlayTapped() }
        }
        row.addView(btnPlay)

        btnStop = tv("■  STOP", "#FFFFFF", 11f, dp, gravity = Gravity.CENTER).apply {
            background = roundRect(Color.parseColor("#C62828"), (8*dp).toInt())
            setPadding((6*dp).toInt(), (9*dp).toInt(), (6*dp).toInt(), (9*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onStopTapped() }
        }
        row.addView(btnStop)

        root.addView(row)
        return root
    }

    private fun removePanel() {
        panelView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        panelView = null; tvStatus = null; tvCountdown = null
        btnPlay = null; btnStop = null
    }

    private fun onPanelTouch(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                panelDragX0 = panelParams.x; panelDragY0 = panelParams.y
                panelTouchX0 = e.rawX;      panelTouchY0 = e.rawY
                panelDragging = false; return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - panelTouchX0).toInt()
                val dy = (e.rawY - panelTouchY0).toInt()
                if (!panelDragging && (Math.abs(dx) > 4 || Math.abs(dy) > 4)) panelDragging = true
                if (panelDragging) {
                    panelView?.let { v ->
                        panelParams.x = (panelDragX0 + dx).coerceIn(0, screenW - v.width)
                        panelParams.y = (panelDragY0 + dy).coerceIn(0, screenH - v.height)
                        try { wm?.updateViewLayout(panelView, panelParams) } catch (_: Exception) {}
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> { panelDragging = false; return true }
        }
        return false
    }

    private fun refreshPanel() {
        handler.post {
            val dp = resources.displayMetrics.density
            if (gesturesActive) {
                tvStatus?.text = "● RUNNING"
                tvStatus?.setTextColor(Color.parseColor("#4CAF50"))
                tvCountdown?.text = ""
                btnPlay?.background = roundRect(Color.parseColor("#2E7D32"), (8*dp).toInt())
                dotView?.background = dotDrawable(true)
            } else {
                tvStatus?.text = "● IDLE"
                tvStatus?.setTextColor(Color.parseColor("#666666"))
                tvCountdown?.text = ""
                btnPlay?.background = roundRect(Color.parseColor("#4CAF50"), (8*dp).toInt())
                dotView?.background = dotDrawable(false)
            }
        }
    }

    // ── panel button actions ──────────────────────────────────────────────────

    /** Called when user taps ▶ START on the floating panel — start countdown then gestures. */
    private fun onPlayTapped() {
        if (gesturesActive) return               // already running, ignore
        val secs = AppPreferences.getCountdownSecs(this)
        startCountdown(secs)
        // Sync MainActivity button state
        sendBroadcast(Intent(MainActivity.ACTION_STATE_CHANGED).apply {
            `package` = packageName
            putExtra("running", true)
        })
    }

    /** Called when user taps ■ STOP on the floating panel. Resets completely; ready to start again. */
    private fun onStopTapped() {
        countDownTimer?.cancel()
        countDownTimer = null
        gesturesActive = false
        AppPreferences.setRunning(this, false)
        // Stop the accessibility service gestures
        sendBroadcast(Intent(LongPressAccessibilityService.ACTION_STOP).apply { `package` = packageName })
        // Sync MainActivity
        sendBroadcast(Intent(MainActivity.ACTION_STATE_CHANGED).apply {
            `package` = packageName
            putExtra("running", false)
        })
        refreshPanel()
        Log.d(TAG, "Stopped by panel")
    }

    // ── countdown + gesture launch ────────────────────────────────────────────

    private fun startCountdown(secs: Int) {
        countDownTimer?.cancel()
        if (secs <= 0) {
            launchGestures(); return
        }
        handler.post {
            tvStatus?.text = "● STARTING…"
            tvStatus?.setTextColor(Color.parseColor("#FF9800"))
            tvCountdown?.text = secs.toString()
        }
        countDownTimer = object : CountDownTimer(secs * 1000L, 1000L) {
            override fun onTick(ms: Long) {
                val remaining = ((ms + 999) / 1000).toInt()
                handler.post { tvCountdown?.text = remaining.toString() }
            }
            override fun onFinish() {
                handler.post { tvCountdown?.text = ""; launchGestures() }
            }
        }.start()
    }

    private fun launchGestures() {
        gesturesActive = true
        AppPreferences.setRunning(this, true)
        refreshPanel()
        sendBroadcast(Intent(LongPressAccessibilityService.ACTION_START).apply { `package` = packageName })
        Log.d(TAG, "Gestures launched")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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

    private fun baseParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun dotDrawable(active: Boolean): GradientDrawable {
        val dp = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) Color.parseColor("#994CAF50") else Color.parseColor("#996200EE"))
            setStroke((2*dp).toInt(), if (active) Color.parseColor("#81C784") else Color.parseColor("#BB86FC"))
        }
    }

    private fun roundRect(color: Int, radiusPx: Int) =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusPx.toFloat() }

    private fun tv(text: String, color: String, sp: Float, dp: Float,
                   top: Int = 0, bottom: Int = 0, gravity: Int = Gravity.START): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(color))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            this.gravity = gravity
            layoutParams = lp(w = LinearLayout.LayoutParams.MATCH_PARENT,
                h = LinearLayout.LayoutParams.WRAP_CONTENT, top = top, bottom = bottom)
        }

    private fun lp(w: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
                   h: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
                   gravity: Int = Gravity.NO_GRAVITY,
                   top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).also {
            if (gravity != Gravity.NO_GRAVITY) it.gravity = gravity
            it.topMargin = top; it.bottomMargin = bottom
        }

    // ── notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_description)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi).setOngoing(true).build()
    }
}
