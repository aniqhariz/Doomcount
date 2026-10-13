package com.doomcount.app.detectors

/**
 * Represents the outcome of evaluating one accessibility event against a detector.
 *
 * Separates raw accessibility events and candidate gestures from confirmed transitions:
 *
 * [NoSignal]            — Event carries no transition signal or is outside target app.
 * [TransitionCandidate] — Event initiated a candidate scroll burst. Motion and content
 *                         evidence are collected during the gesture. Does NOT increment count.
 * [TransitionConfirmed] — Candidate gesture was CONFIRMED as a genuine feed advancement
 *                         (unidirectional displacement, no snap-back, corroborating content).
 *                         Increments the transition counter exactly once.
 * [TransitionCanceled]  — Candidate gesture was CANCELED or aborted (snap-back reversal,
 *                         insufficient net displacement, or same-video return).
 *                         Does NOT increment the transition counter.
 * [AbsorbedInBurst]     — Event is a continuation of an active scroll burst (drag/fling/snap).
 *                         Does NOT increment count.
 * [RejectedSignal]      — Event or burst was rejected immediately (e.g., horizontal swipe,
 *                         known non-feed screen like comments/profile).
 * [PossibleTransition]  — Legacy signal (kept for backward compatibility).
 * [LikelyTransition]    — Legacy signal (kept for backward compatibility).
 */
sealed class DetectionResult {
    /** Event carries no feed-transition signal. */
    object NoSignal : DetectionResult()

    /**
     * Event initiated a candidate transition burst.
     * Evidence collection begins; the counter is NOT incremented yet.
     *
     * @param burstId Monotonically increasing identifier for this burst.
     * @param reason Human-readable explanation of why a candidate was created.
     * @param className Class name of the view emitting the event.
     * @param confidence Preliminary confidence score.
     */
    data class TransitionCandidate(
        val burstId: Long,
        val reason: String,
        val className: String,
        val confidence: Float = 0.5f
    ) : DetectionResult()

    /**
     * A candidate burst was CONFIRMED as a real feed advancement.
     * Increments the transition counter exactly once.
     *
     * @param burstId Identifier of the confirmed burst.
     * @param reason Human-readable confirmation rationale.
     * @param evidence Summary of motion and content evidence supporting confirmation.
     */
    data class TransitionConfirmed(
        val burstId: Long,
        val reason: String,
        val evidence: String
    ) : DetectionResult()

    /**
     * A candidate burst was CANCELED, reversed, or aborted.
     * The feed remained on the original video. Counter is NOT incremented.
     *
     * @param burstId Identifier of the canceled burst.
     * @param reason Why the candidate was deemed canceled or aborted.
     */
    data class TransitionCanceled(
        val burstId: Long,
        val reason: String
    ) : DetectionResult()

    /**
     * Event is a continuation of an active scroll burst.
     *
     * @param burstId Identifier of the burst this event was absorbed into.
     * @param eventIndex Position of this event within the current burst.
     */
    data class AbsorbedInBurst(
        val burstId: Long,
        val eventIndex: Int
    ) : DetectionResult()

    /**
     * Event matched a known non-transition pattern (e.g. comments, profile, horizontal swipe).
     *
     * @param reason Why this event or burst was rejected.
     */
    data class RejectedSignal(val reason: String) : DetectionResult()

    /** Legacy candidate signal (kept for backward compatibility). */
    data class PossibleTransition(val reason: String) : DetectionResult()

    /** Legacy confirmed transition (kept for backward compatibility). */
    data class LikelyTransition(val reason: String, val confidence: Float) : DetectionResult()
}
