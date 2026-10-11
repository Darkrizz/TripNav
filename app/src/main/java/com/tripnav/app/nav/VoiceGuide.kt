package com.tripnav.app.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.tripnav.app.settings.SettingsRepository
import java.util.Locale

/** Spoken guidance through Android TTS; ducks other audio (music, intercom) while speaking. */
class VoiceGuide(context: Context, private val settings: SettingsRepository) : Announcer {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var ready = false
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).build()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.getDefault()
            tts.setAudioAttributes(attrs)
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { audio.abandonAudioFocusRequest(focus) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { audio.abandonAudioFocusRequest(focus) }
        })
    }

    override fun announce(text: String, urgent: Boolean) {
        if (!ready || !settings.settings.value.voiceEnabled || text.isBlank()) return
        val spoken = if (settings.settings.value.voiceStreetNames) text else stripStreet(text)
        audio.requestAudioFocus(focus)
        tts.speak(spoken, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "tn-${System.nanoTime()}")
    }

    /** "Turn right onto Main Street." -> "Turn right." */
    private fun stripStreet(text: String) = text.replace(Regex("\\s+(onto|on|toward|towards|to)\\s+.*$", RegexOption.IGNORE_CASE), ".")

    fun shutdown() = tts.shutdown()
}
