package com.takenakadx.ankihandsfree.anki

import android.net.Uri

/**
 * AnkiDroid の ContentProvider API (com.ichi2.anki.FlashCardsContract) のうち、
 * このアプリで使う定数だけを抜き出したもの。
 *
 * https://github.com/ankidroid/Anki-Android/wiki/AnkiDroid-API
 */
object FlashCardsContract {
    const val ANKIDROID_PACKAGE = "com.ichi2.anki"
    const val AUTHORITY = "com.ichi2.anki.flashcards"
    const val READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    val AUTHORITY_URI: Uri = Uri.parse("content://$AUTHORITY")

    object Note {
        val CONTENT_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "notes")
    }

    object Card {
        const val NOTE_ID = "note_id"
        const val CARD_ORD = "ord"
        const val DECK_ID = "deck_id"
        const val QUESTION = "question"
        const val ANSWER = "answer"
        const val QUESTION_SIMPLE = "question_simple"
        const val ANSWER_SIMPLE = "answer_simple"
        const val ANSWER_PURE = "answer_pure"
    }

    object Deck {
        val CONTENT_ALL_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "decks")
        const val DECK_ID = "deck_id"
        const val DECK_NAME = "deck_name"

        /** JSON 配列 [学習中, 復習, 新規] */
        const val DECK_COUNTS = "deck_count"
    }

    object ReviewInfo {
        val CONTENT_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "schedule")
        const val NOTE_ID = "note_id"
        const val CARD_ORD = "ord"
        const val BUTTON_COUNT = "button_count"
        const val NEXT_REVIEW_TIMES = "next_review_times"
        const val EASE = "answer_ease"
        const val TIME_TAKEN = "time_taken"
    }
}
