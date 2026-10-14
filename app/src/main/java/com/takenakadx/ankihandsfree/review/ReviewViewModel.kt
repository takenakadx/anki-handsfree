package com.takenakadx.ankihandsfree.review

import android.app.Application
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

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
        while (manualCommands.tryReceive().isSuccess) Unit
        _state.update { it.copy(phase = Phase.LOADING, reviewedCount = 0, message = null, heard = "") }
        session = viewModelScope.launch {
            try {
                if (!speaker.init()) {
                    _state.update { it.copy(message = "読み上げエンジンを初期化できませんでした") }
                    return@launch
                }
                if (!listener.isAvailable()) {
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

            // ---- 表面 ----
            _state.update { it.copy(phase = Phase.QUESTION, question = question, answer = answer) }
            speaker.speak(question, cfg().questionLanguage.locale, cfg().speechRate)
            var grade: Grade? = null
            var silent = 0
            questionLoop@ while (true) {
                when (val cmd = awaitCommand()) {
                    VoiceCommand.ShowAnswer -> break@questionLoop
                    VoiceCommand.Repeat -> speaker.speak(question, cfg().questionLanguage.locale, cfg().speechRate)
                    VoiceCommand.Stop -> return say("終了します。")
                    is VoiceCommand.Answer -> { grade = cmd.grade; break@questionLoop }
                    null -> {
                        silent++
                        if (cfg().autoReveal && silent >= cfg().thinkListens) break@questionLoop
                        if (silent >= MAX_SILENT_LISTENS) return pause()
                    }
                }
            }

            // ---- 裏面 ----
            if (grade == null) {
                _state.update { it.copy(phase = Phase.ANSWER) }
                speaker.speak(answer, cfg().answerLanguage.locale, cfg().speechRate)
                silent = 0
                answerLoop@ while (true) {
                    when (val cmd = awaitCommand()) {
                        is VoiceCommand.Answer -> { grade = cmd.grade; break@answerLoop }
                        VoiceCommand.Repeat, VoiceCommand.ShowAnswer ->
                            speaker.speak(answer, cfg().answerLanguage.locale, cfg().speechRate)
                        VoiceCommand.Stop -> return say("終了します。")
                        null -> {
                            silent++
                            if (silent >= MAX_SILENT_LISTENS) return pause()
                            if (silent % 2 == 0) say("評価をどうぞ。もう一度、難しい、正解、簡単。")
                        }
                    }
                }
            }

            val g = grade!!
            if (cfg().confirmGrade) say(g.label)
            answerCard(card, g, System.currentTimeMillis() - startedAt)
        }
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
     * 音声 1 回分の聞き取りと、画面ボタンからの入力のどちらか早い方を待つ。
     * 何も認識できなかった場合は null。
     */
    private suspend fun awaitCommand(): VoiceCommand? = coroutineScope {
        _state.update { it.copy(listening = true, heard = "") }
        val voice = async {
            listener.listen(_state.value.settings.commandLanguage.locale) { partial ->
                _state.update { it.copy(heard = partial) }
            }
        }
        try {
            select {
                manualCommands.onReceive { it }
                voice.onAwait { candidates ->
                    candidates.firstOrNull()?.let { heard -> _state.update { it.copy(heard = heard) } }
                    VoiceCommandParser.parse(candidates)
                }
            }
        } finally {
            voice.cancel()
            _state.update { it.copy(listening = false) }
        }
    }

    private fun blankWord(language: SpeechLanguage) =
        if (language == SpeechLanguage.JAPANESE) "ブランク" else "blank"

    override fun onCleared() {
        stop()
        speaker.shutdown()
    }

    private companion object {
        /** これだけ連続で無反応なら一時停止する（1 回 ≒ 5〜10 秒） */
        const val MAX_SILENT_LISTENS = 6
    }
}
