package com.snstimer.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.snstimer.app.data.ShortVideoCountStore
import com.snstimer.app.data.TargetAppsStore

/** Counts estimated video transitions on TikTok, Instagram Reels, and YouTube Shorts. */
class ShortVideoAccessibilityService : AccessibilityService() {
    private lateinit var targets: TargetAppsStore
    private lateinit var counts: ShortVideoCountStore
    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null
    private var pendingYoutubeContentCount: Runnable? = null
    private var youtubeContentGeneration = 0L
    private val observations = mutableMapOf<String, Observation>()
    private val lastEntryCountAt = mutableMapOf<String, Long>()

    override fun onServiceConnected() {
        targets = TargetAppsStore(this)
        counts = ShortVideoCountStore(this)
        counts.setActiveShortVideoPackage(null)
        processingThread = HandlerThread("short-video-accessibility").apply { start() }
        processingHandler = Handler(processingThread!!.looper)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString() ?: return
        if (!::targets.isInitialized || !::counts.isInitialized) return
        if (packageName !in SUPPORTED_PACKAGES) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                counts.setActiveShortVideoPackage(null)
            }
            return
        }
        if (packageName !in targets.getSelectedPackages()) {
            counts.setActiveShortVideoPackage(null)
            return
        }
        val handler = processingHandler ?: return
        val eventCopy = AccessibilityEvent.obtain(event)
        if (!handler.post {
                try {
                    processAccessibilityEvent(eventCopy)
                } finally {
                    eventCopy.recycle()
                }
            }
        ) {
            eventCopy.recycle()
        }
    }

    private fun processAccessibilityEvent(event: AccessibilityEvent) {
        if (!::targets.isInitialized || !::counts.isInitialized) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in targets.getSelectedPackages() || packageName !in SUPPORTED_PACKAGES) {
            if (packageName in SUPPORTED_PACKAGES) counts.setActiveShortVideoPackage(null)
            return
        }

        val eventSource = event.source?.takeIf { it.packageName?.toString() == packageName }
        val root = matchingWindowRoot(packageName) ?: eventSource ?: run {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                counts.setActiveShortVideoPackage(null)
                observations.remove(packageName)
            }
            return
        }
        try {
            val snapshot = ScreenSnapshot()
            collectScreenData(root, snapshot, 0)
            if (eventSource != null && eventSource !== root) collectScreenData(eventSource, snapshot, 0)
            event.text?.map { it.toString() }?.filter(String::isNotBlank)?.let(snapshot.labels::addAll)
            event.className?.toString()?.let(snapshot.classNames::add)
            val now = SystemClock.elapsedRealtime()
            val signature = snapshot.videoSignature()
            val previous = observations[packageName]
            val screenRecognized = isShortVideoScreen(packageName, snapshot, event.eventType)
            val adInterruption = previous != null && isShortVideoAdScreen(packageName, snapshot)
            if (!screenRecognized && !adInterruption) {
                observations.remove(packageName)
                if (packageName in YOUTUBE_PACKAGES) cancelPendingYoutubeContentCount()
                if (counts.isShortVideoActive(packageName)) counts.setActiveShortVideoPackage(null)
                return
            }

            counts.setActiveShortVideoPackage(packageName)
            if (adInterruption) {
                if (packageName in YOUTUBE_PACKAGES) cancelPendingYoutubeContentCount()
                observations[packageName]?.let { state ->
                    observations[packageName] = state.copy(
                        candidateSignature = state.lastVideoSignature,
                        candidateSince = now,
                        lastSignalAt = now,
                        lastQualifiedAt = now,
                    )
                }
                return
            }
            if (previous == null) {
                val lastEntryAt = lastEntryCountAt[packageName]
                if (lastEntryAt == null || now - lastEntryAt >= ENTRY_COUNT_DEBOUNCE_MS) {
                    record(packageName, "short-video-entry-$now")
                    lastEntryCountAt[packageName] = now
                }
                observations[packageName] = Observation(
                    lastVideoSignature = signature,
                    candidateSignature = signature,
                    candidateSince = now,
                    lastScrollAt = if (packageName in YOUTUBE_PACKAGES) now else 0L,
                    lastSignalAt = now,
                    lastQualifiedAt = now,
                    sessionStartedAt = now,
                    lastCountedScrollAt = 0L,
                    suppressContentUntil = if (packageName in YOUTUBE_PACKAGES) {
                        now + YOUTUBE_ENTRY_SETTLE_MS
                    } else {
                        0L
                    },
                )
            } else {
                observations[packageName] = previous.copy(lastSignalAt = now, lastQualifiedAt = now)
            }
            observeVideoTransition(packageName, signature, event, eventSource)
        } finally {
            if (eventSource != null && eventSource !== root) eventSource.recycle()
            root.recycle()
        }
    }

    private fun observeVideoTransition(
        packageName: String,
        signature: String,
        event: AccessibilityEvent,
        eventSource: AccessibilityNodeInfo?,
    ) {
        val now = SystemClock.elapsedRealtime()
        val previous = observations[packageName]

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (packageName in INSTAGRAM_PACKAGES && eventSource?.isScrollable != true) return
            if (packageName in YOUTUBE_PACKAGES) {
                cancelPendingYoutubeContentCount()
                val state = previous ?: return
                val sessionSettled = now - state.sessionStartedAt >= YOUTUBE_ENTRY_SETTLE_MS
                val throttleElapsed = now - state.lastCountedScrollAt >= YOUTUBE_SCROLL_THROTTLE_MS
                observations[packageName] = state.copy(
                    lastVideoSignature = signature.ifEmpty { state.lastVideoSignature },
                    candidateSignature = signature,
                    candidateSince = now,
                    lastScrollAt = now,
                    lastSignalAt = now,
                    lastQualifiedAt = now,
                    hasScrolled = true,
                    lastCountedScrollAt = if (sessionSettled && throttleElapsed) now else state.lastCountedScrollAt,
                    suppressContentUntil = now + YOUTUBE_CONTENT_SCROLL_SUPPRESS_MS,
                )
                if (sessionSettled && throttleElapsed) record(YOUTUBE_PACKAGE, "youtube-swipe-$now")
                return
            }
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

        // On Instagram, accessibility text changes also occur while browsing the home
        // feed and profile. Only a verified Reels viewer swipe counts as a new video.
        if (packageName in INSTAGRAM_PACKAGES) return
        // Prefer actual scroll events, but use a settled accessibility-tree fingerprint when
        // the YouTube version exposes only WINDOW_CONTENT_CHANGED for Shorts swipes.
        if (packageName in YOUTUBE_PACKAGES) {
            observeYoutubeContentTransition(
                signature = signature,
                eventType = event.eventType,
                sourceViewId = eventSource?.viewIdResourceName,
            )
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

    private fun record(packageName: String, signature: String): Boolean {
        return counts.recordEstimatedVideo(packageName, signature)
    }

    /** YouTube's Shorts viewer exposes content changes but may never emit VIEW_SCROLLED. */
    private fun observeYoutubeContentTransition(
        signature: String,
        eventType: Int,
        sourceViewId: String?,
    ) {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || signature.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val state = observations[YOUTUBE_PACKAGE] ?: return
        if (signature == state.lastVideoSignature) {
            if (state.candidateSignature != signature) {
                observations[YOUTUBE_PACKAGE] = state.copy(candidateSignature = signature, candidateSince = now)
                cancelPendingYoutubeContentCount()
            }
            return
        }

        if (signature != state.candidateSignature) {
            observations[YOUTUBE_PACKAGE] = state.copy(candidateSignature = signature, candidateSince = now)
            val settleMs = if (sourceViewId?.contains(YOUTUBE_RECYCLER_MARKER, ignoreCase = true) == true) {
                YOUTUBE_RECYCLER_CONTENT_SETTLE_MS
            } else {
                YOUTUBE_CONTENT_SETTLE_MS
            }
            scheduleYoutubeContentCount(signature, state.sessionStartedAt, settleMs)
        }
    }

    private fun scheduleYoutubeContentCount(signature: String, sessionStartedAt: Long, settleMs: Long) {
        val handler = processingHandler ?: return
        cancelPendingYoutubeContentCount()
        val generation = youtubeContentGeneration
        val pending = Runnable {
            val current = observations[YOUTUBE_PACKAGE] ?: return@Runnable
            if (generation != youtubeContentGeneration ||
                current.sessionStartedAt != sessionStartedAt ||
                current.candidateSignature != signature ||
                !counts.isShortVideoActive(YOUTUBE_PACKAGE)
            ) return@Runnable

            val now = SystemClock.elapsedRealtime()
            val age = now - current.sessionStartedAt
            when {
                age < YOUTUBE_ENTRY_SETTLE_MS ||
                    current.candidateSince - current.sessionStartedAt < YOUTUBE_ENTRY_SETTLE_MS -> {
                    observations[YOUTUBE_PACKAGE] = current.copy(
                        lastVideoSignature = signature,
                        candidateSince = now,
                    )
                }
                now < current.suppressContentUntil -> {
                    observations[YOUTUBE_PACKAGE] = current.copy(
                        lastVideoSignature = signature,
                        candidateSince = now,
                    )
                }
                now - current.candidateSince < settleMs -> Unit
                else -> {
                    record(YOUTUBE_PACKAGE, signature)
                    observations[YOUTUBE_PACKAGE] = current.copy(
                        lastVideoSignature = signature,
                        candidateSince = now,
                    )
                }
            }
            pendingYoutubeContentCount = null
        }
        pendingYoutubeContentCount = pending
        handler.postDelayed(pending, settleMs)
    }

    private fun cancelPendingYoutubeContentCount() {
        youtubeContentGeneration += 1L
        pendingYoutubeContentCount?.let { processingHandler?.removeCallbacks(it) }
        pendingYoutubeContentCount = null
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
        val hasYoutubePlayer = screen.viewIds.any { id -> YOUTUBE_SHORTS_PLAYER_MARKERS.any { id.contains(it, ignoreCase = true) } } ||
            screen.classNames.any { name -> YOUTUBE_SHORTS_PLAYER_MARKERS.any { name.contains(it, ignoreCase = true) } }
        val hasShortsLabel = labels.any { label -> YOUTUBE_SHORTS_LABELS.any(label::contains) }
        val hasShortsPlayerAction = labels.any { label -> YOUTUBE_SHORTS_ACTION_MARKERS.any(label::contains) }
        val hasYoutubeShortsContext = hasYoutubePlayer || hasShortsLabel && hasShortsPlayerAction
        val hasInstagramReelsPlayer = screen.viewIds.any { id -> INSTAGRAM_REELS_PLAYER_MARKERS.any { id.contains(it, ignoreCase = true) } } ||
            screen.classNames.any { name -> INSTAGRAM_REELS_PLAYER_MARKERS.any { name.contains(it, ignoreCase = true) } }
        val onInstagramHomeOrProfile = labels.any { label -> INSTAGRAM_HOME_PROFILE_LABELS.any(label::contains) } ||
            screen.viewIds.any { id -> INSTAGRAM_HOME_PROFILE_VIEW_MARKERS.any { id.contains(it, ignoreCase = true) } }
        val hasTikTokFeedLabel = labels.any { label -> TIKTOK_FEED_MARKERS.any(label::contains) }
        val hasTikTokPlayerControl = labels.any { label -> TIKTOK_PLAYER_MARKERS.any(label::contains) }

        return when (packageName) {
            in YOUTUBE_PACKAGES -> hasYoutubeShortsContext && engagementControls >= 2
            in INSTAGRAM_PACKAGES -> hasInstagramReelsPlayer && engagementControls >= 3 && !onInstagramHomeOrProfile
            in TIKTOK_PACKAGES -> engagementControls >= 3 ||
                engagementControls >= 2 && (hasTikTokFeedLabel || hasTikTokPlayerControl || eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) ||
                engagementControls >= 1 && hasTikTokFeedLabel && hasTikTokPlayerControl
            else -> false
        }
    }

    /** Keeps the active viewer session through in-player ads without counting the ad itself. */
    private fun isShortVideoAdScreen(packageName: String, screen: ScreenSnapshot): Boolean {
        val labels = screen.labels.map { it.trim().lowercase() }
        val hasAdLabel = labels.any { label ->
            AD_LABEL_MARKERS.any { marker ->
                label == marker || label.startsWith("$marker ·") || label.startsWith("$marker:")
            }
        }
        val hasAdView = screen.viewIds.any { id ->
            AD_VIEW_ID_MARKERS.any { marker -> id.contains(marker, ignoreCase = true) }
        }
        val hasAdMarker = hasAdLabel || hasAdView
        val hasYoutubeAdCallToAction = packageName in YOUTUBE_PACKAGES && labels.any { label ->
            YOUTUBE_AD_CALL_TO_ACTIONS.any { marker ->
                label == marker || label.startsWith("$marker ") || label.startsWith("$marker ·")
            }
        }
        if (!hasAdMarker && !hasYoutubeAdCallToAction) return false

        return when (packageName) {
            // A recognized Shorts session is required by the caller. YouTube replaces its
            // Shorts player subtree with a sponsored-ad layout, so requiring player IDs here
            // would hide the count during exactly these ads.
            in YOUTUBE_PACKAGES -> hasAdMarker || hasYoutubeAdCallToAction
            in INSTAGRAM_PACKAGES -> screen.viewIds.any { id ->
                INSTAGRAM_REELS_PLAYER_MARKERS.any { id.contains(it, ignoreCase = true) }
            } || screen.classNames.any { name ->
                INSTAGRAM_REELS_PLAYER_MARKERS.any { name.contains(it, ignoreCase = true) }
            }
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
        if (depth > MAX_TREE_DEPTH || snapshot.labels.size >= MAX_LABELS || !node.isVisibleToUser) return
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

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (::counts.isInitialized) counts.setActiveShortVideoPackage(null)
        cancelPendingYoutubeContentCount()
        processingHandler = null
        processingThread?.quitSafely()
        processingThread = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (::counts.isInitialized) counts.setActiveShortVideoPackage(null)
        cancelPendingYoutubeContentCount()
        processingHandler = null
        processingThread?.quitSafely()
        processingThread = null
        super.onDestroy()
    }

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
        val sessionStartedAt: Long = 0L,
        val lastCountedScrollAt: Long = 0L,
        val suppressContentUntil: Long = 0L,
    )

    companion object {
        private val YOUTUBE_PACKAGES = setOf("com.google.android.youtube")
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private val INSTAGRAM_PACKAGES = setOf("com.instagram.android", "com.instagram.barcelona")
        private val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically", "com.zhiliaoapp.musically.go", "com.ss.android.ugc.trill",
        )
        private val SUPPORTED_PACKAGES = YOUTUBE_PACKAGES + INSTAGRAM_PACKAGES + TIKTOK_PACKAGES

        private val YOUTUBE_SHORTS_PLAYER_MARKERS = listOf(
            "reel_watch_sequence", "reel_watch_player", "reel_player", "reel_viewer",
            "shorts_player", "shorts_video_player", "shorts_player_page", "reel_player_page",
        )
        private val YOUTUBE_SHORTS_LABELS = listOf("shorts", "쇼츠", "short video")
        private val YOUTUBE_SHORTS_ACTION_MARKERS = listOf(
            "remix", "use this sound", "use this audio", "original sound", "original audio",
            "sound", "audio", "리믹스", "이 사운드 사용", "오디오 사용", "원본 오디오", "사운드", "음원",
        )
        private val AD_LABEL_MARKERS = listOf(
            "sponsored", "sponsored content", "paid promotion", "advertisement", "skip ad", "skip ads",
            "광고", "스폰서", "유료 프로모션", "광고 건너뛰기",
        )
        private val AD_VIEW_ID_MARKERS = listOf(
            "ad_badge", "ad_label", "ad_indicator", "sponsored_label", "promoted_label",
        )
        private val YOUTUBE_AD_CALL_TO_ACTIONS = listOf(
            "install", "download", "visit site", "learn more", "shop now", "sign up", "open app",
            "앱 설치", "설치", "다운로드", "사이트 방문", "자세히 알아보기", "지금 구매", "가입", "앱 열기",
        )
        private val INSTAGRAM_REELS_PLAYER_MARKERS = listOf(
            "clips_viewer", "reel_viewer", "reels_viewer",
        )
        private val INSTAGRAM_HOME_PROFILE_LABELS = listOf(
            "your story", "your stories", "add story", "create story", "followers", "edit profile",
            "view professional dashboard", "게시물", "팔로워", "스토리 추가",
        )
        private val INSTAGRAM_HOME_PROFILE_VIEW_MARKERS = listOf(
            "feed_timeline", "home_feed", "profile_header", "profile_grid", "explore_grid",
        )
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
        private const val MAX_LABELS = 400
        private const val MAX_SIGNATURE_LABELS = 10
        private const val MIN_CONTENT_LABEL_LENGTH = 5
        private const val STABLE_CONTENT_MS = 900L
        private const val SCROLL_DEBOUNCE_MS = 300L
        private const val YOUTUBE_ENTRY_SETTLE_MS = 1_000L
        private const val ENTRY_COUNT_DEBOUNCE_MS = 3_000L
        private const val YOUTUBE_SCROLL_THROTTLE_MS = 650L
        private const val YOUTUBE_CONTENT_SETTLE_MS = 450L
        private const val YOUTUBE_RECYCLER_CONTENT_SETTLE_MS = 280L
        private const val YOUTUBE_CONTENT_SCROLL_SUPPRESS_MS = 2_500L
        private const val YOUTUBE_RECYCLER_MARKER = "reel_recycler"
        private const val POST_SCROLL_SIGNATURE_GUARD_MS = 1_000L
    }
}
