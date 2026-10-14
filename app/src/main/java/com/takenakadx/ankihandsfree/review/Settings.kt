package com.takenakadx.ankihandsfree.review

import android.content.Context
import java.util.Locale

enum class SpeechLanguage(val label: String, val locale: Locale) {
    JAPANESE("日本語", Locale.JAPAN),
    ENGLISH("英語", Locale.US),
    CHINESE("中国語", Locale.SIMPLIFIED_CHINESE),
    KOREAN("韓国語", Locale.KOREA),
    FRENCH("フランス語", Locale.FRANCE),
    GERMAN("ドイツ語", Locale.GERMANY),
    SPANISH("スペイン語", Locale("es", "ES")),
}

data class Settings(
    /** 表面（問題）を読み上げる言語 */
    val questionLanguage: SpeechLanguage = SpeechLanguage.ENGLISH,
    /** 裏面（答え）を読み上げる言語 */
    val answerLanguage: SpeechLanguage = SpeechLanguage.JAPANESE,
    /** 音声コマンドを聞き取る言語 */
    val commandLanguage: SpeechLanguage = SpeechLanguage.JAPANESE,
    val speechRate: Float = 1.0f,
    /** 問題の後、一定時間無言なら自動で答えを読み上げる */
    val autoReveal: Boolean = true,
    /** 自動で答えを出すまでの聞き取り回数（1 回 ≒ 5 秒） */
    val thinkListens: Int = 1,
    /** 評価を聞き取ったら「正解」などと復唱する */
    val confirmGrade: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        return Settings(
            questionLanguage = lang("questionLanguage", d.questionLanguage),
            answerLanguage = lang("answerLanguage", d.answerLanguage),
            commandLanguage = lang("commandLanguage", d.commandLanguage),
            speechRate = prefs.getFloat("speechRate", d.speechRate),
            autoReveal = prefs.getBoolean("autoReveal", d.autoReveal),
            thinkListens = prefs.getInt("thinkListens", d.thinkListens),
            confirmGrade = prefs.getBoolean("confirmGrade", d.confirmGrade),
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("questionLanguage", s.questionLanguage.name)
            .putString("answerLanguage", s.answerLanguage.name)
            .putString("commandLanguage", s.commandLanguage.name)
            .putFloat("speechRate", s.speechRate)
            .putBoolean("autoReveal", s.autoReveal)
            .putInt("thinkListens", s.thinkListens)
            .putBoolean("confirmGrade", s.confirmGrade)
            .apply()
    }

    var lastDeckId: Long
        get() = prefs.getLong("lastDeckId", -1L)
        set(value) = prefs.edit().putLong("lastDeckId", value).apply()

    private fun lang(key: String, default: SpeechLanguage): SpeechLanguage =
        prefs.getString(key, null)
            ?.let { name -> SpeechLanguage.entries.firstOrNull { it.name == name } }
            ?: default
}
