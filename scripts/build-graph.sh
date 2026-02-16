#!/usr/bin/env bash
#
# build-graph.sh — Build a GraphHopper routing graph from an OSM PBF extract.
#
# Produces a graph directory with CH (Contraction Hierarchies) for the "car"
# and "motorcycle" profiles used by the CurveCall Android app.
#
# Prerequisites:
#   - Java 17+ (GraphHopper 11.0 requires Java 17)
#   - ~8 GB RAM for large US states (set via JAVA_OPTS)
#   - Internet connection (first run only — to download the GraphHopper JAR)
#
# Usage:
#   ./build-graph.sh <input.osm.pbf> <output-graph-dir>
#
# Examples:
#   ./build-graph.sh virginia-latest.osm.pbf virginia-graph
#   ./build-graph.sh west-virginia-latest.osm.pbf west-virginia-graph
#
# The output directory can later be zipped with package-region.sh into a
# .ghz archive for deployment to the Android device.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GH_VERSION="11.0"
GH_JAR="graphhopper-web-${GH_VERSION}.jar"
GH_JAR_URL="https://repo1.maven.org/maven2/com/graphhopper/graphhopper-web/${GH_VERSION}/${GH_JAR}"
CONFIG_FILE="${SCRIPT_DIR}/graphhopper-config.yml"

# Default Java heap — increase for large states (CA, TX, etc.)
JAVA_OPTS="${JAVA_OPTS:--Xmx8g -Xms2g}"

# ---------------------------------------------------------------------------
# Argument validation
# ---------------------------------------------------------------------------

if [[ $# -lt 2 ]]; then
    echo "Usage: $0 <input.osm.pbf> <output-graph-dir>"
    echo ""
    echo "Arguments:"
    echo "  input.osm.pbf    Path to an OSM PBF extract (e.g. from Geofabrik)"
    echo "  output-graph-dir  Directory where the graph will be written"
    echo ""
    echo "Examples:"
    echo "  $0 virginia-latest.osm.pbf virginia-graph"
    echo "  $0 /data/osm/west-virginia-latest.osm.pbf /data/graphs/west-virginia-graph"
    exit 1
fi

PBF_FILE="$1"
GRAPH_DIR="$2"

if [[ ! -f "$PBF_FILE" ]]; then
    echo "ERROR: PBF file not found: $PBF_FILE"
    exit 1
fi

if [[ ! -f "$CONFIG_FILE" ]]; then
    echo "ERROR: Config file not found: $CONFIG_FILE"
    echo "Expected at: $CONFIG_FILE"
    exit 1
fi

# ---------------------------------------------------------------------------
# Check Java version
# ---------------------------------------------------------------------------

echo "==> Checking Java version..."
if ! command -v java &> /dev/null; then
    echo "ERROR: Java not found. Install Java 17+ (e.g. OpenJDK 21)."
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [[ "$JAVA_VERSION" -lt 17 ]]; then
    echo "ERROR: Java 17+ required. Found Java $JAVA_VERSION."
    exit 1
fi
echo "    Java $JAVA_VERSION detected."

# ---------------------------------------------------------------------------
# Download GraphHopper JAR if not present
# ---------------------------------------------------------------------------

if [[ ! -f "${SCRIPT_DIR}/${GH_JAR}" ]]; then
    echo "==> Downloading GraphHopper ${GH_VERSION} JAR..."
    echo "    URL: $GH_JAR_URL"
    if command -v curl &> /dev/null; then
        curl -L -o "${SCRIPT_DIR}/${GH_JAR}" "$GH_JAR_URL"
    elif command -v wget &> /dev/null; then
        wget -O "${SCRIPT_DIR}/${GH_JAR}" "$GH_JAR_URL"
    else
        echo "ERROR: Neither curl nor wget found. Install one of them."
        exit 1
    fi
    echo "    Downloaded: ${SCRIPT_DIR}/${GH_JAR}"
else
    echo "==> GraphHopper JAR already present: ${SCRIPT_DIR}/${GH_JAR}"
fi

# ---------------------------------------------------------------------------
# Clean any previous graph at the target location
# ---------------------------------------------------------------------------

if [[ -d "$GRAPH_DIR" ]]; then
    echo "==> Removing existing graph directory: $GRAPH_DIR"
    rm -rf "$GRAPH_DIR"
fi

# ---------------------------------------------------------------------------
# Run GraphHopper import
# ---------------------------------------------------------------------------

echo "==> Building routing graph..."
echo "    PBF input:   $PBF_FILE"
echo "    Graph output: $GRAPH_DIR"
echo "    Profiles:     car, motorcycle (with CH)"
echo "    Java opts:    $JAVA_OPTS"
echo ""

# Convert relative paths to absolute for Java
PBF_ABS="$(cd "$(dirname "$PBF_FILE")" && pwd)/$(basename "$PBF_FILE")"
GRAPH_ABS="$(mkdir -p "$(dirname "$GRAPH_DIR")" && cd "$(dirname "$GRAPH_DIR")" && pwd)/$(basename "$GRAPH_DIR")"

java $JAVA_OPTS \
    -Ddw.graphhopper.datareader.file="$PBF_ABS" \
    -Ddw.graphhopper.graph.location="$GRAPH_ABS" \
    -jar "${SCRIPT_DIR}/${GH_JAR}" \
    import \
    "$CONFIG_FILE"

# ---------------------------------------------------------------------------
# Verify output
# ---------------------------------------------------------------------------

if [[ ! -d "$GRAPH_ABS" ]]; then
    echo "ERROR: Graph directory was not created. Import may have failed."
    exit 1
fi

GRAPH_SIZE=$(du -sh "$GRAPH_ABS" | cut -f1)
echo ""
echo "==> Graph build complete."
echo "    Output: $GRAPH_ABS"
echo "    Size:   $GRAPH_SIZE"
echo ""
echo "Next steps:"
echo "  1. Package with:  ./package-region.sh $GRAPH_ABS <tiles.mbtiles> <region-id>"
echo "  2. Or zip manually: cd $GRAPH_ABS && zip -r ../$(basename "$GRAPH_ABS").ghz ."
