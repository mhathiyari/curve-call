Now I have the full picture. Let me lay out exactly what it takes to get from where you are to a working offline
  app:

  ---
  What You're Building

  A fully offline routing + curve narration app — pick a destination (search or pin drop), compute route on-device,
  ride/drive with offline maps and narrated curve warnings. Both car and motorcycle. Download regions as needed.

  ---
  What's Already Done (the hard part)

  ┌───────────────────────────────────┬──────────────────┐
  │             Component             │      Status      │
  ├───────────────────────────────────┼──────────────────┤
  │ Curve detection engine            │ Done (145 tests) │
  ├───────────────────────────────────┼──────────────────┤
  │ Narration system + timing         │ Done (147 tests) │
  ├───────────────────────────────────┼──────────────────┤
  │ TTS integration + audio focus     │ Done             │
  ├───────────────────────────────────┼──────────────────┤
  │ Session UI (map, speed, controls) │ Done             │
  ├───────────────────────────────────┼──────────────────┤
  │ Settings + preferences            │ Done             │
  ├───────────────────────────────────┼──────────────────┤
  │ GPX import path                   │ Done             │
  └───────────────────────────────────┴──────────────────┘

  ---
  What Needs to Be Built

  1. On-Device Routing — GraphHopper Android

  GraphHopper has a Java library that runs natively on Android. You feed it pre-processed OSM data, call route(from,
  to), and get back a list of coordinates — which goes straight into your existing RouteAnalyzer.

  - Add GraphHopper Android dependency
  - Pre-process OSM region data into routing graphs (done once per region, hosted for download)
  - Wire: destination → graphHopper.route() → coordinates → RouteAnalyzer → NarrationManager
  - Effort: ~3-5 days

  2. Offline Vector Maps

  You can either keep osmdroid with offline tile packs (easier) or migrate to MapLibre with MBTiles (more modern,
  better looking). For personal use, osmdroid with offline tiles is the pragmatic choice.

  - Configure osmdroid to use offline tile archives
  - Download and package regional tile sets
  - Effort: ~2-3 days

  3. Region Download Manager

  A screen where you pick regions to download (Virginia, Smoky Mountains, etc.). Each download includes map tiles +
  routing data.

  - UI: list of regions, download progress, storage used, delete
  - Backend: host pre-processed files on S3 or similar (~$5/month)
  - Effort: ~2-3 days

  4. Destination Picker

  Replace the GPX file picker with a proper "where are you going?" flow.

  - Online search bar (Nominatim — free, no API key) when you have signal
  - Pin drop on the offline map (always works)
  - Recent destinations / favorites
  - Effort: ~1-2 days

  5. Route Preview

  After routing, show the route with curve analysis before starting.

  - Route on map with severity coloring (you already have RouteOverlay)
  - Summary: "38 curves, 7 sharp, ~1h45m"
  - "Start Ride" button → existing session flow
  - Effort: ~1-2 days

  6. Data Preparation Pipeline (one-time setup)

  Scripts to process OSM data into downloadable region packages.

  - Download OSM PBF extracts (from Geofabrik)
  - Run GraphHopper preprocessing → routing graph files
  - Generate tile archives for each region
  - Upload to hosting
  - Effort: ~2-3 days

  ---
  What You Can Skip (for personal MVP)

  - Turn-by-turn voice directions ("turn left in 500 feet") — just show the route on the map and narrate curves. You
  can see the map.
  - Re-routing when off-route — your MapMatcher already detects off-route. For now, just warn. Add re-routing later.
  - Fancy styling — default OSM tiles are fine for personal use.

  ---
  Total Effort Estimate

  ┌─────────────────────────────────┬─────────────┐
  │                                 │    Days     │
  ├─────────────────────────────────┼─────────────┤
  │ GraphHopper routing integration │ 3-5         │
  ├─────────────────────────────────┼─────────────┤
  │ Offline map tiles               │ 2-3         │
  ├─────────────────────────────────┼─────────────┤
  │ Region download manager         │ 2-3         │
  ├─────────────────────────────────┼─────────────┤
  │ Destination picker              │ 1-2         │
  ├─────────────────────────────────┼─────────────┤
  │ Route preview                   │ 1-2         │
  ├─────────────────────────────────┼─────────────┤
  │ Data preparation pipeline       │ 2-3         │
  ├─────────────────────────────────┼─────────────┤
  │ Testing + debugging on device   │ 3-5         │
  ├─────────────────────────────────┼─────────────┤
  │ Total                           │ ~15-25 days │
  └─────────────────────────────────┴─────────────┘

  ---
  The Key Insight

  The novel, complex part is already built. Curve detection, severity classification, narration timing, TTS
  integration — that's your secret sauce and it's done. What remains is integrating well-documented, battle-tested
───────────────────────────────────────────────────