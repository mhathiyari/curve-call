package com.curvecall.narration

import com.curvecall.narration.types.NarrationEvent

/**
 * Text-to-speech engine abstraction for narration delivery.
 *
 * This interface defines the contract for TTS delivery. The Android implementation
 * (AndroidTtsEngine) wraps the Android TextToSpeech API with audio focus management.
 * A test implementation can be used for unit testing without Android dependencies.
 *
 * The TTS engine handles:
 * - Speaking narration text with priority-based interrupt/queue behavior
 * - Audio focus acquisition (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
 * - Configurable speech rate
 * - Completion callbacks
 *
 * Priority behavior:
 * - Higher priority interrupts lower priority in-progress speech
 * - Same or lower priority queues after current speech
 *
 * This interface is pure Kotlin. The Android implementation lives in the app module
 * or is provided via dependency injection.
 */
interface TtsEngine {

    /**
     * Listener for TTS events.
     */
    interface TtsListener {
        /** Called when a narration has finished speaking. */
        fun onSpeechComplete(event: NarrationEvent)

        /** Called when speech is interrupted by a higher-priority event. */
        fun onSpeechInterrupted(interruptedEvent: NarrationEvent, interruptingEvent: NarrationEvent)

        /** Called when an error occurs during speech. */
        fun onSpeechError(event: NarrationEvent, error: String)
    }

    /**
     * Set the TTS event listener.
     */
    fun setTtsListener(listener: TtsListener?)

    /**
     * Speak a narration event.
     *
     * If a narration of equal or lower priority is currently playing, this event
     * is queued. If a narration of higher priority needs to play, the current
     * narration is interrupted.
     *
     * @param event The narration event to speak.
     */
    fun speak(event: NarrationEvent)

    /**
     * Speak text with a given priority.
     *
     * Convenience method that wraps text in a temporary NarrationEvent.
     *
     * @param text The text to speak.
     * @param priority The priority level.
     */
    fun speak(text: String, priority: Int)

    /**
     * Interrupt the current speech and speak this event immediately.
     * Used for high-priority interrupts.
     *
     * @param event The interrupting narration event.
     */
    fun interrupt(event: NarrationEvent)

    /**
     * Stop all current and queued speech.
     */
    fun stop()

    /**
     * Set the speech rate.
     *
     * @param rate Speech rate multiplier. 1.0 is normal speed.
     *   Range: 0.5 (slow) to 2.0 (fast).
     */
    fun setSpeechRate(rate: Float)

    /**
     * Check if the engine is currently speaking.
     */
    fun isSpeaking(): Boolean

    /**
     * Initialize the TTS engine. Must be called before [speak].
     * This may be asynchronous on Android (TTS initialization callback).
     */
    fun initialize()

    /**
     * Release TTS resources. Call when the session ends.
     */
    fun shutdown()

    /**
     * Check if the engine is initialized and ready to speak.
     */
    fun isReady(): Boolean
}

/**
 * Android TextToSpeech wrapper implementation of [TtsEngine].
 *
 * This class wraps the Android TextToSpeech API and manages audio focus.
 * It requires an Android Context and is therefore only usable on Android.
 *
 * Audio focus strategy: AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
 * This causes other audio apps (music, podcasts, navigation) to duck (lower volume)
 * while CurveCall narrates, then restore volume when done.
 *
 * NOTE: This is a skeleton that references Android APIs by name for documentation
 * purposes. The actual implementation requires the Android SDK and will be compiled
 * in the app module or when this module is converted to an Android library module.
 * For now, the module remains pure JVM for testability, and this class serves as
 * the implementation specification.
 *
 * To use in the Android app:
 * 1. Create an AndroidTtsEngine class in the app module implementing TtsEngine
 * 2. Initialize with Android Context
 * 3. Inject via Hilt
 *
 * Implementation details:
 * ```kotlin
 * // In app module:
 * class AndroidTtsEngine(private val context: Context) : TtsEngine {
 *     private var tts: TextToSpeech? = null
 *     private var audioManager: AudioManager? = null
 *     private var focusRequest: AudioFocusRequest? = null
 *     private var currentEvent: NarrationEvent? = null
 *     private var listener: TtsEngine.TtsListener? = null
 *     private var speechRate: Float = 1.0f
 *     private var isInitialized = false
 *
 *     override fun initialize() {
 *         audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
 *         tts = TextToSpeech(context) { status ->
 *             if (status == TextToSpeech.SUCCESS) {
 *                 tts?.language = Locale.US
 *                 tts?.setSpeechRate(speechRate)
 *                 isInitialized = true
 *             }
 *         }
 *
 *         // Build audio focus request
 *         focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
 *             .setAudioAttributes(AudioAttributes.Builder()
 *                 .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
 *                 .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
 *                 .build())
 *             .setOnAudioFocusChangeListener { /* handle focus loss */ }
 *             .build()
 *     }
 *
 *     override fun speak(event: NarrationEvent) {
 *         if (!isInitialized) return
 *         requestAudioFocus()
 *         currentEvent = event
 *         tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
 *             override fun onDone(utteranceId: String?) {
 *                 abandonAudioFocus()
 *                 listener?.onSpeechComplete(event)
 *             }
 *             override fun onError(utteranceId: String?) {
 *                 abandonAudioFocus()
 *                 listener?.onSpeechError(event, "TTS error")
 *             }
 *             override fun onStart(utteranceId: String?) {}
 *         })
 *         tts?.speak(event.text, TextToSpeech.QUEUE_ADD, null, event.text.hashCode().toString())
 *     }
 *
 *     override fun interrupt(event: NarrationEvent) {
 *         val interrupted = currentEvent
 *         tts?.stop()
 *         if (interrupted != null) {
 *             listener?.onSpeechInterrupted(interrupted, event)
 *         }
 *         speak(event)
 *     }
 *
 *     private fun requestAudioFocus() {
 *         focusRequest?.let { audioManager?.requestAudioFocus(it) }
 *     }
 *
 *     private fun abandonAudioFocus() {
 *         focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
 *     }
 * }
 * ```
 */
class DefaultTtsEngine : TtsEngine {
    private var listener: TtsEngine.TtsListener? = null
    private var currentEvent: NarrationEvent? = null
    private var speechRate: Float = 1.0f
    private var speaking = false
    private var ready = false

    override fun setTtsListener(listener: TtsEngine.TtsListener?) {
        this.listener = listener
    }

    override fun speak(event: NarrationEvent) {
        if (!ready) return
        currentEvent = event
        speaking = true
        // In a real implementation, this delegates to Android TTS.
        // For testing/JVM usage, immediately mark as complete.
        speaking = false
        listener?.onSpeechComplete(event)
    }

    override fun speak(text: String, priority: Int) {
        speak(
            NarrationEvent(
                text = text,
                priority = priority,
                curveDistanceFromStart = 0.0,
                advisorySpeedMs = null,
                associatedCurve = null
            )
        )
    }

    override fun interrupt(event: NarrationEvent) {
        val interrupted = currentEvent
        speaking = false
        if (interrupted != null) {
            listener?.onSpeechInterrupted(interrupted, event)
        }
        speak(event)
    }

    override fun stop() {
        speaking = false
        currentEvent = null
    }

    override fun setSpeechRate(rate: Float) {
        require(rate in 0.5f..2.0f) { "Speech rate must be in [0.5, 2.0], got $rate" }
        this.speechRate = rate
    }

    override fun isSpeaking(): Boolean = speaking

    override fun initialize() {
        ready = true
    }

    override fun shutdown() {
        stop()
        ready = false
    }

    override fun isReady(): Boolean = ready
}
