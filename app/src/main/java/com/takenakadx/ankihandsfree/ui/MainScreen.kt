package com.takenakadx.ankihandsfree.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takenakadx.ankihandsfree.review.Grade
import com.takenakadx.ankihandsfree.review.Phase
import com.takenakadx.ankihandsfree.review.ReviewViewModel
import com.takenakadx.ankihandsfree.review.Settings
import com.takenakadx.ankihandsfree.review.SpeechLanguage
import com.takenakadx.ankihandsfree.review.UiState
import com.takenakadx.ankihandsfree.voice.VoiceCommand
import kotlin.math.roundToInt

data class PermissionState(
    val ankiInstalled: Boolean = false,
    val ankiGranted: Boolean = false,
    val micGranted: Boolean = false,
) {
    /** マイクは音声コマンドを使うときだけ必要 */
    val allReady: Boolean get() = ankiInstalled && ankiGranted
}

@Composable
fun MainScreen(
    viewModel: ReviewViewModel,
    permissions: PermissionState,
    onRequestPermissions: () -> Unit,
    onRequestMic: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            when {
                !permissions.allReady -> PermissionScreen(permissions, onRequestPermissions)
                state.phase == Phase.IDLE || state.phase == Phase.FINISHED -> HomeScreen(
                    state = state,
                    onSelectDeck = viewModel::selectDeck,
                    onReload = viewModel::loadDecks,
                    onStart = viewModel::start,
                    onSettings = viewModel::updateSettings,
                    micGranted = permissions.micGranted,
                    onRequestMic = onRequestMic,
                )
                else -> ReviewScreen(state, onCommand = viewModel::sendCommand)
            }
        }
    }
}

@Composable
private fun PermissionScreen(p: PermissionState, onRequest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Anki ハンズフリー", style = MaterialTheme.typography.headlineMedium)
        Text("AnkiDroid のカードを読み上げ、画面の大きなボタンで評価できます。使うには次の準備が必要です。")
        Check("AnkiDroid がインストールされている", p.ankiInstalled)
        Check("AnkiDroid のデータへのアクセス許可", p.ankiGranted)
        if (p.ankiInstalled) {
            Button(onClick = onRequest) { Text("許可する") }
        } else {
            Text("Google Play から AnkiDroid をインストールしてください。", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Check(label: String, ok: Boolean) {
    Text((if (ok) "✅ " else "⬜ ") + label)
}

@Composable
private fun HomeScreen(
    state: UiState,
    onSelectDeck: (Long) -> Unit,
    onReload: () -> Unit,
    onStart: () -> Unit,
    onSettings: ((Settings) -> Settings) -> Unit,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("デッキを選択", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onReload) { Text("更新") }
            TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "閉じる" else "設定") }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.phase == Phase.FINISHED) {
            Text("おつかれさまでした！ ${state.reviewedCount} 枚学習しました。")
        }
        if (showSettings) {
            SettingsPanel(state.settings, onSettings, micGranted, onRequestMic)
        } else {
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(state.decks, key = { it.id }) { deck ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelectDeck(deck.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = deck.id == state.selectedDeckId, onClick = { onSelectDeck(deck.id) })
                        Text(deck.name, Modifier.weight(1f))
                        Text("新${deck.newCount} 習${deck.learnCount} 復${deck.reviewCount}", fontSize = 13.sp)
                    }
                }
            }
        }
        Button(
            onClick = onStart,
            enabled = state.selectedDeckId >= 0,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text("🎧 学習を開始", fontSize = 18.sp) }
        Text(
            "問題が読み上げられたら、画面をタップするか音量ボタンで答えを読み上げます。" +
                "答えの後は、音量 ＋ で正解、音量 − でもう一度（画面のボタンでも評価できます）。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SettingsPanel(
    s: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LanguagePicker("問題の読み上げ言語", s.questionLanguage) { l -> onChange { it.copy(questionLanguage = l) } }
        LanguagePicker("答えの読み上げ言語", s.answerLanguage) { l -> onChange { it.copy(answerLanguage = l) } }
        Text("読み上げ速度: ${"%.1f".format(s.speechRate)}x")
        Slider(
            value = s.speechRate,
            onValueChange = { v -> onChange { it.copy(speechRate = (v * 10).roundToInt() / 10f) } },
            valueRange = 0.5f..2.0f,
        )
        SwitchRow("操作しなければ自動で答えを読み上げる", s.autoReveal) { v -> onChange { it.copy(autoReveal = v) } }
        if (s.autoReveal) {
            Text("考える時間: ${s.thinkSeconds} 秒")
            Slider(
                value = s.thinkSeconds.toFloat(),
                onValueChange = { v -> onChange { it.copy(thinkSeconds = v.roundToInt()) } },
                valueRange = 2f..20f,
                steps = 17,
            )
        }
        SwitchRow("音量ボタンで操作する（上: 答え → 正解 / 下: もう一度）", s.volumeKeys) { v ->
            onChange { it.copy(volumeKeys = v) }
        }
        SwitchRow("評価を読み上げて確認する", s.confirmGrade) { v -> onChange { it.copy(confirmGrade = v) } }
        HorizontalDivider()
        SwitchRow("音声コマンドも使う", s.voiceCommands) { v ->
            if (v && !micGranted) onRequestMic()
            onChange { it.copy(voiceCommands = v) }
        }
        if (s.voiceCommands) {
            LanguagePicker("音声コマンドの言語", s.commandLanguage) { l -> onChange { it.copy(commandLanguage = l) } }
            Text(
                "「答え」で裏面、「もう一度 / 難しい / 正解 / 簡単」で評価、「繰り返して」で再読み上げ、「終了」で終わります。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!micGranted) {
                Text("マイクの許可が必要です", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LanguagePicker(label: String, value: SpeechLanguage, onSelect: (SpeechLanguage) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = { open = true }) { Text(value.label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SpeechLanguage.entries.forEach { lang ->
                DropdownMenuItem(text = { Text(lang.label) }, onClick = { onSelect(lang); open = false })
            }
        }
    }
}

@Composable
private fun ReviewScreen(state: UiState, onCommand: (VoiceCommand) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val send: (VoiceCommand) -> Unit = { cmd ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onCommand(cmd)
    }
    val volumeHint = state.settings.volumeKeys
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 上部: 状態と小さな操作ボタン（誤タップしにくいよう小さく端に置く）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("学習済み ${state.reviewedCount} 枚", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            if (state.listening) Text("🎙️", Modifier.padding(end = 8.dp))
            TextButton(onClick = { send(VoiceCommand.Repeat) }) { Text("🔁 再読") }
            TextButton(onClick = { send(VoiceCommand.Stop) }) { Text("⏹ 終了") }
        }

        // カードの文字は小さめに（確認用）
        Card(Modifier.fillMaxWidth().weight(0.22f)) {
            Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                if (state.phase == Phase.LOADING) {
                    Text("読み込み中…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(state.question, fontSize = 17.sp)
                    if (state.phase == Phase.ANSWER) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(state.answer, fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (state.heard.isNotBlank()) {
                    Text("「${state.heard}」", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        // 画面の残り全体をタップ領域にする
        Box(Modifier.fillMaxWidth().weight(0.78f)) {
            if (state.phase == Phase.ANSWER) {
                GradeZones(volumeHint, onGrade = { send(VoiceCommand.Answer(it)) })
            } else {
                BigButton(
                    label = "答えを見る",
                    sub = if (volumeHint) "画面をタップ / 音量ボタン" else "画面をタップ",
                    color = MaterialTheme.colorScheme.primary,
                    enabled = state.phase == Phase.QUESTION,
                    modifier = Modifier.fillMaxSize(),
                    labelSize = 36,
                    onClick = { send(VoiceCommand.ShowAnswer) },
                )
            }
        }
    }
}

/**
 * 評価のタップ領域。よく使う「もう一度」「正解」を大きく、「難しい」「簡単」を小さく配置する。
 * 左右の位置は音量ボタン（下 = もう一度 / 上 = 正解）と揃えている。
 */
@Composable
private fun GradeZones(volumeHint: Boolean, onGrade: (Grade) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.weight(2f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GradeButton(Grade.AGAIN, if (volumeHint) "音量 −" else null, 34, onGrade)
            GradeButton(Grade.GOOD, if (volumeHint) "音量 ＋" else null, 34, onGrade)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GradeButton(Grade.HARD, null, 24, onGrade)
            GradeButton(Grade.EASY, null, 24, onGrade)
        }
    }
}

@Composable
private fun RowScope.GradeButton(grade: Grade, sub: String?, labelSize: Int, onGrade: (Grade) -> Unit) {
    BigButton(
        label = grade.label,
        sub = sub,
        color = gradeColor(grade),
        enabled = true,
        modifier = Modifier.weight(1f).fillMaxHeight(),
        labelSize = labelSize,
        onClick = { onGrade(grade) },
    )
}

private fun gradeColor(g: Grade): Color = when (g) {
    Grade.AGAIN -> Color(0xFFC62828)
    Grade.HARD -> Color(0xFFEF6C00)
    Grade.GOOD -> Color(0xFF2E7D32)
    Grade.EASY -> Color(0xFF1565C0)
}

@Composable
private fun BigButton(
    label: String,
    sub: String?,
    color: Color,
    enabled: Boolean,
    modifier: Modifier,
    labelSize: Int = 30,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = labelSize.sp, fontWeight = FontWeight.Bold)
            if (sub != null) Text(sub, fontSize = 14.sp)
        }
    }
}
