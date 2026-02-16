package com.curvecall.ui.map

import android.graphics.Canvas
import android.graphics.Paint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Custom osmdroid Overlay that draws a GPS position marker:
 * - Semi-transparent accuracy circle (radius from GPS accuracy)
 * - Bright filled dot at the current position with white border and drop shadow
 *
 * Update [position], [bearing], and [accuracy] on each GPS tick.
 */
class GpsMarkerOverlay : Overlay() {

    /** Current GPS position. Null = not drawn. */
    var position: GeoPoint? = null

    /** Current bearing in degrees (kept for API compat but unused by dot). */
    var bearing: Float = 0f

    /** GPS accuracy in meters. */
    var accuracy: Float = 0f

    private val dotRadiusDp = 8f

    private val accuracyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MapColors.ACCURACY_FILL
    }

    private val accuracyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = MapColors.ACCURACY_STROKE
        strokeWidth = 2f
    }

    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MapColors.GPS_ARROW
        setShadowLayer(6f, 0f, 2f, 0x80000000.toInt())
    }

    private val dotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = MapColors.GPS_ARROW_STROKE
        strokeWidth = 3f
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val pos = position ?: return

        val projection = mapView.projection
        val screenPoint = projection.toPixels(pos, null)
        val x = screenPoint.x.toFloat()
        val y = screenPoint.y.toFloat()

        // Draw accuracy circle
        if (accuracy > 0) {
            val metersPerPixel = projection.metersToPixels(1f)
            val radiusPx = accuracy * metersPerPixel
            if (radiusPx > 5f) {
                canvas.drawCircle(x, y, radiusPx, accuracyFillPaint)
                canvas.drawCircle(x, y, radiusPx, accuracyStrokePaint)
            }
        }

        // Draw position dot
        val density = mapView.context.resources.displayMetrics.density
        val radiusPx = dotRadiusDp * density

        canvas.save()
        canvas.saveLayerAlpha(
            x - radiusPx * 2, y - radiusPx * 2,
            x + radiusPx * 2, y + radiusPx * 2,
            255
        )
        canvas.drawCircle(x, y, radiusPx, dotFillPaint)
        canvas.drawCircle(x, y, radiusPx, dotStrokePaint)
        canvas.restore()
        canvas.restore()
    }
}
