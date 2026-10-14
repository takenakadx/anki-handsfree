package com.takenakadx.ankihandsfree

import com.takenakadx.ankihandsfree.anki.CardText
import org.junit.Assert.assertEquals
import org.junit.Test

class CardTextTest {
    @Test
    fun stripsStyleTagsAndSound() {
        val html = "<style>.card{color:red}</style><div>apple</div>[sound:apple.mp3]"
        assertEquals("apple", CardText.toSpeech(html))
    }

    @Test
    fun lineBreaksAndEntities() {
        assertEquals("A & B\nC < D", CardText.toSpeech("A &amp; B<br>C &lt; D"))
        assertEquals("&lt;", CardText.toSpeech("&amp;lt;"))
        assertEquals("it's", CardText.toSpeech("it&#39;s"))
    }

    @Test
    fun clozeBlank() {
        val html = "The <span class=cloze>[...]</span> is red."
        assertEquals("The blank is red.", CardText.toSpeech(html, blankWord = "blank"))
    }

    @Test
    fun removesFurigana() {
        assertEquals("漢字", CardText.toSpeech("<ruby>漢字<rt>かんじ</rt></ruby>"))
    }

    @Test
    fun keepsTtsTagText() {
        assertEquals("hello", CardText.toSpeech("[anki:tts lang=en_US]hello[/anki:tts]"))
    }
}
