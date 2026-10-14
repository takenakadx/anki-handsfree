package com.takenakadx.ankihandsfree.anki

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class Deck(
    val id: Long,
    val name: String,
    val learnCount: Int,
    val reviewCount: Int,
    val newCount: Int,
) {
    val dueCount: Int get() = learnCount + reviewCount + newCount
}

/** スケジューラが次に出すカード（中身は [CardContent] で別途取得する）。 */
data class DueCard(
    val noteId: Long,
    val ord: Int,
    val buttonCount: Int,
    val nextReviewTimes: List<String>,
)

data class CardContent(
    val questionHtml: String,
    val answerHtml: String,
)

/**
 * AnkiDroid の ContentProvider を通して、デッキ一覧の取得・次のカードの取得・回答の送信を行う。
 * 学習データは AnkiDroid 側に保存されるので、AnkiWeb 同期もそのまま使える。
 */
class AnkiDroidRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    fun isAnkiDroidInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(FlashCardsContract.ANKIDROID_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, FlashCardsContract.READ_WRITE_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun decks(): List<Deck> = withContext(Dispatchers.IO) {
        resolver.query(FlashCardsContract.Deck.CONTENT_ALL_URI, null, null, null, null)
            ?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val counts = c.stringOrNull(FlashCardsContract.Deck.DECK_COUNTS)
                            ?.let { runCatching { JSONArray(it) }.getOrNull() }
                        add(
                            Deck(
                                id = c.long(FlashCardsContract.Deck.DECK_ID),
                                name = c.stringOrNull(FlashCardsContract.Deck.DECK_NAME).orEmpty(),
                                learnCount = counts?.optInt(0) ?: 0,
                                reviewCount = counts?.optInt(1) ?: 0,
                                newCount = counts?.optInt(2) ?: 0,
                            )
                        )
                    }
                }
            }
            ?.sortedBy { it.name.lowercase() }
            ?: throw AnkiAccessException("AnkiDroid からデッキを取得できませんでした")
    }

    /** 指定デッキで次に出題されるカード。今日の分が終わっていれば null。 */
    suspend fun nextDueCard(deckId: Long): DueCard? = withContext(Dispatchers.IO) {
        resolver.query(
            FlashCardsContract.ReviewInfo.CONTENT_URI,
            null,
            "limit=?,deckID=?",
            arrayOf("1", deckId.toString()),
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val times = c.stringOrNull(FlashCardsContract.ReviewInfo.NEXT_REVIEW_TIMES)
                ?.let { runCatching { JSONArray(it) }.getOrNull() }
                ?.let { arr -> List(arr.length()) { arr.optString(it) } }
                .orEmpty()
            DueCard(
                noteId = c.long(FlashCardsContract.ReviewInfo.NOTE_ID),
                ord = c.int(FlashCardsContract.ReviewInfo.CARD_ORD),
                buttonCount = c.int(FlashCardsContract.ReviewInfo.BUTTON_COUNT).takeIf { it > 0 } ?: 4,
                nextReviewTimes = times,
            )
        } ?: throw AnkiAccessException("AnkiDroid のスケジュールを取得できませんでした")
    }

    suspend fun cardContent(card: DueCard): CardContent = withContext(Dispatchers.IO) {
        val uri = Uri.withAppendedPath(
            FlashCardsContract.Note.CONTENT_URI,
            "${card.noteId}/cards/${card.ord}",
        )
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val question = c.stringOrNull(FlashCardsContract.Card.QUESTION_SIMPLE)
                ?: c.stringOrNull(FlashCardsContract.Card.QUESTION)
                ?: ""
            val answer = c.stringOrNull(FlashCardsContract.Card.ANSWER_PURE)
                ?.takeIf { it.isNotBlank() }
                ?: c.stringOrNull(FlashCardsContract.Card.ANSWER_SIMPLE)
                ?: c.stringOrNull(FlashCardsContract.Card.ANSWER)
                ?: ""
            CardContent(question, answer)
        } ?: throw AnkiAccessException("カードの内容を取得できませんでした")
    }

    /**
     * 回答を送信する。[ease] は AnkiDroid のボタン番号（1 = もう一度 … buttonCount）。
     */
    suspend fun answer(card: DueCard, ease: Int, timeTakenMs: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(FlashCardsContract.ReviewInfo.NOTE_ID, card.noteId)
            put(FlashCardsContract.ReviewInfo.CARD_ORD, card.ord)
            put(FlashCardsContract.ReviewInfo.EASE, ease)
            put(FlashCardsContract.ReviewInfo.TIME_TAKEN, timeTakenMs)
        }
        val updated = resolver.update(FlashCardsContract.ReviewInfo.CONTENT_URI, values, null, null)
        if (updated < 1) throw AnkiAccessException("回答を AnkiDroid に保存できませんでした")
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.int(column: String): Int {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) 0 else getInt(i)
    }
}

class AnkiAccessException(message: String) : Exception(message)
