package com.snstimer.app.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.core.content.edit

enum class AttentionEffectType {
    SHAKE,
    PULSE,
    BOUNCE,
    BLINK,
}

data class OverlayAppearanceSettings(
    val timeTextSizeSp: Float = 16f,
    val textColor: Int = Color.WHITE,
    val backgroundColor: Int = Color.GRAY,
    val backgroundTransparency: Float = 0.5f,
    val attentionIntervalMinutes: Int = 5,
    val attentionEffectSizeDp: Float = 8f,
    val attentionEffectType: AttentionEffectType = AttentionEffectType.PULSE,
)

class OverlayAppearanceStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSettings(): OverlayAppearanceSettings {
        val transparency = if (prefs.contains(KEY_BACKGROUND_TRANSPARENCY)) {
            prefs.getFloat(KEY_BACKGROUND_TRANSPARENCY, 0.5f)
        } else {
            1f - prefs.getFloat(KEY_LEGACY_BACKGROUND_OPACITY, 0.5f)
        }
        return OverlayAppearanceSettings(
            timeTextSizeSp = prefs.getFloat(KEY_TEXT_SIZE, 16f),
            textColor = prefs.getInt(KEY_TEXT_COLOR, Color.WHITE),
            backgroundColor = prefs.getInt(KEY_BACKGROUND_COLOR, Color.GRAY),
            backgroundTransparency = transparency.coerceIn(0f, 1f),
            attentionIntervalMinutes = prefs.getInt(KEY_ATTENTION_INTERVAL, 5),
            attentionEffectSizeDp = prefs.getFloat(KEY_ATTENTION_SIZE, 8f),
            attentionEffectType = prefs.getString(KEY_ATTENTION_TYPE, null)
                ?.let { saved -> AttentionEffectType.values().firstOrNull { it.name == saved } }
                ?: AttentionEffectType.PULSE,
        )
    }

    fun saveSettings(settings: OverlayAppearanceSettings) {
        prefs.edit {
            putFloat(KEY_TEXT_SIZE, settings.timeTextSizeSp)
            putInt(KEY_TEXT_COLOR, settings.textColor)
            putInt(KEY_BACKGROUND_COLOR, settings.backgroundColor)
            putFloat(KEY_BACKGROUND_TRANSPARENCY, settings.backgroundTransparency)
            remove(KEY_LEGACY_BACKGROUND_OPACITY)
            putInt(KEY_ATTENTION_INTERVAL, settings.attentionIntervalMinutes)
            putFloat(KEY_ATTENTION_SIZE, settings.attentionEffectSizeDp)
            putString(KEY_ATTENTION_TYPE, settings.attentionEffectType.name)
        }
    }

    companion object {
        private const val PREFS_NAME = "sns_timer_overlay_appearance"
        private const val KEY_TEXT_SIZE = "time_text_size_sp"
        private const val KEY_TEXT_COLOR = "text_color"
        private const val KEY_BACKGROUND_COLOR = "background_color"
        private const val KEY_BACKGROUND_TRANSPARENCY = "background_transparency"
        private const val KEY_LEGACY_BACKGROUND_OPACITY = "background_opacity"
        private const val KEY_ATTENTION_INTERVAL = "attention_interval_minutes"
        private const val KEY_ATTENTION_SIZE = "attention_effect_size_dp"
        private const val KEY_ATTENTION_TYPE = "attention_effect_type"
    }
}
