#!/usr/bin/env python3
"""
gpx-emulator-sim.py — Generate clean GPX files for Android emulator GPS playback.

Takes a route GPX (any format: <rte>/<rtept> or <trk>/<trkpt>), ensures good
point density via interpolation, and writes timestamps at a target speed.

Usage:
    python3 scripts/gpx-emulator-sim.py INPUT.gpx [OPTIONS]

Options:
    --speed MPH       Target speed in mph (default: 35)
    --spacing METERS  Max distance between points (default: 10)
    --output FILE     Output file (default: INPUT-sim.gpx)
    --stats           Print route analysis without generating output

Examples:
    # Basic: 35 mph, 10m spacing
    python3 scripts/gpx-emulator-sim.py testdata/smoky-mountain-loop.gpx

    # Custom speed
    python3 scripts/gpx-emulator-sim.py testdata/smoky-mountain-loop.gpx --speed 45

    # Just analyze point density
    python3 scripts/gpx-emulator-sim.py testdata/smoky-mountain-loop.gpx --stats
"""

import argparse
import math
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from pathlib import Path

# ── Constants ──

EARTH_RADIUS_M = 6_371_000
GPX_NS = "http://www.topografix.com/GPX/1/1"
NS = {"": GPX_NS}
MPH_TO_MPS = 0.44704


# ── Geo math ──

def haversine(lat1, lon1, lat2, lon2):
    """Distance in meters between two lat/lon points."""
    rlat1, rlon1 = math.radians(lat1), math.radians(lon1)
    rlat2, rlon2 = math.radians(lat2), math.radians(lon2)
    dlat = rlat2 - rlat1
    dlon = rlon2 - rlon1
    a = math.sin(dlat / 2) ** 2 + math.cos(rlat1) * math.cos(rlat2) * math.sin(dlon / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def interpolate_point(lat1, lon1, ele1, lat2, lon2, ele2, fraction):
    """Linearly interpolate between two points at given fraction (0.0 to 1.0)."""
    lat = lat1 + (lat2 - lat1) * fraction
    lon = lon1 + (lon2 - lon1) * fraction
    ele = ele1 + (ele2 - ele1) * fraction if ele1 is not None and ele2 is not None else ele1
    return lat, lon, ele


# ── GPX parsing ──

def parse_gpx(filepath):
    """Parse any GPX file and return list of (lat, lon, ele) tuples."""
    ET.register_namespace("", GPX_NS)
    tree = ET.parse(filepath)
    root = tree.getroot()

    points = []

    # Try <trk>/<trkseg>/<trkpt> first
    for trkpt in root.iter(f"{{{GPX_NS}}}trkpt"):
        lat = float(trkpt.get("lat"))
        lon = float(trkpt.get("lon"))
        ele_el = trkpt.find(f"{{{GPX_NS}}}ele")
        ele = float(ele_el.text) if ele_el is not None else None
        points.append((lat, lon, ele))

    # If no track points, try <rte>/<rtept>
    if not points:
        for rtept in root.iter(f"{{{GPX_NS}}}rtept"):
            lat = float(rtept.get("lat"))
            lon = float(rtept.get("lon"))
            ele_el = rtept.find(f"{{{GPX_NS}}}ele")
            ele = float(ele_el.text) if ele_el is not None else None
            points.append((lat, lon, ele))

    return points


# ── Point density analysis ──

def analyze_density(points):
    """Analyze spacing between consecutive points."""
    if len(points) < 2:
        return {}

    distances = []
    for i in range(1, len(points)):
        d = haversine(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1])
        distances.append(d)

    total_dist = sum(distances)

    return {
        "num_points": len(points),
        "total_distance_m": total_dist,
        "total_distance_km": total_dist / 1000,
        "total_distance_mi": total_dist / 1609.34,
        "avg_spacing_m": total_dist / len(distances),
        "min_spacing_m": min(distances),
        "max_spacing_m": max(distances),
        "median_spacing_m": sorted(distances)[len(distances) // 2],
        "points_under_5m": sum(1 for d in distances if d < 5),
        "points_5_to_15m": sum(1 for d in distances if 5 <= d < 15),
        "points_15_to_50m": sum(1 for d in distances if 15 <= d < 50),
        "points_50_to_100m": sum(1 for d in distances if 50 <= d < 100),
        "points_over_100m": sum(1 for d in distances if d >= 100),
        "distances": distances,
    }


def print_stats(filepath, points, stats):
    """Print a density analysis report."""
    print(f"\n{'=' * 60}")
    print(f"  GPX Analysis: {Path(filepath).name}")
    print(f"{'=' * 60}")
    print(f"  Points:          {stats['num_points']:,}")
    print(f"  Total distance:  {stats['total_distance_km']:.2f} km ({stats['total_distance_mi']:.2f} mi)")
    print(f"")
    print(f"  Point spacing:")
    print(f"    Average:  {stats['avg_spacing_m']:.1f} m")
    print(f"    Median:   {stats['median_spacing_m']:.1f} m")
    print(f"    Min:      {stats['min_spacing_m']:.2f} m")
    print(f"    Max:      {stats['max_spacing_m']:.1f} m")
    print(f"")
    print(f"  Spacing distribution:")
    print(f"    < 5m:       {stats['points_under_5m']:,} segments")
    print(f"    5-15m:      {stats['points_5_to_15m']:,} segments")
    print(f"    15-50m:     {stats['points_15_to_50m']:,} segments")
    print(f"    50-100m:    {stats['points_50_to_100m']:,} segments")
    print(f"    > 100m:     {stats['points_over_100m']:,} segments")
    print(f"")

    # Time estimate at 35 mph
    speed_mps = 35 * MPH_TO_MPS
    duration_s = stats["total_distance_m"] / speed_mps
    duration_min = duration_s / 60
    print(f"  At 35 mph:   {duration_min:.1f} min drive time")
    print(f"{'=' * 60}\n")


# ── Densification ──

def densify(points, max_spacing_m):
    """Interpolate between points so no gap exceeds max_spacing_m."""
    if len(points) < 2:
        return list(points)

    dense = [points[0]]

    for i in range(1, len(points)):
        p1 = points[i - 1]
        p2 = points[i]
        dist = haversine(p1[0], p1[1], p2[0], p2[1])

        if dist > max_spacing_m:
            # Insert intermediate points
            num_segments = math.ceil(dist / max_spacing_m)
            for j in range(1, num_segments):
                frac = j / num_segments
                lat, lon, ele = interpolate_point(
                    p1[0], p1[1], p1[2],
                    p2[0], p2[1], p2[2],
                    frac,
                )
                dense.append((lat, lon, ele))

        dense.append(p2)

    return dense


# ── Simplification (remove near-duplicate points) ──

def simplify(points, min_spacing_m=1.0):
    """Remove points that are less than min_spacing_m apart (keeps first and last)."""
    if len(points) < 3:
        return list(points)

    result = [points[0]]

    for i in range(1, len(points) - 1):
        dist = haversine(result[-1][0], result[-1][1], points[i][0], points[i][1])
        if dist >= min_spacing_m:
            result.append(points[i])

    result.append(points[-1])
    return result


# ── Timestamp generation ──

def add_timestamps(points, speed_mps, start_time=None):
    """Add timestamps to points based on constant speed.

    Guarantees every point gets a unique whole-second timestamp (Android emulator
    doesn't reliably parse fractional seconds). If the speed-based interval is
    less than 1 second, the timestamp is bumped to the next second.

    Returns list of (lat, lon, ele, time_str).
    """
    if start_time is None:
        start_time = datetime(2025, 1, 1, 12, 0, 0, tzinfo=timezone.utc)

    result = []
    cumulative_time = 0.0
    last_second = -1  # track the last whole second we emitted

    for i, (lat, lon, ele) in enumerate(points):
        if i > 0:
            dist = haversine(points[i - 1][0], points[i - 1][1], lat, lon)
            cumulative_time += dist / speed_mps

        # Round to whole second, but ensure it's strictly after the previous point
        current_second = int(cumulative_time)
        if current_second <= last_second:
            current_second = last_second + 1

        last_second = current_second
        t = start_time + timedelta(seconds=current_second)
        time_str = t.strftime("%Y-%m-%dT%H:%M:%S") + "Z"
        result.append((lat, lon, ele, time_str))

    return result


# ── GPX writing ──

def write_gpx(points_with_time, output_path, name="Emulator Simulation"):
    """Write a clean GPX track file for emulator playback."""
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="CurveCall-Sim">',
        '  <trk>',
        f'    <name>{name}</name>',
        '    <trkseg>',
    ]

    for lat, lon, ele, time_str in points_with_time:
        lines.append(f'      <trkpt lat="{lat:.7f}" lon="{lon:.7f}">')
        if ele is not None:
            lines.append(f'        <ele>{ele:.1f}</ele>')
        lines.append(f'        <time>{time_str}</time>')
        lines.append(f'      </trkpt>')

    lines.append('    </trkseg>')
    lines.append('  </trk>')
    lines.append('</gpx>')
    lines.append('')

    with open(output_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


# ── Main ──

def main():
    parser = argparse.ArgumentParser(
        description="Generate clean GPX for Android emulator GPS simulation",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("input", help="Input GPX file")
    parser.add_argument("--speed", type=float, default=35, help="Target speed in mph (default: 35)")
    parser.add_argument("--spacing", type=float, default=0, help="Max distance between points in meters (default: auto from speed, ~1 point/sec)")
    parser.add_argument("--output", "-o", help="Output file (default: INPUT-sim.gpx)")
    parser.add_argument("--stats", action="store_true", help="Only print density analysis, don't generate output")
    args = parser.parse_args()

    input_path = Path(args.input)
    if not input_path.exists():
        print(f"Error: {input_path} not found", file=sys.stderr)
        sys.exit(1)

    speed_mps = args.speed * MPH_TO_MPS

    # Auto-compute spacing if not specified: target 1 point per second
    # (matches real GPS 1 Hz update rate, guarantees unique whole-second timestamps)
    if args.spacing <= 0:
        max_spacing = speed_mps  # distance traveled in 1 second
    else:
        max_spacing = args.spacing

    # Parse
    print(f"Parsing {input_path.name}...")
    points = parse_gpx(str(input_path))
    if len(points) < 2:
        print(f"Error: only {len(points)} points found", file=sys.stderr)
        sys.exit(1)

    # Analyze original
    orig_stats = analyze_density(points)
    print_stats(input_path, points, orig_stats)

    if args.stats:
        return

    # Step 1: Remove near-duplicate points (< 1m apart)
    before = len(points)
    points = simplify(points, min_spacing_m=1.0)
    removed = before - len(points)
    if removed > 0:
        print(f"Removed {removed:,} near-duplicate points (< 1m apart)")

    # Step 2: Densify — interpolate where gaps exceed max spacing
    before = len(points)
    points = densify(points, max_spacing_m=max_spacing)
    added = len(points) - before
    if added > 0:
        print(f"Interpolated {added:,} points (max spacing: {max_spacing:.1f}m)")

    # Step 3: Add timestamps at target speed, enforcing unique seconds
    points_with_time = add_timestamps(points, speed_mps)

    # Analyze result
    result_stats = analyze_density(points)
    duration_s = result_stats["total_distance_m"] / speed_mps
    duration_min = duration_s / 60

    # Write output
    if args.output:
        output_path = Path(args.output)
    else:
        output_path = input_path.parent / f"{input_path.stem}-sim.gpx"

    route_name = f"{input_path.stem} ({args.speed:.0f}mph sim)"
    write_gpx(points_with_time, output_path, name=route_name)

    # Summary
    print(f"\n{'─' * 60}")
    print(f"  Output: {output_path}")
    print(f"  Points: {orig_stats['num_points']:,} → {len(points):,}")
    print(f"  Distance: {result_stats['total_distance_km']:.2f} km ({result_stats['total_distance_mi']:.2f} mi)")
    print(f"  Speed: {args.speed} mph ({speed_mps:.1f} m/s)")
    print(f"  Max spacing: {max_spacing:.1f}m")
    print(f"  Avg spacing: {result_stats['avg_spacing_m']:.1f}m")
    print(f"  Duration: {duration_min:.1f} min")
    print(f"  Avg interval: {result_stats['avg_spacing_m'] / speed_mps:.2f}s between points")
    print(f"{'─' * 60}\n")


if __name__ == "__main__":
    main()
