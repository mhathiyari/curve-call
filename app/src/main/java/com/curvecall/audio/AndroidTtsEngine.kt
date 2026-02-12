package com.curvecall.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.curvecall.narration.TtsEngine
import com.curvecall.narration.types.NarrationEvent
import java.util.Locale

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
 * Speech priority behavior:
 * - Higher priority interrupts lower priority in-progress speech.
 * - Same or lower priority queues after current speech.
 */
class AndroidTtsEngine(private val context: Context) : TtsEngine {

    private var tts: TextToSpeech? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var currentEvent: NarrationEvent? = null
    private var listener: TtsEngine.TtsListener? = null
    private var speechRate: Float = 1.0f
    private var isInitialized = false

    override fun setTtsListener(listener: TtsEngine.TtsListener?) {
        this.listener = listener
    }

    override fun initialize() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(speechRate)
                setupUtteranceListener()
                isInitialized = true
            }
        }

        // Build audio focus request for AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        tts?.stop()
                    }
                }
            }
            .build()
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // Speech started
            }

            override fun onDone(utteranceId: String?) {
                abandonAudioFocus()
                val event = currentEvent
                currentEvent = null
                if (event != null) {
                    listener?.onSpeechComplete(event)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                abandonAudioFocus()
                val event = currentEvent
                currentEvent = null
                if (event != null) {
                    listener?.onSpeechError(event, "TTS error")
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                abandonAudioFocus()
                val event = currentEvent
                currentEvent = null
                if (event != null) {
                    listener?.onSpeechError(event, "TTS error code: $errorCode")
                }
            }
        })
    }

    override fun speak(event: NarrationEvent) {
        if (!isInitialized) return

        // If a higher-priority event is already playing, queue this one.
        // If this event has higher priority, interrupt the current one.
        val current = currentEvent
        if (current != null && event.priority > current.priority) {
            interrupt(event)
            return
        }

        requestAudioFocus()
        currentEvent = event

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, event.text.hashCode().toString())
        }
        tts?.speak(event.text, TextToSpeech.QUEUE_ADD, params, event.text.hashCode().toString())
    }

    override fun speak(text: String, priority: Int) {
        speak(
            NarrationEvent(
                text = text,
                priority = priority,
                triggerDistanceFromStart = 0.0,
                associatedCurve = null
            )
        )
    }

    override fun interrupt(event: NarrationEvent) {
        val interrupted = currentEvent
        tts?.stop()
        if (interrupted != null) {
            listener?.onSpeechInterrupted(interrupted, event)
        }
        requestAudioFocus()
        currentEvent = event

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, event.text.hashCode().toString())
        }
        tts?.speak(event.text, TextToSpeech.QUEUE_FLUSH, params, event.text.hashCode().toString())
    }

    override fun stop() {
        tts?.stop()
        currentEvent = null
        abandonAudioFocus()
    }

    override fun setSpeechRate(rate: Float) {
        require(rate in 0.5f..2.0f) { "Speech rate must be in [0.5, 2.0], got $rate" }
        this.speechRate = rate
        tts?.setSpeechRate(rate)
    }

    override fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    override fun isReady(): Boolean = isInitialized

    override fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    private fun requestAudioFocus() {
        focusRequest?.let { audioManager?.requestAudioFocus(it) }
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
    }
}
