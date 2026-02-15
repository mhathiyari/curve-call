package com.curvecall.ui.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Custom osmdroid Overlay that draws a GPS position marker:
 * - Semi-transparent accuracy circle (radius from GPS accuracy)
 * - Bright directional arrow/chevron rotated by bearing with drop shadow
 *
 * Update [position], [bearing], and [accuracy] on each GPS tick.
 */
class GpsMarkerOverlay : Overlay() {

    /** Current GPS position. Null = not drawn. */
    var position: GeoPoint? = null

    /** Current bearing in degrees (0 = north, clockwise). */
    var bearing: Float = 0f

    /** GPS accuracy in meters. */
    var accuracy: Float = 0f

    private val arrowSizeDp = 28f

    private val accuracyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MapColors.ACCURACY_FILL
    }

    private val accuracyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = MapColors.ACCURACY_STROKE
        strokeWidth = 2f
    }

    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MapColors.GPS_ARROW
        setShadowLayer(8f, 0f, 2f, 0x80000000.toInt())
    }

    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

        // Draw directional arrow
        val density = mapView.context.resources.displayMetrics.density
        val arrowSize = arrowSizeDp * density / 2f

        canvas.save()
        // Enable layer for shadow to render correctly
        canvas.saveLayerAlpha(
            x - arrowSize * 2, y - arrowSize * 2,
            x + arrowSize * 2, y + arrowSize * 2,
            255
        )
        canvas.translate(x, y)
        // Compensate for map rotation (heading-up mode)
        canvas.rotate(bearing + mapView.mapOrientation)

        val arrowPath = Path().apply {
            // Arrow pointing up (north = bearing 0)
            moveTo(0f, -arrowSize)           // tip
            lineTo(-arrowSize * 0.6f, arrowSize * 0.5f)  // bottom-left
            lineTo(0f, arrowSize * 0.2f)     // notch
            lineTo(arrowSize * 0.6f, arrowSize * 0.5f)   // bottom-right
            close()
        }

        canvas.drawPath(arrowPath, arrowFillPaint)
        canvas.drawPath(arrowPath, arrowStrokePaint)
        canvas.restore()
        canvas.restore()
    }
}
