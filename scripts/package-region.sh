#!/usr/bin/env bash
#
# package-region.sh — Package a CurveCall region for distribution.
#
# Takes a GraphHopper graph directory and an .mbtiles tile archive,
# packages them for download, and generates SHA-256 checksums.
#
# Output:
#   <output-dir>/<region-id>-graph.ghz     Zipped graph directory
#   <output-dir>/<region-id>-tiles.mbtiles  Copy of tile archive
#   <output-dir>/<region-id>-checksums.txt  SHA-256 checksums
#
# Usage:
#   ./package-region.sh <graph-dir> <tiles.mbtiles> <region-id> [output-dir]
#
# Examples:
#   ./package-region.sh virginia-graph virginia-tiles.mbtiles virginia
#   ./package-region.sh wv-graph wv-tiles.mbtiles west-virginia ./dist

set -euo pipefail

# ---------------------------------------------------------------------------
# Argument validation
# ---------------------------------------------------------------------------

if [[ $# -lt 3 ]]; then
    echo "Usage: $0 <graph-dir> <tiles.mbtiles> <region-id> [output-dir]"
    echo ""
    echo "Arguments:"
    echo "  graph-dir      GraphHopper graph directory (from build-graph.sh)"
    echo "  tiles.mbtiles  MBTiles tile archive (from build-tiles.sh or MOBAC)"
    echo "  region-id      Region identifier (e.g. 'virginia', 'west-virginia')"
    echo "  output-dir     Output directory (default: ./dist)"
    echo ""
    echo "Output files:"
    echo "  <region-id>-graph.ghz       Zipped routing graph"
    echo "  <region-id>-tiles.mbtiles   Map tile archive"
    echo "  <region-id>-checksums.txt   SHA-256 checksums for both files"
    exit 1
fi

GRAPH_DIR="$1"
TILES_FILE="$2"
REGION_ID="$3"
OUTPUT_DIR="${4:-./dist}"

# Validate inputs
if [[ ! -d "$GRAPH_DIR" ]]; then
    echo "ERROR: Graph directory not found: $GRAPH_DIR"
    exit 1
fi

if [[ ! -f "$TILES_FILE" ]]; then
    echo "ERROR: Tiles file not found: $TILES_FILE"
    exit 1
fi

# ---------------------------------------------------------------------------
# Create output directory
# ---------------------------------------------------------------------------

mkdir -p "$OUTPUT_DIR"
echo "==> Packaging region: $REGION_ID"
echo "    Graph source: $GRAPH_DIR"
echo "    Tiles source: $TILES_FILE"
echo "    Output dir:   $OUTPUT_DIR"
echo ""

# ---------------------------------------------------------------------------
# Package graph directory as .ghz (zip archive)
# ---------------------------------------------------------------------------

GHZ_FILE="${OUTPUT_DIR}/${REGION_ID}-graph.ghz"

echo "==> Creating graph archive: $GHZ_FILE"

# Remove existing archive if present
rm -f "$GHZ_FILE"

# Zip the graph directory contents (not the directory itself)
# The Android app expects to unzip directly into a graph directory
(cd "$GRAPH_DIR" && zip -r -q "$(cd "$(dirname "$GHZ_FILE")" && pwd)/$(basename "$GHZ_FILE")" .)

GHZ_SIZE=$(du -sh "$GHZ_FILE" | cut -f1)
GHZ_SIZE_MB=$(du -sm "$GHZ_FILE" | cut -f1)
echo "    Size: $GHZ_SIZE"

# ---------------------------------------------------------------------------
# Copy tiles file
# ---------------------------------------------------------------------------

TILES_OUTPUT="${OUTPUT_DIR}/${REGION_ID}-tiles.mbtiles"

echo "==> Copying tiles archive: $TILES_OUTPUT"

if [[ "$(realpath "$TILES_FILE")" != "$(realpath "$TILES_OUTPUT" 2>/dev/null || echo "")" ]]; then
    cp "$TILES_FILE" "$TILES_OUTPUT"
fi

TILES_SIZE=$(du -sh "$TILES_OUTPUT" | cut -f1)
TILES_SIZE_MB=$(du -sm "$TILES_OUTPUT" | cut -f1)
echo "    Size: $TILES_SIZE"

# ---------------------------------------------------------------------------
# Generate SHA-256 checksums
# ---------------------------------------------------------------------------

CHECKSUM_FILE="${OUTPUT_DIR}/${REGION_ID}-checksums.txt"

echo "==> Generating SHA-256 checksums..."

# Use shasum on macOS, sha256sum on Linux
if command -v sha256sum &> /dev/null; then
    SHA_CMD="sha256sum"
elif command -v shasum &> /dev/null; then
    SHA_CMD="shasum -a 256"
else
    echo "ERROR: Neither sha256sum nor shasum found."
    exit 1
fi

GHZ_HASH=$($SHA_CMD "$GHZ_FILE" | cut -d' ' -f1)
TILES_HASH=$($SHA_CMD "$TILES_OUTPUT" | cut -d' ' -f1)

cat > "$CHECKSUM_FILE" << EOF
# CurveCall region checksums — ${REGION_ID}
# Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")

${GHZ_HASH}  ${REGION_ID}-graph.ghz
${TILES_HASH}  ${REGION_ID}-tiles.mbtiles
EOF

echo "    Graph checksum:  sha256:${GHZ_HASH}"
echo "    Tiles checksum:  sha256:${TILES_HASH}"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo ""
echo "==> Region packaged successfully: $REGION_ID"
echo ""
echo "    Files:"
echo "      ${GHZ_FILE}  (${GHZ_SIZE})"
echo "      ${TILES_OUTPUT}  (${TILES_SIZE})"
echo "      ${CHECKSUM_FILE}"
echo ""
echo "    For regions.json entry:"
echo "    {"
echo "      \"id\": \"${REGION_ID}\","
echo "      \"graphUrl\": \"https://cdn.example.com/${REGION_ID}-graph.ghz\","
echo "      \"graphSizeMb\": ${GHZ_SIZE_MB},"
echo "      \"tilesUrl\": \"https://cdn.example.com/${REGION_ID}-tiles.mbtiles\","
echo "      \"tilesSizeMb\": ${TILES_SIZE_MB},"
echo "      \"graphChecksum\": \"sha256:${GHZ_HASH}\","
echo "      \"tilesChecksum\": \"sha256:${TILES_HASH}\""
echo "    }"
echo ""
echo "Next steps:"
echo "  1. Upload files to your CDN/hosting"
echo "  2. Update scripts/regions.json with the URLs and checksums above"
echo "  3. Test download on device"
