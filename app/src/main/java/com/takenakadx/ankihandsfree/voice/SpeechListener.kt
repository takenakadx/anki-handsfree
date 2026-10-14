package com.takenakadx.ankihandsfree.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SpeechRecognizer で 1 回だけ聞き取り、認識候補を返す。
 * 無音・聞き取れなかった場合は空リストを返す。メインスレッドから呼ぶこと。
 */
class SpeechListener(context: Context) {
    private val appContext = context.applicationContext

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    suspend fun listen(locale: Locale, onPartial: (String) -> Unit = {}): List<String> =
        suspendCancellableCoroutine { cont ->
            // 状態が壊れやすいので毎回作り直す
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            var finished = false

            fun finish(block: () -> Unit) {
                if (finished) return
                finished = true
                recognizer.destroy()
                block()
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val list = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()
                    finish { cont.resume(list) }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.let(onPartial)
                }

                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        SpeechRecognizer.ERROR_CLIENT,
                        -> finish { cont.resume(emptyList()) }

                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            finish { cont.resumeWithException(SpeechException("マイクの権限がありません")) }

                        else -> finish { cont.resumeWithException(SpeechException("音声認識エラー (code=$error)")) }
                    }
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            cont.invokeOnCancellation {
                if (!finished) {
                    finished = true
                    recognizer.cancel()
                    recognizer.destroy()
                }
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            recognizer.startListening(intent)
        }
}

class SpeechException(message: String) : Exception(message)
