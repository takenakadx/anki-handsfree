package com.takenakadx.ankihandsfree.review

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.takenakadx.ankihandsfree.anki.AnkiDroidRepository
import com.takenakadx.ankihandsfree.anki.CardText
import com.takenakadx.ankihandsfree.anki.Deck
import com.takenakadx.ankihandsfree.anki.DueCard
import com.takenakadx.ankihandsfree.voice.SpeechListener
import com.takenakadx.ankihandsfree.voice.Speaker
import com.takenakadx.ankihandsfree.voice.VoiceCommand
import com.takenakadx.ankihandsfree.voice.VoiceCommandParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

enum class Phase { IDLE, LOADING, QUESTION, ANSWER, FINISHED }

data class UiState(
    val decks: List<Deck> = emptyList(),
    val selectedDeckId: Long = -1L,
    val settings: Settings = Settings(),
    val phase: Phase = Phase.IDLE,
    val question: String = "",
    val answer: String = "",
    val listening: Boolean = false,
    val heard: String = "",
    val reviewedCount: Int = 0,
    val message: String? = null,
)

class ReviewViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AnkiDroidRepository(app)
    private val speaker = Speaker(app)
    private val listener = SpeechListener(app)
    private val settingsStore = SettingsStore(app)

    private val _state = MutableStateFlow(
        UiState(settings = settingsStore.load(), selectedDeckId = settingsStore.lastDeckId)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 画面のボタンから送られるコマンド（音声と同じ扱い） */
    private val manualCommands = Channel<VoiceCommand>(Channel.CONFLATED)
    private var session: Job? = null

    val isAnkiDroidInstalled: Boolean get() = repo.isAnkiDroidInstalled()
    val hasAnkiPermission: Boolean get() = repo.hasPermission()

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun loadDecks() {
        viewModelScope.launch {
            runCatching { repo.decks() }
                .onSuccess { decks ->
                    _state.update { s ->
                        val selected = s.selectedDeckId.takeIf { id -> decks.any { it.id == id } }
                            ?: decks.firstOrNull { it.dueCount > 0 }?.id
                            ?: decks.firstOrNull()?.id
                            ?: -1L
                        s.copy(decks = decks, selectedDeckId = selected, message = null)
                    }
                }
                .onFailure { e -> _state.update { it.copy(message = e.message) } }
        }
    }

    fun selectDeck(id: Long) {
        settingsStore.lastDeckId = id
        _state.update { it.copy(selectedDeckId = id) }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val next = transform(_state.value.settings)
        settingsStore.save(next)
        _state.update { it.copy(settings = next) }
    }

    fun sendCommand(command: VoiceCommand) {
        if (command == VoiceCommand.Stop) {
            stop()
        } else {
            manualCommands.trySend(command)
        }
    }

    fun start() {
        if (session?.isActive == true) return
        val deckId = _state.value.selectedDeckId
        if (deckId < 0) return
        drainManualCommands()
        _state.update { it.copy(phase = Phase.LOADING, reviewedCount = 0, message = null, heard = "") }
        session = viewModelScope.launch {
            try {
                if (!speaker.init()) {
                    _state.update { it.copy(message = "読み上げエンジンを初期化できませんでした") }
                    return@launch
                }
                if (_state.value.settings.voiceCommands && !hasMicPermission()) {
                    _state.update { it.copy(message = "音声コマンドを使うにはマイクの許可が必要です") }
                    return@launch
                }
                if (_state.value.settings.voiceCommands && !listener.isAvailable()) {
                    _state.update { it.copy(message = "この端末では音声認識が使えません") }
                    return@launch
                }
                runSession(deckId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: e.toString()) }
            } finally {
                speaker.stop()
                _state.update {
                    it.copy(phase = if (it.phase == Phase.FINISHED) Phase.FINISHED else Phase.IDLE, listening = false)
                }
                loadDecks()
            }
        }
    }

    fun stop() {
        session?.cancel()
        session = null
        speaker.stop()
    }

    private suspend fun runSession(deckId: Long) {
        val cfg = { _state.value.settings }
        while (true) {
            _state.update { it.copy(phase = Phase.LOADING, question = "", answer = "", heard = "") }
            val card = repo.nextDueCard(deckId)
            if (card == null) {
                _state.update { it.copy(phase = Phase.FINISHED) }
                say("お疲れさまでした。このデッキの今日の学習は終わりです。")
                return
            }
            val content = repo.cardContent(card)
            val question = CardText.toSpeech(content.questionHtml, blankWord(cfg().questionLanguage))
            val answer = CardText.toSpeech(content.answerHtml, blankWord(cfg().answerLanguage))
            val startedAt = System.currentTimeMillis()

            // 前のカードで連打されたボタンを次のカードに持ち越さない
            drainManualCommands()

            // ---- 表面 ----
            _state.update { it.copy(phase = Phase.QUESTION, question = question, answer = answer) }
            var grade: Grade? = null
            var speakText: String? = question
            questionLoop@ while (true) {
                val timeout = if (cfg().autoReveal) cfg().thinkSeconds * 1000L else null
                when (val cmd = speakAndAwait(speakText, cfg().questionLanguage, timeout)) {
                    VoiceCommand.ShowAnswer -> break@questionLoop
                    VoiceCommand.Repeat -> speakText = question
                    VoiceCommand.Stop -> return say("終了します。")
                    is VoiceCommand.Answer -> { grade = cmd.grade; break@questionLoop }
                    null -> if (cfg().autoReveal) break@questionLoop else return pause()
                }
            }

            // ---- 裏面 ----
            if (grade == null) {
                _state.update { it.copy(phase = Phase.ANSWER) }
                speakText = answer
                answerLoop@ while (true) {
                    when (val cmd = speakAndAwait(speakText, cfg().answerLanguage, null)) {
                        is VoiceCommand.Answer -> { grade = cmd.grade; break@answerLoop }
                        VoiceCommand.Repeat, VoiceCommand.ShowAnswer -> speakText = answer
                        VoiceCommand.Stop -> return say("終了します。")
                        null -> return pause()
                    }
                }
            }

            val g = grade!!
            if (cfg().confirmGrade) say(g.label)
            answerCard(card, g, System.currentTimeMillis() - startedAt)
        }
    }

    private fun drainManualCommands() {
        while (manualCommands.tryReceive().isSuccess) Unit
    }

    private suspend fun answerCard(card: DueCard, grade: Grade, elapsedMs: Long) {
        repo.answer(card, grade.toEase(card.buttonCount), elapsedMs.coerceIn(1_000L, 60_000L))
        _state.update { it.copy(reviewedCount = it.reviewedCount + 1) }
    }

    private suspend fun pause() {
        say("反応がないので一時停止します。")
    }

    private suspend fun say(text: String) {
        speaker.speak(text, SpeechLanguage.JAPANESE.locale, _state.value.settings.speechRate)
    }

    /**
     * [text] を読み上げ（null なら読み上げない）、その後の入力を待つ。
     * 画面のボタンは読み上げ中でも受け付け、押されたら読み上げを止めてすぐ返す。
     *
     * - ボタンのみ: [timeoutMs] が null ならボタンが押されるまで待ち続ける。経過したら null。
     * - 音声コマンドあり: 聞き取りを繰り返し、[timeoutMs]（null なら [VOICE_IDLE_LIMIT_MS]）経過で null。
     */
    private suspend fun speakAndAwait(text: String?, language: SpeechLanguage, timeoutMs: Long?): VoiceCommand? =
        coroutineScope {
            val afterSpeech = async {
                if (text != null) speaker.speak(text, language.locale, _state.value.settings.speechRate)
                if (_state.value.settings.voiceCommands) {
                    withTimeoutOrNull(timeoutMs ?: VOICE_IDLE_LIMIT_MS) { listenUntilCommand() }
                } else if (timeoutMs != null) {
                    delay(timeoutMs)
                    null
                } else {
                    awaitCancellation()
                }
            }
            try {
                select {
                    manualCommands.onReceive { it }
                    afterSpeech.onAwait { it }
                }
            } finally {
                afterSpeech.cancel()
                _state.update { it.copy(listening = false) }
            }
        }

    private suspend fun listenUntilCommand(): VoiceCommand {
        while (true) {
            _state.update { it.copy(listening = true, heard = "") }
            val candidates = listener.listen(_state.value.settings.commandLanguage.locale) { partial ->
                _state.update { it.copy(heard = partial) }
            }
            candidates.firstOrNull()?.let { heard -> _state.update { it.copy(heard = heard) } }
            VoiceCommandParser.parse(candidates)?.let { return it }
        }
    }

    private fun blankWord(language: SpeechLanguage) =
        if (language == SpeechLanguage.JAPANESE) "ブランク" else "blank"

    override fun onCleared() {
        stop()
        speaker.shutdown()
    }

    private companion object {
        /** 音声コマンド使用時、これだけ無反応なら一時停止する */
        const val VOICE_IDLE_LIMIT_MS = 60_000L
    }
}
