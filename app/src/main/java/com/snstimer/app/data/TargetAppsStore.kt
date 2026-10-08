package com.snstimer.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class TargetAppsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSelectedPackages(): Set<String> =
        prefs.getStringSet(KEY_SELECTED, emptySet())?.toSet() ?: emptySet()

    fun isSelected(packageName: String): Boolean =
        getSelectedPackages().contains(packageName)

    fun setSelected(packageName: String, selected: Boolean) {
        val next = getSelectedPackages().toMutableSet()
        if (selected) next.add(packageName) else next.remove(packageName)
        prefs.edit { putStringSet(KEY_SELECTED, next) }
    }

    fun setSelectedPackages(packages: Set<String>) {
        prefs.edit { putStringSet(KEY_SELECTED, packages) }
    }

    /** Applies the first-run defaults once, without replacing a user's saved choices. */
    fun initializeDefaultSelection(defaultPackages: Set<String>): Set<String> {
        if (!prefs.getBoolean(KEY_DEFAULTS_INITIALIZED, false)) {
            val currentSelection = getSelectedPackages()
            prefs.edit {
                if (currentSelection.isEmpty()) {
                    putStringSet(KEY_SELECTED, defaultPackages)
                }
                putBoolean(KEY_DEFAULTS_INITIALIZED, true)
            }
        }
        return getSelectedPackages()
    }

    companion object {
        private const val PREFS_NAME = "sns_timer_targets"
        private const val KEY_SELECTED = "selected_packages"
        private const val KEY_DEFAULTS_INITIALIZED = "defaults_initialized"
    }
}
