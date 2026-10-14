# Anki ハンズフリー

AnkiDroid のカードを **読み上げ** て、**声で評価** できる Android アプリです。
運転中・家事中・散歩中など、画面を見ずに Anki の復習を進められます。

学習データは AnkiDroid 本体に保存されるため、スケジュール（FSRS / SM-2）や AnkiWeb 同期はそのまま使えます。

## 使い方

1. AnkiDroid をインストールし、コレクションを用意する
2. このアプリを起動し、「AnkiDroid のデータへのアクセス」と「マイク」を許可する
3. デッキを選んで「ハンズフリー学習を開始」
4. 表面が読み上げられたら、思い出して話しかける

| 場面 | 話しかける言葉（例） | 動作 |
| --- | --- | --- |
| 問題 | 「答え」「次」「めくって」/ "answer" | 裏面を読み上げる |
| 問題・答え | 「もう一度」「わからない」「1」/ "again" | もう一度 |
| 問題・答え | 「難しい」「微妙」「2」/ "hard" | 難しい |
| 問題・答え | 「正解」「わかった」「3」/ "good" | 正解 |
| 問題・答え | 「簡単」「楽勝」「4」/ "easy" | 簡単 |
| いつでも | 「繰り返して」「もう一度読んで」/ "repeat" | 再読み上げ |
| いつでも | 「終了」「ストップ」/ "stop" | 学習を終える |

- 問題の段階で評価を言うと、答えの読み上げを飛ばしてすぐ採点します。
- 「無言なら自動で答えを読み上げる」を ON にすると、考える時間が過ぎたら自動で裏面に進みます。
- 長時間反応がないと自動で一時停止します。
- 声が使えない場面用に、画面にも同じ操作のボタンがあります。

### 設定

- 問題 / 答え / 音声コマンドそれぞれの言語（例: 問題は英語、答えは日本語）
- 読み上げ速度
- 自動で答えを出すまでの時間
- 評価の復唱 ON/OFF

## 仕組み

```
AnkiDroid ──(ContentProvider: com.ichi2.anki.flashcards)──▶ AnkiDroidRepository
                                                            │ 次のカード / 回答送信
TextToSpeech ◀── Speaker ◀── ReviewViewModel（学習ループ）──▶ SpeechListener ──▶ SpeechRecognizer
                                     │
                              VoiceCommandParser（認識結果 → コマンド）
```

| ファイル | 役割 |
| --- | --- |
| `anki/AnkiDroidRepository.kt` | AnkiDroid API でデッキ一覧・次のカード・カード本文を取得、回答を送信 |
| `anki/CardText.kt` | カード HTML を読み上げ用テキストに変換（style・音声タグ・ふりがな・穴埋め対応） |
| `voice/Speaker.kt` | TextToSpeech の suspend ラッパー |
| `voice/SpeechListener.kt` | SpeechRecognizer の suspend ラッパー |
| `voice/VoiceCommand.kt` | 認識結果の文字列を学習コマンドに変換（日本語・英語） |
| `review/ReviewViewModel.kt` | 出題 → 読み上げ → 聞き取り → 評価 のループ |
| `ui/MainScreen.kt` | Jetpack Compose の画面 |

## ビルド

- Android Studio (Ladybug 以降) で開いて実行
- またはコマンドラインで `./gradlew assembleDebug`
- テスト: `./gradlew test`

要件: minSdk 26 (Android 8.0) / targetSdk 35 / AnkiDroid 2.x 以降

## 今後のアイデア

- Bluetooth イヤホンのボタン（再生/一時停止）で答え表示・評価
- 画面オフ・バックグラウンドでの学習（フォアグラウンドサービス化）
- カード内の音声ファイル（`[sound:...]`）の再生
- コマンド語のカスタマイズ
