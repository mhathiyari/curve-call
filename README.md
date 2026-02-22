# CurveCue

**Your digital co-driver.** CurveCue is an Android app that narrates upcoming curves in real time as you drive or ride a route. Load a GPX file, hit start, and the app speaks turn-by-turn curve warnings through your speakers — so your eyes stay on the road.

Built for spirited drives on mountain roads, track days with unfamiliar layouts, and motorcycle touring through twisties.

<p align="center">
  <img src="docs/screenshot-home.png" alt="Home Screen" width="270" />
</p>

## How It Works

1. **Load a GPX route** — Export from any mapping app (Calimoto, Kurviger, Google Maps, etc.)
2. **Select your mode** — Car or Motorcycle (motorcycle adds lean angle and surface warnings)
3. **Drive** — CurveCue matches your GPS position to the route in real time and speaks upcoming curves before you reach them

Example narrations:
- *"Left curve ahead, moderate"*
- *"Sharp right ahead, slow to 35"*
- *"Hairpin left ahead, slow to 20, tightening"*
- *"S-bend, right into left, sharp"*

## Features

- **Adaptive timing** — Narration trigger distance adjusts dynamically based on your current speed and braking physics. Faster = earlier warning.
- **Severity classification** — Curves classified into 5 levels by radius: Gentle (>200m), Moderate (100–200m), Firm (50–100m), Sharp (25–50m), Hairpin (<25m)
- **Compound detection** — Identifies S-bends, chicanes, and series of linked curves as a single narration
- **Speed advisories** — Physics-based safe speed recommendations derived from road geometry and lateral G limits
- **Motorcycle mode** — Lean angle suggestions, surface condition warnings (gravel, dirt, cobblestone via Overpass API), sportier timing profiles
- **Road metadata** — Surface type, road name, and condition data integrated into narrations
- **Live map** — Heading-up OpenStreetMap view with severity-colored route overlay and dynamic zoom
- **Offline capable** — Pre-caches map tiles along your route corridor for areas with no signal
- **Audio ducking** — Automatically lowers music volume during narrations, then restores it
- **Configurable verbosity** — Minimal (sharp + hairpin only), Standard (moderate+), or Detailed (everything including straights)
- **Dark theme** — Always-dark UI optimized for minimal glare while driving

## Architecture

CurveCue is a multi-module Kotlin project with a clean separation between pure logic and Android platform code.

```
curve_call/
├── engine/          Pure Kotlin — route analysis pipeline
├── narration/       Pure Kotlin — TTS text generation & timing
└── app/             Android — UI, GPS, audio, DI
```

### Engine Module

Transforms raw GPS coordinates into classified curve segments through an 8-stage pipeline:

```
GPX Points → Interpolation (10m spacing) → Curvature (Menger) → Segmentation
→ Classification (severity, direction, modifiers) → Speed Advisory (lateral G)
→ Lean Angle → Compound Detection (S-bends) → Data Quality (confidence)
```

Key types: `CurveSegment`, `StraightSegment`, `Severity`, `CompoundType`, `MapMatcher`

### Narration Module

Generates natural language from curve data and decides *when* to speak based on a kinematic model:

```
Trigger Distance = TTS Duration + Reaction Time + Braking Distance (v²/2a)
```

Three timing profiles — Relaxed (2.0s reaction), Normal (1.5s), Sporty (1.2s) — with immediate voice chaining for consecutive curve announcements.

### App Module

- **Jetpack Compose** UI with Material 3
- **Hilt** dependency injection
- **MVVM** with StateFlow
- **osmdroid** for OpenStreetMap rendering
- **FusedLocationProvider** for GPS at 1Hz
- **Foreground Service** keeps GPS + TTS alive when backgrounded

## Building

**Requirements:** Android Studio Hedgehog+, JDK 21, Android SDK 34

```bash
# Clone
git clone https://github.com/mhathiyari/curve_call.git
cd curve_call

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run engine + narration tests
./gradlew :engine:test :narration:test
```

**Min SDK:** 26 (Android 8.0)
**Target SDK:** 34 (Android 14)

## Permissions

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` | GPS tracking during active session |
| `FOREGROUND_SERVICE_LOCATION` | Keep GPS alive when app is backgrounded |
| `INTERNET` | OSM map tiles, Overpass API (motorcycle surface data) |
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) |
| `WAKE_LOCK` | Prevent screen sleep during session |
| `READ_EXTERNAL_STORAGE` | GPX file access (pre-Android 13) |

## Project Structure

```
app/src/main/java/com/curvecall/
├── audio/              AndroidTtsEngine — platform TTS + audio focus
├── data/
│   ├── preferences/    UserPreferences — DataStore persistence
│   ├── session/        SessionDataHolder — route data handoff
│   └── tiles/          TileDownloader — offline tile pre-caching
├── di/                 Hilt AppModule
├── service/            SessionForegroundService
├── ui/
│   ├── home/           HomeScreen, HomeViewModel, CurveCallLogo
│   ├── map/            OsmMapView, RouteOverlay, GpsMarkerOverlay
│   ├── navigation/     CurveCallNavHost, NavRoutes
│   ├── session/        SessionScreen, SessionViewModel, components/
│   ├── settings/       SettingsScreen, SettingsViewModel
│   └── theme/          Color, Theme (dark-only)
├── CurveCallApplication.kt
└── MainActivity.kt

engine/src/main/kotlin/com/curvecall/engine/
├── geo/                GeoMath, MengerCurvature
├── pipeline/           Interpolator, CurvatureComputer, Segmenter,
│                       Classifier, SpeedAdvisor, LeanAngleCalculator,
│                       CompoundDetector, DataQualityChecker
├── types/              CurveSegment, Severity, AnalysisConfig, ...
├── MapMatcher.kt
└── RouteAnalyzer.kt

narration/src/main/kotlin/com/curvecall/narration/
├── types/              NarrationConfig, NarrationEvent, TimingProfile
├── NarrationManager.kt
├── NarrationQueue.kt
├── TemplateEngine.kt
└── TimingCalculator.kt
```

## Tests

The engine and narration modules have pure Kotlin unit tests:

```bash
# Run all tests
./gradlew test

# Engine tests (13 test files — pipeline, geometry, MapMatcher)
./gradlew :engine:test

# Narration tests (TemplateEngine, TimingCalculator, NarrationQueue)
./gradlew :narration:test
```

## Tech Stack

| Layer | Technology |
|---|---|
| UI | Jetpack Compose, Material 3, Navigation Compose |
| DI | Hilt |
| State | ViewModel + StateFlow |
| Maps | osmdroid (OpenStreetMap) |
| GPS | Google Play Services FusedLocationProvider |
| Audio | Android TextToSpeech, AudioManager |
| Network | OkHttp (Overpass API) |
| Storage | DataStore Preferences |
| Build | Gradle Kotlin DSL, AGP 8.2.2, Kotlin 1.9.22 |

## Safety

CurveCue is a **driving aid**, not a safety system. Speed advisories and lean angle suggestions are calculated estimates based on road geometry only. They do not account for surface condition, weather, traffic, visibility, or vehicle capability. The driver is solely responsible for all driving decisions. Never interact with the UI while driving — use audio narration only.

## License

All rights reserved.
