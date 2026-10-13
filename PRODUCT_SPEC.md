# DOOMCOUNT — Product Specification

**Version:** 0.1  
**Status:** Initial product definition  
**Platform:** Android first  
**Working name:** DOOMCOUNT

## 1. Product Summary

DOOMCOUNT helps people become aware of how often they move through short-form videos. It counts detected transitions between short videos in supported apps, shows a small live counter while the user is scrolling, and turns activity into useful daily and long-term statistics.

**Core promise:** See how much you scroll, without needing to change how you use your phone.

DOOMCOUNT is an awareness tool, not a blocker, productivity suite, social network, or screen-time replacement.

## 2. Problem

Short-form feeds make it easy to keep moving to the next video without noticing how many transitions have happened. Screen-time totals show duration, but do not show the number of videos a person has passed through.

DOOMCOUNT focuses on this missing metric: detected short-video transitions.

## 3. Target Users

- Android users who regularly use TikTok, Instagram Reels, or YouTube Shorts.
- People curious about their scrolling habits but not ready to block apps.
- Users who like lightweight statistics and weekly comparisons.

## 4. Product Principles

1. **Awareness, not shame.** Neutral language; no guilt-inducing warnings.
2. **Transparent counting.** Explain that counts are estimates based on detectable navigation events, not a perfect record of every video viewed.
3. **Privacy first.** Do not collect video contents, screenshots, messages, typed text, or account credentials.
4. **Local by default.** Keep event records and analytics on the device for the MVP.
5. **Low interruption.** The counter should be unobtrusive and easy to hide or reposition.
6. **Useful free tier.** Free users can see current activity and enough recent history to understand the product's value.

## 5. Counting Rules

### Count +1 when
- A detectable swipe/navigation action moves the feed from one short video to another in a supported feed.
- The event is sufficiently distinguishable from opening a profile, comments, search, or another screen.

### Do not count when
- The user opens or closes comments.
- The user opens a creator profile, search page, inbox, or settings.
- The user opens the app without a detectable transition to another video.
- A duplicate accessibility event is emitted for the same transition.

### Counting definition

One count represents **one detected transition to another short video**, not one second watched, not one unique video, and not a guaranteed count of every video that appeared on screen.

The prototype must test forward swipes, reverse swipes, repeated events, loading delays, ads, app navigation, and returning from another screen. If a reliable distinction cannot be made for an app/version, the app must disclose the limitation rather than invent precision.

## 6. Supported Apps

Initial prototype target:
- TikTok

Planned targets, only after separate validation:
- Instagram Reels
- YouTube Shorts

Support is app- and version-dependent. Do not promise universal detection until tested.

## 7. Main User Flow

1. User opens DOOMCOUNT.
2. Onboarding explains what is counted, what is not counted, and the limitations.
3. User chooses which supported apps to track.
4. User is guided through required Android permissions with a clear explanation of why each is needed.
5. User enables tracking.
6. User opens a supported app; a small floating counter appears if overlay permission is granted and the app is selected for tracking.
7. Detected transitions update the counter.
8. Leaving the tracked app hides the counter and ends that foreground session.
9. DOOMCOUNT saves aggregate activity locally and displays daily and weekly statistics.

## 8. MVP Features

### P0 — Required
- Onboarding and clear limitations statement.
- Select supported apps to track.
- Permission setup and status screen.
- Start/pause tracking.
- Floating live counter overlay, shown only in selected apps.
- Reset daily live count at the local calendar-day boundary.
- Local persistence for daily aggregates from day one.
- Home screen with today's count and a 7-day chart/history.
- Basic summary: average detected transitions per day and comparison with the previous 7-day period where enough data exists.
- Settings to enable/disable individual apps and hide/show the overlay.
- Privacy explanation and local-data deletion control.
- Robust handling for missing permissions, service stopped by Android, reboot, and app updates.

### P1 — After detection prototype succeeds
- Android home-screen widget showing today's count.
- Session summary when leaving a tracked app, optional and dismissible.
- Overlay repositioning and opacity/size controls.
- More polished weekly insight cards.

### Not in MVP
- iOS version.
- Cloud sync or account system.
- Social features or leaderboards.
- App blocking or focus mode.
- AI-generated advice.
- Exact watch-time measurement.
- Guaranteed counting across every app version or feed type.
- Subscription payments before core counting reliability is validated.

## 9. Monetization — Freemium

Proposed prices, subject to validation:
- **Plus monthly:** Rp9,900/month.
- **Plus yearly:** Rp59,000/year.

### Free
- Live floating counter.
- Home-screen widget when implemented.
- Today's count and daily stats.
- 7-day history/chart.
- Average detected transitions per day.
- Weekly comparison.
- Selected-app tracking controls.

### Plus
- Monthly and yearly totals.
- Full available history.
- Per-app breakdown.
- Activity calendar.
- Highest-scroll day and month.
- Longer-term comparisons and trends.
- Premium widget customization.
- Annual DOOMCOUNT recap with shareable cards.

### Data and entitlement rule
Save historical daily aggregates for every user from the first launch. Plus unlocks additional views and analysis; it must not be the reason historical data is first collected. If a user upgrades later, past locally stored aggregates should be available where retained.

Do not implement billing until the tracking prototype and basic retention loop have been validated.

## 10. Annual DOOMCOUNT Recap

A yearly review inspired by shareable year-end recaps, using original visual design and wording.

Potential cards:
- Total detected transitions in the year.
- Daily average.
- Most active month.
- Highest-count day.
- Per-app share.
- Monthly activity graph.

Only generate a recap when enough data exists. Label incomplete years or missing tracking periods clearly; never extrapolate missing days as facts.

## 11. Privacy and Permissions

- Prefer on-device processing and storage for the MVP.
- Do not capture or upload screenshots, video frames, audio, message contents, credentials, or typed text.
- Request only permissions needed for the features the user enables.
- Explain Accessibility Service use in plain language before sending the user to Android Settings.
- Explain overlay permission separately and make the overlay optional.
- Tracking must be user-initiated, visible, and controllable; include a clear pause/stop action.
- Provide a way to delete locally stored data.
- Before public release, review current Google Play policies for Accessibility API use, prominent disclosure/consent, overlay behavior, and background services. Do not assume approval merely because the API works technically.

## 12. Technical Direction

Preferred initial stack:
- Kotlin.
- Jetpack Compose for the app UI.
- Room for local persistence.
- Android AccessibilityService for investigating feed-navigation events, subject to policy review and user consent.
- SYSTEM_ALERT_WINDOW overlay for the floating counter, only after explicit user permission.
- Jetpack Glance for a home-screen widget, in a later milestone.
- DataStore for lightweight preferences if useful.

Keep the detection layer isolated from UI and storage so each supported app can have its own detector and tests.

Suggested conceptual modules:
- `app`: onboarding, dashboard, settings.
- `tracking`: service lifecycle and event stream.
- `detectors`: app-specific transition heuristics.
- `overlay`: floating counter window.
- `data`: Room entities, DAO, repositories.
- `stats`: daily aggregates and weekly/monthly calculations.
- `widget`: later Glance widget implementation.

## 13. Core Data Model (Initial)

### DailyAggregate
- `date`: local date key, unique.
- `totalTransitions`: integer.
- `tiktokTransitions`: integer, nullable/not present until supported.
- `instagramTransitions`: integer, nullable/not present until supported.
- `youtubeTransitions`: integer, nullable/not present until supported.
- `trackingSeconds`: optional; only add if reliably measured and clearly defined.
- `updatedAt`: timestamp.

### Preferences
- Selected apps.
- Tracking enabled/paused state.
- Overlay enabled state.
- Overlay position and appearance.
- Onboarding/permission explanation completion.

Do not store a detailed log of every screen or interaction unless a demonstrated debugging need requires it. Aggregates are sufficient for the planned statistics and reduce sensitive data retention.

## 14. Success Metrics

Prototype metrics:
- Precision of detected transition events in controlled manual tests.
- Missed and duplicate event rate by app and app version.
- Battery impact during a defined test session.
- Service survival and recovery after app switching, screen lock, and reboot.

MVP product metrics, preferably calculated locally at first:
- Onboarding completion.
- Permission setup completion.
- Percentage of users who enable tracking.
- Day-1 and Day-7 return rate, if analytics are later added with appropriate disclosure.
- Widget usage.
- Percentage of tracked sessions with detector errors.

Do not optimize for increasing users' scrolling. The goal is visibility and informed choice, not more engagement with short-video apps.

## 15. Risks and Validation Gates

### Gate A — Detection feasibility
Before building polished screens, make a minimal prototype for TikTok and manually test whether accessibility events reliably distinguish feed transitions from other navigation.

### Gate B — Platform policy
Review Android and Google Play rules for AccessibilityService, overlay permission, disclosure, and background operation before deciding the release path.

### Gate C — Reliability
Test duplicates, missed events, ads, loading, reverse swipes, comments, profiles, app switching, screen lock, permission revocation, and process termination.

### Gate D — Value
Only after the live counter works reasonably well, build the full dashboard and validate whether users return to view their statistics.

If Gate A fails, stop and reassess the detection approach before expanding scope. Never fake counts to make a demo look successful.

## 16. Immediate Build Plan

### Milestone 1 — Detection spike
- Create a minimal Android project in Kotlin.
- Add a user-facing explanation and a settings link for enabling the Accessibility Service.
- Log only event type, package name, and timestamp locally during development.
- Detect the foreground package and inspect relevant accessibility events.
- Build a manual test checklist for TikTok.
- Display a basic in-app count first; add the overlay only after the event stream is understood.

### Milestone 2 — Counter foundation
- Add deduplication and app-specific detection rules.
- Add daily local aggregate persistence with Room.
- Verify local-day reset and recovery after process death.

### Milestone 3 — Usable MVP
- Build Compose onboarding, dashboard, settings, 7-day chart, and optional overlay.
- Add widget after core counting is stable.
- Perform privacy and policy review before release.

### Milestone 4 — Monetization validation
- Test retention and demand.
- Finalize Plus features and pricing only after users demonstrate recurring value.

## 17. Product Copy Draft

**Short description:** Know how many short videos you scroll past.

**Onboarding explanation:** DOOMCOUNT estimates your short-video scrolling by detecting navigation events in the apps you choose. It does not read video content or take screenshots. Counts may be incomplete or inaccurate when an app changes its interface or Android limits background activity.

**Permission explanation — Accessibility:** DOOMCOUNT uses Android's Accessibility Service to detect navigation changes in the selected apps so it can estimate video transitions. It does not use this service to read message contents, capture screenshots, or collect passwords. Tracking can be paused or disabled at any time.

**Permission explanation — Overlay:** The floating counter needs permission to appear above selected apps. You can turn it off and continue using the dashboard.

---

## Open Decisions

- Exact overlay placement and interaction behavior.
- Whether a session counter resets on every app launch or continues through the same local day (recommended: session count resets, daily total persists).
- Detector strategy after testing actual accessibility events.
- Whether any cloud backup is needed; default is no for MVP.
- Final Plus price and whether annual recap is Plus-only.
