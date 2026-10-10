package com.snstimer.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.snstimer.app.data.ShortVideoCountStore
import com.snstimer.app.data.TargetAppsStore

/** Heuristically detects short-video screens from accessible labels; it does not capture or upload screen content. */
class ShortVideoAccessibilityService : AccessibilityService() {
    private lateinit var targets: TargetAppsStore
    private lateinit var counts: ShortVideoCountStore

    override fun onServiceConnected() {
        targets = TargetAppsStore(this)
        counts = ShortVideoCountStore(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!::targets.isInitialized || !::counts.isInitialized) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in targets.getSelectedPackages()) return
        val root = rootInActiveWindow ?: event.source ?: return
        try {
            val labels = ArrayList<String>()
            collectLabels(root, labels, 0)
            if (labels.isEmpty()) return
            val normalized = labels.joinToString(" ").lowercase()
            val matched = SHORT_VIDEO_MARKERS.any(normalized::contains)
            if (!matched) return

            // Stable visible labels form a best-effort signature for the current item.
            val signature = labels.filterNot { it.matches(Regex(".*\\d{1,2}:\\d{2}.*")) }
                .distinct().sorted().joinToString("|")
            if (signature.length >= 12) counts.recordEstimatedVideo(packageName, signature)
        } finally {
            root.recycle()
        }
    }

    private fun collectLabels(node: AccessibilityNodeInfo, output: MutableList<String>, depth: Int) {
        if (depth > 18 || output.size >= 120) return
        node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(output::add)
        node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(output::add)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { collectLabels(child, output, depth + 1) } finally { child.recycle() }
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        private val SHORT_VIDEO_MARKERS = listOf("shorts", "reels", "릴스", "쇼츠")
    }
}
