package com.doomcount.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.doomcount.app.detectors.DetectionResult
import com.doomcount.app.detectors.TikTokDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * DoomAccessibilityService — accessibility service for DOOMCOUNT detection research.
 *
 * ## Lifecycle & Gesture Confirmation:
 * 1. Incoming events from TikTok are evaluated by [TikTokDetector].
 * 2. When a candidate gesture begins, a settlement check is scheduled via [Handler]
 *    for the settlement quiescence window (450ms).
 * 3. At settlement, the gesture's collected evidence (directional consistency, snap-back
 *    reversal, net displacement, content changes) is evaluated.
 * 4. Only if confirmed does [SessionStats.estimatedTransitions] increment.
 *    Canceled or aborted gestures increment [SessionStats.canceledGesturesCount] and
 *    leave the transition count unchanged.
 */
class DoomAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DoomService"
        private const val TRANSITION_TAG = "DoomTransition"

        private var activeServiceInstance: DoomAccessibilityService? = null

        // Shared observable state for the Compose UI
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _sessionStats = MutableStateFlow(SessionStats())
        val sessionStats: StateFlow<SessionStats> = _sessionStats.asStateFlow()

        /**
         * Resets session counters and detector state.
         * Called by MainViewModel when the user taps "Reset Counters".
         */
        fun resetStats() {
            _sessionStats.value = SessionStats()
            activeServiceInstance?.let { service ->
                service.settlementHandler.removeCallbacksAndMessages(null)
                service.tikTokDetector.reset()
            }
        }
    }

    val tikTokDetector = TikTokDetector()
    private var currentForegroundPackage: String = ""

    // Handler for scheduling gesture settlement evaluation
    private val settlementHandler = Handler(Looper.getMainLooper())
    private val settlementRunnable = Runnable {
        evaluateSettlement()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeServiceInstance = this
        _isRunning.value = true
        Log.i(TAG, "DoomAccessibilityService connected")

        val info = serviceInfo
        Log.i(TAG, "Observing packages: ${info?.packageNames?.toList()}")
        Log.i(TAG, "Event types mask: ${info?.eventTypes}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Safe structured logging of event metadata (NO text content is logged)
        EventLogger.log(event)

        val pkg = event.packageName?.toString() ?: return

        // Track foreground package changes
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg != currentForegroundPackage) {
                onForegroundAppChanged(oldPkg = currentForegroundPackage, newPkg = pkg)
                currentForegroundPackage = pkg
            }
        }

        // Process ONLY supported TikTok packages
        if (TikTokDetector.TIKTOK_PACKAGES.contains(pkg)) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                    _sessionStats.update { it.copy(windowStateChanges = it.windowStateChanges + 1) }
                AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                    _sessionStats.update { it.copy(scrollEvents = it.scrollEvents + 1) }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                    _sessionStats.update { it.copy(contentChangedEvents = it.contentChangedEvents + 1) }
                AccessibilityEvent.TYPE_VIEW_CLICKED ->
                    _sessionStats.update { it.copy(clickEvents = it.clickEvents + 1) }
            }

            val result = tikTokDetector.evaluate(event)
            handleDetectionResult(result, pkg)

            // If a candidate burst is active and awaiting settlement, ensure a check is scheduled
            if (tikTokDetector.hasActivePendingCandidate()) {
                scheduleSettlementCheck(TikTokDetector.SETTLEMENT_WINDOW_MS)
            }
        }
    }

    private fun scheduleSettlementCheck(delayMs: Long) {
        settlementHandler.removeCallbacks(settlementRunnable)
        settlementHandler.postDelayed(settlementRunnable, delayMs)
    }

    private fun evaluateSettlement() {
        val timeSinceLastScroll = tikTokDetector.getTimeSinceLastScroll()
        if (timeSinceLastScroll < TikTokDetector.BURST_QUIESCENCE_MS) {
            // Still active motion, reschedule remaining time
            val remaining = (TikTokDetector.SETTLEMENT_WINDOW_MS - timeSinceLastScroll).coerceAtLeast(100L)
            scheduleSettlementCheck(remaining)
            return
        }

        val result = tikTokDetector.settleActiveCandidate()
        handleDetectionResult(result, currentForegroundPackage)
    }

    private fun handleDetectionResult(result: DetectionResult, pkg: String) {
        val now = System.currentTimeMillis()

        when (result) {
            is DetectionResult.NoSignal -> {
                // No action needed
            }

            is DetectionResult.TransitionCandidate -> {
                val currentCount = _sessionStats.value.estimatedTransitions
                Log.i(
                    TRANSITION_TAG,
                    "[CANDIDATE_CREATED] pkg=$pkg burst=#${result.burstId} t=$now " +
                            "reason='${result.reason}' countBefore=$currentCount"
                )
                _sessionStats.update {
                    it.copy(
                        candidateTransitionsCount = it.candidateTransitionsCount + 1,
                        scrollBurstsCount = it.scrollBurstsCount + 1,
                        lastBurstSummary = tikTokDetector.lastBurstSummary,
                        lastDecisionSummary = tikTokDetector.lastDecisionSummary
                    )
                }
            }

            is DetectionResult.TransitionConfirmed -> {
                val before = _sessionStats.value.estimatedTransitions
                val after = before + 1
                Log.i(
                    TRANSITION_TAG,
                    "[TRANSITION_CONFIRMED] pkg=$pkg burst=#${result.burstId} t=$now " +
                            "evidence='${result.evidence}' countBefore=$before countAfter=$after"
                )
                _sessionStats.update {
                    it.copy(
                        estimatedTransitions = after,
                        confirmedTransitionsCount = it.confirmedTransitionsCount + 1,
                        lastBurstSummary = tikTokDetector.lastBurstSummary,
                        lastDecisionSummary = tikTokDetector.lastDecisionSummary,
                        lastTransitionTimestampMs = now
                    )
                }
            }

            is DetectionResult.TransitionCanceled -> {
                val currentCount = _sessionStats.value.estimatedTransitions
                Log.i(
                    TRANSITION_TAG,
                    "[TRANSITION_REJECTED] pkg=$pkg burst=#${result.burstId} t=$now " +
                            "reason='${result.reason}' countUnchanged=$currentCount"
                )
                _sessionStats.update {
                    it.copy(
                        canceledGesturesCount = it.canceledGesturesCount + 1,
                        lastBurstSummary = tikTokDetector.lastBurstSummary,
                        lastDecisionSummary = tikTokDetector.lastDecisionSummary
                    )
                }
            }

            is DetectionResult.AbsorbedInBurst -> {
                Log.d(TRANSITION_TAG, "[SCROLL_ABSORBED] burst=#${result.burstId} index=#${result.eventIndex}")
                _sessionStats.update {
                    it.copy(
                        absorbedScrollEvents = it.absorbedScrollEvents + 1,
                        lastBurstSummary = tikTokDetector.lastBurstSummary
                    )
                }
            }

            is DetectionResult.RejectedSignal -> {
                Log.d(TRANSITION_TAG, "[SIGNAL_REJECTED] pkg=$pkg reason='${result.reason}'")
                _sessionStats.update {
                    it.copy(
                        rejectedBurstsCount = it.rejectedBurstsCount + 1,
                        lastBurstSummary = tikTokDetector.lastBurstSummary,
                        lastDecisionSummary = tikTokDetector.lastDecisionSummary
                    )
                }
            }

            is DetectionResult.PossibleTransition -> {
                _sessionStats.update { it.copy(possibleTransitions = it.possibleTransitions + 1) }
            }

            is DetectionResult.LikelyTransition -> {
                _sessionStats.update { it.copy(likelyTransitions = it.likelyTransitions + 1) }
            }
        }
    }

    private fun onForegroundAppChanged(oldPkg: String, newPkg: String) {
        Log.i(TAG, "Foreground changed: $oldPkg -> $newPkg")
        settlementHandler.removeCallbacksAndMessages(null)
        if (TikTokDetector.TIKTOK_PACKAGES.contains(oldPkg) &&
            !TikTokDetector.TIKTOK_PACKAGES.contains(newPkg)
        ) {
            tikTokDetector.reset()
            Log.i(TAG, "TikTok detector reset (user left TikTok)")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "DoomAccessibilityService interrupted")
        settlementHandler.removeCallbacksAndMessages(null)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _isRunning.value = false
        activeServiceInstance = null
        settlementHandler.removeCallbacksAndMessages(null)
        tikTokDetector.reset()
        Log.i(TAG, "DoomAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _isRunning.value = false
        activeServiceInstance = null
        settlementHandler.removeCallbacksAndMessages(null)
        Log.i(TAG, "DoomAccessibilityService destroyed")
        super.onDestroy()
    }
}

/**
 * Snapshot of event counts and experimental estimates for the current session.
 */
data class SessionStats(
    // ── Raw Accessibility Events (Direct from Android) ──
    val windowStateChanges: Int = 0,
    val scrollEvents: Int = 0,
    val contentChangedEvents: Int = 0,
    val clickEvents: Int = 0,

    // ── Transition Confirmation Metrics ──
    /**
     * Confirmed video transitions where sufficient evidence proved feed advancement.
     * Canceled or aborted swipes do NOT increment this count.
     */
    val estimatedTransitions: Int = 0,

    /** Total candidate bursts initiated. */
    val candidateTransitionsCount: Int = 0,

    /** Total confirmed transitions (matches estimatedTransitions). */
    val confirmedTransitionsCount: Int = 0,

    /**
     * Canceled gestures successfully detected and prevented from false-positive counting
     * (snap-back reversal, insufficient travel, same-video return).
     */
    val canceledGesturesCount: Int = 0,

    /** Bursts rejected immediately (horizontal swipes or non-feed screens). */
    val rejectedBurstsCount: Int = 0,

    /** Total scroll events absorbed into active bursts. */
    val absorbedScrollEvents: Int = 0,

    /** Total bursts recorded. */
    val scrollBurstsCount: Int = 0,

    // ── Diagnostics ──
    val lastBurstSummary: String = "No scroll bursts recorded yet",
    val lastDecisionSummary: String = "Idle — no decisions made yet",
    val lastTransitionTimestampMs: Long = 0L,

    // ── Legacy metrics ──
    val possibleTransitions: Int = 0,
    val likelyTransitions: Int = 0
)
