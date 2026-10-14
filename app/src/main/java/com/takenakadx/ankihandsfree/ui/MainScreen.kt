package com.takenakadx.ankihandsfree.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
    val allReady: Boolean get() = ankiInstalled && ankiGranted && micGranted
}

@Composable
fun MainScreen(
    viewModel: ReviewViewModel,
    permissions: PermissionState,
    onRequestPermissions: () -> Unit,
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
        Text("AnkiDroid のカードを読み上げ、声で評価できます。使うには次の準備が必要です。")
        Check("AnkiDroid がインストールされている", p.ankiInstalled)
        Check("AnkiDroid のデータへのアクセス許可", p.ankiGranted)
        Check("マイクの使用許可", p.micGranted)
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
            SettingsPanel(state.settings, onSettings)
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
        ) { Text("🎧 ハンズフリー学習を開始", fontSize = 18.sp) }
        Text(
            "話しかけ方: 「答え」で裏面、「もう一度 / 難しい / 正解 / 簡単」で評価、" +
                "「繰り返して」で再読み上げ、「終了」で終わります。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SettingsPanel(s: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LanguagePicker("問題の読み上げ言語", s.questionLanguage) { l -> onChange { it.copy(questionLanguage = l) } }
        LanguagePicker("答えの読み上げ言語", s.answerLanguage) { l -> onChange { it.copy(answerLanguage = l) } }
        LanguagePicker("音声コマンドの言語", s.commandLanguage) { l -> onChange { it.copy(commandLanguage = l) } }
        Text("読み上げ速度: ${"%.1f".format(s.speechRate)}x")
        Slider(
            value = s.speechRate,
            onValueChange = { v -> onChange { it.copy(speechRate = (v * 10).roundToInt() / 10f) } },
            valueRange = 0.5f..2.0f,
        )
        SwitchRow("無言なら自動で答えを読み上げる", s.autoReveal) { v -> onChange { it.copy(autoReveal = v) } }
        if (s.autoReveal) {
            Text("考える時間: 聞き取り ${s.thinkListens} 回分（約 ${s.thinkListens * 5} 秒）")
            Slider(
                value = s.thinkListens.toFloat(),
                onValueChange = { v -> onChange { it.copy(thinkListens = v.roundToInt()) } },
                valueRange = 1f..5f,
                steps = 3,
            )
        }
        SwitchRow("評価を復唱する", s.confirmGrade) { v -> onChange { it.copy(confirmGrade = v) } }
        HorizontalDivider()
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("学習済み ${state.reviewedCount} 枚", Modifier.weight(1f))
            Text(
                when {
                    state.listening -> "🎙️ 聞いています…"
                    state.phase == Phase.LOADING -> "読み込み中…"
                    else -> "🔊 読み上げ中"
                },
                fontWeight = FontWeight.Bold,
            )
        }
        Card(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text(state.question, fontSize = 24.sp)
                if (state.phase == Phase.ANSWER) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text(state.answer, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (state.heard.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text("「${state.heard}」", Modifier.padding(8.dp))
            }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        // 音声が使いにくい場面のための手動ボタン
        if (state.phase == Phase.QUESTION) {
            Button(onClick = { onCommand(VoiceCommand.ShowAnswer) }, Modifier.fillMaxWidth().height(56.dp)) {
                Text("答えを見る")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Grade.entries.forEach { g ->
                    FilledTonalButton(
                        onClick = { onCommand(VoiceCommand.Answer(g)) },
                        modifier = Modifier.weight(1f).height(56.dp),
                        enabled = state.phase == Phase.ANSWER,
                    ) { Text(g.label, fontSize = 13.sp) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onCommand(VoiceCommand.Repeat) }, Modifier.weight(1f)) { Text("もう一度読む") }
            OutlinedButton(onClick = { onCommand(VoiceCommand.Stop) }, Modifier.weight(1f)) { Text("終了") }
        }
        Spacer(Modifier.height(4.dp))
    }
}
