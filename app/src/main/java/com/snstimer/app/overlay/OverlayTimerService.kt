package com.snstimer.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.snstimer.app.MainActivity
import com.snstimer.app.R
import com.snstimer.app.data.DailyUsageStore
import com.snstimer.app.data.OverlayAppearanceStore
import com.snstimer.app.data.ShortVideoCountStore
import com.snstimer.app.data.TargetAppsStore
import com.snstimer.app.permission.PermissionChecker
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that polls the current app and shows a usage timer overlay
 * while a user-selected target app is visible, including in picture-in-picture mode.
 */
class OverlayTimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var targetAppsStore: TargetAppsStore
    private lateinit var dailyUsageStore: DailyUsageStore
    private lateinit var appearanceStore: OverlayAppearanceStore
    private lateinit var shortVideoCountStore: ShortVideoCountStore
    private lateinit var foregroundDetector: ForegroundAppDetector
    private lateinit var overlayController: OverlayWindowController

    private var activePackage: String? = null
    private var lastTickAtMs: Long = 0L
    private var sessionStartElapsedRealtimeMs: Long = 0L
    private var sessionBaseUsageMs: Long = 0L
    private var sessionDayKey: String? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        targetAppsStore = TargetAppsStore(this)
        dailyUsageStore = DailyUsageStore(this)
        appearanceStore = OverlayAppearanceStore(this)
        shortVideoCountStore = ShortVideoCountStore(this)
        foregroundDetector = ForegroundAppDetector(this)
        overlayController = OverlayWindowController(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning.set(true)
        handler.post(pollRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        overlayController.hide()
        isRunning.set(false)
        super.onDestroy()
    }

    private fun tick() {
        if (!PermissionChecker.hasAllRequiredPermissions(this)) {
            hideSession()
            stopSelf()
            return
        }

        val targets = targetAppsStore.getSelectedPackages()
        val now = System.currentTimeMillis()
        val detectedForeground = foregroundDetector.currentVisibleTargetPackage(targets)
        val foreground = detectedForeground?.takeIf { it != packageName && it in targets }
        if (foreground == null) {
            hideSession()
            return
        }

        val elapsedRealtime = SystemClock.elapsedRealtime()
        val appearance = appearanceStore.getSettings()
        val todayKey = DAY_KEY_FORMAT.format(java.util.Date(now))
        if (activePackage != foreground) {
            activePackage = foreground
            lastTickAtMs = now
            sessionStartElapsedRealtimeMs = elapsedRealtime
            sessionBaseUsageMs = dailyUsageStore.getTodayUsage(foreground, now)
            sessionDayKey = todayKey
            overlayController.show(appearance)
        } else {
            if (sessionDayKey != todayKey) {
                sessionBaseUsageMs = dailyUsageStore.getTodayUsage(foreground, now)
                sessionStartElapsedRealtimeMs = elapsedRealtime
                sessionDayKey = todayKey
            }
            val intervalStart = lastTickAtMs
            val elapsedMs = (now - intervalStart).coerceIn(0L, MAX_COUNTED_INTERVAL_MS)
            if (elapsedMs > 0L) {
                dailyUsageStore.addUsage(foreground, now - elapsedMs, now)
            }
            lastTickAtMs = now
        }

        val liveElapsedMs = sessionBaseUsageMs + (elapsedRealtime - sessionStartElapsedRealtimeMs).coerceAtLeast(0L)
        overlayController.updateElapsed(
            liveElapsedMs,
            appearance,
            shortVideoLabel = shortVideoLabelFor(foreground, now),
        )
    }

    private fun shortVideoLabelFor(packageName: String, now: Long): String? {
        if (!shortVideoCountStore.isShortVideoActive(packageName)) return null
        val count = shortVideoCountStore.getToday(packageName, now)
        return when (packageName) {
            in INSTAGRAM_PACKAGES -> getString(R.string.overlay_reels_count, count)
            in TIKTOK_PACKAGES -> getString(R.string.overlay_tiktok_count, count)
            in YOUTUBE_PACKAGES -> getString(R.string.overlay_shorts_count, count)
            else -> null
        }
    }

    private fun hideSession() {
        activePackage = null
        lastTickAtMs = 0L
        sessionStartElapsedRealtimeMs = 0L
        sessionBaseUsageMs = 0L
        sessionDayKey = null
        overlayController.hide()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop_monitoring), stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sns_timer_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 500L
        private const val MAX_COUNTED_INTERVAL_MS = 5_000L
        private val DAY_KEY_FORMAT = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        private val INSTAGRAM_PACKAGES = setOf("com.instagram.android", "com.instagram.barcelona")
        private val TIKTOK_PACKAGES = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        private val YOUTUBE_PACKAGES = setOf("com.google.android.youtube")
        const val ACTION_STOP = "com.snstimer.app.action.STOP_OVERLAY"

        private val isRunning = AtomicBoolean(false)

        fun isRunning(): Boolean = isRunning.get()

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayTimerService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayTimerService::class.java))
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, OverlayTimerService::class.java).setAction(ACTION_STOP)
    }
}
