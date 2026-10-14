package com.doomcount.app.detectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic unit tests for [TikTokDetector] using synthetic event sequences.
 *
 * NOTE: These synthetic event sequences model the logical event contracts received
 * from Android AccessibilityService, not guaranteed physical device captures.
 */
class TikTokDetectorTest {

    private lateinit var harness: DetectorTestHarness

    @Before
    fun setUp() {
        harness = DetectorTestHarness()
    }

    /**
     * Requirement 1: One confirmed transition produces exactly one count.
     */
    @Test
    fun testOneConfirmedTransitionProducesExactlyOneCount() {
        val t0 = 1_000L

        // Inception scroll event
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t0,
                scrollDeltaY = 250,
                fromIndex = 0,
                toIndex = 0
            )
        )

        // Continuation scroll event advancing index to 1
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t0 + 100,
                scrollDeltaY = 300,
                fromIndex = 1,
                toIndex = 1
            )
        )

        // Corroborating content update
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_WINDOW_CONTENT_CHANGED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t0 + 150,
                contentChangeTypes = FeedEvent.CONTENT_CHANGE_TYPE_SUBTREE
            )
        )

        // Settle after quiescence window (450ms)
        harness.settleAt(t0 + 600)

        assertEquals("Confirmed transition count should be exactly 1", 1, harness.confirmedTransitions)
        assertEquals("Candidate burst count should be 1", 1, harness.candidateGestures)
        assertEquals("Canceled gesture count should be 0", 0, harness.canceledGestures)
    }

    /**
     * Requirement 2: Multiple accessibility events associated with one transition produce at most one count.
     */
    @Test
    fun testMultipleAccessibilityEventsAssociatedWithOneTransitionProduceAtMostOneCount() {
        val t0 = 1_000L

        // Inception
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t0,
                scrollDeltaY = 150,
                fromIndex = 0,
                toIndex = 0
            )
        )

        // 8 rapid continuation scroll events
        for (i in 1..8) {
            harness.process(
                FeedEvent(
                    eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                    packageName = "com.zhiliaoapp.musically",
                    className = "androidx.viewpager2.widget.ViewPager2",
                    eventTime = t0 + (i * 20),
                    scrollDeltaY = 80,
                    fromIndex = if (i >= 5) 1 else 0,
                    toIndex = if (i >= 5) 1 else 0
                )
            )
        }

        // 20 rapid content updates
        for (i in 1..20) {
            harness.process(
                FeedEvent(
                    eventType = FeedEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    packageName = "com.zhiliaoapp.musically",
                    className = "androidx.viewpager2.widget.ViewPager2",
                    eventTime = t0 + 160 + (i * 5),
                    contentChangeTypes = FeedEvent.CONTENT_CHANGE_TYPE_SUBTREE
                )
            )
        }

        // Settle
        harness.settleAt(t0 + 800)

        // Post-settlement delayed content updates
        for (i in 1..5) {
            harness.process(
                FeedEvent(
                    eventType = FeedEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    packageName = "com.zhiliaoapp.musically",
                    className = "androidx.viewpager2.widget.ViewPager2",
                    eventTime = t0 + 850 + (i * 30),
                    contentChangeTypes = FeedEvent.CONTENT_CHANGE_TYPE_SUBTREE
                )
            )
        }

        assertEquals("Multiple events for one transition must produce exactly 1 count", 1, harness.confirmedTransitions)
        assertEquals("Candidate gestures initiated should be 1", 1, harness.candidateGestures)
        assertTrue("Subsequent scroll events should have been absorbed", harness.absorbedEvents >= 8)
    }

    /**
     * Requirement 3: An idle event sequence produces zero counts.
     */
    @Test
    fun testIdleEventSequenceProducesZeroCounts() {
        val t0 = 1_000L

        // Idle video playback emitting only periodic content updates (video progress/buffering)
        for (i in 1..15) {
            harness.process(
                FeedEvent(
                    eventType = FeedEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    packageName = "com.zhiliaoapp.musically",
                    className = "com.ss.android.ugc.aweme.feed.ui.FeedFragment",
                    eventTime = t0 + (i * 1_000),
                    contentChangeTypes = FeedEvent.CONTENT_CHANGE_TYPE_SUBTREE
                )
            )
        }

        harness.settleAt(t0 + 20_000)

        assertEquals("Idle playback must produce 0 confirmed transitions", 0, harness.confirmedTransitions)
        assertEquals("Idle playback must produce 0 candidate gestures", 0, harness.candidateGestures)
    }

    /**
     * Requirement 4: A canceled gesture produces zero counts when the available signals establish that no transition occurred.
     */
    @Test
    fun testCanceledGesture_whenIndexUnchanged_producesZeroCounts() {
        val t0 = 1_000L

        // User pulls up by 250px (exceeding displacement threshold)
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t0,
                scrollDeltaY = 250,
                fromIndex = 3,
                toIndex = 3
            )
        )

        // Content updates during drag (e.g. video pause UI)
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_WINDOW_CONTENT_CHANGED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t0 + 100,
                contentChangeTypes = FeedEvent.CONTENT_CHANGE_TYPE_SUBTREE
            )
        )

        // User releases without crossing threshold; feed remains on item 3
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t0 + 150,
                scrollDeltaY = 20,
                fromIndex = 3,
                toIndex = 3
            )
        )

        harness.settleAt(t0 + 650)

        assertEquals("Canceled gesture with unchanged index must NOT increment count", 0, harness.confirmedTransitions)
        assertEquals("Canceled gesture count should increment", 1, harness.canceledGestures)
    }

    @Test
    fun testCanceledGesture_whenSnapBackReversalOccurs_producesZeroCounts() {
        val t0 = 1_000L

        // Pull forward 220px (without index data available: fromIndex = -1)
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.recyclerview.widget.RecyclerView",
                eventTime = t0,
                scrollDeltaY = 220,
                fromIndex = -1,
                toIndex = -1
            )
        )

        // Snap-back reversal of 180px in opposite direction
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.recyclerview.widget.RecyclerView",
                eventTime = t0 + 120,
                scrollDeltaY = -180,
                fromIndex = -1,
                toIndex = -1
            )
        )

        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_WINDOW_CONTENT_CHANGED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.recyclerview.widget.RecyclerView",
                eventTime = t0 + 150,
                contentChangeTypes = FeedEvent.CONTENT_CHANGE_TYPE_SUBTREE
            )
        )

        harness.settleAt(t0 + 650)

        assertEquals("Snap-back reversal must NOT increment transition count", 0, harness.confirmedTransitions)
        assertEquals("Canceled gesture count should increment", 1, harness.canceledGestures)
    }

    @Test
    fun testCanceledGesture_whenDisplacementInsufficient_producesZeroCounts() {
        val t0 = 1_000L

        // Small twitch of 60px (under 150px threshold, no index data)
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.recyclerview.widget.RecyclerView",
                eventTime = t0,
                scrollDeltaY = 60,
                fromIndex = -1,
                toIndex = -1
            )
        )

        harness.settleAt(t0 + 600)

        assertEquals("Insufficient displacement must NOT increment transition count", 0, harness.confirmedTransitions)
        assertEquals("Canceled gesture count should increment", 1, harness.canceledGestures)
    }

    @Test
    fun testCanceledGesture_whenNoContentChanges_producesZeroCounts() {
        val t0 = 1_000L

        // Scroll event occurred without index and without any content changes
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.recyclerview.widget.RecyclerView",
                eventTime = t0,
                scrollDeltaY = 220,
                fromIndex = -1,
                toIndex = -1
            )
        )

        harness.settleAt(t0 + 600)

        assertEquals("Scroll without content changes must NOT increment count", 0, harness.confirmedTransitions)
        assertEquals("Canceled gesture count should increment", 1, harness.canceledGestures)
    }

    /**
     * Requirement 5: Opening comments or a profile does not count as a feed transition when distinguishable.
     */
    @Test
    fun testOpeningCommentsDoesNotCountAsFeedTransition() {
        val t0 = 1_000L

        // Window state changes to comments fragment
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = "com.zhiliaoapp.musically",
                className = "com.ss.android.ugc.aweme.comment.ui.CommentListFragment",
                eventTime = t0
            )
        )

        // Scrolling inside the comments list
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "com.ss.android.ugc.aweme.comment.widget.CommentRecyclerView",
                eventTime = t0 + 200,
                scrollDeltaY = 400
            )
        )

        harness.settleAt(t0 + 800)

        assertEquals("Comments scrolling must produce 0 transitions", 0, harness.confirmedTransitions)
        assertEquals("Candidate gestures must be 0 for comment screens", 0, harness.candidateGestures)
    }

    @Test
    fun testOpeningCommentsMidGestureCancelsCandidate() {
        val t0 = 1_000L

        // Inception of scroll in feed
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t0,
                scrollDeltaY = 150
            )
        )
        assertEquals("Candidate should be created", 1, harness.candidateGestures)

        // Mid-gesture comment sheet opened
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = "com.zhiliaoapp.musically",
                className = "com.ss.android.ugc.aweme.comment.ui.CommentListFragment",
                eventTime = t0 + 100
            )
        )

        assertEquals("Opening comments mid-gesture must cancel the candidate", 1, harness.canceledGestures)
        assertEquals("No transitions should be confirmed", 0, harness.confirmedTransitions)
    }

    @Test
    fun testHorizontalSwipeToProfileIsRejected() {
        val t0 = 1_000L

        // Swipe left to open profile (dx dominates dy)
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t0,
                scrollDeltaX = 250,
                scrollDeltaY = 15
            )
        )

        harness.settleAt(t0 + 600)

        assertEquals("Horizontal swipe to profile must NOT count as feed transition", 0, harness.confirmedTransitions)
        assertEquals("Candidate count should be 0", 0, harness.candidateGestures)
    }

    /**
     * Requirement 6: Events from non-target packages are ignored.
     */
    @Test
    fun testEventsFromNonTargetPackagesAreIgnored() {
        val t0 = 1_000L

        // Instagram Reels events
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.instagram.android",
                className = "androidx.recyclerview.widget.RecyclerView",
                eventTime = t0,
                scrollDeltaY = 400
            )
        )
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_WINDOW_CONTENT_CHANGED,
                packageName = "com.instagram.android",
                className = "androidx.recyclerview.widget.RecyclerView",
                eventTime = t0 + 50,
                contentChangeTypes = FeedEvent.CONTENT_CHANGE_TYPE_SUBTREE
            )
        )

        // YouTube Shorts events
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.google.android.apps.youtube.mandolin",
                className = "android.support.v7.widget.RecyclerView",
                eventTime = t0 + 200,
                scrollDeltaY = 500
            )
        )

        harness.settleAt(t0 + 800)

        assertEquals("Non-target packages must produce 0 confirmed transitions", 0, harness.confirmedTransitions)
        assertEquals("Non-target packages must produce 0 candidates", 0, harness.candidateGestures)
    }

    /**
     * Requirement 7: Two distinct successful transitions are counted separately.
     */
    @Test
    fun testTwoDistinctSuccessfulTransitionsAreCountedSeparately() {
        var t = 1_000L

        // ── Transition 1: Swipe from video 0 to video 1 ──
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t,
                scrollDeltaY = 250,
                fromIndex = 0,
                toIndex = 0
            )
        )
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t + 100,
                scrollDeltaY = 300,
                fromIndex = 1,
                toIndex = 1
            )
        )
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_WINDOW_CONTENT_CHANGED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t + 120,
                contentChangeTypes = FeedEvent.CONTENT_CHANGE_TYPE_SUBTREE
            )
        )
        t += 600
        harness.settleAt(t)
        assertEquals("First transition confirmed", 1, harness.confirmedTransitions)

        // User watches video 1 for 2 seconds
        t += 2_000

        // ── Transition 2: Swipe from video 1 to video 2 ──
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t,
                scrollDeltaY = 280,
                fromIndex = 1,
                toIndex = 1
            )
        )
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t + 100,
                scrollDeltaY = 320,
                fromIndex = 2,
                toIndex = 2
            )
        )
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_WINDOW_CONTENT_CHANGED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t + 120,
                contentChangeTypes = FeedEvent.CONTENT_CHANGE_TYPE_SUBTREE
            )
        )
        t += 600
        harness.settleAt(t)

        assertEquals("Both distinct transitions must be counted separately", 2, harness.confirmedTransitions)
        assertEquals("Candidate count should be 2", 2, harness.candidateGestures)
    }

    /**
     * Requirement 8: A delayed content update does not create duplicate counts.
     */
    @Test
    fun testDelayedContentUpdateDoesNotCreateDuplicateCounts() {
        val t0 = 1_000L

        // Perform successful transition
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t0,
                scrollDeltaY = 300,
                fromIndex = 0,
                toIndex = 0
            )
        )
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t0 + 100,
                scrollDeltaY = 250,
                fromIndex = 1,
                toIndex = 1
            )
        )
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_WINDOW_CONTENT_CHANGED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t0 + 150,
                contentChangeTypes = FeedEvent.CONTENT_CHANGE_TYPE_SUBTREE
            )
        )

        // Settle at t0 + 600
        harness.settleAt(t0 + 600)
        assertEquals("Transition confirmed at settlement", 1, harness.confirmedTransitions)

        // Delayed content updates arrive 200ms, 500ms, and 1000ms after settlement
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_WINDOW_CONTENT_CHANGED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t0 + 800,
                contentChangeTypes = FeedEvent.CONTENT_CHANGE_TYPE_SUBTREE
            )
        )
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_WINDOW_CONTENT_CHANGED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t0 + 1_100,
                contentChangeTypes = FeedEvent.CONTENT_CHANGE_TYPE_SUBTREE
            )
        )
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_WINDOW_CONTENT_CHANGED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t0 + 1_600,
                contentChangeTypes = FeedEvent.CONTENT_CHANGE_TYPE_SUBTREE
            )
        )

        harness.settleAt(t0 + 2_100)

        assertEquals("Delayed content updates must NOT create duplicate counts", 1, harness.confirmedTransitions)
    }

    /**
     * Requirement 9: Reverse navigation is handled consistently with the existing counting definition.
     */
    @Test
    fun testReverseNavigationIsHandledConsistently() {
        var t = 1_000L

        // Reverse swipe from video index 5 back to video index 4 (negative dy)
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t,
                scrollDeltaY = -250,
                fromIndex = 5,
                toIndex = 5
            )
        )
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t + 100,
                scrollDeltaY = -280,
                fromIndex = 4,
                toIndex = 4
            )
        )
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_WINDOW_CONTENT_CHANGED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t + 150,
                contentChangeTypes = FeedEvent.CONTENT_CHANGE_TYPE_SUBTREE
            )
        )

        t += 600
        harness.settleAt(t)

        assertEquals("Reverse swipe to a previous video must be counted as a transition", 1, harness.confirmedTransitions)

        // Canceled reverse swipe: user drags down toward video 3, but snaps back to video 4
        t += 2_000
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t,
                scrollDeltaY = -180,
                fromIndex = 4,
                toIndex = 4
            )
        )
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t + 100,
                scrollDeltaY = -30,
                fromIndex = 4,
                toIndex = 4
            )
        )
        harness.process(
            FeedEvent(
                eventType = FeedEvent.TYPE_WINDOW_CONTENT_CHANGED,
                packageName = "com.zhiliaoapp.musically",
                className = "androidx.viewpager2.widget.ViewPager2",
                eventTime = t + 120,
                contentChangeTypes = FeedEvent.CONTENT_CHANGE_TYPE_SUBTREE
            )
        )

        t += 600
        harness.settleAt(t)

        assertEquals("Canceled reverse swipe must NOT increment count", 1, harness.confirmedTransitions)
        assertEquals("Canceled reverse swipe should increment canceled count", 1, harness.canceledGestures)
    }

    /**
     * Test harness simulating the DoomAccessibilityService lifecycle and settlement dispatch.
     */
    private class DetectorTestHarness {
        val detector = TikTokDetector()
        var confirmedTransitions = 0
        var canceledGestures = 0
        var candidateGestures = 0
        var absorbedEvents = 0
        var rejectedBursts = 0

        fun process(event: FeedEvent) {
            val pkg = event.packageName
            if (pkg == null || pkg !in TikTokDetector.TIKTOK_PACKAGES) {
                return
            }

            // Settle previous candidate if quiescence exceeded before new scroll event
            if (event.eventType == FeedEvent.TYPE_VIEW_SCROLLED && detector.hasActivePendingCandidate()) {
                val timeSinceLast = detector.getTimeSinceLastScroll(event.eventTime)
                if (timeSinceLast >= TikTokDetector.BURST_QUIESCENCE_MS) {
                    val priorSettlement = detector.settleActiveCandidate(event.eventTime)
                    handleResult(priorSettlement)
                }
            }

            val result = detector.evaluate(event)
            handleResult(result)
        }

        fun settleAt(timeMs: Long) {
            if (detector.hasActivePendingCandidate()) {
                val result = detector.settleActiveCandidate(timeMs)
                handleResult(result)
            }
        }

        private fun handleResult(result: DetectionResult) {
            when (result) {
                is DetectionResult.TransitionConfirmed -> confirmedTransitions++
                is DetectionResult.TransitionCanceled -> canceledGestures++
                is DetectionResult.TransitionCandidate -> candidateGestures++
                is DetectionResult.AbsorbedInBurst -> absorbedEvents++
                is DetectionResult.RejectedSignal -> rejectedBursts++
                else -> {}
            }
        }
    }
}

