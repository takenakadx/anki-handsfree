package com.takenakadx.ankihandsfree.review

/** Anki の 4 段階評価。 */
enum class Grade(val label: String) {
    AGAIN("もう一度"),
    HARD("難しい"),
    GOOD("正解"),
    EASY("簡単");

    /**
     * AnkiDroid に送るボタン番号に変換する。
     * v3 スケジューラでは常に 4 ボタンだが、古い学習中カードでは 2〜3 ボタンのことがある。
     */
    fun toEase(buttonCount: Int): Int = when (buttonCount) {
        2 -> if (this == AGAIN) 1 else 2
        3 -> when (this) {
            AGAIN -> 1
            HARD, GOOD -> 2
            EASY -> 3
        }
        else -> ordinal + 1
    }
}
