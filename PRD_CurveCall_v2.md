# Product Requirements Document: CurveCall

**Version:** 2.0
**Date:** February 11, 2026
**Status:** Final Draft — Ready for Development

---

## 1. Overview

CurveCall is an open-source Android app that functions as a digital co-driver for enthusiast drivers and motorcyclists. It analyzes road geometry from GPX files and OpenStreetMap data, then delivers real-time spoken narration of upcoming curves — severity, direction, tightening behavior, and speed advisories — in a natural language style inspired by rally pacenotes.

**One-liner:** A digital co-driver that reads the road ahead, narrating curves and speed advisories in real-time using OSM geometry data.

**Product name:** CurveCall

---

## 2. Problem Statement

Enthusiast drivers and motorcyclists on unfamiliar roads (mountain passes, scenic routes, backroads) have no advance knowledge of what's coming. Sudden hairpins, tightening curves, and blind crests catch riders off guard — and on a motorcycle, the margin for error is near zero.

Rally drivers solve this with a co-driver reading pacenotes, but those notes are hand-written after multiple reconnaissance passes. OSM contains enough geometric data (node coordinates of every road) to computationally derive curve geometry and generate these notes automatically for any mapped road in the world.

---

## 3. Target Users

### 3.1 Primary Persona: Enthusiast Road Driver

- Drives for enjoyment on twisty roads (mountain passes, canyon roads, touring routes)
- Wants to drive smoothly and confidently on unfamiliar roads
- Appreciates technical driving language but isn't a professional racer
- Uses a phone mount for navigation
- Often plans routes in advance using tools like Kurviger, Calimoto, or Google Maps and exports GPX files

### 3.2 Secondary Persona: Touring Motorcyclist

- Higher stakes — tightening curves and surface changes are genuinely dangerous
- Needs earlier warnings and more conservative speed advisories
- Values lean angle awareness for cornering confidence
- Frequently plans routes in advance via GPX

**Non-goals for v1:** Visually impaired users, professional motorsport, commercial fleet drivers, daily commuters.

---

## 4. Product Strategy — Two-Phase Approach

### Phase 1: Standalone Curve Narration App (MVP)

A focused, audio-first Android app. The user loads a GPX file, presses start, and listens to curve-by-curve narration while driving. No map view, no turn-by-turn navigation. The user can run OsmAnd, Google Maps, or any other nav app alongside CurveCall for directions.

**Why this first:**
- Ships fast — no map rendering, no routing engine, no search UI
- Gets the core algorithm (curve detection + narration) into real-world testing immediately
- GPX import is the natural input for the enthusiast audience who already plan routes
- Validates the product before investing in full navigation

**Phase 1 deliverable:** Audio-only app with a simple list UI showing upcoming curves, GPX import, motorcycle mode, and TTS narration.

### Phase 2: Full Navigation with MapLibre

Integrate [MapLibre Navigation Android](https://github.com/maplibre/maplibre-navigation-android) for turn-by-turn navigation. This is an open-source fork of the Mapbox Navigation SDK that provides navigation logic without telemetry, API keys, or per-MAU costs. It supports OSRM and Valhalla as routing backends.

**Phase 2 deliverable:** A single app that handles route planning, turn-by-turn directions ("turn left on Highway 9"), AND curve narration ("sharp right ahead, tightening, slow to 35"). Both audio streams share priority logic — curve warnings override turn instructions.

**Why MapLibre Navigation:**
- Fully open source (MIT-licensed fork of Mapbox Nav SDK v0.19)
- Proven in production by Kurviger — an enthusiast driving app with the same target audience
- Works with self-hosted OSRM/Valhalla, no vendor lock-in
- Kotlin-native, excellent Jetpack Compose support via MapLibre Native
- No telemetry, no Mapbox API keys required

---

## 5. Tech Stack

| Component | Choice | Rationale |
|---|---|---|
| **Language** | Kotlin | Android-native, MapLibre Navigation SDK is Kotlin |
| **UI Framework** | Jetpack Compose | Modern Android UI, clean integration with MapLibre in Phase 2 |
| **Min SDK** | API 26 (Android 8.0) | Covers 95%+ of active devices |
| **GPS** | Android FusedLocationProvider | Best accuracy, battery efficiency |
| **TTS** | Android TextToSpeech API | Zero latency, works offline, no cloud dependency |
| **Geo Math** | Custom (see Section 7) | Lightweight, no heavy dependencies for v1 |
| **GPX Parsing** | Custom XML parser or [GPXParser](https://github.com/ticofab/android-gpx-parser) | Well-established Android GPX library |
| **Build** | Gradle + Kotlin DSL | Standard Android tooling |
| **DI** | Hilt | Standard for modern Android apps |
| **State** | Kotlin StateFlow + ViewModel | Compose-native reactive state |

### Phase 2 Additions

| Component | Choice | Rationale |
|---|---|---|
| **Map Rendering** | MapLibre Native Android | Free, OSM-native, vector tiles |
| **Navigation** | MapLibre Navigation Android SDK | Open source turn-by-turn |
| **Routing** | OSRM or Valhalla (self-hosted or public) | Returns detailed geometry, open source |
| **Tile Server** | OpenFreeMap or self-hosted | Free OSM vector tiles |

---

## 6. Narration Design

### 6.1 Voice & Style

The narration uses **technical natural language** — precise driving terminology delivered in readable phrases, not clipped rally shorthand and not vague casual language.

**Examples:**

| Road Situation | Narration |
|---|---|
| Moderate left curve, constant radius | "Left curve ahead, moderate" |
| Sharp right, radius decreases | "Sharp right ahead, tightening, slow to 40" |
| Two linked curves | "Left into right, S-bend, moderate" |
| Very tight switchback | "Hairpin left ahead, slow to 20" |
| Right angle junction turn | "90 degree right ahead, slow to 25" |
| Long sweeping bend | "Long gentle left, holds for 400 meters" |
| Straight after curves | "Straight, 300 meters" (optional, configurable) |

**Motorcycle mode additions:**

| Situation | Car Narration | Motorcycle Narration |
|---|---|---|
| Moderate left, 100m radius | "Left curve ahead, moderate" | "Left curve ahead, moderate, lean 20 degrees" |
| Sharp right, 40m radius | "Sharp right, slow to 45" | "Sharp right, slow to 35, lean 35 degrees" |
| Surface change available | (not narrated in v1) | "Caution, gravel surface ahead" |

### 6.2 Vocabulary

**Severity terms** (mapped to curve radius — see Section 7.4):

| Term | Meaning |
|---|---|
| `gentle` | Barely needs steering input |
| `moderate` | Clear curve, comfortable at moderate speed |
| `firm` | Requires meaningful speed reduction |
| `sharp` | Significant braking required |
| `hairpin` | Near-180° direction change |

**Modifier terms:**

| Term | Meaning | Priority |
|---|---|---|
| `tightening` | Radius decreases through the curve | ALWAYS narrate — this is dangerous |
| `opening` | Radius increases through the curve | Optional |
| `holds` | Constant radius over a long arc (>200m) | Standard+ verbosity |
| `long` | Arc length > 200m | Standard+ verbosity |
| `into` | Next curve follows immediately, no straight | Always narrate |

**Compound calls:**

| Term | Meaning |
|---|---|
| `S-bend` | Left-right or right-left pair |
| `chicane` | Tight S-bend |
| `series of [N] curves` | 3+ linked curves |

**Speed advisories:** "slow to [speed]" — recommended entry speed in user's preferred unit (mph/km/h).

**Lean angle (motorcycle mode only):** "lean [X] degrees" — derived from speed advisory and curve radius.

### 6.3 Timing Model

Narration must arrive early enough for the driver to react, but not so early it's forgotten.

```
announcement_distance = max(
    current_speed * LOOK_AHEAD_SECONDS,
    MIN_ANNOUNCEMENT_DISTANCE
)

Default values:
    LOOK_AHEAD_SECONDS = 8       (car mode)
    LOOK_AHEAD_SECONDS = 10      (motorcycle mode — more reaction time)
    MIN_ANNOUNCEMENT_DISTANCE = 100m
```

For speed advisories that require braking:

```
braking_distance = (current_speed² - advisory_speed²) / (2 * DECEL_RATE)
announcement_distance = max(announcement_distance, braking_distance * 1.5)

    DECEL_RATE = 4.0 m/s²   (car — comfortable braking)
    DECEL_RATE = 3.0 m/s²   (motorcycle — more conservative)
```

### 6.4 Verbosity Levels

| Level | Name | Behavior |
|---|---|---|
| 1 | Minimal | Only `sharp` and `hairpin` + speed advisories |
| 2 | Standard (default) | All curves `moderate` and above + speed advisories |
| 3 | Detailed | All curves including `gentle` + straights between curves |

### 6.5 Sparse Data Warning

When the app detects a road segment with low OSM node density (node spacing > 100m in a section that appears curved based on bearing change), it should:

1. Play a one-time audio warning: "Low data quality ahead — curve information may be incomplete"
2. Show a visual indicator in the upcoming curves list
3. Continue narrating whatever curves it can detect, but flag them as low-confidence
4. Resume normal narration when node density improves

The app should never silently stop narrating — the user must always know if the data is degraded.

---

## 7. Curve Detection & Classification — Core Algorithm

This is the most critical component and the core IP of the product. It transforms raw GPS coordinates into classified, narration-ready curve descriptions.

### 7.1 Input

An ordered list of `(latitude, longitude)` coordinates representing the route. In Phase 1, these come from GPX file track points. In Phase 2, they come from the routing engine (OSRM/Valhalla geometry).

Node density varies: typical spacing on curved roads is 5–30m. On straight highways it can be 100m+. GPX files from route planners tend to have fairly dense points.

### 7.2 Preprocessing: Interpolation

Resample the route to uniform spacing (~10m between points) to normalize curvature computation regardless of source density.

```
Algorithm:
1. Walk along the polyline
2. At every 10m of cumulative distance, interpolate a new point
3. Output: uniformly spaced point list
```

### 7.3 Curvature Computation

For each triplet of consecutive points (P1, P2, P3), compute the radius of the circumscribed circle (Menger curvature):

```kotlin
fun mengerRadius(p1: LatLon, p2: LatLon, p3: LatLon): Double {
    val a = distance(p1, p2)
    val b = distance(p2, p3)
    val c = distance(p1, p3)
    val s = (a + b + c) / 2.0
    val area = sqrt(s * (s - a) * (s - b) * (s - c))
    return if (area < 1e-10) Double.MAX_VALUE  // collinear = straight
    else (a * b * c) / (4.0 * area)
}
```

Apply a **smoothing window** (rolling average over 5–7 points) to reduce noise from imprecise node placement.

Determine **curve direction** from the sign of the cross product of vectors (P1→P2) and (P2→P3): positive = left, negative = right.

### 7.4 Curve Segmentation

```
CURVATURE_THRESHOLD = 1/500    (radius < 500m = "in a curve")
STRAIGHT_GAP = 50m             (gap between curves to consider them separate)

Algorithm:
1. Walk points along route
2. If radius < 500m → mark as "in curve"
3. Consecutive "in curve" points form a curve segment
4. If straight gap between two curve segments < 50m → merge into compound curve
5. Everything else is a straight segment
```

### 7.5 Curve Classification

For each detected curve segment, compute:

| Property | How to Derive |
|---|---|
| **Direction** | Dominant sign of cross products across the segment (left/right) |
| **Minimum radius** | Smallest smoothed radius in the segment |
| **Severity** | From minimum radius — see table below |
| **Arc length** | Sum of point-to-point distances in the segment |
| **Tightening / Opening** | Compare average radius of first third vs. last third |
| **Total angle change** | Difference between entry bearing and exit bearing |
| **Is 90°** | If total angle change is 85–95° and arc length < 50m |

**Severity classification:**

| Severity | Radius Range | Speed Advisory Triggered |
|---|---|---|
| `gentle` | > 200m | No |
| `moderate` | 100–200m | Context-dependent |
| `firm` | 50–100m | Yes |
| `sharp` | 25–50m | Yes |
| `hairpin` | < 25m | Yes |

These thresholds are configurable and will require real-world tuning.

### 7.6 Speed Advisory Calculation

Derive recommended speed from curve radius using lateral acceleration limit:

```
advisory_speed = sqrt(radius * LATERAL_G * 9.81)

Car mode:      LATERAL_G = 0.35   (comfortable road driving)
Motorcycle mode: LATERAL_G = 0.25  (more conservative — no crumple zone)

Convert m/s to user's preferred unit (mph or km/h).
Round down to nearest 5.
```

**Reference table (car mode, 0.35g):**

| Radius | Advisory Speed |
|---|---|
| 200m | ~94 km/h — no advisory |
| 100m | ~66 km/h |
| 50m | ~47 km/h |
| 25m | ~33 km/h |
| 15m | ~25 km/h |

**Reference table (motorcycle mode, 0.25g):**

| Radius | Advisory Speed |
|---|---|
| 200m | ~79 km/h |
| 100m | ~56 km/h |
| 50m | ~39 km/h |
| 25m | ~28 km/h |
| 15m | ~21 km/h |

### 7.7 Lean Angle Calculation (Motorcycle Mode)

Lean angle can be approximated from speed and radius:

```
lean_angle_degrees = atan(v² / (radius * g)) * (180 / π)

where:
    v = advisory speed in m/s
    radius = curve minimum radius in meters
    g = 9.81 m/s²
```

At the advisory speed (0.25g lateral), this gives approximately 14° lean. At slightly higher speeds, lean increases. The narrated lean angle should be calculated at the **advisory speed** to give the rider a target.

Round to nearest 5 degrees. Cap display at 45° (beyond this, narrate "extreme lean" instead of a number).

### 7.8 Compound Curve Detection

After individual curves are classified, detect patterns:

| Pattern | Detection Rule | Narration |
|---|---|---|
| **S-bend** | Two curves, opposite direction, < 50m gap | "Left into right, S-bend, [max severity]" |
| **Chicane** | S-bend where both curves are `sharp` or tighter | "Chicane, [direction]-[direction], slow to [speed]" |
| **Series** | 3+ curves linked with < 50m gaps | "Series of [N] curves, [max severity]" |
| **Tightening sequence** | Same-direction curves, each tighter than last | "Right, tightening through [N] curves" |

---

## 8. User Interface — Phase 1

Phase 1 is **audio-first** with a minimal visual UI. No map rendering.

### 8.1 Screens

**Home Screen:**
- App logo + name (CurveCall)
- "Load GPX" button — opens file picker
- Recently loaded routes (stored locally)
- Mode toggle: Car / Motorcycle
- Settings gear icon

**Active Session Screen:**
- Large current speed display (from GPS)
- Current narration text banner (last spoken instruction, large font, high contrast)
- Upcoming curves list (next 5 curves):
  - Each shows: icon (arrow direction) + severity color + distance + brief text
  - Low-confidence curves marked with a ⚠ indicator
- Speed advisory prominently displayed when active
- Lean angle display when in motorcycle mode and advisory is active
- Controls at bottom: Play/Pause, Mute, Stop
- Verbosity quick-toggle (tap cycles: Minimal → Standard → Detailed)

**Settings Screen:**
- **Mode:** Car / Motorcycle
- **Units:** mph / km/h
- **Verbosity:** Minimal / Standard / Detailed
- **Lateral G threshold:** Slider (0.20–0.50, default 0.35 car / 0.25 motorcycle)
- **Look-ahead time:** Slider (5–15 seconds, default 8 car / 10 motorcycle)
- **TTS voice:** System voice picker
- **TTS speech rate:** Slider (0.5x – 2.0x, default 1.0)
- **Narrate straights:** On/Off (default Off)
- **Audio ducking:** On/Off (lower other app audio when narrating)
- **Lean angle narration (motorcycle only):** On/Off (default On)
- **Surface warnings (motorcycle only):** On/Off (default On, uses OSM surface tags when available)

### 8.2 Color System

Curve severity is color-coded in the upcoming list:

| Severity | Color | Hex |
|---|---|---|
| gentle | Green | #4CAF50 |
| moderate | Yellow | #FFC107 |
| firm | Orange | #FF9800 |
| sharp | Red | #F44336 |
| hairpin | Dark Red | #B71C1C |
| low confidence | Gray | #9E9E9E |

### 8.3 Audio Behavior

- TTS narration uses Android `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` — other apps (music, podcasts, OsmAnd nav) will lower volume briefly during announcements
- If the user is running a separate nav app, CurveCall's audio will interleave naturally
- Narrations never overlap — if a new narration is triggered while one is still speaking, it queues (unless the new one is higher severity, in which case it interrupts)
- Priority order: hairpin > sharp > firm > moderate > gentle > straight

---

## 9. Motorcycle Mode — Detailed Spec

Motorcycle mode is a first-class feature, not an afterthought. It changes multiple behaviors:

| Aspect | Car Mode | Motorcycle Mode |
|---|---|---|
| Default lateral G | 0.35 | 0.25 |
| Look-ahead time | 8 seconds | 10 seconds |
| Braking decel rate | 4.0 m/s² | 3.0 m/s² |
| Speed advisory | Yes | Yes (more conservative) |
| Lean angle narration | No | Yes (e.g., "lean 25 degrees") |
| Surface warnings | No | Yes (when OSM `surface` tag available) |
| Narration style | "Sharp right, slow to 45" | "Sharp right, slow to 35, lean 30 degrees" |
| Tightening emphasis | High | Very high (extra "caution" prefix) |

### 9.1 Surface Warnings (Motorcycle Mode)

When processing a GPX route, CurveCall can optionally query OSM for the `surface` tag on the underlying ways. If the surface changes to a non-paved type within a curve or approaching one, narrate:

- "Caution, gravel surface ahead"
- "Caution, unpaved section ahead"
- "Surface changes to cobblestone"

This requires an Overpass API query for the route corridor, which adds a network dependency. It should be opt-in and pre-fetched when the GPX is loaded, not during real-time driving.

**Phase 1 implementation:** Pre-fetch surface data at GPX load time. Cache locally. If network unavailable, silently skip surface warnings.

---

## 10. Data Pipeline

### 10.1 Phase 1 Flow (GPX-based)

```
[User selects GPX file]
        ↓
[Parse GPX → extract ordered (lat, lon) track points]
        ↓
[Optional: Query Overpass API for surface tags along route corridor]
        ↓
[Geometry Analysis Engine]
    ├─ Interpolation (resample to ~10m)
    ├─ Curvature computation (Menger + smoothing)
    ├─ Segmentation (curves vs. straights)
    ├─ Classification (severity, direction, modifiers)
    ├─ Compound detection (S-bends, series)
    ├─ Speed advisory calculation
    └─ Lean angle calculation (motorcycle mode)
        ↓
[Output: List<CurveSegment> — the full "pacenote script" for the route]
        ↓
[Narration Engine]
    ├─ Template mapping (CurveSegment → spoken text)
    ├─ Timing queue (distance + speed → trigger point)
    └─ TTS output
        ↓
[Real-time GPS tracking]
    ├─ Map-match position to route polyline
    ├─ Compute current speed
    └─ Trigger narrations when within announcement distance
```

### 10.2 Phase 2 Additions

```
[User enters destination OR selects GPX]
        ↓
[OSRM/Valhalla routing → route geometry + turn instructions]
        ↓
[Geometry Analysis Engine (same as Phase 1)]
        ↓
[MapLibre Navigation SDK → turn-by-turn guidance]
        +
[CurveCall Narration Engine → curve narration]
        ↓
[Audio Priority Manager]
    ├─ Curve warnings take priority over turn instructions
    ├─ Turn instructions play in gaps between curve narrations
    └─ If simultaneous, curve narration wins and turn shows as visual only
```

### 10.3 Map Matching

The GPS position must be snapped to the nearest point on the loaded route to determine distance to upcoming curves:

```kotlin
fun mapMatchToRoute(
    gpsPosition: LatLon,
    routePoints: List<LatLon>
): MapMatchResult {
    // Find nearest route segment
    // Project GPS position onto that segment
    // Return: snapped position, route progress (distance from start),
    //         distance to next curve, confidence (distance from route)
}
```

If the driver deviates more than 100m from the route, show a warning: "Off route — curve narration paused." Resume when they return.

---

## 11. Project Structure

```
curvecall/
├── app/                              # Android application module
│   ├── src/main/java/com/curvecall/
│   │   ├── MainActivity.kt
│   │   ├── ui/
│   │   │   ├── theme/
│   │   │   │   └── Theme.kt
│   │   │   ├── home/
│   │   │   │   ├── HomeScreen.kt
│   │   │   │   └── HomeViewModel.kt
│   │   │   ├── session/
│   │   │   │   ├── SessionScreen.kt        # Active narration screen
│   │   │   │   ├── SessionViewModel.kt
│   │   │   │   └── components/
│   │   │   │       ├── SpeedDisplay.kt
│   │   │   │       ├── NarrationBanner.kt
│   │   │   │       ├── UpcomingCurvesList.kt
│   │   │   │       ├── CurveListItem.kt
│   │   │   │       └── LeanAngleIndicator.kt
│   │   │   └── settings/
│   │   │       ├── SettingsScreen.kt
│   │   │       └── SettingsViewModel.kt
│   │   ├── data/
│   │   │   ├── gpx/
│   │   │   │   └── GpxParser.kt            # GPX file parsing
│   │   │   ├── osm/
│   │   │   │   └── OverpassClient.kt       # Surface tag queries
│   │   │   ├── location/
│   │   │   │   └── LocationProvider.kt      # FusedLocationProvider wrapper
│   │   │   └── preferences/
│   │   │       └── UserPreferences.kt       # DataStore preferences
│   │   └── di/
│   │       └── AppModule.kt                 # Hilt DI module
│   └── src/main/res/
│       └── ...
│
├── engine/                           # Pure Kotlin library — no Android dependencies
│   ├── src/main/kotlin/com/curvecall/engine/
│   │   ├── types/
│   │   │   ├── LatLon.kt                   # Basic coordinate type
│   │   │   ├── CurveSegment.kt             # Classified curve with all properties
│   │   │   ├── Severity.kt                 # Enum: GENTLE, MODERATE, FIRM, SHARP, HAIRPIN
│   │   │   ├── CurveModifier.kt            # Enum: TIGHTENING, OPENING, HOLDS, LONG
│   │   │   ├── CompoundType.kt             # Enum: S_BEND, CHICANE, SERIES
│   │   │   ├── RouteSegment.kt             # Either a curve or straight
│   │   │   └── AnalysisConfig.kt           # All configurable thresholds
│   │   ├── geo/
│   │   │   ├── GeoMath.kt                  # Haversine distance, bearing, interpolation
│   │   │   └── MengerCurvature.kt          # Three-point curvature calculation
│   │   ├── pipeline/
│   │   │   ├── Interpolator.kt             # Resample to uniform spacing
│   │   │   ├── CurvatureComputer.kt        # Curvature at each point + smoothing
│   │   │   ├── Segmenter.kt                # Split into curves vs. straights
│   │   │   ├── Classifier.kt               # Severity, direction, modifiers
│   │   │   ├── CompoundDetector.kt          # S-bends, series, tightening sequences
│   │   │   ├── SpeedAdvisor.kt             # Physics-based speed recommendations
│   │   │   ├── LeanAngleCalculator.kt      # Motorcycle lean angle from speed + radius
│   │   │   └── DataQualityChecker.kt       # Detect sparse/low-confidence segments
│   │   ├── RouteAnalyzer.kt                # Pipeline orchestrator
│   │   │                                    # analyzeRoute(points, config) → List<RouteSegment>
│   │   └── MapMatcher.kt                   # Snap GPS position to route, compute progress
│   │
│   └── src/test/kotlin/com/curvecall/engine/
│       ├── pipeline/
│       │   ├── InterpolatorTest.kt
│       │   ├── CurvatureComputerTest.kt
│       │   ├── SegmenterTest.kt
│       │   ├── ClassifierTest.kt
│       │   ├── CompoundDetectorTest.kt
│       │   ├── SpeedAdvisorTest.kt
│       │   ├── LeanAngleCalculatorTest.kt
│       │   └── DataQualityCheckerTest.kt
│       ├── RouteAnalyzerTest.kt             # Integration tests
│       ├── MapMatcherTest.kt
│       └── fixtures/
│           ├── stelvio_pass.json            # Real OSM node sequences
│           ├── tail_of_dragon.json
│           ├── col_de_turini.json
│           ├── simple_hairpin.json
│           ├── s_bend.json
│           ├── long_straight.json
│           └── sparse_data.json             # Low node density test case
│
├── narration/                        # Narration engine — depends on engine module
│   ├── src/main/kotlin/com/curvecall/narration/
│   │   ├── types/
│   │   │   ├── NarrationEvent.kt           # Text + priority + trigger distance
│   │   │   └── NarrationConfig.kt          # Verbosity, mode, units
│   │   ├── TemplateEngine.kt               # CurveSegment → spoken text string
│   │   ├── TimingCalculator.kt             # Speed + distance → when to announce
│   │   ├── NarrationQueue.kt               # Priority queue of upcoming announcements
│   │   ├── NarrationManager.kt             # Orchestrates queue + GPS + triggers
│   │   └── TtsEngine.kt                    # Android TTS abstraction
│   │
│   └── src/test/kotlin/com/curvecall/narration/
│       ├── TemplateEngineTest.kt            # Test all narration text outputs
│       ├── TimingCalculatorTest.kt
│       └── NarrationQueueTest.kt
│
└── build.gradle.kts                  # Root build file
```

### Key Architecture Decisions

1. **`engine` is a pure Kotlin module with ZERO Android dependencies.** This means it can be unit tested fast without an emulator, used in a CLI tool, or ported to KMP later. The function `RouteAnalyzer.analyzeRoute(points: List<LatLon>, config: AnalysisConfig): List<RouteSegment>` is the core API.

2. **`narration` bridges engine output to audio.** It depends on `engine` for types but adds Android TTS. The `TemplateEngine` is pure Kotlin (testable), only `TtsEngine` touches Android APIs.

3. **`app` is the thin Android shell.** UI, GPS, file picking, preferences. Minimal business logic.

---

## 12. Development Plan — Build Order

This section is designed for an AI coding agent (Claude Code) to use as a development roadmap. Each phase has clear inputs, outputs, and a definition of done.

### Phase 1.1: Engine — Geo Math & Curvature (Week 1)

**Build:**
- `engine/types/` — All data types (`LatLon`, `CurveSegment`, `Severity`, etc.)
- `engine/geo/GeoMath.kt` — Haversine distance, bearing calculation, point interpolation
- `engine/geo/MengerCurvature.kt` — Three-point curvature computation
- `engine/pipeline/Interpolator.kt` — Resample points to uniform spacing
- `engine/pipeline/CurvatureComputer.kt` — Curvature at each point with rolling average smoothing

**Test with:** Synthetic point sequences (known circles, known straights, known spirals).

**Done when:** Given 3 points on a circle of known radius, `MengerCurvature` returns that radius ±1%. `CurvatureComputer` on a sequence of points along a 100m radius circle returns smoothed values within 5% of 100m.

### Phase 1.2: Engine — Segmentation & Classification (Week 1–2)

**Build:**
- `engine/pipeline/Segmenter.kt` — Split curvature data into curve vs. straight segments
- `engine/pipeline/Classifier.kt` — Assign severity, direction, tightening/opening, arc length
- `engine/pipeline/SpeedAdvisor.kt` — Speed advisory from radius + G-force
- `engine/pipeline/LeanAngleCalculator.kt` — Lean angle from advisory speed + radius
- `engine/pipeline/DataQualityChecker.kt` — Flag low-density segments

**Test with:** `fixtures/*.json` — real road geometries with expected classifications.

**Done when:** Stelvio Pass fixture correctly identifies >80% of hairpins. Tail of the Dragon identifies the linked curves. Speed advisories are within ±10 km/h of manually calculated values.

### Phase 1.3: Engine — Compound Detection & Orchestrator (Week 2)

**Build:**
- `engine/pipeline/CompoundDetector.kt` — S-bends, chicanes, series
- `engine/RouteAnalyzer.kt` — Pipeline orchestrator, the main entry point
- `engine/MapMatcher.kt` — Snap GPS to route, compute progress

**Test with:** S-bend and series fixtures. Map matcher with simulated GPS positions along a route.

**Done when:** `RouteAnalyzer.analyzeRoute()` takes a list of points and returns a fully classified list of `RouteSegment` objects. Map matcher correctly tracks progress along a route with ±20m accuracy.

### Phase 1.4: Narration Engine (Week 2–3)

**Build:**
- `narration/TemplateEngine.kt` — All text generation logic
- `narration/TimingCalculator.kt` — Announcement distance from speed
- `narration/NarrationQueue.kt` — Priority queue with interrupt logic
- `narration/NarrationManager.kt` — Ties queue to GPS position updates
- `narration/TtsEngine.kt` — Android TTS wrapper

**Test with:** Feed known `CurveSegment` objects into `TemplateEngine`, verify text output. Test timing calculator with various speeds.

**Done when:** Every combination of severity × direction × modifier × compound type produces correct narration text. Motorcycle mode adds lean angles. Queue correctly handles priority interrupts.

### Phase 1.5: Android App Shell (Week 3–4)

**Build:**
- Home screen with GPX file picker
- Settings screen with all configurable options
- Session screen with speed, narration banner, upcoming curves list
- GPS location tracking with `FusedLocationProvider`
- GPX parser
- Wire everything: GPX → Engine → Narration → TTS

**Test with:** Load a real GPX file, drive the route (or simulate with mock location), verify narration plays correctly.

**Done when:** The app loads a GPX file, shows upcoming curves, and speaks narration at the right time while driving. Motorcycle mode shows lean angles. Sparse data warnings appear on low-quality segments.

### Phase 1.6: Real-World Testing & Tuning (Week 4–5)

- Record test drives on known roads
- Compare CurveCall output against manually written pacenotes
- Tune severity thresholds, smoothing window size, timing
- Fix edge cases (very short curves, overlapping announcements, GPS jitter)
- Polish TTS pacing and speech rate

### Phase 2.1: MapLibre Integration (Future)

- Add MapLibre Native Android for map rendering
- Add MapLibre Navigation Android SDK for turn-by-turn
- Implement route planning UI (search + destination)
- Build audio priority manager (curve narration > turn instructions)
- Add visual curve overlay on map (color-coded arcs)

---

## 13. Data Model — Key Types

```kotlin
// === Core Types ===

data class LatLon(val lat: Double, val lon: Double)

enum class Severity { GENTLE, MODERATE, FIRM, SHARP, HAIRPIN }

enum class Direction { LEFT, RIGHT }

enum class CurveModifier { TIGHTENING, OPENING, HOLDS, LONG }

enum class CompoundType { S_BEND, CHICANE, SERIES, TIGHTENING_SEQUENCE }

data class CurveSegment(
    val direction: Direction,
    val severity: Severity,
    val minRadius: Double,              // meters
    val arcLength: Double,              // meters
    val modifiers: Set<CurveModifier>,
    val totalAngleChange: Double,       // degrees
    val is90Degree: Boolean,
    val advisorySpeedMs: Double?,       // m/s, null if no advisory needed
    val leanAngleDeg: Double?,          // degrees, motorcycle only
    val compoundType: CompoundType?,    // null if standalone curve
    val compoundSize: Int?,             // number of curves in compound
    val confidence: Float,              // 0.0–1.0, based on data quality
    val startIndex: Int,                // index in route point list
    val endIndex: Int,
    val startPoint: LatLon,
    val endPoint: LatLon,
    val distanceFromStart: Double       // meters from route start
)

data class StraightSegment(
    val length: Double,                 // meters
    val startIndex: Int,
    val endIndex: Int,
    val distanceFromStart: Double
)

sealed class RouteSegment {
    data class Curve(val data: CurveSegment) : RouteSegment()
    data class Straight(val data: StraightSegment) : RouteSegment()
}

// === Configuration ===

data class AnalysisConfig(
    val interpolationSpacing: Double = 10.0,         // meters
    val smoothingWindow: Int = 7,                     // points
    val curvatureThresholdRadius: Double = 500.0,     // meters
    val straightGapMerge: Double = 50.0,              // meters
    val severityThresholds: SeverityThresholds = SeverityThresholds(),
    val lateralG: Double = 0.35,                      // g-force
    val isMotorcycleMode: Boolean = false,
    val sparseNodeThreshold: Double = 100.0           // meters
)

data class SeverityThresholds(
    val gentle: Double = 200.0,      // radius > 200m
    val moderate: Double = 100.0,    // 100–200m
    val firm: Double = 50.0,         // 50–100m
    val sharp: Double = 25.0,        // 25–50m
    // below 25m = hairpin
)

// === Narration ===

data class NarrationEvent(
    val text: String,                   // "Sharp right ahead, tightening, slow to 35"
    val priority: Int,                  // higher = more important
    val triggerDistanceFromStart: Double,// where on the route to announce
    val associatedCurve: CurveSegment,
    val delivered: Boolean = false
)

enum class DrivingMode { CAR, MOTORCYCLE }

data class NarrationConfig(
    val mode: DrivingMode = DrivingMode.CAR,
    val verbosity: Int = 2,             // 1=minimal, 2=standard, 3=detailed
    val units: SpeedUnit = SpeedUnit.KMH,
    val lookAheadSeconds: Double = 8.0,
    val narrateStraights: Boolean = false,
    val narrateLeanAngle: Boolean = true,
    val narrateSurface: Boolean = true
)

enum class SpeedUnit { MPH, KMH }
```

---

## 14. Safety & Legal

### 14.1 Required Disclaimers

Display on first launch and in Settings → About:

> CurveCall provides road geometry information derived from OpenStreetMap data. Speed advisories and lean angle suggestions are calculated estimates based on road geometry only. They do NOT account for road surface condition, weather, tire condition, traffic, visibility, vehicle capability, or rider skill level.
>
> The driver/rider is solely responsible for their speed, lean angle, and all driving decisions. CurveCall is a driving aid, not a safety system. It may contain errors or use outdated map data.
>
> Never interact with the CurveCall UI while driving. Use audio narration only.

### 14.2 Safety Design Principles

- Audio-first — the driver should never need to look at the screen
- No complex interactions while driving — all controls are single-tap
- Speed advisories are conservative by default (0.35g car, 0.25g motorcycle)
- Speed advisories are capped at the road's legal speed limit when available from OSM `maxspeed` tag
- Tightening curves always get a "caution" prefix in motorcycle mode
- Off-route detection pauses narration rather than narrating wrong curves
- Low-confidence data is flagged, never presented as reliable

---

## 15. Success Metrics

| Metric | Target | How to Measure |
|---|---|---|
| Curve detection accuracy | > 90% of curves classified within ±1 severity level | Compare against manually annotated test routes |
| False positive rate | < 5% | Curves narrated where road is actually straight |
| Missed curve rate (sharp+) | < 2% | Sharp/hairpin curves not detected |
| Narration timing | Within ±2s of ideal braking point | Compare trigger time vs. calculated ideal |
| "Too late" warnings | < 10% of narrations | User feedback / test drive analysis |
| Speed advisory accuracy | Within ±10 km/h of comfortable cornering speed | Test drive validation |
| App crash rate during session | < 0.1% | Crashlytics |
| GPX load time (avg route) | < 3 seconds | Instrumentation |
| Time from GPX load to first narration ready | < 5 seconds | Instrumentation |

---

## 16. Technical Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Curvature algorithm too noisy on real data | High | High | Extensive smoothing tuning; test on 10+ real routes before shipping; adjustable smoothing window |
| GPX track point density too low for accurate curvature | Medium | High | Interpolation + data quality checker; warn user on low-confidence segments |
| TTS latency causes late announcements | Medium | High | Pre-synthesize next 3 narrations; use platform TTS (not cloud) |
| Speed advisories feel wrong | Medium | High | Conservative defaults; user-adjustable G-force; never exceed speed limit |
| GPS jitter causes false retriggering | Medium | Medium | Map matching with hysteresis; mark narrations as "delivered" |
| Running alongside other nav apps causes audio conflicts | Medium | Medium | Use AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK; test with OsmAnd and Google Maps |
| Legal liability from speed/lean advisories | Low | High | Prominent disclaimers; frame as geometry info, not driving instruction |
| Overpass API rate limits for surface data | Low | Low | Pre-fetch at load time; cache aggressively; surface warnings are optional |

---

## 17. Future Roadmap

| Version | Features |
|---|---|
| **1.0** | GPX import, curve narration, motorcycle mode, lean angles (this PRD) |
| **1.1** | Elevation data via SRTM/DEM — crest, dip, gradient narration |
| **1.2** | Route sharing — export CurveCall annotated routes to share with friends |
| **1.3** | Integration with Kurviger / Calimoto route planning (deep link import) |
| **2.0** | MapLibre Navigation integration — full turn-by-turn + curve narration in one app |
| **2.1** | Visual curve overlay on map (color-coded arcs on route) |
| **2.2** | Android Auto support |
| **2.3** | Intersection complexity narration (roundabouts, multi-way junctions) |
| **3.0** | Community pacenote corrections — users can flag inaccurate curves |
| **3.1** | ML-enhanced curve classification trained on real driving data |
| **3.2** | iOS version via Kotlin Multiplatform |

---

## 18. Open Questions (Resolved)

| Question | Decision |
|---|---|
| Turn-by-turn in v1? | No — Phase 1 is audio-only curve narration. Phase 2 adds MapLibre Navigation. |
| Sparse OSM data handling? | Warn the user audibly + visually. Never go silent. |
| GPX import? | Yes — primary input method for Phase 1. |
| Motorcycle mode? | Yes — first-class feature with lower G, earlier timing, lean angles, surface warnings. |
| Product name? | CurveCall |
| Platform? | Android first (Kotlin + Jetpack Compose). iOS via KMP in v3.2. |
| Map view in Phase 1? | No — audio-first with simple list UI. Map view comes in Phase 2. |

---

*End of PRD — CurveCall v2.0*
