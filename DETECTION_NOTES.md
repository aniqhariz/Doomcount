# DETECTION_NOTES.md — DOOMCOUNT Prototype

**Status:** Prototype — Detection Feasibility Spike (Iteration 3: Canceled Swipe Rejection)  
**Last updated:** 2026-10-13  
**Milestone:** 1 — Scroll-Detection Feasibility Spike  

---

## 1. What This Document Is

This document records technical observations, empirical test findings, transition confirmation
heuristics, and known platform limitations for detecting short-video feed transitions in TikTok via
Android `AccessibilityService`.

> [!IMPORTANT]
> **No detection claim in this document is assumed to be perfect ground truth.**
> All counters are based on motion and feed-state heuristics. The app strictly separates raw
> accessibility events, candidate gestures, and confirmed feed transitions.

---

## 2. Empirical Test Findings & Root Cause Analysis

### Prior Test Results (Iteration 2):
* **10 intentional vertical swipes** → 10 detected transitions.
* **20 intentional vertical swipes** → 20 detected transitions.
* **50 intentional vertical swipes** → **51 detected transitions** (1 false positive).
* **Idle video (no interaction)** → 0 transitions.
* **Instagram Reels scrolling** → 0 transitions (package isolation confirmed).

### Root Cause of the 51st False Positive:
During the 50-swipe test, the user partially initiated a vertical swipe but canceled it. TikTok's pager
snapped back to the original video without advancing. However, DOOMCOUNT counted a transition.

**Code Audit Finding:**
In Iteration 2, `TikTokDetector.evaluateScrollEvent` returned `DetectionResult.TransitionCandidate`
on the **leading edge** (the very first `TYPE_VIEW_SCROLLED` event) of any scroll burst occurring after
quiescence. `DoomAccessibilityService` immediately incremented `estimatedTransitions` upon receiving
that candidate.

Consequently, **any touch gesture that emitted a vertical scroll event was immediately counted as a
transition before the gesture even concluded**, regardless of whether the user completed the swipe or
snapped back to the same video.

---

## 3. Transition Confirmation Architecture (Two-Phase Model)

To eliminate false positives from canceled gestures, the detector separates **gesture detection** from
**transition confirmation**:

```
[User initiates swipe]
        │
        ▼
Phase 1: Gesture Inception
  - `TYPE_VIEW_SCROLLED` starts a `CandidateBurst`.
  - Service receives `DetectionResult.TransitionCandidate`.
  - Counter is NOT incremented.
  - Motion tracking records forward displacement, reverse displacement, and index bounds.
        │
        ▼
Phase 2: Motion Accumulation & Quiescence
  - Subsequent scrolls update `forwardDisplacement` or `reverseDisplacement`.
  - `TYPE_WINDOW_CONTENT_CHANGED` updates `contentChangeCount`.
  - A settling window of 450ms quiescence (`SETTLEMENT_WINDOW_MS`) is scheduled via Handler.
        │
        ▼
Phase 3: Settlement Evidence Evaluation
  - Evaluates mathematical motion evidence:
      1. Snap-back reversal check (did pager snap back in opposing direction?)
      2. Net displacement check (did the page travel cross threshold?)
      3. Feed content corroboration (did view hierarchy update for a new video?)
      4. Screen state check (is the active window still the feed?)
        │
  ┌─────┴──────────────────────────────────────────────────────┐
  ▼                                                            ▼
CONFIRMED: Feed Advanced                                CANCELED: Same Video
  - `TransitionConfirmed` emitted.                       - `TransitionCanceled` emitted.
  - Counter incremented by EXACTLY 1.                     - Counter UNCHANGED.
  - `confirmedTransitionsCount++`                         - `canceledGesturesCount++`
```

---

## 4. Confirmation vs. Cancellation Evidence Rules

| Test Criterion | Condition for Confirmation | Condition for Cancellation | Physical Rationale |
|---|---|---|---|
| **Snap-Back Reversal** | `reverseDisplacement < 60px` OR `reverseDisplacement < forwardDisplacement * 0.35` | `reverseDisplacement >= 60px` AND `reverseDisplacement >= forwardDisplacement * 0.35` | In a canceled swipe, the user pulls in direction $A$, releases early, and the physics engine snaps back in direction $-A$. A completed swipe has unidirectional fling/snap with no opposing displacement. |
| **Net Vertical Displacement** | `abs(netDisplacement) >= 120px` (or container index explicitly advanced) | `abs(netDisplacement) < 120px` AND `startFromIndex == lastFromIndex` | On standard Android screens (1080x2400), crossing the pager threshold requires substantial net travel. Small drags that return to origin have $\sum \Delta y \approx 0$. |
| **Feed Content Updates** | `contentChangeCount > 0` OR `startFromIndex != lastFromIndex` | `contentChangeCount == 0` AND `startFromIndex == lastFromIndex` | When a new video loads, view subtrees and player metadata update. An aborted twitch produces no new view holder bindings. |
| **Directional Dominance** | `accumulatedDx <= abs(netDisplacement) * 1.3` | `accumulatedDx > abs(netDisplacement) * 1.3` AND `accumulatedDx > 80px` | Horizontal gestures navigate to user profile or live streams, not vertical feed transitions. |
| **Active Screen State** | `isInNonFeedScreen == false` | `isInNonFeedScreen == true` (comments, search, profile) | Gestures inside comments sheets or dialogs are rejected immediately. |

---

## 5. Structured Debug Logs (`DoomTransition` Tag)

The service logs every candidate inception, burst motion update, and settlement decision:

```
// Candidate created (count unchanged)
I DoomTransition: [CANDIDATE_CREATED] pkg=com.zhiliaoapp.musically burst=#12 t=1728809100120 reason='Scroll burst #12 started after quiescence' countBefore=11

// Motion accumulation during gesture
D DoomTransition: [SCROLL_ABSORBED] burst=#12 index=#2
D DoomTransition: [SCROLL_ABSORBED] burst=#12 index=#3

// Case A: Successful swipe (CONFIRMED)
I DoomTransition: [TRANSITION_CONFIRMED] pkg=com.zhiliaoapp.musically burst=#12 t=1728809100650 evidence='NetDy=1450px (fwd=1450, rev=0), duration=280ms, scrolls=3, contentChanges=14' countBefore=11 countAfter=12

// Case B: Canceled swipe (REJECTED / PREVENTED)
I DoomTransition: [TRANSITION_REJECTED] pkg=com.zhiliaoapp.musically burst=#13 t=1728809105400 reason='Canceled gesture: Snap-back reversal detected (fwd=220px, rev=210px, net=10px)' countUnchanged=12
```

---

## 6. Manual Testing & Validation Protocol

Run these tests on a physical device with TikTok installed. Keep Logcat open:
```bash
adb logcat -s DoomTransition DoomEvent DoomService
```

### Test A — Normal Swipes (10, 20, 50 Swipes)
* **Procedure:** Perform intentional vertical swipes through the "For You" feed (pause 2s per video).
* **Expected:** Exactly 1 confirmed transition per actual feed advancement.
* **Prior Verified Results (Iteration 2):** 10/10 (100%), 20/20 (100%), 51/50 (1 false positive from canceled swipe).
* **Target under Iteration 3:** 10/10, 20/20, 50/50.

### Test B — Canceled Swipes (20 Gestures)
* **Procedure:**
  1. Open DOOMCOUNT and tap **Reset Counters & Clear Session**.
  2. Open TikTok.
  3. Perform **20 deliberately incomplete swipes**: drag finger vertically 20–40% of the screen height, then release or pull back so TikTok snaps back to the original video.
  4. Verify that the video did NOT advance.
  5. Return to DOOMCOUNT.
* **Expected:**
  - **Confirmed Transitions:** **0**
  - **Canceled Gestures Prevented:** **20**
  - **Raw Scroll Events:** ~40–60 (reflecting the physical drags)
* **Verification Status:** Requires manual verification on physical Android device.

### Test C — Mixed Gestures
* **Procedure:**
  1. Perform 10 completed vertical swipes.
  2. Perform 5 canceled/aborted swipes.
  3. Perform 3 horizontal swipes (swipe left to open profile, then back).
  4. Perform 10 completed vertical swipes.
* **Expected:**
  - **Confirmed Transitions:** **20** (10 + 10)
  - **Canceled Gestures Prevented:** **5**
  - **Rejected Bursts:** **3**

### Test D — Idle Video Watching (30 Seconds)
* **Procedure:** Remain on a single video for 30 seconds without touching the screen.
* **Expected:** 0 new candidates, 0 confirmed transitions.
* **Prior Status:** Verified (passed).

### Test E — Target App Isolation (Instagram Reels / Other Apps)
* **Procedure:**
  1. Note DOOMCOUNT confirmed transition count.
  2. Open Instagram Reels and perform 20 vertical swipes.
  3. Return to TikTok and perform 5 vertical swipes.
* **Expected:** Only the 5 TikTok swipes increment the counter. Reels events produce 0 TikTok counts.
* **Prior Status:** Verified (passed).

---

## 7. Known Platform Limitations & Remaining Risks

1. **Slow Drags Near Boundary:**
   If a user drags a video 49% of the way, pauses for >450ms, and then releases so it snaps back,
   the settling timer might evaluate while the user is still holding the touch. To mitigate this,
   settlement is only evaluated when time since the last motion event exceeds `BURST_QUIESCENCE_MS`.
2. **Auto-Advancing Videos:**
   If a feed auto-advances to the next video without a manual touch swipe, no `TYPE_VIEW_SCROLLED` event fires.
   This remains a documented false-negative limitation for hands-free video feeds.
3. **Comment Section Scrolling:**
   Vertical scrolling inside comment sheets can produce vertical scroll bursts. The detector mitigates
   this by checking for non-feed class hints (`comment`, `bottomsheet`, `dialog`) in window state events.
   However, if an OEM or obfuscated build omits class hints, comment scrolling can register as candidate motion.

---

## 8. Summary of Files Modified in Iteration 3

* [`app/src/main/kotlin/com/doomcount/app/detectors/DetectionResult.kt`](file:///d:/Doomcount/app/src/main/kotlin/com/doomcount/app/detectors/DetectionResult.kt):
  Added `TransitionConfirmed` and `TransitionCanceled` sealed classes to decouple candidate inception from confirmation.
* [`app/src/main/kotlin/com/doomcount/app/detectors/TikTokDetector.kt`](file:///d:/Doomcount/app/src/main/kotlin/com/doomcount/app/detectors/TikTokDetector.kt):
  Implemented two-phase confirmation with motion tracking (`forwardDisplacement`, `reverseDisplacement`, `netDisplacement`), snap-back reversal detection, and evidence-based settlement evaluation.
* [`app/src/main/kotlin/com/doomcount/app/service/DoomAccessibilityService.kt`](file:///d:/Doomcount/app/src/main/kotlin/com/doomcount/app/service/DoomAccessibilityService.kt):
  Implemented Handler-driven settlement scheduling (450ms window), counter increment only on `TransitionConfirmed`, tracking of `canceledGesturesCount`, and structured `DoomTransition` logging.
* [`app/src/main/kotlin/com/doomcount/app/ui/MainScreen.kt`](file:///d:/Doomcount/app/src/main/kotlin/com/doomcount/app/ui/MainScreen.kt):
  Updated UI to display confirmed transitions, canceled swipes prevented, latest decision summary, and full manual validation checklist (Tests A–E).
