# Adaptive Look-Ahead Timing Spec

## Problem

The current timing model pre-computes fixed trigger distances at route load using
`speed * lookAheadSeconds` (8s/10s). This is static — it doesn't adapt to the
driver's actual speed when approaching a curve, and doesn't account for TTS
utterance duration or varying reaction needs. A driver going 120 km/h gets the
same lead time as one going 60 km/h for the same curve.

## Design: Hybrid Dynamic Timing

**Keep** the pre-computed event queue (text, priority, associated curve).
**Remove** the pre-computed `triggerDistanceFromStart` from events.
**Add** a real-time trigger evaluation on every GPS tick that uses current speed
to decide if an event should fire NOW.

### Core Timing Model

Working backwards from the **action point** (where the driver must have finished
reacting), the system computes the total required lead distance:

```
                      |<-- TTS -->|<-- React -->|<-- Brake -->|
  [trigger fires]     [speaking]   [processing]  [braking]    [curve entry]
       |                                                          |
       |<-------------- total lead distance --------------------->|
```

#### When braking IS needed (currentSpeed > advisorySpeed):

```kotlin
brakingDist   = (v² - v_target²) / (2 * decelRate)
reactionDist  = v * reactionTimeSec
ttsDist       = v * ttsDurationSec
leadDistance   = brakingDist + reactionDist + ttsDist
leadDistance   = max(leadDistance, MIN_ANNOUNCEMENT_DISTANCE)  // floor at 100m
```

#### When braking is NOT needed (awareness prompt):

```kotlin
reactionDist  = v * reactionTimeSec
ttsDist       = v * ttsDurationSec
leadDistance   = reactionDist + ttsDist
leadDistance   = max(leadDistance, MIN_ANNOUNCEMENT_DISTANCE)
```

#### Trigger condition (evaluated every GPS tick):

```kotlin
distanceToCurve = curve.distanceFromStart - currentProgress
if (distanceToCurve <= leadDistance) → FIRE
```

### TTS Duration Estimation

Estimate utterance duration from the narration text before firing:

```kotlin
fun estimateTtsDuration(text: String): Double {
    val wordCount = text.split("\\s+".toRegex()).size
    val wordsPerSecond = 2.5  // TTS speaking rate (~150 wpm)
    return wordCount / wordsPerSecond + 0.3  // 300ms for TTS engine startup
}
```

Typical durations:
- `"Sharp right ahead"` → 3 words → ~1.5s
- `"Sharp right ahead, tightening, slow to 35"` → 7 words → ~3.1s
- `"Series of 3 curves ahead"` → 5 words → ~2.3s

### Urgent Late-Warning Alert

When the driver is already too close to brake comfortably:

```kotlin
distanceToCurve = curve.distanceFromStart - currentProgress
needsBraking    = advisorySpeed != null && currentSpeed > advisorySpeed

if (needsBraking) {
    brakingDist = (v² - v_target²) / (2 * decelRate)
    safetyRatio = distanceToCurve / brakingDist

    if (safetyRatio < profile.urgencyThreshold) {
        // URGENT: not enough room for comfortable braking
        → fire immediately, bypass queue/cooldown
        → prepend "BRAKE —" to narration text
        → use PRIORITY_URGENT (8, above WARNING)
    }
}
```

## Driver Profiles

Three presets controlling timing aggressiveness:

| Parameter              | Relaxed   | Normal    | Sporty    |
|------------------------|-----------|-----------|-----------|
| `reactionTimeSec`      | 2.5       | 1.5       | 1.0       |
| `minGapSec`            | 5.0       | 3.0       | 2.0       |
| `urgencyThreshold`     | 0.8       | 0.6       | 0.4       |

- **reactionTimeSec**: Time between hearing the end of the prompt and starting to
  act. Relaxed = assumes distraction / unfamiliarity. Sporty = alert, experienced.
- **minGapSec**: Minimum silence between the end of one prompt and the start of
  the next. Prevents cognitive overload from back-to-back prompts.
- **urgencyThreshold**: Ratio of `distanceToCurve / brakingDistance` below which
  the urgent alert fires. Relaxed triggers early (80%), sporty triggers late (40%).

### Profile Data Class

```kotlin
enum class TimingProfile {
    RELAXED, NORMAL, SPORTY
}

data class TimingProfileConfig(
    val reactionTimeSec: Double,
    val minGapSec: Double,
    val urgencyThreshold: Double
) {
    companion object {
        fun forProfile(profile: TimingProfile) = when (profile) {
            TimingProfile.RELAXED -> TimingProfileConfig(2.5, 5.0, 0.8)
            TimingProfile.NORMAL  -> TimingProfileConfig(1.5, 3.0, 0.6)
            TimingProfile.SPORTY  -> TimingProfileConfig(1.0, 2.0, 0.4)
        }
    }
}
```

## Minimum Gap (Cooldown) Enforcement

Track when the last narration finished. Delay non-urgent events if the gap is
too short:

```kotlin
val timeSinceLastNarration = now - lastNarrationEndTimeMs
val minGapMs = profile.minGapSec * 1000

if (timeSinceLastNarration < minGapMs && !isUrgent) {
    // Skip this tick, re-evaluate next tick
    return
}
```

Urgent alerts bypass cooldown entirely.

## Compound Curve Handling

For compound curves (S-bends, chicanes, series), the system already groups them
into a single `CurveSegment` with `compoundType` and `compoundSize`. The timing
change:

- Use the **first sub-curve's** `distanceFromStart` as the action point
- Use the **most severe** sub-curve's advisory speed for braking calculations
- Fire a single grouped announcement timed to the first curve's braking point

No per-sub-curve updates — keep it simple.

## Changes to Existing Code

### 1. `NarrationEvent` — remove pre-computed trigger distance

```kotlin
data class NarrationEvent(
    val text: String,
    val priority: Int,
    val curveDistanceFromStart: Double,  // renamed: raw curve position
    val advisorySpeedMs: Double?,        // NEW: needed for real-time braking calc
    val associatedCurve: CurveSegment?,
    val delivered: Boolean = false
)
```

The `triggerDistanceFromStart` field is **removed**. The trigger decision is now
made at runtime in the queue/manager, not baked into the event.

### 2. `TimingCalculator` — new real-time evaluation method

Keep the existing `brakingDistance()` and `decelRateForMode()` methods. Add:

```kotlin
/**
 * Evaluate whether an event should fire right now given current dynamics.
 *
 * @return FIRE, URGENT, or WAIT
 */
fun evaluate(
    distanceToCurveEntry: Double,
    currentSpeedMs: Double,
    advisorySpeedMs: Double?,
    ttsDurationSec: Double,
    profile: TimingProfileConfig,
    mode: DrivingMode
): TriggerDecision {
    val decel = decelRateForMode(mode)
    val needsBraking = advisorySpeedMs != null && currentSpeedMs > advisorySpeedMs

    // Braking distance (0 if no braking needed)
    val brakeDist = if (needsBraking)
        brakingDistance(currentSpeedMs, advisorySpeedMs!!, decel) else 0.0

    // Check urgent condition first
    if (needsBraking && distanceToCurveEntry > 0) {
        val safetyRatio = distanceToCurveEntry / brakeDist
        if (safetyRatio < profile.urgencyThreshold) {
            return TriggerDecision.URGENT
        }
    }

    // Total lead distance
    val reactionDist = currentSpeedMs * profile.reactionTimeSec
    val ttsDist = currentSpeedMs * ttsDurationSec
    val leadDistance = max(brakeDist + reactionDist + ttsDist, MIN_ANNOUNCEMENT_DISTANCE)

    return if (distanceToCurveEntry <= leadDistance) {
        TriggerDecision.FIRE
    } else {
        TriggerDecision.WAIT
    }
}

enum class TriggerDecision { FIRE, URGENT, WAIT }
```

### 3. `NarrationManager.onLocationUpdate()` — dynamic trigger loop

Replace the current static threshold check with a per-event dynamic evaluation:

```kotlin
fun onLocationUpdate(routeProgressMeters: Double, speedMs: Double) {
    // For each pending event:
    //   1. Compute distanceToCurve = event.curveDistanceFromStart - routeProgress
    //   2. Estimate TTS duration from event.text
    //   3. Call timingCalculator.evaluate(...)
    //   4. If URGENT → fire immediately, bypass cooldown
    //   5. If FIRE → check cooldown, then fire or defer
    //   6. If WAIT → skip
}
```

### 4. `NarrationQueue` — remove distance-based ordering

Events are now ordered by `curveDistanceFromStart` (their physical position on
the route) instead of a pre-computed trigger distance. The `nextEvent()` method
no longer checks `triggerDistanceFromStart <= currentProgress`; instead, the
manager calls `evaluate()` for each pending event.

### 5. `NarrationConfig` — add profile

```kotlin
data class NarrationConfig(
    val mode: DrivingMode = DrivingMode.CAR,
    val verbosity: Int = 2,
    val units: SpeedUnit = SpeedUnit.KMH,
    val timingProfile: TimingProfile = TimingProfile.NORMAL,
    val narrateStraights: Boolean = false,
    val narrateLeanAngle: Boolean = true,
    val narrateSurface: Boolean = true
)
```

Remove `lookAheadSeconds` — it's replaced by the profile-driven kinematic model.

### 6. `NarrationListener` — add urgent callback

```kotlin
interface NarrationListener {
    fun onNarration(event: NarrationEvent)
    fun onInterrupt(event: NarrationEvent)
    fun onUrgentAlert(event: NarrationEvent)  // NEW: distinct audio treatment
    fun onPaused(reason: String)
    fun onResumed()
}
```

The `onUrgentAlert` callback allows the TTS layer to play a warning tone before
the urgent narration, or use a different TTS voice/rate.

## Worked Example

**Scenario**: Driver at 100 km/h (27.8 m/s), Normal profile, approaching a
SHARP right curve with advisory speed 50 km/h (13.9 m/s), narration text is
"Sharp right ahead, slow to 50" (6 words).

```
v_current   = 27.8 m/s
v_advisory  = 13.9 m/s
decelRate   = 4.0 m/s² (car)
reactionTime = 1.5 s (Normal)
ttsDuration  = 6/2.5 + 0.3 = 2.7 s

brakingDist  = (27.8² - 13.9²) / (2 * 4.0) = (772.84 - 193.21) / 8 = 72.5 m
reactionDist = 27.8 * 1.5 = 41.7 m
ttsDist      = 27.8 * 2.7 = 75.1 m

totalLead    = 72.5 + 41.7 + 75.1 = 189.3 m

→ Trigger fires when driver is 189m from curve entry (~6.8s before curve)
→ TTS plays for 2.7s, driver reacts for 1.5s, then brakes for ~3.5s
→ Arrives at curve entry at ~50 km/h ✓

Urgent threshold (Normal = 0.6):
→ Fires urgent if distanceToCurve < 72.5 * 0.6 = 43.5 m
→ At 100 km/h and 43m out, driver has <1.6s → "BRAKE — sharp right!"
```

**Same curve at 60 km/h (16.7 m/s)**:

```
brakingDist  = (16.7² - 13.9²) / 8 = (278.89 - 193.21) / 8 = 10.7 m
reactionDist = 16.7 * 1.5 = 25.0 m
ttsDist      = 16.7 * 2.7 = 45.1 m

totalLead    = 10.7 + 25.0 + 45.1 = 80.8 m

→ Trigger fires at 80.8m (~4.8s before curve)
→ Much less lead time needed because braking requirement is minimal
```

**No braking needed (already at 45 km/h, advisory is 50)**:

```
brakingDist  = 0
reactionDist = 12.5 * 1.5 = 18.75 m
ttsDist      = 12.5 * 2.7 = 33.75 m

totalLead    = max(52.5, 100) = 100 m (minimum floor)

→ Trigger fires at 100m (~8s at 45 km/h). Just an awareness heads-up.
```

## Files to Create/Modify

| File | Action | Description |
|------|--------|-------------|
| `narration/.../types/TimingProfile.kt` | **CREATE** | `TimingProfile` enum + `TimingProfileConfig` data class |
| `narration/.../types/TriggerDecision.kt` | **CREATE** | `TriggerDecision` enum (FIRE, URGENT, WAIT) |
| `narration/.../types/NarrationEvent.kt` | MODIFY | Replace `triggerDistanceFromStart` with `curveDistanceFromStart` + `advisorySpeedMs` |
| `narration/.../types/NarrationConfig.kt` | MODIFY | Replace `lookAheadSeconds` with `timingProfile` |
| `narration/.../TimingCalculator.kt` | MODIFY | Add `evaluate()` + `estimateTtsDuration()`, keep `brakingDistance()` |
| `narration/.../NarrationManager.kt` | MODIFY | Dynamic trigger loop in `onLocationUpdate()`, cooldown tracking |
| `narration/.../NarrationQueue.kt` | MODIFY | Order by `curveDistanceFromStart`, remove distance-threshold check |
| `narration/.../NarrationListener` | MODIFY | Add `onUrgentAlert()` callback |
| `app/.../audio/AndroidTtsEngine.kt` | MODIFY | Handle urgent alerts (tone + speech) |
| `app/.../ui/session/SessionViewModel.kt` | MODIFY | Pass profile config, handle urgent callback |
| `app/.../ui/settings/` | MODIFY | Add timing profile selector (Relaxed/Normal/Sporty) |

## Out of Scope (Future)

- **Adaptive learning**: Tracking actual driver braking behavior to tune reaction
  time assumptions. Deferred until the core kinematic model is validated.
- **IMU/accelerometer input**: Using phone sensors for better acceleration data.
- **Per-curve updates for compound groups**: e.g. "left now" / "right now" within
  an S-bend.
- **Elevation/grade factoring**: Uphill braking is shorter, downhill is longer.
