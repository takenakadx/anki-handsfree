package com.takenakadx.ankihandsfree.anki

/**
 * Anki のカード HTML を読み上げ用のプレーンテキストに変換する。
 */
object CardText {
    private val blockRemovals = listOf(
        Regex("(?is)<style[^>]*>.*?</style>"),
        Regex("(?is)<script[^>]*>.*?</script>"),
    )
    private val soundTag = Regex("\\[sound:[^\\]]*]")
    private val ttsTag = Regex("(?s)\\[anki:tts[^\\]]*](.*?)\\[/anki:tts]")
    private val furigana = Regex("(?is)<rt[^>]*>.*?</rt>|<rp[^>]*>.*?</rp>")
    private val lineBreakTags = Regex("(?i)<br\\s*/?>|</(div|p|li|tr|h[1-6])>|<hr[^>]*>")
    private val anyTag = Regex("(?s)<[^>]+>")
    private val clozeBlank = Regex("\\[(\\.\\.\\.|…)]")
    private val numericEntity = Regex("&#(x?)([0-9a-fA-F]+);")
    private val spaces = Regex("[ \\t\\u00A0]+")
    private val blankLines = Regex("\\n\\s*\\n+")

    private val namedEntities = mapOf(
        "&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
        "&apos;" to "'", "&#39;" to "'", "&amp;" to "&",
    )

    fun toSpeech(html: String, blankWord: String = "ブランク"): String {
        var s = html
        blockRemovals.forEach { s = it.replace(s, "") }
        s = ttsTag.replace(s) { it.groupValues[1] }
        s = soundTag.replace(s, "")
        s = furigana.replace(s, "")
        s = lineBreakTags.replace(s, "\n")
        s = anyTag.replace(s, "")
        s = decodeEntities(s)
        s = clozeBlank.replace(s, blankWord)
        s = spaces.replace(s, " ")
        s = s.lines().joinToString("\n") { it.trim() }
        s = blankLines.replace(s, "\n")
        return s.trim()
    }

    /** 裏面テンプレートの {{FrontSide}} と答えの区切り（<hr id=answer>） */
    private val answerDivider = Regex("(?i)<hr[^>]*\\bid\\s*=\\s*[\"']?answer[\"']?[^>]*>")

    /**
     * 裏面から表面の部分を取り除き、答えだけを読み上げ用テキストにする。
     *
     * Anki の裏面は「{{FrontSide}}<hr id=answer>答え」の形が多い。AnkiDroid の answer_pure は
     * 区切りが見つからないと表面ごと返すので、ここでも取り除く。
     * 1. 区切り線があれば、最後の区切り線より後ろだけを使う
     * 2. それでも先頭が表面と同じ文章なら、その部分を取り除く
     */
    fun answerOnly(answerHtml: String, questionHtml: String, blankWord: String = "ブランク"): String {
        val afterDivider = answerDivider.findAll(answerHtml).lastOrNull()
            ?.let { answerHtml.substring(it.range.last + 1) }
            ?: answerHtml
        val answer = toSpeech(afterDivider, blankWord)
        val question = toSpeech(questionHtml, blankWord)
        if (question.isEmpty() || answer == question) return answer
        val answerLines = answer.lines()
        val questionLines = question.lines()
        if (answerLines.size > questionLines.size && answerLines.take(questionLines.size) == questionLines) {
            return answerLines.drop(questionLines.size).joinToString("\n").trim()
        }
        if (answer.length > question.length && answer.startsWith(question) && answer[question.length].isWhitespace()) {
            return answer.removePrefix(question).trim()
        }
        return answer
    }

    private fun decodeEntities(text: String): String {
        var s = numericEntity.replace(text) { m ->
            val radix = if (m.groupValues[1].isEmpty()) 10 else 16
            m.groupValues[2].toIntOrNull(radix)?.let { String(Character.toChars(it)) } ?: m.value
        }
        // &amp; は最後に置き換える（&amp;lt; を < にしないため）
        namedEntities.forEach { (k, v) -> s = s.replace(k, v) }
        return s
    }
}
