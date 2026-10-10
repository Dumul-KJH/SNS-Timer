package com.snstimer.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.snstimer.app.data.ShortVideoCountStore
import com.snstimer.app.data.TargetAppsStore

/** Counts estimated video transitions on TikTok, Instagram Reels, and YouTube Shorts. */
class ShortVideoAccessibilityService : AccessibilityService() {
    private lateinit var targets: TargetAppsStore
    private lateinit var counts: ShortVideoCountStore
    private val observations = mutableMapOf<String, Observation>()

    override fun onServiceConnected() {
        targets = TargetAppsStore(this)
        counts = ShortVideoCountStore(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!::targets.isInitialized || !::counts.isInitialized) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in targets.getSelectedPackages() || packageName !in SUPPORTED_PACKAGES) return

        val eventSource = event.source?.takeIf { it.packageName?.toString() == packageName }
        val root = matchingWindowRoot(packageName) ?: eventSource ?: return
        try {
            val snapshot = ScreenSnapshot()
            collectScreenData(root, snapshot, 0)
            if (eventSource != null && eventSource !== root) collectScreenData(eventSource, snapshot, 0)
            event.text?.map { it.toString() }?.filter(String::isNotBlank)?.let(snapshot.labels::addAll)
            event.className?.toString()?.let(snapshot.classNames::add)
            val now = SystemClock.elapsedRealtime()
            val previous = observations[packageName]
            val screenRecognized = isShortVideoScreen(packageName, snapshot, event.eventType)
            if (screenRecognized) {
                if (previous == null) {
                    observations[packageName] = Observation(
                        lastVideoSignature = "",
                        candidateSignature = "",
                        candidateSince = now,
                        lastSignalAt = now,
                        lastQualifiedAt = now,
                    )
                } else {
                    observations[packageName] = previous.copy(lastSignalAt = now, lastQualifiedAt = now)
                }
            } else if (previous == null ||
                (packageName !in YOUTUBE_PACKAGES && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) ||
                (previous != null && now - previous.lastQualifiedAt > SHORT_FEED_SESSION_TIMEOUT_MS)
            ) {
                observations.remove(packageName)
                return
            }

            observeVideoTransition(packageName, snapshot.videoSignature(), event)
        } finally {
            if (eventSource != null && eventSource !== root) eventSource.recycle()
            root.recycle()
        }
    }

    private fun observeVideoTransition(packageName: String, signature: String, event: AccessibilityEvent) {
        val now = SystemClock.elapsedRealtime()
        val previous = observations[packageName]

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val indexedPosition = if (event.itemCount > 1 && event.toIndex >= 0) "item:${event.toIndex}" else null
            val pixelPosition = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && event.maxScrollY > 0) {
                "y:${event.scrollY}"
            } else null
            val position = indexedPosition ?: pixelPosition
            val debounceElapsed = now - (previous?.lastScrollAt ?: 0L) >= SCROLL_DEBOUNCE_MS
            val indexChanged = indexedPosition != null && indexedPosition != previous?.lastScrollPosition
            val pixelChanged = indexedPosition == null && pixelPosition != null &&
                pixelPosition != previous?.lastScrollPosition && debounceElapsed
            if (indexChanged || pixelChanged || position == null && debounceElapsed) {
                // Each settled vertical feed swipe advances to one new short video. Count the
                // gesture directly; waiting for a title change misses videos with hidden metadata.
                record(packageName, "feed-swipe-$now")
                observations[packageName] = Observation(
                    lastVideoSignature = signature.ifEmpty { previous?.lastVideoSignature.orEmpty() },
                    candidateSignature = signature,
                    candidateSince = now,
                    lastScrollAt = now,
                    lastScrollPosition = position,
                    lastSignalAt = now,
                    lastQualifiedAt = now,
                    hasScrolled = true,
                )
            } else if (previous != null) {
                observations[packageName] = previous.copy(lastSignalAt = now, lastQualifiedAt = now)
            }
            return
        }

        val state = previous ?: Observation(
            lastVideoSignature = "",
            candidateSignature = signature,
            candidateSince = now,
            lastSignalAt = now,
            lastQualifiedAt = now,
        ).also { observations[packageName] = it }

        if (signature.isEmpty() || signature == state.lastVideoSignature) {
            observations[packageName] = state.copy(lastSignalAt = now)
            return
        }
        if (signature != state.candidateSignature) {
            observations[packageName] = state.copy(
                candidateSignature = signature,
                candidateSince = now,
                lastSignalAt = now,
            )
        } else if (now - state.candidateSince >= STABLE_CONTENT_MS &&
            (!state.hasScrolled || now - state.lastScrollAt > POST_SCROLL_SIGNATURE_GUARD_MS)
        ) {
            // Metadata is a fallback for versions that do not expose feed scroll events.
            record(packageName, signature)
            observations[packageName] = state.copy(
                lastVideoSignature = signature,
                lastSignalAt = now,
            )
        } else {
            observations[packageName] = state.copy(lastSignalAt = now)
        }
    }

    private fun record(packageName: String, signature: String) {
        counts.recordEstimatedVideo(packageName, signature)
    }

    private fun isShortVideoScreen(
        packageName: String,
        screen: ScreenSnapshot,
        eventType: Int,
    ): Boolean {
        val labels = screen.labels.map { it.lowercase() }
        val hasLike = labels.any { label -> LIKE_LABELS.any(label::contains) }
        val hasComments = labels.any { label -> COMMENT_LABELS.any(label::contains) }
        val hasShare = labels.any { label -> SHARE_LABELS.any(label::contains) }
        val hasFollow = labels.any { label -> FOLLOW_LABELS.any(label::contains) }
        val engagementControls = listOf(hasLike, hasComments, hasShare, hasFollow).count { it }
        val hasShortsLabel = labels.any { label -> YOUTUBE_SHORTS_MARKERS.any(label::contains) } ||
            screen.viewIds.any { id -> id.contains("shorts", ignoreCase = true) || id.contains("reel_player", ignoreCase = true) }
        val hasReelsLabel = labels.any { label -> INSTAGRAM_REELS_MARKERS.any(label::contains) } ||
            screen.viewIds.any { id -> id.contains("reel", ignoreCase = true) || id.contains("clips", ignoreCase = true) } ||
            screen.classNames.any { name -> name.contains("reel", ignoreCase = true) || name.contains("clips", ignoreCase = true) }
        val hasTikTokFeedLabel = labels.any { label -> TIKTOK_FEED_MARKERS.any(label::contains) }
        val hasTikTokPlayerControl = labels.any { label -> TIKTOK_PLAYER_MARKERS.any(label::contains) }

        return when (packageName) {
            in YOUTUBE_PACKAGES -> hasShortsLabel && engagementControls >= 1
            in INSTAGRAM_PACKAGES -> hasReelsLabel && engagementControls >= 2
            in TIKTOK_PACKAGES -> engagementControls >= 3 ||
                engagementControls >= 2 && (hasTikTokFeedLabel || hasTikTokPlayerControl || eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) ||
                engagementControls >= 1 && hasTikTokFeedLabel && hasTikTokPlayerControl
            else -> false
        }
    }

    private fun matchingWindowRoot(packageName: String): AccessibilityNodeInfo? {
        for (window in windows) {
            val candidate = window.root
            candidate?.let { root ->
                if (root.packageName?.toString() == packageName) return root
                root.recycle()
            }
        }
        return rootInActiveWindow?.let { root ->
            if (root.packageName?.toString() == packageName) root else {
                root.recycle()
                null
            }
        }
    }

    private fun collectScreenData(node: AccessibilityNodeInfo, snapshot: ScreenSnapshot, depth: Int) {
        if (depth > MAX_TREE_DEPTH || snapshot.labels.size >= MAX_LABELS) return
        node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(snapshot.labels::add)
        node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(snapshot.labels::add)
        node.viewIdResourceName?.let(snapshot.viewIds::add)
        node.className?.toString()?.let(snapshot.classNames::add)
        for (index in 0 until node.childCount) {
            if (snapshot.labels.size >= MAX_LABELS) break
            val child = node.getChild(index) ?: continue
            try {
                collectScreenData(child, snapshot, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    override fun onInterrupt() = Unit

    private data class ScreenSnapshot(
        val labels: MutableList<String> = mutableListOf(),
        val viewIds: MutableList<String> = mutableListOf(),
        val classNames: MutableList<String> = mutableListOf(),
    ) {
        fun videoSignature(): String = labels.asSequence()
            .map { it.trim().lowercase().replace(Regex("\\s+"), " ") }
            .filter { text ->
                text.length >= MIN_CONTENT_LABEL_LENGTH &&
                    text !in GENERIC_LABELS &&
                    !text.matches(Regex("[\\d\\s.,:％%kmb만천억+\\-]+")) &&
                    !text.matches(Regex(".*\\b\\d{1,2}:\\d{2}\\b.*")) &&
                    !METRIC_LABELS.any(text::contains)
            }
            .distinct()
            .sorted()
            .take(MAX_SIGNATURE_LABELS)
            .joinToString("|")
    }

    private data class Observation(
        val lastVideoSignature: String,
        val candidateSignature: String,
        val candidateSince: Long,
        val lastScrollAt: Long = 0L,
        val lastScrollPosition: String? = null,
        val lastSignalAt: Long,
        val lastQualifiedAt: Long,
        val hasScrolled: Boolean = false,
    )

    companion object {
        private val YOUTUBE_PACKAGES = setOf("com.google.android.youtube")
        private val INSTAGRAM_PACKAGES = setOf("com.instagram.android", "com.instagram.barcelona")
        private val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically", "com.zhiliaoapp.musically.go", "com.ss.android.ugc.trill",
        )
        private val SUPPORTED_PACKAGES = YOUTUBE_PACKAGES + INSTAGRAM_PACKAGES + TIKTOK_PACKAGES

        private val YOUTUBE_SHORTS_MARKERS = listOf("shorts", "쇼츠", "short video")
        private val INSTAGRAM_REELS_MARKERS = listOf("reels", "릴스")
        private val TIKTOK_FEED_MARKERS = listOf("for you", "following", "추천", "팔로잉", "친구")
        private val TIKTOK_PLAYER_MARKERS = listOf("sound", "audio", "원본 오디오", "사운드", "음원")
        private val LIKE_LABELS = listOf("like", "likes", "liked", "좋아요", "me gusta", "いいね")
        private val COMMENT_LABELS = listOf("comment", "comments", "댓글", "comentario", "コメント")
        private val SHARE_LABELS = listOf("share", "공유", "compartir", "共有")
        private val FOLLOW_LABELS = listOf("follow", "팔로우", "팔로잉", "seguir", "フォロー")
        private val GENERIC_LABELS = setOf(
            "shorts", "쇼츠", "reels", "릴스", "for you", "following", "팔로잉", "친구",
            "like", "likes", "comment", "comments", "share", "follow", "좋아요", "댓글", "공유", "팔로우",
        )
        private val METRIC_LABELS = listOf(
            " likes", " comments", " views", " shares", "좋아요", "댓글", "조회수", "공유",
        )
        private const val MAX_TREE_DEPTH = 20
        private const val MAX_LABELS = 160
        private const val MAX_SIGNATURE_LABELS = 10
        private const val MIN_CONTENT_LABEL_LENGTH = 5
        private const val STABLE_CONTENT_MS = 900L
        private const val SCROLL_DEBOUNCE_MS = 800L
        private const val POST_SCROLL_SIGNATURE_GUARD_MS = 2_500L
        private const val SHORT_FEED_SESSION_TIMEOUT_MS = 8_000L
    }
}
