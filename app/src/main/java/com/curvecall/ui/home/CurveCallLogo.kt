package com.curvecall.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.curvecall.ui.theme.CurveCallPrimary
import com.curvecall.ui.theme.CurveCallPrimaryVariant

/**
 * Custom CurveCall logo — a stylized S-curve road mark.
 *
 * Draws two parallel S-curves (road edges) with a dashed center line,
 * all rendered in the brand green with a subtle outer glow.
 * The glow pulses gently to suggest motion / headlights on a night road.
 */
@Composable
fun CurveCallLogo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    animate: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "logo_glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    // Draw-in animation
    val drawProgress = remember { Animatable(0f) }
    LaunchedEffect(animate) {
        if (animate) {
            drawProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 900, easing = LinearEasing)
            )
        } else {
            drawProgress.snapTo(1f)
        }
    }

    val currentGlow = if (animate) glowAlpha else 0.35f

    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        // The logo is an S-curve — a smooth winding road viewed from above.
        // Two parallel paths form the road edges, with a center dashed line.

        val roadWidth = w * 0.22f
        val halfRoad = roadWidth / 2f

        // Center spine of the S-curve
        val centerPath = createSCurvePath(w, h)

        // Outer glow (large blurred stroke behind the road)
        drawPath(
            path = centerPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    CurveCallPrimary.copy(alpha = currentGlow * 0.3f),
                    CurveCallPrimary.copy(alpha = currentGlow),
                    CurveCallPrimary.copy(alpha = currentGlow * 0.3f),
                )
            ),
            style = Stroke(
                width = roadWidth * 2.8f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Secondary glow layer (tighter)
        drawPath(
            path = centerPath,
            color = CurveCallPrimary.copy(alpha = currentGlow * 0.6f),
            style = Stroke(
                width = roadWidth * 1.6f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Road surface (dark fill between edges)
        drawPath(
            path = centerPath,
            color = Color(0xFF0A1F0A),
            style = Stroke(
                width = roadWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Left road edge
        drawPath(
            path = createOffsetSCurvePath(w, h, -halfRoad),
            brush = Brush.verticalGradient(
                colors = listOf(
                    CurveCallPrimaryVariant.copy(alpha = 0.7f),
                    CurveCallPrimary,
                    CurveCallPrimaryVariant.copy(alpha = 0.7f),
                )
            ),
            style = Stroke(
                width = 2.5f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Right road edge
        drawPath(
            path = createOffsetSCurvePath(w, h, halfRoad),
            brush = Brush.verticalGradient(
                colors = listOf(
                    CurveCallPrimaryVariant.copy(alpha = 0.7f),
                    CurveCallPrimary,
                    CurveCallPrimaryVariant.copy(alpha = 0.7f),
                )
            ),
            style = Stroke(
                width = 2.5f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Center dashed line
        drawCenterDashes(w, h, CurveCallPrimary.copy(alpha = 0.5f))
    }
}

/**
 * Creates the center spine S-curve path.
 * The curve goes: top-center → bends right → bends left → bottom-center
 */
private fun DrawScope.createSCurvePath(w: Float, h: Float): Path {
    val path = Path()
    val xMid = w / 2f
    val curvature = w * 0.35f

    path.moveTo(xMid, h * 0.08f)

    // Upper curve bends right
    path.cubicTo(
        xMid + curvature, h * 0.25f,
        xMid + curvature, h * 0.38f,
        xMid, h * 0.50f
    )

    // Lower curve bends left
    path.cubicTo(
        xMid - curvature, h * 0.62f,
        xMid - curvature, h * 0.75f,
        xMid, h * 0.92f
    )

    return path
}

/**
 * Creates a parallel offset S-curve for road edges.
 * Approximates a perpendicular offset from the center path.
 */
private fun DrawScope.createOffsetSCurvePath(w: Float, h: Float, offset: Float): Path {
    val path = Path()
    val xMid = w / 2f
    val curvature = w * 0.35f

    // Approximate perpendicular offset by shifting x at straight segments
    // and adjusting at curve apexes
    path.moveTo(xMid + offset, h * 0.08f)

    path.cubicTo(
        xMid + curvature + offset * 0.6f, h * 0.25f,
        xMid + curvature + offset * 0.6f, h * 0.38f,
        xMid + offset, h * 0.50f
    )

    path.cubicTo(
        xMid - curvature + offset * 0.6f, h * 0.62f,
        xMid - curvature + offset * 0.6f, h * 0.75f,
        xMid + offset, h * 0.92f
    )

    return path
}

/**
 * Draw dashed center line marks along the S-curve.
 */
private fun DrawScope.drawCenterDashes(w: Float, h: Float, color: Color) {
    val xMid = w / 2f
    val dashLength = h * 0.035f
    val gapLength = h * 0.045f
    val totalSegments = 8

    // Sample points along the S-curve and draw short line segments
    val points = mutableListOf<Offset>()
    val steps = totalSegments * 4
    val curvature = w * 0.35f

    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val y = h * 0.08f + t * (h * 0.84f)

        // Compute x position along the S-curve at this y
        val x = if (t <= 0.5f) {
            // Upper half: bends right
            val localT = t / 0.5f
            val cx = xMid + curvature * 4 * localT * (1 - localT) * (1 - localT + localT * 0.5f)
            // Simplified approximation
            xMid + curvature * kotlin.math.sin(localT * Math.PI.toFloat()) * 0.5f
        } else {
            // Lower half: bends left
            val localT = (t - 0.5f) / 0.5f
            xMid - curvature * kotlin.math.sin(localT * Math.PI.toFloat()) * 0.5f
        }

        points.add(Offset(x, y))
    }

    // Draw every other pair of points as a dash
    var dashOn = true
    var accumulated = 0f
    val threshold = if (dashOn) dashLength else gapLength

    for (i in 1 until points.size) {
        val dx = points[i].x - points[i - 1].x
        val dy = points[i].y - points[i - 1].y
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        accumulated += dist

        if (dashOn && accumulated <= dashLength) {
            drawLine(
                color = color,
                start = points[i - 1],
                end = points[i],
                strokeWidth = 1.8f,
                cap = StrokeCap.Round
            )
        }

        if (accumulated >= if (dashOn) dashLength else gapLength) {
            accumulated = 0f
            dashOn = !dashOn
        }
    }
}
