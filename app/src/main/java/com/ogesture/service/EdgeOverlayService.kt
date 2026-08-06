package com.ogesture.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ogesture.MainActivity
import com.ogesture.R
import com.ogesture.data.GESTURE_ZONES
import com.ogesture.data.KNOWN_FILTERING_PACKAGES
import com.ogesture.data.SettingsRepository
import com.ogesture.data.ZoneConfig
import com.ogesture.data.ZoneId
import com.ogesture.gesture.SwipeDetector
import com.ogesture.gesture.TouchSample
import com.ogesture.ui.overlay.BackIndicator
import com.ogesture.ui.overlay.HomeIndicator
import com.ogesture.ui.overlay.OverlayIndicator
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class EdgeOverlayService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private lateinit var repo: SettingsRepository
    private val activeViews = mutableMapOf<ZoneId, View>()
    private val indicators = mutableMapOf<ZoneId, OverlayIndicator>()
    private var attached = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var replaying = false

    // True while the foreground app is on the user's excluded list: zones stay untouchable
    // so every touch reaches the app natively, at the cost of gestures in that app.
    @Volatile
    private var passThrough = false

    /** Display geometry the attached zones were laid out for. Null while nothing is attached. */
    private var lastGeometry: ScreenGeometry? = null

    // Rotation is applied to the display after the configuration change lands, so listen for
    // the display change itself rather than racing it: onDisplayChanged fires once the new
    // size is in effect. Configuration changes cover the rest (display-size/density settings,
    // multi-window, folding); both funnel into the same idempotent check.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) rebuildIfGeometryChanged()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        repo = SettingsRepository.get(this)
        startInForeground()
        isRunning = true
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, mainHandler)

        lifecycleScope.launch {
            repo.masterEnabled.distinctUntilChanged().collect { enabled ->
                if (!enabled) {
                    detachAll()
                    stopSelf()
                    return@collect
                }
                if (!Settings.canDrawOverlays(this@EdgeOverlayService)) {
                    Log.w(TAG, "Overlay permission missing; stopping service")
                    detachAll()
                    stopSelf()
                    return@collect
                }
                rebuild(GESTURE_ZONES)
            }
        }

        lifecycleScope.launch {
            combine(
                EdgeGestureAccessibilityService.foregroundPackage,
                repo.excludedApps,
            ) { pkg, excluded ->
                pkg != null && pkg in excluded && pkg in KNOWN_FILTERING_PACKAGES
            }
                .distinctUntilChanged()
                .collect { excluded ->
                    passThrough = excluded
                    applyZoneInteractivity()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        try {
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(displayListener)
        } catch (_: Throwable) { /* never registered */ }
        detachAll()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuildIfGeometryChanged()
    }

    /**
     * Zone windows are sized in pixels from the display's dimensions, and the system keeps
     * those pixel sizes across a rotation: a side zone measured as 90% of portrait height
     * would run off the bottom of a landscape screen, and the bottom zone would span less
     * than half its width. Re-lay them out whenever the geometry they were computed from
     * changes. Configuration changes that don't affect it (locale, theme, font scale) fall
     * out here, so this stays cheap to call from every signal.
     */
    private fun rebuildIfGeometryChanged() {
        if (activeViews.isEmpty()) return
        if (currentGeometry() == lastGeometry) return
        rebuild(GESTURE_ZONES)
    }

    /**
     * Full display size, density, and nav-bar insets the zone layout depends on. The insets
     * are part of the comparison because a 90°→270° flip keeps the same width and height
     * while the 3-button bar jumps to the opposite side — the zones must re-lay out for
     * that even though the display size alone looks unchanged.
     */
    private data class ScreenGeometry(
        val width: Int,
        val height: Int,
        val density: Float,
        val navLeft: Int,
        val navRight: Int,
        val navBottom: Int,
    )

    private fun currentGeometry(): ScreenGeometry {
        val density = resources.displayMetrics.density
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val nav = metrics.windowInsets.getInsets(WindowInsets.Type.navigationBars())
            ScreenGeometry(
                metrics.bounds.width(), metrics.bounds.height(), density,
                nav.left, nav.right, nav.bottom,
            )
        } else {
            @Suppress("DEPRECATION")
            val metrics = resources.displayMetrics
            ScreenGeometry(metrics.widthPixels, metrics.heightPixels, density, 0, 0, 0)
        }
    }

    private fun rebuild(zones: List<ZoneConfig>) {
        detachAll()
        // One snapshot for the whole pass, so every zone agrees on the screen it is sizing to
        // even if a rotation lands mid-rebuild; the listener re-runs us if it changed again.
        val geometry = currentGeometry()
        lastGeometry = geometry
        for (zone in zones) {
            val view = View(this).apply {
                // DEBUG_SHOW_ZONES tints the touch areas so they can be seen while testing.
                setBackgroundColor(if (DEBUG_SHOW_ZONES) ZONE_DEBUG_COLOR else android.graphics.Color.TRANSPARENT)
            }
            val minDistanceDp = if (zone.id == ZoneId.BOTTOM) BOTTOM_MIN_DISTANCE_DP else SIDE_MIN_DISTANCE_DP
            val armDistancePx = minDistanceDp * resources.displayMetrics.density
            val indicator: OverlayIndicator
            val feedback: SwipeDetector.Feedback?
            when (zone.id) {
                ZoneId.LEFT_EDGE, ZoneId.RIGHT_EDGE -> {
                    val ind = BackIndicator(
                        context = this,
                        windowManager = windowManager,
                        fromLeftEdge = zone.id == ZoneId.LEFT_EDGE,
                        armDistancePx = armDistancePx,
                        edgeOffsetPx = if (zone.id == ZoneId.LEFT_EDGE) geometry.navLeft else geometry.navRight,
                    )
                    indicator = ind
                    feedback = object : SwipeDetector.Feedback {
                        override fun onStart(rawX: Float, rawY: Float) = ind.onGestureStart(rawY)
                        override fun onProgress(distancePx: Float, rawX: Float, rawY: Float) =
                            ind.onGestureProgress(distancePx, rawY)
                        override fun onArmed() {
                            ind.onArmed()
                            hapticTick()
                        }
                        override fun onEnd(fired: Boolean) = ind.onGestureEnd(fired)
                    }
                }
                ZoneId.BOTTOM -> {
                    val ind = HomeIndicator(
                        context = this,
                        windowManager = windowManager,
                    )
                    indicator = ind
                    // The bar is static; it only lifts slightly while a bottom gesture is
                    // in progress, then settles back flush with the edge.
                    feedback = object : SwipeDetector.Feedback {
                        override fun onStart(rawX: Float, rawY: Float) = ind.onGestureStart()
                        override fun onProgress(distancePx: Float, rawX: Float, rawY: Float) = Unit
                        override fun onArmed() = Unit
                        override fun onEnd(fired: Boolean) = ind.onGestureEnd()
                    }
                }
            }
            view.setOnTouchListener(
                SwipeDetector(
                    context = this,
                    direction = zone.swipeDirection,
                    onShortSwipe = { onZoneTriggered(zone, long = false) },
                    onLongSwipe = if (zone.longAction != null) {
                        { onZoneTriggered(zone, long = true) }
                    } else null,
                    // Swipes over the nav bar reach the bottom zone via the bar's slippery
                    // handoff, so the finger has already travelled the bar's height before
                    // we get ACTION_DOWN — require only a short confirmation, not a full swipe.
                    minDistanceDp = minDistanceDp,
                    feedback = feedback,
                    onUnusedTouch = { samples -> replayUnusedTouch(samples) },
                )
            )
            val params = layoutParamsFor(zone, geometry)
            try {
                windowManager.addView(view, params)
                view.post {
                    val w = view.width
                    val h = view.height
                    if (w > 0 && h > 0) {
                        view.systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
                    }
                }
                activeViews[zone.id] = view
                indicator.attach()
                indicators[zone.id] = indicator
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to add overlay for ${zone.id}", t)
            }
        }
        attached = activeViews.isNotEmpty()
        // Fresh windows come up touchable; re-apply in case an excluded app is in front.
        applyZoneInteractivity()
    }

    private fun detachAll() {
        for ((_, ind) in indicators) {
            ind.detach()
        }
        indicators.clear()
        if (activeViews.isEmpty()) return
        for ((_, v) in activeViews) {
            try {
                windowManager.removeView(v)
            } catch (_: Throwable) { /* ignore */ }
        }
        activeViews.clear()
        attached = false
    }

    /**
     * A consumed touch turned out not to be a gesture (tap, long-press, wrong-direction
     * drag...). Re-inject it through the accessibility service so it reaches the UI the
     * zone covered — with the zones made untouchable for the duration so the injected
     * events don't land right back on us.
     */
    private fun replayUnusedTouch(samples: List<TouchSample>) {
        if (replaying || passThrough || samples.isEmpty()) return
        val service = EdgeGestureAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Accessibility service not bound; cannot replay touch")
            return
        }
        val first = samples.first()
        val last = samples.last()
        val movedPx = kotlin.math.hypot(last.x - first.x, last.y - first.y)
        val path = Path().apply {
            moveTo(first.x, first.y)
            if (movedPx < TAP_SLOP_PX) {
                // A tap: the recorded path is a single point. dispatchGesture needs a
                // non-zero path, and the target's click detector needs a realistic press
                // duration — a 1 ms same-point stroke registers as "completed" but never
                // clicks. Nudge the end by a sub-slop pixel so it's still a tap, not a drag.
                lineTo(first.x + 1f, first.y + 1f)
            } else {
                for (i in 1 until samples.size) lineTo(samples[i].x, samples[i].y)
            }
        }
        val rawDuration = last.timeMs - first.timeMs
        val duration = if (movedPx < TAP_SLOP_PX) {
            rawDuration.coerceIn(MIN_TAP_MS, MAX_REPLAY_MS)
        } else {
            rawDuration.coerceIn(1L, MAX_REPLAY_MS)
        }
        val gesture = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not build replay gesture", t)
            return
        }
        Log.d(TAG, "Replaying unused touch: ${samples.size} samples over ${duration}ms")
        replaying = true
        applyZoneInteractivity()
        val finish = Runnable {
            if (replaying) {
                replaying = false
                applyZoneInteractivity()
            }
        }
        // Safety net in case the result callback never arrives.
        mainHandler.postDelayed(finish, duration + INJECT_DELAY_MS + 1_000L)
        // updateViewLayout applies asynchronously; give the input pipeline a moment to
        // see the zones as untouchable before injecting, or the replay lands on us again.
        mainHandler.postDelayed({
            val dispatched = service.replay(gesture) { completed ->
                Log.d(TAG, "Replay finished, completed=$completed")
                mainHandler.removeCallbacks(finish)
                finish.run()
            }
            if (!dispatched) {
                Log.w(TAG, "dispatchGesture refused the replay")
                mainHandler.removeCallbacks(finish)
                finish.run()
            }
        }, INJECT_DELAY_MS)
    }

    /**
     * Zones are interactive unless a replay is in flight or the foreground app is
     * excluded. In both cases every window of ours must not just be untouchable but
     * invisible to input dispatch (window alpha 0): each non-touchable overlay window
     * counts as 0.8 "obscuring opacity", and where two of ours stack (side zone + back
     * indicator) the combined 0.96 exceeds Android's 0.8 cap, so the system would drop
     * a replayed touch as untrusted — and apps with their own tapjacking filters would
     * drop real touches flagged as obscured. Alpha-0 windows are exempt from both.
     * Nothing is drawn in the zones between gestures, so hiding them isn't visible;
     * hiding the indicators in excluded apps doubles as the "gestures off here" cue.
     */
    private fun applyZoneInteractivity() {
        val interactive = !replaying && !passThrough
        for ((_, view) in activeViews) {
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: continue
            val newFlags = if (interactive) {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            val newAlpha = if (interactive) 1f else 0f
            if (newFlags == lp.flags && lp.alpha == newAlpha) continue
            lp.flags = newFlags
            lp.alpha = newAlpha
            try {
                windowManager.updateViewLayout(view, lp)
            } catch (_: Throwable) { /* window may be mid-detach */ }
        }
        for ((_, indicator) in indicators) {
            indicator.setWindowHidden(!interactive)
        }
    }

    private fun onZoneTriggered(zone: ZoneConfig, long: Boolean) {
        val action = (if (long) zone.longAction else zone.action) ?: return
        // Side zones already ticked when their indicator armed.
        if (zone.id == ZoneId.BOTTOM) hapticTick()
        val service = EdgeGestureAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Accessibility service not bound; cannot fire $action")
            return
        }
        service.trigger(action)
    }

    private fun hapticTick() {
        val effect = VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            v?.vibrate(effect)
        }
    }

    private fun layoutParamsFor(
        zone: ZoneConfig,
        geometry: ScreenGeometry,
    ): WindowManager.LayoutParams {
        val thicknessPx = (zone.thicknessDp * geometry.density).toInt().coerceAtLeast(1)
        // Each zone is extended across its own edge's nav-bar inset (zero for bar-free
        // edges) and stops fitting insets, so it reaches the physical edge instead of
        // floating next to the bar — the bar sits at the bottom in portrait and moves to a
        // side in landscape with 3-button nav. Touches on the bar itself are still routed
        // to the bar — it is a higher-Z system window — but a swipe that starts on the bar
        // slips to the zone underneath the moment it leaves the bar (the bar is a
        // "slippery" window), and the extra band just past the bar catches it. Without the
        // extension, that slippery handoff would land beyond the zone and the bar's edge
        // would have no working gesture.
        val (widthPx, heightPx, gravity) = when (zone.id) {
            ZoneId.BOTTOM -> Triple(
                (geometry.width * zone.lengthPercent / 100).coerceAtLeast(1),
                thicknessPx + geometry.navBottom,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            )
            ZoneId.LEFT_EDGE -> Triple(
                thicknessPx + geometry.navLeft,
                (geometry.height * zone.lengthPercent / 100).coerceAtLeast(1),
                Gravity.START or Gravity.CENTER_VERTICAL,
            )
            ZoneId.RIGHT_EDGE -> Triple(
                thicknessPx + geometry.navRight,
                (geometry.height * zone.lengthPercent / 100).coerceAtLeast(1),
                Gravity.END or Gravity.CENTER_VERTICAL,
            )
        }
        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.notification_running_title))
            .setContentText(getString(R.string.notification_running_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "EdgeOverlayService"
        private const val CHANNEL_ID = "edge_gesture"
        private const val NOTIFICATION_ID = 1001
        private const val SIDE_MIN_DISTANCE_DP = 24f
        private const val BOTTOM_MIN_DISTANCE_DP = 10f
        private const val MAX_REPLAY_MS = 3_000L
        private const val INJECT_DELAY_MS = 80L
        private const val MIN_TAP_MS = 80L
        private const val TAP_SLOP_PX = 12f

        // Set true to tint the gesture zones so their touch areas are visible while testing.
        private const val DEBUG_SHOW_ZONES = false
        private const val ZONE_DEBUG_COLOR = 0x552196F3 // translucent blue
        const val ACTION_START = "com.ogesture.action.START_OVERLAY"

        /**
         * True between onCreate and onDestroy. Read by the accessibility service watchdog to
         * revive this service if the system (e.g. Samsung battery management) killed it out from
         * under us without going through the master switch.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, EdgeOverlayService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            // stopService (not startService with a stop action) so a stop request arriving
            // after the service has already stopped itself cannot re-create it.
            context.stopService(Intent(context, EdgeOverlayService::class.java))
        }
    }
}
