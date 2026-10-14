package com.takenakadx.ankihandsfree.voice

import com.takenakadx.ankihandsfree.review.Grade

sealed interface VoiceCommand {
    data object ShowAnswer : VoiceCommand
    data object Repeat : VoiceCommand
    data object Stop : VoiceCommand
    data class Answer(val grade: Grade) : VoiceCommand
}

/**
 * 音声認識の結果（候補文字列）を学習コマンドに変換する。
 * 日本語・英語の両方に対応。短い語（「いち」「3」など）は完全一致、長い語は部分一致で判定する。
 */
object VoiceCommandParser {

    private data class Rule(
        val command: VoiceCommand,
        val contains: List<String> = emptyList(),
        val exact: List<String> = emptyList(),
    )

    // 上から順に評価する（「もう一度読んで」を「もう一度」より先に判定するため Repeat が先頭）。
    private val rules = listOf(
        Rule(
            VoiceCommand.Repeat,
            contains = listOf("読んで", "よんで", "繰り返", "くりかえ", "リピート", "repeat", "pardon", "say again", "聞こえ"),
        ),
        Rule(
            VoiceCommand.Stop,
            contains = listOf("終了", "終わり", "おわり", "ストップ", "やめ", "止めて", "とめて", "stop", "quit", "finish"),
        ),
        Rule(
            VoiceCommand.Answer(Grade.AGAIN),
            contains = listOf(
                "もう一度", "もう一回", "もういちど", "もういっかい", "わからない", "分からない", "分かんない",
                "わかんない", "忘れた", "わすれた", "知らない", "しらない", "不正解", "ダメ", "だめ", "again", "wrong",
                "don't know", "forgot",
            ),
            exact = listOf("1", "一", "いち", "one", "アゲイン", "ミス", "miss", "no"),
        ),
        Rule(
            VoiceCommand.Answer(Grade.HARD),
            contains = listOf("難しい", "むずかしい", "むずい", "ムズい", "微妙", "びみょう", "ギリギリ", "ぎりぎり", "hard", "difficult"),
            exact = listOf("2", "二", "に", "two", "ハード"),
        ),
        Rule(
            VoiceCommand.Answer(Grade.EASY),
            contains = listOf("簡単", "かんたん", "楽勝", "らくしょう", "余裕", "よゆう", "easy"),
            exact = listOf("4", "四", "よん", "four", "イージー"),
        ),
        Rule(
            VoiceCommand.Answer(Grade.GOOD),
            contains = listOf(
                "正解", "せいかい", "わかった", "分かった", "覚えた", "おぼえた", "合ってた", "あってた", "できた",
                "グッド", "good", "correct", "got it",
            ),
            exact = listOf("3", "三", "さん", "three", "ok", "オーケー", "オッケー", "はい", "yes", "いい", "良い"),
        ),
        Rule(
            VoiceCommand.ShowAnswer,
            contains = listOf("答え", "こたえ", "回答", "解答", "めくって", "めくる", "裏", "answer", "show", "flip", "reveal"),
            exact = listOf("次", "つぎ", "next", "ネクスト"),
        ),
    )

    /** 候補を信頼度の高い順に調べ、最初にマッチしたコマンドを返す。 */
    fun parse(candidates: List<String>): VoiceCommand? {
        for (candidate in candidates) {
            parse(candidate)?.let { return it }
        }
        return null
    }

    fun parse(text: String): VoiceCommand? {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return null
        for (rule in rules) {
            if (rule.exact.any { normalize(it) == normalized }) return rule.command
            if (rule.contains.any { normalized.contains(normalize(it)) }) return rule.command
        }
        return null
    }

    internal fun normalize(text: String): String = buildString {
        for (ch in text.lowercase()) {
            val c = when (ch) {
                in '０'..'９' -> '0' + (ch - '０')
                in 'ａ'..'ｚ' -> 'a' + (ch - 'ａ')
                '’' -> '\''
                else -> ch
            }
            // 空白と句読点は無視する（音声認識は「正解。」のように句点を付けることがある）
            if (c.isWhitespace() || c in "。、，．,.!?！？「」") continue
            append(c)
        }
    }
}
