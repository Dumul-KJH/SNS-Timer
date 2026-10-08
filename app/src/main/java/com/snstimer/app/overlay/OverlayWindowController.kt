package com.snstimer.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.snstimer.app.R

/**
 * Manages a small, draggable overlay timer window.
 */
class OverlayWindowController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    val isShowing: Boolean
        get() = overlayView != null

    fun show() {
        if (overlayView != null) return

        val density = context.resources.displayMetrics.density
        val paddingH = (16 * density).toInt()
        val paddingV = (10 * density).toInt()

        val view = TextView(context).apply {
            text = formatElapsed(0L)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.MONOSPACE
            setPadding(paddingH, paddingV, paddingH, paddingV)
            background = ContextCompat.getDrawable(context, R.drawable.bg_overlay_timer)
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
    }

    fun updateElapsed(elapsedMs: Long) {
        overlayView?.text = formatElapsed(elapsedMs)
    }

    fun hide() {
        val view = overlayView ?: return
        runCatching { windowManager.removeView(view) }
        overlayView = null
        layoutParams = null
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
