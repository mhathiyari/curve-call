package com.curvecall.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.curvecall.engine.types.Direction
import com.curvecall.engine.types.Severity
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates stereo-panned pre-cue tones before speech narrations.
 *
 * The tone's stereo pan indicates curve direction (left/right speaker),
 * and the pitch + duration indicate severity:
 *
 * | Severity | Frequency | Duration |
 * |----------|-----------|----------|
 * | GENTLE   | 600 Hz    | 200 ms   |
 * | MODERATE | 800 Hz    | 300 ms   |
 * | FIRM     | 1000 Hz   | 350 ms   |
 * | SHARP    | 1200 Hz   | 400 ms   |
 * | HAIRPIN  | 1400 Hz   | 500 ms   |
 *
 * Uses [AudioTrack] with programmatic sine wave generation — no external libraries.
 */
class SpatialTonePlayer {

    private val sampleRate = 44100

    /**
     * Tone parameters for a given severity.
     */
    data class ToneParams(
        val frequencyHz: Int,
        val durationMs: Int
    )

    /**
     * Get tone parameters for a severity level.
     */
    fun paramsForSeverity(severity: Severity): ToneParams {
        return when (severity) {
            Severity.GENTLE -> ToneParams(600, 200)
            Severity.MODERATE -> ToneParams(800, 300)
            Severity.FIRM -> ToneParams(1000, 350)
            Severity.SHARP -> ToneParams(1200, 400)
            Severity.HAIRPIN -> ToneParams(1400, 500)
        }
    }

    /**
     * Play a directional pre-cue tone.
     *
     * @param direction Curve direction — LEFT pans to left speaker, RIGHT to right speaker.
     * @param severity Curve severity — determines pitch and duration.
     * @param volume Volume from 0.0 to 1.0 (default 0.7).
     */
    fun playTone(direction: Direction, severity: Severity, volume: Float = 0.7f) {
        val params = paramsForSeverity(severity)
        val samples = generateStereoSineWave(
            frequencyHz = params.frequencyHz,
            durationMs = params.durationMs,
            direction = direction,
            volume = volume
        )

        val bufferSize = samples.size * 2 // 16-bit samples = 2 bytes each
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(samples, 0, samples.size)
        audioTrack.play()

        // Release after playback completes
        val durationMs = params.durationMs.toLong() + 50 // small buffer
        Thread {
            Thread.sleep(durationMs)
            try {
                audioTrack.stop()
                audioTrack.release()
            } catch (_: IllegalStateException) {
                // Already released
            }
        }.start()
    }

    /**
     * Generate a stereo sine wave as a ShortArray (interleaved L/R samples).
     *
     * For LEFT direction: full volume in left channel, reduced in right.
     * For RIGHT direction: full volume in right channel, reduced in left.
     *
     * Applies a simple fade-in/fade-out envelope (10ms each) to prevent clicks.
     */
    fun generateStereoSineWave(
        frequencyHz: Int,
        durationMs: Int,
        direction: Direction,
        volume: Float = 0.7f
    ): ShortArray {
        val numSamplesPerChannel = sampleRate * durationMs / 1000
        val totalSamples = numSamplesPerChannel * 2 // stereo interleaved
        val samples = ShortArray(totalSamples)

        // Pan: dominant channel gets full volume, other gets 20%
        val leftGain = if (direction == Direction.LEFT) volume else volume * 0.2f
        val rightGain = if (direction == Direction.RIGHT) volume else volume * 0.2f

        // Fade envelope: 10ms fade-in and fade-out
        val fadeSamples = sampleRate * 10 / 1000 // 10ms worth of samples

        for (i in 0 until numSamplesPerChannel) {
            val angle = 2.0 * PI * frequencyHz * i / sampleRate
            val rawSample = sin(angle)

            // Apply fade envelope
            val envelope = when {
                i < fadeSamples -> i.toFloat() / fadeSamples
                i > numSamplesPerChannel - fadeSamples -> (numSamplesPerChannel - i).toFloat() / fadeSamples
                else -> 1.0f
            }

            val leftSample = (rawSample * leftGain * envelope * Short.MAX_VALUE).toInt().toShort()
            val rightSample = (rawSample * rightGain * envelope * Short.MAX_VALUE).toInt().toShort()

            samples[i * 2] = leftSample       // Left channel
            samples[i * 2 + 1] = rightSample   // Right channel
        }

        return samples
    }

    /**
     * Release resources. Call when the session ends.
     */
    fun release() {
        // No persistent resources to release in current implementation
    }
}
