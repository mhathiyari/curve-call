# CurveCall v1.0 — Implementation Plan

## Development Strategy

**Parallel Streams:** The `engine` module is pure Kotlin with zero Android dependencies. The `narration` module only needs Android TTS at the boundary. This means we can develop and test the core algorithm entirely in parallel with Android app scaffolding.

**Critique-Driven:** After each phase, a dedicated review agent validates output against the PRD specs, runs tests, and flags deviations before moving on.

---

## Stream A: Core Engine (Pure Kotlin — No Android)

### Phase A1: Project Setup & Data Types
- [ ] Initialize Gradle multi-module project (`app`, `engine`, `narration`)
- [ ] Configure Kotlin DSL build files with proper module dependencies
- [ ] Set up JUnit 5 for `engine` module tests
- [ ] Implement `engine/types/LatLon.kt`
- [ ] Implement `engine/types/Severity.kt` enum (GENTLE, MODERATE, FIRM, SHARP, HAIRPIN)
- [ ] Implement `engine/types/Direction.kt` enum (LEFT, RIGHT)
- [ ] Implement `engine/types/CurveModifier.kt` enum (TIGHTENING, OPENING, HOLDS, LONG)
- [ ] Implement `engine/types/CompoundType.kt` enum (S_BEND, CHICANE, SERIES, TIGHTENING_SEQUENCE)
- [ ] Implement `engine/types/CurveSegment.kt` data class (full spec from PRD Section 13)
- [ ] Implement `engine/types/StraightSegment.kt` data class
- [ ] Implement `engine/types/RouteSegment.kt` sealed class
- [ ] Implement `engine/types/AnalysisConfig.kt` with defaults (PRD Section 13)
- [ ] Implement `engine/types/SeverityThresholds.kt`

### Phase A2: Geo Math & Curvature
- [ ] Implement `GeoMath.haversineDistance(p1, p2)` — meters between two LatLon points
- [ ] Implement `GeoMath.bearing(from, to)` — compass bearing in degrees
- [ ] Implement `GeoMath.interpolate(p1, p2, fraction)` — point between two coordinates
- [ ] Implement `MengerCurvature.radius(p1, p2, p3)` — circumscribed circle radius
- [ ] Implement `MengerCurvature.direction(p1, p2, p3)` — LEFT/RIGHT via cross product sign
- [ ] Write tests: known circle (100m radius) → verify radius ±1%
- [ ] Write tests: collinear points → verify returns MAX_VALUE (straight)
- [ ] Write tests: known bearings → verify bearing calculation

### Phase A3: Interpolation & Curvature Pipeline
- [ ] Implement `Interpolator.resample(points, spacingMeters)` — uniform 10m spacing
- [ ] Implement `CurvatureComputer.compute(points, smoothingWindow)` — curvature at each point
- [ ] Implement rolling average smoothing (window size 5-7, configurable)
- [ ] Write tests: resample 3 far-apart points → verify ~10m spacing
- [ ] Write tests: circular arc points → verify smoothed curvature within 5% of true radius
- [ ] Write tests: straight line → verify near-zero curvature

### Phase A4: Segmentation & Classification
- [ ] Implement `Segmenter.segment(curvatures, config)` — split into curve/straight segments
- [ ] Handle curvature threshold (radius < 500m = "in curve")
- [ ] Handle straight gap merging (< 50m gap → merge curves)
- [ ] Implement `Classifier.classify(segment, points, config)`
  - [ ] Direction: dominant cross product sign
  - [ ] Minimum radius in segment
  - [ ] Severity from radius thresholds (PRD Section 7.5)
  - [ ] Arc length (sum of point distances)
  - [ ] Tightening/Opening (compare first-third vs last-third avg radius)
  - [ ] Total angle change (entry bearing vs exit bearing)
  - [ ] 90-degree detection (85-95° angle, < 50m arc)
- [ ] Write tests: known hairpin → HAIRPIN severity
- [ ] Write tests: constant-radius curve → no TIGHTENING modifier
- [ ] Write tests: decreasing-radius curve → TIGHTENING modifier
- [ ] Write tests: right-angle turn → is90Degree = true

### Phase A5: Speed Advisory & Lean Angle
- [ ] Implement `SpeedAdvisor.calculate(radius, config)` — `sqrt(radius * lateralG * 9.81)`
- [ ] Convert m/s to user units (mph or km/h), round down to nearest 5
- [ ] Car mode defaults: 0.35g lateral
- [ ] Motorcycle mode defaults: 0.25g lateral
- [ ] Implement `LeanAngleCalculator.calculate(speedMs, radiusM)` — `atan(v²/(r*g))`
- [ ] Round lean to nearest 5°, cap at 45° (narrate "extreme lean" above)
- [ ] Write tests: verify reference table values from PRD (200m→94km/h car, etc.)
- [ ] Write tests: lean angle at advisory speed ≈ 14° (at 0.25g)

### Phase A6: Compound Detection
- [ ] Implement `CompoundDetector.detect(curves, config)`
  - [ ] S-bend: 2 curves, opposite direction, < 50m gap
  - [ ] Chicane: S-bend where both ≥ SHARP
  - [ ] Series: 3+ curves linked with < 50m gaps
  - [ ] Tightening sequence: same direction, each tighter
- [ ] Write tests: left+right close curves → S_BEND
- [ ] Write tests: sharp left + sharp right close → CHICANE
- [ ] Write tests: 4 linked curves → SERIES with correct count

### Phase A7: Data Quality & Route Analyzer
- [ ] Implement `DataQualityChecker.check(originalPoints, interpolatedPoints)`
  - [ ] Detect segments where original node spacing > 100m with bearing change
  - [ ] Return confidence scores (0.0–1.0) per segment
- [ ] Implement `RouteAnalyzer.analyzeRoute(points, config): List<RouteSegment>`
  - [ ] Orchestrate: interpolate → curvature → segment → classify → compound → quality
  - [ ] Wire all pipeline stages together
- [ ] Write integration test: full pipeline on synthetic known road
- [ ] Write test: sparse data → low confidence flag

### Phase A8: Map Matcher
- [ ] Implement `MapMatcher.matchToRoute(gpsPosition, routePoints)`
  - [ ] Find nearest route segment (efficient search — spatial indexing or sliding window)
  - [ ] Project GPS onto segment → snapped position
  - [ ] Compute route progress (distance from start)
  - [ ] Compute distance to next curve
  - [ ] Off-route detection (> 100m from route)
- [ ] Write tests: GPS on route → correct progress
- [ ] Write tests: GPS offset from route → correct snap
- [ ] Write tests: GPS > 100m away → off-route flag

### Phase A9: Test Fixtures with Real Road Data
- [ ] Create `fixtures/simple_hairpin.json` — synthetic tight hairpin
- [ ] Create `fixtures/s_bend.json` — synthetic S-bend
- [ ] Create `fixtures/long_straight.json` — straight road
- [ ] Create `fixtures/sparse_data.json` — low node density
- [ ] Source real road data from OSM for:
  - [ ] `fixtures/stelvio_pass.json` (iconic switchbacks)
  - [ ] `fixtures/tail_of_dragon.json` (318 curves in 11 miles)
  - [ ] `fixtures/col_de_turini.json` (rally stage, mixed curves)
- [ ] Run full pipeline on all fixtures, verify reasonable output
- [ ] Validate: Stelvio >80% hairpins detected, Tail of Dragon linked curves detected

---

## Stream B: Narration Engine

> **Depends on:** Stream A types only (A1). Can start templates in parallel with A2-A8.

### Phase B1: Narration Types
- [ ] Implement `narration/types/NarrationEvent.kt` (text, priority, triggerDistance, curve ref)
- [ ] Implement `narration/types/NarrationConfig.kt` (mode, verbosity, units, look-ahead, etc.)
- [ ] Implement `narration/types/SpeedUnit.kt` enum (MPH, KMH)
- [ ] Implement `narration/types/DrivingMode.kt` enum (CAR, MOTORCYCLE)

### Phase B2: Template Engine
- [ ] Implement `TemplateEngine.generateNarration(curve, config): String`
- [ ] Direction + severity: "Sharp right ahead"
- [ ] Tightening modifier: append ", tightening"
- [ ] Opening modifier: append ", opening" (Standard+ verbosity)
- [ ] Long/Holds modifier: "holds for X meters" (Standard+)
- [ ] Speed advisory: ", slow to 45"
- [ ] 90-degree: "90 degree right ahead, slow to 25"
- [ ] Motorcycle lean angle: ", lean 30 degrees"
- [ ] Motorcycle tightening: prepend "Caution, "
- [ ] Compound S-bend: "Left into right, S-bend, moderate"
- [ ] Compound chicane: "Chicane, left-right, slow to 30"
- [ ] Compound series: "Series of 5 curves, sharp"
- [ ] Straight narration (Detailed verbosity): "Straight, 300 meters"
- [ ] Low confidence: append appropriate indicator
- [ ] Sparse data warning: "Low data quality ahead — curve information may be incomplete"
- [ ] Write exhaustive tests: every severity × direction × modifier combination
- [ ] Write tests: motorcycle mode adds lean angles
- [ ] Write tests: verbosity filtering (Minimal skips gentle/moderate)

### Phase B3: Timing Calculator
- [ ] Implement `TimingCalculator.announcementDistance(speed, config)`
  - [ ] `max(speed * lookAheadSeconds, MIN_ANNOUNCEMENT_DISTANCE)`
  - [ ] For braking: `max(above, brakingDistance * 1.5)`
  - [ ] Braking distance: `(v² - v_advisory²) / (2 * decelRate)`
  - [ ] Car decel: 4.0 m/s², Motorcycle: 3.0 m/s²
- [ ] Write tests: stationary → MIN_ANNOUNCEMENT_DISTANCE (100m)
- [ ] Write tests: 100 km/h car → ~222m lookahead
- [ ] Write tests: braking needed → extended distance

### Phase B4: Narration Queue & Manager
- [ ] Implement `NarrationQueue` — priority queue of NarrationEvents
  - [ ] Priority order: hairpin > sharp > firm > moderate > gentle > straight
  - [ ] Higher severity interrupts lower severity in-progress
  - [ ] Same/lower severity queues after current
  - [ ] Mark events as delivered (no re-triggering)
- [ ] Implement `NarrationManager` — orchestrates queue + GPS position
  - [ ] Accept GPS position updates
  - [ ] Compute distance to each upcoming curve
  - [ ] Trigger narration when within announcement distance
  - [ ] Off-route handling: pause narration, play warning
- [ ] Write tests: queue ordering
- [ ] Write tests: interrupt behavior (sharp preempts gentle)
- [ ] Write tests: no re-delivery of same narration

### Phase B5: TTS Engine
- [ ] Implement `TtsEngine` — Android TextToSpeech wrapper
  - [ ] Initialize TTS on app start
  - [ ] `speak(text, priority)` — interrupt or queue based on priority
  - [ ] Audio focus: AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
  - [ ] Configurable speech rate (0.5x–2.0x)
  - [ ] System voice picker support
- [ ] Test on device: verify audio ducking works with music/nav apps

---

## Stream C: Android App Shell

> **Can start immediately in parallel with Streams A & B.**

### Phase C1: Project Scaffolding & DI
- [ ] Set up Android app module with Jetpack Compose
- [ ] Configure Hilt dependency injection
- [ ] Set up `AppModule.kt` with Hilt providers
- [ ] Configure min SDK 26, target SDK 34
- [ ] Add required permissions: `ACCESS_FINE_LOCATION`, `READ_EXTERNAL_STORAGE` / media permissions
- [ ] Set up Compose navigation (Home → Session → Settings)
- [ ] Implement `Theme.kt` with severity color system (PRD Section 8.2)

### Phase C2: Data Layer
- [ ] Implement `GpxParser.kt` — parse GPX files to `List<LatLon>`
  - [ ] Handle GPX track points (`<trkpt>`)
  - [ ] Handle GPX route points (`<rtept>`) as fallback
  - [ ] Error handling for malformed files
- [ ] Implement `LocationProvider.kt` — FusedLocationProvider wrapper
  - [ ] Request location updates (high accuracy, ~1s interval)
  - [ ] Expose as Kotlin Flow
  - [ ] Handle permissions gracefully
- [ ] Implement `UserPreferences.kt` — DataStore for all settings
  - [ ] Mode (Car/Motorcycle)
  - [ ] Units (mph/km/h)
  - [ ] Verbosity (1/2/3)
  - [ ] Lateral G, Look-ahead time, TTS rate
  - [ ] Lean angle narration, surface warnings toggles
- [ ] Implement `OverpassClient.kt` — surface tag queries (optional, motorcycle mode)
  - [ ] Build Overpass QL query for route corridor
  - [ ] Parse surface tags
  - [ ] Cache results locally
  - [ ] Graceful failure when offline

### Phase C3: Home Screen
- [ ] Implement `HomeScreen.kt`
  - [ ] App logo + name
  - [ ] "Load GPX" button → system file picker (SAF)
  - [ ] Recently loaded routes list (stored in DataStore)
  - [ ] Car/Motorcycle mode toggle
  - [ ] Settings gear icon → navigate to Settings
- [ ] Implement `HomeViewModel.kt`
  - [ ] Handle file picker result
  - [ ] Parse GPX → run RouteAnalyzer → cache result
  - [ ] Show loading state during analysis
  - [ ] Navigate to Session when ready

### Phase C4: Settings Screen
- [ ] Implement `SettingsScreen.kt` — all settings from PRD Section 8.1
  - [ ] Mode: Car / Motorcycle toggle
  - [ ] Units: mph / km/h
  - [ ] Verbosity: Minimal / Standard / Detailed
  - [ ] Lateral G threshold slider (0.20–0.50)
  - [ ] Look-ahead time slider (5–15s)
  - [ ] TTS voice picker (system voices)
  - [ ] TTS speech rate slider (0.5x–2.0x)
  - [ ] Narrate straights toggle
  - [ ] Audio ducking toggle
  - [ ] Motorcycle-only section: lean angle narration, surface warnings
- [ ] Implement `SettingsViewModel.kt` — read/write UserPreferences

### Phase C5: Session Screen
- [ ] Implement `SessionScreen.kt`
  - [ ] Large current speed display (GPS speed)
  - [ ] Narration text banner (last spoken instruction, high contrast)
  - [ ] Upcoming curves list (next 5)
  - [ ] Speed advisory display (prominent when active)
  - [ ] Lean angle display (motorcycle mode)
  - [ ] Controls: Play/Pause, Mute, Stop
  - [ ] Verbosity quick-toggle (tap cycles levels)
- [ ] Implement `SessionViewModel.kt`
  - [ ] Wire GPS → MapMatcher → NarrationManager
  - [ ] Expose upcoming curves as StateFlow
  - [ ] Expose current narration text as StateFlow
  - [ ] Handle play/pause/stop lifecycle
- [ ] Implement `SpeedDisplay.kt` component
- [ ] Implement `NarrationBanner.kt` component
- [ ] Implement `UpcomingCurvesList.kt` + `CurveListItem.kt`
  - [ ] Direction arrow icon
  - [ ] Severity color coding (PRD color system)
  - [ ] Distance to curve
  - [ ] Brief text description
  - [ ] Low-confidence warning indicator
- [ ] Implement `LeanAngleIndicator.kt` component (motorcycle mode)

### Phase C6: Integration Wiring
- [ ] Wire GPX load → engine analysis → narration event generation
- [ ] Wire GPS location flow → MapMatcher → NarrationManager → TTS
- [ ] Handle session lifecycle (start, pause, resume, stop)
- [ ] Handle off-route detection → UI warning + pause narration
- [ ] Handle sparse data warning → audio + visual indicator
- [ ] Keep screen awake during active session (WAKE_LOCK)
- [ ] Handle background audio (foreground service for GPS + TTS)

### Phase C7: Safety & Legal
- [ ] First-launch disclaimer dialog (PRD Section 14.1)
- [ ] About screen with full disclaimer text
- [ ] "Don't show again" preference for disclaimer
- [ ] Verify audio-first design — no required visual interactions during driving

---

## Stream D: Quality & Review (Critique-Driven)

> **Runs after each phase completion. Uses a dedicated review agent.**

### After Stream A completion:
- [ ] Review agent: verify all engine tests pass
- [ ] Review agent: audit algorithm against PRD Section 7 specs
- [ ] Review agent: check edge cases (empty input, single point, two points, very short curves)
- [ ] Review agent: verify speed advisory reference tables match PRD

### After Stream B completion:
- [ ] Review agent: verify all narration templates match PRD Section 6 examples
- [ ] Review agent: check every severity × direction × modifier text output
- [ ] Review agent: verify timing calculations against PRD formulas
- [ ] Review agent: audit queue priority behavior

### After Stream C completion:
- [ ] Review agent: verify all settings from PRD Section 8.1 are present
- [ ] Review agent: check color system matches PRD Section 8.2
- [ ] Review agent: verify audio focus behavior
- [ ] Review agent: check permission handling

### Final Integration:
- [ ] End-to-end test: load GPX → analyze → start session → verify narrations fire
- [ ] Performance: GPX load + analysis < 3 seconds (PRD metric)
- [ ] Performance: time to first narration ready < 5 seconds (PRD metric)
- [ ] Review all safety disclaimers present

---

## Execution Strategy

### Parallel Agent Assignments

| Agent | Stream | Focus |
|-------|--------|-------|
| **Agent 1** | Stream A (Phases A1–A9) | Pure Kotlin engine — all geo math, curvature, segmentation, classification |
| **Agent 2** | Stream B (Phases B1–B5) | Narration templates, timing, queue logic (starts after A1 types exist) |
| **Agent 3** | Stream C (Phases C1–C7) | Android app shell — UI, GPS, file handling, DI, wiring |
| **Agent 4** | Stream D | Critique/review after each phase — tests, PRD compliance, edge cases |

### Dependency Graph

```
A1 (types) ──────────────┬──→ A2–A9 (engine pipeline)
                         ├──→ B1–B5 (narration, after A1)
                         └──→ C1 (app scaffold, immediate)
                              C2–C4 (data + screens, parallel with A/B)

A9 (engine done) ──┐
B5 (narration done)┤──→ C6 (integration wiring)
C5 (session screen)┘

C6 (integrated) ───────→ C7 (safety) → D (final review)
```

### Phase Order (Critical Path)

1. **A1** → Types (unlocks everything)
2. **A2 + C1** → Geo math + App scaffold (parallel)
3. **A3 + B1 + C2** → Interpolation + Narration types + Data layer (parallel)
4. **A4 + B2 + C3** → Segmentation + Templates + Home screen (parallel)
5. **A5 + B3 + C4** → Speed/Lean + Timing + Settings (parallel)
6. **A6 + B4 + C5** → Compounds + Queue + Session screen (parallel)
7. **A7-A8** → Quality checker + Map matcher
8. **A9** → Test fixtures validation
9. **B5** → TTS engine (needs device)
10. **C6** → Full integration wiring
11. **C7 + D** → Safety + Final review
