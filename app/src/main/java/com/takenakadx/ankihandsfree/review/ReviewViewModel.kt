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
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

enum class Phase {
    IDLE,
    LOADING,
    QUESTION,
    ANSWER,

    /** 最後のカードを評価した直後。少しの間だけ「戻す」を受け付ける */
    LAST_CARD,
    FINISHED,
}

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
    /** 直前の評価を取り消せる */
    val canUndo: Boolean = false,
    val message: String? = null,
)

/** 評価したが、まだ AnkiDroid に送っていないカード。「戻す」で評価し直せる。 */
private class PendingAnswer(
    val card: DueCard,
    val question: String,
    val answer: String,
    val grade: Grade,
    val elapsedMs: Long,
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

    /**
     * 直前に評価したカード。次のカードを評価するか、学習を終えたときに AnkiDroid へ送る。
     * AnkiDroid の API には取り消しが無いので、送る前に保留しておくことで「戻す」を実現している。
     */
    private var pending: PendingAnswer? = null

    /** 評価の読み上げ中など、入力を待っていない間に押された「戻す」 */
    private val undoRequested = AtomicBoolean(false)

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
        when (command) {
            VoiceCommand.Stop -> stop()
            VoiceCommand.Undo -> {
                undoRequested.set(true)
                manualCommands.trySend(command)
            }
            else -> manualCommands.trySend(command)
        }
    }

    /** 学習中かつ設定が ON なら、音量ボタンを操作に使う */
    val volumeKeysActive: Boolean
        get() = _state.value.settings.volumeKeys && session?.isActive == true

    /**
     * 音量ボタンの短押し。操作として受け付けたら true。
     *
     * - 問題: 上下どちらでも「答えを見る」
     * - 答え: 上 = 正解 / 下 = もう一度
     *
     * 読み込み中・評価の読み上げ中は無視する（次のカードに持ち越さない）。
     */
    fun onVolumeKey(up: Boolean): Boolean {
        val command = when (_state.value.phase) {
            Phase.QUESTION -> VoiceCommand.ShowAnswer
            Phase.ANSWER -> VoiceCommand.Answer(if (up) Grade.GOOD else Grade.AGAIN)
            else -> return false
        }
        sendCommand(command)
        return true
    }

    /** 音量ボタンの長押し。下 = 戻す / 上 = もう一度読む */
    fun onVolumeLongPress(up: Boolean): Boolean {
        if (up) {
            if (_state.value.phase !in listOf(Phase.QUESTION, Phase.ANSWER)) return false
            sendCommand(VoiceCommand.Repeat)
        } else {
            if (!_state.value.canUndo) return false
            sendCommand(VoiceCommand.Undo)
        }
        return true
    }

    fun start() {
        if (session?.isActive == true) return
        val deckId = _state.value.selectedDeckId
        if (deckId < 0) return
        drainManualCommands()
        undoRequested.set(false)
        pending = null
        _state.update { it.copy(phase = Phase.LOADING, reviewedCount = 0, canUndo = false, message = null, heard = "") }
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
                // 保留中の評価を確定する（終了ボタンや画面を離れたときも失われないように）
                withContext(NonCancellable) {
                    runCatching { commitPending() }
                        .onFailure { e -> _state.update { it.copy(message = e.message ?: e.toString()) } }
                }
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
        /** 「戻す」で戻ってきたカード。表面は飛ばして答えから評価し直す */
        var restored: PendingAnswer? = null

        session@ while (true) {
            val card: DueCard
            val question: String
            val answer: String
            val back = restored
            restored = null
            if (back != null) {
                card = back.card
                question = back.question
                answer = back.answer
            } else {
                _state.update { it.copy(phase = Phase.LOADING, question = "", answer = "", heard = "") }
                // 保留中のカードはまだ AnkiDroid では未回答なので、それ以外の次のカードを選ぶ
                val held = pending
                val next = repo.nextDueCards(deckId, limit = 2)
                    .firstOrNull { held == null || !it.isSameCard(held.card) }
                if (next == null) {
                    if (held != null && offerUndoForLastCard()) {
                        restored = takePending()
                        continue@session
                    }
                    commitPending()
                    _state.update { it.copy(phase = Phase.FINISHED) }
                    say("お疲れさまでした。このデッキの今日の学習は終わりです。")
                    return
                }
                card = next
                val content = repo.cardContent(card)
                question = CardText.toSpeech(content.questionHtml, blankWord(cfg().questionLanguage))
                answer = CardText.answerOnly(content.answerHtml, content.questionHtml, blankWord(cfg().answerLanguage))
            }
            val startedAt = System.currentTimeMillis()

            // 前のカードで連打されたボタンを次のカードに持ち越さない（「戻す」だけは別に覚えておく）
            drainManualCommands()
            if (undoRequested.getAndSet(false) && pending != null) {
                say("前のカードに戻ります。")
                restored = takePending()
                continue@session
            }

            var grade: Grade? = null
            var speakText: String?

            // ---- 表面 ----（戻ってきたカードは飛ばす）
            if (back == null) {
                _state.update { it.copy(phase = Phase.QUESTION, question = question, answer = answer) }
                speakText = question
                questionLoop@ while (true) {
                    val timeout = if (cfg().autoReveal) cfg().thinkSeconds * 1000L else null
                    when (val cmd = speakAndAwait(speakText, cfg().questionLanguage, timeout)) {
                        VoiceCommand.ShowAnswer -> break@questionLoop
                        VoiceCommand.Repeat -> speakText = question
                        VoiceCommand.Stop -> return say("終了します。")
                        VoiceCommand.Undo -> {
                            if (tryUndo()) { restored = takePending(); continue@session }
                            speakText = null
                        }
                        is VoiceCommand.Answer -> { grade = cmd.grade; break@questionLoop }
                        null -> if (cfg().autoReveal) break@questionLoop else return pause()
                    }
                }
            }

            // ---- 裏面 ----
            if (grade == null) {
                _state.update { it.copy(phase = Phase.ANSWER, question = question, answer = answer) }
                speakText = answer
                answerLoop@ while (true) {
                    when (val cmd = speakAndAwait(speakText, cfg().answerLanguage, null)) {
                        is VoiceCommand.Answer -> { grade = cmd.grade; break@answerLoop }
                        VoiceCommand.Repeat, VoiceCommand.ShowAnswer -> speakText = answer
                        VoiceCommand.Stop -> return say("終了します。")
                        VoiceCommand.Undo -> {
                            if (tryUndo()) { restored = takePending(); continue@session }
                            speakText = null
                        }
                        null -> return pause()
                    }
                }
            }

            // 前のカードを確定し、このカードを保留にする（次のカードの間は「戻す」で取り消せる）
            val g = grade!!
            commitPending()
            pending = PendingAnswer(card, question, answer, g, System.currentTimeMillis() - startedAt)
            _state.update { it.copy(reviewedCount = it.reviewedCount + 1, canUndo = true) }
            if (cfg().confirmGrade) say(g.label)
        }
    }

    /** 入力待ち中に「戻す」が押されたとき。戻れるなら true */
    private suspend fun tryUndo(): Boolean {
        undoRequested.set(false)
        if (pending == null) return false
        say("前のカードに戻ります。")
        return true
    }

    /** 最後のカードを評価した後、少しの間「戻す」を待つ。戻すなら true */
    private suspend fun offerUndoForLastCard(): Boolean {
        _state.update { it.copy(phase = Phase.LAST_CARD) }
        drainManualCommands()
        if (undoRequested.getAndSet(false)) return true
        val cmd = speakAndAwait(
            "これで最後のカードです。取り消すなら、戻すを押してください。",
            SpeechLanguage.JAPANESE,
            LAST_CARD_UNDO_MS,
        )
        undoRequested.set(false)
        return cmd == VoiceCommand.Undo
    }

    private fun takePending(): PendingAnswer {
        val p = pending!!
        pending = null
        _state.update { it.copy(reviewedCount = it.reviewedCount - 1, canUndo = false) }
        return p
    }

    /** 保留中の評価を AnkiDroid に送る */
    private suspend fun commitPending() {
        val p = pending ?: return
        repo.answer(p.card, p.grade.toEase(p.card.buttonCount), p.elapsedMs.coerceIn(1_000L, 60_000L))
        pending = null
        _state.update { it.copy(canUndo = false) }
    }

    private fun drainManualCommands() {
        while (manualCommands.tryReceive().isSuccess) Unit
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

        /** 最後のカードの後、「戻す」を受け付ける時間 */
        const val LAST_CARD_UNDO_MS = 8_000L
    }
}
