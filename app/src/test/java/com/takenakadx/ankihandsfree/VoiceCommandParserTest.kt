package com.takenakadx.ankihandsfree

import com.takenakadx.ankihandsfree.review.Grade
import com.takenakadx.ankihandsfree.voice.VoiceCommand
import com.takenakadx.ankihandsfree.voice.VoiceCommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandParserTest {
    private fun grade(g: Grade) = VoiceCommand.Answer(g)

    @Test
    fun japaneseGrades() {
        assertEquals(grade(Grade.AGAIN), VoiceCommandParser.parse("もう一度"))
        assertEquals(grade(Grade.AGAIN), VoiceCommandParser.parse("わからない"))
        assertEquals(grade(Grade.HARD), VoiceCommandParser.parse("難しい"))
        assertEquals(grade(Grade.GOOD), VoiceCommandParser.parse("正解。"))
        assertEquals(grade(Grade.GOOD), VoiceCommandParser.parse("わかった"))
        assertEquals(grade(Grade.EASY), VoiceCommandParser.parse("簡単"))
    }

    @Test
    fun negativeFormsAreNotGood() {
        assertEquals(grade(Grade.AGAIN), VoiceCommandParser.parse("不正解"))
        assertEquals(grade(Grade.AGAIN), VoiceCommandParser.parse("分からない"))
    }

    @Test
    fun numbersMatchOnlyExactly() {
        assertEquals(grade(Grade.AGAIN), VoiceCommandParser.parse("1"))
        assertEquals(grade(Grade.HARD), VoiceCommandParser.parse("に"))
        assertEquals(grade(Grade.GOOD), VoiceCommandParser.parse("３"))
        assertEquals(grade(Grade.EASY), VoiceCommandParser.parse("よん"))
        assertNull(VoiceCommandParser.parse("にんじん"))
    }

    @Test
    fun repeatWinsOverAgain() {
        assertEquals(VoiceCommand.Repeat, VoiceCommandParser.parse("もう一度読んで"))
        assertEquals(VoiceCommand.Repeat, VoiceCommandParser.parse("繰り返して"))
    }

    @Test
    fun navigation() {
        assertEquals(VoiceCommand.ShowAnswer, VoiceCommandParser.parse("答え"))
        assertEquals(VoiceCommand.ShowAnswer, VoiceCommandParser.parse("次"))
        assertEquals(VoiceCommand.Stop, VoiceCommandParser.parse("終了"))
        assertEquals(VoiceCommand.ShowAnswer, VoiceCommandParser.parse("Show answer"))
    }

    @Test
    fun english() {
        assertEquals(grade(Grade.AGAIN), VoiceCommandParser.parse("Again"))
        assertEquals(grade(Grade.HARD), VoiceCommandParser.parse("hard"))
        assertEquals(grade(Grade.GOOD), VoiceCommandParser.parse("Good"))
        assertEquals(grade(Grade.EASY), VoiceCommandParser.parse("easy"))
        assertEquals(VoiceCommand.Stop, VoiceCommandParser.parse("stop"))
    }

    @Test
    fun usesFirstMatchingCandidate() {
        assertEquals(
            grade(Grade.GOOD),
            VoiceCommandParser.parse(listOf("せーかい", "正解", "簡単")),
        )
        assertNull(VoiceCommandParser.parse(listOf("こんにちは", "")))
    }

    @Test
    fun gradeToEase() {
        assertEquals(1, Grade.AGAIN.toEase(4))
        assertEquals(4, Grade.EASY.toEase(4))
        assertEquals(2, Grade.GOOD.toEase(2))
        assertEquals(2, Grade.HARD.toEase(3))
        assertEquals(3, Grade.EASY.toEase(3))
    }
}
