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
