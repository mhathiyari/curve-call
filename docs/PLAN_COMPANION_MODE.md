# Plan: Companion Mode (Routeless Curve Narration)

## Goal

Today CurveCue requires picking a destination; curves are analyzed once over the
pre-computed route, then narrated as the user drives it. **Companion mode** removes
the destination requirement: the user navigates with any app they like (or no app
at all), and CurveCue runs alongside, continuously scanning the road *ahead* of the
current GPS position + heading in the offline GraphHopper graph, computing curve
severity on the fly, and narrating warnings (hairpins, tightening curves, etc.).

Key differences from routed mode:

| | Routed mode | Companion mode |
|---|---|---|
| Route | Full route, analyzed once | Sliding 3–5 km window, re-analyzed on the go |
| Path ahead | Known exactly | Predicted (heading/road-class/name heuristics at forks) |
| Map data | Route polyline from router | Direct graph walk via GraphHopper `LocationIndex` + `EdgeExplorer` |
| Audio | Holds audio focus for session | Per-utterance focus (ducks/pauses the nav app briefly) |
| UI | Full session screen | Foreground service + optional floating bubble overlay |

## Architecture

```
GPS (LocationProvider)
  └─ heading smoothing (low-pass on bearing)
       └─ RoadScanner.scan(lat, lon, heading, speed)        [:app/companion]
            ├─ snap to nearest edge (LocationIndex)
            ├─ walk edges forward 3–5 km, resolving forks:
            │    1. heading continuation (smallest angle diff)
            │    2. road-class preference (tiebreak)
            │    3. name continuity (final tiebreak)
            └─ ScanResult { polyline, snappedPosition, edgeIds }
       └─ RouteAnalyzer.analyzeRouteDetailed(polyline)       [:engine, reused as-is]
       └─ NarrationManager.loadRoute(...) on each rescan     [:narration, reused as-is]
            └─ AndroidTtsEngine(perUtteranceFocus = true)
```

- **Rescan policy:** rescan when the predicted edge set changes (driver took a
  different fork) or after ~2 km of travel. Between rescans, `MapMatcher` tracks
  progress along the scanned polyline and feeds `NarrationManager` as in routed mode.
- **Lifecycle:** `CompanionForegroundService` (foregroundServiceType=location) keeps
  GPS + narration alive while another nav app is in the foreground. Optional
  `CompanionBubble` overlay (SYSTEM_ALERT_WINDOW) shows next-curve preview on top
  of the nav app.
- **Region requirement:** companion mode needs the offline graph for the current
  region (same downloads as offline navigation). Error out with a clear message if
  no region covers the current location.

## Status: existing implementation

> **An uncommitted, near-complete implementation already exists in the git worktree
> `.claude/worktrees/companion-mode` (branch `worktree-companion-mode`).**
> ~2 000 lines: `RoadScanner`, `CompanionState`/`CompanionUiState`,
> `CompanionSessionViewModel`, `CompanionForegroundService`, `CompanionBubble`,
> `CompanionSetupScreen`/`ViewModel`, plus wiring (manifest, DI, nav routes,
> HomeScreen entry, per-utterance audio focus in `AndroidTtsEngine`).
> The plan below treats that as the starting point: review → harden → test → merge.

## Phase 1 — Review & harden the existing worktree code

- [x] Review `RoadScanner` edge-walk correctness against GraphHopper 11 API
- [x] **One-way streets:** scanner uses `EdgeFilter.ALL_EDGES` and ignores access
      direction — it can walk down one-ways the wrong way. Filter candidate edges
      by the car/motorcycle access encoded value in the forward direction.
- [x] **Hairpin vs U-turn rejection:** `U_TURN_THRESHOLD_DEG = 150°` rejects
      candidate edges whose initial bearing differs >150° from travel heading. A
      tight hairpin or switchback at a junction node can legitimately exceed this —
      the exact roads this feature exists for. Distinguish "same edge back the way
      we came" (reject) from "different edge that bends sharply" (allow, or raise
      threshold when the edge geometry curves away).
- [x] **Road-class tiebreak is a stub:** `getRoadClass()` currently returns 0/1
      based on name presence. Read the real `road_class` encoded value from the
      graph so fork resolution prefers staying on the bigger road.
- [x] **Perf: don't run a full graph walk on every GPS fix.** `scan()` is called
      ~1 Hz; instead, cheaply verify the current position still lies on the last
      scanned polyline (MapMatcher distance), and only run a full scan when
      off-polyline, near the window end, or past the 2 km rescan distance. Also
      verify the `scan.edgeIds != lastScanEdgeIds` rescan trigger doesn't fire
      spuriously as the snapped edge advances along the predicted path.
- [x] **Threading:** scan + `analyzeRouteDetailed` currently run inside the
      location collect on the main dispatcher — move to `Dispatchers.Default`,
      drop stale results if a newer fix arrived.
- [x] **Wrong-fork recovery:** when the driver diverges from the prediction,
      cancel queued narrations for curves that no longer apply before loading the
      new scan (verify `narrationManager.stop()` → `loadRoute()` does this cleanly,
      no mid-utterance ghost warnings).
- [x] Review rescan/narration continuity: a curve straddling the rescan boundary
      must not be re-announced after reload (progress resets to ~0 in new window)
- [ ] Off-road handling: 100 m snap threshold, parallel-road (frontage road /
      highway) mis-snap — consider heading agreement in the snap acceptance check

## Phase 2 — Tests

- [x] `RoadScanner` unit tests with a small synthetic GraphHopper graph:
      straight road, fork (heading wins), fork (road class tiebreak), dead end,
      one-way rejection, hairpin continuation, off-road (snap too far)
- [x] Rescan policy: replaced the fragile edge-set comparison with MapMatcher-based
      triggers (no window yet / >30 m off the polyline / >2 km of window consumed) —
      no spurious rescans while following the prediction. Dedicated ViewModel-level
      tests deferred together with the item below.
- [ ] `CompanionSessionViewModel` tests: start without region → error; GPS loss →
      `isGpsLost` + restore message; mute suppresses TTS; verbosity cycle persists
      *(deferred: needs mockk/robolectric + coroutines-test, not yet in the app module)*
- [ ] Heading smoothing tests (low-speed bearing hold, 359°→1° wraparound)
      *(deferred with the above)*

## Phase 3 — UX & polish

- [x] Home screen: "Companion Mode" entry alongside destination flow (wired in
      worktree — verify copy and discoverability)
- [x] Setup screen: explain mode, request overlay permission (optional), preflight
      region check, start button
- [x] Floating bubble: next-curve preview + distance, mute toggle, tap-through to
      app; graceful degradation when overlay permission denied (notification only)
- [x] Notification: ongoing, shows next curve, stop action
- [ ] Audio: confirm per-utterance `GAIN_TRANSIENT` focus plays nicely with Google
      Maps / music (narration pauses them briefly, releases focus right after)
- [ ] Battery: GPS interval + scan throttling sanity check for multi-hour rides

## Phase 4 — Verify & merge

- [x] `./gradlew :engine:test :narration:test :app:testDebugUnitTest` green
      (JAVA_HOME = `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`)
- [ ] Manual drive test (or GPS replay) on a known twisty route in a downloaded
      region: warnings fire at sensible distances; fork mis-predictions recover
- [x] Merge worktree branch into `main`, delete worktree

## Open questions

- Scan distance fixed at 5 km vs speed-scaled (highway speeds eat 5 km in ~3 min)?
- Should companion mode auto-suppress on motorways (road_class check) to avoid
  narrating gentle sweepers at 120 km/h? (SuppressionEngine may already cover this
  via speed floor — verify.)
- Bubble overlay default on or off? (Overlay permission is a hurdle; notification
  + voice may be enough for v1.)
