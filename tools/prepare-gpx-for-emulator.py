#!/usr/bin/env python3
"""
Prepare a GPX file for Android Emulator playback.

Thins out points and adds realistic timestamps so the emulator
can play back the full route without stopping.
"""

import xml.etree.ElementTree as ET
import math
import sys
from datetime import datetime, timedelta

NS = "http://www.topografix.com/GPX/1/1"
ET.register_namespace("", NS)
ET.register_namespace("xsi", "http://www.w3.org/2001/XMLSchema-instance")

def haversine(lat1, lon1, lat2, lon2):
    """Distance in meters between two GPS points."""
    R = 6371000
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = (math.sin(dlat / 2) ** 2 +
         math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) *
         math.sin(dlon / 2) ** 2)
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))

def thin_and_timestamp(input_path, output_path, speed_mph=35, max_points=1000):
    tree = ET.parse(input_path)
    root = tree.getroot()

    # Collect all route points
    points = []
    for rtept in root.iter(f"{{{NS}}}rtept"):
        lat = float(rtept.get("lat"))
        lon = float(rtept.get("lon"))
        ele_el = rtept.find(f"{{{NS}}}ele")
        ele = float(ele_el.text) if ele_el is not None else None
        points.append((lat, lon, ele))

    if not points:
        # Try track points instead
        for trkpt in root.iter(f"{{{NS}}}trkpt"):
            lat = float(trkpt.get("lat"))
            lon = float(trkpt.get("lon"))
            ele_el = trkpt.find(f"{{{NS}}}ele")
            ele = float(ele_el.text) if ele_el is not None else None
            points.append((lat, lon, ele))

    print(f"Original points: {len(points)}")

    # Thin points: keep every Nth point
    if len(points) > max_points:
        step = len(points) // max_points
        points = points[::step]
    print(f"Thinned to: {len(points)}")

    # Build new GPX with track (trk) instead of route — emulator prefers tracks
    speed_mps = speed_mph * 0.44704  # mph to m/s
    start_time = datetime(2025, 7, 26, 12, 0, 0)

    gpx = ET.Element("gpx", {
        "xmlns": NS,
        "version": "1.1",
        "creator": "CurveCall-Tools",
    })

    trk = ET.SubElement(gpx, "trk")
    name = ET.SubElement(trk, "name")
    name.text = "Smoky Mountain Loop (Emulator)"
    trkseg = ET.SubElement(trk, "trkseg")

    current_time = start_time
    for i, (lat, lon, ele) in enumerate(points):
        trkpt = ET.SubElement(trkseg, "trkpt", lat=f"{lat:.6f}", lon=f"{lon:.6f}")
        if ele is not None:
            ele_el = ET.SubElement(trkpt, "ele")
            ele_el.text = f"{ele:.1f}"
        time_el = ET.SubElement(trkpt, "time")
        time_el.text = current_time.strftime("%Y-%m-%dT%H:%M:%SZ")

        # Calculate time to next point
        if i < len(points) - 1:
            dist = haversine(lat, lon, points[i + 1][0], points[i + 1][1])
            dt = max(dist / speed_mps, 1.0)  # at least 1 second between points
            current_time += timedelta(seconds=dt)

    tree = ET.ElementTree(gpx)
    ET.indent(tree, space="  ")
    tree.write(output_path, xml_declaration=True, encoding="UTF-8")

    total_minutes = (current_time - start_time).total_seconds() / 60
    print(f"Output: {output_path}")
    print(f"Simulated drive time: {total_minutes:.0f} minutes at {speed_mph} mph")

if __name__ == "__main__":
    input_file = sys.argv[1] if len(sys.argv) > 1 else "testdata/smoky-mountain-loop.gpx"
    output_file = sys.argv[2] if len(sys.argv) > 2 else "testdata/smoky-mountain-loop-emulator.gpx"
    speed = int(sys.argv[3]) if len(sys.argv) > 3 else 35
    max_pts = int(sys.argv[4]) if len(sys.argv) > 4 else 800

    thin_and_timestamp(input_file, output_file, speed_mph=speed, max_points=max_pts)
