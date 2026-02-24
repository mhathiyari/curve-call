package com.curvecall.narration

import com.curvecall.engine.types.CurveSegment
import com.curvecall.engine.types.Severity

/**
 * Detects transitions between road sections with different characteristics --
 * severity jumps (sudden increase/decrease) and density changes (straight-to-winding,
 * winding-to-straight).
 */
class TransitionDetector {

    enum class TransitionType {
        /** Severity increases by 2+ levels between adjacent curve groups. */
        SEVERITY_INCREASE,
        /** Severity decreases by 2+ levels between adjacent curve groups. */
        SEVERITY_DECREASE,
        /** Long straight (>500m) followed by curves. */
        STRAIGHT_TO_WINDING,
        /** Curves end and a long straight (>500m) follows. */
        WINDING_TO_STRAIGHT
    }

    /**
     * A detected transition point on the route.
     *
     * @property type The kind of transition.
     * @property distanceFromStart Distance from route start where the transition occurs (m).
     * @property fromSeverity Severity of the section before the transition (null for density changes).
     * @property toSeverity Severity of the section after the transition (null for density changes).
     */
    data class Transition(
        val type: TransitionType,
        val distanceFromStart: Double,
        val fromSeverity: Severity? = null,
        val toSeverity: Severity? = null
    )

    /**
     * Detect severity transitions between curve groups.
     *
     * Groups consecutive curves (gap < [groupGapThreshold]) and compares the average
     * severity of adjacent groups. A transition is detected when the severity difference
     * is >= [minSeverityJump] levels.
     *
     * @param curves All curves on the route, ordered by distanceFromStart.
     * @param groupGapThreshold Maximum gap (m) between curves to be in the same group (default 200m).
     * @param minSeverityJump Minimum severity level difference to trigger a transition (default 2).
     * @return List of detected severity transitions.
     */
    fun detectSeverityTransitions(
        curves: List<CurveSegment>,
        groupGapThreshold: Double = GROUP_GAP_THRESHOLD,
        minSeverityJump: Int = MIN_SEVERITY_JUMP
    ): List<Transition> {
        if (curves.size < 2) return emptyList()

        // Group curves by proximity
        val groups = groupCurves(curves, groupGapThreshold)
        if (groups.size < 2) return emptyList()

        val transitions = mutableListOf<Transition>()
        for (i in 0 until groups.size - 1) {
            val currentGroup = groups[i]
            val nextGroup = groups[i + 1]

            val currentAvgSeverity = averageSeverity(currentGroup)
            val nextAvgSeverity = averageSeverity(nextGroup)

            val diff = nextAvgSeverity.ordinal - currentAvgSeverity.ordinal
            if (diff >= minSeverityJump) {
                transitions.add(
                    Transition(
                        type = TransitionType.SEVERITY_INCREASE,
                        distanceFromStart = nextGroup.first().distanceFromStart,
                        fromSeverity = currentAvgSeverity,
                        toSeverity = nextAvgSeverity
                    )
                )
            } else if (diff <= -minSeverityJump) {
                transitions.add(
                    Transition(
                        type = TransitionType.SEVERITY_DECREASE,
                        distanceFromStart = nextGroup.first().distanceFromStart,
                        fromSeverity = currentAvgSeverity,
                        toSeverity = nextAvgSeverity
                    )
                )
            }
        }

        return transitions
    }

    /**
     * Detect density transitions (straight -> winding, winding -> straight).
     *
     * A STRAIGHT_TO_WINDING transition is detected when there's a gap > [straightThreshold]
     * between any curve and the next curve. A WINDING_TO_STRAIGHT is detected when
     * the gap after a curve exceeds [straightThreshold] and there are subsequent curves.
     *
     * Also detects at route boundaries: if the first curve starts > straightThreshold
     * from the route start, and if the last curve ends > straightThreshold from route end.
     *
     * @param curves All curves on the route.
     * @param straightThreshold Minimum straight distance to trigger density transition (default 500m).
     * @param routeLength Total route length in meters (optional, for end-of-route detection).
     * @return List of detected density transitions.
     */
    fun detectDensityTransitions(
        curves: List<CurveSegment>,
        straightThreshold: Double = STRAIGHT_THRESHOLD,
        routeLength: Double? = null
    ): List<Transition> {
        if (curves.isEmpty()) return emptyList()

        val transitions = mutableListOf<Transition>()

        // Check start of route
        if (curves.first().distanceFromStart > straightThreshold) {
            transitions.add(
                Transition(
                    type = TransitionType.STRAIGHT_TO_WINDING,
                    distanceFromStart = curves.first().distanceFromStart
                )
            )
        }

        // Check gaps between consecutive curves
        for (i in 0 until curves.size - 1) {
            val currentEnd = curves[i].distanceFromStart + curves[i].arcLength
            val nextStart = curves[i + 1].distanceFromStart
            val gap = nextStart - currentEnd

            if (gap > straightThreshold) {
                // Winding -> straight after curve i
                transitions.add(
                    Transition(
                        type = TransitionType.WINDING_TO_STRAIGHT,
                        distanceFromStart = currentEnd
                    )
                )
                // Straight -> winding before curve i+1
                transitions.add(
                    Transition(
                        type = TransitionType.STRAIGHT_TO_WINDING,
                        distanceFromStart = curves[i + 1].distanceFromStart
                    )
                )
            }
        }

        // Check end of route
        if (routeLength != null) {
            val lastEnd = curves.last().distanceFromStart + curves.last().arcLength
            if (routeLength - lastEnd > straightThreshold) {
                transitions.add(
                    Transition(
                        type = TransitionType.WINDING_TO_STRAIGHT,
                        distanceFromStart = lastEnd
                    )
                )
            }
        }

        return transitions
    }

    /**
     * Detect all transitions (both severity and density).
     */
    fun detectAll(
        curves: List<CurveSegment>,
        routeLength: Double? = null
    ): List<Transition> {
        val severity = detectSeverityTransitions(curves)
        val density = detectDensityTransitions(curves, routeLength = routeLength)
        return (severity + density).sortedBy { it.distanceFromStart }
    }

    // ---- Private helpers ----

    private fun groupCurves(curves: List<CurveSegment>, gapThreshold: Double): List<List<CurveSegment>> {
        val groups = mutableListOf<MutableList<CurveSegment>>()
        var currentGroup = mutableListOf(curves.first())

        for (i in 1 until curves.size) {
            val gap = curves[i].distanceFromStart -
                (curves[i - 1].distanceFromStart + curves[i - 1].arcLength)
            if (gap <= gapThreshold) {
                currentGroup.add(curves[i])
            } else {
                groups.add(currentGroup)
                currentGroup = mutableListOf(curves[i])
            }
        }
        groups.add(currentGroup)
        return groups
    }

    private fun averageSeverity(curves: List<CurveSegment>): Severity {
        val avgOrdinal = curves.map { it.severity.ordinal }.average().toInt()
        return Severity.entries[avgOrdinal.coerceIn(0, Severity.entries.size - 1)]
    }

    companion object {
        const val GROUP_GAP_THRESHOLD = 200.0
        const val MIN_SEVERITY_JUMP = 2
        const val STRAIGHT_THRESHOLD = 500.0
    }
}
