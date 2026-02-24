# Narration Enhancement — Implementation Checklist

## Phase 0: Verbosity Tier Dispatch + Speed-Adaptive (Foundation)

- [x] 0A. Create `VerbosityTier.kt` enum (TERSE, STANDARD, DESCRIPTIVE)
- [x] 0B. Refactor TemplateEngine with tier dispatch (resolveTier, 3-tier templates for all 8 builders, speed-adaptive downgrade)
- [x] 0C. Extend "Caution" prefix to all modes for tightening
- [x] 0D. Fire-time re-generation in NarrationManager (re-gen text at current speed)
- [x] 0E. Tests: VerbosityTierTest, TemplateEngineTest tier tests, NarrationManagerTest fire-time re-gen

## Phase 1: Intelligent Sequencing (High Value)

- [x] 1A. WindingDetector: detect winding sections (6+ curves in 60s window), breakthrough rules (HAIRPIN, SHARP+, TIGHTENING, median+2)
- [x] 1B. Switchback detection: CompoundType.SWITCHBACKS, positionInCompound, detectSwitchbacks (3+ sharp/hairpin, alternating, <200m gaps)
- [x] 1C. TransitionDetector: severity jumps (2+ levels), density changes (straight↔winding)
- [x] 1D. Context-sensitive transition words in merged events (into <30m / then same-sev / followed by diff-sev)
- [x] 1E. Integration: winding overview + switchback + transition templates in TemplateEngine
- [x] 1F. Integration: winding suppression + connector words + transition events in NarrationManager
- [x] 1G. Tests: WindingDetectorTest (14), TransitionDetectorTest (11), CompoundDetectorTest switchbacks (5), TemplateEngineTest new templates, NarrationManagerTest integration

## Phase 2: Suppression + Spatial Audio (Enhancement)

- [x] 2A. SuppressionEngine: speed floor (<15km/h), already-slow (<advisory×1.1), repetition (20s window), never-suppress overrides
- [x] 2B. SpatialTonePlayer: stereo-panned pre-cue tones (AudioTrack sine wave, 600-1400Hz by severity)
- [ ] 2C. Phrasing variation: 2-3 word-order variants at Standard tier (deferred — low priority)
- [x] 2D. NarrationEvent: add direction/severity fields for spatial audio layer
- [ ] 2E. Integration: suppression in NarrationManager, spatial audio in TTS layer (deferred — requires Android runtime)
- [x] 2F. Tests: SuppressionEngineTest (15 tests)

## Verification

- [x] `./gradlew :engine:test` — all engine tests pass (150 including 5 new switchback tests)
- [x] `./gradlew :narration:test` — all 222 narration tests pass
