package com.snstimer.app.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.LinearInterpolator
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.snstimer.app.R
import com.snstimer.app.data.AttentionEffectType
import com.snstimer.app.data.OverlayAppearanceSettings

/**
 * Manages a small, draggable overlay timer window.
 */
class OverlayWindowController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var attentionAnimator: AnimatorSet? = null
    private var lastTenMinuteMark: Long? = null
    private var lastEffectIntervalMinutes: Int? = null
    private var appliedSettings: OverlayAppearanceSettings? = null

    val isShowing: Boolean
        get() = overlayView != null

    fun show(settings: OverlayAppearanceSettings) {
        if (overlayView != null) return
        lastTenMinuteMark = null
        lastEffectIntervalMinutes = null

        val density = context.resources.displayMetrics.density
        val paddingH = (16 * density).toInt()
        val paddingV = (10 * density).toInt()

        val view = TextView(context).apply {
            text = formatElapsed(0L)
            setTextColor(settings.textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.timeTextSizeSp)
            typeface = Typeface.MONOSPACE
            setPadding(paddingH, paddingV, paddingH, paddingV)
            background = createBackground(settings)
            elevation = 8 * density
            contentDescription = context.getString(R.string.overlay_timer_content_description)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * density).toInt()
            y = (80 * density).toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        enableDragging(view, params)
        windowManager.addView(view, params)
        overlayView = view
        layoutParams = params
        appliedSettings = settings
    }

    fun updateElapsed(elapsedMs: Long, settings: OverlayAppearanceSettings) {
        val view = overlayView ?: return
        if (appliedSettings != settings) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.timeTextSizeSp)
            view.setTextColor(settings.textColor)
            view.background = createBackground(settings)
            appliedSettings = settings
        }
        view.text = formatElapsed(elapsedMs)

        val intervalMs = settings.attentionIntervalMinutes * MINUTE_MS
        val intervalMark = elapsedMs / intervalMs
        val previousMark = lastTenMinuteMark
        if (lastEffectIntervalMinutes != settings.attentionIntervalMinutes || previousMark == null) {
            lastEffectIntervalMinutes = settings.attentionIntervalMinutes
            lastTenMinuteMark = intervalMark
        } else if (intervalMark > previousMark) {
            lastTenMinuteMark = intervalMark
            animateAttention(
                view,
                settings.attentionEffectSizeDp,
                settings.attentionEffectType,
            )
        }
    }

    fun hide() {
        attentionAnimator?.cancel()
        attentionAnimator = null
        lastTenMinuteMark = null
        lastEffectIntervalMinutes = null
        appliedSettings = null
        val view = overlayView ?: return
        runCatching { windowManager.removeView(view) }
        overlayView = null
        layoutParams = null
    }

    private fun animateAttention(
        view: View,
        effectSizeDp: Float,
        effectType: AttentionEffectType,
    ) {
        attentionAnimator?.cancel()
        view.translationX = 0f
        view.translationY = 0f
        view.rotation = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
        val distance = effectSizeDp * context.resources.displayMetrics.density
        val rotation = effectSizeDp * ROTATION_PER_DP
        val scaleAmount = (effectSizeDp * SCALE_PER_DP).coerceIn(0.04f, 0.4f)
        val blinkAmount = (effectSizeDp * BLINK_PER_DP).coerceIn(0.1f, 0.8f)
        val animators = when (effectType) {
            AttentionEffectType.SHAKE -> listOf(
                ObjectAnimator.ofFloat(
                    view,
                    View.TRANSLATION_X,
                    0f,
                    distance,
                    -distance,
                    distance * 0.65f,
                    -distance * 0.65f,
                    0f,
                ),
                ObjectAnimator.ofFloat(
                    view,
                    View.ROTATION,
                    0f,
                    rotation,
                    -rotation,
                    rotation * 0.65f,
                    -rotation * 0.65f,
                    0f,
                ),
            )
            AttentionEffectType.PULSE -> listOf(
                ObjectAnimator.ofFloat(
                    view,
                    View.SCALE_X,
                    1f,
                    1f + scaleAmount,
                    1f - scaleAmount * 0.3f,
                    1f + scaleAmount * 0.6f,
                    1f,
                ),
                ObjectAnimator.ofFloat(
                    view,
                    View.SCALE_Y,
                    1f,
                    1f + scaleAmount,
                    1f - scaleAmount * 0.3f,
                    1f + scaleAmount * 0.6f,
                    1f,
                ),
            )
            AttentionEffectType.BOUNCE -> listOf(
                ObjectAnimator.ofFloat(
                    view,
                    View.TRANSLATION_Y,
                    0f,
                    -distance,
                    0f,
                    -distance * 0.55f,
                    0f,
                ),
            )
            AttentionEffectType.BLINK -> listOf(
                ObjectAnimator.ofFloat(
                    view,
                    View.ALPHA,
                    1f,
                    1f - blinkAmount,
                    1f,
                    1f - blinkAmount * 0.6f,
                    1f,
                ),
            )
        }
        attentionAnimator = AnimatorSet().apply {
            playTogether(animators)
            duration = SHAKE_DURATION_MS
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun createBackground(settings: OverlayAppearanceSettings): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(settings.backgroundColor)
            alpha = ((1f - settings.backgroundTransparency.coerceIn(0f, 1f)) * 255).toInt()
            cornerRadius = CORNER_RADIUS_DP * context.resources.displayMetrics.density
        }

    private fun enableDragging(view: View, params: WindowManager.LayoutParams) {
        var lastX = 0f
        var lastY = 0f
        var startX = 0
        var startY = 0

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastX).toInt()
                    val dy = (event.rawY - lastY).toInt()
                    // Gravity END: increasing x moves leftward visually
                    params.x = startX - dx
                    params.y = startY + dy
                    windowManager.updateViewLayout(v, params)
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val SHAKE_DURATION_MS = 420L
        private const val ROTATION_PER_DP = 0.3f
        private const val SCALE_PER_DP = 0.04f
        private const val BLINK_PER_DP = 0.12f
        private const val CORNER_RADIUS_DP = 20f

        fun formatElapsed(elapsedMs: Long): String {
            val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%02d:%02d".format(minutes, seconds)
            }
        }
    }
}
