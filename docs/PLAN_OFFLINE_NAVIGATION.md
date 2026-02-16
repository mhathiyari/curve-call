# CurveCall — Offline Navigation Implementation Plan

> **Goal:** Transform CurveCall from a GPX-file-driven app into a fully offline,
> self-contained routing + curve narration app. Pick a destination → compute route
> on-device → ride/drive with offline maps and narrated curve warnings.
>
> **Target:** Personal use MVP. Both car and motorcycle. Rough edges OK.
>
> **Search:** Online (Nominatim) when signal available + pin drop on offline map.

---

## Current State (What's Done)

- [x] Curve detection engine (RouteAnalyzer, 145 tests)
- [x] Narration system + adaptive timing (NarrationManager, 147 tests)
- [x] Template engine for curve text generation
- [x] Android TTS integration with audio focus
- [x] GPX file loading + SAF picker
- [x] Session UI (full-screen map, speed, controls, bottom bar)
- [x] Settings screen (mode, units, verbosity, timing profile, etc.)
- [x] osmdroid map with route overlay + GPS marker
- [x] GPS location tracking (FusedLocationProvider)
- [x] Map matching + off-route detection
- [x] Foreground service for background GPS+TTS
- [x] User preferences (DataStore)
- [x] Tile pre-download along route corridor (TileDownloader)

---

## Phase 1: Data Preparation Pipeline (Server-Side)

> One-time setup. Scripts to generate downloadable region packages
> containing routing graphs + map tile archives.

### 1.1 — GraphHopper Routing Graph Generation

- [ ] Set up a build environment (Linux server or local machine with 8GB+ RAM)
- [ ] Download OSM PBF extracts from Geofabrik for target US states
      - Start with: West Virginia, Virginia, North Carolina, Colorado, Tennessee
      - Source: https://download.geofabrik.de/north-america/us.html
- [x] Write a shell script to automate GraphHopper import per state:
      ```
      Input:  virginia-latest.osm.pbf
      Output: virginia-graph/ (routing graph directory)
      ```
      Script: `scripts/build-graph.sh` + `scripts/graphhopper-config.yml`
- [x] Configure two profiles: `car` and `motorcycle` (use car profile with adjusted params)
      Configured in `scripts/graphhopper-config.yml` — matches `GraphHopperRouter.kt`
- [x] Enable Contraction Hierarchies (CH) for fast on-device routing
      CH profiles for both car and motorcycle in `scripts/graphhopper-config.yml`
- [x] Zip each graph directory into a downloadable `.ghz` archive
      Script: `scripts/package-region.sh`
- [x] Document expected graph sizes per state (target: 50-500MB each)
      Documented in `scripts/regions.json` manifest

### 1.2 — Offline Map Tile Archives

- [x] Evaluate tile generation approach:
      - Option A: MOBAC (Mobile Atlas Creator) — GUI tool, exports to osmdroid SQLite/MBTiles
      - Option B: Self-render with OpenMapTiles/Tilemaker from PBF files
      - Option C: Geofabrik pre-rendered tile packages
      Decision: Script supports both automated (Python tile downloader) and manual (MOBAC guide).
      Script: `scripts/build-tiles.sh`
- [ ] Generate tile archives for target states at zoom levels 8-15
      - Zoom 8-11: overview (whole state visible)
      - Zoom 12-15: road-level detail (where you're actually riding)
      - Skip zoom 16+: too large, corridor-based TileDownloader handles these
- [x] Package as `.mbtiles` or `.sqlite` files (osmdroid supports both natively)
      MBTiles format with proper schema in `scripts/build-tiles.sh`
- [x] Document tile archive sizes per state (target: 500MB-2GB each)
      Documented in `scripts/regions.json` manifest and `scripts/build-tiles.sh` help text

### 1.3 — Region Hosting

- [ ] Set up hosting for region packages (S3, Cloudflare R2, or simple HTTP server)
- [x] Create a `regions.json` manifest file listing available regions:
      ```json
      {
        "regions": [
          {
            "id": "virginia",
            "name": "Virginia",
            "graphUrl": "https://cdn.example.com/virginia-graph.ghz",
            "graphSizeMb": 280,
            "tilesUrl": "https://cdn.example.com/virginia-tiles.mbtiles",
            "tilesSizeMb": 950,
            "boundingBox": { "north": 39.46, "south": 36.54, "east": -75.24, "west": -83.68 },
            "lastUpdated": "2026-02-01"
          }
        ]
      }
      ```
      Template manifest: `scripts/regions.json` — 5 states with realistic bounding boxes and size estimates
- [ ] Upload initial set of region packages
- [ ] Test downloads from a real device

---

## Phase 2: On-Device Routing (GraphHopper Android)

> Add GraphHopper as a library dependency. Load pre-processed graphs.
> Route from A→B and feed coordinates into existing RouteAnalyzer.

### 2.1 — GraphHopper Integration

- [x] Add `graphhopper-core` dependency to `:app` module:
      ```kotlin
      implementation("com.graphhopper:graphhopper-core:11.0")
      ```
- [x] Handle dependency conflicts (exclude SLF4J, Jackson conflicts with Android)
      - Excluded: xmlgraphics-commons (java.awt), osmosis (protobuf), jackson-dataformat-xml (Stax), kotlin-stdlib (version)
      - Added: SLF4J 2.0.17 + logback-android 3.0.0
- [x] Verify the library compiles and runs on Android (target API 26+, 64-bit)
      - Full assembleDebug passes — no DEX merge conflicts, no duplicate classes
- [x] Create `GraphHopperRouter` class in `app/src/main/java/com/curvecall/data/routing/`:
      ```kotlin
      class GraphHopperRouter {
          fun loadGraph(graphDir: File)
          val isGraphLoaded: Boolean
          fun route(from: LatLon, to: LatLon, profile: String): RouteResult
          fun close()
      }
      ```
- [x] `RouteResult` data class:
      ```kotlin
      data class RouteResult(
          val points: List<LatLon>,      // route coordinates → feed to RouteAnalyzer
          val distanceMeters: Double,
          val timeMillis: Long,
      )
      ```
- [x] Configure GraphHopper for Android:
      - MMAP_RO mode via `setAllowWrites(false)` (auto-converts from MMAP)
      - Read-only (no lock files)
      - Load from pre-processed graph directory (NOT import PBF on-device)
      - Profiles: car + motorcycle via custom model JSON
- [x] Wire into Hilt DI (`@Singleton` scoped)
- [ ] Test: load a state graph, route between two points, verify coordinates returned
- [ ] Test: verify RouteAnalyzer successfully processes GraphHopper output

### 2.2 — Route Pipeline Integration

- [x] Create `RoutePipeline` that chains:
      ```
      GraphHopperRouter.route() → List<GeoPoint>
        → RouteAnalyzer.analyze() → List<RouteSegment>
          → NarrationManager.load() → ready for session
      ```
- [x] Handle routing errors gracefully (no graph loaded, no route found, etc.)
- [x] Show route analysis progress (reuse existing loading UI from HomeViewModel)
- [x] Store result in `SessionDataHolder` (existing pattern)

---

## Phase 3: Region Download Manager

> UI for users to download/manage offline map + routing data for regions.

### 3.1 — Download Infrastructure

- [x] Create `RegionRepository` class:
      ```kotlin
      class RegionRepository {
          fun fetchAvailableRegions(): List<Region>        // from regions.json
          fun getDownloadedRegions(): List<DownloadedRegion>
          fun downloadRegion(regionId: String): Flow<DownloadProgress>
          fun deleteRegion(regionId: String)
          fun getStorageUsed(): Long
      }
      ```
- [x] `Region` and `DownloadedRegion` data classes
- [x] Download logic:
      - Download `.ghz` (routing graph archive) → unzip to app internal storage
      - Download `.mbtiles` (map tiles) → copy to osmdroid base path
      - Track download progress with `Flow<DownloadProgress>`
      - Support pause/resume (use OkHttp with Range headers)
      - Verify integrity (checksum in `regions.json`)
- [x] Store download metadata in DataStore or Room DB

### 3.2 — Region Manager UI

- [x] Create `RegionScreen` (new Compose screen):
      - List of available regions with download buttons
      - Show downloaded regions with size + delete button
      - Download progress indicator
      - Total storage used display
      - "Tap to download" / "Downloaded ✓" / "Downloading..." states
- [x] Add navigation to RegionScreen from HomeScreen or Settings
- [x] Create `RegionViewModel` to manage download state

### 3.3 — Offline Tile Integration

- [ ] Configure osmdroid to load downloaded `.mbtiles` files automatically:
      - osmdroid scans its base path for archive files by default
      - Set `mapView.setUseDataConnection(false)` when region tiles are available
      - Fall back to online tiles when no offline data for current area
- [ ] Verify: map displays offline tiles when downloaded, online when not
- [ ] Keep existing `TileDownloader` for corridor-based caching as a supplement

---

## Phase 4: Destination Picker

> Replace GPX-only flow with a proper "where are you going?" experience.

### 4.1 — Search (Online)

- [x] Create `GeocodingService` using Nominatim (free, no API key):
      ```kotlin
      class GeocodingService {
          suspend fun search(query: String): List<SearchResult>
      }
      ```
      - Endpoint: `https://nominatim.openstreetmap.org/search?q=...&format=json`
      - Respect usage policy: 1 req/sec, include User-Agent
      - Return: name, lat/lon, type (city, road, POI)
- [x] Search results UI: list with name + address, tap to select
- [x] Debounce search input (300ms)

### 4.2 — Pin Drop (Offline)

- [x] Add long-press handler to map view → drop a destination pin
- [x] Show pin with "Route here" button
- [x] Reverse geocode pin location (online if available, otherwise show "lat, lon")

### 4.3 — Destination Flow

- [x] Create `DestinationScreen` or expand HomeScreen:
      - Search bar at top
      - Map view below (shows downloaded regions)
      - Recent destinations list
      - "Use GPX file" option (keep existing flow as fallback)
- [x] When destination selected:
      1. Get current GPS location (or let user set start point)
      2. Check if routing graph is loaded for the area
      3. If no graph: prompt to download region
      4. Compute route via `GraphHopperRouter`
      5. Run `RouteAnalyzer` on route
      6. Show route preview (Phase 5)
      Wired end-to-end: DestinationViewModel gets GPS → RoutePipeline auto-loads graph → routes → analyzes → stores → navigates to RoutePreview

### 4.4 — Favorites / Recents

- [x] Save recent destinations to DataStore (name, lat/lon, timestamp)
- [x] Allow marking favorites (star icon)
- [x] Show recents/favorites on destination screen

---

## Phase 5: Route Preview

> After routing + analysis, show a summary before starting the session.

### 5.1 — Route Preview Screen

- [x] Create `RoutePreviewScreen`:
      - Full map showing entire route with severity coloring (reuse `RouteOverlay`)
      - Summary stats:
        - Total distance
        - Estimated time
        - Number of curves (by severity)
        - Number of sharp/hairpin curves (highlighted)
      - "Start Ride" / "Start Drive" button
      - "Back" to pick a different destination
- [x] Create `RoutePreviewViewModel`
- [x] Add navigation: DestinationScreen → RoutePreview → Session

### 5.2 — Route Options (stretch)

- [ ] Allow selecting "curvy" vs "direct" route preference
      (GraphHopper supports alternative routes and custom weighting)
- [ ] Show 2-3 alternative routes with curve counts
- [ ] Let user pick preferred route

---

## Phase 6: Navigation Flow Updates

> Wire the new routing flow into the existing session experience.

### 6.1 — Updated HomeScreen

- [x] Replace or supplement GPX file picker with destination input
- [ ] Show downloaded regions on the home map
- [x] Quick actions: "Recent Rides", "Pick on Map", "Load GPX"
- [x] Show "No offline data" warning if no regions downloaded

### 6.2 — Updated SessionViewModel

- [x] Support starting a session from routing result (not just GPX)
- [x] The existing `MapMatcher` + `NarrationManager` flow should work unchanged
      since both consume `List<RouteSegment>` regardless of source
- [ ] Add re-route capability (stretch goal):
      - When off-route detected → option to re-route to original destination
      - This requires calling `GraphHopperRouter.route()` again from current position

### 6.3 — Navigation Updates

- [x] Update `CurveCallNavHost` with new screens:
      - Home → Destination → RoutePreview → Session
      - Home → GPX Load → RoutePreview → Session (existing path, preserved)
      - Settings → RegionManager

---

## Phase 7: Testing & Polish

### 7.1 — Integration Testing

- [ ] Test full flow: download region → pick destination → route → analyze → narrate
- [ ] Test offline: airplane mode → pin drop → route → ride (with pre-downloaded region)
- [ ] Test GraphHopper memory usage on real device (monitor for OOM)
- [ ] Test with various state graph sizes (small: Vermont ~40MB, large: California ~800MB)
- [ ] Test corridor tile download supplements offline base tiles

### 7.2 — Edge Cases

- [x] No region downloaded → clear guidance to download one
      RoutePipeline: "No offline region covers this destination. Go to Settings → Offline Regions to download one."
- [x] Destination outside downloaded region → inform user, suggest region
      `findRegionForCoordinate()` returns null → clear error message in RoutePipeline
- [ ] Route crosses region boundary → handle gracefully (or warn)
- [ ] Low storage → warn before download, show storage management
- [x] Interrupted download → resume capability
      Already implemented in RegionRepository with OkHttp Range headers (Phase 3)
- [x] Graph loading time → show loading spinner (first load may be slow due to mmap)
      RoutePipeline emits "Loading {region} routing data..." state, shown in RoutingProgressCard

### 7.3 — Polish (Personal MVP Level)

- [x] Smooth transitions between screens
      CurveCallNavHost uses slide + fade animations for all screen transitions
- [x] Loading states for routing computation
      RoutePipeline emits Routing/Analyzing/CachingTiles states → RoutingProgressCard in DestinationScreen
- [x] Error messages that tell user what to do (not just "error occurred")
      "No offline region covers this destination. Go to Settings → Offline Regions to download one."
      "Could not find a route. Try a different destination."
      "Route too short for curve analysis."
      "Region data corrupted. Try deleting and re-downloading."
- [ ] Screen stays on during session (existing, verify still works)
- [ ] Audio focus works correctly (existing, verify)

---

## Architecture Summary

```
┌─────────────────────────────────────────────────────────┐
│                     App Module                          │
│                                                         │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ Destination  │  │   Region     │  │   Route       │  │
│  │   Picker     │──│   Manager    │  │   Preview     │  │
│  │ (search/pin) │  │ (download)   │  │ (summary)     │  │
│  └──────┬───────┘  └──────┬───────┘  └───────┬───────┘  │
│         │                 │                   │          │
│  ┌──────▼─────────────────▼───────────────────▼───────┐  │
│  │              GraphHopperRouter                     │  │
│  │   loadGraph() → route(A,B) → List<GeoPoint>       │  │
│  └──────────────────────┬─────────────────────────────┘  │
│                         │                                │
│  ┌──────────────────────▼─────────────────────────────┐  │
│  │              RoutePipeline                         │  │
│  │   GeoPoints → RouteAnalyzer → NarrationManager    │  │
│  └──────────────────────┬─────────────────────────────┘  │
│                         │                                │
│  ┌──────────────────────▼─────────────────────────────┐  │
│  │              Session (existing)                    │  │
│  │   MapMatcher → NarrationManager → TTS → Speaker   │  │
│  └────────────────────────────────────────────────────┘  │
│                                                         │
│  ┌────────────────────────────────────────────────────┐  │
│  │          Offline Map Tiles (osmdroid)              │  │
│  │   .mbtiles archives + corridor TileDownloader     │  │
│  └────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘

┌────────────────────┐  ┌─────────────────────┐
│   Engine Module    │  │  Narration Module   │
│   (unchanged)      │  │  (unchanged)        │
│   RouteAnalyzer    │  │  NarrationManager   │
│   MapMatcher       │  │  TimingCalculator   │
│   145 tests ✓      │  │  TemplateEngine     │
│                    │  │  147 tests ✓        │
└────────────────────┘  └─────────────────────┘
```

---

## Effort Estimates

| Phase | Description | Effort |
|-------|-------------|--------|
| 1 | Data preparation pipeline (server-side scripts) | 2-3 days |
| 2 | GraphHopper on-device routing integration | 3-5 days |
| 3 | Region download manager (UI + download logic) | 2-3 days |
| 4 | Destination picker (search + pin drop) | 1-2 days |
| 5 | Route preview screen | 1-2 days |
| 6 | Navigation flow updates (wire everything together) | 2-3 days |
| 7 | Testing + edge cases + polish | 3-5 days |
| **Total** | | **~15-25 days** |

---

## Key Technical Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Routing engine | GraphHopper (on-device) | Java-native, works on Android, pre-processed graphs, CH for speed |
| Map tiles | osmdroid + .mbtiles archives | Already using osmdroid, native archive support, no migration needed |
| Tile supplement | Keep existing TileDownloader | Corridor-based z16+ tiles supplement regional z8-15 base tiles |
| Search | Nominatim (online) + pin drop | No API key, free, pin drop covers offline case |
| Region data | Pre-processed on server, downloaded to device | Can't process PBF on Android (needs too much RAM) |
| Graph per state | Individual state graphs (50-500MB) | Manageable downloads, avoid loading entire US (~8-15GB) |
| Profiles | Car + motorcycle (via GraphHopper profiles) | Both modes supported, configured at graph build time |

---

## Files to Create

```
app/src/main/java/com/curvecall/
├── data/
│   ├── routing/
│   │   ├── GraphHopperRouter.kt          # On-device routing wrapper
│   │   └── RoutePipeline.kt              # Router → Analyzer → Narration chain
│   ├── regions/
│   │   ├── RegionRepository.kt           # Download + manage region packages
│   │   ├── Region.kt                     # Region data classes
│   │   └── DownloadManager.kt            # Download with progress/resume
│   └── geocoding/
│       └── GeocodingService.kt           # Nominatim search wrapper
├── ui/
│   ├── destination/
│   │   ├── DestinationScreen.kt          # Search + pin drop + recents
│   │   └── DestinationViewModel.kt
│   ├── regions/
│   │   ├── RegionScreen.kt               # Download/manage regions
│   │   └── RegionViewModel.kt
│   └── preview/
│       ├── RoutePreviewScreen.kt         # Route summary before ride
│       └── RoutePreviewViewModel.kt

scripts/                                   # Server-side data prep
├── build-graph.sh                         # GraphHopper PBF → graph
├── build-tiles.sh                         # PBF → mbtiles (or MOBAC)
├── upload-regions.sh                      # Upload to hosting
└── regions.json                           # Region manifest
```

---

## Risk Register

| Risk | Impact | Mitigation |
|------|--------|------------|
| GraphHopper 11.0 doesn't run on Android | High | Fall back to v10.x or v1.0; test early in Phase 2 |
| State graph too large for device memory | Medium | Start with small states (WV, VT); use 64-bit only |
| Tile archives too large for practical download | Medium | Limit to zoom 8-15; corridor tiles supplement at z16+ |
| Route crosses state boundary | Medium | Warn user; later: support multi-graph routing |
| Nominatim rate limiting | Low | Cache results; 1 req/sec is fine for personal use |
| osmdroid MBTiles rendering issues | Low | Well-tested format; fall back to .sqlite if needed |

---

## Order of Implementation

**Recommended sequence (minimizes blocked work):**

1. **Phase 2.1 first** — Get GraphHopper running on Android (this is the biggest risk)
2. **Phase 1** — Build data prep pipeline (can partly overlap with 2.1)
3. **Phase 2.2** — Wire routing into analysis pipeline
4. **Phase 3** — Region downloads (needs hosted data from Phase 1)
5. **Phase 4** — Destination picker
6. **Phase 5** — Route preview
7. **Phase 6** — Navigation flow updates
8. **Phase 7** — Testing and polish

Start with Phase 2.1 because if GraphHopper doesn't work on Android, the whole
plan needs to pivot (to BRouter, Valhalla, or a different approach). Validate the
riskiest assumption first.
