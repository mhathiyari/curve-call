package com.curvecall.data.gpx

import com.curvecall.engine.types.LatLon
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * Parses GPX files to extract an ordered list of geographic coordinates.
 *
 * Supports two GPX formats:
 * 1. Track points (<trkpt>) - primary format from GPS recorders and route planners
 * 2. Route points (<rtept>) - fallback format used by some planning tools
 *
 * Track points take priority if both are present.
 * Handles malformed files gracefully, returning whatever valid points were found.
 */
class GpxParser {

    /**
     * Result of parsing a GPX file.
     *
     * @param points Ordered list of coordinates found in the file
     * @param routeName Optional name from the GPX metadata
     * @param pointCount Total number of points parsed
     * @param errors Any non-fatal parsing errors encountered
     */
    data class GpxResult(
        val points: List<LatLon>,
        val routeName: String?,
        val pointCount: Int,
        val errors: List<String>
    )

    /**
     * Parse a GPX file from an InputStream.
     *
     * @param inputStream The GPX file content
     * @return GpxResult with extracted points and metadata
     * @throws GpxParseException if the file is fundamentally unparseable
     */
    fun parse(inputStream: InputStream): GpxResult {
        val trackPoints = mutableListOf<LatLon>()
        val routePoints = mutableListOf<LatLon>()
        val errors = mutableListOf<String>()
        var routeName: String? = null
        var currentElement = ""
        var inTrackName = false

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(inputStream, null)

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentElement = parser.name

                        when (parser.name) {
                            "trkpt" -> {
                                val point = parsePointAttributes(parser, errors)
                                if (point != null) {
                                    trackPoints.add(point)
                                }
                            }
                            "rtept" -> {
                                val point = parsePointAttributes(parser, errors)
                                if (point != null) {
                                    routePoints.add(point)
                                }
                            }
                            "name" -> {
                                // Capture route/track name (first one found)
                                inTrackName = routeName == null
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inTrackName && currentElement == "name") {
                            routeName = parser.text?.trim()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "name") {
                            inTrackName = false
                        }
                        currentElement = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            if (trackPoints.isEmpty() && routePoints.isEmpty()) {
                throw GpxParseException("Failed to parse GPX file: ${e.message}", e)
            }
            errors.add("Partial parse error: ${e.message}")
        }

        // Prefer track points over route points
        val points = if (trackPoints.isNotEmpty()) trackPoints else routePoints

        if (points.isEmpty()) {
            throw GpxParseException(
                "No valid coordinates found in GPX file. " +
                "Expected <trkpt> or <rtept> elements with lat/lon attributes."
            )
        }

        return GpxResult(
            points = points,
            routeName = routeName,
            pointCount = points.size,
            errors = errors
        )
    }

    /**
     * Extract lat/lon attributes from a track point or route point element.
     */
    private fun parsePointAttributes(
        parser: XmlPullParser,
        errors: MutableList<String>
    ): LatLon? {
        val latStr = parser.getAttributeValue(null, "lat")
        val lonStr = parser.getAttributeValue(null, "lon")

        if (latStr == null || lonStr == null) {
            errors.add("Point missing lat/lon attributes at line ${parser.lineNumber}")
            return null
        }

        return try {
            val lat = latStr.toDouble()
            val lon = lonStr.toDouble()

            // Validate coordinate ranges
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
                errors.add("Point has invalid coordinates: lat=$lat, lon=$lon at line ${parser.lineNumber}")
                return null
            }

            LatLon(lat, lon)
        } catch (e: NumberFormatException) {
            errors.add("Invalid coordinate format: lat=$latStr, lon=$lonStr at line ${parser.lineNumber}")
            null
        }
    }
}

/**
 * Exception thrown when a GPX file cannot be parsed at all.
 */
class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
