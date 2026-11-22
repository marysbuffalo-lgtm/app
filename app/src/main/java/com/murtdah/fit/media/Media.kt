package com.murtdah.fit.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.murtdah.fit.domain.Cue
import com.murtdah.fit.domain.Library
import org.json.JSONObject
import java.util.Locale

data class MediaEntry(val id: String, val gif: String, val altGif: String?, val formCue: String?, val muscles: String?)

class MediaCatalog(private val context: Context) {
    private val gifFiles: Set<String> = runCatching { context.assets.list("gifs")?.toSet() }.getOrNull().orEmpty()
    val sfxFiles: Set<String> = runCatching { context.assets.list("sfx")?.toSet() }.getOrNull().orEmpty()

    val entries: Map<String, MediaEntry> = runCatching {
        val json = JSONObject(context.assets.open("exercise_media.json").bufferedReader().use { it.readText() })
        json.keys().asSequence().associateWith { id ->
            val v = json.get(id)
            if (v is JSONObject) {
                MediaEntry(id, v.optString("gif", "$id.gif"), v.optString("alt_gif").ifBlank { null }, v.optString("form_cue").ifBlank { null }, v.optString("muscles").ifBlank { null })
            } else MediaEntry(id, v.toString(), null, null, null)
        }
    }.getOrDefault(emptyMap())

    fun fileFor(id: String): String? {
        val entry = entries[id]
        val candidates = listOfNotNull(entry?.gif, "$id.gif", "$id.webp", entry?.altGif)
        return candidates.firstOrNull { it in gifFiles && !it.endsWith(".mp4") }
    }

    fun assetUri(id: String): String? = fileFor(id)?.let { "file:///android_asset/gifs/$it" }

    fun usesAlternate(id: String): Boolean {
        val f = fileFor(id) ?: return false
        return f == entries[id]?.altGif && f != "$id.gif"
    }

    data class Check(val found: List<String>, val missing: List<String>, val soundsFound: List<String>, val soundsMissing: List<String>)

    fun check(): Check {
        val ids = Library.all.keys.sorted()
        val found = ids.filter { fileFor(it) != null }
        val sounds = SoundPlayer.FILES.values.toList()
        return Check(found, ids - found.toSet(), sounds.filter { it in sfxFiles }, sounds.filter { it !in sfxFiles })
    }
}

class SoundPlayer(private val context: Context) {
    companion object {
        val FILES = mapOf(
            Cue.START to "start.mp3", Cue.SET_DONE to "set_done.mp3", Cue.REST_START to "rest_start.mp3",
            Cue.TICK to "tick.mp3", Cue.GO to "go.mp3", Cue.NEXT to "next.mp3", Cue.HALFWAY to "halfway.mp3",
            Cue.COMPLETE to "complete.mp3", Cue.PB to "pb.mp3",
        )
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build()
    private val loaded = mutableMapOf<Cue, Int>()
    private var tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
    private val handler = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private val release = Runnable { abandonFocus() }

    init {
        val available = runCatching { context.assets.list("sfx")?.toSet() }.getOrNull().orEmpty()
        FILES.forEach { (cue, file) ->
            if (file in available) runCatching {
                context.assets.openFd("sfx/$file").use { loaded[cue] = pool.load(it, 1) }
            }
        }
    }

    fun play(cue: Cue) {
        requestFocus()
        val id = loaded[cue]
        if (id != null) {
            pool.play(id, 1f, 1f, 1, 0, 1f)
        } else {
            val (type, ms) = when (cue) {
                Cue.START -> ToneGenerator.TONE_PROP_ACK to 300
                Cue.SET_DONE -> ToneGenerator.TONE_PROP_BEEP to 150
                Cue.REST_START -> ToneGenerator.TONE_PROP_PROMPT to 200
                Cue.TICK -> ToneGenerator.TONE_PROP_BEEP to 80
                Cue.GO -> ToneGenerator.TONE_PROP_BEEP2 to 300
                Cue.NEXT -> ToneGenerator.TONE_PROP_ACK to 150
                Cue.HALFWAY -> ToneGenerator.TONE_CDMA_CONFIRM to 400
                Cue.COMPLETE -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD to 700
                Cue.PB -> ToneGenerator.TONE_CDMA_HIGH_PBX_SS to 600
            }
            runCatching { tone?.startTone(type, ms) }
        }
        handler.removeCallbacks(release)
        handler.postDelayed(release, 1_200)
    }

    fun softTick() {
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 30) }
    }

    private fun requestFocus() {
        if (focus != null) return
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
            audio.requestAudioFocus(req)
            focus = req
        }
    }

    private fun abandonFocus() {
        focus?.let { if (Build.VERSION.SDK_INT >= 26) audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    fun inCall(): Boolean = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_RINGTONE ||
        audio.mode == AudioManager.MODE_IN_COMMUNICATION

    fun release() {
        abandonFocus()
        pool.release()
        tone?.release()
        tone = null
    }
}

class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun tap(strong: Boolean = false) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(if (strong) 120 else 35, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

class Voice(context: Context) : TextToSpeech.OnInitListener {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.UK
            ready = true
        }
    }

    fun say(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue")
    }

    fun release() {
        tts.stop()
        tts.shutdown()
    }
}
