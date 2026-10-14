package com.takenakadx.ankihandsfree.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** TextToSpeech を suspend 関数で扱えるようにしたラッパー。 */
class Speaker(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CancellableContinuation<Unit>>()
    private val ids = AtomicLong()

    suspend fun init(): Boolean {
        if (tts != null) return true
        return suspendCancellableCoroutine { cont ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    engine!!.setOnUtteranceProgressListener(listener)
                    tts = engine
                    cont.resume(true)
                } else {
                    engine?.shutdown()
                    cont.resume(false)
                }
            }
        }
    }

    fun isLanguageAvailable(locale: Locale): Boolean =
        (tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE

    /** 読み上げが終わるまで待つ。キャンセルされると読み上げも止める。 */
    suspend fun speak(text: String, locale: Locale, rate: Float = 1.0f) {
        val engine = tts ?: return
        if (text.isBlank()) return
        val id = "utt-${ids.incrementAndGet()}"
        suspendCancellableCoroutine { cont ->
            pending[id] = cont
            cont.invokeOnCancellation {
                pending.remove(id)
                engine.stop()
            }
            engine.language = locale
            engine.setSpeechRate(rate)
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (result != TextToSpeech.SUCCESS) {
                pending.remove(id)?.resume(Unit)
            }
        }
    }

    fun stop() {
        tts?.stop()
        pending.keys.toList().forEach { pending.remove(it)?.resume(Unit) }
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit
        override fun onDone(utteranceId: String) {
            pending.remove(utteranceId)?.resume(Unit)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            pending.remove(utteranceId)?.resume(Unit)
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            pending.remove(utteranceId)?.resume(Unit)
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            pending.remove(utteranceId)?.resume(Unit)
        }
    }
}
