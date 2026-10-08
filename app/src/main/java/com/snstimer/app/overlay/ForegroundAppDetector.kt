package com.snstimer.app.overlay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.Build
import android.content.Context
import android.os.PowerManager
import android.app.KeyguardManager

/** Tracks selected apps that remain visible, including activities paused in PiP. */
class ForegroundAppDetector(context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private var lastQueryTime = 0L
    private val visibleActivities = mutableMapOf<VisibleActivity, Long>()

    @Synchronized
    fun currentVisibleTargetPackage(targetPackages: Set<String>): String? {
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
                val eventPackage = event.packageName ?: continue
                val activity = VisibleActivity(eventPackage, event.className.orEmpty())
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        // Keep resumed activities visible until Android reports that
                        // they stopped. PiP activities can be paused while still visible.
                        visibleActivities[activity] = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            visibleActivities.remove(activity)
                        }
                    }
                }
            }
        }
        // Keep a small overlap so events recorded at a poll boundary are not skipped.
        lastQueryTime = end - QUERY_OVERLAP_MS

        if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) return null

        return visibleActivities.entries
            .asSequence()
            .filter { it.key.packageName in targetPackages }
            .maxByOrNull { it.value }
            ?.key
            ?.packageName
    }

    private data class VisibleActivity(val packageName: String, val className: String)

    companion object {
        private const val INITIAL_LOOKBACK_MS = 24 * 60 * 60 * 1000L
        private const val QUERY_OVERLAP_MS = 2_000L
    }
}
