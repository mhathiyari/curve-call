package com.curvecall.engine.types

/**
 * A geographic coordinate expressed as latitude and longitude in decimal degrees.
 * Uses WGS-84 datum (standard GPS).
 */
data class LatLon(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "Latitude must be in [-90, 90], got $lat" }
        require(lon in -180.0..180.0) { "Longitude must be in [-180, 180], got $lon" }
    }
}
