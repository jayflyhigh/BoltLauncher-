# Bolt Launcher performance and stability fixes

Inspected branch: `bug-fixes-2.0`.
Baseline: `20a63823732e1a679a122cfdb3044279469c3c49`.

The baseline uses bottom-first filtered apps/contacts, query-preserving destination switching,
Enter submission, alias activation on space and backspace undo, immediate query suggestions,
and reversible dock/contact animations. These rules and all layout resources remain intact.

## Implemented causes and fixes

- Android Filter read mutable app/mode/alias state from its worker and rejected stale results
  only by query text. Filtering now uses immutable snapshots, cancellable background work,
  generation checks, and destination/mode checks at publication and commit callbacks.
- Sorting split labels and lowercased them repeatedly in the comparator. Ranking and sort keys
  are now computed once for each matching app. Added ranking regression tests.
- Cancelling suggestion coroutines left blocking HTTP calls running until timeouts. OkHttp's
  asynchronous calls now cancel their sockets with coroutine cancellation and have a total
  timeout. Each keystroke/mode change cancels suggestions, including returning to local matches.
- Combined app observers submitted, refiltered, and rebound the same list. They now publish a
  single filtered snapshot. Search-target package queries run on IO and publish on the UI thread.
- Contact filtering and whole-list refreshes ran on every keystroke on the UI thread. Matching
  now runs on Default, checks its generation, and skips unchanged dataset notifications.
  Enter and delayed contact launch check the committed query to avoid using older results.
- Network suggestion updates forced scrolling even for an unchanged query. They now preserve
  scroll position; a changed query still anchors the best result according to existing rules.
- Interrupted keyboard animations discarded visible translation before measuring their start.
  They now capture the visible start first and ignore obsolete animation end callbacks.
  Existing dock geometry, paths, easing, reduced-motion and e-ink handling remain in place.
- Repeated app refresh jobs could publish older snapshots. Superseded jobs are now cancelled.
- Hiding apps manually mutated lists outside ListAdapter's differ and trusted stale positions.
  Hiding now submits a fresh list and rejects NO_POSITION.
- Alias confirmation could consume composing CJK input; it now observes the existing composition guard.
- Missing/revoked profile access could throw before the app-launch exception handler. The query
  is now guarded. Denied contact permission clears cached results and selection.
- View destruction now cancels filtering/target loading, removes input/inset/scroll callbacks,
  and dismisses the contact-number dialog. Repeated taps cannot stack number-picker dialogs.

## Validation

- `git diff --check`: passed.
- Added `AppSearchRankTest` for ordering and case/whitespace semantics.
- Attempted `./gradlew testDebugUnitTest lintDebug assembleDebug` locally: blocked before Gradle
  could start because services.gradle.org was unreachable. No test/lint/build pass is claimed.
- No Android SDK, adb, emulator, or physical-device session is available in this workspace.
- The existing GitHub workflow now includes this branch and lint, but its execution requires
  pushing the local commits. That push was blocked by automatic approval review pending user
  authorization, so no remote validation or APK is available yet.
- No frame/profile measurements were available. No measured speedup is claimed.

## Device verification still required

Exercise rapid typing/deletion and held Backspace; alias space/undo and CJK composition; all
search modes and direct suggestions; cached/network/offline suggestions; contact selection and
all commands; revoked permissions and missing apps/browsers; list scrolling; keyboard opening,
closing, Back, and interrupted dock/contact transitions; returning from apps and view recreation.
Compare release-like frame traces and search latency on the same device/settings before and after.
