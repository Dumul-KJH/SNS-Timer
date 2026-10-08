package com.snstimer.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Calendar
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date

data class AppDailyUsage(
    val todayMs: Long,
    val sevenDayAverageMs: Long,
    val thirtyDayAverageMs: Long,
)

data class DailyUsageReport(
    val todayTotalMs: Long,
    val sevenDayTotalMs: Long,
    val sevenDayAverageMs: Long,
    val thirtyDayTotalMs: Long,
    val thirtyDayAverageMs: Long,
    val apps: Map<String, AppDailyUsage>,
)

/** Stores tracked foreground time separately for each app and local calendar day. */
class DailyUsageStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getTodayUsage(packageName: String, nowMs: Long = System.currentTimeMillis()): Long =
        prefs.getLong(usageKey(packageName, nowMs), 0L)

    @Synchronized
    fun getReport(packageNames: Set<String>, nowMs: Long = System.currentTimeMillis()): DailyUsageReport {
        val appUsage = packageNames.associateWith { packageName ->
            val today = usageForDays(packageName, 1, nowMs)
            val sevenDays = usageForDays(packageName, 7, nowMs)
            val thirtyDays = usageForDays(packageName, 30, nowMs)
            AppDailyUsage(
                todayMs = today,
                sevenDayAverageMs = sevenDays / 7,
                thirtyDayAverageMs = thirtyDays / 30,
            )
        }
        val todayTotal = appUsage.values.sumOf { it.todayMs }
        val sevenDayTotal = packageNames.sumOf { usageForDays(it, 7, nowMs) }
        val thirtyDayTotal = packageNames.sumOf { usageForDays(it, 30, nowMs) }
        return DailyUsageReport(
            todayTotalMs = todayTotal,
            sevenDayTotalMs = sevenDayTotal,
            sevenDayAverageMs = sevenDayTotal / 7,
            thirtyDayTotalMs = thirtyDayTotal,
            thirtyDayAverageMs = thirtyDayTotal / 30,
            apps = appUsage,
        )
    }

    /** Records an interval, splitting it at local midnight when needed. */
    @Synchronized
    fun addUsage(packageName: String, startMs: Long, endMs: Long) {
        var cursor = startMs
        while (cursor < endMs) {
            val nextMidnight = Calendar.getInstance().apply {
                timeInMillis = cursor
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val segmentEnd = minOf(endMs, nextMidnight)
            val key = usageKey(packageName, cursor)
            val segmentDuration = segmentEnd - cursor
            val updatedDuration = prefs.getLong(key, 0L) + segmentDuration
            prefs.edit {
                putLong(key, updatedDuration)
            }
            cursor = segmentEnd
        }
    }

    private fun usageForDays(packageName: String, days: Int, nowMs: Long): Long {
        val date = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var total = 0L
        repeat(days) {
            total += prefs.getLong(usageKey(packageName, date.timeInMillis), 0L)
            date.add(Calendar.DAY_OF_MONTH, -1)
        }
        return total
    }

    private fun usageKey(packageName: String, timestampMs: Long): String =
        "${KEY_PREFIX}${dayKeyFormat.format(Date(timestampMs))}_$packageName"

    companion object {
        private const val PREFS_NAME = "sns_timer_daily_usage"
        private const val KEY_PREFIX = "usage_"
    }

    private val dayKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
}
