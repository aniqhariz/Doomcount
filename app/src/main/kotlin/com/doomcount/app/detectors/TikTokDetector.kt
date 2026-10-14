package com.doomcount.app.detectors

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * TikTokDetector — transition detector for TikTok feeds via accessibility events.
 *
 * ## Problem Addressed (Canceled Swipe False Positives):
 * Previous iterations incremented the transition count prematurely or failed to reject
 * canceled swipes because:
 * 1. Container item index (`fromIndex`) was ignored whenever vertical displacement exceeded
 *    the arbitrary 120px threshold, confirming transitions even when the pager snapped back
 *    to the exact same video item.
 * 2. Background UI updates (`TYPE_WINDOW_CONTENT_CHANGED`) emitted when touching the screen
 *    or pausing playback were falsely treated as proof that a new video loaded.
 * 3. Smooth animator snap-backs often omitted reverse scroll events, evading the reversal check.
 * 4. Settlement timestamp recording used wall-clock `now` at settlement time rather than
 *    the gesture's completion timestamp, causing cadence debouncing distortions.
 *
 * ## Solution: Two-Phase Confirmation Architecture
 * 1. Phase 1 (Gesture Inception): A vertical scroll initiates a [DetectionResult.TransitionCandidate].
 *    The transition is NOT counted yet. Motion metrics (forward vs. reverse displacement, deltas)
 *    and feed content changes are recorded during the gesture.
 * 2. Phase 2 (Settlement Evaluation): Once the gesture settles (quiescence window of [SETTLEMENT_WINDOW_MS]):
 *    - In non-feed screen (comments, profile, search, dialogs): CANCELED / REJECTED.
 *    - If horizontal displacement dominates: REJECTED.
 *    - Container item index evidence (when available):
 *        * If start index == last index: definitively CANCELED (feed remained on the same video).
 *        * If start index != last index: CONFIRMED (feed advanced forward or reversed to another video).
 *    - Motion heuristics (when container index is unavailable):
 *        * Snap-back reversal detected: CANCELED.
 *        * Net vertical displacement insufficient: CANCELED.
 *        * Zero feed content updates: CANCELED.
 *        * Net displacement dominated by unidirectional travel with content updates: CONFIRMED.
 *    - Counter increments exactly once on [DetectionResult.TransitionConfirmed].
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
         * Minimum interval (in ms) between the conclusion of one transition and the start
         * of another distinct transition to prevent duplicate bursts from the same swipe.
         */
        const val MIN_TRANSITION_INTERVAL_MS = 350L

        /**
         * Minimum net vertical displacement (in pixels) required to confirm a page turn
         * when scroll deltas are available and container indices are not reported.
         */
        const val MIN_NET_VERTICAL_DISPLACEMENT = 150

        /**
         * Ratio threshold for detecting snap-back reversal.
         * If reverse displacement exceeds 35% of forward displacement and is at least 60px,
         * or if net displacement is less than 50% of gross displacement with >= 40px reversal,
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
     * Android AccessibilityService entry point. Safely extracts event metadata
     * and delegates to the platform-independent [evaluate(FeedEvent)].
     */
    fun evaluate(event: AccessibilityEvent): DetectionResult {
        val deltaX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            event.scrollDeltaX
        } else {
            0
        }
        val deltaY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            event.scrollDeltaY
        } else {
            0
        }
        val changeTypes = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            event.contentChangeTypes
        } else {
            0
        }

        return evaluate(
            FeedEvent(
                eventType = event.eventType,
                packageName = event.packageName?.toString(),
                className = event.className?.toString(),
                eventTime = event.eventTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
                scrollDeltaX = deltaX,
                scrollDeltaY = deltaY,
                fromIndex = event.fromIndex,
                toIndex = event.toIndex,
                itemCount = event.itemCount,
                contentChangeTypes = changeTypes
            )
        )
    }

    /**
     * Evaluates a platform-independent [FeedEvent].
     * Isolated from Android-specific APIs for deterministic JVM unit testing.
     */
    fun evaluate(event: FeedEvent): DetectionResult {
        val pkg = event.packageName ?: return DetectionResult.NoSignal
        if (pkg !in TIKTOK_PACKAGES) {
            return DetectionResult.NoSignal
        }

        val eventTime = event.eventTime

        return when (event.eventType) {
            FeedEvent.TYPE_WINDOW_STATE_CHANGED -> {
                evaluateWindowStateChanged(event)
            }

            FeedEvent.TYPE_VIEW_SCROLLED -> {
                evaluateScrollEvent(event, eventTime)
            }

            FeedEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                evaluateContentChanged(event)
            }

            else -> DetectionResult.NoSignal
        }
    }

    private fun evaluateWindowStateChanged(event: FeedEvent): DetectionResult {
        val className = event.className ?: ""
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

    private fun evaluateScrollEvent(event: FeedEvent, eventTime: Long): DetectionResult {
        val className = event.className ?: ""
        val dx = event.scrollDeltaX
        val dy = event.scrollDeltaY

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
        if (candidate != null && !candidate.isSettled && (eventTime - candidate.lastEventTimeMs) < BURST_QUIESCENCE_MS) {
            // Absorbed into current burst
            candidate.scrollEventCount++
            candidate.lastEventTimeMs = eventTime
            lastScrollEventMs = eventTime

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
                    if (candidate.reverseDisplacement >= 40) {
                        candidate.isReversalDetected = true
                    }
                }
            }

            // Update item index if reported by the container
            if (event.fromIndex >= 0) {
                if (candidate.startFromIndex < 0) {
                    candidate.startFromIndex = event.fromIndex
                }
                candidate.lastFromIndex = event.fromIndex
            }
            if (event.toIndex >= 0) {
                if (candidate.startToIndex < 0) {
                    candidate.startToIndex = event.toIndex
                }
                candidate.lastToIndex = event.toIndex
            }
            if (event.itemCount >= 0) candidate.itemCount = event.itemCount

            val duration = eventTime - candidate.startTimeMs
            lastBurstSummary = "Burst #${candidate.burstId}: ${candidate.scrollEventCount} scrolls over ${duration}ms " +
                    "(fwd=${candidate.forwardDisplacement}px, rev=${candidate.reverseDisplacement}px, net=${candidate.netDisplacement}px)"

            return DetectionResult.AbsorbedInBurst(
                burstId = candidate.burstId,
                eventIndex = candidate.scrollEventCount
            )
        }

        // If a previous candidate exists and hasn't been settled yet, settle it before starting a new burst
        var priorSettlementResult: DetectionResult? = null
        if (candidate != null && !candidate.isSettled) {
            priorSettlementResult = settleCandidate(candidate, eventTime)
        }

        // Check horizontal swipe on burst inception
        if (abs(dx) > abs(dy) * 1.5 && abs(dx) > 60) {
            lastBurstSummary = "Burst #$nextBurstId [REJECTED]: Horizontal swipe (dx=$dx, dy=$dy) in $className"
            return priorSettlementResult ?: DetectionResult.RejectedSignal("Horizontal scroll detected: dx=$dx, dy=$dy")
        }

        // Check transition cadence debounce from the end of the previous transition
        val timeSinceLastTransition = eventTime - lastConfirmedTransitionMs
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
            startTimeMs = eventTime,
            lastEventTimeMs = eventTime,
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
        lastScrollEventMs = eventTime

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

    private fun evaluateContentChanged(event: FeedEvent): DetectionResult {
        activeCandidate?.let { candidate ->
            if (!candidate.isSettled) {
                candidate.contentChangeCount++
                if ((event.contentChangeTypes and FeedEvent.CONTENT_CHANGE_TYPE_SUBTREE) != 0) {
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

        // ── Rule 1: Screen State & Container Verification ───────────────────────
        if (isInNonFeedScreen || NON_FEED_CLASS_HINTS.any { candidate.containerClassName.contains(it, ignoreCase = true) }) {
            val reason = "Rejected: In non-feed screen or container (${candidate.containerClassName})"
            lastDecisionSummary = "Burst #${candidate.burstId} REJECTED: Non-feed screen"
            return DetectionResult.TransitionCanceled(candidate.burstId, reason)
        }

        // ── Rule 2: Directional Dominance (Vertical vs Horizontal) ──────────────
        if (candidate.accumulatedDx > abs(candidate.netDisplacement) * 1.3 && candidate.accumulatedDx > 80) {
            val reason = "Rejected: Horizontal displacement dominated (dx=${candidate.accumulatedDx}px, netDy=${candidate.netDisplacement}px)"
            lastDecisionSummary = "Burst #${candidate.burstId} REJECTED: Horizontal motion"
            return DetectionResult.TransitionCanceled(candidate.burstId, reason)
        }

        // ── Rule 3: Container Index Ground Truth (Definitive Evidence) ──────────
        // When the container reports item index bounds (fromIndex/toIndex):
        val hasValidIndices = candidate.startFromIndex >= 0 && candidate.lastFromIndex >= 0
        if (hasValidIndices) {
            if (candidate.startFromIndex == candidate.lastFromIndex) {
                // The pager returned to or stayed on the exact same item.
                // This is definitive evidence that the gesture was canceled or snapped back.
                val reason = "Canceled gesture: Container item index remained unchanged (index=${candidate.startFromIndex})"
                lastDecisionSummary = "Burst #${candidate.burstId} CANCELED: Returned to same item (${candidate.startFromIndex})"
                return DetectionResult.TransitionCanceled(
                    burstId = candidate.burstId,
                    reason = reason
                )
            } else {
                // The item index explicitly changed (e.g., 0 -> 1 for forward, or 5 -> 4 for reverse swipe).
                // Confirm transition!
                lastConfirmedTransitionMs = candidate.lastEventTimeMs

                val evidence = "Index advanced from ${candidate.startFromIndex} to ${candidate.lastFromIndex}, " +
                        "netDy=${candidate.netDisplacement}px, duration=${duration}ms, scrolls=${candidate.scrollEventCount}, " +
                        "contentChanges=${candidate.contentChangeCount}"
                val reason = "Feed advancement confirmed by index change (${candidate.startFromIndex} -> ${candidate.lastFromIndex})"
                lastDecisionSummary = "Burst #${candidate.burstId} CONFIRMED: Item index advanced (${candidate.startFromIndex} -> ${candidate.lastFromIndex})"

                return DetectionResult.TransitionConfirmed(
                    burstId = candidate.burstId,
                    reason = reason,
                    evidence = evidence
                )
            }
        }

        // ── Rules 4-7: Motion & Content Heuristics (When Container Index is Unavailable) ─

        // Rule 4: Snap-back Reversal
        val hasReversal = candidate.isReversalDetected &&
                candidate.reverseDisplacement >= 60 &&
                candidate.reverseDisplacement >= (candidate.forwardDisplacement * REVERSAL_RATIO_THRESHOLD)

        val netRatioReversal = candidate.grossDisplacement > 0 &&
                candidate.reverseDisplacement >= 40 &&
                abs(candidate.netDisplacement).toFloat() / candidate.grossDisplacement < 0.5f

        if (hasReversal || netRatioReversal) {
            val reason = "Canceled gesture: Snap-back reversal detected (fwd=${candidate.forwardDisplacement}px, rev=${candidate.reverseDisplacement}px, net=${candidate.netDisplacement}px)"
            lastDecisionSummary = "Burst #${candidate.burstId} CANCELED: Snap-back (net=${candidate.netDisplacement}px)"
            return DetectionResult.TransitionCanceled(
                burstId = candidate.burstId,
                reason = reason
            )
        }

        // Rule 5: Insufficient Net Vertical Displacement
        if (candidate.grossDisplacement > 0 && abs(candidate.netDisplacement) < MIN_NET_VERTICAL_DISPLACEMENT) {
            val reason = "Canceled gesture: Insufficient net vertical displacement (${candidate.netDisplacement}px < ${MIN_NET_VERTICAL_DISPLACEMENT}px)"
            lastDecisionSummary = "Burst #${candidate.burstId} CANCELED: Travel too small (${candidate.netDisplacement}px)"
            return DetectionResult.TransitionCanceled(
                burstId = candidate.burstId,
                reason = reason
            )
        }

        // Rule 6: Zero Feed Content Updates
        // A genuine video transition attaches and renders a new video player hierarchy.
        // If zero content updates occurred, no new video was bound.
        if (candidate.contentChangeCount == 0) {
            val reason = "Canceled gesture: No content updates recorded"
            lastDecisionSummary = "Burst #${candidate.burstId} CANCELED: No feed content updates"
            return DetectionResult.TransitionCanceled(
                burstId = candidate.burstId,
                reason = reason
            )
        }

        // ── Rule 7: Unidirectional Displacement with Corroborating Feed Changes ──
        lastConfirmedTransitionMs = candidate.lastEventTimeMs

        val evidence = buildString {
            append("NetDy=${candidate.netDisplacement}px (fwd=${candidate.forwardDisplacement}, rev=${candidate.reverseDisplacement}), ")
            append("duration=${duration}ms, ")
            append("scrolls=${candidate.scrollEventCount}, ")
            append("contentChanges=${candidate.contentChangeCount}")
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
