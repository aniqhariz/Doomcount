package com.doomcount.app.detectors

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * TikTokDetector — experimental heuristic for detecting confirmed short-video feed transitions.
 *
 * ## Problem Addressed (Canceled Swipe False Positives):
 * Previous iterations incremented the transition count on the LEADING EDGE of a scroll burst
 * (the very first scroll event). When a user initiated a vertical swipe but released early or
 * dragged back, TikTok snapped back to the original video. Because scroll events had occurred,
 * the detector falsely counted a transition.
 *
 * ## Solution: Two-Phase Confirmation Architecture
 * 1. Phase 1 (Gesture Inception): A vertical scroll burst initiates a [DetectionResult.TransitionCandidate].
 *    The transition is NOT counted yet. Motion metrics (forward vs. reverse displacement, deltas)
 *    and feed content changes are recorded during the gesture.
 * 2. Phase 2 (Settlement Evaluation): Once the gesture settles (quiescence window of [SETTLEMENT_WINDOW_MS]):
 *    - If snap-back reversal occurred (user pulled forward, then released and pager snapped back in
 *      the opposite direction): CANCELED.
 *    - If net vertical displacement is insufficient to cross the page boundary: CANCELED.
 *    - If horizontal displacement dominates: REJECTED.
 *    - If no content changes or index changes occurred: CANCELED.
 *    - Only if unidirectional displacement, feed content updates, and no snap-back are confirmed:
 *      [DetectionResult.TransitionConfirmed] is emitted and the counter increments exactly once.
 */
class TikTokDetector {

    companion object {
        val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill"
        )

        /**
         * Quiescence window (in ms) between scroll events to consider them part of the same gesture.
         */
        const val BURST_QUIESCENCE_MS = 400L

        /**
         * Settling window (in ms) of inactivity after the last scroll event before evaluating
         * whether the candidate gesture successfully completed or was canceled.
         */
        const val SETTLEMENT_WINDOW_MS = 450L

        /**
         * Minimum interval (in ms) between two distinct confirmed video transitions.
         */
        const val MIN_TRANSITION_INTERVAL_MS = 600L

        /**
         * Minimum net vertical displacement (in pixels) required to confirm a page turn
         * when scroll deltas are available. On a 1080x2400 screen, crossing the feed boundary
         * typically requires substantial net vertical travel.
         */
        const val MIN_NET_VERTICAL_DISPLACEMENT = 120

        /**
         * Ratio threshold for detecting snap-back reversal.
         * If reverse displacement exceeds 35% of forward displacement and is at least 60px,
         * the view snapped back toward or to the original video.
         */
        const val REVERSAL_RATIO_THRESHOLD = 0.35f

        /**
         * Substrings in class names indicating non-feed screens or overlay dialogs.
         */
        private val NON_FEED_CLASS_HINTS = setOf(
            "comment",
            "Comment",
            "profile",
            "Profile",
            "search",
            "Search",
            "setting",
            "Setting",
            "inbox",
            "Inbox",
            "discover",
            "Discover",
            "login",
            "Login",
            "share",
            "Share",
            "dialog",
            "Dialog",
            "bottomsheet",
            "BottomSheet"
        )
    }

    /**
     * Internal state model for an active scroll burst being evaluated.
     */
    data class CandidateBurst(
        val burstId: Long,
        val startTimeMs: Long,
        var lastEventTimeMs: Long,
        val containerClassName: String,
        var scrollEventCount: Int = 1,
        var initialDirection: Int = 0, // +1 for positive dy, -1 for negative dy, 0 for unknown
        var forwardDisplacement: Int = 0,
        var reverseDisplacement: Int = 0,
        var accumulatedDx: Int = 0,
        var startFromIndex: Int = -1,
        var lastFromIndex: Int = -1,
        var startToIndex: Int = -1,
        var lastToIndex: Int = -1,
        var itemCount: Int = -1,
        var contentChangeCount: Int = 0,
        var hasSubtreeContentChange: Boolean = false,
        var isReversalDetected: Boolean = false,
        var isSettled: Boolean = false
    ) {
        val netDisplacement: Int get() = forwardDisplacement - reverseDisplacement
        val grossDisplacement: Int get() = forwardDisplacement + reverseDisplacement
    }

    // Active burst and candidate tracking
    private var activeCandidate: CandidateBurst? = null
    private var nextBurstId: Long = 1L
    private var lastScrollEventMs: Long = 0L
    private var lastConfirmedTransitionMs: Long = 0L

    // Screen state tracking
    private var currentScreenClassName: String = ""
    private var isInNonFeedScreen: Boolean = false

    // Diagnostic summaries
    var lastBurstSummary: String = "No scroll bursts recorded yet"
        private set
    var lastDecisionSummary: String = "Idle — no decisions made yet"
        private set

    /**
     * Checks if there is an active candidate burst awaiting settlement evaluation.
     */
    fun hasActivePendingCandidate(): Boolean {
        return activeCandidate?.let { !it.isSettled } ?: false
    }

    /**
     * Returns the burst ID of the currently pending candidate, or 0 if none.
     */
    fun getActiveBurstId(): Long {
        return activeCandidate?.burstId ?: 0L
    }

    /**
     * Returns milliseconds elapsed since the last scroll event in the active burst.
     */
    fun getTimeSinceLastScroll(now: Long = System.currentTimeMillis()): Long {
        return if (lastScrollEventMs > 0L) now - lastScrollEventMs else Long.MAX_VALUE
    }

    /**
     * Evaluates an incoming accessibility event from TikTok.
     */
    fun evaluate(event: AccessibilityEvent): DetectionResult {
        val pkg = event.packageName?.toString() ?: return DetectionResult.NoSignal
        if (pkg !in TIKTOK_PACKAGES) {
            return DetectionResult.NoSignal
        }

        val now = System.currentTimeMillis()

        return when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                evaluateWindowStateChanged(event)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                evaluateScrollEvent(event, now)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                evaluateContentChanged(event, now)
            }

            else -> DetectionResult.NoSignal
        }
    }

    private fun evaluateWindowStateChanged(event: AccessibilityEvent): DetectionResult {
        val className = event.className?.toString() ?: ""
        currentScreenClassName = className

        val isNonFeed = NON_FEED_CLASS_HINTS.any { hint ->
            className.contains(hint, ignoreCase = true)
        }
        isInNonFeedScreen = isNonFeed

        if (isNonFeed) {
            // If user opened comments/profile/search mid-gesture, cancel active candidate immediately
            activeCandidate?.let { candidate ->
                if (!candidate.isSettled) {
                    candidate.isSettled = true
                    lastDecisionSummary = "Burst #${candidate.burstId} CANCELED: User opened non-feed screen ($className)"
                    return DetectionResult.TransitionCanceled(
                        burstId = candidate.burstId,
                        reason = "Navigated away from feed to $className"
                    )
                }
            }
            return DetectionResult.RejectedSignal("Active screen is non-feed: $className")
        }

        return DetectionResult.NoSignal
    }

    private fun evaluateScrollEvent(event: AccessibilityEvent, now: Long): DetectionResult {
        val className = event.className?.toString() ?: ""

        val (dx, dy) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Pair(event.scrollDeltaX, event.scrollDeltaY)
        } else {
            Pair(0, 0)
        }

        // Screen-level check
        if (isInNonFeedScreen) {
            return DetectionResult.RejectedSignal("In non-feed screen: $currentScreenClassName")
        }

        // Container-level check
        if (NON_FEED_CLASS_HINTS.any { className.contains(it, ignoreCase = true) }) {
            return DetectionResult.RejectedSignal("Scroll container is non-feed: $className")
        }

        val candidate = activeCandidate

        // Check if this scroll event belongs to the active burst
        if (candidate != null && !candidate.isSettled && (now - candidate.lastEventTimeMs) < BURST_QUIESCENCE_MS) {
            // Absorbed into current burst
            candidate.scrollEventCount++
            candidate.lastEventTimeMs = now
            lastScrollEventMs = now

            candidate.accumulatedDx += abs(dx)

            // Track motion direction and snap-back reversal
            if (candidate.initialDirection == 0 && dy != 0) {
                candidate.initialDirection = if (dy > 0) 1 else -1
                candidate.forwardDisplacement += abs(dy)
            } else if (candidate.initialDirection != 0 && dy != 0) {
                val currentDir = if (dy > 0) 1 else -1
                if (currentDir == candidate.initialDirection) {
                    candidate.forwardDisplacement += abs(dy)
                } else {
                    candidate.reverseDisplacement += abs(dy)
                    if (candidate.reverseDisplacement >= 50) {
                        candidate.isReversalDetected = true
                    }
                }
            }

            // Update item index if reported by the container
            if (event.fromIndex >= 0) candidate.lastFromIndex = event.fromIndex
            if (event.toIndex >= 0) candidate.lastToIndex = event.toIndex
            if (event.itemCount >= 0) candidate.itemCount = event.itemCount

            val duration = now - candidate.startTimeMs
            lastBurstSummary = "Burst #${candidate.burstId}: ${candidate.scrollEventCount} scrolls over ${duration}ms (fwd=${candidate.forwardDisplacement}px, rev=${candidate.reverseDisplacement}px, net=${candidate.netDisplacement}px)"

            return DetectionResult.AbsorbedInBurst(
                burstId = candidate.burstId,
                eventIndex = candidate.scrollEventCount
            )
        }

        // If a previous candidate exists and hasn't been settled yet, settle it before starting a new burst
        var priorSettlementResult: DetectionResult? = null
        if (candidate != null && !candidate.isSettled) {
            priorSettlementResult = settleCandidate(candidate, now)
        }

        // Check horizontal swipe on burst inception
        if (abs(dx) > abs(dy) * 1.5 && abs(dx) > 60) {
            lastBurstSummary = "Burst #$nextBurstId [REJECTED]: Horizontal swipe (dx=$dx, dy=$dy) in $className"
            return priorSettlementResult ?: DetectionResult.RejectedSignal("Horizontal scroll detected: dx=$dx, dy=$dy")
        }

        // Check transition cadence debounce
        val timeSinceLastTransition = now - lastConfirmedTransitionMs
        if (timeSinceLastTransition < MIN_TRANSITION_INTERVAL_MS && lastConfirmedTransitionMs > 0) {
            lastBurstSummary = "Burst #$nextBurstId [DEBOUNCED]: Rapid swipe ${timeSinceLastTransition}ms after previous transition"
            return priorSettlementResult ?: DetectionResult.AbsorbedInBurst(
                burstId = nextBurstId,
                eventIndex = 1
            )
        }

        // Start a NEW Candidate Burst!
        val burstId = nextBurstId++
        val initDir = if (dy != 0) (if (dy > 0) 1 else -1) else 0
        val newCandidate = CandidateBurst(
            burstId = burstId,
            startTimeMs = now,
            lastEventTimeMs = now,
            containerClassName = className,
            scrollEventCount = 1,
            initialDirection = initDir,
            forwardDisplacement = abs(dy),
            reverseDisplacement = 0,
            accumulatedDx = abs(dx),
            startFromIndex = event.fromIndex,
            lastFromIndex = event.fromIndex,
            startToIndex = event.toIndex,
            lastToIndex = event.toIndex,
            itemCount = event.itemCount
        )

        activeCandidate = newCandidate
        lastScrollEventMs = now

        lastBurstSummary = "Burst #$burstId [CANDIDATE]: Gesture started in $className (dy=$dy)"
        lastDecisionSummary = "Burst #$burstId: Candidate created — gathering evidence..."

        // Return prior settlement if it had an outcome, otherwise return the new Candidate
        return priorSettlementResult ?: DetectionResult.TransitionCandidate(
            burstId = burstId,
            reason = "Scroll burst #$burstId started after quiescence in $className",
            className = className,
            confidence = 0.5f
        )
    }

    private fun evaluateContentChanged(event: AccessibilityEvent, now: Long): DetectionResult {
        activeCandidate?.let { candidate ->
            if (!candidate.isSettled) {
                candidate.contentChangeCount++
                if ((event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) != 0) {
                    candidate.hasSubtreeContentChange = true
                }
            }
        }
        return DetectionResult.NoSignal
    }

    /**
     * Settles the currently active candidate burst if one exists and is unsettled.
     * Called when the settling quiescence window expires or when a new burst begins.
     */
    fun settleActiveCandidate(now: Long = System.currentTimeMillis()): DetectionResult {
        val candidate = activeCandidate ?: return DetectionResult.NoSignal
        if (candidate.isSettled) return DetectionResult.NoSignal
        return settleCandidate(candidate, now)
    }

    private fun settleCandidate(candidate: CandidateBurst, now: Long): DetectionResult {
        candidate.isSettled = true
        val duration = candidate.lastEventTimeMs - candidate.startTimeMs

        // ── Rule 1: Snap-back reversal (Canceled Swipe) ──────────────────────────
        // If the gesture moved forward and then snapped back in the opposite direction
        val hasReversal = candidate.isReversalDetected &&
                candidate.reverseDisplacement >= 60 &&
                candidate.reverseDisplacement >= (candidate.forwardDisplacement * REVERSAL_RATIO_THRESHOLD)

        if (hasReversal) {
            val reason = "Canceled gesture: Snap-back reversal detected (fwd=${candidate.forwardDisplacement}px, rev=${candidate.reverseDisplacement}px, net=${candidate.netDisplacement}px)"
            lastDecisionSummary = "Burst #${candidate.burstId} CANCELED: Snap-back (net=${candidate.netDisplacement}px)"
            return DetectionResult.TransitionCanceled(
                burstId = candidate.burstId,
                reason = reason
            )
        }

        // ── Rule 2: Insufficient net vertical displacement ───────────────────────
        // If deltas are available and net displacement didn't cross threshold
        if (candidate.grossDisplacement > 0 && abs(candidate.netDisplacement) < MIN_NET_VERTICAL_DISPLACEMENT) {
            // Exception: if the container index explicitly advanced to a new item
            val indexAdvanced = candidate.startFromIndex >= 0 &&
                    candidate.lastFromIndex >= 0 &&
                    candidate.lastFromIndex != candidate.startFromIndex

            if (!indexAdvanced) {
                val reason = "Canceled gesture: Insufficient net vertical displacement (${candidate.netDisplacement}px < ${MIN_NET_VERTICAL_DISPLACEMENT}px)"
                lastDecisionSummary = "Burst #${candidate.burstId} CANCELED: Travel too small (${candidate.netDisplacement}px)"
                return DetectionResult.TransitionCanceled(
                    burstId = candidate.burstId,
                    reason = reason
                )
            }
        }

        // ── Rule 3: Horizontal or diagonal abort ─────────────────────────────────
        if (candidate.accumulatedDx > abs(candidate.netDisplacement) * 1.3 && candidate.accumulatedDx > 80) {
            val reason = "Rejected: Horizontal displacement dominated (dx=${candidate.accumulatedDx}px, netDy=${candidate.netDisplacement}px)"
            lastDecisionSummary = "Burst #${candidate.burstId} REJECTED: Horizontal motion"
            return DetectionResult.TransitionCanceled(
                burstId = candidate.burstId,
                reason = reason
            )
        }

        // ── Rule 4: Zero feed content updates & index unchanged ──────────────────
        // An advancing video triggers player UI & view subtree updates; an inert/canceled touch does not
        if (candidate.contentChangeCount == 0 && candidate.startFromIndex == candidate.lastFromIndex) {
            val reason = "Canceled gesture: No content updates or index change recorded"
            lastDecisionSummary = "Burst #${candidate.burstId} CANCELED: No feed content updates"
            return DetectionResult.TransitionCanceled(
                burstId = candidate.burstId,
                reason = reason
            )
        }

        // ── All cancellation checks passed: Transition CONFIRMED! ────────────────
        lastConfirmedTransitionMs = now

        val evidence = buildString {
            append("NetDy=${candidate.netDisplacement}px (fwd=${candidate.forwardDisplacement}, rev=${candidate.reverseDisplacement}), ")
            append("duration=${duration}ms, ")
            append("scrolls=${candidate.scrollEventCount}, ")
            append("contentChanges=${candidate.contentChangeCount}")
            if (candidate.lastFromIndex >= 0) {
                append(", index=${candidate.lastFromIndex}")
            }
        }

        val reason = "Feed advancement confirmed for burst #${candidate.burstId} ($evidence)"
        lastDecisionSummary = "Burst #${candidate.burstId} CONFIRMED: Feed advanced (net=${candidate.netDisplacement}px, changes=${candidate.contentChangeCount})"

        return DetectionResult.TransitionConfirmed(
            burstId = candidate.burstId,
            reason = reason,
            evidence = evidence
        )
    }

    /**
     * Resets detector state when user leaves TikTok or clears session.
     */
    fun reset() {
        activeCandidate?.isSettled = true
        activeCandidate = null
        lastScrollEventMs = 0L
        lastConfirmedTransitionMs = 0L
        currentScreenClassName = ""
        isInNonFeedScreen = false
        lastBurstSummary = "Reset / Idle"
        lastDecisionSummary = "Reset / Idle"
    }
}
