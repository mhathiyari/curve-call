package com.curvecall.narration

/**
 * Learns actual TTS speaking rate from observed utterance durations and provides
 * calibrated duration estimates.
 *
 * The default model assumes 2.5 words/second + 0.3s startup. After collecting
 * [MIN_SAMPLES] observations, it recalibrates using a simple linear fit to
 * converge on the device's actual TTS behavior.
 *
 * Thread-safe: all public methods are synchronized.
 *
 * Usage:
 * ```
 * val calibrator = TtsDurationCalibrator()
 * // After each TTS utterance completes:
 * calibrator.recordSample(wordCount = 7, actualDurationSec = 3.1)
 * // When estimating future durations:
 * val estimate = calibrator.estimateDuration(wordCount = 5)
 * ```
 */
class TtsDurationCalibrator {

    companion object {
        /** Minimum samples before recalibration. */
        const val MIN_SAMPLES = 5

        /** Maximum samples retained (rolling window). */
        const val MAX_SAMPLES = 30

        /** Default words per second (matches TimingCalculator). */
        const val DEFAULT_WPS = 2.5

        /** Default TTS engine startup delay (matches TimingCalculator). */
        const val DEFAULT_STARTUP_SEC = 0.3

        /** Safety bounds for calibrated WPS to prevent runaway values. */
        const val MIN_WPS = 1.0
        const val MAX_WPS = 4.0

        /** Safety bounds for calibrated startup delay. */
        const val MIN_STARTUP_SEC = 0.0
        const val MAX_STARTUP_SEC = 1.5
    }

    private val samples = mutableListOf<Pair<Int, Double>>() // (wordCount, actualDurationSec)

    @Volatile
    private var calibratedWps: Double = DEFAULT_WPS

    @Volatile
    private var calibratedStartup: Double = DEFAULT_STARTUP_SEC

    @Volatile
    private var isCalibrated: Boolean = false

    /**
     * Record an observed TTS utterance duration.
     *
     * @param wordCount Number of words in the spoken text.
     * @param actualDurationSec Actual wall-clock duration from TTS start to done callback.
     */
    @Synchronized
    fun recordSample(wordCount: Int, actualDurationSec: Double) {
        if (wordCount <= 0 || actualDurationSec <= 0.0) return

        samples.add(wordCount to actualDurationSec)

        // Keep rolling window
        while (samples.size > MAX_SAMPLES) {
            samples.removeFirst()
        }

        if (samples.size >= MIN_SAMPLES) {
            recalibrate()
        }
    }

    /**
     * Estimate TTS duration for the given word count using calibrated parameters.
     *
     * Falls back to defaults if not yet calibrated.
     *
     * @param wordCount Number of words in the text to speak.
     * @return Estimated duration in seconds.
     */
    fun estimateDuration(wordCount: Int): Double {
        return wordCount / calibratedWps + calibratedStartup
    }

    /**
     * Whether the calibrator has enough data to provide calibrated estimates.
     */
    fun isCalibrated(): Boolean = isCalibrated

    /**
     * Current calibrated words-per-second rate.
     */
    fun currentWps(): Double = calibratedWps

    /**
     * Current calibrated startup delay.
     */
    fun currentStartup(): Double = calibratedStartup

    /**
     * Reset calibration to defaults (e.g., when speech rate changes).
     */
    @Synchronized
    fun reset() {
        samples.clear()
        calibratedWps = DEFAULT_WPS
        calibratedStartup = DEFAULT_STARTUP_SEC
        isCalibrated = false
    }

    /**
     * Simple linear regression: duration = wordCount / wps + startup.
     *
     * Fits `y = a + b*x` where y = duration, x = wordCount.
     * Then `startup = a`, `wps = 1/b`.
     */
    private fun recalibrate() {
        val n = samples.size.toDouble()
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumXX = 0.0

        for ((wordCount, duration) in samples) {
            val x = wordCount.toDouble()
            sumX += x
            sumY += duration
            sumXY += x * duration
            sumXX += x * x
        }

        val denominator = n * sumXX - sumX * sumX
        if (denominator < 1e-10) return // Can't fit (all same word count)

        val a = (sumY * sumXX - sumX * sumXY) / denominator // intercept (startup)
        val b = (n * sumXY - sumX * sumY) / denominator     // slope (sec/word)

        // wps = 1/b (words per second), startup = a
        if (b > 0.0) {
            calibratedWps = (1.0 / b).coerceIn(MIN_WPS, MAX_WPS)
            calibratedStartup = a.coerceIn(MIN_STARTUP_SEC, MAX_STARTUP_SEC)
            isCalibrated = true
        }
    }
}
