# CurveCall Code Review Report

**Reviewer:** Critique/Review Agent
**Date:** 2026-02-12
**Scope:** Full cross-module integration review of engine, narration, and app modules
**Status:** BLOCKING -- Issues must be resolved before proceeding

---

## Summary

The three modules were built independently and, while individually well-structured, have **multiple critical integration mismatches** that will prevent compilation. The most severe issues are in the Hilt DI wiring (AppModule.kt) where the app module assumes constructors and APIs that do not exist in the engine and narration modules. Additionally, the SessionViewModel references methods on MapMatcher and NarrationManager that do not match their actual APIs.

**Issue Counts:**
- CRITICAL: 11 (will prevent compilation or crash at runtime)
- HIGH: 8 (functionality mismatch with PRD or significant bugs)
- MEDIUM: 9 (integration mismatches that need fixing)
- LOW: 7 (style, organization, minor improvements)

---

## 1. CRITICAL -- Compilation Blockers and Runtime Crashes

### C-01: RouteAnalyzer is an `object`, not a `class` -- cannot be instantiated via `RouteAnalyzer()`

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/di/AppModule.kt` line 102-104
**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/engine/src/main/kotlin/com/curvecall/engine/RouteAnalyzer.kt` line 24

**What's wrong:** AppModule calls `RouteAnalyzer()` to construct an instance, but `RouteAnalyzer` is declared as `object RouteAnalyzer` (a Kotlin singleton object). You cannot call the constructor of an `object`.

**What the fix should be:** Either:
- Change `RouteAnalyzer` from `object` to `class` in the engine module, OR
- Remove the Hilt provider and use `RouteAnalyzer` directly as a static reference (e.g., inject nothing and call `RouteAnalyzer.analyzeRoute()` directly in ViewModels).

The same issue applies to HomeViewModel line 34, which receives `RouteAnalyzer` via `@Inject constructor` -- this is incompatible with an `object`.

---

### C-02: MapMatcher requires constructor arguments -- cannot be instantiated via `MapMatcher()`

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/di/AppModule.kt` line 108-110
**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/engine/src/main/kotlin/com/curvecall/engine/MapMatcher.kt` line 14-17

**What's wrong:** AppModule calls `MapMatcher()` (no-arg constructor), but `MapMatcher` requires two constructor arguments: `routePoints: List<LatLon>` and `segments: List<RouteSegment>`. There is no default constructor.

**What the fix should be:** MapMatcher cannot be a singleton provided at app startup since it depends on route data loaded at runtime. Remove the Hilt provider for MapMatcher and create it in the SessionViewModel after route data is available. For example:
```kotlin
val mapMatcher = MapMatcher(routePoints, routeSegments)
```

---

### C-03: TtsEngine is an `interface`, not a `class` -- `TtsEngine(context)` will not compile

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/di/AppModule.kt` line 134-138
**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/narration/src/main/kotlin/com/curvecall/narration/TtsEngine.kt` line 25

**What's wrong:** AppModule calls `TtsEngine(context)` but `TtsEngine` is an `interface`. Interfaces cannot be instantiated. The narration module provides `DefaultTtsEngine` as a JVM stub, but the actual Android TTS wrapper (`AndroidTtsEngine`) referenced in the TtsEngine.kt docs has never been created.

**What the fix should be:**
1. Create an `AndroidTtsEngine` class in the app module that implements `TtsEngine` and wraps `android.speech.tts.TextToSpeech`.
2. Update the Hilt provider to return the `AndroidTtsEngine` instance:
```kotlin
fun provideTtsEngine(@ApplicationContext context: Context): TtsEngine {
    return AndroidTtsEngine(context)
}
```

---

### C-04: NarrationManager constructor mismatch -- Hilt provides 4 args, constructor expects 3

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/di/AppModule.kt` line 142-149
**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/narration/src/main/kotlin/com/curvecall/narration/NarrationManager.kt` line 41-45

**What's wrong:** AppModule constructs `NarrationManager(templateEngine, timingCalculator, narrationQueue, ttsEngine)` (4 arguments), but the actual constructor is `NarrationManager(templateEngine, timingCalculator, config)` (3 arguments). NarrationManager does not accept a NarrationQueue or TtsEngine in its constructor -- it creates its own internal queue and does not directly use TTS.

**What the fix should be:** Fix the Hilt provider:
```kotlin
fun provideNarrationManager(
    templateEngine: TemplateEngine,
    timingCalculator: TimingCalculator
): NarrationManager {
    return NarrationManager(templateEngine, timingCalculator)
}
```
Remove `NarrationQueue` and `TtsEngine` from the parameter list. The NarrationQueue provider can also be removed since it is internal to NarrationManager.

---

### C-05: SessionViewModel calls `mapMatcher.matchToRoute(gpsLatLon, routePoints)` -- wrong signature

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/ui/session/SessionViewModel.kt` line 263
**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/engine/src/main/kotlin/com/curvecall/engine/MapMatcher.kt` line 79

**What's wrong:** SessionViewModel calls `mapMatcher.matchToRoute(gpsLatLon, routePoints)` passing two arguments. But `MapMatcher.matchToRoute()` only takes one argument: `gpsPosition: LatLon`. The `routePoints` are provided at construction time, not per-call.

**What the fix should be:** Change to `mapMatcher.matchToRoute(gpsLatLon)`. The MapMatcher instance must already have been constructed with the route points.

---

### C-06: SessionViewModel references `matchResult.distanceFromStart` and `matchResult.isLowConfidence` -- fields do not exist

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/ui/session/SessionViewModel.kt` lines 266, 288, 302
**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/engine/src/main/kotlin/com/curvecall/engine/MapMatcher.kt` lines 46-71

**What's wrong:** SessionViewModel accesses:
- `matchResult.distanceFromRoute` -- EXISTS as a field on `MatchResult`
- `matchResult.distanceFromStart` (line 288) -- DOES NOT EXIST. The field is called `routeProgress`.
- `matchResult.isLowConfidence` (line 302) -- DOES NOT EXIST. `MatchResult` has no confidence field.

**What the fix should be:**
- Replace `matchResult.distanceFromStart` with `matchResult.routeProgress`
- Remove or replace `matchResult.isLowConfidence`. Sparse data detection should be done by checking if the current route progress falls within any `SparseRegion` from the analysis result, not from MapMatcher.

---

### C-07: SessionViewModel calls `narrationManager.initialize()`, `narrationManager.update()`, `narrationManager.reset()`, `narrationManager.updateVerbosity()` -- none of these methods exist

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/ui/session/SessionViewModel.kt` lines 169, 243, 213, 294
**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/narration/src/main/kotlin/com/curvecall/narration/NarrationManager.kt`

**What's wrong:** The SessionViewModel calls:
- `narrationManager.initialize(routeSegments, routePoints)` -- does not exist. The method is `loadRoute(segments, points)`.
- `narrationManager.update(currentPosition, routeProgress, currentSpeedMs, isMuted)` -- does not exist. The method is `onLocationUpdate(latitude, longitude, speedMs)`.
- `narrationManager.reset()` -- does not exist. The method is `stop()`.
- `narrationManager.updateVerbosity(next)` -- does not exist. The method is `updateConfig(newConfig)`.

**What the fix should be:**
- Replace `narrationManager.initialize(...)` with `narrationManager.loadRoute(routeSegments, routePoints)`
- Replace `narrationManager.update(...)` with `narrationManager.onLocationUpdate(lat, lon, speedMs)`
- Replace `narrationManager.reset()` with `narrationManager.stop()`
- Replace `narrationManager.updateVerbosity(next)` with `narrationManager.updateConfig(currentConfig.copy(verbosity = next))`

---

### C-08: SessionViewModel expects `narrationManager.update()` to return a result object -- NarrationManager uses a listener pattern instead

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/ui/session/SessionViewModel.kt` line 294-298
**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/narration/src/main/kotlin/com/curvecall/narration/NarrationManager.kt` lines 50-62

**What's wrong:** SessionViewModel calls:
```kotlin
val narrationResult = narrationManager.update(...)
narrationResult.lastSpokenText
```
But `NarrationManager.onLocationUpdate()` returns `Unit`. It communicates results via the `NarrationListener` callback interface (onNarration, onInterrupt, onPaused, onResumed).

**What the fix should be:** Implement `NarrationManager.NarrationListener` in SessionViewModel and receive narration events via callbacks, updating the UI state accordingly. The `ttsEngine.speak()` calls should be triggered from the listener callbacks.

---

### C-09: SessionViewModel calls `ttsEngine.speak(text, priority)` -- TtsEngine `speak(String, Int)` method exists but TtsEngine is injected as an interface with no Android implementation

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/ui/session/SessionViewModel.kt` lines 275-276, 283-284, 306-309

**What's wrong:** SessionViewModel calls `ttsEngine.speak("Off route...", priority = 10)` and `ttsEngine.stop()`. While these methods exist on the `TtsEngine` interface, there is no concrete Android implementation. The `DefaultTtsEngine` is a no-op stub. Without creating `AndroidTtsEngine`, all TTS calls will silently do nothing.

This is related to C-03 but has additional runtime impact: even if compilation is fixed, no audio will play.

**What the fix should be:** Create `AndroidTtsEngine` in the app module as described in TtsEngine.kt's documentation comments (lines 111-197).

---

### C-10: `narration` module uses `Severity` comparison `curve.severity >= Severity.MODERATE` -- enum comparison semantics issue

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/narration/src/main/kotlin/com/curvecall/narration/TemplateEngine.kt` line 131
**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/engine/src/main/kotlin/com/curvecall/engine/types/Severity.kt`

**What's wrong:** `TemplateEngine.shouldNarrate()` uses `curve.severity >= Severity.MODERATE`. Kotlin enums support `>=` comparison via ordinal values. The Severity enum is ordered GENTLE(0), MODERATE(1), FIRM(2), SHARP(3), HAIRPIN(4). So `>= MODERATE` means MODERATE, FIRM, SHARP, HAIRPIN. This is actually **correct** behavior for standard verbosity. However, this relies on enum ordinal ordering which is fragile and not explicitly documented.

**Severity:** Downgraded from CRITICAL to MEDIUM -- it will compile and behave correctly, but is fragile.

**What the fix should be:** Add a comment or use an explicit when-clause to make the intent clearer, or add a `Comparable`-safe helper method.

---

### C-11: `kapt` plugin applied with `version` in app/build.gradle.kts -- may cause build error

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/build.gradle.kts` line 5

**What's wrong:** The line `id("org.jetbrains.kotlin.kapt") version "1.9.22"` applies kapt with an explicit version. However, kapt's version must match the Kotlin plugin version. Since the Kotlin Android plugin is already declared in the root build.gradle.kts with version 1.9.22, specifying the version again in the app module may cause a conflict or redundancy depending on Gradle version. In some configurations this causes "Plugin request for plugin already on the classpath" errors.

**What the fix should be:** Change to `id("org.jetbrains.kotlin.kapt")` without the version, since the version is already controlled by the root build file, OR add `id("org.jetbrains.kotlin.kapt") version "1.9.22" apply false` to the root and just `id("org.jetbrains.kotlin.kapt")` in the app.

---

## 2. HIGH -- PRD Compliance Issues and Significant Bugs

### H-01: Speed advisory rounding uses `Math.round` (rounds nearest) instead of `floor` (rounds down) as specified in PRD

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/narration/src/main/kotlin/com/curvecall/narration/TemplateEngine.kt` line 474-476
**PRD Reference:** Section 7.6: "Round down to nearest 5"

**What's wrong:** `roundToNearest5()` uses `Math.round(value / 5.0) * 5` which rounds to the NEAREST 5. The PRD explicitly says "Round down to nearest 5." This means using `floor`, not `round`. For a speed of 47.9 km/h, `round` gives 50 while `floor` gives 45. This is a safety issue -- speed advisories should be conservative (round down).

**What the fix should be:**
```kotlin
private fun roundToNearest5(value: Double): Int {
    return (Math.floor(value / 5.0) * 5).toInt()
}
```

Note: The engine's `SpeedAdvisor` correctly uses `floor` for `toKmhRounded()` and `toMphRounded()`. But the TemplateEngine's `formatSpeedAdvisory()` does its own conversion and uses `roundToNearest5()` which rounds to nearest, not floor. This means the displayed/spoken speed could be 5 km/h HIGHER than the engine computed.

---

### H-02: PRD Section 6.5 -- Sparse data warning audio text does not match

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/narration/src/main/kotlin/com/curvecall/narration/TemplateEngine.kt` line 502-503
**PRD Reference:** Section 6.5: "Low data quality ahead -- curve information may be incomplete"

**What's wrong:** The TemplateEngine uses an em-dash (Unicode \u2014) in the warning text: `"Low data quality ahead \u2014 curve information may be incomplete"`. The PRD says: "Low data quality ahead -- curve information may be incomplete" (double-dash). For TTS output, the em-dash may cause unexpected pauses or pronunciation. This is minor but for consistency with the PRD spec, it should be a double-dash or the TTS behavior with em-dash should be tested.

**What the fix should be:** Use `"Low data quality ahead, curve information may be incomplete"` or `"Low data quality ahead -- curve information may be incomplete"` for better TTS clarity.

---

### H-03: Narration templates do not exactly match PRD Section 6.1 examples

**PRD Reference:** Section 6.1
**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/narration/src/main/kotlin/com/curvecall/narration/TemplateEngine.kt`

**What's wrong:** Several narration patterns differ from the PRD examples:

| PRD Example | Actual Template Output | Issue |
|---|---|---|
| "Left curve ahead, moderate" | "Moderate left ahead" | Severity before direction; missing "curve" |
| "Sharp right ahead, tightening, slow to 40" | "Sharp right ahead, tightening, slow to 40" | OK |
| "Left into right, S-bend, moderate" | "left into right, S-bend, Moderate" | Capitalization differs |
| "Hairpin left ahead, slow to 20" | "Hairpin left ahead, slow to 20" | OK |
| "Long gentle left, holds for 400 meters" | "Long Gentle left, holds for 400 meters" | Capitalization of "Gentle" |

The key issue: the standard curve template uses `"$severityStr $dir ahead"` (e.g., "Moderate left ahead") but the PRD shows `"Left curve ahead, moderate"` (direction first, then "curve ahead", then severity). The PRD format is `"[Direction] curve ahead, [severity]"` while the code uses `"[Severity] [direction] ahead"`.

**What the fix should be:** Restructure `buildStandardCurveNarration()` to output `"[direction] curve ahead, [severity]"` to match PRD examples. Also fix capitalization: severity should be lowercase when used as a descriptor in mid-sentence.

---

### H-04: `Severity.MODERATE` curves never get speed advisories

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/engine/src/main/kotlin/com/curvecall/engine/pipeline/SpeedAdvisor.kt` line 66
**PRD Reference:** Section 7.5: "moderate: Context-dependent"

**What's wrong:** `needsAdvisory(Severity.MODERATE)` returns `false` unconditionally. The PRD says moderate is "context-dependent" for speed advisories. For moderate curves (100-200m radius), speeds of 56-79 km/h might still warrant an advisory depending on the road speed limit or the approach speed. Always returning false means some moderate curves at 100m radius (advisory speed ~66 km/h) on a 100 km/h road will have no warning.

**What the fix should be:** Make moderate advisory context-dependent. A reasonable heuristic: issue an advisory for moderate curves when the advisory speed would be below a threshold (e.g., below 70 km/h), or always include it and let the narration template decide based on verbosity.

---

### H-05: Test fixture JSON files referenced in PRD Section 11/12 are missing

**PRD Reference:** Section 11 (Project Structure), Section 12.2
**Expected Location:** `/Users/mustafahathiyari/gitworkspace/curve_call/engine/src/test/kotlin/com/curvecall/engine/fixtures/`

**What's wrong:** The PRD specifies 7 JSON test fixture files:
- `stelvio_pass.json`
- `tail_of_dragon.json`
- `col_de_turini.json`
- `simple_hairpin.json`
- `s_bend.json`
- `long_straight.json`
- `sparse_data.json`

The entire `fixtures/` directory does not exist. The test files exist but without real-world test data, the accuracy targets from PRD Section 15 (>90% curve detection, <5% false positive) cannot be validated.

**What the fix should be:** Create the fixtures directory and populate with real OSM coordinate sequences for the named roads.

---

### H-06: `SessionDataHolder` is not thread-safe

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/data/session/SessionDataHolder.kt`

**What's wrong:** `SessionDataHolder` stores route data in plain `var` properties without synchronization. The `HomeViewModel` writes to it on `Dispatchers.Default` (line 83 of HomeViewModel.kt), and `SessionViewModel` reads from it in `init`. If the read happens on the main thread before the write completes, it could read stale/null data. There is also no memory fence guaranteeing visibility across threads.

**What the fix should be:** Either:
- Use `@Volatile` annotations on the vars, or
- Use `AtomicReference` wrappers, or
- Use `StateFlow<RouteData?>` for reactive, thread-safe data sharing.

---

### H-07: HomeViewModel injects `RouteAnalyzer` as a constructor parameter but uses it as an instance

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/ui/home/HomeViewModel.kt` line 34, 119

**What's wrong:** HomeViewModel has `private val routeAnalyzer: RouteAnalyzer` as an injected constructor parameter and calls `routeAnalyzer.analyzeRoute(...)`. Since `RouteAnalyzer` is an `object`, it should be called as `RouteAnalyzer.analyzeRoute(...)` statically. The DI provider also cannot construct it (see C-01).

**What the fix should be:** Remove `RouteAnalyzer` from the @Inject constructor and call `RouteAnalyzer.analyzeRoute()` directly, or convert RouteAnalyzer to a class.

---

### H-08: `Divider` composable is deprecated in Material3

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/ui/settings/SettingsScreen.kt` line 18
**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/ui/about/AboutScreen.kt` line 17

**What's wrong:** `Divider` was deprecated in recent Material3 versions in favor of `HorizontalDivider`. With the Compose BOM 2024.01.00, this should still compile but will generate deprecation warnings. In newer BOMs it may be removed.

**What the fix should be:** Replace `Divider` with `HorizontalDivider` and update the import.

---

## 3. MEDIUM -- Integration Mismatches

### M-01: `narration` module is pure JVM but NarrationManager has Android-like design without Android TTS integration

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/narration/build.gradle.kts` -- uses `org.jetbrains.kotlin.jvm` plugin
**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/narration/src/main/kotlin/com/curvecall/narration/NarrationManager.kt`

**What's wrong:** The narration module is pure JVM (no Android dependencies), which is correct per the PRD's architecture. However, `NarrationManager` has its own internal map-matching (lines 353-393), duplicating `MapMatcher` from the engine. The app module's `SessionViewModel` does its own map-matching AND has its own TTS integration, creating confusion about which component owns map-matching and narration triggering.

**What the fix should be:** Clarify the responsibility boundary. Either:
1. SessionViewModel owns map-matching and calls NarrationManager with position updates (current approach), but then NarrationManager's internal map-matching should be removed, or
2. NarrationManager fully owns the pipeline from GPS to narration event, and SessionViewModel only passes raw GPS.

The current dual map-matching will cause inconsistencies in route progress tracking.

---

### M-02: `UserPreferences.DrivingMode` and `narration.types.DrivingMode` are separate enums

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/data/preferences/UserPreferences.kt` line 57-58
**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/narration/src/main/kotlin/com/curvecall/narration/types/DrivingMode.kt`

**What's wrong:** Two separate `DrivingMode` enums exist: `UserPreferences.DrivingMode` (CAR, MOTORCYCLE) and `com.curvecall.narration.types.DrivingMode` (CAR, MOTORCYCLE). They have identical values but are separate types. The SessionViewModel will need to convert between them when constructing NarrationConfig, which adds boilerplate and risk of bugs.

**What the fix should be:** Use the narration module's `DrivingMode` everywhere, including in UserPreferences. Or create a shared `DrivingMode` in the engine module (since it's the base dependency).

---

### M-03: `UserPreferences.SpeedUnits` and `narration.types.SpeedUnit` are separate enums

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/data/preferences/UserPreferences.kt` line 88
**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/narration/src/main/kotlin/com/curvecall/narration/types/SpeedUnit.kt`

**What's wrong:** Same issue as M-02 but for speed units. `UserPreferences.SpeedUnits` (MPH, KMH) vs `SpeedUnit` (MPH, KMH). The narration types use `SpeedUnit` while the app uses `UserPreferences.SpeedUnits`.

**What the fix should be:** Use a single shared enum. The narration module's `SpeedUnit` is the right location since it's used by the narration config.

---

### M-04: Severity comparison via `>=` operator (C-10 downgraded)

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/narration/src/main/kotlin/com/curvecall/narration/TemplateEngine.kt` line 131

**What's wrong:** As discussed in C-10, using `>=` on enum ordinals works but is fragile. If someone reorders the Severity enum, the comparison will silently break.

**What the fix should be:** Use an explicit `when` clause or add a `severityLevel()` helper.

---

### M-05: `narration` module's NarrationQueue import of `java.util.concurrent.locks.ReentrantLock` breaks KMP portability

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/narration/src/main/kotlin/com/curvecall/narration/NarrationQueue.kt` line 4
**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/narration/src/main/kotlin/com/curvecall/narration/NarrationManager.kt` line 10

**What's wrong:** The narration module uses `java.util.concurrent.locks.ReentrantLock` and `kotlin.concurrent.withLock`. While this is fine for JVM/Android, the PRD mentions KMP (Kotlin Multiplatform) in version 3.2. Using JVM-specific concurrency primitives will block KMP migration.

**What the fix should be:** This is not blocking for v1, but note for future: consider using `kotlinx.coroutines.sync.Mutex` instead, which is multiplatform-compatible.

---

### M-06: `Hilt` providers for `NarrationQueue` and `TtsEngine` import `TtsEngine` as a class

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/di/AppModule.kt` line 15

**What's wrong:** Line 15 imports `com.curvecall.narration.TtsEngine`. This import resolves to the `TtsEngine` interface. The provider on line 134-138 calls `TtsEngine(context)` as if it's a class constructor. This will not compile since interfaces cannot be instantiated (restatement of C-03 for clarity on the import side).

**What the fix should be:** Import the concrete implementation or create one.

---

### M-07: `SessionViewModel` injects `MapMatcher` via DI but MapMatcher needs route-specific data

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/ui/session/SessionViewModel.kt` line 44

**What's wrong:** `SessionViewModel` receives `MapMatcher` via `@Inject constructor`. But `MapMatcher` must be constructed with route-specific data (points and segments). A singleton `MapMatcher` makes no sense -- it needs to be created per-route. Additionally, the existing `MapMatcher` constructor requires 2 arguments (see C-02).

**What the fix should be:** Remove `MapMatcher` from Hilt DI. Create it locally in `SessionViewModel.initializeRoute()`:
```kotlin
private var mapMatcher: MapMatcher? = null
// In initializeRoute():
mapMatcher = MapMatcher(points, segments)
```

---

### M-08: `SessionViewModel` imports `SessionForegroundService` but calls `start(context)` / `stop(context)` -- correct API

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/ui/session/SessionViewModel.kt` lines 166, 216

**What's wrong:** The calls `SessionForegroundService.start(appContext)` and `SessionForegroundService.stop(appContext)` are correct -- these static methods exist on the companion object (lines 41, 55 of SessionForegroundService.kt). However, starting a foreground service from a ViewModel (which survives configuration changes) without proper lifecycle awareness could lead to the service running indefinitely if the ViewModel is not properly cleared.

**What the fix should be:** Consider tying the service lifecycle more tightly to the Activity or the navigation graph. Add `stopSession()` call in `onCleared()` (currently it only cancels the job and stops TTS but does not stop the foreground service).

---

### M-09: `proguard-rules.pro` is referenced but does not exist

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/build.gradle.kts` line 30

**What's wrong:** The release build type references `"proguard-rules.pro"` but this file was never created. When building a release APK, the build will fail with a file-not-found error.

**What the fix should be:** Create an empty `proguard-rules.pro` file in the `app/` directory, or add ProGuard rules for Hilt, OkHttp, and other libraries that need them.

---

## 4. LOW -- Style, Organization, and Minor Improvements

### L-01: `Classifier.computeTotalAngleChange()` returns absolute value -- loses direction information

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/engine/src/main/kotlin/com/curvecall/engine/pipeline/Classifier.kt` line 219

**What's wrong:** The method returns `abs(GeoMath.bearingDifference(entryBearing, exitBearing))`. This loses the sign (left vs right). The `totalAngleChange` field in `CurveSegment` stores this as "degrees" without indicating whether a signed or unsigned value is expected. The `is90Degree` check on line 54 uses `abs(totalAngleChange)`, which is redundant since the value is already absolute.

**What the fix should be:** Document that `totalAngleChange` is always positive (absolute value), and remove the redundant `abs()` on line 54.

---

### L-02: `CompoundDetector.detectSeries()` and `detectSBendsAndChicanes()` accept `List<Any>` parameter

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/engine/src/main/kotlin/com/curvecall/engine/pipeline/CompoundDetector.kt` lines 84, 139

**What's wrong:** Both methods accept a `pairs: List<Any>` parameter but never actually use it. The `CurvePair` data class is computed in `detect()` and passed as `pairs`, but the methods re-compute gap distances internally. The `List<Any>` type is a type-safety loss.

**What the fix should be:** Either:
- Remove the `pairs` parameter from these private methods since they don't use it, or
- Actually use the pre-computed pairs data to avoid recomputation.

---

### L-03: `OverpassClient.surfaceCache` uses `hashCode()` of the entire List<LatLon> as cache key

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/data/osm/OverpassClient.kt` line 63

**What's wrong:** `routePoints.hashCode()` for a large list is expensive to compute and hash collisions are possible. A different route with the same hash would return wrong cached data.

**What the fix should be:** Use a more robust cache key, such as a hash of the first point, last point, and point count, or use the route URI as a key.

---

### L-04: `LocationProvider.getLastLocation()` uses deprecated coroutine task integration

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/data/location/LocationProvider.kt` line 89

**What's wrong:** `kotlinx.coroutines.tasks.await(task)` is the correct API but the import style `kotlinx.coroutines.tasks.await` requires the `kotlinx-coroutines-play-services` dependency which IS included in build.gradle.kts. However, the fully-qualified call style is unusual -- normally you'd use `task.await()` as an extension function.

**What the fix should be:** Use `fusedLocationClient.lastLocation.await()` after importing `kotlinx.coroutines.tasks.await`.

---

### L-05: `UserPreferences.addRecentRoute()` uses `Set<String>` which loses ordering

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/data/preferences/UserPreferences.kt` line 234-246

**What's wrong:** `stringSetPreferencesKey` stores routes as a `Set<String>`, which has no guaranteed ordering. The code calls `toList().takeLast(10)` to keep the 10 most recent, but since sets are unordered, "most recent" has no meaning. Older routes could be kept while newer ones are dropped.

**What the fix should be:** Use `stringPreferencesKey` with a JSON-encoded list, or use a separate Room database table with timestamps for proper ordering.

---

### L-06: `Severity` enum ordering is implicitly relied upon but not documented as critical

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/engine/src/main/kotlin/com/curvecall/engine/types/Severity.kt`

**What's wrong:** Multiple files rely on the ordinal ordering of the Severity enum (GENTLE < MODERATE < FIRM < SHARP < HAIRPIN) for comparisons. If the enum order is ever changed, multiple systems break silently.

**What the fix should be:** Add a prominent doc comment: "WARNING: Enum order is semantic -- GENTLE must be first, HAIRPIN last. Multiple comparison operators depend on ordinal ordering."

---

### L-07: No `@RequiresPermission` annotations on location-sensitive code

**File:** `/Users/mustafahathiyari/gitworkspace/curve_call/app/src/main/java/com/curvecall/data/location/LocationProvider.kt`

**What's wrong:** `LocationProvider` uses `@SuppressLint("MissingPermission")` to suppress lint warnings. While the SessionScreen handles permission requests, there's no compile-time guarantee that permissions are checked before calling `locationUpdates()`.

**What the fix should be:** Consider using `@RequiresPermission` annotations from `androidx.annotation` instead of `@SuppressLint` to get better compile-time checks.

---

## 5. Architecture Observations

### A-01: Engine module is correctly Android-free

The `engine` module uses only `org.jetbrains.kotlin.jvm` plugin and has zero Android dependencies. All types are pure Kotlin data classes. The Haversine, Menger curvature, and pipeline stages are well-structured. This module is ready for CLI usage or KMP porting. **PASS.**

### A-02: No circular dependencies

Dependency graph: `app` -> `narration` -> `engine`. No reverse dependencies. **PASS.**

### A-03: Narration module is correctly Android-free

The `narration` module uses `org.jetbrains.kotlin.jvm` and has no Android dependencies. `TtsEngine` is correctly defined as an interface with Android implementation deferred to the app module. **PASS.**

### A-04: Hilt DI wiring needs significant repair

As documented in C-01 through C-04, the DI module assumes constructors and types that don't match the actual implementations. This is the biggest integration failure. **FAIL.**

---

## 6. PRD Compliance Checklist

| PRD Section | Requirement | Status | Issue |
|---|---|---|---|
| 6.1 | Narration text matches examples | PARTIAL | H-03: Template format differs from PRD |
| 6.3 | Timing model (look-ahead + braking) | PASS | TimingCalculator correctly implements the formula |
| 6.4 | Verbosity levels (1/2/3) | PASS | TemplateEngine correctly filters by verbosity |
| 6.5 | Sparse data warning | PASS | Text differs slightly (H-02) but functionality is present |
| 7.3 | Menger curvature formula | PASS | Exact match with PRD pseudocode |
| 7.4 | Segmentation algorithm | PASS | Correct thresholds (500m, 50m gap) |
| 7.5 | Severity thresholds | PASS | gentle>200, moderate>100, firm>50, sharp>25, hairpin<25 |
| 7.6 | Speed advisory formula | PASS | Engine formula correct; narration rounding wrong (H-01) |
| 7.7 | Lean angle formula | PASS | atan(v^2/(r*g)) correctly implemented |
| 7.8 | Compound detection | PASS | S-bend, chicane, series, tightening sequence all present |
| 8.1 | All settings present | PASS | All PRD-listed settings are in UserPreferences and SettingsScreen |
| 8.2 | Color system | PASS | All hex values match PRD exactly |
| 8.3 | Audio behavior (ducking, queue, interrupt) | PARTIAL | Queue has interrupt logic; no actual Android audio focus yet (C-03/C-09) |
| 10.1 | Data pipeline | PASS (code) | All pipeline stages present; integration broken (C-01 through C-09) |
| 14.1 | Safety disclaimer | PASS | Text matches PRD exactly |

---

## 7. Recommended Fix Priority Order

1. **C-01 + H-07:** Convert `RouteAnalyzer` from `object` to `class`, or remove from DI and use directly
2. **C-02 + M-07:** Remove `MapMatcher` from DI; create per-route in SessionViewModel
3. **C-03 + C-09:** Create `AndroidTtsEngine` in app module implementing `TtsEngine` interface
4. **C-04:** Fix `NarrationManager` provider to match actual constructor signature
5. **C-05, C-06, C-07, C-08:** Fix all `SessionViewModel` API calls to match actual engine/narration APIs
6. **C-11:** Fix kapt plugin declaration
7. **H-01:** Fix speed advisory rounding to use floor instead of round
8. **H-03:** Fix narration template format to match PRD examples
9. **M-01:** Decide on single map-matching authority (engine's MapMatcher vs NarrationManager internal)
10. **M-02, M-03:** Unify duplicate enums (DrivingMode, SpeedUnit)
11. **M-09:** Create proguard-rules.pro
12. **H-05:** Create test fixture JSON files

---

*End of Review Report*
