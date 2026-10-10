package com.snstimer.app.data

import android.content.Context
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stores only daily estimated counts and a short-lived signature used for deduplication. */
class ShortVideoCountStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("short_video_counts", Context.MODE_PRIVATE)

    @Synchronized
    fun getToday(packageName: String, nowMs: Long = System.currentTimeMillis()): Int =
        prefs.getInt(countKey(packageName, nowMs), 0)

    @Synchronized
    fun recordEstimatedVideo(packageName: String, signature: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = countKey(packageName, nowMs)
        val signatureHash = signature.hashCode()
        val previousHash = prefs.getInt("last_signature_$packageName", Int.MIN_VALUE)
        val previousAt = prefs.getLong("last_signature_time_$packageName", 0L)
        if (signatureHash == previousHash || nowMs - previousAt < DEDUPLICATION_MS) return false
        prefs.edit {
            putInt(key, prefs.getInt(key, 0) + 1)
            putInt("last_signature_$packageName", signatureHash)
            putLong("last_signature_time_$packageName", nowMs)
        }
        return true
    }

    fun getTodayCounts(packageNames: Set<String>, nowMs: Long = System.currentTimeMillis()): Map<String, Int> =
        packageNames.associateWith { getToday(it, nowMs) }

    private fun countKey(packageName: String, nowMs: Long) = "count_${dayFormat.format(Date(nowMs))}_$packageName"

    companion object {
        private const val DEDUPLICATION_MS = 500L
        private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    }
}
