#!/usr/bin/env bash
#
# build-tiles.sh — Generate offline map tile archives (.mbtiles) from OSM data.
#
# Produces .mbtiles files for use with osmdroid on the CurveCall Android app.
# Tiles cover zoom levels 8-15 (overview through road-level detail).
# Zoom 16+ is handled on-device by the corridor-based TileDownloader.
#
# This script supports two approaches:
#   1. tilemaker (CLI, automated) — renders vector tiles from PBF, then rasterizes
#   2. MOBAC guide (manual) — for rasterizing from an online tile server
#
# For personal use, the MOBAC approach is simpler and produces better-looking
# raster tiles (they use the standard OSM tile style). The tilemaker approach
# is fully automated but requires more setup.
#
# Prerequisites (tilemaker approach):
#   - tilemaker installed: https://github.com/systemed/tilemaker
#   - tippecanoe (optional, for tile optimization)
#   - osmium-tool (for PBF bounding-box extraction)
#   - mbutil or similar for .mbtiles packaging
#
# Usage:
#   ./build-tiles.sh <input.osm.pbf> <output.mbtiles> [--bbox north,south,east,west]
#
# Examples:
#   ./build-tiles.sh virginia-latest.osm.pbf virginia-tiles.mbtiles
#   ./build-tiles.sh us-latest.osm.pbf wv-tiles.mbtiles --bbox 40.64,37.20,-77.72,-82.64
#
# MOBAC approach (recommended for personal use — see guide below):
#   1. Download MOBAC from https://mobac.sourceforge.io/
#   2. Select "OpenStreetMap Mapnik" as the tile source
#   3. Draw a selection box around the target state
#   4. Set zoom levels: 8, 9, 10, 11, 12, 13, 14, 15
#   5. Set output format: "MBTiles SQLite"
#   6. Click "Create Atlas"
#   7. Output: .mbtiles file ready for the device

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

MIN_ZOOM=8
MAX_ZOOM=15

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

if [[ $# -lt 2 ]]; then
    echo "Usage: $0 <input.osm.pbf> <output.mbtiles> [--bbox north,south,east,west]"
    echo ""
    echo "This script downloads raster tiles for a region and packages them as .mbtiles."
    echo ""
    echo "Arguments:"
    echo "  input.osm.pbf   OSM PBF file (used to derive bounding box if --bbox not given)"
    echo "  output.mbtiles   Output MBTiles file path"
    echo "  --bbox           Bounding box as north,south,east,west (decimal degrees)"
    echo ""
    echo "Approaches:"
    echo "  Auto:   This script uses osmium + python to download OSM raster tiles"
    echo "  Manual: Use MOBAC (Mobile Atlas Creator) for a GUI-based workflow"
    echo ""
    echo "MOBAC Guide (recommended for personal use):"
    echo "  1. Download MOBAC: https://mobac.sourceforge.io/"
    echo "  2. Tile source: OpenStreetMap Mapnik"
    echo "  3. Draw selection box around the target state"
    echo "  4. Zoom levels: 8-15"
    echo "  5. Output format: MBTiles SQLite"
    echo "  6. Create Atlas"
    echo ""
    echo "Expected output sizes (zoom 8-15):"
    echo "  West Virginia:  ~400-600 MB"
    echo "  Virginia:       ~700-1000 MB"
    echo "  North Carolina: ~800-1200 MB"
    echo "  Colorado:       ~600-900 MB"
    echo "  Tennessee:      ~600-900 MB"
    exit 1
fi

PBF_FILE="$1"
OUTPUT_FILE="$2"
shift 2

BBOX=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --bbox)
            BBOX="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

if [[ ! -f "$PBF_FILE" ]]; then
    echo "ERROR: PBF file not found: $PBF_FILE"
    exit 1
fi

# ---------------------------------------------------------------------------
# Extract bounding box from PBF if not provided
# ---------------------------------------------------------------------------

if [[ -z "$BBOX" ]]; then
    echo "==> Extracting bounding box from PBF file..."
    if ! command -v osmium &> /dev/null; then
        echo "ERROR: osmium-tool not found. Install it or provide --bbox manually."
        echo "  macOS:  brew install osmium-tool"
        echo "  Ubuntu: apt install osmium-tool"
        exit 1
    fi

    # osmium fileinfo outputs header bbox as "minlon,minlat,maxlon,maxlat"
    HEADER_BBOX=$(osmium fileinfo -e -g header.boxes "$PBF_FILE" 2>/dev/null | head -1)
    if [[ -z "$HEADER_BBOX" ]]; then
        echo "ERROR: Could not extract bounding box from PBF header."
        echo "Provide it manually with --bbox north,south,east,west"
        exit 1
    fi

    # Parse osmium format: (minlon,minlat,maxlon,maxlat)
    MINLON=$(echo "$HEADER_BBOX" | tr '()' ' ' | cut -d',' -f1 | tr -d ' ')
    MINLAT=$(echo "$HEADER_BBOX" | tr '()' ' ' | cut -d',' -f2 | tr -d ' ')
    MAXLON=$(echo "$HEADER_BBOX" | tr '()' ' ' | cut -d',' -f3 | tr -d ' ')
    MAXLAT=$(echo "$HEADER_BBOX" | tr '()' ' ' | cut -d',' -f4 | tr -d ' ')

    BBOX="${MAXLAT},${MINLAT},${MAXLON},${MINLON}"
    echo "    Bounding box: north=$MAXLAT south=$MINLAT east=$MAXLON west=$MINLON"
fi

# Parse bbox components
NORTH=$(echo "$BBOX" | cut -d',' -f1)
SOUTH=$(echo "$BBOX" | cut -d',' -f2)
EAST=$(echo "$BBOX" | cut -d',' -f3)
WEST=$(echo "$BBOX" | cut -d',' -f4)

echo "==> Tile generation parameters:"
echo "    Bounding box: N=$NORTH S=$SOUTH E=$EAST W=$WEST"
echo "    Zoom levels:  $MIN_ZOOM - $MAX_ZOOM"
echo "    Output:       $OUTPUT_FILE"
echo ""

# ---------------------------------------------------------------------------
# Generate tiles using Python script (downloads from OSM tile servers)
# ---------------------------------------------------------------------------

echo "==> Generating .mbtiles via tile download..."
echo "    This downloads raster tiles from tile.openstreetmap.org"
echo "    and packages them into an MBTiles SQLite database."
echo ""
echo "    IMPORTANT: Respect OSM tile usage policy:"
echo "    - Max 2 downloads/sec (enforced by this script)"
echo "    - Include proper User-Agent"
echo "    - For bulk downloads, consider using a local tile server instead"
echo ""

if ! command -v python3 &> /dev/null; then
    echo "ERROR: python3 not found."
    exit 1
fi

# Create a temporary Python script for tile downloading
TILE_SCRIPT=$(mktemp /tmp/build_tiles_XXXXXX.py)
trap "rm -f $TILE_SCRIPT" EXIT

cat > "$TILE_SCRIPT" << 'PYTHON_SCRIPT'
"""
Download OSM raster tiles and package into MBTiles format.

MBTiles spec: https://github.com/mapbox/mbtiles-spec/blob/master/1.3/spec.md
OSM tile naming: https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames
"""

import math
import os
import sqlite3
import sys
import time
import urllib.request

def deg2tile(lat_deg, lon_deg, zoom):
    """Convert lat/lon to tile x,y at given zoom level."""
    lat_rad = math.radians(lat_deg)
    n = 2.0 ** zoom
    x = int((lon_deg + 180.0) / 360.0 * n)
    y = int((1.0 - math.asinh(math.tan(lat_rad)) / math.pi) / 2.0 * n)
    return x, y

def count_tiles(north, south, east, west, min_zoom, max_zoom):
    """Count total tiles in the bounding box across all zoom levels."""
    total = 0
    for z in range(min_zoom, max_zoom + 1):
        x_min, y_min = deg2tile(north, west, z)
        x_max, y_max = deg2tile(south, east, z)
        total += (x_max - x_min + 1) * (y_max - y_min + 1)
    return total

def create_mbtiles(output_path, north, south, east, west, min_zoom, max_zoom):
    """Download tiles and create MBTiles database."""
    total_tiles = count_tiles(north, south, east, west, min_zoom, max_zoom)
    print(f"    Total tiles to download: {total_tiles:,}")

    # Estimate size (average ~15KB per tile)
    est_mb = total_tiles * 15 / 1024
    print(f"    Estimated output size:   ~{est_mb:.0f} MB")
    print()

    if os.path.exists(output_path):
        os.remove(output_path)

    conn = sqlite3.connect(output_path)
    c = conn.cursor()

    # MBTiles schema
    c.execute("""
        CREATE TABLE metadata (
            name TEXT,
            value TEXT
        )
    """)
    c.execute("""
        CREATE TABLE tiles (
            zoom_level INTEGER,
            tile_column INTEGER,
            tile_row INTEGER,
            tile_data BLOB
        )
    """)
    c.execute("""
        CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)
    """)

    # Metadata
    c.execute("INSERT INTO metadata VALUES ('name', 'CurveCall OSM Tiles')")
    c.execute("INSERT INTO metadata VALUES ('type', 'baselayer')")
    c.execute("INSERT INTO metadata VALUES ('version', '1')")
    c.execute("INSERT INTO metadata VALUES ('description', 'OpenStreetMap tiles for CurveCall offline navigation')")
    c.execute("INSERT INTO metadata VALUES ('format', 'png')")
    c.execute(f"INSERT INTO metadata VALUES ('bounds', '{west},{south},{east},{north}')")
    c.execute(f"INSERT INTO metadata VALUES ('minzoom', '{min_zoom}')")
    c.execute(f"INSERT INTO metadata VALUES ('maxzoom', '{max_zoom}')")
    conn.commit()

    # Tile servers (round-robin)
    servers = ['a', 'b', 'c']
    server_idx = 0

    downloaded = 0
    errors = 0
    start_time = time.time()

    for z in range(min_zoom, max_zoom + 1):
        x_min, y_min = deg2tile(north, west, z)
        x_max, y_max = deg2tile(south, east, z)
        level_tiles = (x_max - x_min + 1) * (y_max - y_min + 1)
        print(f"    Zoom {z:2d}: {level_tiles:>8,} tiles (x: {x_min}-{x_max}, y: {y_min}-{y_max})")

        for x in range(x_min, x_max + 1):
            for y in range(y_min, y_max + 1):
                server = servers[server_idx % len(servers)]
                server_idx += 1

                url = f"https://{server}.tile.openstreetmap.org/{z}/{x}/{y}.png"

                # MBTiles uses TMS y-coordinate (flipped)
                tms_y = (2 ** z) - 1 - y

                try:
                    req = urllib.request.Request(url, headers={
                        'User-Agent': 'CurveCall-TileBuilder/1.0 (personal use; https://github.com/curvecall)'
                    })
                    with urllib.request.urlopen(req, timeout=30) as resp:
                        tile_data = resp.read()
                        c.execute(
                            "INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)",
                            (z, x, tms_y, tile_data)
                        )
                except Exception as e:
                    errors += 1
                    if errors <= 10:
                        print(f"    WARNING: Failed to download z={z} x={x} y={y}: {e}")
                    elif errors == 11:
                        print(f"    WARNING: Suppressing further error messages...")

                downloaded += 1
                if downloaded % 100 == 0:
                    elapsed = time.time() - start_time
                    rate = downloaded / elapsed if elapsed > 0 else 0
                    pct = downloaded / total_tiles * 100
                    print(f"    Progress: {downloaded:,}/{total_tiles:,} ({pct:.1f}%) — {rate:.1f} tiles/sec", end='\r')
                    conn.commit()

                # Rate limit: max 2 requests/sec to respect OSM tile usage policy
                time.sleep(0.5)

    conn.commit()
    conn.close()

    elapsed = time.time() - start_time
    size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"\n    Done: {downloaded:,} tiles downloaded, {errors} errors")
    print(f"    Time: {elapsed/60:.1f} minutes")
    print(f"    Output: {output_path} ({size_mb:.1f} MB)")

if __name__ == '__main__':
    if len(sys.argv) != 7:
        print("Usage: build_tiles.py <output> <north> <south> <east> <west> <min_zoom> <max_zoom>")
        sys.exit(1)

    output = sys.argv[1]
    north = float(sys.argv[2])
    south = float(sys.argv[3])
    east = float(sys.argv[4])
    west = float(sys.argv[5])
    min_zoom, max_zoom = int(sys.argv[6].split('-')[0]), int(sys.argv[6].split('-')[1])

    create_mbtiles(output, north, south, east, west, min_zoom, max_zoom)
PYTHON_SCRIPT

python3 "$TILE_SCRIPT" \
    "$OUTPUT_FILE" \
    "$NORTH" \
    "$SOUTH" \
    "$EAST" \
    "$WEST" \
    "${MIN_ZOOM}-${MAX_ZOOM}"

echo ""
echo "==> Tile archive complete: $OUTPUT_FILE"
echo ""
echo "Next steps:"
echo "  1. Package with:  ./package-region.sh <graph-dir> $OUTPUT_FILE <region-id>"
echo "  2. Or copy directly to device for testing"
