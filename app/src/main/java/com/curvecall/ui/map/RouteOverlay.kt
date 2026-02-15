package com.curvecall.ui.map

import com.curvecall.engine.types.LatLon
import com.curvecall.engine.types.RouteSegment
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline

/**
 * Builds osmdroid Polyline overlays from analyzed route segments.
 * Each segment gets its own Polyline colored by severity (curves) or gray (straights).
 * Stroke widths are density-aware for consistent appearance across devices.
 */
object RouteOverlay {

    private const val CURVE_STROKE_DP = 6f
    private const val STRAIGHT_STROKE_DP = 3f

    /**
     * Build a list of Polyline overlays from route segments and interpolated points.
     *
     * @param segments The analyzed route segments (curves + straights).
     * @param interpolatedPoints The uniformly spaced points used for analysis.
     *        Segment startIndex/endIndex reference into this list.
     * @param density Screen density for converting dp to px. Default 1.0 for backwards compat.
     * @return List of configured Polyline overlays ready to add to a MapView.
     */
    fun buildPolylines(
        segments: List<RouteSegment>,
        interpolatedPoints: List<LatLon>,
        density: Float = 1f
    ): List<Polyline> {
        if (interpolatedPoints.isEmpty()) return emptyList()

        return segments.mapNotNull { segment ->
            val (startIdx, endIdx, color, strokeDp) = when (segment) {
                is RouteSegment.Curve -> SegmentInfo(
                    segment.data.startIndex,
                    segment.data.endIndex,
                    MapColors.forSeverity(segment.data.severity),
                    CURVE_STROKE_DP
                )
                is RouteSegment.Straight -> SegmentInfo(
                    segment.data.startIndex,
                    segment.data.endIndex,
                    MapColors.STRAIGHT,
                    STRAIGHT_STROKE_DP
                )
            }

            // Bounds check
            val safeStart = startIdx.coerceIn(0, interpolatedPoints.size - 1)
            val safeEnd = endIdx.coerceIn(safeStart, interpolatedPoints.size - 1)
            if (safeEnd <= safeStart) return@mapNotNull null

            val geoPoints = (safeStart..safeEnd).map { i ->
                val p = interpolatedPoints[i]
                GeoPoint(p.lat, p.lon)
            }

            if (geoPoints.size < 2) return@mapNotNull null

            Polyline().apply {
                setPoints(geoPoints)
                outlinePaint.color = color
                outlinePaint.strokeWidth = strokeDp * density
                outlinePaint.isAntiAlias = true
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
            }
        }
    }

    private data class SegmentInfo(
        val startIdx: Int,
        val endIdx: Int,
        val color: Int,
        val strokeDp: Float
    )
}
