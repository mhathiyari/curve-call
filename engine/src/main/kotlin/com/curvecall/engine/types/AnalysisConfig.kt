package com.curvecall.engine.types

/**
 * Configuration for the route analysis pipeline.
 * All thresholds and parameters are documented with their PRD-specified defaults.
 */
data class AnalysisConfig(
    /** Target spacing between interpolated points in meters. Default 10.0m. */
    val interpolationSpacing: Double = 10.0,

    /** Number of points for the rolling average smoothing window. Default 7. */
    val smoothingWindow: Int = 7,

    /** Radius below which a point is considered "in a curve" (meters). Default 500.0m. */
    val curvatureThresholdRadius: Double = 500.0,

    /** Straight gap below which adjacent curves are merged (meters). Default 50.0m. */
    val straightGapMerge: Double = 50.0,

    /** Severity classification thresholds. */
    val severityThresholds: SeverityThresholds = SeverityThresholds(),

    /** Lateral g-force limit for speed advisory calculation. Car=0.35, Motorcycle=0.25. */
    val lateralG: Double = 0.35,

    /** Whether to calculate motorcycle-specific metrics (lean angle). */
    val isMotorcycleMode: Boolean = false,

    /** Node spacing above which data quality is flagged as sparse (meters). Default 100.0m. */
    val sparseNodeThreshold: Double = 100.0
)
