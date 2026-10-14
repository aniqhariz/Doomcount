# DETECTION_NOTES.md — DOOMCOUNT Prototype

**Status:** Prototype — Detection Feasibility Spike (Iteration 4: Canceled Swipe Rejection & Definite Index Corroboration)  
**Last updated:** 2026-10-14  
**Milestone:** 1 — Scroll-Detection Feasibility Spike  

---

## 1. What This Document Is

This document records technical observations, empirical test findings, transition confirmation
heuristics, deduplication behavior, automated test results, and known platform limitations for detecting short-video feed transitions in TikTok via
Android `AccessibilityService`.

> [!IMPORTANT]
> **No detection claim in this document is assumed to be perfect ground truth.**
> All counters are based on motion, structural container indices, and feed-state heuristics. The app strictly separates raw
> accessibility events, candidate gestures, and confirmed feed transitions.

---

## 2. Empirical Test Findings & Root Cause Analysis

### Prior Manual Test Observations (Iterations 1 & 2):
* **10 intentional vertical swipes** → 10 detected transitions.
* **20 intentional vertical swipes** → 20 detected transitions.
* **50 intentional vertical swipes** → **51 detected transitions** (1 false positive).
* **Idle video (no interaction)** → 0 transitions.
* **Instagram Reels scrolling** → 0 transitions (package isolation confirmed).
* **Canceled or ambiguous gestures** → sometimes incremented count unexpectedly.

### Root Cause Audit Findings:

1. **Container Item Index Bypassed on Displacements $\ge 120$px:**
   In earlier iterations, `startFromIndex == lastFromIndex` was only checked inside an exception block when `abs(netDisplacement) < 120px`. On standard 1080x2400 displays, an incomplete drag often travels 150–250px before the user lifts their finger. Because net displacement exceeded 120px, the detector completely skipped checking whether the container item index changed, confirming a transition even though TikTok snapped back to the exact same video item.
2. **False Reliance on Background Content Changes (`contentChangeCount > 0`):**
   Simply touching or dragging TikTok's video feed causes player UI state changes (video pausing, playback controls appearing, progress bar layout) that emit `AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED` (`CONTENT_CHANGE_TYPE_SUBTREE`). Treating `contentChangeCount > 0` as corroboration of a new video transition allowed canceled drags to be confirmed.
3. **Smooth Animator Snap-Back Omitting Reverse Scroll Events:**
   When an incomplete swipe is released, TikTok's pager snaps back to the original item using a view property animator or physics spring. In many cases, this animation does not emit `TYPE_VIEW_SCROLLED` in reverse, or emits fewer than 60px of reverse scroll. Consequently, heuristics relying solely on `reverseDisplacement >= 60px` failed to detect snap-back cancellations when reverse events were omitted by the OS.
4. **Cadence Debounce Timestamp Distortion:**
   `lastConfirmedTransitionMs` was recorded using wall-clock `now` at settlement time (450ms after the gesture concluded). This distorted the cadence debounce window for subsequent user swipes, debouncing legitimate swipes started shortly after settlement.
5. **Inline Settlement Burst Dropping:**
   When a new scroll event arrived after quiescence, inline settlement of the previous candidate returned the prior settlement outcome but dropped or corrupted the inception of the new burst, causing subsequent drag events to be swallowed or misclassified.

---

## 3. Transition Confirmation Architecture (Iteration 4)

To resolve false positives and ensure deterministic behavior, the detector uses a multi-tier confirmation architecture:

```
[User initiates swipe in TikTok feed]
         │
         ▼
Phase 1: Gesture Inception
  - `TYPE_VIEW_SCROLLED` starts a `CandidateBurst`.
  - Service receives `DetectionResult.TransitionCandidate`.
  - Counter is NOT incremented.
  - Motion tracking records forward displacement, reverse displacement, and initial container index (`fromIndex`).
         │
         ▼
Phase 2: Motion Accumulation & Quiescence
  - Subsequent scrolls update `forwardDisplacement` or `reverseDisplacement` and track latest `fromIndex`.
  - `TYPE_WINDOW_CONTENT_CHANGED` updates `contentChangeCount`.
  - If user navigates away (e.g. comments, profile), candidate is immediately CANCELED.
  - A settling window of 450ms quiescence (`SETTLEMENT_WINDOW_MS`) is scheduled via Handler.
         │
         ▼
Phase 3: Settlement Evidence Evaluation
  - Tier 1: Screen & Directional Checks
      * Active screen is non-feed (comments, profile, search, dialog): CANCELED.
      * Horizontal displacement dominates vertical: CANCELED.
  - Tier 2: Container Item Index Ground Truth (Definitive Signal)
      * `startFromIndex >= 0 && lastFromIndex >= 0`:
          - `startFromIndex == lastFromIndex` ──► CANCELED (Returned to same video, counter UNCHANGED).
          - `startFromIndex != lastFromIndex` ──► CONFIRMED (Advanced forward or reverse, counter +1).
  - Tier 3: Motion & Content Heuristics (When Container Index is Unavailable)
      * Snap-back reversal ratio (`reverse >= 35%` of forward and `>= 60px`, or net < 50% gross) ──► CANCELED.
      * Net vertical displacement < 150px ──► CANCELED.
      * Zero feed content updates ──► CANCELED.
      * Unidirectional displacement >= 150px with content updates ──► CONFIRMED.
```

---

## 4. Confirmation vs. Cancellation Evidence Rules

| Test Criterion | Condition for Confirmation | Condition for Cancellation | Physical & Platform Rationale |
|---|---|---|---|
| **Container Index Ground Truth** | `startFromIndex != lastFromIndex` (e.g., $0 \to 1$, or reverse $5 \to 4$) | `startFromIndex == lastFromIndex` (e.g., starts at 3, ends at 3) | TikTok's feed is a 1-item-per-page vertical pager. If the adapter index returns to the original item, the feed remained on the same video. If index changes, the feed advanced. |
| **Active Screen State** | `isInNonFeedScreen == false` | `isInNonFeedScreen == true` (comments, search, profile, bottomsheet) | Gestures inside comments sheets or dialogs are rejected immediately. Mid-gesture opening cancels candidate. |
| **Directional Dominance** | `accumulatedDx <= abs(netDisplacement) * 1.3` | `accumulatedDx > abs(netDisplacement) * 1.3` AND `accumulatedDx > 80px` | Horizontal swipes navigate to user profile or other feeds, not vertical feed transitions. |
| **Snap-Back Reversal** (Index unavailable) | `reverseDisplacement < 60px` AND net displacement $\ge 50\%$ gross | `reverseDisplacement >= 60px` AND `reverse >= forward * 0.35`, OR `net < 0.5 * gross` with `reverse >= 40px` | Pulling forward and snapping back in the opposing direction produces opposing scroll vectors. |
| **Net Vertical Travel** (Index unavailable) | `abs(netDisplacement) >= 150px` | `abs(netDisplacement) < 150px` | Flipping a page on high-resolution displays requires substantial net travel. Small drags below 150px do not flip the feed. |
| **Feed Content Updates** (Index unavailable) | `contentChangeCount > 0` | `contentChangeCount == 0` | A new video binding produces subtree updates; inert touches without content updates are rejected. |

---

## 5. Deduplication Behavior

1. **Burst Clustering:** Rapid successive scroll events occurring within `BURST_QUIESCENCE_MS` (400ms) are grouped into a single `CandidateBurst` (`AbsorbedInBurst`).
2. **Quiescence Before Next Burst:** When a new scroll arrives after quiescence, any unsettled candidate is settled before creating the next burst, preventing dropped candidates and burst state corruption.
3. **Cadence Debouncing:** Rapid successive transitions within `MIN_TRANSITION_INTERVAL_MS` (350ms) from the end of the previous gesture are debounced. The timestamp recorded (`lastConfirmedTransitionMs`) uses the gesture completion time (`candidate.lastEventTimeMs`), avoiding debounce errors caused by settlement timer delay.
4. **Post-Settlement Content Changes:** Delayed `TYPE_WINDOW_CONTENT_CHANGED` events arriving after a burst has settled are classified as `NoSignal` and do not trigger duplicate counts or spawn candidate bursts.

---

## 6. Automated Unit Tests & Verification

Deterministic unit tests were implemented in [`TikTokDetectorTest.kt`](file:///home/rixzeruuuu/Documents/Codingan/Doomcount/app/src/test/kotlin/com/doomcount/app/detectors/TikTokDetectorTest.kt) using synthetic event sequences via platform-independent [`FeedEvent`](file:///home/rixzeruuuu/Documents/Codingan/Doomcount/app/src/main/kotlin/com/doomcount/app/detectors/FeedEvent.kt).

### Test Suite Execution:
* **Task:** `./gradlew testDebugUnitTest`
* **Test Class:** `com.doomcount.app.detectors.TikTokDetectorTest`
* **Total Tests:** 14
* **Passed:** 14
* **Failed:** 0
* **Skipped:** 0

### Test Coverage Summary:
1. `testOneConfirmedTransitionProducesExactlyOneCount`: Confirmed transition yields exactly count = 1.
2. `testMultipleAccessibilityEventsAssociatedWithOneTransitionProduceAtMostOneCount`: 1 scroll inception, 8 continuation scrolls, 20 content changes, and 5 post-settlement changes yield exactly count = 1.
3. `testIdleEventSequenceProducesZeroCounts`: 30s of idle content updates produce 0 counts.
4. `testCanceledGesture_whenIndexUnchanged_producesZeroCounts`: Drag of 250px with content changes where container index stays at 3 produces 0 counts and 1 canceled gesture.
5. `testCanceledGesture_whenSnapBackReversalOccurs_producesZeroCounts`: 220px forward pull with 180px snap-back reversal (without index) produces 0 counts and 1 canceled gesture.
6. `testCanceledGesture_whenDisplacementInsufficient_producesZeroCounts`: 60px twitch produces 0 counts and 1 canceled gesture.
7. `testCanceledGesture_whenNoContentChanges_producesZeroCounts`: Drag without content changes produces 0 counts.
8. `testOpeningCommentsDoesNotCountAsFeedTransition`: Scrolling in comment sheet produces 0 counts and 0 candidates.
9. `testOpeningCommentsMidGestureCancelsCandidate`: Mid-gesture comment opening immediately cancels candidate.
10. `testHorizontalSwipeToProfileIsRejected`: Horizontal swipe produces 0 counts.
11. `testEventsFromNonTargetPackagesAreIgnored`: Events from Instagram Reels and YouTube Shorts produce 0 counts.
12. `testTwoDistinctSuccessfulTransitionsAreCountedSeparately`: Two sequential swipes produce exactly 2 counts.
13. `testDelayedContentUpdateDoesNotCreateDuplicateCounts`: Delayed content updates after settlement produce 0 additional counts.
14. `testReverseNavigationIsHandledConsistently`: Swiping to previous video (index $5 \to 4$) produces 1 count; canceled reverse swipe (index stays at 4) produces 0 additional counts.

---

## 7. Known Platform Limitations & Cases Requiring Physical-Device Validation

1. **Missing Container Index with Inaudible Snap-Back:**
   If TikTok runs on a device or build where `fromIndex` and `toIndex` are omitted (`-1`), AND the user drags >150px before releasing, AND TikTok's animator snaps back without emitting reverse `TYPE_VIEW_SCROLLED` events, the detector cannot mathematically distinguish this snap-back from a completed swipe using accessibility events alone. This is an inherent limitation of Android's accessibility event stream when `canRetrieveWindowContent` is false and view hierarchy inspection is disabled.
2. **Auto-Advancing Feeds (Hands-Free):**
   If a feed auto-advances to the next video without a manual touch swipe, no `TYPE_VIEW_SCROLLED` event fires. This remains an expected limitation for hands-free video feeds.
3. **In-Feed Comment Overlays Without Class Hints:**
   If an OEM build or third-party fork strips fragment class names or renders comments in a generic `FrameLayout` without matching any non-feed class hints, comment scrolling could be evaluated as feed scroll motion.
4. **Physical-Device Validation Required:**
   While the unit test suite verifies synthetic event sequences deterministically, physical validation with a real phone running TikTok remains required to observe exact event timings and coordinate conventions across TikTok app updates.

---

## 8. Summary of Files Modified in Iteration 4

* [`app/src/main/kotlin/com/doomcount/app/detectors/FeedEvent.kt`](file:///home/rixzeruuuu/Documents/Codingan/Doomcount/app/src/main/kotlin/com/doomcount/app/detectors/FeedEvent.kt):
  Added platform-independent event abstraction decoupling detector logic from Android framework APIs.
* [`app/src/main/kotlin/com/doomcount/app/detectors/TikTokDetector.kt`](file:///home/rixzeruuuu/Documents/Codingan/Doomcount/app/src/main/kotlin/com/doomcount/app/detectors/TikTokDetector.kt):
  Implemented multi-tier settlement logic: container index ground truth (`startFromIndex == lastFromIndex` for cancellation, `!=` for confirmation), improved snap-back ratios, net displacement checks, accurate gesture completion timestamps for cadence debouncing, and `FeedEvent` evaluation.
* [`app/src/main/kotlin/com/doomcount/app/service/DoomAccessibilityService.kt`](file:///home/rixzeruuuu/Documents/Codingan/Doomcount/app/src/main/kotlin/com/doomcount/app/service/DoomAccessibilityService.kt):
  Settles quiesced candidates prior to evaluating subsequent scroll bursts, ensuring clean candidate inception and lifecycle tracking.
* [`app/src/test/kotlin/com/doomcount/app/detectors/TikTokDetectorTest.kt`](file:///home/rixzeruuuu/Documents/Codingan/Doomcount/app/src/test/kotlin/com/doomcount/app/detectors/TikTokDetectorTest.kt):
  Added comprehensive unit test suite with 14 tests covering all 9 required scenarios.
* [`app/build.gradle.kts`](file:///home/rixzeruuuu/Documents/Codingan/Doomcount/app/build.gradle.kts):
  Added `junit:junit:4.13.2` test dependency.
* [`DETECTION_NOTES.md`](file:///home/rixzeruuuu/Documents/Codingan/Doomcount/DETECTION_NOTES.md):
  Updated technical documentation, root cause analysis, architecture, test results, and known platform limitations.
