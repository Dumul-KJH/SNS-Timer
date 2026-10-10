package com.snstimer.app.data

import android.content.Context

/** Remembers whether the user expects background monitoring to continue. */
class MonitoringPreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isMonitoringEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun setMonitoringEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "monitoring_preferences"
        const val KEY_ENABLED = "monitoring_enabled"
    }
}
