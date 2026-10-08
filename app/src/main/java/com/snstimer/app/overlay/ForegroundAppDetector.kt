package com.snstimer.app.overlay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.app.KeyguardManager
import android.os.PowerManager

/**
 * Finds the package currently in the foreground via UsageEvents.
 */
class ForegroundAppDetector(context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private var lastQueryTime = 0L
    private var foregroundPackage: String? = null

    @Synchronized
    fun currentForegroundPackage(): String? {
        val end = System.currentTimeMillis()
        if (lastQueryTime == 0L) {
            // Seed from recent history once. Subsequent polls only read new events,
            // so a long-running foreground app does not disappear after a fixed window.
            lastQueryTime = end - INITIAL_LOOKBACK_MS
        }
        val events = usageStatsManager.queryEvents(lastQueryTime, end)
        if (events != null) {
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // ACTIVITY_RESUMED == deprecated MOVE_TO_FOREGROUND (value 1).
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    foregroundPackage = event.packageName
                }
            }
        }
        // Keep a small overlap so events recorded at a poll boundary are not skipped.
        lastQueryTime = end - QUERY_OVERLAP_MS

        return foregroundPackage.takeIf { powerManager.isInteractive && !keyguardManager.isKeyguardLocked }
    }

    companion object {
        private const val INITIAL_LOOKBACK_MS = 24 * 60 * 60 * 1000L
        private const val QUERY_OVERLAP_MS = 2_000L
    }
}
